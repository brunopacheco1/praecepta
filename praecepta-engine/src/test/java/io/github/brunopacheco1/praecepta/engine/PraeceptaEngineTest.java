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

        @Test
        void rule_order_and_collect_return_all_matches_in_rule_order() {
            for (var policy : List.of(HitPolicy.RULE_ORDER, HitPolicy.COLLECT)) {
                Assertions.assertThat(engine(policy).build().evaluate(PT_SMB))
                        .containsExactly(new Discount("A", 10), new Discount("B", 5), new Discount("A", 1));
            }
        }

        @Test
        void collect_can_aggregate() {
            var engine = engine(HitPolicy.COLLECT).aggregation(AggregationOperator.SUM).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly(new Discount("*", 16));
            Assertions.assertThat(engine.evaluate(FR_CORP)).isEmpty();
        }

        @Test
        void no_match_is_an_empty_result_under_every_policy() {
            for (var policy : HitPolicy.values()) {
                var builder = engine(policy);
                if (policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER) {
                    builder.outputPriority("grade", List.of("A", "B"));
                }
                Assertions.assertThat(builder.build().evaluate(FR_CORP)).as("policy %s", policy).isEmpty();
            }
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
            var engine = PraeceptaEngine.<Order, Discount>builder()
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
                Assertions.assertThat(engine.evaluate(order)).isEqualTo(engine(HitPolicy.COLLECT).build().evaluate(order));
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
        void inputs_must_be_bound_exactly_one_way() {
            var neither = PraeceptaEngine.<Order, Discount>builder()
                    .hitPolicy(HitPolicy.FIRST).output(PraeceptaEngineTest::discount).rules(RULES);
            Assertions.assertThatThrownBy(neither::build).isInstanceOf(IllegalStateException.class);
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
