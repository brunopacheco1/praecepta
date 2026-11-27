/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import io.github.brunopacheco1.praecepta.tck.TckResults.Result;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;

/**
 * The run's results in the DMN TCK's own format, which its results site (https://dmn-tck.github.io/tck/) shows for each
 * product from {@code TestResults/<vendor>/<version>/} of dmn-tck/tck: {@code tck_results.csv}, one line per test case
 * of each test file, and {@code tck_results.properties}, which names the product.
 *
 * <p>The kit scores a test case, where {@link TckRunner} scores each of its result nodes: a test case is
 * {@link Verdict#SUCCESS} when every one of its nodes passes, {@link Verdict#ERROR} when at least one fails, and
 * {@link Verdict#IGNORED} when none fails and at least one is not supported.
 */
final class TckExport {

    static final String CSV = "tck_results.csv";
    static final String PROPERTIES = "tck_results.properties";

    /** A test case's result, as the kit names it ({@code TestResult.Result}). */
    enum Verdict {
        SUCCESS,
        ERROR,
        IGNORED,
    }

    /**
     * One line of {@code tck_results.csv}.
     *
     * @param directory the test case directory under the kit's {@code TestCases}, {@code compliance-level-2/0001-input-data-string}
     * @param testFile  the test file's name without {@code .xml}
     * @param testCase  the test case's id in its file
     * @param message   for ERROR, the failing nodes with what differed; for IGNORED, the reason; else empty
     */
    record Row(String directory, String testFile, String testCase, Verdict result, String message) {

        /** The line as the kit's own runner writes it: five quoted fields, and no escaping, so no field holds a quote or a line break. */
        String line() {
            return "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n".formatted(field(directory), field(testFile), field(testCase), result, field(message));
        }
    }

    private TckExport() {
    }

    /** Writes both files to a directory, which it creates. */
    static void write(Path directory, List<Result> results, String version, LocalDate date) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(CSV), csv(rows(results)));
        // every character outside ASCII is escaped, so the file reads the same in ISO 8859-1 and in UTF-8
        Files.writeString(directory.resolve(PROPERTIES), properties(version, date), StandardCharsets.US_ASCII);
    }

    /** One row per test case of each test file, in the order of the results, which is the kit's. */
    static List<Row> rows(List<Result> results) {
        var byTestCase = new LinkedHashMap<List<String>, List<Result>>();
        for (var result : results) {
            var check = result.check();
            byTestCase.computeIfAbsent(List.of(check.testCase(), check.testFile(), check.id()), key -> new ArrayList<>()).add(result);
        }
        return byTestCase.values().stream().map(TckExport::row).toList();
    }

    private static Row row(List<Result> nodes) {
        var check = nodes.getFirst().check();
        var failures = nodes.stream().filter(node -> node.status() == Status.FAILED)
                .map(node -> "'%s' %s".formatted(node.check().decision(), node.outcome().detail()))
                .toList();
        var reasons = nodes.stream().filter(node -> node.status() == Status.UNSUPPORTED)
                .map(node -> node.outcome().detail())
                .distinct()
                .toList();
        Verdict verdict;
        String message;
        if (!failures.isEmpty()) {
            verdict = Verdict.ERROR;
            message = "FAILURE: " + String.join("; ", failures);
        } else if (!reasons.isEmpty()) {
            verdict = Verdict.IGNORED;
            message = String.join("; ", reasons);
        } else {
            verdict = Verdict.SUCCESS;
            message = "";
        }
        var testFile = check.testFile().endsWith(".xml") ? check.testFile().substring(0, check.testFile().length() - 4) : check.testFile();
        return new Row(check.testCase(), testFile, check.id(), verdict, message);
    }

    /** The text of {@code tck_results.csv}: a line per row, and no header. */
    static String csv(List<Row> rows) {
        var text = new StringBuilder();
        rows.forEach(row -> text.append(row.line()));
        return text.toString();
    }

    /** The longest field written: a failure may quote a whole context, which the kit's site shows on one line. */
    static final int MAX_FIELD_LENGTH = 500;

    /** A field on one line, without double quotes, which become single ones, and cut to {@link #MAX_FIELD_LENGTH}. */
    static String field(String text) {
        var line = text.replace('"', '\'').replaceAll("\\s*\\R\\s*", " ");
        return line.length() <= MAX_FIELD_LENGTH ? line : line.substring(0, MAX_FIELD_LENGTH - 3) + "...";
    }

    /**
     * The text of {@code tck_results.properties}: the keys the kit's site reads, sorted, and nothing else, so that two
     * runs of one version on one day write the same file.
     *
     * @param version the version of Praecepta that ran
     * @param date    the day of the run
     */
    static String properties(String version, LocalDate date) {
        var values = new TreeMap<String, String>();
        values.put("vendor.name", "Bruno Pacheco");
        values.put("vendor.url", "https://github.com/brunopacheco1");
        values.put("product.name", "Praecepta");
        values.put("product.url", "https://bruno.pacheco.lu/praecepta/");
        values.put("product.version", version);
        values.put("product.comment", "A DMN 1.5 engine for Java. It rejects external functions (Java, PMML) by design.");
        values.put("last.update", date.toString());
        values.put("instructions.url", "https://github.com/brunopacheco1/praecepta/blob/main/praecepta-tck/README.md");
        var text = new StringBuilder();
        values.forEach((key, value) -> text.append(key).append('=').append(escaped(value)).append('\n'));
        return text.toString();
    }

    /**
     * A value as a {@code .properties} file holds it: a backslash, a line break, a tab and a form feed escaped, a
     * leading space too, which a reader would skip, and any character outside printable ASCII as a Unicode escape.
     */
    static String escaped(String value) {
        var text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '\f' -> text.append("\\f");
                case ' ' -> text.append(i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.toString();
    }
}
