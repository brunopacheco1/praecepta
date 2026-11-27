/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * What a decision table of a graph reads, found by parsing every cell and expression of the table once ({@link #of}).
 *
 * @param columns     its columns that name what they read, then the inputs its simple cells compare with, each read as a
 *                    value of its column's type
 * @param computed    its columns whose input expressions are expressions ({@code Age * 12}), parsed, by their text
 * @param expressions the names its FEEL (cells beyond the simple ones, input expressions, output entries) reads beside
 *                    its columns, whose values it reads as they are
 * @param parsed      that FEEL, parsed, which the table's engine takes rather than parse it again
 */
record TableReads(List<ColumnRead> columns, Map<String, FeelExpression> computed, Set<String> expressions, TableExpressions.Parsed parsed) {

    /** What logic that is no decision table reads as a table: nothing. */
    static final TableReads NONE = new TableReads(List.of(), Map.of(), Set.of(), TableExpressions.Parsed.NONE);

    /** How a decision table reads a name: as a column of {@code type}, or of lists of it, or as an input its cells compare with. */
    record ColumnRead(String name, ColumnType type, boolean list) {
    }

    /** Every name read, columns first. */
    Set<String> names() {
        var names = new LinkedHashSet<String>();
        columns.forEach(read -> names.add(read.name()));
        names.addAll(expressions);
        return names;
    }

    /**
     * The names in scope for a decision table's FEEL: its decision's requirements (DMN 1.5, 7.3.4) and its own columns,
     * with the model's types.
     */
    static FeelExpression.Scope scope(Set<String> requirements, DecisionTable table, Map<String, FeelType> types) {
        var names = new LinkedHashSet<>(requirements);
        // a column that is an expression is no name: its text would read as itself
        table.inputs().stream().filter(input -> !TableExpressions.isExpression(input.name())).forEach(input -> names.add(input.name()));
        return FeelExpression.scope(names, types);
    }

    /**
     * What a table reads: its columns that name what they read, and the inputs its simple cells compare with, each of the
     * type of the column that compares with it; its columns that are expressions; and the names its FEEL reads beside its
     * columns, parsed with {@code scope} in scope.
     *
     * @param decision the table's decision, which messages name
     * @throws InvalidDecisionTableException if a cell or an expression is invalid, or columns of different types
     *                                       compare with one input
     */
    static TableReads of(String decision, DecisionTable table, FeelExpression.Scope scope) {
        var columns = new ArrayList<ColumnRead>();
        var computed = new LinkedHashMap<String, FeelExpression>();
        var expressions = new LinkedHashSet<String>();
        var columnNames = new HashSet<String>();
        var parsed = new TableExpressions.Parsed(scope, new HashMap<>(), new HashMap<>());
        for (var input : table.inputs()) {
            if (TableExpressions.isExpression(input.name())) {
                // no name in the table: its text, read as a name, is what the decision requires of that name
                var expression = parse(decision, input.name(), parsed);
                computed.put(input.name(), expression);
                expressions.addAll(expression.names());
            } else {
                columnNames.add(input.name());
                columns.add(new ColumnRead(input.name(), input.type(), table.listInputs().contains(input.name())));
            }
        }
        var named = new LinkedHashMap<String, ColumnType>();
        for (var rule : table.rules()) {
            for (var input : table.inputs()) {
                cellReads(decision, table, rule, input, parsed, named, expressions);
            }
            rule.output().expressions().values().forEach(text -> expressions.addAll(parse(decision, text, parsed).names()));
        }
        named.keySet().removeAll(columnNames);
        named.forEach((name, type) -> columns.add(new ColumnRead(name, type, table.listInputs().contains(name))));
        expressions.removeAll(columnNames);
        expressions.removeAll(named.keySet());
        return new TableReads(List.copyOf(columns), Collections.unmodifiableMap(computed), Collections.unmodifiableSet(expressions),
                new TableExpressions.Parsed(scope, Map.copyOf(parsed.cells()), Map.copyOf(parsed.expressions())));
    }

    /**
     * Adds what a cell reads: to {@code named} the inputs a simple cell compares with, of its column's type; to
     * {@code expressions} the names a cell in the full FEEL language reads, which it parses into {@code parsed}.
     */
    private static void cellReads(String decision, DecisionTable table, Praeceptum rule, DecisionTable.InputColumn input,
            TableExpressions.Parsed parsed, Map<String, ColumnType> named, Set<String> expressions) {
        UnaryTests cell;
        try {
            cell = table.cell(rule, input);
        } catch (CellSyntaxException rejected) {
            if (!table.feelCells()) {
                throw invalid(decision, rejected);
            }
            expressions.addAll(parseCell(decision, rule.input().values().get(input.name()), rejected, parsed).names());
            return;
        }
        for (var name : UnaryTests.names(cell)) {
            if (table.inputs().stream().anyMatch(column -> column.name().equals(name))) {
                continue;
            }
            var previous = named.putIfAbsent(name, input.type());
            if (previous != null && previous != input.type()) {
                throw new InvalidDecisionTableException("Decision '%s': '%s' is compared with both a %s and a %s column"
                        .formatted(decision, name, previous.feelName(), input.type().feelName()));
            }
        }
    }

    /** A cell of a table beyond the simple unary tests, parsed once whatever the rules that hold it, into {@code parsed}. */
    private static FeelUnaryTests parseCell(String decision, String text, CellSyntaxException rejected, TableExpressions.Parsed parsed) {
        var tests = parsed.cells().get(text);
        if (tests == null) {
            try {
                tests = FeelUnaryTests.parseCell(text, parsed.scope(), rejected);
            } catch (CellSyntaxException e) {
                throw invalid(decision, e);
            }
            parsed.cells().put(text, tests);
        }
        return tests;
    }

    /** An expression of a table, parsed once whatever the rules that hold it, into {@code parsed}. */
    private static FeelExpression parse(String decision, String text, TableExpressions.Parsed parsed) {
        var expression = parsed.expressions().get(text);
        if (expression == null) {
            try {
                expression = FeelExpression.parse(text, parsed.scope());
            } catch (ExpressionSyntaxException e) {
                throw invalid(decision, e);
            }
            parsed.expressions().put(text, expression);
        }
        return expression;
    }

    private static InvalidDecisionTableException invalid(String decision, IllegalArgumentException e) {
        return new InvalidDecisionTableException("Decision '%s': %s".formatted(decision, e.getMessage()));
    }
}
