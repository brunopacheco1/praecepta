package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Aggregator;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Finding;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Outcome;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Differential tests: on random tables and inputs, the engine must agree with {@link ReferenceMatcher},
 * an executable statement of strict DMN 1.3 semantics. This is the safety net for replacing the core.
 *
 * <p>{@code perColumn} alternates between the two ways of binding inputs, so both are covered.
 */
class EngineMatchesReferencePropertiesTest {

    private static final int TRIES = 1000;

    @Provide
    Arbitrary<DecisionTableScenario> scenarios() {
        return DecisionTableScenario.arbitrary();
    }

    /** A DMN output value list for the {@code out} column: some permutation of x, y, z. */
    @Provide
    Arbitrary<List<String>> priorities() {
        return Arbitraries.shuffle(DecisionTableScenario.OUT_VALUES);
    }

    // ---- multi-hit policies ------------------------------------------------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_returns_every_matching_rule_in_rule_order(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        var engine = engine(s, s.rules(), HitPolicy.COLLECT, List.of(), perColumn);
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.COLLECT);
        for (var input : s.inputs()) {
            Assertions.assertThat(s.ids(engine.evaluate(input)))
                    .as("input %s", input)
                    .containsExactlyElementsOf(s.ruleIds(reference.matches(input)));
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void rule_order_returns_matches_in_rule_order(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.RULE_ORDER, List.of(), perColumn);
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void output_order_sorts_matches_by_output_priority(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.OUTPUT_ORDER, priority, perColumn);
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_aggregations_match_dmn(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.COLLECT);
        for (var aggregation : AggregationOperator.values()) {
            var builder = PraeceptaEngine.<Map<String, String>, BigDecimal>builder()
                    .hitPolicy(HitPolicy.COLLECT)
                    .aggregation(aggregation)
                    .output(numericOutput())
                    .rules(s.rules());
            bindInputs(builder, s.columns(), perColumn);
            var engine = builder.build();
            for (var input : s.inputs()) {
                var actual = engine.evaluate(input);
                var expected = reference.aggregate(input, DecisionTableScenario.NUMBER, Aggregator.valueOf(aggregation.name()));
                Assertions.assertThat(actual).as("%s of input %s", aggregation, input)
                        .isEqualTo(expected == null ? List.of() : List.of(expected));
            }
        }
    }

    // ---- single-hit policies -------------------------------------------------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void first_returns_the_first_match_in_rule_order(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.FIRST, List.of(), perColumn);
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void priority_returns_the_highest_priority_match(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.PRIORITY, priority, perColumn);
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void unique_returns_the_single_match_or_signals_overlap(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.UNIQUE, List.of(), perColumn);
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void any_returns_the_shared_output_or_signals_disagreement(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        // without the unique id output, overlapping rules can genuinely agree
        assertSameHits(s, withoutIds(s.rules()), HitPolicy.ANY, List.of(), perColumn);
    }

    // ---- table-level guarantees ---------------------------------------------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void every_table_is_accepted_under_every_hit_policy(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        for (var policy : HitPolicy.values()) {
            Assertions.assertThatCode(() -> engine(s, s.rules(), policy, priority, perColumn))
                    .as("policy %s", policy)
                    .doesNotThrowAnyException();
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void no_match_yields_an_empty_result(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        var reference = new ReferenceMatcher(s.rules(), HitPolicy.COLLECT);
        for (var policy : HitPolicy.values()) {
            var engine = engine(s, s.rules(), policy, priority, perColumn);
            for (var input : s.inputs()) {
                if (reference.matches(input).isEmpty()) {
                    Assertions.assertThat(engine.evaluate(input)).as("%s, input %s", policy, input).isEmpty();
                }
            }
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void validation_report_matches_the_reference(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority) {
        for (var policy : List.of(HitPolicy.UNIQUE, HitPolicy.ANY, HitPolicy.FIRST, HitPolicy.PRIORITY)) {
            var rules = policy == HitPolicy.ANY ? withoutIds(s.rules()) : s.rules();
            var engine = engine(s, rules, policy, priority, true);
            var reference = new ReferenceMatcher(rules, policy, priorityMap(policy, priority));
            var actual = engine.validationReport().issues().stream()
                    .map(issue -> new Finding(issue.kind().name(), issue.ruleIds()))
                    .collect(Collectors.toSet());
            Assertions.assertThat(actual).as("policy %s", policy)
                    .containsExactlyInAnyOrderElementsOf(reference.expectedFindings(s.columns()));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void assertSameHits(DecisionTableScenario s, List<Praeceptum> rules, HitPolicy policy,
            List<String> priority, boolean perColumn) {
        var engine = engine(s, rules, policy, priority, perColumn);
        var reference = new ReferenceMatcher(rules, policy, priorityMap(policy, priority));
        for (var input : s.inputs()) {
            switch (reference.evaluate(input)) {
                case Outcome.Hits hits -> Assertions.assertThat(engine.evaluate(input))
                        .as("%s, input %s", policy, input)
                        .containsExactlyElementsOf(hits.outputs());
                case Outcome.Violation violation -> Assertions.assertThatThrownBy(() -> engine.evaluate(input))
                        .as("%s, input %s", policy, input)
                        .isInstanceOfSatisfying(HitPolicyViolationException.class, e -> Assertions
                                .assertThat(e.getRuleIds())
                                .containsExactlyElementsOf(reference.matches(input).stream().map(Praeceptum::priority).toList()));
            }
        }
    }

    private static PraeceptaEngine<Map<String, String>, PraeceptaOutput> engine(DecisionTableScenario s,
            List<Praeceptum> rules, HitPolicy policy, List<String> priority, boolean perColumn) {
        var builder = PraeceptaEngine.<Map<String, String>, PraeceptaOutput>builder()
                .hitPolicy(policy)
                .output(output -> output)
                .rules(rules);
        if (policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER) {
            builder.outputPriority(DecisionTableScenario.OUT, priority);
        }
        bindInputs(builder, s.columns(), perColumn);
        return builder.build();
    }

    private static <O> void bindInputs(PraeceptaEngine.Builder<Map<String, String>, O> builder, List<String> columns, boolean perColumn) {
        if (perColumn) {
            for (var column : columns) {
                builder.input(column, input -> input.get(column));
            }
        } else {
            builder.inputs(PraeceptaInput::new);
        }
    }

    private static Map<String, List<String>> priorityMap(HitPolicy policy, List<String> priority) {
        return policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER
                ? Map.of(DecisionTableScenario.OUT, priority)
                : Map.of();
    }

    private static List<Praeceptum> withoutIds(List<Praeceptum> rules) {
        return rules.stream()
                .map(rule -> new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(
                        Map.of(DecisionTableScenario.OUT, rule.output().values().get(DecisionTableScenario.OUT)))))
                .toList();
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
