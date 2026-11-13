/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Decision requirement graphs read from DMN files and assembled in code: evaluation in dependency order, values passed
 * between decisions, and the checks made when a graph is built.
 */
class DecisionGraphTest {

    static DecisionGraph graph(String file) {
        try (InputStream dmn = DecisionGraphTest.class.getResourceAsStream("/dmn/graph/" + file)) {
            return DecisionGraph.builder().model(DmnReader.readModel(dmn))
                    .logValidationReport(false).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- chains ------------------------------------------------------------------------------------

    @Test
    void a_chain_is_evaluated_in_dependency_order_each_decision_reading_the_value_of_the_one_it_requires() {
        var graph = graph("chain.dmn");

        assertThat(graph.validationReport().isEmpty()).isTrue();
        assertThat(graph.evaluate("Monthly Limit", Map.of("Applicant Age", 20, "Income", 1200))).isEqualTo(new BigDecimal("50"));
        assertThat(graph.evaluate("Monthly Limit", Map.of("Applicant Age", 30, "Income", 1200))).isEqualTo(new BigDecimal("200"));
        assertThat(graph.evaluate("Credit Limit", Map.of("Applicant Age", 30, "Income", 1200))).isEqualTo(new BigDecimal("2400"));
        assertThat(graph.evaluate("Risk Category", Map.of("Applicant Age", 30))).isEqualTo("Low");
    }

    @Test
    void a_decision_needs_only_the_input_data_it_requires_directly_or_through_other_decisions() {
        var graph = graph("chain.dmn");

        assertThat(graph.requiredInputData("Risk Category")).containsExactly("Applicant Age");
        assertThat(graph.requiredInputData("Monthly Limit")).containsExactly("Applicant Age", "Income");
        assertThat(graph.evaluate("Risk Category", Map.of("Applicant Age", 20, "Income", "not a number"))).isEqualTo("High");
    }

    @Test
    void input_data_may_be_given_as_text_or_any_number_and_a_missing_one_is_null() {
        var graph = graph("chain.dmn");

        assertThat(graph.evaluate("Credit Limit", Map.of("Applicant Age", "20", "Income", "1200.00"))).isEqualTo(new BigDecimal("600"));
        assertThat(graph.evaluate("Credit Limit", Map.of("Applicant Age", 20L, "Income", 1200.5d))).isEqualTo(new BigDecimal("600.25"));
        // no rule matches a missing age, so the risk is null, and so are the limits that depend on it
        assertThat(graph.evaluate("Risk Category", Map.of())).isNull();
        assertThat(graph.evaluate("Monthly Limit", Map.of("Income", 1200))).isNull();
    }

    @Test
    void an_input_data_value_not_of_its_type_is_rejected() {
        var graph = graph("chain.dmn");

        assertThatThrownBy(() -> graph.evaluate("Risk Category", Map.of("Applicant Age", "old")))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining("Applicant Age=old is not a valid number");
        assertThatThrownBy(() -> graph.evaluate("Risk Category", Map.of("Applicant Age", LocalDate.of(2000, 1, 1))))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining("is not a number");
    }

    @Test
    void the_types_of_input_data_and_decisions_are_known_once_the_graph_is_built() {
        var graph = graph("chain.dmn");

        assertThat(graph.inputData()).containsExactly(Map.entry("Applicant Age", FeelType.of(ColumnType.NUMBER)),
                Map.entry("Income", FeelType.of(ColumnType.NUMBER)));
        assertThat(graph.type("Risk Category")).isEqualTo(FeelType.of(ColumnType.STRING));
        assertThat(graph.type("Monthly Limit")).isEqualTo(FeelType.of(ColumnType.NUMBER));
        assertThat(graph.decisions()).containsExactly("Risk Category", "Credit Limit", "Monthly Limit");
    }

    @Test
    void an_unknown_decision_is_named_with_the_decisions_there_are() {
        var graph = graph("chain.dmn");

        assertThatThrownBy(() -> graph.evaluate("Nothing", Map.of())).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no decision named 'Nothing'").hasMessageContaining("[Risk Category, Credit Limit, Monthly Limit]");
        assertThatThrownBy(() -> graph.validationReport("Nothing")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> graph.type("Nothing")).isInstanceOf(NoSuchElementException.class);
    }

    // ---- diamonds ----------------------------------------------------------------------------------

    @Test
    void a_diamond_evaluates_both_branches_from_the_same_input_data() {
        var graph = graph("diamond.dmn");

        assertThat(graph.evaluate("Offer Text", Map.of("Score", 750))).isEqualTo("Offer: Gold");
        assertThat(graph.evaluate("Offer Text", Map.of("Score", 650))).isEqualTo("Offer: Silver");
        assertThat(graph.evaluate("Offer Text", Map.of("Score", 550))).isEqualTo("Offer: None");
        assertThat(graph.evaluate("Eligible", Map.of("Score", 550))).isEqualTo(false);
    }

    @Test
    void several_decisions_are_evaluated_at_once_sharing_the_decisions_they_require() {
        var graph = graph("diamond.dmn");

        var values = graph.evaluate(List.of("Offer Text", "Band", "Eligible"), Map.of("Score", 750));

        assertThat(values).containsExactly(Map.entry("Offer Text", "Offer: Gold"), Map.entry("Band", "A"), Map.entry("Eligible", true));
    }

    // ---- multiple outputs, multiple results ---------------------------------------------------------

    @Test
    void a_table_with_several_outputs_gives_a_context_whose_components_are_read_by_path() {
        var graph = graph("multi-output.dmn");

        assertThat(graph.type("Pricing").feelName()).isEqualTo("context<discount: number, grade: string>");
        assertThat(graph.evaluate("Pricing", Map.of("Customer Type", "Gold"))).isEqualTo(Map.of("discount", new BigDecimal("0.2"), "grade", "A"));
        assertThat(graph.evaluate("Shipping", Map.of("Customer Type", "Gold"))).isEqualTo("Free");
        assertThat(graph.evaluate("Shipping", Map.of("Customer Type", "Bronze"))).isEqualTo("Standard");
        assertThat(graph.evaluate("Total", Map.of("Customer Type", "Gold", "Order Total", 100))).isEqualTo(new BigDecimal("80"));
        assertThat(graph.evaluate("Total", Map.of("Customer Type", "Silver", "Order Total", new BigDecimal("99.90"))))
                .isEqualTo(new BigDecimal("89.91"));
    }

    @Test
    void a_table_with_several_results_gives_a_list_which_a_list_input_downstream_takes() {
        var graph = graph("multiple-results.dmn");

        assertThat(graph.validationReport().isEmpty()).as("%s", graph.validationReport()).isTrue();
        assertThat(graph.type("Flags")).isEqualTo(FeelType.listOf(ColumnType.STRING));
        assertThat(graph.evaluate("Flags", Map.of("Amount", 20000, "Country", "XX"))).isEqualTo(List.of("large", "sanctioned"));
        assertThat(graph.evaluate("Review", Map.of("Amount", 20000, "Country", "XX"))).isEqualTo("Block");
        assertThat(graph.evaluate("Review", Map.of("Amount", 20000, "Country", "PT"))).isEqualTo("Manual");
        // no rule matches: Flags is null, which only "-" matches downstream
        assertThat(graph.evaluate("Flags", Map.of("Amount", 5, "Country", "PT"))).isNull();
        assertThat(graph.evaluate("Review", Map.of("Amount", 5, "Country", "PT"))).isEqualTo("Auto");
    }

    @Test
    void a_path_into_a_list_of_contexts_is_the_list_of_their_components() {
        var graph = graph("multiple-results.dmn");

        assertThat(graph.evaluate("Checks", Map.of("Amount", 20000))).isEqualTo(List.of(
                Map.of("code", "AML", "weight", new BigDecimal("3")), Map.of("code", "BASIC", "weight", new BigDecimal("1"))));
        assertThat(graph.evaluate("Due Diligence", Map.of("Amount", 20000))).isEqualTo("Enhanced");
        assertThat(graph.evaluate("Due Diligence", Map.of("Amount", 20))).isEqualTo("Standard");
    }

    @Test
    void an_aggregated_table_gives_a_single_value() {
        var graph = graph("multiple-results.dmn");

        assertThat(graph.type("Flag Count")).isEqualTo(FeelType.of(ColumnType.NUMBER));
        assertThat(graph.evaluate("Flag Count", Map.of("Amount", 20000, "Country", "XX"))).isEqualTo(new BigDecimal("2"));
        assertThat(graph.evaluate("Flag Count", Map.of("Amount", 20, "Country", "PT"))).isNull();
    }

    // ---- literal expressions -----------------------------------------------------------------------

    @Test
    void literal_expressions_compute_with_strings_numbers_dates_and_durations() {
        var graph = graph("literals.dmn");
        var inputs = Map.<String, Object>of("Full Name", "Ada", "Start", LocalDate.of(2025, 1, 31), "Hours", 3);

        assertThat(graph.evaluate("Greeting", inputs)).isEqualTo("Hello Ada");
        assertThat(graph.evaluate("Due", inputs)).isEqualTo(LocalDate.of(2025, 2, 14));
        assertThat(graph.evaluate("Shift", inputs)).isEqualTo(Duration.ofHours(3));
        assertThat(graph.type("Shift")).isEqualTo(FeelType.of(ColumnType.DAY_TIME_DURATION));
    }

    @Test
    void a_date_bound_to_a_decision_that_declares_a_date_and_time_is_utc_midnight() {
        var graph = graph("literals.dmn");

        assertThat(graph.evaluate("Starts At", Map.of("Start", "2025-01-31"))).isEqualTo(LocalDate.of(2025, 1, 31).atStartOfDay(ZoneOffset.UTC));
        assertThat(graph.type("Starts At")).isEqualTo(FeelType.of(ColumnType.DATE_TIME));
    }

    @Test
    void a_literal_expression_reads_the_components_of_a_structured_input_data() {
        var graph = graph("literals.dmn");

        // the DMN TCK's 0008-LX-arithmetic
        var payment = (BigDecimal) graph.evaluate("Payment", Map.of("Loan", Map.of("principal", 600000, "rate", new BigDecimal("0.0375"),
                "months", 360)));

        assertThat(payment.setScale(11, RoundingMode.HALF_EVEN)).isEqualTo(new BigDecimal("2778.69354943277"));
        assertThat(graph.evaluate("Payment", Map.of("Loan", Map.of("principal", 600000)))).as("missing components are null").isNull();
    }

    // ---- what is reported when the graph is built --------------------------------------------------

    @Test
    void decisions_in_a_cycle_are_reported_and_cannot_be_evaluated_but_the_others_can() {
        var graph = graph("cycle.dmn");

        assertThat(graph.validationReport().issues(Kind.DECISION_CYCLE)).extracting(ValidationReport.Issue::detail).containsExactly(
                "decisions 'A' -> 'B' -> 'A' require one another in a cycle (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)",
                "decision 'E' requires itself (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)");
        assertThat(graph.validationReport().issues()).allMatch(issue -> issue.severity() == Severity.ERROR);
        assertThat(List.of("A", "B", "C", "D", "E")).map(graph::canEvaluate).containsExactly(false, false, false, true, false);
        assertThat(graph.evaluate("D", Map.of("X", 1))).isEqualTo(new BigDecimal("2"));
        assertThatThrownBy(() -> graph.evaluate("C", Map.of("X", 1))).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageStartingWith("Decision 'C' cannot be evaluated: it requires a decision in a cycle");
        assertThatThrownBy(() -> graph.evaluate("B", Map.of())).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("'A' -> 'B' -> 'A'");
    }

    @Test
    void missing_requirements_and_names_no_requirement_provides_are_reported() {
        var graph = graph("missing.dmn");

        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::kind).containsOnly(Kind.MISSING_REQUIREMENT);
        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::toString).containsExactly(
                "ERROR decision 'A' requires decision 'nowhere', which is not in the model",
                "ERROR decision 'B' reads 'Y', which none of its information requirements provides (DMN 1.5, 7.3.4)",
                "ERROR decision 'C' requires input data 'D', which is a decision, not input data");
        assertThat(List.of("A", "B", "C", "D", "E")).map(graph::canEvaluate).containsExactly(false, false, false, true, false);
        assertThatThrownBy(() -> graph.evaluate("E", Map.of())).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'E' cannot be evaluated: it requires decision 'B', which cannot be evaluated: "
                        + "decision 'B' reads 'Y', which none of its information requirements provides (DMN 1.5, 7.3.4)");
        assertThat(graph.evaluate("D", Map.of("X", 2))).isEqualTo(new BigDecimal("4"));
    }

    @Test
    void values_that_do_not_fit_where_they_go_are_type_mismatches() {
        var graph = graph("type-mismatch.dmn");

        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::kind).containsOnly(Kind.TYPE_MISMATCH);
        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::detail).containsExactlyInAnyOrder(
                "decision 'Size' reads 'Name', a string, into its input 'Name', a number",
                "decision 'Twice': literal expression 'Name * 2' * combines a string and a number, which gives null in FEEL",
                "decision 'First' reads 'Name.first', but 'Name' is a string, which has no component 'first'",
                "decision 'Declared' declares a number, but its literal expression 'Name + \"!\"' gives a string, so its value is null "
                        + "(DMN 1.5, 10.3.2.9.4)",
                "decision 'Single' reads 'Names', a list<string>, into its input 'Names', a string");
        assertThat(List.of("Size", "Twice", "First", "Single", "Downstream")).map(graph::canEvaluate).containsOnly(false);
        assertThatThrownBy(() -> graph.evaluate("Downstream", Map.of("Name", "x"))).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("it requires decision 'Size'");
    }

    @Test
    void a_literal_expression_bound_to_a_type_it_does_not_conform_to_is_null() {
        // DMN 1.5, 10.3.2.9.4: "If after applying the implicit conversions the converted value and the target type do not
        // conform, the conforms to conversion is applied", which gives null; the mismatch is reported all the same
        var graph = graph("type-mismatch.dmn");

        assertThat(graph.canEvaluate("Declared")).isTrue();
        assertThat(graph.type("Declared")).isEqualTo(FeelType.of(ColumnType.NUMBER));
        assertThat(graph.evaluate("Declared", Map.of("Name", "x"))).isNull();
    }

    @Test
    void a_single_value_bound_to_a_decision_that_declares_a_list_is_a_list_of_one() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Code", ColumnType.STRING)
                .literalDecision("Codes", FeelType.listOf(ColumnType.STRING), "Code + \"!\"")
                .decision(Tables.table("Flagged", "FIRST", List.of("Codes:list<string>"), List.of("Flagged:boolean"), List.of(
                        List.of("list contains(?, \"a!\")", "true"), List.of("-", "false"))))
                .build();

        assertThat(graph.validationReport().isEmpty()).as("%s", graph.validationReport()).isTrue();
        assertThat(graph.evaluate("Codes", Map.of("Code", "a"))).isEqualTo(List.of("a!"));
        assertThat(graph.evaluate("Flagged", Map.of("Code", "a"))).isEqualTo(true);
        assertThat(graph.evaluate("Codes", Map.of())).isNull();
    }

    @Test
    void fail_on_rejects_a_graph_whose_report_has_issues_that_severe() {
        var builder = DecisionGraph.builder().logValidationReport(false).failOn(Severity.ERROR);
        try (InputStream dmn = getClass().getResourceAsStream("/dmn/graph/cycle.dmn")) {
            builder.model(DmnReader.readModel(dmn));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThatThrownBy(builder::build).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageStartingWith("Decision model 'Cycle' has 2 validation issue(s) at ERROR or above:")
                .hasMessageContaining("ERROR decision 'E' requires itself");
    }

    @Test
    void each_table_keeps_its_own_validation_report() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Age", ColumnType.NUMBER)
                .decision(Tables.table("Tier", "UNIQUE", List.of("Age:number"), List.of("Tier:string"), List.of(
                        List.of(">= 18", "\"adult\""), List.of(">= 65", "\"senior\""))))
                .literalDecision("Label", "\"tier \" + Tier")
                .build();

        assertThat(graph.validationReport().isEmpty()).isTrue();
        assertThat(graph.validationReport("Tier").issues(Kind.OVERLAPPING_RULES)).hasSize(1);
        assertThat(graph.validationReport("Label").isEmpty()).isTrue();
        assertThat(graph.evaluate("Label", Map.of("Age", 30))).isEqualTo("tier adult");
        assertThatThrownBy(() -> graph.evaluate("Label", Map.of("Age", 70))).isInstanceOf(HitPolicyViolationException.class);
    }

    // ---- assembled in code ---------------------------------------------------------------------------

    @Test
    void a_graph_assembled_in_code_requires_what_its_logic_reads() {
        var graph = DecisionGraph.builder()
                .name("Code")
                .logValidationReport(false)
                .inputData("Customer", new FeelType.Context(Map.of("age", FeelType.of(ColumnType.NUMBER))))
                .decision(Tables.table("Pricing", "UNIQUE", List.of("Customer.age:number"), List.of("discount:number", "grade:string"), List.of(
                        List.of("< 30", "0.1", "\"young\""), List.of(">= 30", "0.2", "\"other\""))))
                .literalDecision("Discounted", FeelType.of(ColumnType.NUMBER), "100 * (1 - Pricing.discount)")
                .literalDecision("Broken", "Missing + 1")
                .build();

        var model = graph.model();
        assertThat(model.name()).isEqualTo("Code");
        assertThat(model.decision("Pricing").orElseThrow().requiredInputs()).containsExactly("Customer");
        assertThat(model.decision("Discounted").orElseThrow().requiredDecisions()).containsExactly("Pricing");
        assertThat(model.decision("Broken").orElseThrow().requiredInputs()).containsExactly("Missing");
        assertThat(graph.evaluate("Discounted", Map.of("Customer", Map.of("age", 25)))).isEqualTo(new BigDecimal("90"));
        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::detail)
                .containsExactly("decision 'Broken' requires input data 'Missing', which is not in the model");
    }

    @Test
    void an_input_data_without_a_type_takes_the_type_of_the_columns_that_read_it_if_they_agree() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Age", FeelType.ANY)
                .inputData("Code", FeelType.ANY)
                .decision(Tables.table("Adult", "FIRST", List.of("Age:number"), List.of("Adult:boolean"), List.of(List.of(">= 18", "true"))))
                .decision(Tables.table("Known", "FIRST", List.of("Code:string"), List.of("Known:boolean"), List.of(List.of("\"a\"", "true"))))
                .decision(Tables.table("Odd", "FIRST", List.of("Code:number"), List.of("Odd:boolean"), List.of(List.of("1", "true"))))
                .build();

        assertThat(graph.inputData()).containsEntry("Age", FeelType.of(ColumnType.NUMBER)).containsEntry("Code", FeelType.of(ColumnType.STRING));
        assertThat(graph.evaluate("Adult", Map.of("Age", "21"))).isEqualTo(true);
        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::detail).containsExactly(
                "input data 'Code' declares no type, and decision 'Known' reads it as a string, but decision 'Odd' as a number");
    }

    @Test
    void a_value_of_undeclared_type_read_by_path_is_checked_when_it_is_read() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Applicant", FeelType.ANY)
                .decision(Tables.table("Adult", "FIRST", List.of("Applicant.age:number"), List.of("Adult:boolean"),
                        List.of(List.of(">= 18", "true"))))
                .build();

        assertThat(graph.validationReport().isEmpty()).isTrue();
        assertThat(graph.evaluate("Adult", Map.of("Applicant", Map.of("age", 30)))).isEqualTo(true);
        assertThatThrownBy(() -> graph.evaluate("Adult", Map.of("Applicant", Map.of("age", "thirty"))))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining("Applicant.age=thirty is not a number");
    }

    @Test
    void a_cell_may_compare_with_a_decision() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Amount", ColumnType.NUMBER)
                .literalDecision("Limit", FeelType.of(ColumnType.NUMBER), "1000 * 2")
                .decision(Tables.table("Within", "FIRST", List.of("Amount:number"), List.of("Within:boolean"), List.of(
                        List.of("<= Limit", "true"), List.of("> Limit", "false"))))
                .build();

        assertThat(graph.model().decision("Within").orElseThrow().requirements()).containsExactly("Limit", "Amount");
        assertThat(graph.evaluate("Within", Map.of("Amount", 1500))).isEqualTo(true);
        assertThat(graph.evaluate("Within", Map.of("Amount", 2500))).isEqualTo(false);
    }

    @Test
    void a_cell_naming_a_decision_that_gives_a_list_asks_whether_the_value_is_in_it() {
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Symptom", ColumnType.STRING)
                .inputData("Season", ColumnType.STRING)
                .decision(Tables.table("Flu Symptoms", "COLLECT", List.of("Season:string"), List.of("Flu Symptoms:string"), List.of(
                        List.of("-", "\"fever\""), List.of("\"winter\"", "\"cough\""))))
                .decision(Tables.table("Flu", "FIRST", List.of("Symptom:string"), List.of("Flu:boolean"), List.of(
                        List.of("Flu Symptoms", "true"), List.of("-", "false"))))
                .build();

        assertThat(graph.validationReport().isEmpty()).as("%s", graph.validationReport()).isTrue();
        assertThat(graph.evaluate("Flu", Map.of("Symptom", "cough", "Season", "winter"))).isEqualTo(true);
        assertThat(graph.evaluate("Flu", Map.of("Symptom", "cough", "Season", "summer"))).isEqualTo(false);
    }

    @Test
    void a_column_of_lists_takes_a_single_value_as_a_list_of_one() {
        // DMN 1.5, 10.3.2.9.4, "to singleton list"
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Code", ColumnType.STRING)
                .literalDecision("Tag", "Code + \"!\"")
                .decision(Tables.table("Flagged", "FIRST", List.of("Tag:list<string>"), List.of("Flagged:boolean"), List.of(
                        List.of("list contains(?, \"b!\")", "true"), List.of("-", "false"))))
                .build();

        assertThat(graph.validationReport().isEmpty()).as("%s", graph.validationReport()).isTrue();
        assertThat(graph.evaluate("Flagged", Map.of("Code", "b"))).isEqualTo(true);
        // one string, which is not read as the text of a list
        assertThat(graph.evaluate("Flagged", Map.of("Code", "a, b"))).isEqualTo(false);
    }

    @Test
    void the_default_output_of_a_table_with_several_results_is_a_list_of_one() {
        // DMN 1.5, 10.3.2.10, step 2 gives the default, which binding to the decision's list type makes a list of one
        // (10.3.2.9.4), as the DMN TCK's 0109-ruleOrder-hitpolicy expects
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("Age", ColumnType.NUMBER)
                .decision(Tables.withDefault(Tables.table("Status", "RULE ORDER", List.of("Age:number"), List.of("Status:string"), List.of(
                        List.of(">= 18", "\"adult\""), List.of(">= 65", "\"senior\""))), "Declined"))
                .build();

        assertThat(graph.evaluate("Status", Map.of("Age", 70))).isEqualTo(List.of("adult", "senior"));
        assertThat(graph.evaluate("Status", Map.of("Age", 10))).isEqualTo(List.of("Declined"));
        assertThat(graph.evaluate("Status", new HashMap<>(Map.of()))).isEqualTo(List.of("Declined"));
    }

    @Test
    void two_elements_of_the_same_name_are_rejected() {
        var builder = DecisionGraph.builder().inputData("Age", ColumnType.NUMBER).literalDecision("Age", "1");

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Age' names two elements of the decision model");
    }

    @Test
    void a_literal_expression_beyond_the_subset_names_the_release_that_plans_it() {
        assertThatThrownBy(() -> DecisionGraph.builder().literalDecision("Upper", "upper case(Name)"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("built-in functions are planned for 0.9 (#117)");
        assertThatThrownBy(() -> DecisionGraph.builder().literalDecision("If", "if Age > 18 then 1 else 2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("the full FEEL language is planned for 0.8 (#116)");
    }

    @Test
    void a_name_holding_a_feel_operator_that_no_requirement_provides_points_at_full_feel() {
        // the DMN TCK's 0106-feel-ternary-logic: in the subset, "A and B" is one name, as names may hold "and"
        var graph = DecisionGraph.builder()
                .logValidationReport(false)
                .inputData("A", ColumnType.BOOLEAN)
                .inputData("B", ColumnType.BOOLEAN)
                .decision(new DecisionModel.Decision("Both", List.of(), List.of("A", "B"),
                        new DecisionLogic.LiteralExpression("A and B")))
                .build();

        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::detail).containsExactly(
                "decision 'Both' reads 'A and B', which none of its information requirements provides (DMN 1.5, 7.3.4); "
                        + "if 'and' is meant as FEEL's operator, the full FEEL language is planned for 0.8 (#116)");
    }

    @Test
    void a_long_chain_of_decisions_is_built_and_evaluated_without_recursion() {
        var builder = DecisionGraph.builder().logValidationReport(false).inputData("X", ColumnType.NUMBER).literalDecision("D0", "X + 1");
        int length = 5_000;
        for (int i = 1; i < length; i++) {
            builder.literalDecision("D" + i, "D" + (i - 1) + " + 1");
        }
        var graph = builder.build();

        assertThat(graph.evaluate("D" + (length - 1), Map.of("X", 0))).isEqualTo(BigDecimal.valueOf(length));
        assertThat(graph.requiredInputData("D" + (length - 1))).containsExactly("X");
    }

    @Test
    void a_long_cycle_is_reported_without_recursion() {
        var builder = DecisionGraph.builder().logValidationReport(false);
        int length = 5_000;
        for (int i = 0; i < length; i++) {
            builder.literalDecision("D" + i, "D" + ((i + 1) % length) + " + 1");
        }
        var graph = builder.build();

        assertThat(graph.validationReport().issues(Kind.DECISION_CYCLE)).hasSize(1);
        assertThat(Arrays.asList(graph.validationReport().issues(Kind.DECISION_CYCLE).getFirst().detail().split(" -> "))).hasSize(length + 1);
    }
}
