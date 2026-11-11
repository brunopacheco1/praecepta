/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Which time and date-time columns generated code gives a Java type with a time zone ({@code OffsetTime},
 * {@code ZonedDateTime}) instead of one without ({@code LocalTime}, {@code LocalDateTime}). A record
 * component has one Java type, while FEEL lets a column hold values of both kinds, so the table decides:
 * <ul>
 * <li>an input takes values with a zone when the literals written for it (its cells and input value
 * list) all have one and there is at least one; for an input that holds lists, its elements do. Inputs that cells compare ({@code > other}) take the
 * same kind, so that they are compared as written (two values with a zone by instant, two without by local time),
 * and the inputs linked by such cells are decided together;</li>
 * <li>an output has values with a zone when its literal values all have one and so do the inputs its
 * rules name, and there is at least one of them.</li>
 * </ul>
 * A column, or a group of linked inputs, whose literals mix the two kinds is rejected: FEEL compares a value with
 * a zone and one without by local time, so the engine evaluates such a table, but the table does not say which
 * kind its generated component should take.
 */
final class TimeZones {

    private enum Kind { LOCAL, ZONED }

    /** The inputs (columns, then the other inputs cells compare with) and outputs whose values have a time zone. */
    record Zoned(Set<String> inputs, Set<String> outputs) {
    }

    private TimeZones() {
    }

    /**
     * @throws IllegalArgumentException if a column, or a group of inputs that cells compare, holds literals
     *                                  with and without a time zone
     */
    static Zoned of(DecisionTable table) {
        var inputs = zonedInputs(table);
        return new Zoned(inputs, zonedOutputs(table, inputs));
    }

    private static Set<String> zonedInputs(DecisionTable table) {
        var types = new LinkedHashMap<String, ColumnType>();
        table.inputs().forEach(column -> types.put(column.name(), column.type()));
        table.namedInputs().forEach(types::putIfAbsent);

        var groups = new Groups();
        var kinds = new HashMap<String, Set<Kind>>();
        for (var column : table.inputs()) {
            if (!isTemporal(column.type())) {
                continue;
            }
            var cells = new ArrayList<UnaryTests>();
            for (var rule : table.rules()) {
                cells.add(table.cell(rule, column)); // a list column's cells test its elements
            }
            if (column.allowedValues() != null) {
                cells.add(CellParser.parse(column.allowedValues(), column.type()));
            }
            for (var cell : cells) {
                UnaryTests.names(cell).forEach(name -> groups.join(column.name(), name));
                literals(cell).forEach(value -> kinds.computeIfAbsent(column.name(), any -> EnumSet.noneOf(Kind.class)).add(kind(value)));
            }
        }

        var byGroup = new HashMap<String, Set<Kind>>();
        kinds.forEach((column, columnKinds) -> byGroup.computeIfAbsent(groups.root(column), any -> EnumSet.noneOf(Kind.class))
                .addAll(columnKinds));
        var zoned = new LinkedHashSet<String>();
        for (var input : types.entrySet()) {
            var groupKinds = byGroup.getOrDefault(groups.root(input.getKey()), Set.of());
            if (groupKinds.size() > 1) {
                throw mixed(table, "input '" + input.getKey() + "'", input.getValue());
            }
            if (isTemporal(input.getValue()) && groupKinds.contains(Kind.ZONED)) {
                zoned.add(input.getKey());
            }
        }
        return zoned;
    }

    private static Set<String> zonedOutputs(DecisionTable table, Set<String> zonedInputs) {
        var zoned = new LinkedHashSet<String>();
        for (var output : table.outputs()) {
            if (!isTemporal(output.type())) {
                continue;
            }
            var kinds = EnumSet.noneOf(Kind.class);
            for (var rule : table.rules()) {
                var input = rule.output().references().get(output.name());
                if (input != null) {
                    kinds.add(zonedInputs.contains(input) ? Kind.ZONED : Kind.LOCAL);
                    continue;
                }
                var text = rule.output().values().get(output.name());
                if (text != null) {
                    kinds.add(kind(parse(text, output.type(), table, output.name())));
                }
            }
            if (kinds.size() > 1) {
                throw mixed(table, "output '" + output.name() + "'", output.type());
            }
            if (kinds.contains(Kind.ZONED)) {
                zoned.add(output.name());
            }
        }
        return zoned;
    }

    private static Object parse(String text, ColumnType type, DecisionTable table, String output) {
        try {
            return type == ColumnType.TIME ? FeelTemporals.parseTime(text.strip()) : FeelTemporals.parseDateTime(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Table '%s': output '%s' value '%s' is not a %s"
                    .formatted(table.name(), output, text, feelName(type)), e);
        }
    }

    private static IllegalArgumentException mixed(DecisionTable table, String what, ColumnType type) {
        return new IllegalArgumentException(("Table '%s': %s takes %s values both with and without a time zone, "
                + "but its generated record component holds one kind").formatted(table.name(), what, feelName(type)));
    }

    private static boolean isTemporal(ColumnType type) {
        return type == ColumnType.TIME || type == ColumnType.DATE_TIME;
    }

    private static String feelName(ColumnType type) {
        return type == ColumnType.TIME ? "time" : type.feelName();
    }

    private static Kind kind(Object value) {
        return FeelTemporals.hasZone(value) ? Kind.ZONED : Kind.LOCAL;
    }

    /** The literal values of a cell, in the order written; names of other inputs are left out. */
    private static List<Comparable<?>> literals(UnaryTests cell) {
        var values = new ArrayList<Comparable<?>>();
        cell.tests().forEach(test -> values.addAll(UnaryTests.values(test)));
        values.removeIf(UnaryTests.Name.class::isInstance);
        return values;
    }

    /** Inputs joined into groups, by name (union-find). */
    private static final class Groups {
        private final Map<String, String> parents = new HashMap<>();

        String root(String name) {
            var root = name;
            while (parents.containsKey(root)) {
                root = parents.get(root);
            }
            return root;
        }

        void join(String a, String b) {
            var rootA = root(a);
            var rootB = root(b);
            if (!rootA.equals(rootB)) {
                parents.put(rootA, rootB);
            }
        }
    }
}
