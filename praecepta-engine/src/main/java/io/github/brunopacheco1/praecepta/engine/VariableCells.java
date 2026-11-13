/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Name;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Decides, on concrete values, a cell that compares with other inputs ({@code > dateE},
 * {@code [numB..numC]}), with FEEL's three-valued logic: a comparison with a missing value is unknown,
 * so it fails, and so does its {@code not(...)}; {@code = x} is true when both are missing, and {@code != x} is
 * its opposite. A time or
 * date-time with a time zone and one without compare by their local time ({@link FeelTemporals}). A plain
 * name whose input holds a list ({@code Flu Symptoms}) is true when the value is in that list, as in
 * DMN; the engine only lets list inputs be named that way.
 */
final class VariableCells {

    private VariableCells() {
    }

    /**
     * @param value  the column's value, null if missing
     * @param inputs the value of each named input, null if missing
     */
    static boolean accepts(UnaryTests cell, Object value, Function<String, Object> inputs) {
        return switch (cell) {
            case UnaryTests.Any any -> true;
            case UnaryTests.Positive(var tests) -> any(tests, value, inputs) == Truth.TRUE;
            case UnaryTests.Negated(var tests) -> any(tests, value, inputs) == Truth.FALSE;
        };
    }

    /** True if a test is true; else unknown if one is unknown; else false. */
    private static Truth any(List<Test> tests, Object value, Function<String, Object> inputs) {
        var result = Truth.FALSE;
        for (var test : tests) {
            var outcome = test(test, value, inputs);
            if (outcome == Truth.TRUE) {
                return Truth.TRUE;
            }
            if (outcome == Truth.UNKNOWN) {
                result = Truth.UNKNOWN;
            }
        }
        return result;
    }

    private static Truth test(Test test, Object value, Function<String, Object> inputs) {
        return switch (test) {
            case NullTest n -> Truth.of(value == null);
            case UnaryTests.NullElement n -> Truth.of(value == null); // only list columns' cells hold it, and they name no input
            case NotEqual(var negated) -> test(negated, value, inputs).not(); // != is not(=), so unknown stays unknown
            case Equals(var literal, var explicit) -> equal(value, resolve(literal, inputs));
            case Compare(var operator, var literal) -> compare(value, operator, resolve(literal, inputs));
            case Range(var low, var lowClosed, var high, var highClosed) -> {
                var from = resolve(low, inputs);
                var to = resolve(high, inputs);
                if (value == null || from == null || to == null) {
                    yield Truth.UNKNOWN;
                }
                // e1 >= e2 and e1 <= e3 (Table 55), each unknown when FEEL does not define it (a time with a zone id)
                yield compare(value, lowClosed ? Operator.GE : Operator.GT, from)
                        .and(compare(value, highClosed ? Operator.LE : Operator.LT, to));
            }
        };
    }

    /** {@code value = other}: a member of {@code other} if it is a list; true if both are missing, false if one is. */
    private static Truth equal(Object value, Object other) {
        if (other instanceof List<?> list) {
            return in(value, list);
        }
        if (value == null || other == null) {
            return Truth.of(value == other);
        }
        return FeelTemporals.comparable(value, other) ? Truth.of(FeelTemporals.compare(value, other) == 0) : Truth.UNKNOWN;
    }

    /** {@code value operator other}: unknown if either is missing, or FEEL does not compare them. */
    private static Truth compare(Object value, Operator operator, Object other) {
        if (value == null || other == null || !FeelTemporals.comparable(value, other)) {
            return Truth.UNKNOWN;
        }
        int cmp = FeelTemporals.compare(value, other);
        return Truth.of(switch (operator) {
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
        });
    }

    /**
     * Membership in a named list, as FEEL's {@code ? in list} tests it, which is {@code list contains(list, ?)}
     * (DMN 1.5, Table 55): true if an element equals the value, else unknown if an element cannot be compared
     * with it (a time in another zone), else false. A missing value equals only a missing element, and a missing
     * element only a missing value ({@code null = x} is false).
     */
    private static Truth in(Object value, List<?> list) {
        if (value == null) {
            return Truth.of(list.stream().anyMatch(Objects::isNull)); // List.of(...) would throw on contains(null)
        }
        var in = Truth.FALSE;
        for (var element : list) {
            if (element == null) {
                continue; // a missing element equals no value that is there
            }
            if (!FeelTemporals.comparable(value, element)) {
                in = Truth.UNKNOWN; // an element FEEL cannot compare with the value: unknown, unless another equals it
            } else if (FeelTemporals.compare(value, element) == 0) {
                return Truth.TRUE;
            }
        }
        return in;
    }

    private static Object resolve(Comparable<?> literal, Function<String, Object> inputs) {
        return literal instanceof Name(var name) ? inputs.apply(name) : literal;
    }
}
