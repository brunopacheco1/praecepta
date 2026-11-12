/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;

import io.github.brunopacheco1.praecepta.engine.ForcedMaskForm;
import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Aggregator;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Finding;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Outcome;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Differential tests: on random typed tables and inputs, the engine must agree with
 * {@link ReferenceMatcher}, an executable statement of strict DMN 1.3 semantics with FEEL unary tests.
 *
 * <p>{@code perColumn} alternates between the two ways of binding inputs: typed getters, and text
 * maps parsed per column type. Each scenario also picks the form the engine stores its masks in
 * (dense, chosen by density, sparse wherever possible, or a mix of both), and the wide scenarios have up to 200
 * rules, so masks of several words, sparse and dense, are checked against the reference.
 */
class EngineMatchesReferencePropertiesTest {

    private static final int TRIES = 1000;
    /** Static analysis is checked by brute force over every input region, so fewer tries. */
    private static final int ANALYSIS_TRIES = 300;
    /** Tables of up to 200 rules: each try evaluates every hit policy. */
    private static final int WIDE_TRIES = 200;
    private static final int WIDE_ANALYSIS_TRIES = 50;
    /**
     * The most cells the reference's brute force evaluates for a wide table's analysis (its representative inputs
     * times its rules): now and then, three columns of times and date-times with and without a zone give hundreds
     * of thousands of inputs, which take the reference minutes, so such a try is skipped.
     */
    private static final long MAX_WIDE_ANALYSIS_WORK = 5_000_000L;

    @Provide
    Arbitrary<DecisionTableScenario> scenarios() {
        return DecisionTableScenario.arbitrary();
    }

    @Provide
    Arbitrary<DecisionTableScenario> wideScenarios() {
        return DecisionTableScenario.wide();
    }

    @Provide
    Arbitrary<DecisionTableScenario> temporalScenarios() {
        return DecisionTableScenario.temporal();
    }

    @Provide
    Arbitrary<DecisionTableScenario> scenariosWithValueLists() {
        return DecisionTableScenario.withStringValueLists();
    }

    /** A DMN output value list for the {@code out} column: some permutation of x, y, z. */
    @Provide
    Arbitrary<List<String>> priorities() {
        return Arbitraries.shuffle(DecisionTableScenario.OUT_VALUES);
    }

