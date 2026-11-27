/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * The cells the index leaves to evaluation: those that compare with another input ({@code > limit}) and those in the
 * full FEEL language, beyond the simple unary tests ({@code ? > 5 and ? < 10}). The index sees such a cell as {@code -},
 * and the engine applies it to the input once the index has matched its rule ({@code PraeceptaEngine#keepVariableMatches}).
 *
 * <p>This class prepares them when the engine is built: it takes the cells that name inputs out of the table
 * ({@link #takeVariableCells}), lays both kinds out by rule bit, and tells which rules have any ({@link #variableRules})
 * and which rules the static analysis leaves out ({@link #computedColumnRules}).
 */
final class DeferredCells {

    private DeferredCells() {
    }

    /**
     * A cell that compares with another input, as in {@code > dateE}: the column it tests and, for each
     * input it names, that input's column; positions are in the evaluation order.
     */
    record VariableCell(int column, UnaryTests cell, Map<String, Integer> names) {
    }

    /** A cell in the full FEEL language ({@link FeelUnaryTests}), applied to the value of its column, by position in evaluation order. */
    record FeelCell(int column, FeelUnaryTests tests) {
    }

    /**
     * Takes the cells that name inputs out of {@code cells}, leaving "any value" for the index, and
     * returns them by rule (index in rule order) and column. Each named input becomes an input of the
     * engine: bound by its own getter, or with map-based inputs, read with the type of the column that
     * names it.
     *
     * @param types column types; named inputs that are not columns yet are added, so callers must read
     *              the inputs from it only after this call
     * @param cells cells by column; changed in place, and given an all-"any" column for each added input
     */
    static Map<Integer, Map<String, UnaryTests>> takeVariableCells(List<Praeceptum> rules, Map<String, ColumnType> types,
            Map<String, List<UnaryTests>> cells, boolean mapInputs, Set<String> lists) {
        var byRule = new LinkedHashMap<Integer, Map<String, UnaryTests>>();
        var named = new LinkedHashMap<String, ColumnType>();
        for (var column : List.copyOf(cells.keySet())) {
            var columnCells = new ArrayList<>(cells.get(column));
            for (int r = 0; r < columnCells.size(); r++) {
                var cell = columnCells.get(r);
                if (!cell.namesInputs()) {
                    continue;
                }
                nameInputs(rules.get(r), column, cell, types, named, mapInputs);
                rejectComparisonsWithLists(rules.get(r), column, cell, lists);
                byRule.computeIfAbsent(r, any -> new LinkedHashMap<>()).put(column, cell);
                columnCells.set(r, new UnaryTests.Any());
            }
            cells.put(column, List.copyOf(columnCells));
        }
        named.forEach((name, type) -> {
            if (types.putIfAbsent(name, type) == null) {
                cells.put(name, Collections.nCopies(rules.size(), new UnaryTests.Any()));
            }
        });
        return byRule;
    }

    /**
     * Adds to {@code named} the inputs {@code cell} compares with, checked: each has an input binding
     * (or inputs are maps) and the type of {@code column}.
     */
    private static void nameInputs(Praeceptum rule, String column, UnaryTests cell, Map<String, ColumnType> types,
            Map<String, ColumnType> named, boolean mapInputs) {
        var type = types.get(column);
        for (var name : UnaryTests.names(cell)) {
            var known = types.containsKey(name) ? types.get(name) : named.get(name);
            if (known == null && !mapInputs) {
                throw new InvalidDecisionTableException("Rule %s, column '%s': compares with '%s', which has no input binding"
                        .formatted(rule.priority(), column, name));
            }
            if (known != null && known != type) {
                throw new InvalidDecisionTableException("Rule %s, column '%s': compares a %s with '%s', a %s"
                        .formatted(rule.priority(), column, type.feelName(), name, known.feelName()));
            }
            named.putIfAbsent(name, type);
        }
    }

    /**
     * A cell may name an input that holds a list only as a whole test, {@code Flu Symptoms} or
     * {@code not(Flu Symptoms)}, which asks whether the value is in the list: FEEL compares no value with a
     * list, so {@code > limits}, {@code [low..limits]}, {@code = limits} or {@code != limits} would never hold
     * (an equality of a value and a list is null, DMN 1.5, Tables 52 and 53).
     */
    private static void rejectComparisonsWithLists(Praeceptum rule, String column, UnaryTests cell, Set<String> lists) {
        for (var test : cell.tests()) {
            if (test instanceof UnaryTests.Equals equals && !equals.explicit()) {
                continue; // a bare name: list contains(Flu Symptoms, ?), DMN 1.5, Table 55
            }
            for (var name : UnaryTests.names(test)) {
                if (lists.contains(name)) {
                    throw new InvalidDecisionTableException(("Rule %s, column '%s': compares with '%s', which holds lists; "
                            + "a cell can only ask whether the value is in it, as '%s' does").formatted(rule.priority(), column, name, name));
                }
            }
        }
    }

    /** The variable cells by bit, with columns as positions in the evaluation order; null for rules without any. */
    static VariableCell[][] variableCellsByBit(Map<Integer, Map<String, UnaryTests>> byRule, int[] bitToRule,
            List<String> evaluationOrder) {
        var byBit = new VariableCell[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var ruleCells = byRule.get(bitToRule[bit]);
            if (ruleCells == null) {
                continue;
            }
            byBit[bit] = ruleCells.entrySet().stream()
                    .map(cell -> {
                        var names = new LinkedHashMap<String, Integer>();
                        UnaryTests.names(cell.getValue()).forEach(name -> names.put(name, evaluationOrder.indexOf(name)));
                        return new VariableCell(evaluationOrder.indexOf(cell.getKey()), cell.getValue(), Map.copyOf(names));
                    })
                    .toArray(VariableCell[]::new);
        }
        return byBit;
    }

    /** The FEEL cells by bit, with columns as positions in the evaluation order; null for rules without any. */
    static FeelCell[][] feelCellsByBit(TableExpressions expressions, int[] bitToRule, List<String> evaluationOrder) {
        var byBit = new FeelCell[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var ruleCells = expressions.cells(bitToRule[bit]);
            if (!ruleCells.isEmpty()) {
                byBit[bit] = ruleCells.entrySet().stream()
                        .map(cell -> new FeelCell(evaluationOrder.indexOf(cell.getKey()), cell.getValue()))
                        .toArray(FeelCell[]::new);
            }
        }
        return byBit;
    }

    /** The rules with cells that compare with another input, or in the full FEEL language, as a mask. */
    static long[] variableRules(VariableCell[][] variableCellsByBit, FeelCell[][] feelCellsByBit, int words) {
        var mask = new long[words];
        for (int bit = 0; bit < variableCellsByBit.length; bit++) {
            if (variableCellsByBit[bit] != null || feelCellsByBit[bit] != null) {
                BitsetIndex.set(mask, bit);
            }
        }
        return mask;
    }

    /**
     * The rules the analysis leaves out ({@link TableAnalysis#analyse}): those with cells that compare with another input
     * or are in the full FEEL language ({@code variableRules}), and those that test a column computed from other inputs
     * ({@code Age * 12}), which the index sees as a column of its own, unrelated to the inputs it is computed from, so
     * that {@code < 18} on {@code Age} and {@code >= 216} on {@code Age * 12} would seem to overlap.
     *
     * @param columns the columns that may be computed: those whose input expressions are expressions, bound to a getter
     *                or not, in a strict FEEL table, else those the engine computes
     * @param cells   the cells by column, in rule order, those beyond the index {@code -}
     */
    static long[] computedColumnRules(Set<String> columns, Map<String, List<UnaryTests>> cells, int[] bitToRule,
            long[] variableRules) {
        var computed = columns.stream().filter(TableExpressions::isExpression).toList();
        if (computed.isEmpty()) {
            return variableRules;
        }
        var mask = variableRules.clone();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            int rule = bitToRule[bit];
            if (computed.stream().anyMatch(column -> !(cells.get(column).get(rule) instanceof UnaryTests.Any))) {
                BitsetIndex.set(mask, bit);
            }
        }
        return mask;
    }
}
