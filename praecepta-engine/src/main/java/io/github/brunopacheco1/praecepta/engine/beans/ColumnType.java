/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Locale;

/** The type of an input column, which decides how its cells are parsed and its values compared. */
public enum ColumnType {
    /**
     * Text, compared exactly. Cells: literals ({@code "PT"} or legacy {@code PT}), lists, {@code not(...)},
     * and comparisons and intervals ({@code < "N"}, {@code ["A".."M"]}), which order strings as
     * FEEL does: character by character, by Unicode code point (see {@code FeelStrings#compare}).
     */
    STRING,
    /** Decimal numbers, compared by value ({@code 1.0} equals {@code 1}). Cells add comparisons and intervals. */
    NUMBER,
    /** Calendar dates ({@code LocalDate}). Cells use {@code date("2025-01-31")}, comparisons and intervals. */
    DATE,
    /** {@code true} or {@code false} ({@code Boolean}). Cells: {@code true}, {@code false}, lists, {@code not(...)}; no comparisons. */
    BOOLEAN,
    /**
     * Times of day, without a zone ({@code LocalTime}), with an offset ({@code OffsetTime}) or with a zone id
     * ({@link io.github.brunopacheco1.praecepta.engine.feel.ZonedTime}). Cells use {@code time("08:30:00")},
     * {@code time("08:30:00+01:00")} or {@code time("08:30:00@Europe/Paris")}, comparisons and intervals. Two times
     * with an offset compare by instant; a time with an offset and one without compare by local time; a time with a
     * zone id compares by local time with one in the same zone, and with no other.
     */
    TIME,
    /**
     * Dates with a time of day, without a zone ({@code LocalDateTime}) or with an offset or a zone id
     * ({@code ZonedDateTime}). Cells use {@code date and time("2025-01-31T08:30:00")},
     * {@code date and time("2025-01-31T08:30:00Z")} or {@code date and time("2025-01-31T08:30:00@Europe/Paris")},
     * comparisons and intervals. Two date-times with a zone compare by instant; one with a zone and
     * one without compare by local date and time.
     */
    DATE_TIME,
    /**
     * Durations of days, hours, minutes and seconds ({@code java.time.Duration}), compared by length.
     * Cells use {@code duration("P1DT2H")}, comparisons and intervals.
     */
    DAY_TIME_DURATION,
    /**
     * Durations of years and months ({@link io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths},
     * bound from a {@code java.time.Period}), compared by their total of months ({@code P1Y} equals
     * {@code P12M}). Cells use {@code duration("P1Y2M")}, comparisons and intervals.
     */
    YEAR_MONTH_DURATION;

    /** The type's name in FEEL and in messages: {@code string}, {@code number}, ..., {@code date and time}. */
    public String feelName() {
        return switch (this) {
            case DATE_TIME -> "date and time";
            case DAY_TIME_DURATION -> "days and time duration";
            case YEAR_MONTH_DURATION -> "years and months duration";
            default -> name().toLowerCase(Locale.ROOT);
        };
    }

    /** Whether cells may compare values of this type ({@code <}, {@code >=}, intervals): all but BOOLEAN. */
    public boolean supportsComparisons() {
        return this != BOOLEAN;
    }
}
