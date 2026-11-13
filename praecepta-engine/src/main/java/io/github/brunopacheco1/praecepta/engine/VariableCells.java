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
            case UnaryTests.Positive(var tests) -> any(tests, value, inputs) == Boolean.TRUE;
            case UnaryTests.Negated(var tests) -> any(tests, value, inputs) == Boolean.FALSE;
        };
    }

    /** True if a test is true; else unknown (null) if one is unknown; else false. */
    private static Boolean any(List<Test> tests, Object value, Function<String, Object> inputs) {
        Boolean result = Boolean.FALSE;
        for (var test : tests) {
            var outcome = test(test, value, inputs);
            if (outcome == Boolean.TRUE) {
                return Boolean.TRUE;
            }
            if (outcome == null) {
                result = null;
            }
        }
        return result;
    }

    private static Boolean test(Test test, Object value, Function<String, Object> inputs) {
        return switch (test) {
            case NullTest n -> value == null;
            case UnaryTests.NullElement n -> value == null; // only list columns' cells hold it, and they name no input
            case NotEqual(var negated) -> {
                var equal = test(negated, value, inputs); // != is not(=), so unknown stays unknown
                yield equal == null ? null : !equal;
            }
            case Equals(var literal, var explicit) -> {
                var other = resolve(literal, inputs);
                if (other instanceof List<?> list) {
                    yield in(value, list);
                }
                if (value == null || other == null) {
                    yield value == other;
                }
                yield FeelTemporals.comparable(value, other) ? FeelTemporals.compare(value, other) == 0 : null;
            }
            case Compare(var operator, var literal) -> {
                var other = resolve(literal, inputs);
                if (value == null || other == null || !FeelTemporals.comparable(value, other)) {
                    yield null;
                }
                int cmp = FeelTemporals.compare(value, other);
                yield switch (operator) {
                    case LT -> cmp < 0;
                    case LE -> cmp <= 0;
                    case GT -> cmp > 0;
                    case GE -> cmp >= 0;
                };
            }
            case Range(var low, var lowClosed, var high, var highClosed) -> {
                var from = resolve(low, inputs);
                var to = resolve(high, inputs);
                if (value == null || from == null || to == null) {
                    yield null;
                }
                // e1 >= e2 and e1 <= e3 (Table 55), each unknown when FEEL does not define it (a time with a zone id)
                var aboveLow = !FeelTemporals.comparable(value, from) ? null
                        : Boolean.valueOf(lowClosed ? FeelTemporals.compare(value, from) >= 0 : FeelTemporals.compare(value, from) > 0);
                var belowHigh = !FeelTemporals.comparable(value, to) ? null
                        : Boolean.valueOf(highClosed ? FeelTemporals.compare(value, to) <= 0 : FeelTemporals.compare(value, to) < 0);
                if (aboveLow == Boolean.FALSE || belowHigh == Boolean.FALSE) {
                    yield Boolean.FALSE;
                }
                yield aboveLow == null || belowHigh == null ? null : Boolean.TRUE;
            }
        };
    }

    /**
     * Membership in a named list, as FEEL's {@code ? in list} tests it, which is {@code list contains(list, ?)}
     * (DMN 1.5, Table 55): true if an element equals the value, else unknown if an element cannot be compared
     * with it (a time in another zone), else false. A missing value equals only a missing element, and a missing
     * element only a missing value ({@code null = x} is false).
     */
    private static Boolean in(Object value, List<?> list) {
        if (value == null) {
            return list.stream().anyMatch(Objects::isNull); // List.of(...) would throw on contains(null)
        }
        Boolean in = Boolean.FALSE;
        for (var element : list) {
            if (element == null) {
                continue; // a missing element equals no value that is there
            }
            if (!FeelTemporals.comparable(value, element)) {
                in = null; // an element FEEL cannot compare with the value: unknown, unless another equals it
            } else if (FeelTemporals.compare(value, element) == 0) {
                return Boolean.TRUE;
            }
        }
        return in;
    }

    private static Object resolve(Comparable<?> literal, Function<String, Object> inputs) {
        return literal instanceof Name(var name) ? inputs.apply(name) : literal;
    }
}
