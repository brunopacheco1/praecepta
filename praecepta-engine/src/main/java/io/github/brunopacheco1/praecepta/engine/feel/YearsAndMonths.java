/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.Period;
import java.time.format.DateTimeParseException;

/**
 * A FEEL years and months duration, such as {@code P1Y2M}: a whole number of months, which is how
 * FEEL compares them ({@code P1Y} equals {@code P12M}). {@link Period} is not comparable and keeps
 * {@code P1Y} and {@code P12M} apart, so cells and the index hold this instead.
 *
 * @param months the total number of months; negative for a negative duration
 */
public record YearsAndMonths(long months) implements Comparable<YearsAndMonths> {

    /** The duration of a period's years and months. */
    public static YearsAndMonths of(Period period) {
        if (period.getDays() != 0) {
            throw new IllegalArgumentException("'" + period + "' has days; a years and months duration has only years and months");
        }
        return new YearsAndMonths(period.toTotalMonths());
    }

    /**
     * Parses ISO 8601 text such as {@code P1Y2M}, {@code P14M} or {@code -P1Y}.
     *
     * @throws DateTimeParseException   if the text is not an ISO period
     * @throws IllegalArgumentException if it has days (or weeks)
     */
    public static YearsAndMonths parse(String text) {
        return of(Period.parse(text));
    }

    /** As a {@link Period} of years and months, e.g. {@code P1Y2M}. */
    public Period toPeriod() {
        return Period.ofMonths(Math.toIntExact(months)).normalized();
    }

    @Override
    public int compareTo(YearsAndMonths other) {
        return Long.compare(months, other.months);
    }

    /** The ISO text FEEL writes: {@code P1Y2M}, {@code -P3M}, {@code P0M}. */
    @Override
    public String toString() {
        long total = Math.abs(months);
        var text = new StringBuilder(months < 0 ? "-P" : "P");
        if (total >= 12) {
            text.append(total / 12).append('Y');
        }
        if (total % 12 != 0 || total < 12) {
            text.append(total % 12).append('M');
        }
        return text.toString();
    }
}
