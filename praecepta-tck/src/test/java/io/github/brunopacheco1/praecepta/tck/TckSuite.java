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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.Element;

/**
 * The vendored TCK test cases (src/test/resources/tck): every result node of every test file whose
 * decision is a decision table. Other decisions (literal expressions, contexts, invocations...) and
 * decision-service or BKM test cases are outside Praecepta's scope and are not counted.
 */
final class TckSuite {

    /** The dmn-tck/tck commit the files were copied from. */
    static final String TCK_COMMIT = "5cd912f281831bc535e710aba435e16de198028a";

    /** One expected result: a decision evaluated with a test case's inputs. */
    record Check(String testCase, String testFile, String id, String decision, Model model,
                 Map<String, TckValue> inputs, TckValue expected, boolean expectsError) {

        String label() {
            return testFile + " #" + id + " " + decision;
        }
    }

    /**
     * @param tables the decisions whose logic is a decision table
     * @param decisions every decision of the model
     */
    record Model(Path file, Set<String> tables, Set<String> decisions) {
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

    /**
     * Every check, by TCK test case directory ({@code compliance-level-2/0004-simpletable-U}), in name
     * order. Test cases without a decision-table check are left out.
     */
    static Map<String, List<Check>> load() {
        var root = root();
        var byCase = new LinkedHashMap<String, List<Check>>();
        var models = new HashMap<Path, Model>();
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

    private static void read(Path file, String testCase, Map<Path, Model> models, List<Check> checks) {
        var root = TckXml.parse(file);
        var modelName = TckXml.children(root, "modelName").getFirst().getTextContent().strip();
        var model = models.computeIfAbsent(file.resolveSibling(modelName), TckSuite::model);
        var testFile = file.getFileName().toString();
        for (var test : TckXml.children(root, "testCase")) {
            if (!isDecision(test)) {
                continue; // decision services and BKMs
            }
            var inputs = new LinkedHashMap<String, TckValue>();
            for (var input : TckXml.children(test, "inputNode")) {
                inputs.put(input.getAttribute("name"), TckValue.read(input));
            }
            for (var result : TckXml.children(test, "resultNode")) {
                var decision = result.getAttribute("name");
                if (!isDecision(result) || !model.tables().contains(decision)) {
                    continue;
                }
                var expected = TckXml.children(result, "expected");
                checks.add(new Check(testCase, testFile, test.getAttribute("id"), decision, model, inputs,
                        expected.isEmpty() ? TckValue.NIL : TckValue.read(expected.getFirst()),
                        "true".equals(result.getAttribute("errorResult"))));
            }
        }
    }

    /** Whether a test case or result node is about a decision; its {@code type} is then absent or "decision". */
    private static boolean isDecision(Element node) {
        var type = node.getAttribute("type");
        return type.isEmpty() || type.equals("decision");
    }

    private static Model model(Path file) {
        var decisions = TckXml.descendants(TckXml.parse(file), "decision");
        var tables = decisions.stream()
                .filter(decision -> !TckXml.children(decision, "decisionTable").isEmpty())
                .map(decision -> decision.getAttribute("name"))
                .collect(Collectors.toUnmodifiableSet());
        var names = decisions.stream().map(decision -> decision.getAttribute("name")).collect(Collectors.toUnmodifiableSet());
        return new Model(file, tables, names);
    }
}
