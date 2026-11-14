/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.w3c.dom.Element;

/**
 * The vendored TCK (src/test/resources/tck): every result node of every test file of compliance levels 2 and 3,
 * whatever it needs. {@link Check#isDecisionTable()} picks the ones RESULTS.md has counted since 0.1, a decision
 * whose logic is a decision table.
 */
final class TckSuite {

    /** The dmn-tck/tck commit the files were copied from. */
    static final String TCK_COMMIT = "5cd912f281831bc535e710aba435e16de198028a";

    /** What a test case invokes, from its {@code type}. */
    enum Invocable {
        DECISION,
        DECISION_SERVICE,
        KNOWLEDGE_MODEL;

        static Invocable of(String type) {
            return switch (type) {
                case "decisionService" -> DECISION_SERVICE;
                case "bkm" -> KNOWLEDGE_MODEL;
                default -> DECISION;
            };
        }
    }

    /**
     * One result node: a decision evaluated with a test case's inputs (or, for a test case of another
     * {@link Invocable}, that decision service or knowledge model invoked with them).
     *
     * @param testCase  the TCK test case directory, {@code compliance-level-3/0004-lending}
     * @param kind      what the test case invokes
     * @param invocable the decision service or knowledge model the test case invokes; empty for a decision
     * @param decision  the result node's name: a decision, or what the test case invokes
     */
    record Check(String testCase, String testFile, String id, Invocable kind, String invocable, String decision, DmnModel model,
                 Map<String, TckValue> inputs, TckValue expected, boolean expectsError) {

        String label() {
            return testFile + " #" + id + " " + decision;
        }

        /** The node's key in the baseline: unique in the kit, and stable while the kit's files are. */
        String key() {
            return testCase + "/" + label();
        }

        /** The compliance level, 2 or 3. */
        int level() {
            return testCase.startsWith("compliance-level-2/") ? 2 : 3;
        }

        /** Whether the node is a decision whose logic is a decision table: the checks RESULTS.md has always counted. */
        boolean isDecisionTable() {
            return kind == Invocable.DECISION && model.tables().contains(decision);
        }
    }

    private TckSuite() {
    }

    static Path root() {
        try {
            return Path.of(TckSuite.class.getResource("/tck").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every result node, by TCK test case directory ({@code compliance-level-2/0004-simpletable-U}), in name order. */
    static Map<String, List<Check>> load() {
        var root = root();
        var byCase = new LinkedHashMap<String, List<Check>>();
        var models = new HashMap<Path, DmnModel>();
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(f -> f.getFileName().toString().matches(".*-test-\\d+\\.xml")).sorted().toList()) {
                var testCase = root.relativize(file.getParent()).toString().replace('\\', '/');
                var checks = byCase.computeIfAbsent(testCase, c -> new ArrayList<>());
                read(file, testCase, models, checks);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byCase.values().removeIf(List::isEmpty);
        return byCase;
    }

    private static void read(Path file, String testCase, Map<Path, DmnModel> models, List<Check> checks) {
        var root = TckXml.parse(file);
        var modelName = TckXml.children(root, "modelName").getFirst().getTextContent().strip();
        var model = models.computeIfAbsent(file.resolveSibling(modelName), DmnModel::new);
        var testFile = file.getFileName().toString();
        for (var test : TckXml.children(root, "testCase")) {
            var kind = Invocable.of(test.getAttribute("type"));
            var inputs = new LinkedHashMap<String, TckValue>();
            for (var input : TckXml.children(test, "inputNode")) {
                inputs.put(input.getAttribute("name"), TckValue.read(input));
            }
            for (var result : TckXml.children(test, "resultNode")) {
                checks.add(check(testCase, testFile, test, kind, result, model, inputs));
            }
        }
    }

    private static Check check(String testCase, String testFile, Element test, Invocable kind, Element result, DmnModel model,
                               Map<String, TckValue> inputs) {
        var expected = TckXml.children(result, "expected");
        return new Check(testCase, testFile, test.getAttribute("id"), kind, test.getAttribute("invocableName"),
                result.getAttribute("name"), model, inputs,
                expected.isEmpty() ? TckValue.NIL : TckValue.read(expected.getFirst()),
                "true".equals(result.getAttribute("errorResult")));
    }
}
