/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Objects;

/**
 * A FEEL range (DMN 1.5, 10.3.2.7): the values between two endpoints, each included or not, written
 * {@code [1..10]}, {@code (1..10]}, {@code ]1..10[}; or the values that satisfy a comparison with one endpoint,
 * written {@code < 10}, {@code <= 10}, {@code > 10}, {@code >= 10}, {@code = 10} or {@code != 10}. Its properties
 * are those Table 65 gives a range: {@code start}, {@code end}, {@code start included} and {@code end included}. A
 * comparison with one endpoint has no start ({@code < 10}, {@code <= 10}) or no end ({@code > 10}, {@code >= 10}),
 * {@code = 10} starts and ends at 10, both included, and {@code != 10} at 10, both excluded. Two ranges are equal in
 * FEEL when they are written alike: {@code (< 10)} is not {@code (null..10)} (the DMN TCK's
 * {@code 0068-feel-equality}), which {@code comparison} tells apart.
 *
 * @param start          the start, or null if it has none
 * @param startIncluded  whether the start is in the range
 * @param end            the end, or null if it has none
 * @param endIncluded    whether the end is in the range
 * @param comparison     for a comparison with one endpoint, its operator; null for a range between two endpoints
 * @throws IllegalArgumentException if a comparison's endpoints are not those it has: a {@code <} or {@code <=} with a
 *                                  start or without an end, an end included for {@code <} or excluded for {@code <=},
 *                                  and so on
 */
public record FeelRange(Object start, boolean startIncluded, Object end, boolean endIncluded, Comparison comparison) {

    /** The operator of a range of one endpoint (DMN 1.5, grammar rule 7). */
    public enum Comparison {
        /** {@code < e}: no start, the end {@code e} excluded. */
        LESS("<"),
        /** {@code <= e}: no start, the end {@code e} included. */
        LESS_OR_EQUAL("<="),
        /** {@code > e}: the start {@code e} excluded, no end. */
        GREATER(">"),
        /** {@code >= e}: the start {@code e} included, no end. */
        GREATER_OR_EQUAL(">="),
        /** {@code = e}: the start and end {@code e}, both included. */
        EQUAL("="),
        /** {@code != e}: the start and end {@code e}, both excluded. */
        NOT_EQUAL("!=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        /** The operator as FEEL writes it: {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =} or {@code !=}. */
        public String symbol() {
            return symbol;
        }

        /**
         * The comparison FEEL writes with a symbol.
         *
         * @throws IllegalArgumentException if it is none of {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =} and
         *                                  {@code !=}
         */
        public static Comparison of(String symbol) {
            for (var comparison : values()) {
                if (comparison.symbol.equals(symbol)) {
                    return comparison;
                }
            }
            throw new IllegalArgumentException("'" + symbol + "' is not a comparison");
        }

        /** Whether its endpoint is the end of the range, which then has no start: {@code <} and {@code <=}. */
        boolean bounds() {
            return this == LESS || this == LESS_OR_EQUAL;
        }
    }

    /** Checks that a comparison's endpoints and inclusions are those it has. */
    public FeelRange {
        if (comparison != null && !isShapeOf(comparison, start, startIncluded, end, endIncluded)) {
            throw new IllegalArgumentException("a range '%s e' has no start %s %s and end %s %s".formatted(comparison.symbol(),
                    start, startIncluded ? "included" : "excluded", end, endIncluded ? "included" : "excluded"));
        }
    }

    private static boolean isShapeOf(Comparison comparison, Object start, boolean startIncluded, Object end, boolean endIncluded) {
        return switch (comparison) {
            case LESS -> start == null && !startIncluded && !endIncluded;
            case LESS_OR_EQUAL -> start == null && !startIncluded && endIncluded;
            case GREATER -> end == null && !startIncluded && !endIncluded;
            case GREATER_OR_EQUAL -> end == null && startIncluded && !endIncluded;
            case EQUAL -> Objects.equals(start, end) && startIncluded && endIncluded;
            case NOT_EQUAL -> Objects.equals(start, end) && !startIncluded && !endIncluded;
        };
    }

    /** A range between two endpoints, such as {@code [1..10)}. */
    public static FeelRange between(Object start, boolean startIncluded, Object end, boolean endIncluded) {
        return new FeelRange(start, startIncluded, end, endIncluded, null);
    }

    /** The range of the values that satisfy a comparison with one endpoint, such as {@code < 10}. */
    public static FeelRange comparison(Comparison comparison, Object endpoint) {
        return switch (comparison) {
            case LESS -> new FeelRange(null, false, endpoint, false, comparison);
            case LESS_OR_EQUAL -> new FeelRange(null, false, endpoint, true, comparison);
            case GREATER -> new FeelRange(endpoint, false, null, false, comparison);
            case GREATER_OR_EQUAL -> new FeelRange(endpoint, true, null, false, comparison);
            case EQUAL -> new FeelRange(endpoint, true, endpoint, true, comparison);
            case NOT_EQUAL -> new FeelRange(endpoint, false, endpoint, false, comparison);
        };
    }

    /** An endpoint as {@link #toString} writes it: a simple value's text, or the kind of any other value. */
    private static String endpoint(Object value) {
        return value == null || FeelValues.simpleType(value) != null ? FeelValues.text(value) : FeelValues.typeName(value);
    }

    /** The range as FEEL writes it: {@code [1..10)}, {@code < 10}. */
    @Override
    public String toString() {
        if (comparison != null) {
            return comparison.symbol() + " " + endpoint(comparison.bounds() ? end : start);
        }
        return (startIncluded ? "[" : "(") + endpoint(start) + ".." + endpoint(end) + (endIncluded ? "]" : ")");
    }
}
