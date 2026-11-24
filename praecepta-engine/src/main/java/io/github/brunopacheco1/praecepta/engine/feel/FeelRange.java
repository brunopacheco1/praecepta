/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

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
 * @param comparison     for a comparison with one endpoint, its operator ({@code <}, {@code <=}, {@code >}, {@code >=},
 *                       {@code =} or {@code !=}); null for a range between two endpoints
 */
public record FeelRange(Object start, boolean startIncluded, Object end, boolean endIncluded, String comparison) {

    /** A range between two endpoints, such as {@code [1..10)}. */
    public static FeelRange between(Object start, boolean startIncluded, Object end, boolean endIncluded) {
        return new FeelRange(start, startIncluded, end, endIncluded, null);
    }

    /**
     * The range of the values that satisfy a comparison with one endpoint, such as {@code < 10}.
     *
     * @param operator {@code <}, {@code <=}, {@code >}, {@code >=}, {@code =} or {@code !=}
     * @throws IllegalArgumentException for any other operator
     */
    public static FeelRange comparison(String operator, Object endpoint) {
        return switch (operator) {
            case "<" -> new FeelRange(null, false, endpoint, false, operator);
            case "<=" -> new FeelRange(null, false, endpoint, true, operator);
            case ">" -> new FeelRange(endpoint, false, null, false, operator);
            case ">=" -> new FeelRange(endpoint, true, null, false, operator);
            case "=" -> new FeelRange(endpoint, true, endpoint, true, operator);
            case "!=" -> new FeelRange(endpoint, false, endpoint, false, operator);
            default -> throw new IllegalArgumentException("'" + operator + "' is not a comparison");
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
            return comparison + " " + endpoint(comparison.startsWith("<") ? end : start);
        }
        return (startIncluded ? "[" : "(") + endpoint(start) + ".." + endpoint(end) + (endIncluded ? "]" : ")");
    }
}
