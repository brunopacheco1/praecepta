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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Business knowledge models (DMN 1.5, 6.3.10) and their invocations, and decision tables within boxed expressions, as a decision
 * requirements graph evaluates them (#118).
 */
class KnowledgeModelsTest {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final Map<String, Object> LOAN = Map.of("Principal", 24000, "Rate", new BigDecimal("0.06"), "Months", 24);

    private static DecisionModel model(String name) {
        try (var dmn = KnowledgeModelsTest.class.getResourceAsStream("/dmn/graph/" + name)) {
            return DmnReader.readModel(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecisionGraph graph(DecisionModel model) {
        return DecisionGraph.builder().model(model).logValidationReport(false).build();
    }

    @Test
    void a_decision_invokes_the_business_knowledge_models_it_requires_which_invoke_those_they_require_and_themselves() {
        var graph = graph(model("knowledge.dmn"));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat((BigDecimal) graph.evaluate("Monthly Payment", LOAN)).isEqualByComparingTo("1063.694646066165713742983357940095");
        assertThat(graph.evaluate("Rounded Payment", LOAN)).isEqualTo(new BigDecimal("1063.69"));
        assertThat(graph.evaluate("Months Factorial", Map.of("Months", 5))).isEqualTo(new BigDecimal("120"));
        assertThat(graph.requiredInputData("Rounded Payment")).containsExactly("Principal", "Rate", "Months");
        assertThat(graph.type("Rounded Payment")).isEqualTo(NUMBER);
    }

    @Test
    void a_model_with_business_knowledge_models_written_as_dmn_evaluates_as_the_original() {
        var original = model("knowledge.dmn");
        var written = DmnWriter.write(original);
        var copy = graph(DmnReader.readModel(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8))));

        assertThat(copy.model().knowledgeModels()).isEqualTo(original.knowledgeModels());
        assertThat(copy.evaluate(copy.decisions(), LOAN)).isEqualTo(graph(original).evaluate(copy.decisions(), LOAN));
    }

    @Test
    void a_business_knowledge_model_invoked_from_outside_gives_its_value_bound_to_the_output_type_its_function_type_declares() {
        // the DMN TCK's 1157-implicit-conversions: a number, where the function type gives a list of numbers, is a list of one
        var listOfTwice = new DecisionModel.BusinessKnowledgeModel("twice", List.of(), new DecisionLogic.FunctionDefinition(
                List.of(new DecisionLogic.Parameter("x", NUMBER)), new DecisionLogic.LiteralExpression("x * 2"),
                new FeelType.FunctionOf(List.of(NUMBER), new FeelType.ListOf(NUMBER))));
        var graph = graph(new DecisionModel("M", List.of(), List.of(), List.of(listOfTwice), List.of(), Map.of()));

        assertThat(graph.knowledgeModels()).containsExactly("twice");
        var outcome = graph.invokeKnowledgeModel("twice", Map.of("x", 2));
        assertThat(outcome.value()).isEqualTo(List.of(new BigDecimal("4")));
        assertThat(outcome.errors()).isEmpty();
        assertThat(graph.invokeKnowledgeModel("twice", Map.of("x", "two")).errors()).containsKey("twice");
        assertThatThrownBy(() -> graph.invokeKnowledgeModel("thrice", Map.of())).isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("has no business knowledge model named 'thrice'");
    }

    @Test
    void invoking_a_function_nothing_in_the_model_names_is_null_with_an_error_and_one_it_does_not_require_is_reported() {
        // the DMN TCK's 1131-feel-function-invocation
        var twice = new DecisionModel.BusinessKnowledgeModel("twice", List.of(), new DecisionLogic.FunctionDefinition(
                List.of(new DecisionLogic.Parameter("x", NUMBER)), new DecisionLogic.LiteralExpression("x * 2"), FeelType.ANY));
        var missing = new DecisionModel.Decision("Missing", List.of(), List.of(), new DecisionLogic.LiteralExpression("non_existing_function(1)"));
        var unrequired = new DecisionModel.Decision("Unrequired", List.of(), List.of(), new DecisionLogic.LiteralExpression("twice(1)"));
        var graph = graph(new DecisionModel("M", List.of(), List.of(missing, unrequired), List.of(twice), List.of(), Map.of()));

        assertThat(graph.validationReport().issues()).satisfiesExactlyInAnyOrder(
                issue -> {
                    assertThat(issue.kind()).isEqualTo(Kind.ALWAYS_NULL);
                    assertThat(issue.detail()).contains("no function named 'non_existing_function' is in scope");
                },
                issue -> {
                    assertThat(issue.kind()).isEqualTo(Kind.MISSING_REQUIREMENT);
                    assertThat(issue.detail())
                            .contains("decision 'Unrequired' invokes 'twice', which is a business knowledge model it does not require");
                });
        var outcome = graph.evaluateWithErrors("Missing", Map.of());
        assertThat(outcome.value()).isNull();
        assertThat(outcome.errors().get("Missing")).anyMatch(error -> error.contains("no function named 'non_existing_function'"));
        assertThat(graph.canEvaluate("Unrequired")).isFalse();
    }

    @Test
    void a_business_knowledge_model_built_in_code_is_invoked_by_the_decisions_that_require_it() {
        var twice = new DecisionModel.BusinessKnowledgeModel("twice", List.of(), new DecisionLogic.FunctionDefinition(
                List.of(new DecisionLogic.Parameter("x", NUMBER)), new DecisionLogic.LiteralExpression("x * 2"), FeelType.ANY));
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("Amount", ColumnType.NUMBER)
                .knowledgeModel(twice)
                .decision(new DecisionModel.Decision("Doubled", List.of(), List.of("Amount"), List.of("twice"),
                        new DecisionLogic.LiteralExpression("twice(Amount)")))
                .build();

        assertThat(graph.evaluate("Doubled", Map.of("Amount", 21))).isEqualTo(new BigDecimal("42"));
        assertThat(graph.type("Doubled")).isEqualTo(NUMBER);
    }

    @Test
    void what_a_business_knowledge_model_reads_or_requires_that_the_model_does_not_provide_is_reported() {
        var reads = new DecisionModel.BusinessKnowledgeModel("reads", List.of(), new DecisionLogic.FunctionDefinition(
                List.of(new DecisionLogic.Parameter("x", FeelType.ANY)), new DecisionLogic.LiteralExpression("x + Amount"), FeelType.ANY));
        var requires = new DecisionModel.BusinessKnowledgeModel("requires", List.of("nowhere"), new DecisionLogic.FunctionDefinition(
                List.of(), new DecisionLogic.LiteralExpression("1"), FeelType.ANY));
        var indirect = new DecisionModel.BusinessKnowledgeModel("indirect", List.of("requires"), new DecisionLogic.FunctionDefinition(
                List.of(), new DecisionLogic.LiteralExpression("requires()"), FeelType.ANY));
        var model = new DecisionModel("M", List.of(new DecisionModel.InputData("Amount", NUMBER)), List.of(
                new DecisionModel.Decision("A", List.of(), List.of("Amount"), List.of("reads"), new DecisionLogic.LiteralExpression("reads(1)")),
                new DecisionModel.Decision("B", List.of(), List.of(), List.of("indirect"), new DecisionLogic.LiteralExpression("indirect()")),
                new DecisionModel.Decision("C", List.of(), List.of(), List.of("Amount"), new DecisionLogic.LiteralExpression("1")),
                new DecisionModel.Decision("E", List.of(), List.of(), List.of(), new DecisionLogic.LiteralExpression("2"))),
                List.of(reads, requires, indirect), Map.of());

        var graph = graph(model);

        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::kind).containsOnly(Kind.MISSING_REQUIREMENT);
        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::detail).containsExactly(
                "business knowledge model 'reads' reads 'Amount', which is neither one of its parameters nor a business knowledge model it "
                        + "requires (DMN 1.5, 6.3.10)",
                "business knowledge model 'requires' requires business knowledge model 'nowhere', which is not in the model",
                "decision 'C' requires business knowledge model 'Amount', which is input data, not a business knowledge model");
        assertThat(List.of("A", "B", "C", "E")).map(graph::canEvaluate).containsExactly(false, false, false, true);
    }

    @Test
    void a_decision_table_within_a_boxed_expression_reads_the_names_in_scope_where_it_is_and_keeps_its_report() {
        var graph = graph(model("nested-tables.dmn"));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(graph.evaluate("Person's description", Map.of("Person", Map.of("Name", "Bob", "Gender", "Male")))).isEqualTo("Bob is a man");
        assertThat(graph.evaluate("Greeting", Map.of("Person", Map.of("Name", "Eve", "Gender", "Female")))).isEqualTo("Ms Eve");
        assertThat(graph.validationReport("Description").issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.OVERLAPPING_RULES);
            assertThat(issue.ruleIds()).containsExactly(2, 3);
        });
        assertThat(graph.validationReport("Greeting").issues()).isEmpty();
        assertThatThrownBy(() -> graph.evaluate("Person's description", Map.of("Person", Map.of("Name", "Ada", "Gender", "Female"))))
                .isInstanceOf(HitPolicyViolationException.class);
        assertThatThrownBy(() -> DecisionGraph.builder().model(model("nested-tables.dmn")).logValidationReport(false)
                .failOn(ValidationReport.Severity.ERROR).build())
                .isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("business knowledge model 'Description'");
    }

    @Test
    void a_column_whose_input_expression_is_no_feel_name_reads_the_requirement_of_that_name() {
        // the DMN TCK's 0004-lending: Pre-bureauRiskCategory is a decision, whose name FEEL reads only where it is in scope
        var graph = graph(DmnReader.readModel(new ByteArrayInputStream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="m" name="M" namespace="https://example.com/m">
                  <inputData id="c" name="Pre-bureauRiskCategory"><variable name="Pre-bureauRiskCategory" typeRef="string"/></inputData>
                  <decision id="d" name="Eligibility">
                    <informationRequirement><requiredInput href="#c"/></informationRequirement>
                    <decisionTable>
                      <input><inputExpression typeRef="string"><text>Pre-bureauRiskCategory</text></inputExpression></input>
                      <output typeRef="string"/>
                      <rule><inputEntry><text>"HIGH"</text></inputEntry><outputEntry><text>"INELIGIBLE"</text></outputEntry></rule>
                      <rule><inputEntry><text>not("HIGH")</text></inputEntry><outputEntry><text>"ELIGIBLE"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""".getBytes(StandardCharsets.UTF_8))));

        assertThat(graph.evaluate("Eligibility", Map.of("Pre-bureauRiskCategory", "HIGH"))).isEqualTo("INELIGIBLE");
        assertThat(graph.evaluate("Eligibility", Map.of("Pre-bureauRiskCategory", "LOW"))).isEqualTo("ELIGIBLE");
    }

    @Test
    void a_business_knowledge_model_whose_table_invokes_it_without_end_is_null_with_an_error_on_a_small_stack() throws Exception {
        // each table evaluates its FEEL afresh: the evaluation's limits still count every level, so the stack holds
        var graph = graph(DmnReader.readModel(new ByteArrayInputStream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="m" name="M" namespace="https://example.com/m">
                  <inputData id="n" name="N"><variable name="N" typeRef="number"/></inputData>
                  <decision id="d" name="D">
                    <informationRequirement><requiredInput href="#n"/></informationRequirement>
                    <knowledgeRequirement><requiredKnowledge href="#loop"/></knowledgeRequirement>
                    <literalExpression><text>loop(N)</text></literalExpression>
                  </decision>
                  <businessKnowledgeModel id="loop" name="loop">
                    <variable name="loop"/>
                    <encapsulatedLogic>
                      <formalParameter name="n" typeRef="number"/>
                      <decisionTable>
                        <input><inputExpression typeRef="number"><text>n</text></inputExpression></input>
                        <output/>
                        <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>loop(n + 1)</text></outputEntry></rule>
                      </decisionTable>
                    </encapsulatedLogic>
                  </businessKnowledgeModel>
                </definitions>""".getBytes(StandardCharsets.UTF_8))));
        var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        var thread = new Thread(null, () -> {
            try {
                outcome.set(graph.evaluateWithErrors("D", Map.of("N", 1)));
            } catch (Throwable e) {
                outcome.set(e);
            }
        }, "small stack", 512 * 1024);
        thread.start();
        thread.join();

        assertThat(outcome.get()).isInstanceOfSatisfying(DecisionGraph.Outcome.class, result -> {
            assertThat(result.value()).isNull();
            assertThat(result.errors().get("D")).anyMatch(error -> error.contains("more deeply than Praecepta evaluates"));
        });
    }
}
