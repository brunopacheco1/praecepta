/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Readable examples of the engine's behaviour. Broad correctness is covered by
 * {@code EngineMatchesReferencePropertiesTest}; these document the API and DMN semantics.
 */
class PraeceptaEngineTest {

    record Order(String country, String segment) {
    }

    record Discount(String grade, int percent) {
    }

    //            country  segment  → grade  percent
    // rule 10:   PT       -          A       10
    // rule 20:   PT,ES    SMB        B        5
    // rule 30:   -        SMB        A        1
    private static final List<Praeceptum> RULES = List.of(
            rule(10, "PT", null, "A", "10"),
            rule(20, "PT,ES", "SMB", "B", "5"),
            rule(30, null, "SMB", "A", "1"));

    private static final Order PT_SMB = new Order("PT", "SMB");     // rules 10, 20, 30
    private static final Order ES_SMB = new Order("ES", "SMB");     // rules 20, 30
    private static final Order PT_CORP = new Order("PT", "CORP");   // rule 10
    private static final Order FR_CORP = new Order("FR", "CORP");   // none
    private static final Order MISSING_SMB = new Order(null, "SMB"); // rule 30

    @Nested
    class HitPolicies {

        @Test
        void first_returns_the_first_matching_rule_in_rule_order() {
            var engine = engine(HitPolicy.FIRST).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly(new Discount("A", 10));
            Assertions.assertThat(engine.evaluate(ES_SMB)).containsExactly(new Discount("B", 5));
        }

        @Test
        void unique_returns_the_only_match_and_rejects_overlaps() {
            var engine = engine(HitPolicy.UNIQUE).build();
            Assertions.assertThat(engine.evaluate(PT_CORP)).containsExactly(new Discount("A", 10));
            Assertions.assertThatThrownBy(() -> engine.evaluate(ES_SMB))
                    .isInstanceOfSatisfying(HitPolicyViolationException.class, e -> {
                        Assertions.assertThat(e.getHitPolicy()).isEqualTo(HitPolicy.UNIQUE);
                        Assertions.assertThat(e.getRuleIds()).containsExactly(20, 30);
                    });
        }

        @Test
        void any_requires_overlapping_rules_to_agree() {
            var gradeOnly = RULES.stream()
                    .map(r -> new Praeceptum(r.priority(), r.input(),
                            new PraeceptaOutput(Map.of("grade", r.output().values().get("grade"), "percent", "0"))))
                    .toList();
            var engine = engine(HitPolicy.ANY).rules(gradeOnly).build();
            Assertions.assertThat(engine.evaluate(MISSING_SMB)).containsExactly(new Discount("A", 0));
            Assertions.assertThatThrownBy(() -> engine.evaluate(ES_SMB)) // B vs A
                    .isInstanceOf(HitPolicyViolationException.class);
        }

        @Test
        void priority_follows_output_value_lists_and_breaks_ties_by_rule_order() {
            var engine = engine(HitPolicy.PRIORITY).outputPriority("grade", List.of("B", "A")).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly(new Discount("B", 5));
            Assertions.assertThat(engine.evaluate(MISSING_SMB)).containsExactly(new Discount("A", 1));
        }

        @Test
        void output_order_sorts_all_matches_by_priority() {
            var engine = engine(HitPolicy.OUTPUT_ORDER).outputPriority("grade", List.of("B", "A")).build();
            Assertions.assertThat(engine.evaluate(PT_SMB))
                    .containsExactly(new Discount("B", 5), new Discount("A", 10), new Discount("A", 1));
        }

        @ParameterizedTest
        @EnumSource(names = {"RULE_ORDER", "COLLECT"})
        void rule_order_and_collect_return_all_matches_in_rule_order(HitPolicy policy) {
            var engine = engine(policy).build();
            Assertions.assertThat(engine.evaluate(PT_SMB))
                    .containsExactly(new Discount("A", 10), new Discount("B", 5), new Discount("A", 1));
        }

        @Test
        void collect_can_aggregate() {
            var engine = engine(HitPolicy.COLLECT).aggregation(AggregationOperator.SUM).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly(new Discount("*", 16));
            Assertions.assertThat(engine.evaluate(FR_CORP)).isEmpty();
        }

        @Test
        void collect_can_count_the_matching_rules() {
            var engine = engine(HitPolicy.COLLECT).aggregation(AggregationOperator.COUNT).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly(new Discount("#", 3));
            Assertions.assertThat(engine.evaluate(ES_SMB)).containsExactly(new Discount("#", 2));
        }

        /** DMN 1.5, 10.3.2.10, step 2: with no match and no default output the result is null, before any aggregation. */
        @ParameterizedTest
        @EnumSource(AggregationOperator.class)
        void every_aggregation_gives_no_output_when_no_rule_matches(AggregationOperator aggregation) {
            var engine = engine(HitPolicy.COLLECT).aggregation(aggregation).build();
            Assertions.assertThat(engine.evaluate(FR_CORP)).as("%s", aggregation).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(HitPolicy.class)
        void no_match_is_an_empty_result_under_every_policy(HitPolicy policy) {
            var builder = engine(policy);
            if (policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER) {
                builder.outputPriority("grade", List.of("A", "B"));
            }
            Assertions.assertThat(builder.build().evaluate(FR_CORP)).isEmpty();
        }
    }

    @Nested
    class Inputs {

