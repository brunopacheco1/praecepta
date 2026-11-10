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
     * {@link String#compareTo} does (character by character, by UTF-16 code unit).
     */
    STRING,
    /** Decimal numbers, compared by value ({@code 1.0} equals {@code 1}). Cells add comparisons and intervals. */
    NUMBER,
    /** Calendar dates ({@code LocalDate}). Cells use {@code date("2025-01-31")}, comparisons and intervals. */
    DATE,
    /** {@code true} or {@code false} ({@code Boolean}). Cells: {@code true}, {@code false}, lists, {@code not(...)}; no comparisons. */
    BOOLEAN,
    /** Times of day without a zone ({@code LocalTime}). Cells use {@code time("08:30:00")}, comparisons and intervals. */
    TIME,
    /**
     * Dates with a time of day, without a zone ({@code LocalDateTime}). Cells use
     * {@code date and time("2025-01-31T08:30:00")}, comparisons and intervals.
     */
    DATE_TIME;

    /** The type's name in FEEL and in messages: {@code string}, {@code number}, ..., {@code date and time}. */
    public String feelName() {
        return this == DATE_TIME ? "date and time" : name().toLowerCase(Locale.ROOT);
    }

    /** Whether cells may compare values of this type ({@code <}, {@code >=}, intervals): all but BOOLEAN. */
    public boolean supportsComparisons() {
        return this != BOOLEAN;
    }
}
