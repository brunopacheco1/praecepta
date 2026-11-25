/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;

/**
 * The TCK's arithmetic test cases, whose decisions are literal expressions rather than decision tables, evaluated as the
 * output entry of a decision table, which is any FEEL expression since 0.8 (#116): every expression gives the kit's
 * result, through a {@link DecisionGraph} of a one-rule table whose output declares no type, so that each value keeps its
 * own (DMN 1.5, 10.3.2.10, step 3c). Their literals are at-literals ({@code @"2021-01-01" + @"P1D"}), so the cases check
 * the arithmetic of dates, times, date-times and durations (DMN 1.5, Tables 57, 59 and 62), and operands FEEL does not
 * combine ({@code @"P1D" + 10}), which give null, with an error, where 0.7 rejected the table. An expression wrapped in
 * {@code string(...)}, which the kit uses for values it cannot write otherwise (a time with a zone id), and any other
 * that invokes a built-in function, is evaluated as it is (since 0.9, #117).
 */
class OutputExpressionsTckTest {

    /**
     * @param expectedPassed the checks whose expression gives the kit's result
     * @param expectedNull   the checks among them whose result is null, as FEEL gives for what it does not define
     */
    @ParameterizedTest
    @CsvSource({
            "compliance-level-3/0099-arithmetic-negation, 14,   7",
            "compliance-level-3/0100-arithmetic,          1087, 819",
    })
    void every_expression_gives_the_kits_result_as_an_output_entry(String testCase, int expectedPassed, int expectedNull) {
        var directory = TckSuite.root().resolve(Path.of(testCase));
        var name = Path.of(testCase).getFileName().toString();
        var expressions = expressions(directory.resolve(name + ".dmn"));
        var passed = 0;
        var nulls = 0;
        var notSupported = new TreeMap<String, Integer>();
        var failed = new ArrayList<String>();
        var root = TckXml.parse(directory.resolve(name + "-test-01.xml"));
        for (var node : TckXml.descendants(root, "resultNode")) {
            var expected = TckValue.read(TckXml.children(node, "expected").getFirst());
            var text = expressions.get(node.getAttribute("name"));
            var outcome = outcome(text, expected);
            if (outcome == null) {
                passed++;
                nulls += expected instanceof TckValue.Nil ? 1 : 0;
            } else if (outcome.startsWith("not supported: ")) {
                notSupported.merge(outcome.substring("not supported: ".length()), 1, Integer::sum);
            } else {
                failed.add(node.getAttribute("name") + ": " + text + " -> " + outcome + ", expected " + expected);
            }
        }
        Assertions.assertThat(failed).as("%s; not supported: %s", testCase, notSupported).isEmpty();
        Assertions.assertThat(passed).as("checks passed in %s; not supported: %s", testCase, notSupported).isEqualTo(expectedPassed);
        Assertions.assertThat(nulls).as("checks whose result is null in %s", testCase).isEqualTo(expectedNull);
    }

    /** Null if the expression, as an output entry, gives the expected value; else why not. */
    private static String outcome(String text, TckValue expected) {
        var output = new DecisionTable.OutputColumn("value", ColumnType.STRING, List.of(), null, false, false);
        var rule = new Praeceptum(1, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of(), Map.of("value", text)));
        DecisionGraph graph;
        try {
            graph = DecisionGraph.builder().logValidationReport(false)
                    .decision(new DecisionTable("Value", HitPolicy.FIRST, null, List.of(), List.of(output), List.of(rule), true)).build();
        } catch (InvalidDecisionTableException e) {
            return "not supported: " + e.getMessage();
        }
        var value = graph.evaluate("Value", Map.of());
        if (value == null) {
            return expected.matches(TckValue.NIL, true) ? null : "null";
        }
        var valueText = value instanceof BigDecimal number ? number.toPlainString() : FeelLiterals.text(value);
        var actual = new TckValue.Simple(value instanceof String ? "string" : "", valueText);
        return expected.matches(actual, true) ? null : String.valueOf(actual);
    }

    /** The text of each decision's literal expression, by decision name. */
    private static Map<String, String> expressions(Path dmn) {
        var expressions = new HashMap<String, String>();
        for (var decision : TckXml.descendants(TckXml.parse(dmn), "decision")) {
            var texts = TckXml.descendants(decision, "text");
            if (!texts.isEmpty()) {
                expressions.put(decision.getAttribute("name"), texts.getFirst().getTextContent());
            }
        }
        return expressions;
    }
}
