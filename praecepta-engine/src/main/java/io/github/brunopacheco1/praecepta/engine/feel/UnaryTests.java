package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.List;

/**
 * A parsed table cell: the subset of DMN FEEL "simple unary tests" Praecepta supports. Every test
 * looks at a single column value, which is what lets rules be indexed with bitmasks.
 *
 * <p>Values are {@code String}, {@code BigDecimal} or {@code LocalDate}, depending on the column type.
 */
public sealed interface UnaryTests {

    /** {@code -} or a blank cell: matches every value, including a missing one. */
    record Any() implements UnaryTests {
    }

    /** {@code t1, t2, ...}: matches when any test matches. */
    record Positive(List<Test> tests) implements UnaryTests {
        public Positive {
            tests = List.copyOf(tests);
        }
    }

    /** {@code not(t1, t2, ...)}: matches when none of the tests matches (FEEL three-valued logic). */
    record Negated(List<Test> tests) implements UnaryTests {
        public Negated {
            tests = List.copyOf(tests);
        }
    }

    /** One test of a cell. */
    sealed interface Test {
    }

    /** {@code null}: the value is missing. */
    record NullTest() implements Test {
    }

    /** A literal: the value equals it. */
    record Equals(Comparable<?> value) implements Test {
    }

    /** {@code < x}, {@code <= x}, {@code > x}, {@code >= x}. */
    record Compare(Operator operator, Comparable<?> value) implements Test {
    }

    /** {@code [a..b]}, with {@code (} / {@code ]} at the start or {@code )} / {@code [} at the end for open ends. */
    record Range(Comparable<?> low, boolean lowClosed, Comparable<?> high, boolean highClosed) implements Test {
    }

    enum Operator {
        LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
