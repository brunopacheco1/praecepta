/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A column whose input expression computes its value from other inputs ({@code Age * 12}): its value is the
 * expression's, which must be of the column's type (a value of another type is rejected, as a getter's is); for a
 * column of lists, a single value is a list of one (DMN 1.5, 10.3.2.9.4, "to singleton list").
 *
 * @param type the column's type, or a list of it for a column of lists
 */
record ComputedColumn(String name, FeelExpression expression, FeelType type) {

    /** The column's value, given the expression's. */
    Object value(Object computed) {
        var value = computed == null || computed instanceof Collection<?> || !(type instanceof FeelType.ListOf) ? computed : List.of(computed);
        return GraphValues.normalise(value, type, name);
    }

    /** The computed columns by position in evaluation order, null where a column is read; empty if none is computed. */
    static ComputedColumn[] byPosition(Map<String, FeelExpression> computed, List<String> evaluationOrder,
            Map<String, ColumnType> types, Set<String> lists) {
        if (computed.isEmpty()) {
            return new ComputedColumn[0];
        }
        var byColumn = new ComputedColumn[evaluationOrder.size()];
        computed.forEach((column, expression) -> {
            var type = lists.contains(column) ? FeelType.listOf(types.get(column)) : FeelType.of(types.get(column));
            byColumn[evaluationOrder.indexOf(column)] = new ComputedColumn(column, expression, type);
        });
        return byColumn;
    }

    /**
     * An input being evaluated by a table that computes columns, with the values of those it has computed, so that each
     * is computed once per evaluation however often it is read: by the index, by FEEL cells and by output entries. A
     * class, not a record: it holds an array and is never compared.
     */
    static final class Computing<T> {
        /** Marks a computed column's value as not computed yet in {@link #values}. */
        static final Object NOT_COMPUTED = new Object();

        final T input;
        /** By column (evaluation order): a computed column's value, or {@link #NOT_COMPUTED}. */
        final Object[] values;

        Computing(T input, int columns) {
            this.input = input;
            this.values = new Object[columns];
            Arrays.fill(values, NOT_COMPUTED);
        }
    }
}
