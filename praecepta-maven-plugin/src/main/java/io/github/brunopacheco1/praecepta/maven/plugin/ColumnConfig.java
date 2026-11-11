/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.util.ArrayList;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * A column of an Excel or CSV table, as configured in the pom:
 *
 * <pre>{@code
 * <input>
 *   <name>country</name>
 *   <allowedValues>"PT","ES","FR"</allowedValues>
 * </input>
 * <input>
 *   <name>age</name>
 *   <type>NUMBER</type>
 * </input>
 * <input>
 *   <name>nationalities</name>
 *   <list>true</list>
 * </input>
 * <output>
 *   <name>tier</name>
 *   <priority><value>gold</value><value>silver</value></priority>
 * </output>
 * }</pre>
 */
public class ColumnConfig {

    /** The column's header in the sheet. */
    String name;

    /**
     * STRING (default), NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION or YEAR_MONTH_DURATION. A
     * TIME or DATE_TIME column whose values are all written with a time zone generates an {@code OffsetTime}
     * or {@code ZonedDateTime} (see {@link TimeZones}).
     */
    ColumnType type = ColumnType.STRING;

    /**
     * Inputs only: the DMN input value list, as unary tests ({@code "PT","ES"} or {@code [0..120]}).
     * A string column with a value list becomes an enum in the generated input record.
     */
    String allowedValues;

    /** Outputs only: the DMN output value list, highest priority first (PRIORITY and OUTPUT_ORDER). */
    List<String> priority = new ArrayList<>();

    /**
     * Inputs only: whether the column holds lists of its type, such as a customer's nationalities. A
     * cell then matches when some element passes one of its tests; the input record gets a
     * {@code java.util.List} component, and {@code allowedValues} apply to the elements.
     */
    boolean list;

    public ColumnConfig() {
    }

    ColumnConfig(String name, ColumnType type, String allowedValues, List<String> priority) {
        this.name = name;
        this.type = type;
        this.allowedValues = allowedValues;
        this.priority = new ArrayList<>(priority);
    }

    /** An input column that holds lists of {@code type}. */
    static ColumnConfig listInput(String name, ColumnType type, String allowedValues) {
        var column = new ColumnConfig(name, type, allowedValues, List.of());
        column.list = true;
        return column;
    }
}
