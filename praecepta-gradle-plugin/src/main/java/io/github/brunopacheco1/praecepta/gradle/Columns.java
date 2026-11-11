/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Nested;

/**
 * The input or output columns of an Excel or CSV table, in the order they are declared.
 *
 * <pre>{@code
 * inputs {
 *     column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
 *     column("age") { type = "NUMBER" }
 *     column("nationalities") { list = true }
 * }
 * }</pre>
 */
public abstract class Columns {

    private final List<ColumnSpec> columns = new ArrayList<>();

    /** Creates an empty list of columns; Gradle does for each table. */
    public Columns() {
        // columns are added by column(...)
    }

    /**
     * Creates the columns.
     *
     * @return Gradle's object factory
     */
    @Inject
    protected abstract ObjectFactory getObjects();

    /**
     * The columns, in the order they were declared.
     *
     * @return the columns
     */
    @Nested
    public List<ColumnSpec> getColumns() {
        return List.copyOf(columns);
    }

    /**
     * Declares a column of type {@code STRING} with no value list.
     *
     * @param name the column's header in the sheet
     * @return the column, to configure further
     */
    public ColumnSpec column(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidUserDataException("Every column needs a name: column(\"country\")");
        }
        var column = getObjects().newInstance(ColumnSpec.class, name);
        column.getType().convention("STRING");
        column.getList().convention(false);
        columns.add(column);
        return column;
    }

    /**
     * Declares a column and configures it: {@code column("age") { type = "NUMBER" }}.
     *
     * @param name   the column's header in the sheet
     * @param action what to set on it
     * @return the column
     */
    public ColumnSpec column(String name, Action<? super ColumnSpec> action) {
        var column = column(name);
        action.execute(column);
        return column;
    }
}
