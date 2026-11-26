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

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/** Decision services (DMN 1.5, 6.3.9), invoked by the decisions that require them and from outside the model (#119). */
class DecisionServicesTest {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);

    private static DecisionModel model(String name) {
        try (var dmn = DecisionServicesTest.class.getResourceAsStream("/dmn/graph/" + name)) {
            return DmnReader.readModel(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecisionGraph graph(DecisionModel model) {
        return DecisionGraph.builder().model(model).logValidationReport(false).build();
    }

    @Test
    void a_decision_invokes_a_service_with_its_input_data_then_its_input_decisions_positionally_or_by_name() {
        var graph = graph(model("services.dmn"));

        assertThat(graph.validationReport().issues()).isEmpty();
        assertThat(graph.decisionServices()).containsExactly("Pricing", "Net and Label");
        @SuppressWarnings("unchecked")
        var quote = (Map<String, Object>) graph.evaluate("Quote", Map.of());
        assertThat((BigDecimal) quote.get("positional")).as("Price 10, Quantity 3, Discount 0.5").isEqualByComparingTo("15");
        assertThat((BigDecimal) quote.get("named")).isEqualByComparingTo("9");
        var both = new LinkedHashMap<String, Object>();
        both.put("Net", new BigDecimal("4"));
        both.put("Label", "net 4");
        assertThat(quote.get("both")).as("a context of its output decisions").isEqualTo(both);
        assertThat(graph.requiredInputData("Quote")).as("a service's input data are its arguments").isEmpty();
    }

    @Test
    void a_service_invoked_from_outside_takes_its_input_decisions_values_and_leaves_the_models_alone() {
        var graph = graph(model("services.dmn"));

        var outcome = graph.evaluateService("Pricing", Map.of("Price", 4, "Quantity", 5, "Discount", new BigDecimal("0.25")));

        assertThat((BigDecimal) ((Map<?, ?>) outcome.value()).get("Net")).isEqualByComparingTo("15");
        assertThat(outcome.errors()).isEmpty();
        assertThat(graph.evaluate("Net", Map.of("Price", 4, "Quantity", 5))).as("the model's Discount is still 0").isEqualTo(new BigDecimal("20"));
    }

    @Test
    void an_argument_that_does_not_conform_makes_the_service_null_with_an_error_and_a_list_of_one_is_its_element() {
        var graph = graph(model("services.dmn"));

        var wrong = graph.evaluateService("Pricing", Map.of("Price", "four", "Quantity", 5, "Discount", 0));
        assertThat(((Map<?, ?>) wrong.value()).get("Net")).isNull();
        assertThat(wrong.errors().get("Pricing")).anyMatch(error -> error.contains("is not a number"));

        var listOfOne = graph.evaluateService("Pricing", Map.of("Price", List.of(4), "Quantity", 5, "Discount", 0));
        assertThat((BigDecimal) ((Map<?, ?>) listOfOne.value()).get("Net")).isEqualByComparingTo("20");
    }

    @Test
    void a_model_with_decision_services_written_as_dmn_reads_back_and_evaluates_as_the_original() {
        var original = model("services.dmn");
        var written = DmnWriter.write(original);
        var copy = DmnReader.readModel(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8)));

        assertThat(copy.decisionServices()).isEqualTo(original.decisionServices());
        assertThat(graph(copy).evaluate("Quote", Map.of())).isEqualTo(graph(original).evaluate("Quote", Map.of()));
    }

    @Test
    void a_service_types_as_a_function_of_its_parameters_giving_its_output() {
        var graph = graph(model("services.dmn"));

        assertThat(graph.model().decisionService("Pricing").orElseThrow().parameters()).containsExactly("Price", "Quantity", "Discount");
        assertThat(graph.type("Quote").component("positional")).isEqualTo(NUMBER);
    }

    @Test
    void a_service_that_evaluates_a_decision_reading_input_data_it_does_not_take_is_reported_with_what_invokes_it() {
        var service = new DecisionModel.DecisionService("Partial", List.of("Gross"), List.of(), List.of(), List.of("Price"), FeelType.ANY);
        var inputs = List.of(new DecisionModel.InputData("Price", NUMBER), new DecisionModel.InputData("Quantity", NUMBER));
        var gross = new DecisionModel.Decision("Gross", List.of(), List.of("Price", "Quantity"),
                new DecisionLogic.LiteralExpression("Price * Quantity"));
        var uses = new DecisionModel.Decision("Uses", List.of(), List.of(), List.of("Partial"), new DecisionLogic.LiteralExpression("Partial(2)"));
        var model = new DecisionModel("M", inputs, List.of(gross, uses), List.of(), List.of(service), Map.of());

        var graph = graph(model);

        assertThat(graph.validationReport().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(Kind.MISSING_REQUIREMENT);
            assertThat(issue.detail()).contains("decision service 'Partial' evaluates decision 'Gross', which requires input data 'Quantity'");
        });
        assertThat(graph.canEvaluate("Uses")).isFalse();
        assertThat(graph.canEvaluate("Gross")).isTrue();
        assertThatThrownBy(() -> graph.evaluateService("Partial", Map.of())).isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision service 'Partial' cannot be evaluated");
    }
}
