/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

/**
 * Whether the inputs of a TIME or DATE_TIME column come without a time zone, with one, or either way.
 * The input binding decides: {@code timeInput} and {@code dateTimeInput} give values without a zone,
 * {@code offsetTimeInput} and {@code zonedDateTimeInput} values with one, {@code zonedTimeInput} times with a
 * zone id, and map-based inputs, read from text, any of them. A {@link ColumnIndex} splits only the kinds its inputs can take into classes, so
 * the static analysis looks for gaps among those values and no others. Columns of other types have no
 * zone and are always {@link #LOCAL}.
 */
enum Zoning {
    /** Values without a time zone only. */
    LOCAL,
    /** Times and date-times with a time zone that has an offset only. */
    ZONED,
    /** Times with a zone id only ({@code ZonedTime}). */
    ZONE_ID,
    /** Any of them. */
    EITHER;

    boolean takesLocal() {
        return this == LOCAL || this == EITHER;
    }

    boolean takesZoned() {
        return this == ZONED || this == EITHER;
    }

    /** Whether a TIME column takes times with a zone id; a date-time's zone id is a time zone like an offset. */
    boolean takesZoneIds() {
        return this == ZONE_ID || this == EITHER;
    }
}
