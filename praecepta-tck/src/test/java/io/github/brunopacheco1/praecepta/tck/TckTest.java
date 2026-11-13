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
 * longer passes; a node that fails or is not supported is reported as skipped, with what differed or what it needs.
 * RESULTS.md and the baseline are rewritten with {@code -Dtck.update=true}; while they differ from this run, the test
 * that compares them is skipped with what changed, so a new pass is recorded deliberately, never by accident.
 */
class TckTest {

    private static final Path RESULTS = Path.of("RESULTS.md");
    private static final boolean UPDATE = Boolean.getBoolean("tck.update");
    /** Every node's result, by TCK test case in name order. */
    private static final Map<String, List<Result>> RUN = run();
    private static final Set<String> BASELINE = Baseline.read(Baseline.FILE);

    private static Map<String, List<Result>> run() {
        var evaluator = ModelEvaluator.praecepta();
        var runner = new TckRunner(evaluator);
        var results = new LinkedHashMap<String, List<Result>>();
        TckSuite.load().forEach((testCase, checks) -> results.put(testCase, checks.stream()
                .map(check -> new Result(check, runner.run(check), TckResults.latest(SingleDecisionEvaluator.content(check))))
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
        if (outcome.status() == Status.PASSED) {
            return;
        }
        var what = outcome.status() == Status.FAILED ? "fails: " + outcome.detail()
                : "not supported: " + outcome.needs().stream().map(Feature::label).toList();
        if (BASELINE.contains(result.check().key())) {
            Assertions.fail("regression, the baseline has it passing; now it " + what);
        }
        Assumptions.abort(what);
    }

    @Test
    void no_node_the_baseline_has_passing_regresses() {
        assertThat(Baseline.regressions(BASELINE, passing()))
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
        var baseline = Baseline.render(passing());
        if (UPDATE) {
            Files.writeString(RESULTS, report);
            Files.writeString(Baseline.FILE, baseline);
        }
        var publishedReport = Files.exists(RESULTS) ? Files.readString(RESULTS) : "";
        var publishedBaseline = Files.exists(Baseline.FILE) ? Files.readString(Baseline.FILE) : "";
        var newPasses = passing().stream().filter(key -> !BASELINE.contains(key)).count();
        Assumptions.assumeTrue(publishedReport.equals(report) && publishedBaseline.equals(baseline),
                () -> "RESULTS.md or baseline.txt is out of date (%d result nodes pass that the baseline does not list): "
                        .formatted(newPasses) + "run mvn -pl praecepta-tck -am test -Dtck.update=true");
    }
}
