/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine.Builder;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * The input columns of a decision table, worked out from a {@link Builder}'s settings when its engine is built: what
 * the engine's inputs are, how each cell reads, and which column the index evaluates first. {@link #read} is the place
 * to start; it goes through the steps in order.
 *
 * @param types           each column's type (its elements' for a column of lists), in the order of
 *                        {@link PraeceptaEngine#inputs()}; the inputs that cells and expressions read are columns too
 * @param lists           the columns that hold lists
 * @param expressions     the table's parts in the full FEEL language, parsed
 * @param computed        the columns whose input expression computes their value, with that expression
 * @param cells           each column's cells as the index reads them, in rule order; a cell the index leaves to
 *                        evaluation ({@link DeferredCells}) is {@code -} here
 * @param variableCells   the cells that compare with another input, by rule (index in rule order) and column
 * @param allowedValues   the input values of each column that declares them, as written; an enum-bound column's
 *                        constants, if it declares none
 * @param names           the columns' names, in the order of {@link PraeceptaEngine#inputs()}
 * @param evaluationOrder the columns' names in the order the index evaluates them ({@link #orderByWildcards})
 */
record TableColumns(Map<String, ColumnType> types, Set<String> lists, TableExpressions expressions, Map<String, FeelExpression> computed,
        Map<String, List<UnaryTests>> cells, Map<Integer, Map<String, UnaryTests>> variableCells, Map<String, String> allowedValues,
        List<String> names, List<String> evaluationOrder) {

    /**
     * Works out the columns of the table {@code builder} describes: types the columns, parses the table's FEEL and its
     * cells, takes out the cells the index leaves to evaluation, adds the inputs those and the expressions read, and
     * orders the columns for the index.
     *
     * @throws InvalidDecisionTableException if a cell, an expression or a declared input value is invalid
     */
    static TableColumns read(Builder<?, ?> builder, List<Praeceptum> rules) {
        var types = columnTypes(builder, rules);
        var lists = listInputs(builder);
        var expressions = new TableExpressions(scope(builder, types), builder.types, builder.parsed);
        var computed = computedColumns(builder, types, expressions);
        var cells = parseCells(rules, types, builder.feelCells, lists, expressions);
        for (int r = 0; r < rules.size(); r++) {
            expressions.outputs(rules.get(r), r, builder.tableOutputTypes);
        }
        // cells that compare with another input are decided when evaluating; the index sees "any value"
        var variableCells = DeferredCells.takeVariableCells(rules, types, cells, builder.inputStrategy != null, lists);
        addExpressionInputs(expressions, types, cells, rules.size(), builder);
        var allowedValues = allowedValues(builder, types.keySet());
        var names = List.copyOf(types.keySet());
        return new TableColumns(types, lists, expressions, computed, cells, variableCells, allowedValues, names,
                orderByWildcards(names, cells, lists));
    }

    /** Builds the index of each column, in evaluation order, over the rules in bit order ({@link HitPolicies#bitToRule}). */
    List<ColumnIndex> indexes(Builder<?, ?> builder, int[] bitToRule) {
        int words = BitsetIndex.wordsFor(bitToRule.length);
        var enumValues = enumValuesByColumn(builder.bindings);
        return evaluationOrder.stream()
                .map(column -> {
                    var type = types.get(column);
                    var columnCells = new ColumnIndex.Cells(inBitOrder(cells.get(column), bitToRule), words, builder.maskForm);
                    var allowed = parseAllowedValues(column, type, allowedValues.get(column));
                    return lists.contains(column)
                            ? ColumnIndex.ofLists(column, type, columnCells, allowed, zoning(builder, column))
                            : new ColumnIndex(column, type, columnCells, allowed, zoning(builder, column), enumValues.get(column));
                })
                .toList();
    }

    // ---- types, lists and input values --------------------------------------------------------------

    /**
     * The type of each column. Bound columns carry their own, and rules may test no other column. With
     * an {@link InputStrategy}, the columns are the table's, those the rules test and those with input
     * values (STRING unless the table says otherwise), then {@code columnType(...)} overrides. A column of the
     * {@linkplain Builder#table table} whose input expression is an expression, not a name, is computed, so it needs no
     * binding.
     */
    private static Map<String, ColumnType> columnTypes(Builder<?, ?> builder, List<Praeceptum> rules) {
        var types = new LinkedHashMap<String, ColumnType>();
        if (builder.inputStrategy == null) {
            builder.bindings.forEach((column, binding) -> types.put(column, binding.type()));
            if (builder.tableTypes != null) {
                builder.tableTypes.forEach((column, type) -> {
                    if (TableExpressions.isExpression(column)) {
                        types.putIfAbsent(column, type);
                    }
                });
            }
            rejectUndeclaredColumns(rules, types.keySet());
            return types;
        }
        if (builder.tableTypes != null) {
            types.putAll(builder.tableTypes);
        }
        columnsUsedBy(rules).forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
        builder.allowedValues.keySet().forEach(column -> types.putIfAbsent(column, ColumnType.STRING));
        types.putAll(builder.columnTypes);
        return types;
    }

    /**
     * The inputs that hold lists: those bound with {@code listInput(...)} or, with an {@link InputStrategy},
     * those the table or {@code listColumnType(...)} declare.
     */
    private static Set<String> listInputs(Builder<?, ?> builder) {
        if (builder.inputStrategy != null) {
            return Set.copyOf(builder.listColumns);
        }
        var lists = new HashSet<String>();
        builder.bindings.forEach((column, binding) -> {
            if (binding.list()) {
                lists.add(column);
            }
        });
        return lists;
    }

    /**
     * The input value list of each column: the declared ones and, for an enum-bound column without
     * one, its constants. An enum is a closed set of values.
     */
    private static Map<String, String> allowedValues(Builder<?, ?> builder, Set<String> columns) {
        var allowedValues = new LinkedHashMap<>(builder.allowedValues);
        builder.bindings.forEach((column, binding) -> {
            if (binding.enumValues() != null) {
                allowedValues.putIfAbsent(column, quotedList(binding.enumValues()));
            }
        });
        for (var column : allowedValues.keySet()) {
            if (!columns.contains(column)) {
                throw new InvalidDecisionTableException("Input values declared for unknown column '%s'".formatted(column));
            }
        }
        return allowedValues;
    }

    /**
     * Whether a column's inputs have a time zone: as its getter says, or either way when read from text or computed by
     * its input expression.
     */
    private static Zoning zoning(Builder<?, ?> builder, String column) {
        var binding = builder.bindings.get(column);
        return builder.inputStrategy != null || binding == null ? Zoning.EITHER : binding.zoning();
    }

    /** For each enum-bound column, each constant's value by ordinal. */
    private static Map<String, String[]> enumValuesByColumn(Map<String, ? extends Builder.Binding<?>> bindings) {
        var byColumn = new HashMap<String, String[]>();
        bindings.forEach((column, binding) -> {
            if (binding.enumValues() != null) {
                byColumn.put(column, binding.enumValues().toArray(String[]::new));
            }
        });
        return byColumn;
    }

    private static List<String> columnsUsedBy(List<Praeceptum> rules) {
        var columns = new TreeSet<String>();
        rules.forEach(rule -> columns.addAll(rule.input().values().keySet()));
        return List.copyOf(columns);
    }

    /** A rule may name a column that has no input binding only to leave it blank (a wildcard). */
    private static void rejectUndeclaredColumns(List<Praeceptum> rules, Set<String> declared) {
        for (var rule : rules) {
            for (var cell : rule.input().values().entrySet()) {
                var column = cell.getKey();
                if (!declared.contains(column) && !isWildcard(cell.getValue())) {
                    throw new InvalidDecisionTableException("Rule %s tests column '%s', which has no declared input"
                            .formatted(rule.priority(), column));
                }
            }
        }
    }

    /**
     * Whether a cell matches any value. The column has no type to parse the cell with, and a cell that is
     * not even valid as text (a comparison, an interval) still tests the column, so it is no wildcard.
     */
    private static boolean isWildcard(String cell) {
        try {
            return CellParser.parse(cell, ColumnType.STRING) instanceof UnaryTests.Any;
        } catch (CellSyntaxException e) {
            return false;
        }
    }

    // ---- cells ---------------------------------------------------------------------------------------

    /**
     * Parsed cells per column, in rule order: strict FEEL for a DMN table (a list column's with
     * {@code list contains(?, ...)}), else Praecepta's syntax. A strict FEEL cell beyond the simple unary tests the index
     * evaluates is {@code -} here, and kept in {@code expressions}, which apply it once the index has matched its rule.
     */
    private static Map<String, List<UnaryTests>> parseCells(List<Praeceptum> rules, Map<String, ColumnType> types, boolean feel,
            Set<String> lists, TableExpressions expressions) {
        var cells = new LinkedHashMap<String, List<UnaryTests>>();
        types.forEach((column, type) -> {
            var columnCells = new ArrayList<UnaryTests>(rules.size());
            for (int r = 0; r < rules.size(); r++) {
                var rule = rules.get(r);
                try {
                    columnCells.add(parseCell(rule, column, type, feel, lists.contains(column)));
                } catch (CellSyntaxException e) {
                    if (!feel) {
                        throw new InvalidDecisionTableException("Rule %s, column '%s': %s".formatted(rule.priority(), column, e.getMessage()));
                    }
                    expressions.cell(rule, r, column, type, e);
                    columnCells.add(new UnaryTests.Any());
                }
            }
            cells.put(column, Collections.unmodifiableList(columnCells));
        });
        return cells;
    }

    /** A cell as the index reads it. */
    private static UnaryTests parseCell(Praeceptum rule, String column, ColumnType type, boolean feel, boolean list) {
        var cell = rule.input().values().get(column);
        if (!feel) {
            var parsed = CellParser.parse(cell, type);
            if (list && parsed.tests().stream().anyMatch(test -> test instanceof UnaryTests.NotEqual(UnaryTests.NullTest n))) {
                throw new InvalidDecisionTableException(("Rule %s, column '%s': '!= null' is not a test of a list column, "
                        + "where 'null' tests the list and other tests its elements; write not(null) for a list that is there")
                        .formatted(rule.priority(), column));
            }
            return parsed;
        }
        return list ? CellParser.parseFeelList(cell, type) : CellParser.parseFeel(cell, type);
    }

    /** A column's cells, from rule order to bit order. */
    private static List<UnaryTests> inBitOrder(List<UnaryTests> cellsByRule, int[] bitToRule) {
        return Arrays.stream(bitToRule).mapToObj(cellsByRule::get).toList();
    }

    /** The values as a FEEL list of string literals, e.g. {@code "PT", "ES"}. */
    static String quotedList(List<String> values) {
        return values.stream()
                .map(value -> FeelLiterals.literal(Objects.requireNonNull(value, "enum value")))
                .collect(Collectors.joining(", "));
    }

    /** A column's declared input values as unary tests of its type, or null if none are declared. */
    private static UnaryTests parseAllowedValues(String column, ColumnType type, String declared) {
        if (declared == null) {
            return null;
        }
        UnaryTests tests;
        try {
            tests = CellParser.parse(declared, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException("Input values of column '%s': %s".formatted(column, e.getMessage()));
        }
        if (type == ColumnType.STRING && tests.values().isEmpty()) {
            throw new InvalidDecisionTableException("Input values of string column '%s' must be a list of values".formatted(column));
        }
        return tests;
    }

    /**
     * Columns with fewer wildcards first: evaluation stops as soon as no rule is left, so selective
     * columns first make that happen sooner, and once a sparse column has confined the candidates to a
     * few words, later columns only intersect those. Columns that hold lists go after the others with as
     * many wildcards, since a list's mask is built whole, element by element. The result does not depend
     * on the order.
     */
    private static List<String> orderByWildcards(List<String> columns, Map<String, List<UnaryTests>> cells, Set<String> lists) {
        var wildcards = new LinkedHashMap<String, Long>();
        for (var column : columns) {
            wildcards.put(column, cells.get(column).stream().filter(UnaryTests.Any.class::isInstance).count());
        }
        Comparator<Map.Entry<String, Long>> byWildcards = Map.Entry.comparingByValue();
        return wildcards.entrySet().stream()
                .sorted(byWildcards.thenComparing(column -> lists.contains(column.getKey()))) // stable: ties keep declaration order
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---- the full FEEL language: its scope, the columns it computes and the names it reads -----------

    /**
     * The names in scope for the table's FEEL: its columns, the inputs bound to it, and the values a graph gives it.
     */
    private static Set<String> scope(Builder<?, ?> builder, Map<String, ColumnType> types) {
        var names = new LinkedHashSet<String>();
        // a column that is an expression is no name: its text would read as itself
        types.keySet().stream().filter(column -> !TableExpressions.isExpression(column)).forEach(names::add);
        names.addAll(builder.bindings.keySet());
        names.addAll(builder.expressionInputs.keySet());
        return names;
    }

    /**
     * Parses the columns whose input expressions compute their value, those that are expressions rather than names and
     * that no getter is bound to.
     *
     * @return their expressions, by column
     */
    private static Map<String, FeelExpression> computedColumns(Builder<?, ?> builder, Map<String, ColumnType> types,
            TableExpressions expressions) {
        for (var entry : types.entrySet()) {
            var column = entry.getKey();
            if (TableExpressions.isExpression(column) && !builder.bindings.containsKey(column)) {
                expressions.column(column, entry.getValue());
            }
        }
        return expressions.columns();
    }

    /**
     * Adds the names the table's FEEL reads that are not columns, nor values a graph gives, as inputs of the engine, which
     * the index sees as columns of {@code -} cells: bound by their own getters, or with map-based inputs read with the
     * type {@code columnType(...)} declares, else that of the column or output whose entry reads them first.
     *
     * @throws InvalidDecisionTableException if one has no input binding, with per-column bindings
     */
    private static void addExpressionInputs(TableExpressions expressions, Map<String, ColumnType> types,
            Map<String, List<UnaryTests>> cells, int ruleCount, Builder<?, ?> builder) {
        expressions.named().forEach((name, type) -> {
            if (types.containsKey(name) || builder.expressionInputs.containsKey(name)) {
                return;
            }
            if (builder.inputStrategy == null) {
                throw new InvalidDecisionTableException("The table's expressions read '%s', which has no input binding".formatted(name));
            }
            types.put(name, type);
            cells.put(name, Collections.nCopies(ruleCount, new UnaryTests.Any()));
        });
    }

    /**
     * Where each name the table's FEEL reads is: a column, by position in evaluation order, or a value a graph gives. A
     * column whose input expression is an expression is no name in the table, so its text, read as a name, is the value a
     * graph gives of that name: a requirement named {@code Pre-bureauRiskCategory} that a column of that input expression
     * reads (the DMN TCK's {@code 0004-lending}).
     *
     * @throws InvalidDecisionTableException if a name is neither a column nor a value a graph gives
     */
    static Map<String, Integer> nameReads(TableExpressions expressions, List<String> evaluationOrder, List<String> given,
            Map<String, FeelExpression> computed) {
        var reads = new HashMap<String, Integer>();
        for (var name : expressions.names()) {
            int column = computed.containsKey(name) ? -1 : evaluationOrder.indexOf(name);
            int value = given.indexOf(name);
            if (column < 0 && value < 0) {
                throw new InvalidDecisionTableException(
                        "The table's expressions read '%s', which is neither an input of the table nor a value given to it".formatted(name));
            }
            reads.put(name, column >= 0 ? column : -1 - value);
        }
        return Map.copyOf(reads);
    }

    /**
     * The type of each name the table's FEEL may read: a column's, a list of it for a column of lists, or a given value's.
     * A table without FEEL beyond the index types nothing, and loads none of FEEL's types.
     */
    Map<String, FeelType> nameTypes(Map<String, FeelType> given) {
        if (expressions.isEmpty()) {
            return Map.of();
        }
        var nameTypes = new HashMap<>(given);
        types.forEach((name, type) -> nameTypes.put(name, lists.contains(name) ? FeelType.listOf(type) : FeelType.of(type)));
        return nameTypes;
    }
}
