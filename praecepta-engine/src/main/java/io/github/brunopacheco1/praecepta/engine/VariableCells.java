/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.List;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Name;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Decides, on concrete values, a cell that compares with other inputs ({@code > dateE},
 * {@code [numB..numC]}), with FEEL's three-valued logic: a comparison with a missing value is unknown,
 * so it fails, and so does its {@code not(...)}; {@code = x} is true when both are missing. Comparing a
 * time or date-time with a time zone with one without, equality included, is unknown too.
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
            case Equals(var literal) -> {
                var other = resolve(literal, inputs);
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
                if (value == null || from == null || to == null
                        || !FeelTemporals.comparable(value, from) || !FeelTemporals.comparable(value, to)) {
                    yield null;
                }
                int above = FeelTemporals.compare(value, from);
                int below = FeelTemporals.compare(value, to);
                yield (lowClosed ? above >= 0 : above > 0) && (highClosed ? below <= 0 : below < 0);
            }
        };
    }

    private static Object resolve(Comparable<?> literal, Function<String, Object> inputs) {
        return literal instanceof Name(var name) ? inputs.apply(name) : literal;
    }
}
