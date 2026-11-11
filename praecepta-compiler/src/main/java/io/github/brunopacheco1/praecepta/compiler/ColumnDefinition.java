/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * A column of an Excel or CSV table, as a build tool's configuration declares it.
 *
 * @param name          the column's header in the sheet
 * @param type          STRING (when null), NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION or
 *                      YEAR_MONTH_DURATION. A TIME or DATE_TIME column whose values are all written with a
 *                      time zone generates an {@code OffsetTime} or {@code ZonedDateTime}
 * @param allowedValues inputs only: the DMN input value list, as unary tests ({@code "PT","ES"} or
 *                      {@code [0..120]}); a string column with a value list becomes an enum in the generated
 *                      input record. Null for none
 * @param priority      outputs only: the DMN output value list, highest priority first (PRIORITY and
 *                      OUTPUT_ORDER); empty for none
 * @param list          inputs only: whether the column holds lists of its type, such as a customer's
 *                      nationalities. A cell then matches when some element passes one of its tests; the
 *                      input record gets a {@code java.util.List} component, and {@code allowedValues} apply
 *                      to the elements
 */
public record ColumnDefinition(String name, ColumnType type, String allowedValues, List<String> priority, boolean list) {

    /** A null type is STRING, and a null priority list empty. */
    public ColumnDefinition {
        type = type == null ? ColumnType.STRING : type;
        priority = priority == null ? List.of() : List.copyOf(priority);
    }

    /** A column of single values. */
    public ColumnDefinition(String name, ColumnType type, String allowedValues, List<String> priority) {
        this(name, type, allowedValues, priority, false);
    }

    /** An input column of single values, without a value list. */
    public static ColumnDefinition of(String name, ColumnType type) {
        return new ColumnDefinition(name, type, null, List.of());
    }

    /** An input column that holds lists of {@code type}. */
    public static ColumnDefinition listInput(String name, ColumnType type, String allowedValues) {
        return new ColumnDefinition(name, type, allowedValues, List.of(), true);
    }
}
