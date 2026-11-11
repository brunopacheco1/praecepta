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
import java.util.Set;
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
 * @param listInputs  the inputs that hold a list of values rather than one value: columns of the table,
 *                    whose values are lists of their type, and inputs the cells compare with (see
 *                    {@link #namedInputs}), whose values are lists of the type of the column that names
 *                    them. A list column's cell holds when some element passes one of its tests
 *                    ({@code "PT","ES"}: the list holds {@code "PT"} or {@code "ES"}), and its
 *                    {@code not(...)} when no element does; a missing list passes only {@code -} and
 *                    {@code null}, and an empty one fails every test. In strict FEEL such a cell
 *                    is written with {@code list contains(?, "PT")} (see {@code CellParser#parseFeelList}).
 *                    A cell that names a list input, as {@code Flu Symptoms} in a string column, holds
 *                    when the column's value is in that list, as in DMN.
 */
public record DecisionTable(
        String name,
        HitPolicy hitPolicy,
        AggregationOperator aggregation,
        List<InputColumn> inputs,
        List<OutputColumn> outputs,
        List<Praeceptum> rules,
        boolean feelCells,
        Set<String> listInputs) {

    public DecisionTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rules = List.copyOf(rules);
        listInputs = Set.copyOf(listInputs);
    }

    /** A table whose inputs each hold one value. */
    public DecisionTable(String name, HitPolicy hitPolicy, AggregationOperator aggregation, List<InputColumn> inputs,
                         List<OutputColumn> outputs, List<Praeceptum> rules, boolean feelCells) {
        this(name, hitPolicy, aggregation, inputs, outputs, rules, feelCells, Set.of());
    }

    /** A table whose cells are in Praecepta's syntax, and whose inputs each hold one value. */
    public DecisionTable(String name, HitPolicy hitPolicy, AggregationOperator aggregation, List<InputColumn> inputs,
                         List<OutputColumn> outputs, List<Praeceptum> rules) {
        this(name, hitPolicy, aggregation, inputs, outputs, rules, false);
    }

    /**
     * The rule's cell in a column, parsed as the table holds it: strict FEEL or Praecepta's syntax, and
     * for a list column (strict FEEL) with {@code CellParser#parseFeelList}.
     *
     * @throws io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException if the cell is invalid
     */
    public UnaryTests cell(Praeceptum rule, InputColumn column) {
        var text = rule.input().values().get(column.name());
        if (!feelCells) {
            return CellParser.parse(text, column.type());
        }
        return listInputs.contains(column.name()) ? CellParser.parseFeelList(text, column.type()) : CellParser.parseFeel(text, column.type());
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
                UnaryTests.names(cell(rule, input)).stream()
                        .filter(name -> !columns.contains(name))
                        .forEach(name -> nameInput(named, name, input.type()));
            }
        }
        return named;
    }

    private static void nameInput(Map<String, ColumnType> named, String name, ColumnType type) {
        var previous = named.putIfAbsent(name, type);
        if (previous != null && previous != type) {
            throw new IllegalArgumentException("'%s' is compared with both a %s and a %s column"
                    .formatted(name, previous.feelName(), type.feelName()));
        }
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
