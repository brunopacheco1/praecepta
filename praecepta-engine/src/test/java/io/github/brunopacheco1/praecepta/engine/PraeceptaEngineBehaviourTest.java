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
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/**
 * Pins the exact messages and orderings of {@link PraeceptaEngine} and its builder, so that its
 * implementation can be restructured without changing what callers observe.
 */
class PraeceptaEngineBehaviourTest {

    /** An input is a map of column values; each binding reads one entry. */
    private static final Map<String, String> PT_SMB = Map.of("country", "PT", "segment", "SMB");

    @Nested
    class BuilderMessages {

        @Test
        void a_hit_policy_is_required() {
            var builder = PraeceptaEngine.<Map<String, String>, String>builder().output(PraeceptaEngineBehaviourTest::grade);
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("a hit policy is required");
        }

        @Test
        void an_output_strategy_is_required() {
            var builder = PraeceptaEngine.<Map<String, String>, String>builder().hitPolicy(HitPolicy.FIRST);
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("an output strategy is required");
        }

        @Test
        void rules_are_required() {
            var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                    .hitPolicy(HitPolicy.FIRST).output(PraeceptaEngineBehaviourTest::grade);
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("rules are required");
        }

        @Test
        void inputs_are_bound_one_way() {
            var neither = PraeceptaEngine.<Map<String, String>, String>builder()
                    .hitPolicy(HitPolicy.FIRST).output(PraeceptaEngineBehaviourTest::grade).rules(List.of());
            Assertions.assertThatThrownBy(neither::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("bind inputs either per column with input(...) or with inputs(...), not both and not neither");
            var both = byColumn(HitPolicy.FIRST).inputs(PraeceptaInput::new);
            Assertions.assertThatThrownBy(both::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("bind inputs either per column with input(...) or with inputs(...), not both and not neither");
        }

        @Test
        void column_types_need_map_based_inputs() {
            var builder = byColumn(HitPolicy.FIRST).columnType("country", ColumnType.STRING);
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("columnType(...) and listColumnType(...) only apply with inputs(...); per-column bindings carry their type");
        }

        @Test
        void table_columns_need_a_binding_of_their_type() {
            var unbound = byColumn(HitPolicy.FIRST).table(table(new DecisionTable.InputColumn("age", ColumnType.NUMBER)));
            Assertions.assertThatThrownBy(unbound::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("column 'age' of the table has no input binding");
            var mistyped = byColumn(HitPolicy.FIRST).table(table(new DecisionTable.InputColumn("country", ColumnType.NUMBER)));
            Assertions.assertThatThrownBy(mistyped::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("column 'country' is a NUMBER column in the table but is bound as STRING");
        }

        @Test
        void table_columns_are_checked_in_table_order() {
            var builder = byColumn(HitPolicy.FIRST).table(table(
                    new DecisionTable.InputColumn("segment", ColumnType.DATE),
                    new DecisionTable.InputColumn("country", ColumnType.NUMBER)));
            Assertions.assertThatThrownBy(builder::build).hasMessage("column 'segment' is a DATE column in the table but is bound as STRING");
        }

        @Test
        void aggregation_needs_collect() {
            var builder = byColumn(HitPolicy.FIRST).aggregation(AggregationOperator.SUM);
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("aggregation is only allowed with the COLLECT hit policy");
        }

        @Test
        void priority_policies_need_priority_lists_and_only_they_take_them() {
            Assertions.assertThatThrownBy(byColumn(HitPolicy.OUTPUT_ORDER)::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("OUTPUT_ORDER requires outputPriority(...) lists");
            var builder = byColumn(HitPolicy.FIRST).outputPriority("grade", List.of("A", "B"));
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("outputPriority(...) only applies to PRIORITY and OUTPUT_ORDER");
        }

        @Test
        void checks_run_in_order() {
            // inputs bound both ways, a column type, an aggregation without COLLECT: the binding check comes first
            var builder = byColumn(HitPolicy.FIRST).inputs(PraeceptaInput::new).columnType("x", ColumnType.STRING)
                    .aggregation(AggregationOperator.SUM);
            Assertions.assertThatThrownBy(builder::build)
                    .hasMessage("bind inputs either per column with input(...) or with inputs(...), not both and not neither");
            // a mistyped table column and an aggregation without COLLECT: the table check comes first
            var table = byColumn(HitPolicy.FIRST).table(table(new DecisionTable.InputColumn("country", ColumnType.NUMBER)))
                    .aggregation(AggregationOperator.SUM);
            Assertions.assertThatThrownBy(table::build)
                    .hasMessage("column 'country' is a NUMBER column in the table but is bound as STRING");
        }

        @Test
        void bindings_and_priority_lists_are_declared_once() {
            var bound = byColumn(HitPolicy.FIRST);
            Assertions.assertThatThrownBy(() -> bound.input("country", input -> input.get("x")))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Input column 'country' is declared twice");
            var builder = byColumn(HitPolicy.PRIORITY).outputPriority("grade", List.of("A", "B"));
            var gradeAgain = List.of("A");
            Assertions.assertThatThrownBy(() -> builder.outputPriority("grade", gradeAgain))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Priority list for 'grade' is declared twice");
            var duplicates = List.of("A", "A");
            Assertions.assertThatThrownBy(() -> builder.outputPriority("other", duplicates))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Priority list for 'other' has duplicates: [A, A]");
        }
    }

    @Nested
    class TableMessages {

        @Test
        void rules_may_not_test_unbound_columns() {
            var builder = byColumn(HitPolicy.FIRST, rule(7, Map.of("country", "PT", "age", "18"), "A"));
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Rule 7 tests column 'age', which has no declared input");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"18", "\"18\"", "> 18", "<= 18", "[18..65]", "not(> 18)", "\"18"})
        void rules_may_not_test_unbound_columns_whatever_the_cell(String cell) {
            var builder = byColumn(HitPolicy.FIRST, rule(7, Map.of("country", "PT", "age", cell), "A"));
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Rule 7 tests column 'age', which has no declared input");
        }

        @Test
        void rules_may_leave_unbound_columns_as_wildcards() {
            var engine = byColumn(HitPolicy.FIRST, rule(7, Map.of("country", "PT", "age", "-"), "A")).build();
            Assertions.assertThat(engine.inputs()).containsExactly("country", "segment");
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly("A");
        }

        @Test
        void input_values_need_a_known_column() {
            var builder = byColumn(HitPolicy.FIRST).allowedValues("age", "[0..150]");
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Input values declared for unknown column 'age'");
        }

        @Test
        void undeclared_columns_are_reported_before_unknown_input_values() {
            var builder = byColumn(HitPolicy.FIRST, rule(7, Map.of("age", "18"), "A")).allowedValues("age", "[0..150]");
            Assertions.assertThatThrownBy(builder::build).hasMessage("Rule 7 tests column 'age', which has no declared input");
        }

        @Test
        void cell_syntax_errors_name_the_rule_and_column() {
            var builder = byColumn(HitPolicy.FIRST, rule(1, Map.of("country", "PT"), "A"), rule(2, Map.of("segment", "\"SMB"), "B"));
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessageStartingWith("Rule 2, column 'segment': ");
        }

        @Test
        void cells_are_parsed_column_by_column() {
            var builder = byColumn(HitPolicy.FIRST,
                    rule(1, Map.of("segment", "\"SMB"), "A"),
                    rule(2, Map.of("country", "\"PT"), "B"));
            Assertions.assertThatThrownBy(builder::build).hasMessageStartingWith("Rule 2, column 'country': ");
        }

        @Test
        void input_values_must_parse() {
            var builder = byColumn(HitPolicy.FIRST).allowedValues("country", "\"PT");
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessageStartingWith("Input values of column 'country': ");
        }

        @Test
        void string_input_values_are_a_list_of_values() {
            var builder = byColumn(HitPolicy.FIRST).allowedValues("country", "not(\"FR\")");
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Input values of string column 'country' must be a list of values");
        }

        @Test
        void outputs_must_be_in_their_priority_list() {
            var builder = byColumn(HitPolicy.PRIORITY).outputPriority("grade", List.of("A"));
            Assertions.assertThatThrownBy(builder::build)
                    .isExactlyInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Rule 2: output grade=B is not in its priority list [A]");
        }

        @Test
        void fail_on_reports_the_validation_findings() {
            var builder = byColumn(HitPolicy.UNIQUE).failOn(Severity.WARNING);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOfSatisfying(InvalidDecisionTableException.class, e -> {
                        Assertions.assertThat(e.getMessage()).isEqualTo("Decision table failed validation:\n" + e.getReport());
                        var severities = e.getReport().issues().stream().map(ValidationReport.Issue::severity).toList();
                        Assertions.assertThat(severities).isSortedAccordingTo((a, b) -> b.compareTo(a));
                    });
        }
    }

    @Nested
    class Evaluation {

        //            country  segment  → grade
        // rule 1:    PT       -          A
        // rule 2:    -        SMB        B
        // rule 3:    -        -          A

        @Test
        void unique_and_any_violations_list_the_matching_rules() {
            var unique = byColumn(HitPolicy.UNIQUE).build();
            Assertions.assertThatThrownBy(() -> unique.evaluate(PT_SMB))
                    .isExactlyInstanceOf(HitPolicyViolationException.class)
                    .hasMessage("UNIQUE hit policy violated: rules [1, 2, 3] all match");
            var any = byColumn(HitPolicy.ANY).build();
            Assertions.assertThatThrownBy(() -> any.evaluate(PT_SMB))
                    .isExactlyInstanceOf(HitPolicyViolationException.class)
                    .hasMessage("ANY hit policy violated: matching rules [1, 2, 3] have different outputs");
        }

        @Test
        void any_returns_the_shared_output() {
            var engine = byColumn(HitPolicy.ANY, rule(1, Map.of("country", "PT"), "A"), rule(2, Map.of(), "A")).build();
            Assertions.assertThat(engine.evaluate(PT_SMB)).containsExactly("A");
        }

        @Test
        void collect_returns_outputs_in_rule_order_or_aggregated() {
            Assertions.assertThat(byColumn(HitPolicy.COLLECT).build().evaluate(PT_SMB)).containsExactly("A", "B", "A");
            var concat = byColumn(HitPolicy.COLLECT).aggregation(AggregationOperator.SUM).output(new OutputStrategy<>() {
                @Override
                public String transform(PraeceptaOutput output) {
                    return grade(output);
                }

                @Override
                public String sum(String a, String b) {
                    return a + b;
                }
            });
            Assertions.assertThat(concat.build().evaluate(PT_SMB)).containsExactly("ABA");
            Assertions.assertThat(concat.build().evaluate(Map.of("country", "ES"))).containsExactly("A");
        }

        @Test
        void count_needs_an_output_strategy_that_counts_and_says_so_when_built() {
            var builder = byColumn(HitPolicy.COLLECT).aggregation(AggregationOperator.COUNT);
            Assertions.assertThatThrownBy(builder::build)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("This OutputStrategy does not support aggregation");
        }

        @Test
        void priorities_compare_output_columns_in_declaration_order_and_ties_keep_rule_order() {
            //    grade  level  (all rules match every input)
            // 1  B      x
            // 2  A      y
            // 3  A      y
            // 4  A      x
            var rules = new Praeceptum[] {
                rule(1, Map.of(), Map.of("grade", "B", "level", "x", "id", "1")),
                rule(2, Map.of(), Map.of("grade", "A", "level", "y", "id", "2")),
                rule(3, Map.of(), Map.of("grade", "A", "level", "y", "id", "3")),
                rule(4, Map.of(), Map.of("grade", "A", "level", "x", "id", "4")),
            };
            OutputStrategy<String> id = output -> output.values().get("id");
            var gradeThenLevel = byColumn(HitPolicy.OUTPUT_ORDER, rules).output(id)
                    .outputPriority("grade", List.of("A", "B"))
                    .outputPriority("level", List.of("x", "y"))
                    .build();
            Assertions.assertThat(gradeThenLevel.evaluate(PT_SMB)).containsExactly("4", "2", "3", "1");
            var levelOnly = byColumn(HitPolicy.OUTPUT_ORDER, rules).output(id)
                    .outputPriority("level", List.of("y", "x"))
                    .build();
            Assertions.assertThat(levelOnly.evaluate(PT_SMB)).containsExactly("2", "3", "1", "4");
            var priority = byColumn(HitPolicy.PRIORITY, rules).output(id)
                    .outputPriority("level", List.of("y", "x"))
                    .outputPriority("grade", List.of("B", "A"))
                    .build();
            Assertions.assertThat(priority.evaluate(PT_SMB)).containsExactly("2");
            Assertions.assertThat(priority.evaluate(Map.of())).containsExactly("2");
        }

        @Test
        void no_match_and_no_rules_give_an_empty_list() {
            Assertions.assertThat(byColumn(HitPolicy.FIRST, rule(1, Map.of("country", "FR"), "A")).build().evaluate(PT_SMB)).isEmpty();
            Assertions.assertThat(byColumn(HitPolicy.COLLECT, new Praeceptum[0]).build().evaluate(PT_SMB)).isEmpty();
        }

        @Test
        void outputs_are_converted_once_per_rule() {
            var conversions = new int[1];
            var engine = byColumn(HitPolicy.COLLECT).output(output -> {
                conversions[0]++;
                return grade(output);
            }).build();
            engine.evaluate(PT_SMB);
            engine.evaluate(PT_SMB);
            Assertions.assertThat(conversions[0]).isEqualTo(3);
        }

        @Test
        void listed_string_values_are_quoted_and_escaped() {
            var engine = byColumn(HitPolicy.FIRST, rule(1, Map.of(), "A"))
                    .allowedValues("country", List.of("P\"T", "E\\S"))
                    .build();
            Assertions.assertThat(engine.evaluate(Map.of("country", "P\"T"))).containsExactly("A");
            Assertions.assertThat(engine.evaluate(Map.of("country", "E\\S"))).containsExactly("A");
            var notAllowed = Map.of("country", "PT");
            Assertions.assertThatThrownBy(() -> engine.evaluate(notAllowed))
                    .isExactlyInstanceOf(InvalidInputException.class)
                    .hasMessage("Input country=PT is not one of the column's allowed input values");
        }

        @Test
        void bound_numbers_must_be_finite() {
            var engine = PraeceptaEngine.<Double, String>builder()
                    .hitPolicy(HitPolicy.FIRST)
                    .numberInput("amount", amount -> amount)
                    .output(PraeceptaEngineBehaviourTest::grade)
                    .rules(List.of(rule(1, Map.of("amount", "> 0"), "A")))
                    .build();
            Assertions.assertThat(engine.evaluate(1.5)).containsExactly("A");
            Assertions.assertThatThrownBy(() -> engine.evaluate(Double.NaN))
                    .isExactlyInstanceOf(InvalidInputException.class)
                    .hasMessage("Input amount=NaN is not a finite number");
        }
    }

    @Nested
    class Columns {

        @Test
        void bound_columns_keep_binding_order() {
            var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                    .hitPolicy(HitPolicy.FIRST)
                    .input("segment", input -> input.get("segment"))
                    .input("country", input -> input.get("country"))
                    .output(PraeceptaEngineBehaviourTest::grade)
                    .rules(List.of(rule(1, Map.of("country", "PT"), "A")))
                    .build();
            Assertions.assertThat(engine.inputs()).containsExactly("segment", "country");
            Assertions.assertThat(engine.hitPolicy()).isEqualTo(HitPolicy.FIRST);
        }

        @Test
        void map_based_columns_come_from_the_table_rules_value_lists_and_column_types() {
            var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                    .table(table(new DecisionTable.InputColumn("zeta", ColumnType.STRING)))
                    .rules(List.of(rule(1, Map.of("country", "PT", "beta", "-"), "A")))
                    .inputs(PraeceptaInput::new)
                    .allowedValues("alpha", List.of("x"))
                    .columnType("age", ColumnType.NUMBER)
                    .output(PraeceptaEngineBehaviourTest::grade)
                    .build();
            Assertions.assertThat(engine.inputs()).containsExactly("zeta", "beta", "country", "alpha", "age");
            Assertions.assertThat(engine.evaluate(Map.of("country", "PT", "age", "3"))).containsExactly("A");
            var notANumber = Map.of("age", "three");
            Assertions.assertThatThrownBy(() -> engine.evaluate(notANumber))
                    .isExactlyInstanceOf(InvalidInputException.class)
                    .hasMessageStartingWith("Input age=three ");
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** Three overlapping rules, see {@link Evaluation}. */
    private static PraeceptaEngine.Builder<Map<String, String>, String> byColumn(HitPolicy policy) {
        return byColumn(policy,
                rule(1, Map.of("country", "PT"), "A"),
                rule(2, Map.of("segment", "SMB"), "B"),
                rule(3, Map.of(), "A"));
    }

    private static PraeceptaEngine.Builder<Map<String, String>, String> byColumn(HitPolicy policy, Praeceptum... rules) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .input("country", input -> input.get("country"))
                .input("segment", input -> input.get("segment"))
                .output(PraeceptaEngineBehaviourTest::grade)
                .rules(List.of(rules));
    }

    private static DecisionTable table(DecisionTable.InputColumn... inputs) {
        return new DecisionTable("t", HitPolicy.FIRST, null, List.of(inputs), List.of(), List.of(rule(1, Map.of(), "A")));
    }

    private static String grade(PraeceptaOutput output) {
        return output.values().get("grade");
    }

    private static Praeceptum rule(int id, Map<String, String> cells, String grade) {
        return rule(id, cells, Map.of("grade", grade));
    }

    private static Praeceptum rule(int id, Map<String, String> cells, Map<String, String> outputs) {
        return new Praeceptum(id, new PraeceptaInput(new HashMap<>(cells)), new PraeceptaOutput(outputs));
    }
}
