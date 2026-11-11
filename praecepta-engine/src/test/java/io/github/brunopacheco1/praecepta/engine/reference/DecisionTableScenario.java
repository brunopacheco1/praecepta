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
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ForcedMaskForm;
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
 * by half an hour and twelve hours, and inputs fall on and between those steps. Half the time and
 * date-time columns take values with a time zone (bound with {@code offsetTimeInput} and
 * {@code zonedDateTimeInput}): the same instants, written at various offsets and zones, so that equal
 * instants written differently are frequent. One literal in five of a time or date-time column has the
 * other kind (a zone where the inputs have none, or none where they have one), which FEEL cannot compare
 * with the inputs. Some columns declare input values, and inputs include values outside them. One column
 * in five holds lists ({@link #lists}): its inputs are lists of zero to three of the values a single
 * input could take, missing lists, and now and then a list holding a missing element, which is invalid.
 *
 * <p>Every rule has outputs {@code id} (its 1-based position, unique), {@code out} (one of x/y/z,
 * often shared), {@code n} (a small number, for aggregation) and {@code echo}, which one time in four
 * names the input {@code c0} (so it takes that input's value), unless {@code c0} holds lists, one time
 * in four is an output expression over a string or number column (or over literals), and is otherwise
 * {@code "a"}, a value a string input may also have.
 *
 * @param zonedColumns the time and date-time columns whose inputs have a time zone
 * @param lists        the columns that hold lists
 * @param maskForm     the form the engine stores its masks in
 */
public record DecisionTableScenario(List<String> columns, Map<String, ColumnType> types, Map<String, String> allowedValues,
        List<Praeceptum> rules, List<Map<String, Object>> inputs, boolean feelCells, Set<String> zonedColumns,
        Set<String> lists, ForcedMaskForm maskForm) {

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
    /** Rules of a scenario: few, so that the reference's brute force stays quick and rules overlap often. */
    private static final int MAX_RULES = 8;
    /** Rules of a {@link #wide()} scenario: enough for masks of up to four words. */
    private static final int MAX_WIDE_RULES = 200;
    /** String values: a column's domain is the first two to four; comparisons and intervals use all six. */
    private static final List<String> LETTERS = List.of("a", "b", "c", "d", "e", "f");
    /** The offsets times with a zone are written at. */
    private static final List<ZoneOffset> OFFSETS = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(1), ZoneOffset.ofHours(-5),
            ZoneOffset.ofHoursMinutes(5, 30));
    /** The zones date-times with a zone are written in: offsets, and zone ids with daylight saving time. */
    private static final List<ZoneId> ZONES = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(1), ZoneOffset.ofHours(-5),
            ZoneId.of("Europe/Paris"), ZoneId.of("America/New_York"));

    /**
     * Column definition: type (of its elements, for a list), string domain, declared input values (null
     * if none), for a time or date-time column whether its inputs have a time zone, and whether it holds
     * lists.
     */
    private record Column(ColumnType type, List<String> domain, String allowed, boolean zoned, boolean list) {
        Column(ColumnType type, List<String> domain, String allowed, boolean zoned) {
            this(type, domain, allowed, zoned, false);
        }

        Column(ColumnType type, List<String> domain, String allowed) {
            this(type, domain, allowed, false, false);
        }
    }

    /** Scenarios in Praecepta's syntax, and as often in strict FEEL with cells naming other columns. */
    public static Arbitrary<DecisionTableScenario> arbitrary() {
        return Arbitraries.oneOf(scenarios(false, MAX_RULES), scenarios(false, MAX_RULES).map(DecisionTableScenario::withVariableCells));
    }

    /**
     * Scenarios of up to {@link #MAX_WIDE_RULES} rules, as {@link #arbitrary()} makes them otherwise: their
     * masks span several words, so a mask's words, and the edges between them, are exercised in every form.
     */
    public static Arbitrary<DecisionTableScenario> wide() {
        return Arbitraries.oneOf(scenarios(false, MAX_WIDE_RULES), scenarios(false, MAX_WIDE_RULES).map(DecisionTableScenario::withVariableCells));
    }

    /**
     * This scenario as a DMN table holds it: every cell in strict FEEL (a list column's with
     * {@code list contains(?, ...)}), and one cell in three (by rule and column) of a column of single
     * values compares with a column of the same type instead, itself included ({@code > c1},
     * {@code c0}, {@code not(c2)}); a column that holds lists is only named whole ({@code c1},
     * {@code not(c1)}), which asks whether the value is in that list.
     */
    public DecisionTableScenario withVariableCells() {
        var rewritten = new ArrayList<Praeceptum>();
        for (int r = 0; r < rules.size(); r++) {
            var rule = rules.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                var column = columns.get(c);
                var type = types.get(column);
                var cell = CellParser.parse(rule.input().values().get(column), type);
                if (lists.contains(column)) {
                    cells.put(column, DmnWriter.renderList(cell));
                } else {
                    cells.put(column, (r + c) % 3 == 0 ? variableCell(type, r, c) : DmnWriter.render(cell));
                }
            }
            rewritten.add(new Praeceptum(rule.priority(), new PraeceptaInput(cells), rule.output()));
        }
        return new DecisionTableScenario(columns, types, allowedValues, List.copyOf(rewritten), inputs, true, zonedColumns, lists,
                maskForm);
    }

    private String variableCell(ColumnType type, int r, int c) {
        var peers = columns.stream().filter(column -> types.get(column) == type).toList();
        var other = peers.get(r % peers.size());
        var forms = type.supportsComparisons() && !lists.contains(other)
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
        return new DecisionTable("scenario", policy, null, inputColumns, outputs, tableRules, feelCells, lists);
    }

    /** Scenarios where every string column declares its input values, so gaps are analysed. */
    public static Arbitrary<DecisionTableScenario> withStringValueLists() {
        return scenarios(true, MAX_RULES);
    }

    private static Arbitrary<DecisionTableScenario> scenarios(boolean allStringsDeclared, int maxRules) {
        return column(allStringsDeclared).list().ofMinSize(1).ofMaxSize(3).flatMap(columns -> {
            var rules = rule(columns).list().ofMinSize(1).ofMaxSize(maxRules);
            var inputs = input(columns).list().ofMinSize(1).ofMaxSize(12);
            var forms = Arbitraries.of(ForcedMaskForm.values());
            return Combinators.combine(rules, inputs, forms).as((ruleRows, inputRows, form) -> build(columns, ruleRows, inputRows, form));
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
        var number = oneTimeInThree(interval(ColumnType.NUMBER, false)).map(allowed -> new Column(ColumnType.NUMBER, List.of(), allowed));
        var date = oneTimeInThree(interval(ColumnType.DATE, false)).map(allowed -> new Column(ColumnType.DATE, List.of(), allowed));
        var bool = oneTimeInThree(Arbitraries.of("true", "false", "true, false"))
                .map(allowed -> new Column(ColumnType.BOOLEAN, List.of(), allowed));
        var time = Arbitraries.of(false, true).flatMap(zoned -> oneTimeInThree(interval(ColumnType.TIME, zoned))
                .map(allowed -> new Column(ColumnType.TIME, List.of(), allowed, zoned)));
        var dateTime = Arbitraries.of(false, true).flatMap(zoned -> oneTimeInThree(interval(ColumnType.DATE_TIME, zoned))
                .map(allowed -> new Column(ColumnType.DATE_TIME, List.of(), allowed, zoned)));
        var dayTime = oneTimeInThree(interval(ColumnType.DAY_TIME_DURATION, false))
                .map(allowed -> new Column(ColumnType.DAY_TIME_DURATION, List.of(), allowed));
        var yearMonth = oneTimeInThree(interval(ColumnType.YEAR_MONTH_DURATION, false))
                .map(allowed -> new Column(ColumnType.YEAR_MONTH_DURATION, List.of(), allowed));
        var single = Arbitraries.frequencyOf(Tuple.of(4, string), Tuple.of(2, number), Tuple.of(2, date),
                Tuple.of(1, bool), Tuple.of(1, time), Tuple.of(1, dateTime), Tuple.of(1, dayTime), Tuple.of(1, yearMonth));
        if (declareStrings) {
            return single; // gaps are analysed: no column holds lists, whose rules the analysis leaves out
        }
        // one column in five holds lists of such values
        return Combinators.combine(single, Arbitraries.integers().between(0, 4))
                .as((column, oneInFive) -> new Column(column.type(), column.domain(), column.allowed(), column.zoned(), oneInFive == 0));
    }

    /** Declared input values one time in three; otherwise none (null). */
    private static Arbitrary<String> oneTimeInThree(Arbitrary<String> declared) {
        return Arbitraries.oneOf(Arbitraries.just((String) null), Arbitraries.just((String) null), declared);
    }

    /** What a rule's {@link #ECHO} output is. */
    private enum Echo { VALUE, INPUT, EXPRESSION }

    /** @param expression which of the expression forms the {@link #ECHO} output takes, if it is one */
    private record RuleRow(List<String> cells, String out, int number, Echo echo, int expression) {
    }

    private static Arbitrary<RuleRow> rule(List<Column> columns) {
        var cells = new ArrayList<Arbitrary<String>>();
        columns.forEach(column -> cells.add(cell(column)));
        return Combinators.combine(Combinators.combine(cells).as(List::copyOf), Arbitraries.of(OUT_VALUES), Arbitraries.integers().between(1, 9),
                Arbitraries.of(Echo.VALUE, Echo.VALUE, Echo.INPUT, Echo.EXPRESSION), Arbitraries.integers().between(0, 3)).as(RuleRow::new);
    }

    /**
     * An output expression over the first column of single strings or numbers, or over literals if there
     * is none: concatenation, arithmetic with every operator, unary minus and parentheses, a name read
     * twice, and a division by zero, which gives null.
     */
    private static String expression(List<String> names, Map<String, ColumnType> types, Set<String> lists, int form) {
        var string = names.stream().filter(name -> types.get(name) == ColumnType.STRING && !lists.contains(name)).findFirst();
        var number = names.stream().filter(name -> types.get(name) == ColumnType.NUMBER && !lists.contains(name)).findFirst();
        if (number.isPresent() && (string.isEmpty() || form % 2 == 0)) {
            var n = number.get();
            return List.of(n + " * 2 - 1", "-(" + n + " + 1.50) / 4", n + " / 0", "1 + " + n + " * " + n).get(form);
        }
        if (string.isPresent()) {
            var s = string.get();
            return List.of(s + " + \"!\"", "\"<\" + " + s + " + \">\"", s + " + " + s, "\"a \\\"quoted\\\" \" + " + s).get(form);
        }
        return List.of("\"x\" + \"y\"", "1 + 2 * 3", "(10 - 4) / 4", "-2.50 * 2").get(form);
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
            case TIME, DATE_TIME -> Arbitraries.frequencyOf(Tuple.of(4, temporalLiteral(column.type(), column.zoned())),
                    Tuple.of(1, temporalLiteral(column.type(), !column.zoned())));
            case DAY_TIME_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.DAY_TIME_DURATION, i, -1), FeelLiterals.text(dayTime(i))));
            case YEAR_MONTH_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.YEAR_MONTH_DURATION, i, -1), FeelLiterals.text(yearMonth(i))));
        };
    }

    /**
     * A time or date-time, with a zone or without, written with its function or as bare text. Without a
     * zone, the bare text has no seconds ({@code 08:30}), as people write it.
     */
    private static Arbitrary<String> temporalLiteral(ColumnType type, boolean zoned) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), zone(type, zoned)).as((i, zone) -> temporal(type, i, zone))
                .flatMap(value -> Arbitraries.of(FeelLiterals.literal(value), zoned ? FeelLiterals.text(value) : value.toString()));
    }

    /** Which of {@link #OFFSETS} or {@link #ZONES} to write a value in; -1, none, if not {@code zoned}. */
    private static Arbitrary<Integer> zone(ColumnType type, boolean zoned) {
        return zoned ? Arbitraries.integers().between(0, (type == ColumnType.TIME ? OFFSETS : ZONES).size() - 1) : Arbitraries.just(-1);
    }

    /**
     * Step {@code i} of a time (half hours from 08:00) or date-time (twelve hours from 2025-01-01T06:00):
     * without a zone if {@code zone} is -1, else the same instant at offset zero, written at that offset or zone.
     */
    static Comparable<?> temporal(ColumnType type, int i, int zone) {
        if (type == ColumnType.TIME) {
            var time = TIME0.plusMinutes(30L * i);
            return zone < 0 ? time : OffsetTime.of(time, ZoneOffset.UTC).withOffsetSameInstant(OFFSETS.get(zone));
        }
        var moment = MOMENT0.plusHours(12L * i);
        return zone < 0 ? moment : moment.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONES.get(zone));
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
        var interval = column.type() == ColumnType.TIME || column.type() == ColumnType.DATE_TIME
                ? Arbitraries.frequencyOf(Tuple.of(4, interval(column.type(), column.zoned())), Tuple.of(1, interval(column.type(), !column.zoned())))
                : interval(column.type(), false);
        return Arbitraries.oneOf(comparison, interval);
    }

    /** An interval between two steps; with a zone, each end is written in a zone of its own. */
    private static Arbitrary<String> interval(ColumnType type, boolean zoned) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), Arbitraries.integers().between(0, 5),
                Arbitraries.of("[", "(", "]"), Arbitraries.of("]", ")", "["), zone(type, zoned), zone(type, zoned))
                .as((a, b, open, close, lowZone, highZone) -> open + value(type, Math.min(a, b), lowZone) + ".."
                        + value(type, Math.max(a, b), highZone) + close);
    }

    private static String value(ColumnType type, int i, int zone) {
        return switch (type) {
            case NUMBER -> String.valueOf(i);
            case DATE -> "date(\"" + DAY0.plusDays(i) + "\")";
            case TIME -> zone >= 0 ? FeelLiterals.literal(temporal(type, i, zone))
                    : "time(\"" + DateTimeFormatter.ISO_LOCAL_TIME.format(TIME0.plusMinutes(30L * i)) + "\")";
            case DATE_TIME -> zone >= 0 ? FeelLiterals.literal(temporal(type, i, zone))
                    : "date and time(\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(MOMENT0.plusHours(12L * i)) + "\")";
            case STRING -> "\"" + LETTERS.get(i) + "\"";
            case DAY_TIME_DURATION -> FeelLiterals.literal(dayTime(i));
            case YEAR_MONTH_DURATION -> FeelLiterals.literal(yearMonth(i));
            case BOOLEAN -> throw new IllegalArgumentException("no intervals on " + type);
        };
    }

    private static Arbitrary<List<Object>> input(List<Column> columns) {
        var values = new ArrayList<Arbitrary<Object>>();
        for (var column : columns) {
            if (column.list()) {
                // zero to three values; now and then a list holding a missing element, which is invalid
                var withMissing = value(column, false).list().ofMinSize(1).ofMaxSize(2).map(elements -> {
                    var list = new ArrayList<Object>(elements);
                    list.add(null);
                    return list;
                });
                values.add(Arbitraries.<Object>frequencyOf(
                        Tuple.of(12, value(column, false).list().ofMaxSize(3)),
                        Tuple.of(1, withMissing)).injectNull(0.15));
            } else {
                values.add(value(column, true).injectNull(0.15));
            }
        }
        return Combinators.combine(values).as(row -> new ArrayList<>(row));
    }

    /** A value of the column's type; a blank string (a missing value) only if {@code blank}. */
    private static Arbitrary<Object> value(Column column, boolean blank) {
        return switch (column.type()) {
            case STRING -> {
                var options = new ArrayList<Object>(column.domain());
                options.add("q");
                if (blank) {
                    options.add(" ");
                }
                // around and between the letters, for comparisons: below "a", between "a" and "b", "e"
                options.add("A");
                options.add("ab");
                options.add("e");
                yield Arbitraries.of(options);
            }
            case NUMBER -> Arbitraries.integers().between(-2, 12).<Object>map(half -> BigDecimal.valueOf(half).divide(BigDecimal.TWO));
            case DATE -> Arbitraries.integers().between(-1, 6).<Object>map(DAY0::plusDays);
            case BOOLEAN -> Arbitraries.<Object>of(true, false);
            // quarter steps: on every boundary and halfway between them; with a zone, the same instants in various zones
            case TIME -> Combinators.combine(Arbitraries.integers().between(-2, 12), zone(ColumnType.TIME, column.zoned()))
                    .<Object>as((q, zone) -> {
                        var time = TIME0.plusMinutes(15L * q);
                        return zone < 0 ? time : OffsetTime.of(time, ZoneOffset.UTC).withOffsetSameInstant(OFFSETS.get(zone));
                    });
            case DATE_TIME -> Combinators.combine(Arbitraries.integers().between(-2, 12), zone(ColumnType.DATE_TIME, column.zoned()))
                    .<Object>as((q, zone) -> {
                        var moment = MOMENT0.plusHours(6L * q);
                        return zone < 0 ? moment : moment.atZone(ZoneOffset.UTC).withZoneSameInstant(ZONES.get(zone));
                    });
            // half steps, negative ones included; periods are not normalised, so P12M must equal P1Y
            case DAY_TIME_DURATION -> Arbitraries.integers().between(-2, 12).<Object>map(h -> Duration.ofHours(3L * h));
            case YEAR_MONTH_DURATION -> Arbitraries.integers().between(-2, 14).<Object>map(Period::ofMonths);
        };
    }

    private static DecisionTableScenario build(List<Column> columns, List<RuleRow> ruleRows, List<List<Object>> inputRows,
            ForcedMaskForm maskForm) {
        var names = new ArrayList<String>();
        var types = new LinkedHashMap<String, ColumnType>();
        var allowed = new LinkedHashMap<String, String>();
        var zoned = new LinkedHashSet<String>();
        var lists = new HashSet<String>();
        for (int c = 0; c < columns.size(); c++) {
            var name = "c" + c;
            names.add(name);
            types.put(name, columns.get(c).type());
            if (columns.get(c).zoned()) {
                zoned.add(name);
            }
            if (columns.get(c).allowed() != null) {
                allowed.put(name, columns.get(c).allowed());
            }
            if (columns.get(c).list()) {
                lists.add(name);
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
            // an output holds one value, so it never names a column that holds lists
            var echo = row.echo() == Echo.INPUT && lists.contains(names.getFirst()) ? Echo.VALUE : row.echo();
            Map<String, String> references = echo == Echo.INPUT ? Map.of(ECHO, names.getFirst()) : Map.of();
            Map<String, String> expressions = echo == Echo.EXPRESSION ? Map.of(ECHO, expression(names, types, lists, row.expression())) : Map.of();
            if (echo == Echo.VALUE) {
                outputs.put(ECHO, ECHO_VALUE);
            }
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(Map.copyOf(outputs), references, expressions)));
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
                false, Set.copyOf(zoned), Set.copyOf(lists), maskForm);
    }

    /** Makes the builder store its masks in the scenario's form. */
    public <O> void applyMaskForm(PraeceptaEngine.Builder<Map<String, Object>, O> builder) {
        maskForm.applyTo(builder);
    }

    /**
     * Binds every column to the input map with the typed getter of its type, and of its zone for times
     * and date-times, or of lists of its type.
     */
    public <O> void bindColumns(PraeceptaEngine.Builder<Map<String, Object>, O> builder) {
        for (var column : columns) {
            if (lists.contains(column)) {
                if (types.get(column) == ColumnType.STRING) {
                    builder.listInput(column, input -> strings(input.get(column)));
                } else {
                    builder.listInput(column, types.get(column), input -> (List<?>) input.get(column));
                }
                continue;
            }
            if (zonedColumns.contains(column)) {
                if (types.get(column) == ColumnType.TIME) {
                    builder.offsetTimeInput(column, input -> (OffsetTime) input.get(column));
                } else {
                    builder.zonedDateTimeInput(column, input -> (ZonedDateTime) input.get(column));
                }
                continue;
            }
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

    /** A list of strings as {@code listInput} binds it; the scenario's lists hold strings, or null for a missing element. */
    @SuppressWarnings("unchecked")
    private static List<String> strings(Object list) {
        return (List<String>) list;
    }

    public ReferenceMatcher reference(List<Praeceptum> tableRules, HitPolicy policy, Map<String, List<String>> outputPriority) {
        return new ReferenceMatcher(tableRules, policy, outputPriority, types, allowedValues, feelCells, zonedColumns, lists);
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
                    return new Praeceptum(rule.priority(), rule.input(),
                            new PraeceptaOutput(Map.copyOf(values), rule.output().references(), rule.output().expressions()));
                })
                .toList();
    }

    /** The output value lists the hit policy needs: {@code priority} for {@link #OUT} under PRIORITY and OUTPUT ORDER, else none. */
    public static Map<String, List<String>> outputPriority(HitPolicy policy, List<String> priority) {
        return policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER ? Map.of(OUT, priority) : Map.of();
    }

    /**
     * The input as text, for map-based binding: numbers in plain notation, dates in ISO format,
     * date-times with a zone id as Java writes them ({@code 2025-01-01T07:00+01:00[Europe/Paris]}), and
     * lists as FEEL lists such as {@code ["a", "b"]} or {@code [date("2025-01-01")]}, with {@code null}
     * for a missing element.
     */
    public static Map<String, String> asText(Map<String, Object> input) {
        var text = new HashMap<String, String>();
        input.forEach((column, value) -> text.put(column, switch (value) {
            case null -> null;
            case List<?> list -> list.stream().map(DecisionTableScenario::elementText).collect(Collectors.joining(", ", "[", "]"));
            default -> plainText(value);
        }));
        return text;
    }

    private static String plainText(Object value) {
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    /** A list's element as a list's text holds it: strings quoted, other values as FEEL literals or bare ISO text. */
    private static String elementText(Object value) {
        return switch (value) {
            case null -> "null";
            case Period period -> FeelLiterals.literal(YearsAndMonths.of(period));
            default -> FeelLiterals.literal(value);
        };
    }
}
