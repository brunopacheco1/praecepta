/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of FEEL as {@link FeelExpression} evaluates it: its arithmetic agrees with decimal arithmetic of 34
 * digits ({@link ArithmeticReference}, DMN 1.5, 10.3.2.3.1 and Table 59); equality is reflexive and symmetric
 * (DMN 1.5, Table 53); and the logic of three values obeys De Morgan's laws (Table 50).
 */
class FeelExpressionPropertiesTest {

    private static final int TRIES = 1000;
    private static final Map<String, Object> VALUES = Map.of("a", new BigDecimal("2.5"), "b", new BigDecimal("-4"), "c",
            new BigDecimal("10"));

    /** Arithmetic of the names a, b, c and small numbers, nested a few levels. */
    @Provide
    Arbitrary<String> arithmetic() {
        Arbitrary<String> leaf = Arbitraries.oneOf(Arbitraries.of("a", "b", "c"), Arbitraries.integers().between(-9, 9).map(String::valueOf));
        return Arbitraries.recursive(() -> leaf, inner -> Combinators.combine(inner, Arbitraries.of("+", "-", "*", "/"), inner)
                .as((x, operator, y) -> "(" + x + " " + operator + " " + y + ")"), 0, 4);
    }

    @Property(tries = TRIES)
    void arithmetic_agrees_with_decimal_arithmetic(@ForAll("arithmetic") String text) {
        var expected = new ArithmeticReference(text).evaluate(VALUES::get);
        var actual = FeelExpression.parse(text, List.of("a", "b", "c")).evaluate(VALUES::get);
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat((BigDecimal) actual).isEqualByComparingTo((BigDecimal) expected);
        }
    }

    /**
     * The arithmetic {@link #arithmetic()} writes, fully parenthesised, evaluated with Java's decimals of 34 digits, as
     * DMN 1.5 has FEEL's numbers (10.3.2.3.1): a division by zero is null (Table 59).
     */
    private record ArithmeticReference(String text) {

        Object evaluate(java.util.function.Function<String, Object> values) {
            return value(new int[] {0}, values);
        }

        private BigDecimal value(int[] at, java.util.function.Function<String, Object> values) {
            if (text.charAt(at[0]) != '(') {
                int end = text.indexOf(' ', at[0]);
                int close = text.indexOf(')', at[0]);
                int stop = end < 0 ? text.length() : end;
                stop = close >= 0 && close < stop ? close : stop;
                var leaf = text.substring(at[0], stop);
                at[0] = stop;
                return Character.isLetter(leaf.charAt(0)) ? (BigDecimal) values.apply(leaf) : new BigDecimal(leaf);
            }
            at[0]++;
            var left = value(at, values);
            char operator = text.charAt(at[0] + 1);
            at[0] += 3;
            var right = value(at, values);
            at[0]++; // the closing parenthesis
            if (left == null || right == null) {
                return null;
            }
            return switch (operator) {
                case '+' -> left.add(right, java.math.MathContext.DECIMAL128);
                case '-' -> left.subtract(right, java.math.MathContext.DECIMAL128);
                case '*' -> left.multiply(right, java.math.MathContext.DECIMAL128);
                default -> right.signum() == 0 ? null : left.divide(right, java.math.MathContext.DECIMAL128);
            };
        }
    }

    /** FEEL values written as literals: numbers, strings, booleans, null, lists and contexts of them. */
    @Provide
    Arbitrary<String> literals() {
        Arbitrary<String> simple = Arbitraries.oneOf(Arbitraries.integers().between(-3, 3).map(String::valueOf),
                Arbitraries.of("\"x\"", "\"y\"", "true", "false", "null", "@\"2025-01-31\"", "@\"P1D\"", "@\"P0Y\""));
        return Arbitraries.recursive(() -> simple, inner -> Arbitraries.oneOf(
                inner.list().ofMaxSize(3).map(items -> "[" + String.join(", ", items) + "]"),
                Combinators.combine(inner, inner).as((x, y) -> "{k: " + x + ", m: " + y + "}"),
                Combinators.combine(inner, inner).as((x, y) -> "[" + x + ".." + y + "]")), 0, 3);
    }

    @Property(tries = TRIES)
    void equality_is_reflexive_and_symmetric(@ForAll("literals") String x, @ForAll("literals") String y) {
        Object xx = FeelExpression.parse("(" + x + ") = (" + x + ")").evaluate(name -> null);
        Object xy = FeelExpression.parse("(" + x + ") = (" + y + ")").evaluate(name -> null);
        Object yx = FeelExpression.parse("(" + y + ") = (" + x + ")").evaluate(name -> null);
        if (FeelExpression.parse(x).evaluate(name -> null) != null) {
            assertThat(xx).as("%s = %s", x, x).isEqualTo(true);
        }
        assertThat(xy).as("%s = %s against %s = %s", x, y, y, x).isEqualTo(yx);
    }

    @Property(tries = TRIES)
    void de_morgan_holds_for_three_values(@ForAll("tests") String p, @ForAll("tests") String q) {
        // not(p and q) = not(p) or not(q), with not written as an if, so that the law is FEEL's logic, not not()'s
        var left = FeelExpression.parse(negation("(" + p + " and " + q + ")")).evaluate(name -> null);
        var right = FeelExpression.parse(negation(p) + " or " + negation(q)).evaluate(name -> null);
        assertThat(left).isEqualTo(right);
    }

    @Provide
    Arbitrary<String> tests() {
        return Arbitraries.of("true", "false", "null");
    }

    private static String negation(String test) {
        return "(if " + test + " = true then false else if " + test + " = false then true else null)";
    }
}
