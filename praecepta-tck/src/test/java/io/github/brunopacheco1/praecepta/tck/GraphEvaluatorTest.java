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

/** How the run evaluates a decision through its model's decision requirements graph, and what it leaves to the others. */
class GraphEvaluatorTest {

    private static final Map<String, Check> KIT = TckSuite.load().values().stream().flatMap(List::stream)
            .collect(Collectors.toMap(Check::key, Function.identity()));
    private static final DmnModel OFFERS = new DmnModel(resource("/graphs/offers.dmn"));

    private final SingleDecisionEvaluator single = new SingleDecisionEvaluator();
    private final GraphEvaluator graphs = new GraphEvaluator(single);

    @Test
    void a_decision_is_evaluated_through_the_graph_computing_the_decisions_it_requires() {
        var check = offers(Map.of("Score", new TckValue.Simple("decimal", "750")), "Offer: Gold");

        assertThat(graphs.evaluate(check)).isEqualTo(new Evaluation.Value(new TckValue.Simple("string", "Offer: Gold"), true));
        assertThat(new TckRunner(ModelEvaluator.praecepta()).run(check).status()).isEqualTo(Status.PASSED);
        // evaluated on its own, it would need Offer computed
        assertThat(((Evaluation.Unsupported) single.evaluate(check)).needs()).contains(Feature.REQUIRED_DECISIONS);
    }

    @Test
    void a_decision_whose_value_the_test_case_gives_is_input_data_and_is_not_computed() {
        // Score 750 computes Band "A" and Eligible true, so Gold; the test case gives Band "C" instead
        var check = offers(Map.of("Score", new TckValue.Simple("decimal", "750"), "Band", new TckValue.Simple("string", "C")),
                "Offer: Silver");

        assertThat(new TckRunner(graphs).run(check).status()).isEqualTo(Status.PASSED);
        assertThat(new TckRunner(graphs).run(offers(Map.of("Score", new TckValue.Simple("decimal", "750")), "Offer: Gold")).status())
                .as("the graph without the given decision is still the model's").isEqualTo(Status.PASSED);
    }

    @Test
    void the_components_of_a_structured_input_data_are_read_through_the_graph() {
        var check = KIT.get("compliance-level-2/0008-LX-arithmetic/0008-LX-arithmetic-test-01.xml #001 payment");

        // FEEL's 34 digits (DMN 1.5, 10.3.2.3.1), where the kit gives 15: a known failure
        assertThat(graphs.evaluate(check))
                .isEqualTo(new Evaluation.Value(new TckValue.Simple("decimal", "2778.693549432766768088520383236299"), true));
    }

    @Test
    void a_null_feel_reports_an_error_for_is_the_error_the_kit_expects() {
        // DMN 1.5, Table 53 and 10.3.2.15: false = 0 compares values of two types, which is null, and an error
        var check = KIT.get("compliance-level-3/0068-feel-equality/0068-feel-equality-test-01.xml #boolean_008 boolean_008");

        assertThat(graphs.evaluate(check)).isInstanceOf(Evaluation.Error.class);
        assertThat(new TckRunner(graphs).run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void the_full_feel_language_is_evaluated_through_the_graph() {
        var check = KIT.get("compliance-level-2/0106-feel-ternary-logic/0106-feel-ternary-logic-test-01.xml #001 DecisionAnd");

        assertThat(new TckRunner(graphs).run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_node_needs_what_its_decisions_need_but_no_longer_graphs() {
        // 0082-feel-coercion invokes decision services, so readModel rejects it; its decisions are evaluated alone
        var check = KIT.get("compliance-level-3/0082-feel-coercion/0082-feel-coercion-test-01.xml #decision_bkm_001 decision_bkm_001");

        var needs = ((Evaluation.Unsupported) ModelEvaluator.praecepta().evaluate(check)).needs();
        assertThat(needs).contains(Feature.KNOWLEDGE_MODELS).doesNotContain(Feature.REQUIRED_DECISIONS);
    }

    @Test
    void a_node_on_decisions_that_invoke_built_in_functions_is_evaluated_through_the_graph() {
        var check = KIT.get("compliance-level-3/0020-vacation-days/0020-vacation-days-test-01.xml #001 Total Vacation Days");

        assertThat(new TckRunner(graphs).run(check).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void a_model_read_only_in_part_is_left_to_single_decisions() {
        // 0082-feel-coercion has decision services, so readModel rejects it, and each decision is evaluated alone
        var check = KIT.get("compliance-level-3/0082-feel-coercion/0082-feel-coercion-test-01.xml #decision_001 decision_001");

        assertThat(graphs.evaluate(check)).isEqualTo(new Evaluation.Unsupported(Feature.DECISION_SERVICES));
        assertThat(new TckRunner(ModelEvaluator.praecepta()).run(check).status()).isEqualTo(Status.PASSED);
    }

    private static Check offers(Map<String, TckValue> inputs, String expected) {
        return new Check("graphs/offers", "offers-test-01.xml", "001", Invocable.DECISION, "", "Offer Text", OFFERS, inputs,
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
