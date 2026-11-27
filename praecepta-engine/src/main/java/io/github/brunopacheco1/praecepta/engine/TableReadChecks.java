/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.PropertyColumn;
import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.Resolution;
import io.github.brunopacheco1.praecepta.engine.TableReads.ColumnRead;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The phase of a {@link GraphAnalysis} that follows typing: checks that every value a decision table reads fits the
 * column, or the input its cells compare with, that reads it. A value that does not fit is a
 * {@link Kind#TYPE_MISMATCH}. It also notes two ways a table reads a value that is not of its column's type: an input
 * its cells compare with whose value is a list, and a column that reads a property of a date, time or duration.
 */
final class TableReadChecks {

    private final GraphAnalysis graph;

    TableReadChecks(GraphAnalysis graph) {
        this.graph = graph;
    }

    /** Checks every decision table, in the model's order. */
    void check() {
        var decisions = graph.model.decisions();
        for (int d = 0; d < decisions.size(); d++) {
            if (!(decisions.get(d).logic() instanceof DecisionLogic.Table(var table, var type))) {
                continue;
            }
            var columns = table.inputs().stream().map(DecisionTable.InputColumn::name).collect(Collectors.toSet());
            for (var read : graph.tableReads[d].columns()) {
                var resolution = graph.reads.get(d).get(read.name());
                if (resolution != null && !graph.conflicting.contains(d + ":" + read.name())) {
                    checkRead(d, read, resolution, columns.contains(read.name()));
                }
            }
            for (var computed : graph.tableReads[d].computed().entrySet()) {
                checkComputedColumn(d, table, computed.getKey(), computed.getValue());
            }
        }
    }

    /**
     * Checks a value a table reads by name.
     *
     * @param column whether a column of the table reads it; else its cells compare with it
     */
    private void checkRead(int d, ColumnRead read, Resolution resolution, boolean column) {
        var source = resolution.error() == null ? resolution.type() : pathType(d, read, resolution, column);
        if (source == null) {
            return; // reported
        }
        boolean single = source instanceof FeelType.Simple(var type) && type == read.type() || source instanceof FeelType.Any;
        boolean list = source.equals(FeelType.listOf(read.type())) || source.equals(new FeelType.ListOf(FeelType.ANY));
        if (single || read.list() && list) {
            return;
        }
        if (list && !column) {
            graph.listedNamedInputs.get(d).add(read.name()); // a cell names a list: the value is in it, as with an input data of lists
            return;
        }
        reportMismatch(d, read, source, column ? "input" : "cells' comparisons with");
    }

    /**
     * The type of a column's path that its requirement's type does not have: a property of a date, time or duration,
     * which the column reads as FEEL does ({@link #propertyType}); else null, the path reported as a type mismatch.
     */
    private FeelType pathType(int d, ColumnRead read, Resolution resolution, boolean column) {
        var property = resolution.unsupported() && column ? propertyType(d, read, resolution) : null;
        if (property == null) {
            graph.report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', but %s".formatted(name(d), read.name(), resolution.error()));
        }
        return property;
    }

    /**
     * The type of a column that reads a property of a date, time or duration ({@code Birth.year}, DMN 1.5, Table 65),
     * which the table reads as FEEL does, computing the column from its requirement's value; null if FEEL has no such
     * property there.
     */
    private FeelType propertyType(int d, ColumnRead read, Resolution resolution) {
        var root = resolution.root(read.name());
        var reasons = new ArrayList<String>();
        var expression = FeelExpression.parse(read.name(), FeelExpression.scope(List.of(root), Map.of()));
        var type = expression.type(name -> name.equals(root) ? graph.types[resolution.slot()] : null, reasons::add);
        if (type instanceof FeelType.Null) {
            return null;
        }
        graph.propertyColumns.get(d).put(read.name(), new PropertyColumn(expression, root, resolution.slot()));
        return type;
    }

    /**
     * Checks a column whose input expression is an expression ({@code Age * 12}): its value, as FEEL types it from the
     * types of what it reads, fits the column, a value of its type (or of a list of it) or of a type only its values tell.
     */
    private void checkComputedColumn(int d, DecisionTable table, String column, FeelExpression expression) {
        var resolved = graph.reads.get(d);
        var type = expression.type(name -> resolved.containsKey(name) ? resolved.get(name).type() : null);
        var read = new ColumnRead(column, table.inputs().stream().filter(input -> input.name().equals(column)).findFirst()
                .orElseThrow().type(), table.listInputs().contains(column));
        boolean single = type instanceof FeelType.Simple(var simple) && simple == read.type();
        boolean list = type instanceof FeelType.ListOf(var element) && (element.equals(FeelType.of(read.type())) || element instanceof FeelType.Any);
        boolean fits = type instanceof FeelType.Any || type instanceof FeelType.Null || single || read.list() && list;
        if (!fits) {
            reportMismatch(d, read, type, "input");
        }
    }

    private void reportMismatch(int d, ColumnRead read, FeelType source, String what) {
        var expected = read.list() ? FeelType.listOf(read.type()) : FeelType.of(read.type());
        graph.report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', a %s, into its %s '%s', a %s".formatted(name(d), read.name(),
                source.feelName(), what, read.name(), expected.feelName()));
    }

    private String name(int d) {
        return graph.model.decisions().get(d).name();
    }
}
