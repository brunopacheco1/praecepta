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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.brunopacheco1.praecepta.tck.TckResults.Result;

/**
 * The DMN TCK's decision-table test cases. Every supported check must pass; unsupported ones are
 * reported as skipped, with the missing feature. RESULTS.md, the published summary, must match what
 * this run produces: run with {@code -Dtck.update=true} to rewrite it.
 */
class TckTest {

    private static final Path RESULTS = Path.of("RESULTS.md");
    private static final List<Result> RUN = run();

    private static List<Result> run() {
        var runner = new TckRunner();
        return TckSuite.load().values().stream()
                .flatMap(List::stream)
                .map(check -> new Result(check, runner.run(check)))
                .toList();
    }

    @TestFactory
    Stream<DynamicContainer> decision_table_test_cases() {
        return RUN.stream()
                .collect(java.util.stream.Collectors.groupingBy(result -> result.check().testCase(),
                        java.util.TreeMap::new, java.util.stream.Collectors.toList()))
                .entrySet().stream()
                .map(entry -> DynamicContainer.dynamicContainer(entry.getKey(), entry.getValue().stream()
                        .map(result -> DynamicTest.dynamicTest(result.check().label(), () -> {
                            switch (result.outcome().status()) {
                                case PASSED -> {
                                    // nothing to report
                                }
                                case UNSUPPORTED -> Assumptions.abort("not supported: " + result.outcome().detail());
                                case FAILED -> Assertions.fail(result.outcome().detail());
                            }
                        }))));
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
