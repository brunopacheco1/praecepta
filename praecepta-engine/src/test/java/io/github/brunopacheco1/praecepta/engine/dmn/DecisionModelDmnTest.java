/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/** Decision models read from DMN by {@link DmnReader#readModel} and written back by {@link DmnWriter#write(DecisionModel)}. */
class DecisionModelDmnTest {

    private static InputStream file(String name) {
        return DecisionModelDmnTest.class.getResourceAsStream("/dmn/graph/" + name);
    }

    private static DecisionModel model(String name) {
        try (var dmn = file(name)) {
            return DmnReader.readModel(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecisionModel read(String xml) {
        return DmnReader.readModel(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** A DMN model of the given elements, in the DMN 1.5 namespace. */
    private static String dmn(String elements) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="m" name="M" namespace="https://example.com/m">
                %s
                </definitions>
                """.formatted(elements);
    }

    // ---- reading -----------------------------------------------------------------------------------

    @Test
    void a_model_is_read_with_its_input_data_decisions_requirements_and_logic() {
        var model = model("chain.dmn");

        assertThat(model.name()).isEqualTo("Credit");
        assertThat(model.inputData()).containsExactly(new DecisionModel.InputData("Applicant Age", FeelType.of(ColumnType.NUMBER)),
                new DecisionModel.InputData("Income", FeelType.of(ColumnType.NUMBER)));
        var limit = model.decision("Credit Limit").orElseThrow();
        assertThat(limit.requiredDecisions()).containsExactly("Risk Category");
        assertThat(limit.requiredInputs()).containsExactly("Income");
        assertThat(limit.logic()).isInstanceOf(DecisionLogic.Table.class);
        assertThat(model.decision("Monthly Limit").orElseThrow().logic())
                .isEqualTo(new DecisionLogic.LiteralExpression("Credit Limit / 12", FeelType.of(ColumnType.NUMBER)));
    }

    @Test
    void reading_one_table_of_a_model_with_several_decisions_works_as_before() {
        try (var dmn = file("chain.dmn")) {
            var table = DmnReader.read(dmn, "Credit Limit");
            assertThat(table.inputs()).extracting(column -> column.name()).containsExactly("Risk Category", "Income");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (var dmn = file("chain.dmn")) {
            assertThat(DmnReader.readAll(dmn)).extracting(table -> table.name()).containsExactly("Risk Category", "Credit Limit");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void input_data_types_may_be_lists_and_contexts_of_any_depth_up_to_the_limit() {
        var model = read(dmn("""
                <itemDefinition name="tApplicant">
                  <itemComponent name="name"><typeRef>string</typeRef></itemComponent>
                  <itemComponent name="scores" isCollection="true"><typeRef>number</typeRef></itemComponent>
                  <itemComponent name="address">
                    <itemComponent name="city"><typeRef>string</typeRef></itemComponent>
                  </itemComponent>
                  <itemComponent name="extra"><typeRef>Any</typeRef></itemComponent>
                </itemDefinition>
                <itemDefinition name="tApplicants" isCollection="true"><typeRef>tApplicant</typeRef></itemDefinition>
                <inputData id="a" name="Applicants"><variable name="Applicants" typeRef="tApplicants"/></inputData>
                <inputData id="b" name="Anything"><variable name="Anything"/></inputData>
                """));

        var applicant = new FeelType.Context(Map.of("name", FeelType.of(ColumnType.STRING), "scores", FeelType.listOf(ColumnType.NUMBER),
                "address", new FeelType.Context(Map.of("city", FeelType.of(ColumnType.STRING))), "extra", FeelType.ANY));
        assertThat(model.inputData("Applicants").orElseThrow().type()).isEqualTo(new FeelType.ListOf(applicant));
        assertThat(model.inputData("Applicants").orElseThrow().type().feelName())
                .isEqualTo("list<context<name: string, scores: list<number>, address: context<city: string>, extra: Any>>");
        assertThat(model.inputData("Anything").orElseThrow().type()).isEqualTo(FeelType.ANY);
    }

    @Test
    void a_type_nested_beyond_the_limit_or_defined_in_terms_of_itself_is_rejected() {
        var deep = new StringBuilder("<itemDefinition name=\"t0\"><typeRef>string</typeRef></itemDefinition>\n");
        for (int i = 1; i <= DmnReader.MAX_TYPE_DEPTH + 1; i++) {
            deep.append("<itemDefinition name=\"t%d\"><itemComponent name=\"c\"><typeRef>t%d</typeRef></itemComponent></itemDefinition>\n"
                    .formatted(i, i - 1));
        }
        deep.append("<inputData id=\"x\" name=\"X\"><variable name=\"X\" typeRef=\"t%d\"/></inputData>".formatted(DmnReader.MAX_TYPE_DEPTH + 1));

        assertThatThrownBy(() -> read(dmn(deep.toString()))).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("nests more than 32 levels");
        assertThatThrownBy(() -> read(dmn("""
                <itemDefinition name="tNode"><itemComponent name="next"><typeRef>tNode</typeRef></itemComponent></itemDefinition>
                <inputData id="x" name="X"><variable name="X" typeRef="tNode"/></inputData>
                """))).isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("'tNode' is defined in terms of itself");
    }

    @Test
    void a_requirement_that_refers_to_no_element_is_kept_as_the_id_it_refers_to() {
        var model = model("missing.dmn");

        assertThat(model.decision("A").orElseThrow().requiredDecisions()).containsExactly("nowhere");
        assertThat(model.decision("C").orElseThrow().requiredInputs()).containsExactly("D");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<businessKnowledgeModel id=\"k\" name=\"Rates\"/>|business knowledge model 'Rates'|(#118)",
        "<decisionService id=\"s\" name=\"Service\"/>|decision service 'Service'|(#119)",
        "<import namespace=\"https://example.com/other\" name=\"other\" importType=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"/>"
                + "|imports 'other'|(#119)",
        "<decision id=\"d\" name=\"D\"><knowledgeRequirement><requiredKnowledge href=\"#k\"/></knowledgeRequirement>"
                + "<literalExpression><text>1</text></literalExpression></decision>|Decision 'D' requires business knowledge|(#118)",
        "<decision id=\"d\" name=\"D\"><context/></decision>|its logic is a boxed context|(#118)",
        "<decision id=\"d\" name=\"D\"><invocation/></decision>|its logic is a boxed invocation|(#118)",
        "<decision id=\"d\" name=\"D\"><relation/></decision>|its logic is a relation|(#118)",
        "<decision id=\"d\" name=\"D\"><conditional/></decision>|its logic is a boxed conditional|(#118)",
        "<decision id=\"d\" name=\"D\"><functionDefinition/></decision>|its logic is a function definition|(#118)",
        "<decision id=\"d\" name=\"D\"><literalExpression><text>upper case(\"a\")</text></literalExpression></decision>"
                + "|function calls such as upper case(...)|built-in functions are planned for 0.9 (#117)",
        "<decision id=\"d\" name=\"D\"><literalExpression><text>if true then 1 else 2</text></literalExpression></decision>"
                + "|is not in Praecepta's expression subset|the full FEEL language is planned for 0.8 (#116)",
        "<decision id=\"d\" name=\"D\"><informationRequirement><requiredDecision href=\"other.dmn#x\"/></informationRequirement>"
                + "<literalExpression><text>1</text></literalExpression></decision>|an element of another model|(#119)",
    })
    void what_praecepta_does_not_evaluate_yet_is_rejected_naming_the_issue_that_plans_it(String row) {
        var parts = row.split("\\|");

        assertThatThrownBy(() -> read(dmn(parts[0]))).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining(parts[1]).hasMessageContaining(parts[2]);
    }

    @Test
    void a_decision_without_logic_an_empty_literal_and_another_expression_language_are_rejected() {
        assertThatThrownBy(() -> read(dmn("<decision id=\"d\" name=\"D\"><variable name=\"D\"/></decision>")))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessage("Decision 'D' has no decision logic; Praecepta evaluates "
                        + "decisions whose logic is a decision table or a literal expression");
        assertThatThrownBy(() -> read(dmn("<decision id=\"d\" name=\"D\"><literalExpression><text> </text></literalExpression></decision>")))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessage("Decision 'D': its literal expression is empty");
        assertThatThrownBy(() -> read(dmn("<decision id=\"d\" name=\"D\"><literalExpression expressionLanguage=\"https://www.w3.org/TR/xpath\">"
                + "<text>1</text></literalExpression></decision>")))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("Praecepta reads FEEL");
    }

    @Test
    void two_elements_of_the_same_name_are_rejected() {
        assertThatThrownBy(() -> read(dmn("""
                <inputData id="a" name="X"/>
                <decision id="b" name="X"><literalExpression><text>1</text></literalExpression></decision>
                """))).isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("'X' names two elements of the decision model");
    }

    @Test
    void an_information_requirement_must_name_one_required_element() {
        assertThatThrownBy(() -> read(dmn("""
                <decision id="b" name="B"><informationRequirement/><literalExpression><text>1</text></literalExpression></decision>
                """))).isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("either a required decision or a required input");
    }

    // ---- writing -----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"chain.dmn", "diamond.dmn", "multi-output.dmn", "multiple-results.dmn", "literals.dmn", "cycle.dmn",
        "missing-free.dmn", "type-mismatch.dmn", "untyped.dmn", "chain-reversed.dmn"})
    void a_model_written_as_dmn_reads_back_as_the_same_model_and_writes_the_same(String name) {
        var model = name.equals("missing-free.dmn") ? withoutMissing(model("missing.dmn")) : model(name);

        var xml = DmnWriter.write(model);
        var read = read(xml);

        assertThat(read).isEqualTo(model);
        assertThat(DmnWriter.write(read)).isEqualTo(xml);
        var ids = new HashSet<String>();
        Pattern.compile(" id=\"([^\"]*)\"").matcher(xml).results().forEach(id -> assertThat(ids.add(id.group(1))).as("unique id %s", id.group(1))
                .isTrue());
    }

    /** The model, without the decision whose requirement refers to no element, which DMN cannot write. */
    private static DecisionModel withoutMissing(DecisionModel model) {
        return new DecisionModel(model.name(), model.inputData(),
                model.decisions().stream().filter(decision -> !decision.name().equals("A")).toList());
    }

    @Test
    void a_model_written_and_read_back_evaluates_as_the_original() {
        var original = model("multiple-results.dmn");
        var copy = read(DmnWriter.write(original));
        var graph = DecisionGraph.builder().model(original).logValidationReport(false).build();
        var copied = DecisionGraph.builder().model(copy).logValidationReport(false).build();

        for (var amount : List.of(-5, 20, 20000)) {
            for (var country : List.of("PT", "XX")) {
                var inputs = Map.<String, Object>of("Amount", amount, "Country", country);
                assertThat(copied.evaluate(copied.decisions(), inputs)).isEqualTo(graph.evaluate(graph.decisions(), inputs));
            }
        }
        var literals = DecisionGraph.builder().model(model("literals.dmn")).logValidationReport(false).build();
        var literalsCopy = DecisionGraph.builder().model(read(DmnWriter.write(model("literals.dmn")))).logValidationReport(false).build();
        var inputs = Map.<String, Object>of("Start", LocalDate.of(2025, 1, 1), "Full Name", "Ada", "Hours", 2,
                "Loan", Map.of("principal", 1200, "rate", new BigDecimal("0.12"), "months", 6));
        assertThat(literalsCopy.evaluate(literalsCopy.decisions(), inputs)).isEqualTo(literals.evaluate(literals.decisions(), inputs));
        assertThat(literalsCopy.evaluate("Due", inputs)).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(literalsCopy.evaluate("Payment", Map.of("Loan", Map.of("principal", 1200, "rate", 0, "months", 12))))
                .as("a division by zero is null").isNull();
        assertThat((BigDecimal) literalsCopy.evaluate("Payment", inputs)).isPositive();
    }

    @Test
    void a_requirement_the_model_does_not_have_cannot_be_written() {
        var model = model("missing.dmn");

        assertThatThrownBy(() -> DmnWriter.write(model)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'A' requires 'nowhere', which is not in the model, so DMN cannot refer to it");
    }

    @Test
    void a_list_of_lists_cannot_be_written() {
        var model = new DecisionModel("M", List.of(new DecisionModel.InputData("X", new FeelType.ListOf(FeelType.listOf(ColumnType.NUMBER)))),
                List.of());

        assertThatThrownBy(() -> DmnWriter.write(model)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("input data 'X' is a list of lists");
    }
}
