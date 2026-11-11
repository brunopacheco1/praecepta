/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * A complete decision table: columns with their types and value lists, hit policy and rules. This is
 * what a DMN decision table holds; {@code DmnReader} and {@code DmnWriter} convert between the two,
 * and {@code PraeceptaEngine.Builder#table} configures an engine from it.
 *
 * @param name        the decision's name
 * @param hitPolicy   the hit policy
 * @param aggregation COLLECT aggregation, or null
 * @param inputs      input columns, in table order
 * @param outputs     output columns, in table order
 * @param rules       rules in rule order; input cells are unary tests of their column's type, outputs
 *                    are plain values as text (a string, or a number, boolean, date or time such as
 *                    {@code 2025-01-31}), null when empty
 * @param feelCells   whether the input cells are strict FEEL, as a DMN file holds them (strings quoted,
 *                    {@code date(...)} calls), where a name compares with another input, as in
 *                    {@code > dateE}; otherwise Praecepta's syntax, where an unquoted word is a string
 */
public record DecisionTable(
        String name,
        HitPolicy hitPolicy,
        AggregationOperator aggregation,
        List<InputColumn> inputs,
        List<OutputColumn> outputs,
        List<Praeceptum> rules,
        boolean feelCells) {

    public DecisionTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rules = List.copyOf(rules);
    }

    /** A table whose cells are in Praecepta's syntax. */
    public DecisionTable(String name, HitPolicy hitPolicy, AggregationOperator aggregation, List<InputColumn> inputs,
                         List<OutputColumn> outputs, List<Praeceptum> rules) {
        this(name, hitPolicy, aggregation, inputs, outputs, rules, false);
    }

    /**
     * The inputs the cells compare with that are not columns of the table, each with the type of the
     * column that compares with it, in the order first named. Empty unless {@link #feelCells}.
     *
     * @throws IllegalArgumentException if a cell is invalid, or columns of different types compare with one input
     */
    public Map<String, ColumnType> namedInputs() {
        var named = new LinkedHashMap<String, ColumnType>();
        if (!feelCells) {
            return named;
        }
        var columns = inputs.stream().map(InputColumn::name).collect(Collectors.toSet());
        for (var rule : rules) {
            for (var input : inputs) {
                var cell = CellParser.parseFeel(rule.input().values().get(input.name()), input.type());
                for (var name : UnaryTests.names(cell)) {
                    if (columns.contains(name)) {
                        continue;
                    }
                    var previous = named.putIfAbsent(name, input.type());
                    if (previous != null && previous != input.type()) {
                        throw new IllegalArgumentException("'%s' is compared with both a %s and a %s column"
                                .formatted(name, previous.feelName(), input.type().feelName()));
                    }
                }
            }
        }
        return named;
    }

    /**
     * @param name          the column name, which is also the input variable it reads
     * @param type          the column type
     * @param allowedValues DMN input value list as unary tests (e.g. {@code "PT","ES"} or {@code [0..120]}), or null
     */
    public record InputColumn(String name, ColumnType type, String allowedValues) {
        public InputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        public InputColumn(String name, ColumnType type) {
            this(name, type, null);
        }
    }

    /**
     * @param name   the column name
     * @param type   the type of its values
     * @param values DMN output value list, highest priority first (used by PRIORITY and OUTPUT_ORDER); may be empty
     */
    public record OutputColumn(String name, ColumnType type, List<String> values) {
        public OutputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            values = List.copyOf(values);
        }

        public OutputColumn(String name, ColumnType type) {
            this(name, type, List.of());
        }
    }
}
