/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Invalid;

/**
 * The arguments of FEEL's built-in functions (DMN 1.5, 10.3.4), as their bodies read them: each of a parameter's type,
 * or {@link Invalid}, which makes the invocation null with an error naming the parameter and what it was given. A
 * parameter of a simple type given a list of one value takes that value, and one of a list type given a single value
 * takes the list of it (10.3.2.9.4, "from singleton list" and "to singleton list").
 */
final class Arguments {

    private Arguments() {
    }

    /** The value of a parameter of a simple type: the element of a list of one ("from singleton list"). */
    static Object single(Object value) {
        return value instanceof List<?> list && list.size() == 1 ? list.getFirst() : value;
    }

    static String string(Object value, String parameter) {
        return single(value) instanceof String text ? text : reject("a string", value, parameter);
    }

    static BigDecimal number(Object value, String parameter) {
        return single(value) instanceof BigDecimal number ? number : reject("a number", value, parameter);
    }

    static Boolean bool(Object value, String parameter) {
        return single(value) instanceof Boolean bool ? bool : reject("a boolean", value, parameter);
    }

    /**
     * The whole part of a number, as an int: a position, a length or a scale, which FEEL takes as the number's whole
     * part ({@code substring("foobar", 3, 3.8)} is {@code "oba"}, the DMN TCK's {@code 1103-feel-substring-function}).
     */
    static int whole(Object value, String parameter) {
        var number = number(value, parameter);
        try {
            return number.setScale(0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            return reject("a number from %d to %d".formatted(Integer.MIN_VALUE, Integer.MAX_VALUE), value, parameter);
        }
    }

    /** A list; a single value, but null, as the list of it ("to singleton list"). */
    static List<?> list(Object value, String parameter) {
        if (value instanceof List<?> list) {
            return list;
        }
        return value == null ? reject("a list", null, parameter) : Collections.singletonList(value);
    }

    /** A context. */
    static Map<?, ?> context(Object value, String parameter) {
        return single(value) instanceof Map<?, ?> context ? context : reject("a context", value, parameter);
    }

    /** A function. */
    static FeelFunction function(Object value, String parameter) {
        return single(value) instanceof FeelFunction function ? function : reject("a function", value, parameter);
    }

    /**
     * The values of a function of a list or of its values ({@code min(list)}, {@code min(c1, c2, ...)}): the list,
     * if it is given one argument (a single value as the list of it), else the arguments.
     */
    static List<?> items(List<Object> arguments, String parameter) {
        return arguments.size() == 1 ? list(arguments.getFirst(), parameter) : arguments;
    }

    /** Counts {@code count} steps of the evaluation, or stops the invocation, if it is beyond its limits. */
    static void steps(Env env, long count) {
        if (!env.step(count)) {
            throw new Invalid(null);
        }
    }

    /** Counts the characters of a string a function makes, or stops the invocation, if it is beyond the limits. */
    static String characters(Env env, String made) {
        if (!env.characters(made.length())) {
            throw new Invalid(null);
        }
        return made;
    }

    /** A list FEEL gives: unmodifiable, and it may hold null. */
    static List<Object> result(List<?> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** No value: the parameter is given what it does not take. */
    static <T> T reject(String what, Object value, String parameter) {
        throw invalid(what, value, parameter);
    }

    /** That a parameter takes values of a kind, not the one it is given. */
    static Invalid invalid(String what, Object value, String parameter) {
        return new Invalid("takes %s as its '%s', not %s".formatted(what, parameter, FeelValues.text(value)));
    }

    /** No value, for a reason of the function's own. */
    static Invalid invalid(String why) {
        return new Invalid(why);
    }
}
