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
 * zone id, which may be a zone with one fixed offset and so a time at that offset (DMN 1.5, Table 72), and
 * map-based inputs, read from text, any of them. A {@link ColumnIndex} splits only the kinds its inputs can take into classes, so
 * the static analysis looks for gaps among those values and no others. Columns of other types have no
 * zone and are always {@link #LOCAL}.
 */
enum Zoning {
    /** Values without a time zone only. */
    LOCAL,
    /** Times and date-times with a time zone that has an offset only. */
    ZONED,
    /**
     * Times with a zone id only ({@code ZonedTime}); a zone id with one fixed offset ({@code Etc/UTC}) makes a time at
     * that offset, which the column then also takes.
     */
    ZONE_ID,
    /** Any of them. */
    EITHER;

    boolean takesLocal() {
        return this == LOCAL || this == EITHER;
    }

    boolean takesZoned() {
        return this != LOCAL;
    }

    /** Whether a TIME column takes times with a zone id; a date-time's zone id is a time zone like an offset. */
    boolean takesZoneIds() {
        return this == ZONE_ID || this == EITHER;
    }
}
