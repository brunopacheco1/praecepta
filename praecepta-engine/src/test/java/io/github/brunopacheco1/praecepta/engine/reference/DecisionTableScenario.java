/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * A random decision table plus inputs to evaluate against it. Columns are STRING, NUMBER, DATE,
 * BOOLEAN, TIME or DATE_TIME, and cells use every form of the grammar: wildcards, literals (quoted
 * and legacy unquoted, function and bare forms), lists, comparisons, intervals with every bracket
 * style, {@code not(...)} and {@code null}. String columns compare less often than the others, so
 * that both kinds of string column (with and without comparisons) are common, and string inputs
 * include values between and around the letters the cells mention. Values come from small domains, and dates are consecutive
 * days, so overlaps, boundaries and empty gaps between days are frequent; times and date-times step
 * by half an hour and twelve hours, and inputs fall on and between those steps. Some columns declare
 * input values, and inputs include values outside them.
 *
 * <p>Every rule has outputs {@code id} (its 1-based position, unique), {@code out} (one of x/y/z,
 * often shared), {@code n} (a small number, for aggregation) and {@code echo}, which one time in three
 * names the input {@code c0} (so it takes that input's value) and is otherwise {@code "a"}, a value a
 * string input may also have.
 */
public record DecisionTableScenario(List<String> columns, Map<String, ColumnType> types, Map<String, String> allowedValues,
        List<Praeceptum> rules, List<Map<String, Object>> inputs, boolean feelCells) {

    public static final String ID = "id";
    public static final String OUT = "out";
    public static final String NUMBER = "n";
    /** An output that names the input {@code c0} in some rules. */
    public static final String ECHO = "echo";
    /** The {@link #ECHO} of the rules that do not name {@code c0}. */
    private static final String ECHO_VALUE = "a";
    public static final List<String> OUT_VALUES = List.of("x", "y", "z");

    static final LocalDate DAY0 = LocalDate.of(2025, 1, 1);
    static final LocalTime TIME0 = LocalTime.of(8, 0);
    static final LocalDateTime MOMENT0 = DAY0.atTime(6, 0);
    /** String values: a column's domain is the first two to four; comparisons and intervals use all six. */
    private static final List<String> LETTERS = List.of("a", "b", "c", "d", "e", "f");

    /** Column definition: type, string domain, and declared input values (null if none). */
    private record Column(ColumnType type, List<String> domain, String allowed) {
    }

    /** Scenarios in Praecepta's syntax, and as often in strict FEEL with cells naming other columns. */
    public static Arbitrary<DecisionTableScenario> arbitrary() {
        return Arbitraries.oneOf(scenarios(false), scenarios(false).map(DecisionTableScenario::withVariableCells));
    }

    /**
     * This scenario as a DMN table holds it: every cell in strict FEEL, and one cell in three (by rule and
     * column) compares with a column of the same type instead, itself included ({@code > c1},
     * {@code c0}, {@code not(c2)}).
     */
    public DecisionTableScenario withVariableCells() {
        var rewritten = new ArrayList<Praeceptum>();
        for (int r = 0; r < rules.size(); r++) {
            var rule = rules.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                var column = columns.get(c);
                var type = types.get(column);
                var cell = rule.input().values().get(column);
                cells.put(column, (r + c) % 3 == 0 ? variableCell(type, r, c) : DmnWriter.render(CellParser.parse(cell, type)));
            }
            rewritten.add(new Praeceptum(rule.priority(), new PraeceptaInput(cells), rule.output()));
        }
        return new DecisionTableScenario(columns, types, allowedValues, List.copyOf(rewritten), inputs, true);
    }

    private String variableCell(ColumnType type, int r, int c) {
        var peers = columns.stream().filter(column -> types.get(column) == type).toList();
        var other = peers.get(r % peers.size());
        var forms = type.supportsComparisons()
                ? List.of("> " + other, other, "not(" + other + ")", "<= " + other + ", null")
                : List.of(other, "not(" + other + ")");
        return forms.get((r / 3 + c) % forms.size());
    }

    /** The scenario's table, for {@code PraeceptaEngine.Builder#table}: its outputs carry {@code priority} for {@link #OUT}. */
    public DecisionTable table(List<Praeceptum> tableRules, HitPolicy policy, List<String> priority) {
        var inputColumns = columns.stream()
                .map(column -> new DecisionTable.InputColumn(column, types.get(column), allowedValues.get(column)))
                .toList();
        var outputs = List.of(new DecisionTable.OutputColumn(ID, ColumnType.STRING),
                new DecisionTable.OutputColumn(OUT, ColumnType.STRING, outputPriority(policy, priority).getOrDefault(OUT, List.of())),
                new DecisionTable.OutputColumn(NUMBER, ColumnType.NUMBER), new DecisionTable.OutputColumn(ECHO, ColumnType.STRING));
        return new DecisionTable("scenario", policy, null, inputColumns, outputs, tableRules, feelCells);
    }

    /** Scenarios where every string column declares its input values, so gaps are analysed. */
    public static Arbitrary<DecisionTableScenario> withStringValueLists() {
        return scenarios(true);
    }

    private static Arbitrary<DecisionTableScenario> scenarios(boolean allStringsDeclared) {
        return column(allStringsDeclared).list().ofMinSize(1).ofMaxSize(3).flatMap(columns -> {
            var rules = rule(columns).list().ofMinSize(1).ofMaxSize(8);
            var inputs = input(columns).list().ofMinSize(1).ofMaxSize(12);
            return Combinators.combine(rules, inputs).as((ruleRows, inputRows) -> build(columns, ruleRows, inputRows));
        });
    }

    private static Arbitrary<Column> column(boolean declareStrings) {
        var string = Arbitraries.integers().between(2, 4).flatMap(size -> {
            var domain = LETTERS.subList(0, size);
            var declared = Arbitraries.of(domain).set().ofMinSize(1).ofMaxSize(size)
                    .map(values -> String.join(",", values.stream().sorted().map(v -> "\"" + v + "\"").toList()));
            var maybe = declareStrings ? declared : oneTimeInThree(declared);
            return maybe.map(allowed -> new Column(ColumnType.STRING, domain, allowed));
        });
        var number = oneTimeInThree(interval(ColumnType.NUMBER)).map(allowed -> new Column(ColumnType.NUMBER, List.of(), allowed));
        var date = oneTimeInThree(interval(ColumnType.DATE)).map(allowed -> new Column(ColumnType.DATE, List.of(), allowed));
        var bool = oneTimeInThree(Arbitraries.of("true", "false", "true, false"))
                .map(allowed -> new Column(ColumnType.BOOLEAN, List.of(), allowed));
        var time = oneTimeInThree(interval(ColumnType.TIME)).map(allowed -> new Column(ColumnType.TIME, List.of(), allowed));
        var dateTime = oneTimeInThree(interval(ColumnType.DATE_TIME)).map(allowed -> new Column(ColumnType.DATE_TIME, List.of(), allowed));
        var dayTime = oneTimeInThree(interval(ColumnType.DAY_TIME_DURATION))
                .map(allowed -> new Column(ColumnType.DAY_TIME_DURATION, List.of(), allowed));
        var yearMonth = oneTimeInThree(interval(ColumnType.YEAR_MONTH_DURATION))
                .map(allowed -> new Column(ColumnType.YEAR_MONTH_DURATION, List.of(), allowed));
        return Arbitraries.frequencyOf(Tuple.of(4, string), Tuple.of(2, number), Tuple.of(2, date),
                Tuple.of(1, bool), Tuple.of(1, time), Tuple.of(1, dateTime), Tuple.of(1, dayTime), Tuple.of(1, yearMonth));
    }

    /** Declared input values one time in three; otherwise none (null). */
    private static Arbitrary<String> oneTimeInThree(Arbitrary<String> declared) {
        return Arbitraries.oneOf(Arbitraries.just((String) null), Arbitraries.just((String) null), declared);
    }

    private record RuleRow(List<String> cells, String out, int number, boolean echoesInput) {
    }

    private static Arbitrary<RuleRow> rule(List<Column> columns) {
        var cells = new ArrayList<Arbitrary<String>>();
        columns.forEach(column -> cells.add(cell(column)));
        return Combinators.combine(Combinators.combine(cells).as(List::copyOf), Arbitraries.of(OUT_VALUES), Arbitraries.integers().between(1, 9),
                Arbitraries.of(true, false, false)).as(RuleRow::new);
    }

    @SuppressWarnings("unchecked")
    private static Arbitrary<String> cell(Column column) {
        Arbitrary<String> single = literal(column);
        Arbitrary<String> list = Arbitraries.oneOf(single, test(column)).list().ofMinSize(2).ofMaxSize(3).map(parts -> String.join(", ", parts));
        Arbitrary<String> negated = Arbitraries.oneOf(single, list, test(column)).map(inner -> "not(" + inner + ")");
        return Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.just("")),
                Tuple.of(1, Arbitraries.just("-")),
                Tuple.of(4, single),
                Tuple.of(comparisonWeight(column.type()), test(column)),
                Tuple.of(2, list),
                Tuple.of(1, negated),
                Tuple.of(1, Arbitraries.just("null")),
                Tuple.of(1, single.map(value -> "null, " + value)));
    }

    private static int comparisonWeight(ColumnType type) {
        if (!type.supportsComparisons()) {
            return 0;
        }
        return type == ColumnType.STRING ? 1 : 4;
    }

    /** A literal, written in every accepted style. */
    private static Arbitrary<String> literal(Column column) {
        return switch (column.type()) {
            case STRING -> Arbitraries.of(column.domain()).flatMap(v -> Arbitraries.of(v, "\"" + v + "\""));
            case NUMBER -> Arbitraries.integers().between(0, 5).map(String::valueOf);
            case DATE -> Arbitraries.integers().between(0, 5).flatMap(d -> Arbitraries.of(
                    "date(\"" + DAY0.plusDays(d) + "\")", DAY0.plusDays(d).toString()));
            case BOOLEAN -> Arbitraries.of("true", "false");
            case TIME -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.TIME, i), TIME0.plusMinutes(30L * i).toString()));
            case DATE_TIME -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.DATE_TIME, i), MOMENT0.plusHours(12L * i).toString()));
            case DAY_TIME_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.DAY_TIME_DURATION, i), FeelLiterals.text(dayTime(i))));
            case YEAR_MONTH_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.YEAR_MONTH_DURATION, i), FeelLiterals.text(yearMonth(i))));
        };
    }

    /** Days and time durations step by six hours, so that four steps make a day. */
    private static Duration dayTime(int step) {
        return Duration.ofHours(6L * step);
    }

    /** Years and months durations, unevenly spaced so that some intervals hold one month and some none. */
    private static final List<Integer> MONTHS = List.of(0, 1, 3, 6, 12, 13);

    private static YearsAndMonths yearMonth(int step) {
        return new YearsAndMonths(MONTHS.get(step));
    }

    /** A comparison or an interval (ordered columns). */
    private static Arbitrary<String> test(Column column) {
        if (!column.type().supportsComparisons()) {
            return literal(column);
        }
        var comparison = Combinators.combine(Arbitraries.of("<", "<=", ">", ">="), literal(column)).as((op, v) -> op + " " + v);
        return Arbitraries.oneOf(comparison, interval(column.type()));
    }

    private static Arbitrary<String> interval(ColumnType type) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), Arbitraries.integers().between(0, 5),
                Arbitraries.of("[", "(", "]"), Arbitraries.of("]", ")", "["))
                .as((a, b, open, close) -> open + value(type, Math.min(a, b)) + ".." + value(type, Math.max(a, b)) + close);
    }

    private static String value(ColumnType type, int i) {
        return switch (type) {
            case NUMBER -> String.valueOf(i);
            case DATE -> "date(\"" + DAY0.plusDays(i) + "\")";
            case TIME -> "time(\"" + DateTimeFormatter.ISO_LOCAL_TIME.format(TIME0.plusMinutes(30L * i)) + "\")";
            case DATE_TIME -> "date and time(\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(MOMENT0.plusHours(12L * i)) + "\")";
            case STRING -> "\"" + LETTERS.get(i) + "\"";
            case DAY_TIME_DURATION -> FeelLiterals.literal(dayTime(i));
            case YEAR_MONTH_DURATION -> FeelLiterals.literal(yearMonth(i));
            case BOOLEAN -> throw new IllegalArgumentException("no intervals on " + type);
        };
    }

    private static Arbitrary<List<Object>> input(List<Column> columns) {
        var values = new ArrayList<Arbitrary<Object>>();
        for (var column : columns) {
            values.add(switch (column.type()) {
                case STRING -> {
                    var options = new ArrayList<Object>(column.domain());
                    options.add("q");
                    options.add(" ");
                    // around and between the letters, for comparisons: below "a", between "a" and "b", "e"
                    options.add("A");
                    options.add("ab");
                    options.add("e");
                    yield Arbitraries.of(options).injectNull(0.15);
                }
                case NUMBER -> Arbitraries.integers().between(-2, 12)
                        .<Object>map(half -> BigDecimal.valueOf(half).divide(BigDecimal.TWO))
                        .injectNull(0.15);
                case DATE -> Arbitraries.integers().between(-1, 6).<Object>map(DAY0::plusDays).injectNull(0.15);
                case BOOLEAN -> Arbitraries.<Object>of(true, false).injectNull(0.15);
                // quarter steps: on every boundary and halfway between them
                case TIME -> Arbitraries.integers().between(-2, 12).<Object>map(q -> TIME0.plusMinutes(15L * q)).injectNull(0.15);
                case DATE_TIME -> Arbitraries.integers().between(-2, 12).<Object>map(q -> MOMENT0.plusHours(6L * q)).injectNull(0.15);
                // half steps, negative ones included; periods are not normalised, so P12M must equal P1Y
                case DAY_TIME_DURATION -> Arbitraries.integers().between(-2, 12).<Object>map(h -> Duration.ofHours(3L * h)).injectNull(0.15);
                case YEAR_MONTH_DURATION -> Arbitraries.integers().between(-2, 14).<Object>map(Period::ofMonths).injectNull(0.15);
            });
        }
        return Combinators.combine(values).as(row -> new ArrayList<>(row));
    }

    private static DecisionTableScenario build(List<Column> columns, List<RuleRow> ruleRows, List<List<Object>> inputRows) {
        var names = new ArrayList<String>();
        var types = new LinkedHashMap<String, ColumnType>();
        var allowed = new LinkedHashMap<String, String>();
        for (int c = 0; c < columns.size(); c++) {
            var name = "c" + c;
            names.add(name);
            types.put(name, columns.get(c).type());
            if (columns.get(c).allowed() != null) {
                allowed.put(name, columns.get(c).allowed());
            }
        }
        var rules = new ArrayList<Praeceptum>();
        for (int r = 0; r < ruleRows.size(); r++) {
            var row = ruleRows.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < names.size(); c++) {
                cells.put(names.get(c), row.cells().get(c));
            }
            var outputs = new HashMap<>(Map.of(ID, String.valueOf(r + 1), OUT, row.out(), NUMBER, String.valueOf(row.number())));
            Map<String, String> references = row.echoesInput() ? Map.of(ECHO, names.getFirst()) : Map.of();
            if (!row.echoesInput()) {
                outputs.put(ECHO, ECHO_VALUE);
            }
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(Map.copyOf(outputs), references)));
        }
        var inputs = new ArrayList<Map<String, Object>>();
        for (var row : inputRows) {
            var input = new HashMap<String, Object>();
            for (int c = 0; c < names.size(); c++) {
                input.put(names.get(c), row.get(c));
            }
            inputs.add(input);
        }
        return new DecisionTableScenario(List.copyOf(names), Map.copyOf(types), Map.copyOf(allowed), List.copyOf(rules), List.copyOf(inputs),
                false);
    }

    /** Binds every column to the input map with the typed getter of its type. */
    public <O> void bindColumns(PraeceptaEngine.Builder<Map<String, Object>, O> builder) {
        for (var column : columns) {
            switch (types.get(column)) {
                case STRING -> builder.input(column, input -> (String) input.get(column));
                case NUMBER -> builder.numberInput(column, input -> (BigDecimal) input.get(column));
                case DATE -> builder.dateInput(column, input -> (LocalDate) input.get(column));
                case BOOLEAN -> builder.booleanInput(column, input -> (Boolean) input.get(column));
                case TIME -> builder.timeInput(column, input -> (LocalTime) input.get(column));
                case DATE_TIME -> builder.dateTimeInput(column, input -> (LocalDateTime) input.get(column));
                case DAY_TIME_DURATION -> builder.durationInput(column, input -> (Duration) input.get(column));
                case YEAR_MONTH_DURATION -> builder.periodInput(column, input -> (Period) input.get(column));
            }
        }
    }

    public ReferenceMatcher reference(List<Praeceptum> tableRules, HitPolicy policy, Map<String, List<String>> outputPriority) {
        return new ReferenceMatcher(tableRules, policy, outputPriority, types, allowedValues, feelCells);
    }

    public List<String> ids(List<PraeceptaOutput> outputs) {
        return outputs.stream().map(output -> output.values().get(ID)).toList();
    }

    public List<String> ruleIds(List<Praeceptum> matches) {
        return ids(matches.stream().map(Praeceptum::output).toList());
    }

    /**
     * The rules with {@link #OUT} and {@link #ECHO} as their only outputs. Without the unique {@link #ID},
     * overlapping rules can agree, as ANY needs.
     */
    public static List<Praeceptum> outOnly(List<Praeceptum> rules) {
        return rules.stream()
                .map(rule -> {
                    var values = new HashMap<String, String>();
                    values.put(OUT, rule.output().values().get(OUT));
                    if (rule.output().values().containsKey(ECHO)) {
                        values.put(ECHO, rule.output().values().get(ECHO));
                    }
                    return new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(Map.copyOf(values), rule.output().references()));
                })
                .toList();
    }

    /** The output value lists the hit policy needs: {@code priority} for {@link #OUT} under PRIORITY and OUTPUT ORDER, else none. */
    public static Map<String, List<String>> outputPriority(HitPolicy policy, List<String> priority) {
        return policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER ? Map.of(OUT, priority) : Map.of();
    }

    /** The input as text, for map-based binding: numbers in plain notation, dates in ISO format. */
    public static Map<String, String> asText(Map<String, Object> input) {
        var text = new HashMap<String, String>();
        input.forEach((column, value) -> text.put(column, switch (value) {
            case null -> null;
            case BigDecimal decimal -> decimal.toPlainString();
            default -> value.toString();
        }));
        return text;
    }
}
