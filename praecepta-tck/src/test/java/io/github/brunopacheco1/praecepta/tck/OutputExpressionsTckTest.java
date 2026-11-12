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
import java.util.Map;
import java.util.TreeMap;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;

/**
 * The TCK's arithmetic test cases, whose decisions are literal expressions rather than decision tables, evaluated as
 * output expressions ({@link OutputExpression}), which output entries are: every expression Praecepta reads gives the
 * kit's result. Their literals are at-literals ({@code @"2021-01-01" + @"P1D"}), so the cases check the arithmetic of
 * dates, times, date-times and durations (DMN 1.5, Tables 57, 59 and 62) and the at-literals output entries share with
 * cells. An expression wrapped in {@code string(...)}, which the kit uses for values it cannot write otherwise (a time
 * with a zone id), is evaluated without it and compared as FEEL's text of the value.
 *
 * <p>An expression is <em>rejected</em> when the engine would refuse the table: FEEL gives null for operands of types
 * it does not combine ({@code @"P1D" + 10}), and Praecepta rejects them when the engine is built, so a rejected
 * expression passes when the kit expects null. It is <em>not supported</em> when it is not in Praecepta's subset
 * (functions, lists, contexts, {@code null} operands, hour 24).
 */
class OutputExpressionsTckTest {

    /**
     * Checks Praecepta answers otherwise, by decision: the kit gives a power that is not whole to 13 significant digits,
     * where FEEL's numbers have 34 (DMN 1.5, 10.3.2.3.1 and Table 60), which output expressions keep (#71).
     */
    private static final Map<String, String> KNOWN = Map.of(
            "exponent_lhs_number_exp_rhs_number_005", "a power to 13 digits, not 34");

    @ParameterizedTest
    @CsvSource({
            "compliance-level-3/0099-arithmetic-negation, 10",
            "compliance-level-3/0100-arithmetic,          478",
    })
    void every_expression_praecepta_reads_gives_the_kits_result(String testCase, int expectedPassed) {
        var directory = TckSuite.root().resolve(Path.of(testCase));
        var name = Path.of(testCase).getFileName().toString();
        var expressions = expressions(directory.resolve(name + ".dmn"));
        var passed = 0;
        var notSupported = new TreeMap<String, Integer>();
        var failed = new ArrayList<String>();
        var root = TckXml.parse(directory.resolve(name + "-test-01.xml"));
        for (var node : TckXml.descendants(root, "resultNode")) {
            var expected = TckValue.read(TckXml.children(node, "expected").getFirst());
            var text = expressions.get(node.getAttribute("name"));
            var outcome = KNOWN.containsKey(node.getAttribute("name")) ? "not supported: " + KNOWN.get(node.getAttribute("name"))
                    : outcome(text, expected);
            if (outcome == null) {
                passed++;
            } else if (outcome.startsWith("not supported: ")) {
                notSupported.merge(outcome.substring("not supported: ".length()), 1, Integer::sum);
            } else {
                failed.add(node.getAttribute("name") + ": " + text + " -> " + outcome + ", expected " + expected);
            }
        }
        Assertions.assertThat(failed).as("%s; not supported: %s", testCase, notSupported).isEmpty();
        Assertions.assertThat(passed).as("checks passed in %s; not supported: %s", testCase, notSupported).isEqualTo(expectedPassed);
    }

    /** Null if the expression gives the expected value (or is rejected where FEEL gives null); else why not. */
    private static String outcome(String text, TckValue expected) {
        var asString = text.startsWith("string(") && text.endsWith(")");
        var expressionText = asString ? text.substring("string(".length(), text.length() - 1) : text;
        OutputExpression expression;
        try {
            expression = OutputExpression.parse(expressionText);
        } catch (ExpressionSyntaxException e) {
            return "not supported: " + reason(e.getMessage());
        }
        try {
            expression.type(any -> null);
        } catch (ExpressionTypeException e) {
            if (e.getMessage().endsWith("is not an input")) {
                return "not supported: names";
            }
            return expected instanceof TckValue.Nil ? null : "rejected (" + e.getMessage() + ")";
        }
        var value = expression.evaluate(any -> null);
        if (value == null) {
            return expected.matches(TckValue.NIL, true) ? null : "null";
        }
        var valueText = value instanceof BigDecimal number ? number.toPlainString() : FeelLiterals.text(value);
        var actual = new TckValue.Simple(asString ? "string" : "", valueText);
        return expected.matches(actual, true) ? null : String.valueOf(actual);
    }

    /** What makes an expression unsupported, in a few words. */
    private static String reason(String message) {
        if (message.contains("function calls")) {
            return "function calls";
        }
        if (message.contains("'null' is not supported")) {
            return "null operands";
        }
        if (message.contains("'true' is not supported") || message.contains("'false' is not supported")) {
            return "booleans";
        }
        if (message.contains("24:00")) {
            return "hour 24";
        }
        if (message.contains("unexpected '['") || message.contains("unexpected '{'") || message.contains("'function'")) {
            return "lists, ranges, contexts and functions";
        }
        return message;
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