    // ---- hit policies ---------------------------------------------------------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_returns_every_matching_rule_in_rule_order(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        assertSameHits(s, s.rules(), HitPolicy.COLLECT, List.of(), perColumn);
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
        assertSameHits(s, DecisionTableScenario.outOnly(s.rules()), HitPolicy.ANY, List.of(), perColumn);
    }

    /**
     * The table's one output, the number {@code n}, is now and then an expression, which may give a missing value:
     * SUM, MIN and MAX are then missing, as FEEL's {@code sum}, {@code min} and {@code max} are null for a list
     * that holds null; COUNT counts it.
     */
    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void collect_aggregations_match_dmn(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        var rules = DecisionTableScenario.numberOnly(s.rules());
        var reference = s.reference(rules, HitPolicy.COLLECT, Map.of(), true, List.of(DecisionTableScenario.NUMBER));
        for (var aggregation : AggregationOperator.values()) {
            var builder = PraeceptaEngine.<Map<String, Object>, Optional<BigDecimal>>builder()
                    .table(s.numberTable(rules, aggregation))
                    .output(numericOutput());
            configure(builder, s, perColumn);
            var engine = builder.build();
            for (var input : s.inputs()) {
                if (reference.invalidColumn(input) != null) {
                    Assertions.assertThatThrownBy(() -> engine.evaluate(input)).isInstanceOf(InvalidInputException.class);
                    continue;
                }
                // no match gives the default output if there is one, else no output, whatever the aggregation; a
                // matching rule whose output is missing makes SUM, MIN and MAX missing
                var expected = reference.aggregate(input, DecisionTableScenario.NUMBER, Aggregator.valueOf(aggregation.name()))
                        .map(total -> List.of(Optional.ofNullable(total.value())))
                        .orElse(List.of());
                Assertions.assertThat(engine.evaluate(input)).as("%s of input %s", aggregation, input).isEqualTo(expected);
            }
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void every_table_is_accepted_under_every_hit_policy(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        for (var policy : HitPolicy.values()) {
            Assertions.assertThatCode(() -> engine(s, s.rules(), policy, priority, perColumn))
                    .as("policy %s", policy)
                    .doesNotThrowAnyException();
        }
    }

    // ---- times and date-times, with and without a zone, single and in lists ------------------------

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void temporal_tables_match_the_reference_under_every_hit_policy(@ForAll("temporalScenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        for (var policy : HitPolicy.values()) {
            var rules = policy == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
            assertSameHits(s, rules, policy, priority, perColumn);
        }
    }

    // ---- tables of several words -----------------------------------------------------------------

    /**
     * ANY over outputs that read no input: the engine compares the matches with the masks of the rules
     * that have the same outputs, which span several words here, stored in every form.
     */
    @Property(tries = WIDE_TRIES, edgeCases = EdgeCasesMode.NONE)
    void wide_any_tables_compare_outputs_with_the_masks_of_equal_outputs(@ForAll("wideScenarios") DecisionTableScenario s,
            @ForAll boolean perColumn) {
        var rules = DecisionTableScenario.outOnlyValues(s.rules());
        for (var form : ForcedMaskForm.values()) {
            assertSameHits(s.withMaskForm(form), rules, HitPolicy.ANY, List.of(), perColumn);
        }
    }

    @Property(tries = TRIES, edgeCases = EdgeCasesMode.NONE)
    void any_tables_compare_outputs_with_the_masks_of_equal_outputs(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        assertSameHits(s, DecisionTableScenario.outOnlyValues(s.rules()), HitPolicy.ANY, List.of(), perColumn);
    }

    @Property(tries = WIDE_TRIES, edgeCases = EdgeCasesMode.NONE)
    void wide_tables_match_the_reference_under_every_hit_policy(@ForAll("wideScenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        for (var policy : HitPolicy.values()) {
            var rules = policy == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
            assertSameHits(s, rules, policy, priority, perColumn);
        }
    }

    @Property(tries = WIDE_ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void wide_tables_validation_report_matches_the_reference(@ForAll("wideScenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        var reference = s.reference(s.rules(), HitPolicy.COLLECT, Map.of(), perColumn);
        long work = s.rules().size();
        for (var column : s.columns()) {
            work *= reference.representativeValues(column, true).size();
        }
        Assume.that(work <= MAX_WIDE_ANALYSIS_WORK);
        assertSameFindings(s, priority, perColumn);
    }

    // ---- static analysis ------------------------------------------------------------------------

    /**
     * Also with map-based inputs ({@code perColumn} false), whose time and date-time columns take values
     * with a time zone and without: the analysis then looks for overlaps and gaps among both kinds.
     */
    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void validation_report_matches_the_reference(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        assertSameFindings(s, priority, perColumn);
    }

    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void temporal_tables_validation_report_matches_the_reference(@ForAll("temporalScenarios") DecisionTableScenario s,
            @ForAll("priorities") List<String> priority, @ForAll boolean perColumn) {
        assertSameFindings(s, priority, perColumn);
    }

    private static void assertSameFindings(DecisionTableScenario s, List<String> priority, boolean perColumn) {
        for (var policy : List.of(HitPolicy.UNIQUE, HitPolicy.ANY, HitPolicy.FIRST, HitPolicy.PRIORITY)) {
            var rules = policy == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
            var engine = engine(s, rules, policy, priority, perColumn);
            var reference = s.reference(rules, policy, DecisionTableScenario.outputPriority(policy, priority), perColumn);
            var actual = engine.validationReport().issues().stream()
                    .filter(issue -> issue.kind() != Kind.GAP && issue.kind() != Kind.UNREACHABLE_DEFAULT_OUTPUT)
                    .map(issue -> new Finding(issue.kind().name(), issue.ruleIds()))
                    .collect(Collectors.toSet());
            Assertions.assertThat(actual).as("policy %s", policy)
                    .containsExactlyInAnyOrderElementsOf(reference.expectedFindings(s.columns()));
            // not logging the report (as the integrations build their engines) leaves it as it is
            var quiet = PraeceptaEngine.<Map<String, Object>, PraeceptaOutput>builder()
                    .table(s.table(rules, policy, priority))
                    .output(output -> output)
                    .logValidationReport(false);
            configure(quiet, s, perColumn);
            Assertions.assertThat(quiet.build().validationReport()).as("policy %s, not logged", policy)
                    .isEqualTo(engine.validationReport());
        }
    }

    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void gaps_are_reported_exactly_when_valid_inputs_go_unmatched(@ForAll("scenariosWithValueLists") DecisionTableScenario s,
            @ForAll boolean perColumn) {
        var reference = s.reference(s.rules(), HitPolicy.COLLECT, Map.of(), perColumn);
        Assertions.assertThat(reference.gapAnalysisApplies(s.columns())).isTrue();
        assertGapsMatchUncoveredInputs(s, reference, perColumn);
    }

    /**
     * Also without value lists: number, date and time columns, and string columns that compare; with
     * map-based inputs, among times and date-times of both kinds.
     */
    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void gaps_are_reported_exactly_wherever_gap_analysis_applies(@ForAll("scenarios") DecisionTableScenario s, @ForAll boolean perColumn) {
        var reference = s.reference(s.rules(), HitPolicy.COLLECT, Map.of(), perColumn);
        if (reference.gapAnalysisApplies(s.columns())) {
            assertGapsMatchUncoveredInputs(s, reference, perColumn);
        }
    }

    private static void assertGapsMatchUncoveredInputs(DecisionTableScenario s, ReferenceMatcher reference, boolean perColumn) {
        var engine = engine(s, s.rules(), HitPolicy.COLLECT, List.of(), perColumn);
        var gaps = engine.validationReport().issues(Kind.GAP);
        var uncovered = reference.uncoveredInputs(s.columns());

        Assertions.assertThat(gaps.isEmpty()).as("gaps %s vs uncovered %s", gaps, uncovered).isEqualTo(uncovered.isEmpty());
        for (var gap : gaps) {
            // the example's columns are fixed; every other column may take any valid value
            for (var input : completions(gap.example(), s, reference)) {
                Assertions.assertThat(reference.matches(input)).as("gap %s, input %s", gap.detail(), input).isEmpty();
                // no rule matches: no output, or the default output, which does not hide the gap from the report
                var noMatch = reference.defaultOutput() == null ? List.<PraeceptaOutput>of() : List.of(reference.defaultOutput());
                Assertions.assertThat(engine.evaluate(input)).as("gap %s, input %s", gap.detail(), input).isEqualTo(noMatch);
            }
        }
    }

    /**
     * DMN 1.5, 10.3.2.10: "A complete decision table SHALL NOT specify a default output value." The engine reports a
     * default output exactly when every valid input, a missing value in any column included, matches one of the rules
     * its analysis sees; half of the tables get a rule of {@code -} cells, which makes them complete.
     */
    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void a_default_output_is_reported_unreachable_exactly_when_every_input_matches_a_rule(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll boolean catchAll, @ForAll boolean perColumn) {
        assertUnreachableDefaultMatchesCompleteness(s, catchAll, perColumn);
    }

    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void a_temporal_tables_default_output_is_reported_unreachable_exactly_when_it_is_complete(
            @ForAll("temporalScenarios") DecisionTableScenario s, @ForAll boolean catchAll, @ForAll boolean perColumn) {
        assertUnreachableDefaultMatchesCompleteness(s, catchAll, perColumn);
    }

    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void a_default_output_over_declared_values_is_reported_unreachable_exactly_when_it_is_complete(
            @ForAll("scenariosWithValueLists") DecisionTableScenario s, @ForAll boolean catchAll, @ForAll boolean perColumn) {
        assertUnreachableDefaultMatchesCompleteness(s, catchAll, perColumn);
    }

    private static void assertUnreachableDefaultMatchesCompleteness(DecisionTableScenario s, boolean catchAll, boolean perColumn) {
        var rules = catchAll ? s.withCatchAll(s.rules()) : s.rules();
        var engine = engine(s, rules, HitPolicy.COLLECT, List.of(), perColumn);
        var reference = s.reference(rules, HitPolicy.COLLECT, Map.of(), perColumn);
        var reported = engine.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT);
        boolean expected = reference.defaultOutput() != null && reference.everyInputMatchesAnAnalysedRule(s.columns());
        Assertions.assertThat(!reported.isEmpty()).as("reported %s, default %s", reported, reference.defaultOutput()).isEqualTo(expected);
        if (!reported.isEmpty()) {
            Assertions.assertThat(reference.everyInputMatches(s.columns())).as("a reported table is complete").isTrue();
        }
    }

    @Property(tries = ANALYSIS_TRIES, edgeCases = EdgeCasesMode.NONE)
    void no_gaps_are_reported_when_a_constrained_string_column_has_no_value_list(@ForAll("scenarios") DecisionTableScenario s) {
        var reference = s.reference(s.rules(), HitPolicy.COLLECT, Map.of());
        if (!reference.gapAnalysisApplies(s.columns())) {
            Assertions.assertThat(engine(s, s.rules(), HitPolicy.COLLECT, List.of(), true).validationReport().issues(Kind.GAP)).isEmpty();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void assertSameHits(DecisionTableScenario s, List<Praeceptum> rules, HitPolicy policy,
            List<String> priority, boolean perColumn) {
        var engine = engine(s, rules, policy, priority, perColumn);
        var reference = s.reference(rules, policy, DecisionTableScenario.outputPriority(policy, priority));
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
                case Outcome.InvalidInput invalid -> Assertions.assertThatThrownBy(() -> engine.evaluate(input))
                        .as("%s, input %s", policy, input)
                        .isInstanceOf(InvalidInputException.class);
            }
        }
    }

    private static PraeceptaEngine<Map<String, Object>, PraeceptaOutput> engine(DecisionTableScenario s,
            List<Praeceptum> rules, HitPolicy policy, List<String> priority, boolean perColumn) {
        // the scenario's table: in strict FEEL when its cells name other columns; it carries the priority list
        var builder = PraeceptaEngine.<Map<String, Object>, PraeceptaOutput>builder()
                .table(s.table(rules, policy, priority))
                .output(output -> output);
        configure(builder, s, perColumn);
        return builder.build();
    }

    private static <O> void configure(PraeceptaEngine.Builder<Map<String, Object>, O> builder, DecisionTableScenario s, boolean perColumn) {
        s.applyMaskForm(builder);
        if (perColumn) {
            s.bindColumns(builder);
        } else {
            builder.inputs(input -> new PraeceptaInput(DecisionTableScenario.asText(input)));
            s.types().forEach(builder::columnType);
        }
        s.allowedValues().forEach(builder::allowedValues);
    }

    /** Every valid completion of a gap example: missing columns take each representative valid value. */
    private static List<Map<String, Object>> completions(Map<String, Object> example, DecisionTableScenario s, ReferenceMatcher reference) {
        var missing = s.columns().stream().filter(column -> !example.containsKey(column)).toList();
        return reference.representativeInputs(example, missing, false).stream()
                .filter(input -> reference.invalidColumn(input) == null)
                .toList();
    }

    /**
     * The number output, empty where it is missing (a computed output may be). The engine combines only outputs
     * that are not missing, so combining an empty one fails.
     */
    private static OutputStrategy<Optional<BigDecimal>> numericOutput() {
        return new OutputStrategy<>() {
            @Override
            public Optional<BigDecimal> transform(PraeceptaOutput output) {
                return Optional.ofNullable(output.values().get(DecisionTableScenario.NUMBER)).map(BigDecimal::new);
            }

            @Override
            public Optional<BigDecimal> sum(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                return combine(a, b, BigDecimal::add);
            }

            @Override
            public Optional<BigDecimal> count(int matches) {
                return Optional.of(BigDecimal.valueOf(matches));
            }

            @Override
            public Optional<BigDecimal> min(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                return combine(a, b, BigDecimal::min);
            }

            @Override
            public Optional<BigDecimal> max(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                return combine(a, b, BigDecimal::max);
            }
        };
    }

    private static Optional<BigDecimal> combine(Optional<BigDecimal> a, Optional<BigDecimal> b, BinaryOperator<BigDecimal> operator) {
        return Optional.of(operator.apply(a.orElseThrow(), b.orElseThrow()));
    }
}
