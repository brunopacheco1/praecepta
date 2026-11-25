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
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
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
            assertThat(table.inputs()).extracting(InputColumn::name).containsExactly("Risk Category", "Income");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (var dmn = file("chain.dmn")) {
            assertThat(DmnReader.readAll(dmn)).extracting(DecisionTable::name).containsExactly("Risk Category", "Credit Limit");
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
            deep.append("<itemDefinition name=\"t%d\"><itemComponent name=\"c\"><typeRef>t%d</typeRef></itemComponent></itemDefinition>"
                    .formatted(i, i - 1)).append('\n');
        }
        deep.append("<inputData id=\"x\" name=\"X\"><variable name=\"X\" typeRef=\"t%d\"/></inputData>".formatted(DmnReader.MAX_TYPE_DEPTH + 1));

        var tooDeep = dmn(deep.toString());
        assertThatThrownBy(() -> read(tooDeep)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("nests more than 32 levels");
        var recursive = dmn("""
                <itemDefinition name="tNode"><itemComponent name="next"><typeRef>tNode</typeRef></itemComponent></itemDefinition>
                <inputData id="x" name="X"><variable name="X" typeRef="tNode"/></inputData>
                """);
        assertThatThrownBy(() -> read(recursive)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("'tNode' is defined in terms of itself");
    }

    @Test
    void a_requirement_that_refers_to_no_element_is_kept_as_the_id_it_refers_to() {
        var model = model("missing.dmn");

        assertThat(model.decision("A").orElseThrow().requiredDecisions()).containsExactly("nowhere");
        assertThat(model.decision("C").orElseThrow().requiredInputs()).containsExactly("D");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<decisionService id=\"s\" name=\"Service\"/><decision id=\"d\" name=\"D\"><knowledgeRequirement><requiredKnowledge href=\"#s\"/>"
                + "</knowledgeRequirement><literalExpression><text>Service()</text></literalExpression></decision>"
                + "|'D' invokes the decision service 'Service'|(#119)",
        "<import namespace=\"https://example.com/other\" name=\"other\" importType=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"/>"
                + "|imports 'other'|(#119)",
        "<decision id=\"d\" name=\"D\"><functionDefinition kind=\"Java\"><literalExpression><text>1</text></literalExpression>"
                + "</functionDefinition></decision>|defines a function in Java|(#119)",
        "<decision id=\"d\" name=\"D\"><informationRequirement><requiredDecision href=\"other.dmn#x\"/></informationRequirement>"
                + "<literalExpression><text>1</text></literalExpression></decision>|an element of another model|(#119)",
    })
    void what_praecepta_does_not_evaluate_yet_is_rejected_naming_the_issue_that_plans_it(String row) {
        var parts = row.split("\\|");
        var xml = dmn(parts[0]);

        assertThatThrownBy(() -> read(xml)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining(parts[1]).hasMessageContaining(parts[2]);
    }

    @Test
    void a_decision_without_logic_an_empty_literal_and_another_expression_language_are_rejected() {
        var noLogic = dmn("<decision id=\"d\" name=\"D\"><variable name=\"D\"/></decision><decision id=\"e\" name=\"E\">"
                + "<informationRequirement><requiredDecision href=\"#d\"/></informationRequirement>"
                + "<literalExpression><text>D</text></literalExpression></decision>");
        assertThatThrownBy(() -> read(noLogic))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessage("Decision 'D' has no decision logic; Praecepta evaluates "
                        + "decisions whose logic is a decision table, a literal expression or a boxed expression");
        var empty = dmn("<decision id=\"d\" name=\"D\"><literalExpression><text> </text></literalExpression></decision>");
        assertThatThrownBy(() -> read(empty))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessage("Decision 'D': its literal expression is empty");
        var xpath = dmn("<decision id=\"d\" name=\"D\"><literalExpression expressionLanguage=\"https://www.w3.org/TR/xpath\">"
                + "<text>1</text></literalExpression></decision>");
        assertThatThrownBy(() -> read(xpath))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("Praecepta reads FEEL");
    }

    @Test
    void boxed_expressions_are_read_with_their_parts_and_the_types_their_model_declares() {
        var model = model("boxed.dmn");
        var number = FeelType.of(ColumnType.NUMBER);
        var numbers = new FeelType.ListOf(number);

        var payments = (DecisionLogic.Context) model.decision("Payments").orElseThrow().logic();
        assertThat(payments.type()).isEqualTo(numbers);
        assertThat(payments.entries()).extracting(DecisionLogic.ContextEntry::name).containsExactly("payment", "all");
        var payment = (DecisionLogic.FunctionDefinition) payments.entries().getFirst().value();
        assertThat(payment.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.name()).isEqualTo("loan");
            assertThat(parameter.type().component("rate")).isEqualTo(number);
        });
        assertThat(payment.body().type()).isEqualTo(number);
        var all = (DecisionLogic.Iteration) payments.entries().get(1).value();
        assertThat(all.kind()).isEqualTo(DecisionLogic.Iteration.Kind.FOR);
        assertThat(all.type()).as("an entry's type is its variable's").isEqualTo(numbers);
        assertThat(all.body()).isEqualTo(new DecisionLogic.Invocation("payment",
                List.of(new DecisionLogic.Binding("loan", new DecisionLogic.LiteralExpression("loan"))), FeelType.ANY));
        assertThat(payments.result()).isEqualTo(new DecisionLogic.LiteralExpression("for p in all return decimal(p, 2)"));

        assertThat(model.decision("Affordable").orElseThrow().logic()).isEqualTo(new DecisionLogic.Filter(
                new DecisionLogic.LiteralExpression("Payments"), new DecisionLogic.LiteralExpression("item <= Limit"), FeelType.ANY));
        var verdict = (DecisionLogic.Conditional) model.decision("Verdict").orElseThrow().logic();
        assertThat(verdict.type()).isEqualTo(FeelType.of(ColumnType.STRING));
        assertThat(((DecisionLogic.Iteration) verdict.condition()).kind()).isEqualTo(DecisionLogic.Iteration.Kind.EVERY);
        assertThat(model.decision("Bounds").orElseThrow().logic()).isEqualTo(new DecisionLogic.BoxedList(List.of(
                new DecisionLogic.LiteralExpression("Limit / 2"), new DecisionLogic.LiteralExpression("Limit"),
                new DecisionLogic.LiteralExpression("Limit * 2", number)), numbers));
        var rates = (DecisionLogic.Relation) model.decision("Rates").orElseThrow().logic();
        assertThat(rates.columns()).containsExactly(new DecisionLogic.Parameter("term", number), new DecisionLogic.Parameter("rate", number));
        assertThat(rates.rows()).hasSize(2);
    }

    @Test
    void business_knowledge_models_are_read_with_their_knowledge_requirements_and_logic() {
        var model = model("knowledge.dmn");
        var number = FeelType.of(ColumnType.NUMBER);

        assertThat(model.knowledgeModels()).extracting(DecisionModel.BusinessKnowledgeModel::name).containsExactly("PMT", "Rounded PMT",
                "factorial");
        var pmt = model.knowledgeModel("PMT").orElseThrow();
        assertThat(pmt.requiredKnowledge()).isEmpty();
        assertThat(pmt.logic().type()).as("its variable's function type").isEqualTo(new FeelType.FunctionOf(List.of(number, number, number),
                number));
        assertThat(pmt.logic().parameters()).extracting(DecisionLogic.Parameter::name).containsExactly("principal", "rate", "months");
        assertThat(model.knowledgeModel("Rounded PMT").orElseThrow().requiredKnowledge()).containsExactly("PMT");
        var payment = model.decision("Monthly Payment").orElseThrow();
        assertThat(payment.requiredKnowledge()).containsExactly("PMT");
        assertThat(payment.requirements()).containsExactly("Principal", "Rate", "Months", "PMT");
        assertThat(payment.logic()).isInstanceOf(DecisionLogic.Invocation.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<businessKnowledgeModel id=\"k\" name=\"K\"/>|Business knowledge model 'K' has no encapsulated logic",
        "<businessKnowledgeModel id=\"k\" name=\"K\"><informationRequirement><requiredInput href=\"#x\"/></informationRequirement>"
                + "<encapsulatedLogic><literalExpression><text>1</text></literalExpression></encapsulatedLogic></businessKnowledgeModel>"
                + "|a business knowledge model has no information requirements",
        "<decision id=\"d\" name=\"D\"><knowledgeRequirement/><literalExpression><text>1</text></literalExpression></decision>"
                + "|a knowledge requirement must name the required knowledge",
        "<businessKnowledgeModel id=\"k\" name=\"K\"><encapsulatedLogic><literalExpression><text>1 +</text></literalExpression>"
                + "</encapsulatedLogic></businessKnowledgeModel>|Business knowledge model 'K': in the function's body",
    })
    void a_business_knowledge_model_that_is_not_well_formed_is_rejected(String row) {
        var parts = row.split("\\|");

        assertThatThrownBy(() -> read(dmn(parts[0]))).isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining(parts[1]);
    }

    @Test
    void a_decision_without_logic_or_a_decision_service_that_nothing_requires_is_left_out() {
        // the DMN TCK's 0087-chapter-11-example: a human decision, and services that group decisions
        var model = read(dmn("""
                <decision id="human" name="Adjudication"><variable name="Adjudication"/></decision>
                <decisionService id="s" name="Service"><outputDecision href="#d"/></decisionService>
                <decision id="d" name="D"><literalExpression><text>1</text></literalExpression></decision>"""));

        assertThat(model.decisions()).extracting(DecisionModel.Decision::name).containsExactly("D");
    }

    @Test
    void a_decision_table_within_a_boxed_expression_is_named_after_what_holds_it_and_reads_its_names() {
        var model = model("nested-tables.dmn");

        var description = model.knowledgeModel("Description").orElseThrow().logic().body();
        assertThat(description).isInstanceOfSatisfying(DecisionLogic.Table.class, table -> {
            assertThat(table.table().name()).isEqualTo("Description");
            assertThat(table.table().inputs()).extracting(InputColumn::name).containsExactly("Person.Gender", "Person.Name");
        });
        var context = (DecisionLogic.Context) model.decision("Greeting").orElseThrow().logic();
        assertThat(context.entries().get(1).value()).isInstanceOfSatisfying(DecisionLogic.Table.class,
                table -> assertThat(table.table().name()).isEqualTo("salutation"));
    }

    @Test
    void a_name_that_holds_a_number_is_read_as_the_requirement_it_names() {
        // the DMN TCK's 0034-drg-scopes: the text is not FEEL but with its requirement's name in scope
        var model = read(dmn("""
                <inputData id="a" name="decision A 2.1"/>
                <decision id="d" name="D">
                  <informationRequirement><requiredInput href="#a"/></informationRequirement>
                  <literalExpression><text>decision A 2.1</text></literalExpression>
                </decision>"""));

        assertThat(model.decision("D").orElseThrow().logic()).isEqualTo(new DecisionLogic.LiteralExpression("decision A 2.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<conditional><if><literalExpression><text>true</text></literalExpression></if></conditional>|its <then> holds no expression",
        "<context><contextEntry><literalExpression><text>1</text></literalExpression></contextEntry><contextEntry><variable name=\"a\"/>"
                + "<literalExpression><text>1</text></literalExpression></contextEntry></context>|only a context's last entry may have no variable",
        "<context><contextEntry><variable name=\"a\"/><literalExpression><text>1</text></literalExpression></contextEntry>"
                + "<contextEntry><variable name=\"a\"/><literalExpression><text>2</text></literalExpression></contextEntry></context>"
                + "|two entries named 'a'",
        "<for><in><literalExpression><text>[1]</text></literalExpression></in></for>|the iterator has no iteratorVariable",
        "<invocation><binding><parameter name=\"x\"/></binding></invocation>|names the function it invokes with a literal expression",
        "<list><literalExpression><text>1 +</text></literalExpression></list>|in element 1 of the list",
        "<relation><column name=\"a\"/><row/></relation>|a row of the relation has 0 cells for 1 columns",
    })
    void a_boxed_expression_that_is_not_well_formed_is_rejected_saying_where(String row) {
        var parts = row.split("\\|");
        var xml = dmn("<decision id=\"d\" name=\"D\">" + parts[0] + "</decision>");

        assertThatThrownBy(() -> read(xml)).isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("Decision 'D'")
                .hasMessageContaining(parts[1]);
    }

    @Test
    void two_elements_of_the_same_name_are_rejected() {
        var xml = dmn("""
                <inputData id="a" name="X"/>
                <decision id="b" name="X"><literalExpression><text>1</text></literalExpression></decision>
                """);
        assertThatThrownBy(() -> read(xml)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("'X' names two elements of the decision model");
    }

    @Test
    void an_information_requirement_must_name_one_required_element() {
        var xml = dmn("""
                <decision id="b" name="B"><informationRequirement/><literalExpression><text>1</text></literalExpression></decision>
                """);
        assertThatThrownBy(() -> read(xml)).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("either a required decision or a required input");
    }

    // ---- writing -----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"chain.dmn", "diamond.dmn", "multi-output.dmn", "multiple-results.dmn", "literals.dmn", "cycle.dmn",
        "missing-free.dmn", "type-mismatch.dmn", "untyped.dmn", "chain-reversed.dmn", "boxed.dmn", "knowledge.dmn", "nested-tables.dmn"})
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

    /**
     * A type of the model under the name the writer gives an element's item definition is written once, and the element's
     * type is that one; a type of the model of that name that is another type leaves the element the next free name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"number", "string"})
    void a_type_of_the_model_named_as_an_element_type_is_written_once(String principal) {
        var xml = dmn("""
                <itemDefinition name="input_data_0_type">
                  <itemComponent name="principal"><typeRef>%s</typeRef></itemComponent>
                </itemDefinition>
                <itemDefinition name="tLoan"><itemComponent name="principal"><typeRef>number</typeRef></itemComponent></itemDefinition>
                <inputData id="l" name="Loan"><variable name="Loan" typeRef="tLoan"/></inputData>
                <decision id="d" name="Typed">
                  <informationRequirement><requiredInput href="#l"/></informationRequirement>
                  <literalExpression><text>Loan instance of input_data_0_type</text></literalExpression>
                </decision>""".formatted(principal));
        var model = read(xml);

        var written = DmnWriter.write(model);
        var read = read(written);

        assertThat(read).isEqualTo(model);
        assertThat(DmnWriter.write(read)).isEqualTo(written);
        assertThat(Pattern.compile("<itemDefinition name=\"input_data_0_type\"").matcher(written).results().count()).isOne();
        assertThat(written).contains(principal.equals("number") ? "typeRef=\"input_data_0_type\"" : "typeRef=\"input_data_0_type_1\"");
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
