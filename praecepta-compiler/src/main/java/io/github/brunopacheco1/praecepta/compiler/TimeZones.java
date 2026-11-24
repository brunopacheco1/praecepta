/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
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
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * Which time and date-time columns generated code gives a Java type with a time zone ({@code OffsetTime},
 * {@code ZonedDateTime}), or with a zone id for a time ({@link ZonedTime}), instead of one without ({@code LocalTime},
 * {@code LocalDateTime}). A record
 * component has one Java type, while FEEL lets a column hold values of both kinds, so the table decides:
 * <ul>
 * <li>an input takes values with a zone when the literals written for it (its cells and input value
 * list) all have one and there is at least one; for an input that holds lists, its elements do. Inputs that cells compare ({@code > other}) take the
 * same kind, so that they are compared as written (two values with a zone by instant, two without by local time),
 * and the inputs linked by such cells are decided together;</li>
 * <li>an output has values with a zone when its literal values (its default value included) all have one and so do the inputs its
 * rules name, and there is at least one of them. An output expression counts by its operands of the output's type, the
 * inputs it reads and its literals: {@code start + duration("PT8H")} has the kind of {@code start}, as a date and time
 * or a time plus or minus a duration keeps its zone, or its lack of one.</li>
 * </ul>
 * A column, or a group of linked inputs, whose literals mix kinds is rejected: FEEL compares a value with
 * a zone and one without by local time, so the engine evaluates such a table, but the table does not say which
 * kind its generated component should take. A time with a zone id is a kind of its own, unless its zone has one fixed
 * offset ({@code Etc/UTC}): such a time is the time at that offset (DMN 1.5, Table 72), so it has a time zone, and
 * a column whose zone ids all have a fixed offset takes an {@code OffsetTime}. A time column that mixes times with a
 * zone id and times with an offset (or in such a zone) takes a {@link ZonedTime}, which may be in a zone with one
 * fixed offset ({@code Etc/UTC}, or {@code UTC+01:00} for an offset), so it holds both.
 */
final class TimeZones {

    /** Without a time zone, with an offset or a zone id that gives one, or, for a time, with a zone id. */
    enum Kind { LOCAL, ZONED, ZONE_ID }

    /**
     * The inputs (columns, then the other inputs cells compare with) and outputs whose values have a time zone, and
     * the time inputs and outputs whose values have a zone id.
     */
    record Zoned(Set<String> inputs, Set<String> outputs, Set<String> zoneIdInputs, Set<String> zoneIdOutputs) {

        Kind input(String name) {
            return kind(inputs, zoneIdInputs, name);
        }

        Kind output(String name) {
            return kind(outputs, zoneIdOutputs, name);
        }

        private static Kind kind(Set<String> zoned, Set<String> zoneIds, String name) {
            if (zoned.contains(name)) {
                return Kind.ZONED;
            }
            return zoneIds.contains(name) ? Kind.ZONE_ID : Kind.LOCAL;
        }
    }

    private TimeZones() {
    }

    /**
     * @throws IllegalArgumentException if a column, or a group of inputs that cells compare, holds literals
     *                                  with and without a time zone
     */
    static Zoned of(DecisionTable table) {
        var context = new Context(table);
        var inputs = inputKinds(context);
        var outputs = outputKinds(context, inputs);
        return new Zoned(named(inputs, Kind.ZONED), named(outputs, Kind.ZONED), named(inputs, Kind.ZONE_ID), named(outputs, Kind.ZONE_ID));
    }

