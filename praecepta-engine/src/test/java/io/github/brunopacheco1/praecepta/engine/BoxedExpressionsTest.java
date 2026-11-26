/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/** Boxed expressions (DMN 1.5, 10.2) as decision logic, as a decision requirements graph evaluates them (#118). */
class BoxedExpressionsTest {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);

    private static DecisionModel model(String name) {
        try (var dmn = BoxedExpressionsTest.class.getResourceAsStream("/dmn/graph/" + name)) {
            return DmnReader.readModel(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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

    private static final Map<String, Object> LOANS = Map.of("Limit", 500, "Loans", List.of(
            Map.of("principal", 1200, "rate", new BigDecimal("0.12"), "months", 12),
            Map.of("principal", 24000, "rate", new BigDecimal("0.06"), "months", 24)));

    @Test
    void every_kind_of_boxed_expression_is_evaluated_as_the_feel_it_boxes() {
        var graph = graph(model("boxed.dmn"));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(graph.requiredInputData("Payments")).containsExactly("Loans");
        assertThat(graph.evaluate("Payments", LOANS)).isEqualTo(List.of(new BigDecimal("106.62"), new BigDecimal("1063.69")));
        assertThat(graph.evaluate("Affordable", LOANS)).isEqualTo(List.of(new BigDecimal("106.62")));
        assertThat(graph.evaluate("Verdict", LOANS)).isEqualTo("some affordable");
        assertThat(graph.evaluate("Verdict", Map.of("Limit", 5000, "Loans", LOANS.get("Loans")))).isEqualTo("all affordable");
        assertThat(graph.evaluate("Verdict", Map.of("Limit", 1, "Loans", LOANS.get("Loans")))).isEqualTo("none affordable");
        assertThat(graph.evaluate("Bounds", LOANS)).isEqualTo(List.of(new BigDecimal("250"), new BigDecimal("500"), new BigDecimal("1000")));
        assertThat(graph.evaluate("Rates", Map.of())).isEqualTo(List.of(
                Map.of("term", new BigDecimal("12"), "rate", new BigDecimal("0.05")),
                Map.of("term", new BigDecimal("24"), "rate", new BigDecimal("0.06"))));
        assertThat(graph.type("Payments")).isEqualTo(new FeelType.ListOf(NUMBER));
        assertThat(graph.type("Rates")).isEqualTo(new FeelType.ListOf(new FeelType.Context(Map.of("term", NUMBER, "rate", NUMBER))));
    }

    @Test
    void a_model_of_boxed_expressions_written_as_dmn_evaluates_as_the_original() {
        var original = model("boxed.dmn");
        var written = DmnWriter.write(original);
        var copy = graph(DmnReader.readModel(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8))));

        assertThat(written).as("conditionals, iterators and filters are DMN 1.5's").contains("https://www.omg.org/spec/DMN/20230324/MODEL/");
        assertThat(copy.evaluate(copy.decisions(), LOANS)).isEqualTo(graph(original).evaluate(copy.decisions(), LOANS));
    }

    /** DMN 1.5, 10.2.7 to 10.2.9, as the DMN TCK's 1150-boxed-conditional to 1154-boxed-every have them. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "<conditional><if><literalExpression><text>\"abc\"</text></literalExpression></if><then><literalExpression><text>1</text>"
                + "</literalExpression></then><else><literalExpression><text>2</text></literalExpression></else></conditional>",
        "<filter><in><literalExpression><text>[1, 2, 3]</text></literalExpression></in><match><literalExpression>"
                + "<text>if item &lt; 3 then true else \"no\"</text></literalExpression></match></filter>",
        "<some iteratorVariable=\"x\"><in><literalExpression><text>[1, 2]</text></literalExpression></in><satisfies><literalExpression>"
                + "<text>if x = 2 then true else \"no\"</text></literalExpression></satisfies></some>",
        "<every iteratorVariable=\"x\"><in><literalExpression><text>[1, 2]</text></literalExpression></in><satisfies><literalExpression>"
                + "<text>if x = 1 then true else \"no\"</text></literalExpression></satisfies></every>",
    })
    void a_boxed_condition_that_is_not_a_boolean_makes_its_construct_null_with_an_error(String logic) {
        var graph = graph(read("<decision id=\"d\" name=\"D\">" + logic + "</decision>"));

        var outcome = graph.evaluateWithErrors("D", Map.of());

        assertThat(outcome.value()).isNull();
        assertThat(outcome.errors().get("D")).anyMatch(error -> error.contains("not a boolean"));
    }

    @Test
    void a_null_condition_is_not_true_and_gives_no_error() {
        var graph = graph(read("""
                <decision id="d" name="D"><conditional><if><literalExpression><text>null</text></literalExpression></if>
                <then><literalExpression><text>1</text></literalExpression></then><else><literalExpression><text>2</text></literalExpression></else>
                </conditional></decision>"""));

        var outcome = graph.evaluateWithErrors("D", Map.of());

        assertThat(outcome.value()).isEqualTo(new BigDecimal("2"));
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void a_context_entry_named_as_a_requirement_reads_the_requirement_and_hides_it_from_the_entries_after_it() {
        // the DMN TCK's 1146-feel-context-put-function, decision015
        var graph = graph(read("""
                <inputData id="c" name="c"><variable name="c"/></inputData>
                <decision id="d" name="D">
                  <informationRequirement><requiredInput href="#c"/></informationRequirement>
                  <context>
                    <contextEntry><variable name="c"/><literalExpression><text>c + 1</text></literalExpression></contextEntry>
                    <contextEntry><variable name="twice"/><literalExpression><text>c * 2</text></literalExpression></contextEntry>
                  </context>
                </decision>"""));

        assertThat(graph.evaluate("D", Map.of("c", 1))).isEqualTo(Map.of("c", new BigDecimal("2"), "twice", new BigDecimal("4")));
    }

    @Test
    void a_function_a_context_entry_defines_may_invoke_itself() {
        var graph = graph(read("""
                <decision id="d" name="D">
                  <context>
                    <contextEntry>
                      <variable name="fib"/>
                      <functionDefinition>
                        <formalParameter name="n" typeRef="number"/>
                        <literalExpression><text>if n &lt; 2 then n else fib(n - 1) + fib(n - 2)</text></literalExpression>
                      </functionDefinition>
                    </contextEntry>
                    <contextEntry><literalExpression><text>fib(10)</text></literalExpression></contextEntry>
                  </context>
                </decision>"""));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(graph.evaluate("D", Map.of())).isEqualTo(new BigDecimal("55"));
    }

    @Test
    void a_part_is_bound_to_the_type_its_model_declares() {
        var graph = graph(read("""
                <decision id="d" name="D">
                  <context>
                    <contextEntry><variable name="list" typeRef="number"/><literalExpression><text>[7]</text></literalExpression></contextEntry>
                    <contextEntry><variable name="wrong" typeRef="string"/><literalExpression><text>7</text></literalExpression></contextEntry>
                  </context>
                </decision>"""));

        var outcome = graph.evaluateWithErrors("D", Map.of());

        var context = new LinkedHashMap<String, Object>();
        context.put("list", new BigDecimal("7"));
        context.put("wrong", null);
        assertThat(outcome.value()).as("a list of one to its element, a number not to a string").isEqualTo(context);
        assertThat(outcome.errors().get("D")).anyMatch(error -> error.contains("not a string"));
    }

    @Test
    void an_invocation_binds_its_arguments_by_parameter_name_and_leaves_the_others_null() {
        var graph = graph(read("""
                <decision id="d" name="D">
                  <context>
                    <contextEntry><variable name="f"/>
                      <literalExpression><text>function(a, b) if b = null then a else a - b</text></literalExpression></contextEntry>
                    <contextEntry><variable name="both"/><invocation><literalExpression><text>f</text></literalExpression>
                      <binding><parameter name="b"/><literalExpression><text>1</text></literalExpression></binding>
                      <binding><parameter name="a"/><literalExpression><text>10</text></literalExpression></binding>
                    </invocation></contextEntry>
                    <contextEntry><variable name="one"/><invocation><literalExpression><text>f</text></literalExpression>
                      <binding><parameter name="a"/><literalExpression><text>10</text></literalExpression></binding>
                      <binding><parameter name="b"/></binding>
                    </invocation></contextEntry>
                    <contextEntry><literalExpression><text>[both, one]</text></literalExpression></contextEntry>
                  </context>
                </decision>"""));

        assertThat(graph.evaluate("D", Map.of())).isEqualTo(List.of(new BigDecimal("9"), new BigDecimal("10")));
    }

    @Test
    void a_name_a_boxed_expression_reads_that_no_requirement_provides_is_reported() {
        var graph = graph(read("""
                <decision id="d" name="D"><list><literalExpression><text>Missing</text></literalExpression></list></decision>"""));

        assertThat(graph.validationReport().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.MISSING_REQUIREMENT);
            assertThat(issue.detail()).contains("reads 'Missing'");
        });
        assertThat(graph.canEvaluate("D")).isFalse();
    }

    @Test
    void a_decision_table_within_a_boxed_expression_is_composed_only_where_the_caller_embeds_it() {
        var table = new DecisionLogic.Table(Tables.table("t", "UNIQUE", List.of("a:number"), List.of("b:number"), List.of(List.of("1", "2"))));
        var context = new DecisionLogic.Context(List.of(new DecisionLogic.ContextEntry("t", table)), null, FeelType.ANY);

        assertThatThrownBy(() -> FeelExpression.compose(context, FeelExpression.namesScope(List.of())))
                .isInstanceOf(ExpressionSyntaxException.class).hasMessageContaining("in context entry 't'")
                .hasMessageContaining("only within a decision model");
    }
}
