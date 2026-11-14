/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.tck.Feature.Area;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/** What Praecepta evaluates of the kit's models, and what it says the rest needs. */
class SingleDecisionEvaluatorTest {

    private static final Map<String, Check> KIT = TckSuite.load().values().stream().flatMap(List::stream)
            .collect(Collectors.toMap(Check::key, Function.identity()));

    private final SingleDecisionEvaluator evaluator = new SingleDecisionEvaluator();

    @Test
    void a_literal_expression_reads_the_input_data_of_the_test_case() {
        var check = KIT.get("compliance-level-2/0001-input-data-string/0001-input-data-string-test-01.xml #001 Greeting Message");
        assertThat(new TckRunner(evaluator).run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_decision_table_is_evaluated_as_the_engine_evaluates_it() {
        var check = KIT.get("compliance-level-2/0004-simpletable-U/0004-simpletable-U-test-01.xml #001 Approval Status");
        assertThat(new TckRunner(evaluator).run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_decision_on_other_decisions_needs_graphs_and_what_its_own_logic_needs() {
        var check = KIT.get("compliance-level-3/0020-vacation-days/0020-vacation-days-test-01.xml #001 Total Vacation Days");
        assertThat(evaluator.evaluate(check)).isEqualTo(new Evaluation.Unsupported(List.of(Feature.builtIn("max"),
                Feature.REQUIRED_DECISIONS)));
        // the tables it requires are read, so a graph evaluator would need max() alone
        assertThat(evaluator.needsBesideGraphs(check, List.of())).containsExactly(Feature.builtIn("max"));
        assertThat(TckResults.latest(SingleDecisionEvaluator.content(check))).isEqualTo(Area.BUILT_IN_FUNCTIONS);
    }

    @Test
    void the_components_of_a_structured_input_are_not_read_by_literal_expressions() {
        var check = KIT.get("compliance-level-2/0008-LX-arithmetic/0008-LX-arithmetic-test-01.xml #001 payment");
        assertThat(evaluator.evaluate(check)).isEqualTo(new Evaluation.Unsupported(Feature.STRUCTURED_INPUTS));
        assertThat(TckResults.latest(SingleDecisionEvaluator.content(check))).isEqualTo(Area.BOXED_EXPRESSIONS);
    }

    @Test
    void a_decision_service_test_case_needs_decision_services() {
        var check = KIT.get("compliance-level-3/0085-decision-services/0085-decision-services-test-01.xml #001 decision_001");
        assertThat(evaluator.evaluate(check)).isEqualTo(new Evaluation.Unsupported(Feature.DECISION_SERVICES));
    }

    @Test
    void a_knowledge_model_invocation_needs_the_model_and_what_its_body_needs() {
        var check = KIT.get("compliance-level-2/0009-invocation-arithmetic/0009-invocation-arithmetic-test-01.xml #001 MonthlyPayment");
        var needs = ((Evaluation.Unsupported) evaluator.evaluate(check)).needs();
        assertThat(needs).first().isEqualTo(Feature.KNOWLEDGE_MODELS);
    }

    @Test
    void division_by_zero_gives_null_but_reports_no_error_where_the_kit_expects_one() {
        var check = KIT.get("compliance-level-3/0100-arithmetic/0100-arithmetic-test-01.xml #divide_lhs_number_by_rhs_number_002 "
                + "divide_lhs_number_by_rhs_number_002");
        assertThat(evaluator.evaluate(check)).isEqualTo(new Evaluation.Unsupported(LiteralExpressionEvaluator.EXPRESSION_ERRORS));
        assertThat(TckResults.latest(SingleDecisionEvaluator.content(check))).isEqualTo(Area.DECISION_TABLES);
    }

    @Test
    void a_value_of_another_type_than_the_decisions_needs_an_implicit_conversion() {
        var check = KIT.get("compliance-level-3/1157-implicit-conversions/1157-implicit-conversions-test-01.xml #003 "
                + "From Date To Date and Time");
        assertThat(evaluator.evaluate(check)).isEqualTo(new Evaluation.Unsupported(LiteralExpressionEvaluator.TYPE_CONVERSIONS));
    }

    @Test
    void an_expression_rejected_where_feel_gives_null_is_not_supported() {
        var check = KIT.get("compliance-level-3/0100-arithmetic/0100-arithmetic-test-01.xml #error_when_add_lhs_number_to_rhs_string "
                + "error_when_add_lhs_number_to_rhs_string");
        assertThat(evaluator.evaluate(check))
                .isEqualTo(new Evaluation.Unsupported(LiteralExpressionEvaluator.REJECTED_WHERE_FEEL_GIVES_NULL));
    }
}
