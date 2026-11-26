/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;

/**
 * The parts of a decision table in the full FEEL language (DMN 1.5, chapter 10) that the bitset index does not
 * evaluate, parsed once when its engine is built: input entries beyond the simple unary tests {@link CellParser} reads,
 * input expressions that compute a column's value from other inputs, and output entries that compute their value. Each
 * is parsed with the names in scope that the engine reads (its columns, the inputs bound to it, and the values a
 * {@link DecisionGraph} gives it), and the names each reads that are not columns become inputs of the engine, as those
 * a simple cell compares with are ({@code > limit}).
 */
final class TableExpressions {

    private final Collection<String> names;
    /** The types the model defines (its item definitions), which {@code instance of} and parameters may name. */
    private final Map<String, FeelType> types;
    /** The names in scope, indexed when the first expression is parsed: a table of simple cells parses none. */
    private FeelExpression.Scope scope;
    /** What was parsed already, by text, in the same scope ({@link Parsed}); nothing for a table built alone. */
    private final Parsed parsed;
    /** By rule (index in rule order), by column: its input entry in the full FEEL language. */
    private final Map<Integer, Map<String, FeelUnaryTests>> cells = new HashMap<>();
    /** By column: the input expression a column's value is computed with. */
    private final Map<String, FeelExpression> columns = new LinkedHashMap<>();
    /** By rule (index in rule order), by output: the output entry that computes its value. */
    private final Map<Integer, Map<String, FeelExpression>> outputs = new HashMap<>();
    /**
     * The names the expressions read, columns among them, each with the type of the column or output whose entry reads it
     * first: the type of an input of the engine read from text, unless the engine is given its type.
     */
    private final Map<String, ColumnType> named = new LinkedHashMap<>();

    /**
     * The table's FEEL as {@link GraphAnalysis} parsed it, by text, so that a {@link DecisionGraph} parses each of its
     * tables' entries once: its input entries beyond the simple unary tests, and its input expressions and output
     * entries, parsed in {@link #scope}, which the engine then parses in, too.
     */
    record Parsed(FeelExpression.Scope scope, Map<String, FeelUnaryTests> cells, Map<String, FeelExpression> expressions) {

        static final Parsed NONE = new Parsed(null, Map.of(), Map.of());
    }

    /**
     * @param names  the names in scope: those the engine reads
     * @param types  the types the model defines, by name
     * @param parsed what was parsed already, and the scope it was parsed in, which then replaces {@code names}
     */
    TableExpressions(Collection<String> names, Map<String, FeelType> types, Parsed parsed) {
        this.names = names;
        this.types = types;
        this.parsed = parsed;
        this.scope = parsed.scope();
    }

    private FeelExpression.Scope scope() {
        if (scope == null) {
            scope = FeelExpression.scope(new LinkedHashSet<>(names), types);
        }
        return scope;
    }

    /** Whether a column's name is no FEEL name but an expression, whose value the engine computes from other inputs. */
    static boolean isExpression(String column) {
        return !CellParser.isName(column);
    }

    // ---- parsing ---------------------------------------------------------------------------------

    /**
     * A strict FEEL cell that {@link CellParser} rejected, read as FEEL unary tests, which the engine applies to the rule
     * once the index has matched it; recorded, and its names noted as read by a column of {@code type}.
     *
     * @param rule     the rule's index in rule order
     * @param rejected why the subset rejected it, which stands if it is made only of what the subset reads
     * @throws InvalidDecisionTableException if it is not FEEL, or uses what Praecepta does not evaluate yet
     */
    void cell(Praeceptum rule, int ruleIndex, String column, ColumnType type, CellSyntaxException rejected) {
        var where = "Rule %s, column '%s'".formatted(rule.priority(), column);
        var text = rule.input().values().get(column);
        var tests = text == null ? null : parsed.cells().get(text);
        if (tests == null) {
            try {
                tests = FeelUnaryTests.parseCell(text, scope(), rejected);
            } catch (CellSyntaxException e) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
        }
        rejectUnsupported(where, tests.unsupported());
        cells.computeIfAbsent(ruleIndex, any -> new LinkedHashMap<>()).put(column, tests);
        note(tests.names(), type);
    }

    /**
     * A column whose input expression is an expression, not a name: its value is computed from the inputs it reads.
     *
     * @throws InvalidDecisionTableException if it is not FEEL, or uses what Praecepta does not evaluate yet
     */
    void column(String column, ColumnType type) {
        var where = "Input expression '%s'".formatted(column);
        var expression = parse(column, where);
        columns.put(column, expression);
        note(expression.names(), type);
    }

    /**
     * The output entries of a rule that compute their value ({@code PraeceptaOutput#expressions}), each of an output of
     * the type given (a string for one the engine does not know).
     *
     * @throws InvalidDecisionTableException if one is not FEEL, or uses what Praecepta does not evaluate yet
     */
    void outputs(Praeceptum rule, int ruleIndex, Map<String, ColumnType> outputTypes) {
        rule.output().expressions().forEach((output, text) -> {
            var expression = parse(text, "Rule %s, output '%s'".formatted(rule.priority(), output));
            outputs.computeIfAbsent(ruleIndex, any -> new LinkedHashMap<>()).put(output, expression);
            note(expression.names(), outputTypes.getOrDefault(output, ColumnType.STRING));
        });
    }

    private FeelExpression parse(String text, String where) {
        var expression = parsed.expressions().get(text);
        if (expression == null) {
            try {
                expression = FeelExpression.parse(text, scope());
            } catch (ExpressionSyntaxException e) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
        }
        rejectUnsupported(where, expression.unsupported());
        return expression;
    }

    /** Rejects what an expression uses that Praecepta does not evaluate yet, naming the release that plans it. */
    static void rejectUnsupported(String where, List<FeelExpression.Unsupported> unsupported) {
        if (!unsupported.isEmpty()) {
            var message = "%s uses %s".formatted(where, FeelExpression.Unsupported.describe(unsupported));
            var first = unsupported.getFirst();
            throw new UnsupportedFeatureException(message, first.release(), first.issue());
        }
    }

    private void note(List<String> names, ColumnType type) {
        names.forEach(name -> named.putIfAbsent(name, type));
    }

    // ---- what was parsed -------------------------------------------------------------------------

    /** Whether the table has any part in the full FEEL language. */
    boolean isEmpty() {
        return cells.isEmpty() && columns.isEmpty() && outputs.isEmpty();
    }

    /** The rule's cells in the full FEEL language, by column; empty if it has none. */
    Map<String, FeelUnaryTests> cells(int ruleIndex) {
        return cells.getOrDefault(ruleIndex, Map.of());
    }

    /** The columns computed from other inputs, with their input expressions. */
    Map<String, FeelExpression> columns() {
        return columns;
    }

    /** The rule's output entries that compute their value, by output; empty if it has none. */
    Map<String, FeelExpression> outputs(int ruleIndex) {
        return outputs.getOrDefault(ruleIndex, Map.of());
    }

    /** The names the expressions read, columns among them, each with the type of what reads it first, in the order first read. */
    Map<String, ColumnType> named() {
        return named;
    }

    /** Every name the expressions read, in the order first read. */
    Set<String> names() {
        return named.keySet();
    }
}