        @Test
        void missing_and_blank_inputs_only_match_wildcards() {
            var engine = engine(HitPolicy.COLLECT).build();
            Assertions.assertThat(engine.evaluate(MISSING_SMB)).containsExactly(new Discount("A", 1));
            Assertions.assertThat(engine.evaluate(new Order("  ", "SMB"))).containsExactly(new Discount("A", 1));
        }

        @Test
        void map_based_inputs_behave_like_per_column_binding() {
            var perColumn = engine(HitPolicy.COLLECT).build();
            var byMap = PraeceptaEngine.<Order, Discount>builder()
                    .hitPolicy(HitPolicy.COLLECT)
                    .inputs(order -> {
                        var values = new HashMap<String, String>();
                        values.put("country", order.country());
                        values.put("segment", order.segment());
                        return new PraeceptaInput(values);
                    })
                    .output(PraeceptaEngineTest::discount)
                    .rules(RULES)
                    .build();
            for (var order : List.of(PT_SMB, ES_SMB, PT_CORP, FR_CORP, MISSING_SMB)) {
                Assertions.assertThat(byMap.evaluate(order)).isEqualTo(perColumn.evaluate(order));
            }
        }

        @Test
        void a_rule_testing_an_undeclared_column_is_rejected() {
            var builder = PraeceptaEngine.<Order, Discount>builder()
                    .hitPolicy(HitPolicy.FIRST)
                    .input("country", Order::country)
                    .output(PraeceptaEngineTest::discount)
                    .rules(RULES);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOf(InvalidDecisionTableException.class)
                    .hasMessageContaining("segment");
        }

        @Test
        void an_empty_table_never_matches() {
            Assertions.assertThat(engine(HitPolicy.FIRST).rules(List.of()).build().evaluate(PT_SMB)).isEmpty();
        }
    }

    @Nested
    class Validation {

        @Test
        void reports_overlaps_under_unique() {
            var report = engine(HitPolicy.UNIQUE).build().validationReport();
            Assertions.assertThat(report.issues()).containsExactly(
                    new Issue(Kind.OVERLAPPING_RULES, List.of(10, 20)),
                    new Issue(Kind.OVERLAPPING_RULES, List.of(10, 30)),
                    new Issue(Kind.OVERLAPPING_RULES, List.of(20, 30)));
        }

        @Test
        void reports_rules_shadowed_under_first() {
            var shadowing = List.of(rule(1, null, "SMB", "A", "1"), rule(2, "PT", "SMB", "B", "2"));
            var report = engine(HitPolicy.FIRST).rules(shadowing).build().validationReport();
            Assertions.assertThat(report.issues()).containsExactly(new Issue(Kind.UNREACHABLE_RULE, List.of(2)));
            Assertions.assertThat(report.hasIssuesAtLeast(Severity.ERROR)).isFalse();
        }

        @Test
        void a_clean_table_has_no_issues() {
            Assertions.assertThat(engine(HitPolicy.FIRST).build().validationReport().isEmpty()).isTrue();
        }

        @Test
        void fail_on_turns_findings_into_build_errors() {
            var builder = engine(HitPolicy.UNIQUE).failOn(Severity.ERROR);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOfSatisfying(InvalidDecisionTableException.class,
                            e -> Assertions.assertThat(e.getReport().issues(Kind.OVERLAPPING_RULES)).hasSize(3));
        }
    }

    @Nested
    class Configuration {

        @Test
        void priority_policies_require_output_value_lists() {
            var builder = engine(HitPolicy.PRIORITY);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outputPriority");
        }

        @Test
        void outputs_missing_from_the_priority_list_are_rejected() {
            var builder = engine(HitPolicy.PRIORITY).outputPriority("grade", List.of("A"));
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOf(InvalidDecisionTableException.class)
                    .hasMessageContaining("grade=B");
        }

        @Test
        void aggregation_requires_collect() {
            var builder = engine(HitPolicy.FIRST).aggregation(AggregationOperator.SUM);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COLLECT");
        }

        @Test
        void inputs_must_be_bound() {
            var neither = PraeceptaEngine.<Order, Discount>builder()
                    .hitPolicy(HitPolicy.FIRST).output(PraeceptaEngineTest::discount).rules(RULES);
            Assertions.assertThatThrownBy(neither::build).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void inputs_cannot_be_bound_both_per_column_and_as_a_map() {
            var both = engine(HitPolicy.FIRST).inputs(order -> new PraeceptaInput(Map.of()));
            Assertions.assertThatThrownBy(both::build).isInstanceOf(IllegalStateException.class);
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static PraeceptaEngine.Builder<Order, Discount> engine(HitPolicy policy) {
        return PraeceptaEngine.<Order, Discount>builder()
                .hitPolicy(policy)
                .input("country", Order::country)
                .input("segment", Order::segment)
                .output(new OutputStrategy<>() {
                    @Override
                    public Discount transform(PraeceptaOutput output) {
                        return discount(output);
                    }

                    @Override
                    public Discount sum(Discount a, Discount b) {
                        return new Discount("*", a.percent() + b.percent());
                    }

                    @Override
                    public Discount count(int matches) {
                        return new Discount("#", matches);
                    }
                })
                .rules(RULES);
    }

    private static Discount discount(PraeceptaOutput output) {
        return new Discount(output.values().get("grade"), Integer.parseInt(output.values().get("percent")));
    }

    private static Praeceptum rule(int id, String country, String segment, String grade, String percent) {
        var cells = new HashMap<String, String>();
        cells.put("country", country);
        cells.put("segment", segment);
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("grade", grade, "percent", percent)));
    }
}