    private static Set<String> named(Map<String, Kind> kinds, Kind kind) {
        var names = new LinkedHashSet<String>();
        kinds.forEach((name, itsKind) -> {
            if (itsKind == kind) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * What the table's FEEL is read with, worked out once for all its columns and outputs: the type of every input (its
     * columns, then the other inputs its cells or outputs read), and the names in scope.
     */
    private record Context(DecisionTable table, Map<String, ColumnType> types, FeelExpression.Scope scope) {

        Context(DecisionTable table) {
            this(table, inputTypes(table));
        }

        private Context(DecisionTable table, Map<String, ColumnType> types) {
            // a column that is an expression is no name: its text would read as itself
            this(table, types, FeelExpression.namesScope(types.keySet().stream().filter(CellParser::isName).toList()));
        }

        /** The type of every input, in order: the table's columns, then the other inputs its cells or outputs read. */
        private static Map<String, ColumnType> inputTypes(DecisionTable table) {
            var types = new LinkedHashMap<String, ColumnType>();
            table.inputs().forEach(column -> types.put(column.name(), column.type()));
            table.namedInputs().forEach(types::putIfAbsent);
            return types;
        }

        /** The names among {@code names} of inputs of the type: those whose values share a kind with a value of it. */
        List<String> ofType(List<String> names, ColumnType type) {
            return names.stream().filter(name -> types.get(name) == type).toList();
        }
    }

    /**
     * The kind of each time and date-time input, in order; LOCAL for one that names no value with a zone. A cell joins its
     * column with the inputs of its type that it reads, and a column computed from other inputs ({@code start + @"PT1H"})
     * with the inputs of its type that its expression reads, as their values have the same kind.
     */
    private static Map<String, Kind> inputKinds(Context context) {
        var table = context.table();
        var groups = new Groups();
        var kinds = new HashMap<String, Set<Kind>>();
        for (var column : table.inputs()) {
            if (isTemporal(column.type())) {
                var columnKinds = kinds.computeIfAbsent(column.name(), any -> EnumSet.noneOf(Kind.class));
                columnReads(context, column).forEach(read -> {
                    context.ofType(read.names(), column.type()).forEach(name -> groups.join(column.name(), name));
                    read.literals().forEach(value -> columnKinds.add(kind(value)));
                });
            }
        }
        var types = context.types();

        var byGroup = new HashMap<String, Set<Kind>>();
        kinds.forEach((column, columnKinds) -> byGroup.computeIfAbsent(groups.root(column), any -> EnumSet.noneOf(Kind.class))
                .addAll(columnKinds));
        var inputKinds = new LinkedHashMap<String, Kind>();
        for (var input : types.entrySet()) {
            var groupKinds = merged(byGroup.getOrDefault(groups.root(input.getKey()), Set.of()));
            if (groupKinds.size() > 1) {
                throw mixed(table, "input '" + input.getKey() + "'", input.getValue(), groupKinds);
            }
            if (isTemporal(input.getValue())) {
                inputKinds.put(input.getKey(), groupKinds.isEmpty() ? Kind.LOCAL : groupKinds.iterator().next());
            }
        }
        return inputKinds;
    }

    /**
     * What a cell reads, for its column's kind: the names of the inputs it compares with, and its literals of the
     * column's type (a cell in the full FEEL language may hold others, such as a duration added to a time).
     */
    private record CellReads(List<String> names, List<Object> literals) {
    }

    /**
     * What a column reads: its input expression, if it is computed, its cells, rule by rule (a list column's test its
     * elements), and its allowed values as one more, if it has them.
     */
    private static List<CellReads> columnReads(Context context, DecisionTable.InputColumn column) {
        var table = context.table();
        var cells = new ArrayList<CellReads>();
        var scope = context.scope();
        if (table.feelCells() && !CellParser.isName(column.name())) {
            var expression = FeelExpression.parse(column.name(), scope);
            cells.add(new CellReads(expression.names(), ofType(expression.literals(), column.type())));
        }
        for (var rule : table.rules()) {
            try {
                var cell = table.cell(rule, column);
                cells.add(new CellReads(UnaryTests.names(cell), literals(cell)));
            } catch (CellSyntaxException rejected) {
                var tests = FeelUnaryTests.parseCell(rule.input().values().get(column.name()), scope, rejected);
                cells.add(new CellReads(tests.names(), ofType(tests.literals(), column.type())));
            }
        }
        if (column.allowedValues() != null) {
            var allowed = CellParser.parse(column.allowedValues(), column.type());
            cells.add(new CellReads(List.of(), literals(allowed)));
        }
        return cells;
    }

    /** The values of a type among literals. */
    private static List<Object> ofType(List<Object> literals, ColumnType type) {
        return literals.stream().filter(value -> typeOf(value) == type).toList();
    }

    private static Map<String, Kind> outputKinds(Context context, Map<String, Kind> inputKinds) {
        var table = context.table();
        var kinds = new LinkedHashMap<String, Kind>();
        for (var output : table.outputs()) {
            if (!isTemporal(output.type())) {
                continue;
            }
            var outputKinds = merged(outputKinds(context, output, inputKinds));
            if (outputKinds.size() > 1) {
                throw mixed(table, "output '" + output.name() + "'", output.type(), outputKinds);
            }
            kinds.put(output.name(), outputKinds.isEmpty() ? Kind.LOCAL : outputKinds.iterator().next());
        }
        return kinds;
    }

    /**
     * The kinds of the values a time or date-time output takes: those of the inputs the rules name, of the rules'
     * literals, and of the output's default value.
     */
    private static Set<Kind> outputKinds(Context context, DecisionTable.OutputColumn output, Map<String, Kind> inputKinds) {
        var table = context.table();
        var kinds = EnumSet.noneOf(Kind.class);
        if (output.defaultValue() != null) {
            kinds.add(kind(parse(output.defaultValue(), output.type(), table, output.name())));
        }
        for (var rule : table.rules()) {
            var input = rule.output().references().get(output.name());
            var expression = rule.output().expressions().get(output.name());
            var text = rule.output().values().get(output.name());
            if (input != null) {
                kinds.add(inputKinds.getOrDefault(input, Kind.LOCAL));
            } else if (expression != null) {
                expressionKinds(FeelExpression.parse(expression, context.scope()), output.type(), context, inputKinds, kinds);
            } else if (text != null) {
                kinds.add(kind(parse(text, output.type(), table, output.name())));
            }
        }
        return kinds;
    }

    /**
     * Adds the kinds of an expression's literals and inputs of the output's type, which give the kind of its value: FEEL
     * adds a duration to a time or a date and time without changing its zone (DMN 1.5, Table 57), and an expression
     * that gives a time or a date and time gives one of those it holds or reads. A date bound to a date and time output
     * is a date and time at UTC midnight (DMN 1.5, 10.3.2.9.4), so an expression of a date and time output that may give
     * a date (it is typed a date, or its type is unknown and it holds or reads dates) gives one with a zone.
     */
    private static void expressionKinds(FeelExpression expression, ColumnType type, Context context, Map<String, Kind> inputKinds,
            Set<Kind> kinds) {
        ofType(expression.literals(), type).forEach(value -> kinds.add(kind(value)));
        context.ofType(expression.names(), type).forEach(name -> kinds.add(inputKinds.getOrDefault(name, Kind.LOCAL)));
        if (type == ColumnType.DATE_TIME && mayGiveDate(expression, context)) {
            kinds.add(Kind.ZONED);
        }
    }

    /** Whether an expression may give a date: it is typed one, or its type is not known and it holds or reads one. */
    private static boolean mayGiveDate(FeelExpression expression, Context context) {
        var type = expression.type(name -> context.types().containsKey(name) ? FeelType.of(context.types().get(name)) : null);
        if (type.equals(FeelType.of(ColumnType.DATE))) {
            return true;
        }
        return type instanceof FeelType.Any && (expression.literals().stream().anyMatch(LocalDate.class::isInstance)
                || !context.ofType(expression.names(), ColumnType.DATE).isEmpty());
    }

    /** The time or date-and-time type of a literal; null for any other. */
    private static ColumnType typeOf(Object value) {
        if (value instanceof LocalTime || value instanceof OffsetTime || value instanceof ZonedTime) {
            return ColumnType.TIME;
        }
        return value instanceof LocalDateTime || value instanceof ZonedDateTime ? ColumnType.DATE_TIME : null;
    }

    private static Object parse(String text, ColumnType type, DecisionTable table, String output) {
        try {
            return type == ColumnType.TIME ? FeelTemporals.parseTime(text.strip()) : FeelTemporals.parseDateTime(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Table '%s': output '%s' value '%s' is not a %s"
                    .formatted(table.name(), output, text, feelName(type)), e);
        }
    }

    private static IllegalArgumentException mixed(DecisionTable table, String what, ColumnType type, Set<Kind> kinds) {
        var kindsOf = kinds.contains(Kind.ZONE_ID) ? "both with a zone id and without one" : "both with and without a time zone";
        return new IllegalArgumentException(("Table '%s': %s takes %s values %s, but its generated record component holds one kind")
                .formatted(table.name(), what, feelName(type), kindsOf));
    }

    /**
     * The kinds a column's component takes: a time with a zone id and one with an offset (or in a zone with one fixed
     * offset, which is the same) both fit a {@link ZonedTime}, whose zone may have a fixed offset; others as they are.
     */
    private static Set<Kind> merged(Set<Kind> kinds) {
        if (kinds.contains(Kind.ZONE_ID) && kinds.contains(Kind.ZONED)) {
            var merged = EnumSet.copyOf(kinds);
            merged.remove(Kind.ZONED);
            return merged;
        }
        return kinds;
    }

    private static boolean isTemporal(ColumnType type) {
        return type == ColumnType.TIME || type == ColumnType.DATE_TIME;
    }

    private static String feelName(ColumnType type) {
        return type == ColumnType.TIME ? "time" : type.feelName();
    }

    private static Kind kind(Object value) {
        if (value instanceof ZonedTime) {
            return Kind.ZONE_ID;
        }
        return FeelTemporals.hasZone(value) ? Kind.ZONED : Kind.LOCAL;
    }

    /** The literal values of a cell, in the order written; names of other inputs are left out. */
    private static List<Object> literals(UnaryTests cell) {
        var values = new ArrayList<Object>();
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
