/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.brunopacheco1.praecepta.tck.TckResults.Result;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;

/**
 * The whole DMN TCK, compliance levels 2 and 3: every result node is evaluated with {@link ModelEvaluator#praecepta()}
 * and scored. The build fails only on a regression, a node the committed baseline (baseline.txt) lists that no
 * longer passes, or no longer passes through the decision requirements graph ({@link GraphEvaluator}) though the
 * baseline has it doing so; a node that fails or is not supported is reported as skipped, with what differed or what it
 * needs. RESULTS.md and the baseline are rewritten with {@code -Dtck.update=true}; while they differ from this run, the
 * test that compares them is skipped with what changed, so a new pass is recorded deliberately, never by accident, and
 * with {@code -Dtck.strict=true}, as the build workflow runs it, it fails, so a pull request cannot leave them stale.
 */
class TckTest {

    private static final Path RESULTS = Baseline.MODULE.resolve("RESULTS.md");
    private static final boolean UPDATE = Boolean.getBoolean("tck.update");
    private static final boolean STRICT = Boolean.getBoolean("tck.strict");
    /** The nodes that pass through the decision requirements graph itself, not the evaluator it falls back to. */
    private static final Set<String> THROUGH_GRAPH = new HashSet<>();
    /** Every node's result, by TCK test case in name order. */
    private static final Map<String, List<Result>> RUN = run();
    private static final Map<String, String> BASELINE = Baseline.read(Baseline.FILE, UPDATE);

    private static Map<String, List<Result>> run() {
        var single = new SingleDecisionEvaluator();
        var graph = new GraphEvaluator(single);
        var runner = new TckRunner(graph.orElse(single));
        var graphRunner = new TckRunner(graph);
        var results = new LinkedHashMap<String, List<Result>>();
        TckSuite.load().forEach((testCase, checks) -> results.put(testCase, checks.stream()
                .map(check -> {
                    if (graphRunner.run(check).status() == Status.PASSED) {
                        THROUGH_GRAPH.add(check.key());
                    }
                    return new Result(check, runner.run(check), TckResults.latest(SingleDecisionEvaluator.content(check)));
                })
                .toList()));
        return results;
    }

    private static List<String> passing() {
        return RUN.values().stream().flatMap(List::stream)
                .filter(result -> result.status() == Status.PASSED)
                .map(result -> result.check().key())
                .toList();
    }

    @TestFactory
    Stream<DynamicContainer> tck_models() {
        return RUN.entrySet().stream()
                .map(entry -> DynamicContainer.dynamicContainer(entry.getKey(), entry.getValue().stream()
                        .map(result -> DynamicTest.dynamicTest(result.check().label(), () -> report(result)))));
    }

    /** PASSED passes; a regression fails; anything else is skipped with what differed or what the node needs. */
    private static void report(Result result) {
        var outcome = result.outcome();
        var key = result.check().key();
        if (outcome.status() == Status.PASSED) {
            if (Baseline.GRAPH.equals(BASELINE.get(key)) && !THROUGH_GRAPH.contains(key)) {
                Assertions.fail("regression, the baseline has it passing through the decision requirements graph; now it passes "
                        + "only when evaluated on its own");
            }
            return;
        }
        var what = outcome.status() == Status.FAILED ? "fails: " + outcome.detail()
                : "not supported: " + outcome.needs().stream().map(Feature::label).toList();
        if (BASELINE.containsKey(key)) {
            Assertions.fail("regression, the baseline has it passing; now it " + what);
        }
        Assumptions.abort(what);
    }

    @Test
    void no_node_the_baseline_has_passing_regresses() {
        assertThat(Baseline.regressions(BASELINE, passing(), THROUGH_GRAPH))
                .as("result nodes that passed in baseline.txt and no longer do; fix them, or regenerate the baseline "
                        + "deliberately with -Dtck.update=true")
                .isEmpty();
    }

    @Test
    void every_node_has_a_key_of_its_own() {
        var keys = RUN.values().stream().flatMap(List::stream).map(result -> result.check().key()).toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void the_kit_has_every_model_and_result_node_of_levels_2_and_3() {
        var results = RUN.values().stream().flatMap(List::stream).toList();
        // at 5cd912f, without the result nodes the test files have commented out
        assertThat(RUN.keySet().stream().filter(testCase -> testCase.startsWith("compliance-level-2/"))).hasSize(28);
        assertThat(RUN.keySet().stream().filter(testCase -> testCase.startsWith("compliance-level-3/"))).hasSize(120);
        assertThat(results.stream().filter(result -> result.check().level() == 2)).hasSize(126);
        assertThat(results.stream().filter(result -> result.check().level() == 3)).hasSize(3_385);
    }

    @Test
    void published_results_and_baseline_are_current() throws IOException {
        var report = TckResults.render(RUN);
        var baseline = Baseline.render(passing(), THROUGH_GRAPH);
        if (UPDATE) {
            Files.writeString(RESULTS, report);
            Files.writeString(Baseline.FILE, baseline);
        }
        var publishedReport = Files.exists(RESULTS) ? Files.readString(RESULTS) : "";
        var publishedBaseline = Files.exists(Baseline.FILE) ? Files.readString(Baseline.FILE) : "";
        var newPasses = passing().stream().filter(key -> !BASELINE.containsKey(key)).count();
        var current = publishedReport.equals(report) && publishedBaseline.equals(baseline);
        var why = "RESULTS.md or baseline.txt is out of date (%d result nodes pass that the baseline does not list): "
                .formatted(newPasses) + "run mvn -pl praecepta-tck -am test -Dtck.update=true";
        if (STRICT) {
            assertThat(current).as(why).isTrue();
        }
        Assumptions.assumeTrue(current, why);
    }
}
