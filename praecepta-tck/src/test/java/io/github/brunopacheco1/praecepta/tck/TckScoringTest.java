/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/** How a result node is scored, and what the baseline counts as a regression. */
class TckScoringTest {

    @TempDir
    Path directory;

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
    void a_failure_to_evaluate_fails_the_node_with_its_message() {
        var outcome = score(check(TckValue.NIL, false), new Evaluation.Failure("the decision requirements graph cannot be built"));
        assertThat(outcome).isEqualTo(TckRunner.Outcome.failed("the decision requirements graph cannot be built"));
    }

    @Test
    void an_unsupported_node_is_reported_with_its_reason() {
        var outcome = score(check(TckValue.NIL, false), new Evaluation.Unsupported("Decision 'Max' uses an external function"));
        assertThat(outcome.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(outcome.detail()).isEqualTo("Decision 'Max' uses an external function");
    }

    @Test
    void a_regression_is_a_node_of_the_baseline_that_no_longer_passes() {
        var baseline = Set.of("a", "b", "c");
        assertThat(Baseline.regressions(baseline, List.of("a", "c", "d"))).containsExactly("b");
        assertThat(Baseline.regressions(baseline, List.of("c", "b", "a", "e"))).isEmpty();
        assertThat(Baseline.regressions(Set.of(), List.of())).isEmpty();
    }

    @Test
    void the_baseline_lists_the_passing_keys_sorted_under_its_header() {
        var text = Baseline.render(List.of("b x", "a y"));
        assertThat(text).startsWith("# SPDX-FileCopyrightText: Bruno Pacheco\n").endsWith("\na y\nb x\n");
    }

    @Test
    void a_baseline_reads_as_its_keys_without_its_comments_and_blank_lines() throws IOException {
        var file = directory.resolve("baseline.txt");
        Files.writeString(file, Baseline.render(List.of("b x", "a y")) + "\n# a comment\n");
        assertThat(Baseline.read(file, false)).containsExactly("a y", "b x");
    }

    @Test
    void the_committed_baseline_lists_result_nodes_of_the_kit() {
        assertThat(Baseline.read(Baseline.FILE, false)).isNotEmpty().allSatisfy(key -> assertThat(key).startsWith("compliance-level-"));
    }

    @Test
    void a_missing_or_empty_baseline_fails_unless_it_is_being_regenerated() throws IOException {
        var missing = directory.resolve("no-such-baseline.txt");
        assertThatThrownBy(() -> Baseline.read(missing, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no regression is caught");
        assertThat(Baseline.read(missing, true)).isEmpty();

        var empty = directory.resolve("empty.txt");
        Files.writeString(empty, "# only a comment\n");
        assertThatThrownBy(() -> Baseline.read(empty, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lists no result node");
        assertThat(Baseline.read(empty, true)).isEmpty();
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
        return TckRunner.score(check, evaluation);
    }

    private static Check check(TckValue expected, boolean expectsError) {
        return new Check("compliance-level-2/0004-simpletable-U", "0004-simpletable-U-test-01.xml", "001", Invocable.DECISION, "",
                "Approval Status", Path.of("0004-simpletable-U.dmn"), Map.of(), expected, expectsError);
    }
}
