/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/** Literal expression decisions in the full FEEL language (DMN 1.5, chapter 10), as a decision requirements graph evaluates them. */
class FeelDecisionsTest {

    private static DecisionModel read(String elements) {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="m" name="M" namespace="https://example.com/m">
                %s
                </definitions>
                """.formatted(elements);
        return DmnReader.readModel(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static DecisionGraph graph(DecisionModel model) {
        return DecisionGraph.builder().model(model).logValidationReport(false).build();
    }

    @Test
    void a_decision_reads_its_requirements_by_names_that_hold_spaces_keywords_and_symbols() {
        // DMN 1.5, 10.3.1.2: the longest name in scope wins, as the DMN TCK's 0100-arithmetic and 0084-feel-for-loops have it
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("Another Date and Time", ColumnType.DATE_TIME)
                .inputData("Date-Time", ColumnType.DATE_TIME)
                .inputData("days in weekend", FeelType.listOf(ColumnType.STRING))
                .literalDecision("Gap", "Another Date and Time - Date-Time")
                .literalDecision("Sundays", "for d in days in weekend return d = \"sunday\"")
                .build();

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(graph.requiredInputData("Gap")).containsExactly("Another Date and Time", "Date-Time");
        assertThat(graph.evaluate("Gap", Map.of("Another Date and Time", "2025-01-02T00:00:00", "Date-Time", "2025-01-01T00:00:00")))
                .hasToString("PT24H");
        assertThat(graph.evaluate("Sundays", Map.of("days in weekend", List.of("saturday", "sunday")))).isEqualTo(List.of(false, true));
    }

    @Test
    void a_decision_computes_with_lists_contexts_filters_and_functions_of_the_decisions_it_requires() {
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("Orders", FeelType.ANY)
                .literalDecision("Large", "Orders[amount > 100]")
                .literalDecision("Summary", "{count: for o in Large return 1, ids: Large.id, "
                        + "label: function(o) o.id + \": \" + (if o.amount > 500 then \"huge\" else \"large\"), first: label(Large[1])}")
                .literalDecision("First", "Summary.first")
                .build();
        var orders = List.of(Map.of("id", "a", "amount", 50), Map.of("id", "b", "amount", 150), Map.of("id", "c", "amount", 900));

        assertThat(graph.evaluate("First", Map.of("Orders", orders))).isEqualTo("b: large");
        assertThat((List<?>) graph.evaluate("Large", Map.of("Orders", orders))).hasSize(2);
        assertThat(graph.type("Summary")).isInstanceOf(FeelType.Context.class);
    }

    @Test
    void errors_feel_reports_come_with_the_value() {
        // DMN 1.5, 10.3.2.15: where FEEL defines no value, the value is null and an error may be reported
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("Amount", ColumnType.NUMBER)
                .literalDecision("Rate", "100 / Amount")
                .literalDecision("Label", "Rate * 2")
                .build();

        var outcome = graph.evaluateWithErrors("Label", Map.of("Amount", 0));
        assertThat(outcome.value()).isNull();
        assertThat(outcome.errors()).containsOnlyKeys("Rate", "Label");
        assertThat(outcome.errorsOf("Rate")).containsExactly("100 / 0 has no value in FEEL");
        assertThat(outcome.errorsOf("Label")).singleElement().asString().contains("does not combine a null and a number");
        assertThat(graph.evaluateWithErrors("Label", Map.of("Amount", 4))).isEqualTo(new DecisionGraph.Outcome(new BigDecimal("50"), Map.of()));
        assertThat(graph.evaluate("Label", Map.of("Amount", 0))).isNull();
    }

    @Test
    void a_decision_always_null_is_a_warning_with_the_reason_and_still_reports_its_error() {
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("Name", ColumnType.STRING)
                .literalDecision("Odd", "Name * 2")
                .build();

        assertThat(graph.validationReport().issues(Kind.ALWAYS_NULL)).extracting(ValidationReport.Issue::detail).containsExactly(
                "decision 'Odd': in literal expression 'Name * 2', * combines a string and a number, which gives null in FEEL; its "
                        + "value is null (DMN 1.5, 10.3.2.15)");
        assertThat(graph.evaluateWithErrors("Odd", Map.of("Name", "x")).errorsOf("Odd")).hasSize(1);
    }

    @Test
    void a_value_is_bound_to_the_type_its_decision_declares() {
        // DMN 1.5, 10.3.2.9.4, as the DMN TCK's 0082-feel-coercion and 1157-implicit-conversions have it
        var graph = DecisionGraph.builder().logValidationReport(false)
                .literalDecision("Listed", FeelType.listOf(ColumnType.NUMBER), "1")
                .literalDecision("Single", FeelType.of(ColumnType.STRING), "[\"a\"]")
                .literalDecision("Moment", FeelType.of(ColumnType.DATE_TIME), "@\"2025-01-31\"")
                .literalDecision("Mismatch", FeelType.listOf(ColumnType.NUMBER), "[1, \"a\"]")
                .literalDecision("Context", new FeelType.Context(Map.of("name", FeelType.of(ColumnType.STRING),
                        "age", FeelType.of(ColumnType.NUMBER))), "{name: \"x\"}")
                .build();

        assertThat(graph.evaluate("Listed", Map.of())).isEqualTo(List.of(BigDecimal.ONE));
        assertThat(graph.evaluate("Single", Map.of())).isEqualTo("a");
        assertThat(graph.evaluate("Moment", Map.of())).isEqualTo(LocalDate.of(2025, 1, 31).atStartOfDay(ZoneOffset.UTC));
        assertThat(graph.evaluate("Mismatch", Map.of())).isNull();
        assertThat(graph.evaluate("Context", Map.of())).isNull();
        assertThat(graph.evaluateWithErrors("Mismatch", Map.of()).errorsOf("Mismatch")).singleElement().asString()
                .contains("is not a list<number>");
    }

    @Test
    void instance_of_names_the_item_definitions_of_the_model() {
        var model = read("""
                <itemDefinition name="tAmounts" isCollection="true"><typeRef>number</typeRef></itemDefinition>
                <itemDefinition name="tRule">
                  <functionItem outputTypeRef="boolean"><parameters name="x" typeRef="number"/></functionItem>
                </itemDefinition>
                <itemDefinition name="tUnused"><typeRef>string</typeRef></itemDefinition>
                <inputData id="a" name="Amounts"><variable name="Amounts"/></inputData>
                <decision id="d" name="Typed">
                  <informationRequirement><requiredInput href="#a"/></informationRequirement>
                  <literalExpression><text>[Amounts instance of tAmounts, (function(n: number) n > 0) instance of tRule]</text></literalExpression>
                </decision>
                """);

        assertThat(model.types()).containsOnlyKeys("tAmounts", "tRule");
        assertThat(model.types()).containsEntry("tRule", new FeelType.FunctionOf(List.of(FeelType.of(ColumnType.NUMBER)),
                FeelType.of(ColumnType.BOOLEAN)));
        assertThat(graph(model).evaluate("Typed", Map.of("Amounts", List.of(1, 2)))).isEqualTo(List.of(true, true));
        assertThat(graph(model).evaluate("Typed", Map.of("Amounts", List.of("x")))).isEqualTo(List.of(false, true));
        var written = DmnReader.readModel(new ByteArrayInputStream(DmnWriter.write(model).getBytes(StandardCharsets.UTF_8)));
        assertThat(written.types()).isEqualTo(model.types());
    }

    @Test
    void a_literal_expression_that_invokes_a_built_in_function_is_evaluated() {
        // FEEL's built-in functions since 0.9 (#117)
        var model = read("""
                <decision id="d" name="D"><literalExpression><text>upper case("a") + "!"</text></literalExpression></decision>
                """);
        assertThat(graph(model).evaluate("D", Map.of())).isEqualTo("A!");
    }

    @Test
    void an_input_of_a_collection_of_collections_is_a_list_of_lists() {
        // the DMN TCK's 0009-append-flatten: an item definition that is a collection of another collection
        var model = read("""
                <itemDefinition name="tStrings" isCollection="true"><typeRef>string</typeRef></itemDefinition>
                <itemDefinition name="tNested" isCollection="true"><typeRef>tStrings</typeRef></itemDefinition>
                <inputData id="n" name="Nested"><variable name="Nested" typeRef="tNested"/></inputData>
                <decision id="d" name="D"><informationRequirement><requiredInput href="#n"/></informationRequirement>
                  <literalExpression><text>flatten(append(Nested, ["c"]))</text></literalExpression></decision>
                """);
        assertThat(graph(model).evaluate("D", Map.of("Nested", List.of(List.of("a"), List.of("b"))))).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void a_literal_expression_that_defines_an_external_function_is_rejected_when_the_model_is_read() {
        // Praecepta does not evaluate external functions, by design: the model is rejected
        assertThatThrownBy(() -> read("""
                <decision id="d" name="D"><literalExpression><text>function(a) external {java: {class: "C"}}</text></literalExpression></decision>
                """)).isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("uses an external function (Java or PMML), which Praecepta does not evaluate, by design")
                .satisfies(e -> assertThat(((UnsupportedFeatureException) e).release()).isNull());
    }
}
