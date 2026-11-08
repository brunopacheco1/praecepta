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
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.brunopacheco1.praecepta.tck.TckResults.Result;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Outcome;

/**
 * The DMN TCK's decision-table test cases. Every supported check must pass; unsupported ones are
 * reported as skipped, with the missing feature. RESULTS.md, the published summary, must match what
 * this run produces: run with {@code -Dtck.update=true} to rewrite it.
 */
class TckTest {

    private static final Path RESULTS = Path.of("RESULTS.md");
    /** Every check's outcome, by TCK test case in name order. */
    private static final Map<String, List<Result>> RUN = run();

    private static Map<String, List<Result>> run() {
        var runner = new TckRunner();
        var results = new LinkedHashMap<String, List<Result>>();
        TckSuite.load().forEach((testCase, checks) -> results.put(testCase,
                checks.stream().map(check -> new Result(check, runner.run(check))).toList()));
        return results;
    }

    @TestFactory
    Stream<DynamicContainer> decision_table_test_cases() {
        return RUN.entrySet().stream()
                .map(entry -> DynamicContainer.dynamicContainer(entry.getKey(), entry.getValue().stream()
                        .map(result -> DynamicTest.dynamicTest(result.check().label(), () -> report(result.outcome())))));
    }

    /** PASSED passes, UNSUPPORTED is skipped with the missing feature, FAILED fails. */
    private static void report(Outcome outcome) {
        switch (outcome.status()) {
            case PASSED -> {
                // nothing to report
            }
            case UNSUPPORTED -> Assumptions.abort("not supported: " + outcome.detail());
            case FAILED -> Assertions.fail(outcome.detail());
        }
    }

    @Test
    void published_results_are_current() throws IOException {
        var report = TckResults.render(RUN);
        if (Boolean.getBoolean("tck.update")) {
            Files.writeString(RESULTS, report);
        }
        assertThat(Files.exists(RESULTS) ? Files.readString(RESULTS) : "")
                .as("RESULTS.md is out of date: run mvn -pl praecepta-tck -am test -Dtck.update=true")
                .isEqualTo(report);
    }
}
