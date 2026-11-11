/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

/**
 * Whether the inputs of a TIME or DATE_TIME column come without a time zone, with one, or either way.
 * The input binding decides: {@code timeInput} and {@code dateTimeInput} give values without a zone,
 * {@code offsetTimeInput} and {@code zonedDateTimeInput} values with one, and map-based inputs, read
 * from text, either. A {@link ColumnIndex} splits only the kinds its inputs can take into classes, so
 * the static analysis looks for gaps among those values and no others. Columns of other types have no
 * zone and are always {@link #LOCAL}.
 */
enum Zoning {
    /** Values without a time zone only. */
    LOCAL,
    /** Times and date-times with a time zone only. */
    ZONED,
    /** Both. */
    EITHER;

    boolean takesLocal() {
        return this != ZONED;
    }

    boolean takesZoned() {
        return this != LOCAL;
    }
}
