/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.brunopacheco1.praecepta.tck.TckRunner.Outcome;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Renders RESULTS.md: how many result nodes pass, by compliance level and by model, each failure with what the kit
 * expects and what Praecepta gives, and each node that is not supported with the reason.
 */
final class TckResults {

    /** A result node and how it scored. */
    record Result(Check check, Outcome outcome) {

        Status status() {
            return outcome.status();
        }
    }

    private TckResults() {
    }

    /** @param byCase every node's result, by TCK test case directory in name order */
    static String render(Map<String, List<Result>> byCase) {
        var results = byCase.values().stream().flatMap(List::stream).toList();
        var md = new StringBuilder();
        appendIntroduction(md, results);
        appendLevels(md, byCase);
        appendModels(md, byCase);
        appendFailures(md, results);
        appendNotSupported(md, results);
        return md.toString();
    }

    private static void appendIntroduction(StringBuilder md, List<Result> results) {
        int passed = count(results, Status.PASSED);
        md.append("""
                <!--
                SPDX-FileCopyrightText: Bruno Pacheco

                SPDX-License-Identifier: Apache-2.0
                -->

                # DMN TCK results

                Praecepta against the [DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) (TCK): every
                model of compliance levels 2 and 3 at commit
                [`%s`](https://github.com/dmn-tck/tck/tree/%s). `TckTest` generates this file; the
                [README](README.md) says how to run the kit and how results are compared.

                **%s of %s result nodes pass (%s).** Failed: %s. Not supported: %s.

                A *result node* is one expected result in a TCK test file: a decision, a decision service or a business
                knowledge model evaluated with one test case's inputs. It *passes* when Praecepta gives the expected
                result, or reports an error where the kit expects one. It *fails* when Praecepta gives another result.
                It is *not supported* when its model uses what Praecepta rejects by design: an external function
                ([known deviations](../docs/DMN-CONFORMANCE.md#known-deviations)). Result nodes that a test file has
                commented out are not counted.
                """.formatted(TckSuite.TCK_COMMIT.substring(0, 7), TckSuite.TCK_COMMIT, number(passed), number(results.size()),
                percent(passed, results.size()), number(count(results, Status.FAILED)), number(count(results, Status.UNSUPPORTED))));
    }

    /** One row per compliance level, and the total. */
    private static void appendLevels(StringBuilder md, Map<String, List<Result>> byCase) {
        md.append("""

                ## By compliance level

                | Compliance level | Models | Result nodes | Passed | Failed | Not supported |
                |---|---:|---:|---:|---:|---:|
                """);
        for (int level = 2; level <= 3; level++) {
            var prefix = "compliance-level-" + level + "/";
            var cases = byCase.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix)).toList();
            var results = cases.stream().flatMap(entry -> entry.getValue().stream()).toList();
            line(md, "| %d | %d | %s |".formatted(level, cases.size(), counts(results)));
        }
        var results = byCase.values().stream().flatMap(List::stream).toList();
        line(md, "| **Total** | **%d** | **%s** |".formatted(byCase.size(), counts(results).replace(" | ", "** | **")));
    }

    /** One row per model, a test case directory of the kit. */
    private static void appendModels(StringBuilder md, Map<String, List<Result>> byCase) {
        md.append("""

                ## By model

                | Model (TCK test case directory) | Result nodes | Passed | Failed | Not supported |
                |---|---:|---:|---:|---:|
                """);
        byCase.forEach((testCase, caseResults) -> line(md, "| %s | %s |".formatted(link(testCase), counts(caseResults))));
    }

    /** The failed nodes, in the kit's order, with what differed. */
    private static void appendFailures(StringBuilder md, List<Result> results) {
        var failures = results.stream().filter(result -> result.status() == Status.FAILED).toList();
        if (failures.isEmpty()) {
            return;
        }
        md.append("""

                ## Failures

                Each failed node, with what the kit expects and what Praecepta gives. The README explains them
                ([known failures](README.md#known-failures)).

                """);
        failures.forEach(result -> line(md, "- `%s`: %s".formatted(result.check().key(), escape(result.outcome().detail()))));
    }

    /** The nodes that are not supported, in the kit's order, under the reason they share. */
    private static void appendNotSupported(StringBuilder md, List<Result> results) {
        var byReason = new LinkedHashMap<String, List<String>>();
        results.stream().filter(result -> result.status() == Status.UNSUPPORTED)
                .forEach(result -> byReason.computeIfAbsent(result.outcome().detail(), reason -> new ArrayList<>()).add(result.check().key()));
        if (byReason.isEmpty()) {
            return;
        }
        md.append("""

                ## Not supported

                Each node that is not supported, under the reason Praecepta's DMN reader gives for rejecting its model.
                """);
        byReason.forEach((reason, keys) -> {
            line(md, "");
            line(md, "Reason: " + escape(reason));
            line(md, "");
            keys.forEach(key -> line(md, "- `%s`".formatted(key)));
        });
    }

    /** {@code nodes | passed | failed | not supported}, with thousands separated. */
    private static String counts(List<Result> results) {
        return "%s | %s | %s | %s".formatted(number(results.size()), number(count(results, Status.PASSED)),
                number(count(results, Status.FAILED)), number(count(results, Status.UNSUPPORTED)));
    }

    private static String link(String testCase) {
        return "[%s](https://github.com/dmn-tck/tck/tree/%s/TestCases/%s)".formatted(testCase, TckSuite.TCK_COMMIT, testCase);
    }

    /** Appends a line ending in {@code \n} on every platform, so RESULTS.md is the same everywhere. */
    private static void line(StringBuilder md, String text) {
        md.append(text).append('\n');
    }

    private static int count(List<Result> results, Status status) {
        return (int) results.stream().filter(result -> result.status() == status).count();
    }

    /** A count with a comma between thousands, as the rest of the documentation writes them: {@code 3,385}. */
    static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f %%", 100.0 * part / whole);
    }

    private static String escape(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
