/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.List;
import java.util.Optional;

/**
 * A parsed table cell: the subset of DMN FEEL "simple unary tests" Praecepta supports. Every test
 * looks at a single column value, which is what lets rules be indexed with bitmasks.
 *
 * <p>Values are {@code String}, {@code BigDecimal}, {@code LocalDate}, {@code Boolean}, {@code LocalTime} or
 * {@code LocalDateTime}, depending on the column type.
 *
 * <p>A missing (null) value follows FEEL's three-valued logic: {@code null} is true for it, a literal is
 * false, and a comparison or an interval is unknown, which is neither a match nor a mismatch.
 */
public sealed interface UnaryTests {

    /**
     * The values of a plain list of literals, such as {@code "A","B"} or {@code 1, 2}, in the order written
     * (duplicates included). Empty for any other cell: {@code -}, {@code not(...)}, {@code null}, a
     * comparison or an interval. Value lists of string columns and output value lists must have this form.
     */
    default Optional<List<Comparable<?>>> values() {
        if (!(this instanceof Positive(var tests)) || !tests.stream().allMatch(Equals.class::isInstance)) {
            return Optional.empty();
        }
        return Optional.of(tests.stream().<Comparable<?>>map(test -> ((Equals) test).value()).toList());
    }

    /** {@code -} or a blank cell: matches every value, including a missing one. */
    record Any() implements UnaryTests {
    }

    /** {@code t1, t2, ...}: matches when at least one test is true. */
    record Positive(List<Test> tests) implements UnaryTests {
        public Positive {
            tests = List.copyOf(tests);
        }
    }

    /**
     * {@code not(t1, t2, ...)}: matches when every test is false. A test that is unknown makes the
     * whole cell unknown, so {@code not(< 18)} does not match a missing value.
     */
    record Negated(List<Test> tests) implements UnaryTests {
        public Negated {
            tests = List.copyOf(tests);
        }
    }

    /** One test of a cell: true, false or unknown for a given value. */
    sealed interface Test {
    }

    /** {@code null}: true only when the value is missing. */
    record NullTest() implements Test {
    }

    /** A literal such as {@code "PT"}, {@code 18} or {@code date("2025-01-31")}: true when the value equals it. */
    record Equals(Comparable<?> value) implements Test {
    }

    /** {@code < x}, {@code <= x}, {@code > x} or {@code >= x}: true when the value compares to {@code x} that way. */
    record Compare(Operator operator, Comparable<?> value) implements Test {
    }

    /**
     * {@code [low..high]}: true when the value lies between {@code low} and {@code high}. An end is
     * included ({@code lowClosed}, {@code highClosed}) when written with {@code [} at the start or
     * {@code ]} at the end, and excluded when written with {@code (} or {@code ]} at the start, or
     * {@code )} or {@code [} at the end.
     */
    record Range(Comparable<?> low, boolean lowClosed, Comparable<?> high, boolean highClosed) implements Test {
    }

    /** A comparison operator. */
    enum Operator {
        LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        /** The operator as FEEL writes it: {@code <}, {@code <=}, {@code >} or {@code >=}. */
        public String symbol() {
            return symbol;
        }
    }
}
