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
import java.util.Collections;
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
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * A random decision table plus inputs to evaluate against it. Columns are STRING, NUMBER, DATE,
 * BOOLEAN, TIME or DATE_TIME, and cells use every form of the grammar: wildcards, literals (quoted
 * and legacy unquoted, with escape sequences, function, at-literal and bare forms, numbers with a fraction alone or an
 * exponent, dates, times and date-times built from their parts), lists, comparisons, intervals with every bracket
 * style, {@code = x}, {@code != x} (and {@code != null}), {@code not(...)} and {@code null}. String columns compare less often than the others, so
 * that both kinds of string column (with and without comparisons) are common, and string inputs
 * include values between and around the letters the cells mention, among them characters beyond U+FFFF and
 * from U+E000 up, which FEEL orders by code point and UTF-16 would order otherwise. Values come from small domains, and dates are consecutive
 * days, so overlaps, boundaries and empty gaps between days are frequent; times and date-times step
 * by half an hour and twelve hours, and inputs fall on and between those steps (a zoned date-time input
 * now and then falls instead on either side of a daylight saving change, where a local time is skipped or
 * occurs twice). Half the time and
 * date-time columns take values with a time zone (bound with {@code offsetTimeInput} and
 * {@code zonedDateTimeInput}): the same instants, written at various offsets and zones, so that equal
 * instants written differently are frequent. One literal in five of a time or date-time column has the
 * other kind (a zone where the inputs have none, or none where they have one), which FEEL compares with
 * the inputs by local time, and one interval in six has an end of each kind. Some columns declare input
 * values, and inputs include values outside them. One column
 * in five holds lists ({@link #lists}): its inputs are lists of zero to three of the values a single
 * input could take (a list of times or date-times now and then mixes values with and without a zone),
 * missing lists, and now and then a list holding a missing element. In strict FEEL, one list cell in three also
 * asks for a missing element ({@code list contains(?, null)}).
 *
 * <p>Every rule has outputs {@code id} (its 1-based position, unique), {@code out} (one of x/y/z,
 * often shared), {@code n} (a small number, for aggregation, which one time in four is an output
 * expression over a number column or literals, now and then a division by zero, which gives a missing
 * output) and {@code echo}, which one time in four
 * names the input {@code c0} (so it takes that input's value), unless {@code c0} holds lists, one time
 * in four is an output expression over a string or number column (or over literals), and is otherwise
 * {@code "a"}, a value a string input may also have.
 *
 * <p>Half of the scenarios declare default output values ({@link #defaults}) for some of those outputs,
 * which the table returns when no rule matches; a declared default may be {@code null}, so that now and then
 * every declared default is null.
 *
 * @param zonedColumns the time and date-time columns whose inputs have a time zone
 * @param lists        the columns that hold lists
 * @param maskForm     the form the engine stores its masks in
 * @param defaults     the default value of each output that has one (DMN's {@code defaultOutputEntry}), null for a
 *                     default of {@code null}; empty for none
 */
public record DecisionTableScenario(List<String> columns, Map<String, ColumnType> types, Map<String, String> allowedValues,
        List<Praeceptum> rules, List<Map<String, Object>> inputs, boolean feelCells, Set<String> zonedColumns,
        Set<String> lists, ForcedMaskForm maskForm, Map<String, String> defaults) {

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
    /**
     * String values, in FEEL's order (by code point): a column's domain is the first two to four, and now
     * and then five or six; intervals use the first six, and comparisons all seven. The fifth and sixth tell
     * FEEL's order from UTF-16's: U+FF5E, and U+1F40E, which UTF-16 writes as two surrogates (U+D83D U+DC0E)
     * and so puts before U+FF5E. The seventh, U+1F40E followed by a tab, a quote and a backslash, can only be
     * written with escape sequences.
     */
    private static final List<String> LETTERS = List.of("a", "b", "c", "d", "～", "🐎", "🐎\t\"\\");
    /** How many of {@link #LETTERS} intervals and value lists use: all but the one with escape sequences. */
    private static final int INTERVAL_LETTERS = 6;
    /** A character from U+E000 up: after U+1F40E's first surrogate in UTF-16, before U+1F40E by code point. */
    private static final String PRIVATE_USE = String.valueOf((char) 0xE000);
    /** The offsets times with a zone are written at. */
    private static final List<ZoneOffset> OFFSETS = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(1), ZoneOffset.ofHours(-5),
            ZoneOffset.ofHoursMinutes(5, 30));
    /**
     * The offsets input times with a zone are also written at. A time has no date, so at -12:00 the
     * generated times (07:30 to 11:00 at offset zero) are evenings that lie after every time at the other
     * offsets, more than a day after midnight at offset zero; at +12:00 they are evenings that keep their
     * place. Cells do not use them, as their intervals would then start after they end.
     */
    private static final List<ZoneOffset> INPUT_OFFSETS = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(1), ZoneOffset.ofHours(-5),
            ZoneOffset.ofHoursMinutes(5, 30), ZoneOffset.ofHours(12), ZoneOffset.ofHours(-12));
    /** The zones date-times with a zone are written in: offsets, the farthest ones, and zone ids with daylight saving time. */
    private static final List<ZoneId> ZONES = List.of(ZoneOffset.UTC, ZoneOffset.ofHours(1), ZoneOffset.ofHours(-5),
            ZoneId.of("Europe/Paris"), ZoneId.of("America/New_York"), ZoneOffset.ofHours(-12), ZoneId.of("Pacific/Kiritimati"));
    /**
     * Instants on either side of the 2025 daylight saving changes in Paris and New York: written in their
     * zone, one falls just after a skipped hour, and the others on a local time that occurs twice.
     */
    private static final List<ZonedDateTime> DAYLIGHT_SAVING_CHANGES = List.of(
            ZonedDateTime.of(2025, 3, 30, 1, 30, 0, 0, ZoneOffset.UTC), ZonedDateTime.of(2025, 10, 26, 0, 30, 0, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2025, 10, 26, 1, 30, 0, 0, ZoneOffset.UTC), ZonedDateTime.of(2025, 11, 2, 5, 30, 0, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2025, 11, 2, 6, 30, 0, 0, ZoneOffset.UTC));

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
     * {@code not(c1)}), which asks whether the value is in that list. One cell in three of a column that holds
     * lists also asks for a missing element ({@code list contains(?, null)}; a {@code -} becomes that alone).
     */
    public DecisionTableScenario withVariableCells() {
        var rewritten = new ArrayList<Praeceptum>();
        for (int r = 0; r < rules.size(); r++) {
            var rule = rules.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                var column = columns.get(c);
                var type = types.get(column);
                var text = rule.input().values().get(column);
                var cell = CellParser.parse(text, type);
                if (lists.contains(column)) {
                    cells.put(column, (r + c) % 3 == 1 ? withNullElement(cell) : DmnWriter.renderList(cell));
                } else {
                    cells.put(column, (r + c) % 3 == 0 ? variableCell(type, r, c) : feelCell(text, cell, type));
                }
            }
            rewritten.add(new Praeceptum(rule.priority(), new PraeceptaInput(cells), rule.output()));
        }
        return new DecisionTableScenario(columns, types, allowedValues, List.copyOf(rewritten), inputs, true, zonedColumns, lists,
                maskForm, defaults);
    }

    /** A list column's cell that also asks for a missing element: {@code ... or list contains(?, null)}, inside its {@code not(...)}. */
    private static String withNullElement(UnaryTests cell) {
        var tests = new ArrayList<>(cell.tests());
        tests.add(new UnaryTests.NullElement());
        return DmnWriter.renderList(cell instanceof UnaryTests.Negated ? new UnaryTests.Negated(tests) : new UnaryTests.Positive(tests));
    }

    /**
     * A cell as strict FEEL: as written, where strict FEEL reads it the same (as it reads quoted strings with
     * escape sequences, so they are read by both parsers), else as {@link DmnWriter#render} writes it.
     */
    private static String feelCell(String text, UnaryTests cell, ColumnType type) {
        if (text.indexOf('\\') >= 0) {
            try {
                if (CellParser.parseFeel(text, type).equals(cell)) {
                    return text;
                }
            } catch (CellSyntaxException e) {
                // not strict FEEL as written
            }
        }
        return DmnWriter.render(cell);
    }

    private String variableCell(ColumnType type, int r, int c) {
        var peers = columns.stream().filter(column -> types.get(column) == type).toList();
        var other = peers.get(r % peers.size());
        List<String> forms;
        if (lists.contains(other)) {
            forms = List.of(other, "not(" + other + ")"); // a list is only named whole, which asks whether the value is in it
        } else if (type.supportsComparisons()) {
            forms = List.of("> " + other, other, "not(" + other + ")", "<= " + other + ", null", "!= " + other, "= " + other + ", > " + other);
        } else {
            forms = List.of(other, "not(" + other + ")", "!= " + other);
        }
        return forms.get((r / 3 + c) % forms.size());
    }

    /**
     * The scenario's table, for {@code PraeceptaEngine.Builder#table}: its outputs carry {@code priority} for
     * {@link #OUT}, and their {@link #defaults}.
     */
    public DecisionTable table(List<Praeceptum> tableRules, HitPolicy policy, List<String> priority) {
        var inputColumns = columns.stream()
                .map(column -> new DecisionTable.InputColumn(column, types.get(column), allowedValues.get(column)))
                .toList();
        var outputs = List.of(output(ID, ColumnType.STRING, List.of()),
                output(OUT, ColumnType.STRING, outputPriority(policy, priority).getOrDefault(OUT, List.of())),
                output(NUMBER, ColumnType.NUMBER, List.of()), output(ECHO, ColumnType.STRING, List.of()));
        return new DecisionTable("scenario", policy, null, inputColumns, outputs, tableRules, feelCells, lists);
    }

    /** An output column with its value list and its default value, if the scenario gives it one ({@code null} included). */
    public DecisionTable.OutputColumn output(String name, ColumnType type, List<String> values) {
        return new DecisionTable.OutputColumn(name, type, values, defaults.get(name), defaults.containsKey(name));
    }

    /**
     * The output when no rule matches, for a table with these outputs, as DMN 1.5 has it (10.3.2.10, step 2):
     * each output's default, null for one without; null, no output at all, if none has a default.
     */
    public PraeceptaOutput defaultOutput(List<String> outputs) {
        if (outputs.stream().noneMatch(defaults::containsKey)) {
            return null;
        }
        var values = new HashMap<String, String>();
        outputs.forEach(output -> values.put(output, defaults.get(output)));
        return new PraeceptaOutput(values);
    }

    /**
     * No default output one time in two; otherwise one for some of the outputs, each of which may be {@code null}
     * (#92): the outputs id, out, n and echo each take a digit in base 3 of {@code outputs}, 0 for no default, 1 for
     * a value and 2 for {@code null}.
     */
    private static Arbitrary<Map<String, String>> defaultValues() {
        var some = Combinators.combine(Arbitraries.integers().between(1, 80), Arbitraries.of(OUT_VALUES)).as((outputs, out) -> {
            var defaults = new HashMap<String, String>();
            var names = List.of(ID, OUT, NUMBER, ECHO);
            var values = List.of("0", out, "1.50", "none");
            int digits = outputs;
            for (int i = 0; i < names.size(); i++, digits /= 3) {
                switch (digits % 3) {
                    case 1 -> defaults.put(names.get(i), values.get(i));
                    case 2 -> defaults.put(names.get(i), null);
                    default -> {
                        // no default
                    }
                }
            }
            return Collections.unmodifiableMap(defaults);
        });
        return Arbitraries.oneOf(Arbitraries.just(Map.<String, String>of()), some);
    }

    /** Scenarios where every string column declares its input values, so gaps are analysed. */
    public static Arbitrary<DecisionTableScenario> withStringValueLists() {
        return scenarios(true, MAX_RULES);
    }

    /**
     * Scenarios whose two or three columns all hold times, or all date-times, each with or without a time
     * zone, and half of them lists: as {@link #arbitrary()} makes them otherwise. Values with and without
     * a zone meet in the same lists, and cells of one column often name another, so membership in a list
     * of the other kind (unknown in FEEL) is common.
     */
    public static Arbitrary<DecisionTableScenario> temporal() {
        var columns = Arbitraries.of(ColumnType.TIME, ColumnType.DATE_TIME)
                .flatMap(type -> temporalColumn(type).list().ofMinSize(2).ofMaxSize(3));
        return Arbitraries.oneOf(scenarios(columns, MAX_RULES), scenarios(columns, MAX_RULES).map(DecisionTableScenario::withVariableCells));
    }

    private static Arbitrary<Column> temporalColumn(ColumnType type) {
        return Arbitraries.of(false, true).flatMap(zoned -> Arbitraries.of(false, true).flatMap(list ->
                oneTimeInThree(interval(type, zoned)).map(allowed -> new Column(type, List.of(), allowed, zoned, list))));
    }

    private static Arbitrary<DecisionTableScenario> scenarios(boolean allStringsDeclared, int maxRules) {
        return scenarios(column(allStringsDeclared).list().ofMinSize(1).ofMaxSize(3), maxRules);
    }

    private static Arbitrary<DecisionTableScenario> scenarios(Arbitrary<List<Column>> tableColumns, int maxRules) {
        return tableColumns.flatMap(columns -> {
            var rules = rule(columns).list().ofMinSize(1).ofMaxSize(maxRules);
            var inputs = input(columns).list().ofMinSize(1).ofMaxSize(12);
            var forms = Arbitraries.of(ForcedMaskForm.values());
            return Combinators.combine(rules, inputs, forms, defaultValues())
                    .as((ruleRows, inputRows, form, defaults) -> build(columns, ruleRows, inputRows, form, defaults));
        });
    }

    private static Arbitrary<Column> column(boolean declareStrings) {
        // mostly two to four letters; now and then the two that FEEL and UTF-16 order differently too
        var sizes = Arbitraries.frequencyOf(Tuple.of(3, Arbitraries.integers().between(2, 4)),
                Tuple.of(1, Arbitraries.integers().between(5, INTERVAL_LETTERS)));
        var string = sizes.flatMap(size -> {
            var domain = LETTERS.subList(0, size);
            var declared = Arbitraries.of(domain).set().ofMinSize(1).ofMaxSize(size)
                    .map(values -> String.join(",", values.stream().sorted().map(v -> quoted(v, LETTERS.indexOf(v))).toList()));
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
        // one column in five holds lists of such values, whose gaps are analysed too
        return Combinators.combine(single, Arbitraries.integers().between(0, 4))
                .as((column, oneInFive) -> new Column(column.type(), column.domain(), column.allowed(), column.zoned(), oneInFive == 0));
    }

    /** Declared input values one time in three; otherwise none (null). */
    private static Arbitrary<String> oneTimeInThree(Arbitrary<String> declared) {
        return Arbitraries.oneOf(Arbitraries.just((String) null), Arbitraries.just((String) null), declared);
    }

    /** What a rule's {@link #ECHO} output is. */
    private enum Echo { VALUE, INPUT, EXPRESSION }

    /**
     * @param expression       which of the expression forms the {@link #ECHO} output takes, if it is one
     * @param numberExpression which of the expression forms the {@link #NUMBER} output takes, or -1 for its literal
     */
    private record RuleRow(List<String> cells, String out, int number, Echo echo, int expression, int numberExpression) {
    }

    private static Arbitrary<RuleRow> rule(List<Column> columns) {
        var cells = new ArrayList<Arbitrary<String>>();
        columns.forEach(column -> cells.add(cell(column)));
        var numberExpression = Arbitraries.frequencyOf(Tuple.of(12, Arbitraries.just(-1)), Tuple.of(4, Arbitraries.integers().between(0, 5)));
        return Combinators.combine(Combinators.combine(cells).as(List::copyOf), Arbitraries.of(OUT_VALUES), Arbitraries.integers().between(1, 9),
                Arbitraries.of(Echo.VALUE, Echo.VALUE, Echo.INPUT, Echo.EXPRESSION), Arbitraries.integers().between(0, 5), numberExpression)
                .as(RuleRow::new);
    }

    /**
     * A number output expression over the first column of single numbers, or over literals if there is
     * none; one form divides by zero, which gives a missing output, and two raise to whole powers (unary
     * minus first, then from left to right), one of them zero to a negative power when the input is zero.
     */
    private static String numberExpression(List<String> names, Map<String, ColumnType> types, Set<String> lists, int form) {
        var number = names.stream().filter(name -> types.get(name) == ColumnType.NUMBER && !lists.contains(name)).findFirst();
        if (number.isPresent()) {
            return overNumber(number.get()).get(form);
        }
        return List.of("1 + 2 * 3", "(10 - 4) / 4", "1 / 0", "-2.50 * 2", "-3 ** 2 / 7", "0 ** -1 + 2.5E-1").get(form);
    }

    /** The number expressions over a number input. */
    private static List<String> overNumber(String n) {
        return List.of(n + " * 2 - 1", "-(" + n + " + 1.50) / 4", n + " / 0", "1 + " + n + " * " + n,
                "-" + n + " ** 2 + 2 ** 3 ** 2", n + " ** -2 * 1.5e1");
    }

    /**
     * An output expression over the first column of single strings or numbers, or over literals if there
     * is none: concatenation, arithmetic with every operator, unary minus and parentheses, a name read
     * twice, a division by zero, which gives null, whole powers, numbers with an exponent and escape
     * sequences in strings.
     */
    private static String expression(List<String> names, Map<String, ColumnType> types, Set<String> lists, int form) {
        var string = names.stream().filter(name -> types.get(name) == ColumnType.STRING && !lists.contains(name)).findFirst();
        var number = names.stream().filter(name -> types.get(name) == ColumnType.NUMBER && !lists.contains(name)).findFirst();
        if (number.isPresent() && (string.isEmpty() || form % 2 == 0)) {
            return overNumber(number.get()).get(form);
        }
        if (string.isPresent()) {
            var s = string.get();
            return List.of(s + " + \"!\"", "\"<\" + " + s + " + \">\"", s + " + " + s, "\"a \\\"quoted\\\" \" + " + s,
                    s + " + \"\\t\\u00e9\"", "\"\\\\ \" + " + s).get(form);
        }
        return List.of("\"x\" + \"y\"", "1 + 2 * 3", "(10 - 4) / 4", "-2.50 * 2", "2 ** 3 ** 0", "(1 / 3) * 3 - 1e0").get(form);
    }

    @SuppressWarnings("unchecked")
    private static Arbitrary<String> cell(Column column) {
        Arbitrary<String> single = literal(column);
        Arbitrary<String> list = Arbitraries.oneOf(single, test(column), equality(single)).list().ofMinSize(2).ofMaxSize(3)
                .map(parts -> String.join(", ", parts));
        Arbitrary<String> negated = Arbitraries.oneOf(single, list, test(column)).map(inner -> "not(" + inner + ")");
        return Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.just("")),
                Tuple.of(1, Arbitraries.just("-")),
                Tuple.of(4, single),
                Tuple.of(comparisonWeight(column.type()), test(column)),
                Tuple.of(2, equality(single)),
                Tuple.of(2, list),
                Tuple.of(1, negated),
                Tuple.of(1, Arbitraries.just("null")),
                Tuple.of(1, single.map(value -> "null, " + value)));
    }

    /**
     * {@code = x} and {@code != x} (DMN 1.5, grammar rule 7.a), {@code = null} and {@code != null}: one test each, so
     * that a list of tests holds them among others ({@code != 1, > 3}).
     */
    private static Arbitrary<String> equality(Arbitrary<String> single) {
        return Arbitraries.frequencyOf(
                Tuple.of(2, single.map(value -> "= " + value)),
                Tuple.of(4, single.map(value -> "!= " + value)),
                Tuple.of(1, Arbitraries.just("= null")),
                Tuple.of(2, Arbitraries.just("!= null")));
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
            case STRING -> stringLiteral(column.domain());
            // in each of FEEL's forms: digits, a fraction alone, an exponent (grammar rule 35)
            case NUMBER -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(String.valueOf(i), i + ".0", i + "0E-1",
                    "." + i + "e1", i + "e+0"));
            case DATE -> Arbitraries.integers().between(0, 5).flatMap(d -> {
                var date = DAY0.plusDays(d);
                return Arbitraries.of("date(\"" + date + "\")", date.toString(), atLiteral(date),
                        "date(%d, %d, %d)".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
            });
            case BOOLEAN -> Arbitraries.of("true", "false");
            case TIME, DATE_TIME -> Arbitraries.frequencyOf(Tuple.of(4, temporalLiteral(column.type(), column.zoned())),
                    Tuple.of(1, temporalLiteral(column.type(), !column.zoned())));
            case DAY_TIME_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.DAY_TIME_DURATION, i, -1), FeelLiterals.text(dayTime(i)), atLiteral(dayTime(i))));
            case YEAR_MONTH_DURATION -> Arbitraries.integers().between(0, 5).flatMap(i -> Arbitraries.of(
                    value(ColumnType.YEAR_MONTH_DURATION, i, -1), FeelLiterals.text(yearMonth(i)), atLiteral(yearMonth(i))));
        };
    }

    /** One of the values, as a literal in each accepted style: unquoted (where it can be), and quoted in every form of {@link #quoted}. */
    private static Arbitrary<String> stringLiteral(List<String> values) {
        return Arbitraries.of(values).flatMap(v -> {
            var styles = new ArrayList<String>();
            if (v.chars().allMatch(c -> c > ' ' && c != '"' && c != '\\')) {
                styles.add(v);
            }
            for (int form = 0; form < QUOTED_FORMS; form++) {
                styles.add(quoted(v, form));
            }
            return Arbitraries.of(styles);
        });
    }

    /** How many forms {@link #quoted} writes. */
    private static final int QUOTED_FORMS = 4;

    /**
     * A string literal, its characters written in one of FEEL's forms (grammar rule 64), chosen by {@code form}
     * modulo {@link #QUOTED_FORMS}: 0, as they are, with the single-letter escapes where needed ({@code \t},
     * {@code \"}, {@code \\}); 1, every character as a backslash, an uppercase U and six hexadecimal digits; 2,
     * every UTF-16 unit as a backslash, a lowercase u and four, so a character beyond U+FFFF as a surrogate pair;
     * 3, the three mixed, character by character.
     */
    private static String quoted(String value, int form) {
        var literal = new StringBuilder("\"");
        int index = 0;
        for (int i = 0; i < value.length(); i += Character.charCount(value.codePointAt(i)), index++) {
            int codePoint = value.codePointAt(i);
            switch (form % QUOTED_FORMS == 3 ? index % 3 : form % QUOTED_FORMS) {
                case 0 -> literal.append(FeelLiterals.literal(Character.toString(codePoint)), 1,
                        FeelLiterals.literal(Character.toString(codePoint)).length() - 1);
                case 1 -> literal.append("\\U%06X".formatted(codePoint));
                default -> Character.toString(codePoint).chars().forEach(unit -> literal.append("\\u%04x".formatted(unit)));
            }
        }
        return literal.append('"').toString();
    }

    /**
     * A time or date-time, with a zone or without, written with its function, as an at-literal, with the function's
     * other forms ({@link #componentLiteral}) or as bare text. Without a zone, the bare text has no seconds
     * ({@code 08:30}), as people write it.
     */
    private static Arbitrary<String> temporalLiteral(ColumnType type, boolean zoned) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), zone(type, zoned)).as((i, zone) -> temporal(type, i, zone))
                .flatMap(value -> Arbitraries.of(FeelLiterals.literal(value), zoned ? FeelLiterals.text(value) : value.toString(),
                        atLiteral(value), componentLiteral(value)));
    }

    /** A date, time, date-time or duration as an at-literal: {@code @"2025-01-01"} (DMN 1.5, grammar rule 65). */
    static String atLiteral(Object value) {
        var literal = FeelLiterals.literal(value);
        return "@" + literal.substring(literal.indexOf('"'), literal.lastIndexOf('"') + 1);
    }

    /**
     * A time or date-time with the forms of its function that build it from parts (DMN 1.5, Table 72):
     * {@code time(8, 30, 0)}, {@code time(8, 30, 0, duration("PT1H"))}, {@code date and time(date(...), time(...))}; one
     * in a zone id, which no time holds, as an at-literal.
     */
    private static String componentLiteral(Object value) {
        return switch (value) {
            case LocalTime time -> "time(%d, %d, %d)".formatted(time.getHour(), time.getMinute(), time.getSecond());
            case OffsetTime time -> "time(%d, %d, %d, %s)".formatted(time.getHour(), time.getMinute(), time.getSecond(),
                    FeelLiterals.literal(Duration.ofSeconds(time.getOffset().getTotalSeconds())));
            case LocalDateTime moment -> "date and time(%s, %s)".formatted(FeelLiterals.literal(moment.toLocalDate()),
                    FeelLiterals.literal(moment.toLocalTime()));
            case ZonedDateTime moment when moment.getZone() instanceof ZoneOffset offset -> "date and time(%s, %s)".formatted(
                    atLiteral(moment.toLocalDate()), componentLiteral(OffsetTime.of(moment.toLocalTime(), offset)));
            default -> atLiteral(value);
        };
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
        // a string compares with any of the letters, outside the column's domain too, so with those FEEL orders by code point
        var bound = column.type() == ColumnType.STRING ? stringLiteral(LETTERS) : literal(column);
        var comparison = Combinators.combine(Arbitraries.of("<", "<=", ">", ">="), bound).as((op, v) -> op + " " + v);
        var interval = column.type() == ColumnType.TIME || column.type() == ColumnType.DATE_TIME
                ? Arbitraries.frequencyOf(Tuple.of(4, interval(column.type(), column.zoned())), Tuple.of(1, interval(column.type(), !column.zoned())),
                        Tuple.of(1, mixedInterval(column.type())))
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

    /**
     * An interval with one end with a zone and one without, which FEEL orders by local time: the ends are
     * written in that order, so that the interval does not start after it ends.
     */
    private static Arbitrary<String> mixedInterval(ColumnType type) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), Arbitraries.integers().between(0, 5),
                Arbitraries.of("[", "(", "]"), Arbitraries.of("]", ")", "["), zone(type, true))
                .as((a, b, open, close, zone) -> {
                    var local = temporal(type, a, -1);
                    var zoned = temporal(type, b, zone);
                    boolean localFirst = FeelTemporals.compare(local, zoned) <= 0;
                    return open + FeelLiterals.literal(localFirst ? local : zoned) + ".." + FeelLiterals.literal(localFirst ? zoned : local) + close;
                });
    }

    private static String value(ColumnType type, int i, int zone) {
        return switch (type) {
            case NUMBER -> String.valueOf(i);
            case DATE -> i % 2 == 0 ? "date(\"" + DAY0.plusDays(i) + "\")" : atLiteral(DAY0.plusDays(i));
            case TIME -> zone >= 0 ? FeelLiterals.literal(temporal(type, i, zone))
                    : "time(\"" + DateTimeFormatter.ISO_LOCAL_TIME.format(TIME0.plusMinutes(30L * i)) + "\")";
            case DATE_TIME -> zone >= 0 ? FeelLiterals.literal(temporal(type, i, zone))
                    : "date and time(\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(MOMENT0.plusHours(12L * i)) + "\")";
            // in each of the quoted forms, by step and end
            case STRING -> quoted(LETTERS.get(i), i + zone + 1);
            case DAY_TIME_DURATION -> FeelLiterals.literal(dayTime(i));
            case YEAR_MONTH_DURATION -> FeelLiterals.literal(yearMonth(i));
            case BOOLEAN -> throw new IllegalArgumentException("no intervals on " + type);
        };
    }

    private static Arbitrary<List<Object>> input(List<Column> columns) {
        var values = new ArrayList<Arbitrary<Object>>();
        for (var column : columns) {
            if (column.list()) {
                // zero to three values; now and then a list holding a missing element
                var element = element(column);
                var withMissing = element.list().ofMinSize(1).ofMaxSize(2).map(elements -> {
                    var list = new ArrayList<Object>(elements);
                    list.add(null);
                    return list;
                });
                values.add(Arbitraries.<Object>frequencyOf(
                        Tuple.of(12, element.list().ofMaxSize(3)),
                        Tuple.of(2, withMissing)).injectNull(0.15));
            } else {
                values.add(value(column, true).injectNull(0.15));
            }
        }
        return Combinators.combine(values).as(row -> new ArrayList<>(row));
    }

    /**
     * An element of a list column: a value of its type, and for times and date-times one time in five of
     * the other kind (with a zone where the column's have none, or none where they have one), which a list
     * may mix, as FEEL lets it.
     */
    private static Arbitrary<Object> element(Column column) {
        if (column.type() != ColumnType.TIME && column.type() != ColumnType.DATE_TIME) {
            return value(column, false);
        }
        var otherKind = new Column(column.type(), column.domain(), column.allowed(), !column.zoned(), true);
        return Arbitraries.frequencyOf(Tuple.of(4, value(column, false)), Tuple.of(1, value(otherKind, false)));
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
                // around and between the letters, for comparisons: below "a", between "a" and "b", "e"; and
                // the characters that FEEL's order by code point and UTF-16's order place differently
                options.add("A");
                options.add("ab");
                options.add("e");
                options.add(LETTERS.get(4));
                options.add(LETTERS.get(5));
                options.add(LETTERS.get(5) + "a");
                options.add(PRIVATE_USE);
                // which cells can only write with escape sequences, and a tab and a quote alone
                options.add(LETTERS.get(6));
                options.add("a\t\"");
                yield Arbitraries.of(options);
            }
            case NUMBER -> Arbitraries.integers().between(-2, 12).<Object>map(half -> BigDecimal.valueOf(half).divide(BigDecimal.TWO));
            case DATE -> Arbitraries.integers().between(-1, 6).<Object>map(DAY0::plusDays);
            case BOOLEAN -> Arbitraries.<Object>of(true, false);
            // quarter steps: on every boundary and halfway between them; with a zone, the same instants in various zones
            case TIME -> Combinators.combine(Arbitraries.integers().between(-2, 12),
                            column.zoned() ? Arbitraries.integers().between(0, INPUT_OFFSETS.size() - 1) : Arbitraries.just(-1))
                    .<Object>as((q, zone) -> {
                        var time = TIME0.plusMinutes(15L * q);
                        return zone < 0 ? time : OffsetTime.of(time, ZoneOffset.UTC).withOffsetSameInstant(INPUT_OFFSETS.get(zone));
                    });
            case DATE_TIME -> Combinators.combine(Arbitraries.integers().between(-2, 12), zone(ColumnType.DATE_TIME, column.zoned()),
                            Arbitraries.integers().between(0, 5 * DAYLIGHT_SAVING_CHANGES.size() - 1))
                    .<Object>as((q, zone, change) -> {
                        var moment = MOMENT0.plusHours(6L * q);
                        if (zone < 0) {
                            return moment;
                        }
                        // one time in five, an instant at a daylight saving change
                        var instant = change < DAYLIGHT_SAVING_CHANGES.size() ? DAYLIGHT_SAVING_CHANGES.get(change) : moment.atZone(ZoneOffset.UTC);
                        return instant.withZoneSameInstant(ZONES.get(zone));
                    });
            // half steps, negative ones included; periods are not normalised, so P12M must equal P1Y
            case DAY_TIME_DURATION -> Arbitraries.integers().between(-2, 12).<Object>map(h -> Duration.ofHours(3L * h));
            case YEAR_MONTH_DURATION -> Arbitraries.integers().between(-2, 14).<Object>map(Period::ofMonths);
        };
    }

    private static DecisionTableScenario build(List<Column> columns, List<RuleRow> ruleRows, List<List<Object>> inputRows,
            ForcedMaskForm maskForm, Map<String, String> defaults) {
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
            var outputs = new HashMap<>(Map.of(ID, String.valueOf(r + 1), OUT, row.out()));
            // an output holds one value, so it never names a column that holds lists
            var echo = row.echo() == Echo.INPUT && lists.contains(names.getFirst()) ? Echo.VALUE : row.echo();
            Map<String, String> references = echo == Echo.INPUT ? Map.of(ECHO, names.getFirst()) : Map.of();
            var expressions = new HashMap<String, String>();
            if (echo == Echo.EXPRESSION) {
                expressions.put(ECHO, expression(names, types, lists, row.expression()));
            }
            if (echo == Echo.VALUE) {
                outputs.put(ECHO, ECHO_VALUE);
            }
            if (row.numberExpression() < 0) {
                outputs.put(NUMBER, String.valueOf(row.number()));
            } else {
                expressions.put(NUMBER, numberExpression(names, types, lists, row.numberExpression()));
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
                false, Set.copyOf(zoned), Set.copyOf(lists), maskForm, defaults);
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
        return reference(tableRules, policy, outputPriority, true);
    }

    /**
     * @param perColumn whether the engine binds the inputs per column, so a time or date-time column's
     *                  inputs are of one kind ({@link #zonedColumns}), or from text, which may hold either
     */
    public ReferenceMatcher reference(List<Praeceptum> tableRules, HitPolicy policy, Map<String, List<String>> outputPriority,
            boolean perColumn) {
        return reference(tableRules, policy, outputPriority, perColumn, List.of(ID, OUT, NUMBER, ECHO));
    }

    /** @param outputs the table's outputs, as {@link #table} has them unless a test builds its own table */
    public ReferenceMatcher reference(List<Praeceptum> tableRules, HitPolicy policy, Map<String, List<String>> outputPriority,
            boolean perColumn, List<String> outputs) {
        return new ReferenceMatcher(tableRules, policy, outputPriority, types, allowedValues, feelCells, zonedColumns, lists, !perColumn,
                defaultOutput(outputs));
    }

    /** This scenario with its masks stored in {@code form}. */
    public DecisionTableScenario withMaskForm(ForcedMaskForm form) {
        return new DecisionTableScenario(columns, types, allowedValues, rules, inputs, feelCells, zonedColumns, lists, form, defaults);
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
                    var expressions = new HashMap<>(rule.output().expressions());
                    expressions.keySet().retainAll(Set.of(ECHO));
                    return new Praeceptum(rule.priority(), rule.input(),
                            new PraeceptaOutput(Map.copyOf(values), rule.output().references(), expressions));
                })
                .toList();
    }

    /** The rules, then one more whose every cell is {@code -}, so that every input matches some rule. */
    public List<Praeceptum> withCatchAll(List<Praeceptum> rules) {
        var cells = new HashMap<String, String>();
        columns.forEach(column -> cells.put(column, "-"));
        var catchAll = new Praeceptum(rules.size() + 1, new PraeceptaInput(cells),
                new PraeceptaOutput(Map.of(ID, String.valueOf(rules.size() + 1), OUT, OUT_VALUES.getFirst(), NUMBER, "1", ECHO, ECHO_VALUE)));
        var all = new ArrayList<>(rules);
        all.add(catchAll);
        return List.copyOf(all);
    }

    /**
     * The rules with {@link #NUMBER} as their only output, a literal or an expression, which may give a missing
     * value: what a COLLECT aggregation aggregates, since DMN aggregates a single output (8.2.11).
     */
    public static List<Praeceptum> numberOnly(List<Praeceptum> rules) {
        return rules.stream()
                .map(rule -> {
                    var values = new HashMap<String, String>();
                    if (rule.output().values().containsKey(NUMBER)) {
                        values.put(NUMBER, rule.output().values().get(NUMBER));
                    }
                    var expressions = new HashMap<>(rule.output().expressions());
                    expressions.keySet().retainAll(Set.of(NUMBER));
                    return new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(values, Map.of(), expressions));
                })
                .toList();
    }

    /** The scenario's table with {@link #NUMBER} as its only output, with its default if it has one, aggregated. */
    public DecisionTable numberTable(List<Praeceptum> tableRules, AggregationOperator aggregation) {
        var table = table(tableRules, HitPolicy.COLLECT, List.of());
        return new DecisionTable(table.name(), HitPolicy.COLLECT, aggregation, table.inputs(),
                List.of(output(NUMBER, ColumnType.NUMBER, List.of())), tableRules, table.feelCells(), table.listInputs());
    }

    /**
     * The rules with {@link #OUT} and {@link #ECHO} as their only outputs, as {@link #outOnly}, but with every
     * {@code echo} the value {@code "a"}: no output reads an input, so ANY compares the outputs the engine
     * converted when it was built, with the masks of the rules that have the same outputs.
     */
    public static List<Praeceptum> outOnlyValues(List<Praeceptum> rules) {
        return rules.stream()
                .map(rule -> new Praeceptum(rule.priority(), rule.input(),
                        new PraeceptaOutput(Map.of(OUT, rule.output().values().get(OUT), ECHO, ECHO_VALUE))))
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
