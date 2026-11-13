/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.tck.Feature.Area;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/** How a result node is scored, how evaluators combine, and what the baseline counts as a regression. */
class TckScoringTest {

    private static final DmnModel MODEL = new DmnModel(
            TckSuite.root().resolve("compliance-level-2/0004-simpletable-U/0004-simpletable-U.dmn"));
    private static final Feature LISTS = Feature.feel("lists");
    private static final Feature BUILT_IN = Feature.builtIn("abs");

    @Test
    void a_value_passes_when_it_is_the_expected_one() {
        var check = check(new TckValue.Simple("decimal", "10"), false);
        assertThat(score(check, new Evaluation.Value(new TckValue.Simple("number", "10.0"), true)).status()).isEqualTo(Status.PASSED);
        var outcome = score(check, new Evaluation.Value(new TckValue.Simple("number", "11"), true));
        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).isEqualTo("expected 10, got 11");
    }

    @Test
    void an_error_passes_only_where_the_kit_expects_one() {
        assertThat(score(check(TckValue.NIL, true), new Evaluation.Error("no rule")).status()).isEqualTo(Status.PASSED);
        assertThat(score(check(TckValue.NIL, false), new Evaluation.Error("no rule")).status()).isEqualTo(Status.FAILED);
        assertThat(score(check(TckValue.NIL, true), new Evaluation.Value(TckValue.NIL, true)).status()).isEqualTo(Status.FAILED);
    }

    @Test
    void an_unsupported_node_keeps_every_need_and_is_reported_by_the_first() {
        var outcome = score(check(TckValue.NIL, false), new Evaluation.Unsupported(List.of(LISTS, BUILT_IN)));
        assertThat(outcome.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(outcome.detail()).isEqualTo("lists");
        assertThat(outcome.needs()).containsExactly(LISTS, BUILT_IN);
        assertThat(TckResults.latest(outcome.needs())).isEqualTo(Area.BUILT_IN_FUNCTIONS);
    }

    @Test
    void an_evaluator_that_throws_fails_the_node_and_not_the_run() {
        ModelEvaluator broken = check -> {
            throw new IllegalStateException("boom");
        };
        var outcome = new TckRunner(broken).run(check(TckValue.NIL, false));
        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.detail()).contains("boom");
    }

    @Test
    void the_next_evaluator_takes_the_nodes_the_first_does_not_support() {
        ModelEvaluator graphs = check -> new Evaluation.Unsupported(LISTS);
        ModelEvaluator single = check -> new Evaluation.Value(TckValue.NIL, true);
        assertThat(graphs.orElse(single).evaluate(check(TckValue.NIL, false))).isEqualTo(new Evaluation.Value(TckValue.NIL, true));
        assertThat(single.orElse(graphs).evaluate(check(TckValue.NIL, false))).isEqualTo(new Evaluation.Value(TckValue.NIL, true));
    }

    @Test
    void where_neither_evaluator_supports_a_node_the_first_ones_reasons_are_kept() {
        ModelEvaluator graphs = check -> new Evaluation.Unsupported(LISTS);
        ModelEvaluator single = check -> new Evaluation.Unsupported(Feature.REQUIRED_DECISIONS);
        assertThat(graphs.orElse(single).evaluate(check(TckValue.NIL, false))).isEqualTo(new Evaluation.Unsupported(LISTS));
    }

    @Test
    void a_regression_is_a_node_of_the_baseline_that_no_longer_passes() {
        var baseline = Map.of("a", "", "b", "", "c", "");
        assertThat(Baseline.regressions(baseline, List.of("a", "c", "d"), Set.of())).containsExactly("b");
        assertThat(Baseline.regressions(baseline, List.of("c", "b", "a", "e"), Set.of())).isEmpty();
        assertThat(Baseline.regressions(Map.of(), List.of(), Set.of())).isEmpty();
    }

    @Test
    void a_regression_is_also_a_node_that_passed_through_the_graph_and_now_passes_only_on_its_own() {
        var baseline = Map.of("a", Baseline.GRAPH, "b", "");
        assertThat(Baseline.regressions(baseline, List.of("a", "b"), Set.of("a"))).isEmpty();
        assertThat(Baseline.regressions(baseline, List.of("a", "b"), Set.of()))
                .containsExactly("a (no longer through the decision requirements graph)");
    }

    @Test
    void the_baseline_lists_the_passing_keys_sorted_under_its_header_tagging_those_through_the_graph() {
        var text = Baseline.render(List.of("b x", "a y"), Set.of("b x"));
        assertThat(text).startsWith("# SPDX-FileCopyrightText: Bruno Pacheco\n").endsWith("\na y\nb x\tgraph\n");
    }

    @Test
    void the_committed_baseline_reads_without_its_comments() {
        assertThat(Baseline.read(Baseline.FILE, false)).isNotEmpty()
                .allSatisfy((key, tag) -> assertThat(key).startsWith("compliance-level-"))
                .containsValue(Baseline.GRAPH);
    }

    @Test
    void a_missing_baseline_fails_unless_it_is_being_regenerated() {
        var missing = Baseline.MODULE.resolve("target/no-such-baseline.txt");
        assertThatThrownBy(() -> Baseline.read(missing, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no regression is caught");
        assertThat(Baseline.read(missing, true)).isEmpty();
    }

    @Test
    void a_text_value_is_not_the_number_it_spells() {
        var expected = new TckValue.Simple("decimal", "10");
        assertThat(expected.matches(new TckValue.Simple("string", "10"), true)).isFalse();
        assertThat(expected.matches(new TckValue.Simple("decimal", "10.0"), true)).isTrue();
        assertThat(expected.matches(new TckValue.Simple("number", "10"), true)).isTrue();
        assertThat(expected.matches(new TckValue.Simple("", "10"), true)).as("a value that names no type").isTrue();
    }

    private static TckRunner.Outcome score(Check check, Evaluation evaluation) {
        return new TckRunner(any -> evaluation).run(check);
    }

    private static Check check(TckValue expected, boolean expectsError) {
        return new Check("compliance-level-2/0004-simpletable-U", "0004-simpletable-U-test-01.xml", "001", Invocable.DECISION, "",
                "Approval Status", MODEL, Map.of(), expected, expectsError);
    }
}
