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
 * Properties of FEEL as {@link FeelExpression} evaluates it: it agrees with the arithmetic output entries compute
 * ({@link OutputExpression}), on which 0.7's literal expressions ran; equality is reflexive and symmetric
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
    void arithmetic_agrees_with_output_expressions(@ForAll("arithmetic") String text) {
        var expected = OutputExpression.parse(text).evaluate(VALUES::get);
        var actual = FeelExpression.parse(text, List.of("a", "b", "c")).evaluate(VALUES::get);
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat((BigDecimal) actual).isEqualByComparingTo((BigDecimal) expected);
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
        // not(p and q) = not(p) or not(q), with not written as an if, as not() is a built-in function (0.9)
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
