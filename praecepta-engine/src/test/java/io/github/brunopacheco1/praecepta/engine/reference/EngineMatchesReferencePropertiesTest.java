package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import net.jqwik.api.Disabled;
import net.jqwik.api.EdgeCasesMode;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidPraeceptaException;
import io.github.brunopacheco1.praecepta.engine.exceptions.MissingOutputException;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Aggregator;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Outcome;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Differential tests: the engine must agree with {@link ReferenceMatcher} (strict DMN) on random
 * tables and inputs. This is the safety net for replacing the engine core.
 *
 * <p>Active properties describe behaviour the current engine already gets right. Properties marked
 * {@code @Disabled("Phase 1 …")} are the strict-DMN acceptance criteria for Phase 1; enable each one
 * as the behaviour lands. Still to be written once the engine API can express them: PRIORITY and
 * OUTPUT_ORDER (need DMN output value lists).
 */
class EngineMatchesReferencePropertiesTest {

    private static final int TRIES = 1000;

    @Provide
    Arbitrary<DecisionTableScenario> scenarios() {
        return DecisionTableScenario.arbitrary();
    }

    // ---- matching -------------------------------------------------------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_returns_exactly_the_matching_rules(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.COLLECT).orElseThrow();
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.COLLECT);
        for (var input : s.inputs()) {
            Assertions.assertThat(s.ids(evaluate(engine, input)))
                    .as("input %s", input)
                    .containsExactlyInAnyOrderElementsOf(s.ruleIds(reference.matches(input)));
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void rule_order_returns_matches_in_table_order(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.RULE_ORDER).orElseThrow();
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.RULE_ORDER);
        for (var input : s.inputs()) {
            Assertions.assertThat(s.ids(evaluate(engine, input)))
                    .as("input %s", input)
                    .containsExactlyElementsOf(s.ids(hits(reference.evaluate(input))));
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_aggregations_match_dmn(@ForAll("scenarios") DecisionTableScenario s) {
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.COLLECT);
        for (var aggregation : List.of(AggregationOperator.SUM, AggregationOperator.MIN, AggregationOperator.MAX)) {
            var engine = new PraeceptaEngine<PraeceptaInput, BigDecimal>(
                    HitPolicy.COLLECT, aggregation, input -> input, numericOutput());
            engine.register(s.rules());
            for (var input : s.inputs()) {
                BigDecimal actual;
                try {
                    actual = engine.evaluate(new PraeceptaInput(input)).getFirst();
                } catch (MissingOutputException e) {
                    actual = null;
                }
                var expected = reference.aggregate(input, DecisionTableScenario.NUMBER, Aggregator.valueOf(aggregation.name()));
                Assertions.assertThat(actual).as("%s of input %s", aggregation, input).isEqualTo(expected);
            }
        }
    }

    // ---- single-hit policies, where the current engine accepts the table ------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void first_returns_the_first_match_in_table_order(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.FIRST);
        Assume.that(engine.isPresent()); // current engine rejects tables with unreachable rules
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.FIRST);
        for (var input : s.inputs()) {
            Assertions.assertThat(s.ids(evaluate(engine.get(), input)))
                    .as("input %s", input)
                    .containsExactlyElementsOf(s.ids(hits(reference.evaluate(input))));
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void unique_agrees_when_at_most_one_rule_matches(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.UNIQUE);
        Assume.that(engine.isPresent());
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.UNIQUE);
        for (var input : s.inputs()) {
            if (reference.evaluate(input) instanceof Outcome.Hits hits) {
                Assertions.assertThat(s.ids(evaluate(engine.get(), input)))
                        .as("input %s", input)
                        .containsExactlyElementsOf(s.ids(hits.outputs()));
            }
        }
    }

    // ---- Phase 1 acceptance criteria (strict DMN) -----------------------------------------

    @Disabled("Phase 1: unreachable rules must be reported, not rejected; DMN allows them")
    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void every_table_is_accepted_under_every_hit_policy(@ForAll("scenarios") DecisionTableScenario s) {
        for (var policy : HitPolicy.values()) {
            Assertions.assertThat(engine(s, policy)).as("policy %s", policy).isPresent();
        }
    }

    @Disabled("Phase 1: UNIQUE currently returns the lowest rule id instead of failing on overlap")
    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void unique_signals_overlapping_rules(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.UNIQUE).orElseThrow();
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.UNIQUE);
        for (var input : s.inputs()) {
            if (reference.evaluate(input) instanceof Outcome.Violation) {
                Assertions.assertThatThrownBy(() -> engine.evaluate(new PraeceptaInput(input)))
                        .as("input %s", input)
                        .isNotInstanceOf(MissingOutputException.class);
            }
        }
    }

    @Disabled("Phase 1: ANY currently ignores whether matching rules agree on their outputs")
    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void any_returns_the_shared_output_or_signals_disagreement(@ForAll("scenarios") DecisionTableScenario s) {
        var withoutIds = s.rules().stream()
                .map(rule -> new io.github.brunopacheco1.praecepta.engine.beans.Praeceptum(rule.priority(), rule.input(),
                        new PraeceptaOutput(Map.of(DecisionTableScenario.OUT, rule.output().values().get(DecisionTableScenario.OUT)))))
                .toList();
        var engine = new PraeceptaEngine<PraeceptaInput, PraeceptaOutput>(HitPolicy.ANY, input -> input, output -> output);
        engine.register(withoutIds);
        var reference = new ReferenceMatcher(withoutIds, HitPolicy.ANY);
        for (var input : s.inputs()) {
            switch (reference.evaluate(input)) {
                case Outcome.Hits hits -> Assertions.assertThat(evaluate(engine, input)).isEqualTo(hits.outputs());
                case Outcome.Violation violation -> Assertions.assertThatThrownBy(() -> engine.evaluate(new PraeceptaInput(input)))
                        .as("input %s", input)
                        .isNotInstanceOf(MissingOutputException.class);
            }
        }
    }

    @Disabled("Phase 1: no match must yield an empty result, not MissingOutputException")
    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void no_match_yields_an_empty_result(@ForAll("scenarios") DecisionTableScenario s) {
        var engine = engine(s, HitPolicy.COLLECT).orElseThrow();
        for (var input : s.inputs()) {
            Assertions.assertThatCode(() -> engine.evaluate(new PraeceptaInput(input))).doesNotThrowAnyException();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Optional<PraeceptaEngine<PraeceptaInput, PraeceptaOutput>> engine(DecisionTableScenario s, HitPolicy policy) {
        var engine = new PraeceptaEngine<PraeceptaInput, PraeceptaOutput>(policy, input -> input, output -> output);
        try {
            engine.register(s.rules());
            return Optional.of(engine);
        } catch (InvalidPraeceptaException e) {
            return Optional.empty();
        }
    }

    private static List<PraeceptaOutput> evaluate(PraeceptaEngine<PraeceptaInput, PraeceptaOutput> engine, Map<String, String> input) {
        try {
            return engine.evaluate(new PraeceptaInput(input));
        } catch (MissingOutputException e) {
            return List.of();
        }
    }

    private static List<PraeceptaOutput> hits(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Hits hits -> hits.outputs();
            case Outcome.Violation violation -> throw new AssertionError("unexpected violation: " + violation.reason());
        };
    }

    private static OutputStrategy<BigDecimal> numericOutput() {
        return new OutputStrategy<>() {
            @Override
            public BigDecimal transform(PraeceptaOutput output) {
                return new BigDecimal(output.values().get(DecisionTableScenario.NUMBER));
            }

            @Override
            public BigDecimal sum(BigDecimal a, BigDecimal b) {
                return a.add(b);
            }

            @Override
            public BigDecimal min(BigDecimal a, BigDecimal b) {
                return a.min(b);
            }

            @Override
            public BigDecimal max(BigDecimal a, BigDecimal b) {
                return a.max(b);
            }
        };
    }
}
