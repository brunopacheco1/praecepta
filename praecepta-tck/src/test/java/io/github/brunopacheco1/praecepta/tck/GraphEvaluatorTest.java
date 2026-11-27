/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/** How the run evaluates a result node through its model's decision requirements graph, and what it does not evaluate. */
class GraphEvaluatorTest {

    private static final Map<String, Check> KIT = TckSuite.load().values().stream().flatMap(List::stream)
            .collect(Collectors.toMap(Check::key, Function.identity()));
    private static final Path OFFERS = resource("/graphs/offers.dmn");
    private static final Path EXTERNAL = resource("/graphs/external.dmn");

    private final GraphEvaluator graphs = new GraphEvaluator();
    private final TckRunner runner = new TckRunner();

    @Test
    void a_decision_is_evaluated_through_the_graph_computing_the_decisions_it_requires() {
        var check = offers("Offer Text", Map.of("Score", new TckValue.Simple("decimal", "750")), "Offer: Gold");

        assertThat(graphs.evaluate(check)).isEqualTo(new Evaluation.Value(new TckValue.Simple("string", "Offer: Gold"), true));
        assertThat(runner.run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_decision_whose_value_the_test_case_gives_is_input_data_and_is_not_computed() {
        // Score 750 computes Band "A" and Eligible true, so Gold; the test case gives Band "C" instead
        var check = offers("Offer Text", Map.of("Score", new TckValue.Simple("decimal", "750"), "Band", new TckValue.Simple("string", "C")),
                "Offer: Silver");

        assertThat(runner.run(check).status()).isEqualTo(Status.PASSED);
        assertThat(runner.run(offers("Offer Text", Map.of("Score", new TckValue.Simple("decimal", "750")), "Offer: Gold")).status())
                .as("the graph without the given decision is still the model's").isEqualTo(Status.PASSED);
    }

    @Test
    void the_components_of_a_structured_input_data_are_read_through_the_graph() {
        var check = KIT.get("compliance-level-2/0008-LX-arithmetic/0008-LX-arithmetic-test-01.xml #001 payment");

        // FEEL's 34 digits (DMN 1.5, 10.3.2.3.1), where the kit states 15
        assertThat(graphs.evaluate(check))
                .isEqualTo(new Evaluation.Value(new TckValue.Simple("decimal", "2778.693549432766768088520383236299"), true));
    }

    @Test
    void a_null_feel_reports_an_error_for_is_the_error_the_kit_expects() {
        // DMN 1.5, Table 53 and 10.3.2.15: false = 0 compares values of two types, which is null, and an error
        var check = KIT.get("compliance-level-3/0068-feel-equality/0068-feel-equality-test-01.xml #boolean_008 boolean_008");

        assertThat(graphs.evaluate(check)).isInstanceOf(Evaluation.Error.class);
        assertThat(runner.run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_model_with_an_external_function_is_not_supported_whatever_the_node_and_the_reason_is_the_readers() {
        // Max is an external function, so readModel rejects the model; Band and Label do not use it
        for (var decision : List.of("Band", "Label", "Max")) {
            var outcome = runner.run(check(EXTERNAL, decision, Map.of("Score", new TckValue.Simple("decimal", "750")), "A"));

            assertThat(outcome.status()).as(decision).isEqualTo(Status.UNSUPPORTED);
            assertThat(outcome.detail()).as(decision).contains("'Max'", "an external function", "which Praecepta does not evaluate, by design");
        }
    }

    @Test
    void a_result_node_that_names_no_decision_or_decision_service_of_the_model_fails() {
        var outcome = runner.run(offers("Discount", Map.of("Score", new TckValue.Simple("decimal", "750")), "10"));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).isEqualTo("the model has no decision or decision service named 'Discount'");
    }

    @Test
    void an_evaluation_that_throws_fails_the_node_and_not_the_run() {
        var outcome = runner.run(check(OFFERS.resolveSibling("no-such-model.dmn"), "Offer", Map.of(), "Gold"));

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).startsWith("evaluation threw ").contains("no-such-model.dmn");
    }

    private static Check offers(String decision, Map<String, TckValue> inputs, String expected) {
        return check(OFFERS, decision, inputs, expected);
    }

    private static Check check(Path model, String decision, Map<String, TckValue> inputs, String expected) {
        return new Check("graphs", "graphs-test-01.xml", "001", Invocable.DECISION, "", decision, model, inputs,
                new TckValue.Simple("string", expected), false);
    }

    private static Path resource(String name) {
        try {
            return Path.of(GraphEvaluatorTest.class.getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
