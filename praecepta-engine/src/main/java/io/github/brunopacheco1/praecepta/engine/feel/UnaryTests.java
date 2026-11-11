/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A parsed table cell: the subset of DMN FEEL "simple unary tests" Praecepta supports. Every test
 * looks at a single column value, which is what lets rules be indexed with bitmasks; a test that
 * compares with another input ({@link Name}, strict FEEL only) is decided when evaluating instead.
 *
 * <p>Values are {@code String}, {@code BigDecimal}, {@code LocalDate}, {@code Boolean}, {@code LocalTime} or
 * {@code OffsetTime}, {@code LocalDateTime} or {@code ZonedDateTime}, {@code Duration} or
 * {@link YearsAndMonths}, depending on the column type, or a {@link Name}. Times and date-times with a
 * time zone compare by instant, and not at all with those without one ({@link FeelTemporals}): such a
 * comparison, equality included, is unknown.
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
        if (!(this instanceof Positive(var tests))
                || !tests.stream().allMatch(test -> test instanceof Equals(var value) && !(value instanceof Name))) {
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

    /** The names of the inputs a cell compares with ({@link Name}), in the order written. */
    static List<String> names(UnaryTests cell) {
        var tests = switch (cell) {
            case Any any -> List.<Test>of();
            case Positive(var positive) -> positive;
            case Negated(var negated) -> negated;
        };
        var names = new ArrayList<String>();
        for (var test : tests) {
            switch (test) {
                case NullTest n -> {
                    // no value
                }
                case Equals(var value) -> addName(value, names);
                case Compare(var operator, var value) -> addName(value, names);
                case Range(var low, var lowClosed, var high, var highClosed) -> {
                    addName(low, names);
                    addName(high, names);
                }
            }
        }
        return names;
    }

    private static void addName(Comparable<?> value, List<String> names) {
        if (value instanceof Name(var name)) {
            names.add(name);
        }
    }

    /**
     * Whether some test compares with another input ({@link Name}) instead of a literal. Such a cell can only
     * be decided once that input's value is known.
     */
    default boolean namesInputs() {
        return !names(this).isEmpty();
    }

    /**
     * In strict FEEL, a name where a literal could be, as in {@code > dateE} or {@code [numB..numC]}: the
     * value of that input, known only when evaluating. It is comparable only so that it fits where literals
     * go; names compare by their text.
     *
     * @param name the input's name, e.g. {@code dateE} or {@code Complex.aString}
     */
    record Name(String name) implements Comparable<Name> {
        @Override
        public int compareTo(Name other) {
            return name.compareTo(other.name);
        }
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
