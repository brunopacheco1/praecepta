/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import javax.inject.Inject;

import org.gradle.api.Named;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * A column of an Excel or CSV table, with the Maven plugin's settings:
 *
 * <pre>{@code
 * column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
 * column("age") { type = "NUMBER" }
 * column("nationalities") { list = true }
 * column("tier") { priority = listOf("gold", "silver") }
 * }</pre>
 */
public abstract class ColumnSpec implements Named {

    private final String name;

    /**
     * Creates a column of type {@code STRING}; {@link Columns#column(String)} does.
     *
     * @param name the column's header in the sheet
     */
    @Inject
    @SuppressWarnings("java:S5993") // Gradle creates its managed types through public constructors only
    public ColumnSpec(String name) {
        this.name = name;
    }

    /**
     * The column's header in the sheet.
     *
     * @return the name
     */
    @Input
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@code STRING} (default), {@code NUMBER}, {@code DATE}, {@code BOOLEAN}, {@code TIME}, {@code DATE_TIME},
     * {@code DAY_TIME_DURATION} or {@code YEAR_MONTH_DURATION}. A {@code TIME} or {@code DATE_TIME} column whose
     * values are all written with a time zone generates an {@code OffsetTime} or {@code ZonedDateTime}.
     *
     * @return the type
     */
    @Input
    public abstract Property<String> getType();

    /**
     * Inputs only: the DMN input value list, as unary tests ({@code "PT","ES"} or {@code [0..120]}). A string column
     * with a value list becomes an enum in the generated input record.
     *
     * @return the allowed values
     */
    @Input
    @Optional
    public abstract Property<String> getAllowedValues();

    /**
     * Outputs only: the DMN output value list, highest priority first ({@code PRIORITY} and {@code OUTPUT_ORDER}).
     *
     * @return the values, highest priority first
     */
    @Input
    public abstract ListProperty<String> getPriority();

    /**
     * Inputs only: whether the column holds lists of its type, such as a customer's nationalities. A cell then
     * matches when some element passes one of its tests; the input record gets a {@code java.util.List} component,
     * and {@code allowedValues} apply to the elements.
     *
     * @return whether the column holds lists
     */
    @Input
    public abstract Property<Boolean> getList();
}
