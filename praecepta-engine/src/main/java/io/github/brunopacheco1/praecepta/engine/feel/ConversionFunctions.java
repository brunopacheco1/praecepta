/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.string;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.optional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's conversion functions (DMN 1.5, 10.3.4.1), its boolean functions (10.3.4.2), {@code now} and {@code today}
 * (10.3.4.11), and its date and time functions (10.3.4.6). {@code date}, {@code time}, {@code date and time} and
 * {@code duration} of literals are date time literals, which the parser folds; these are the same functions of any
 * argument.
 */
final class ConversionFunctions {

    private static final FeelType STRING = FeelType.of(ColumnType.STRING);
    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final FeelType BOOLEAN = FeelType.of(ColumnType.BOOLEAN);
    private static final String FROM = "from";
    private static final String DATE = "date";
    private static final String DATE_AND_TIME = "date and time";
    /** A number, once its grouping separators are gone and its decimal separator is a period. */
    private static final Pattern NUMBER_TEXT = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private ConversionFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        add.accept(BuiltInFunction.of(DATE, FeelType.of(ColumnType.DATE), optional(1, 2), (arguments, env) -> temporal(DATE, arguments),
                List.of(List.of(FROM), List.of("year", "month", "day"))));
        add.accept(BuiltInFunction.of(DATE_AND_TIME, FeelType.of(ColumnType.DATE_TIME), optional(1, 1),
                (arguments, env) -> temporal(DATE_AND_TIME, arguments), List.of(List.of(FROM), List.of(DATE, "time"))));
        add.accept(BuiltInFunction.of("time", FeelType.of(ColumnType.TIME), optional(1, 3), (arguments, env) -> temporal("time", arguments),
                List.of(List.of(FROM), List.of("hour", "minute", "second", "offset"))));
        add.accept(BuiltInFunction.of("duration", FeelType.ANY, fixed(1), (arguments, env) -> temporal("duration", arguments), FROM));
        add.accept(BuiltInFunction.of("years and months duration", FeelType.of(ColumnType.YEAR_MONTH_DURATION), fixed(2),
                (arguments, env) -> yearsAndMonths(arguments.get(0), arguments.get(1)), FROM, "to"));
        add.accept(BuiltInFunction.of("number", NUMBER, optional(1, 2), ConversionFunctions::number,
                FROM, "grouping separator", "decimal separator"));
        add.accept(BuiltInFunction.of("string", STRING, fixed(1), (arguments, env) -> {
            var value = arguments.getFirst();
            return value == null ? null : Arguments.characters(env, text(value, 0));
        }, FROM));
        add.accept(BuiltInFunction.of("range", new FeelType.RangeOf(FeelType.ANY), fixed(1),
                (arguments, env) -> range(string(arguments.getFirst(), FROM)), FROM));
        add.accept(BuiltInFunction.of("not", BOOLEAN, fixed(1),
                (arguments, env) -> Arguments.bool(arguments.getFirst(), "negand") ? Boolean.FALSE : Boolean.TRUE, "negand"));
        add.accept(BuiltInFunction.of("is", BOOLEAN, fixed(2), (arguments, env) -> same(arguments.get(0), arguments.get(1), env),
                "value1", "value2"));
        add.accept(BuiltInFunction.of("now", FeelType.of(ColumnType.DATE_TIME), fixed(0),
                (arguments, env) -> ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS)));
        add.accept(BuiltInFunction.of("today", FeelType.of(ColumnType.DATE), fixed(0), (arguments, env) -> LocalDate.now()));
        add.accept(BuiltInFunction.of("day of year", NUMBER, fixed(1),
                (arguments, env) -> BigDecimal.valueOf(date(arguments.getFirst()).getDayOfYear()), DATE));
        add.accept(BuiltInFunction.of("day of week", STRING, fixed(1),
                (arguments, env) -> date(arguments.getFirst()).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), DATE));
        add.accept(BuiltInFunction.of("month of year", STRING, fixed(1),
                (arguments, env) -> date(arguments.getFirst()).getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), DATE));
        add.accept(BuiltInFunction.of("week of year", NUMBER, fixed(1),
                (arguments, env) -> BigDecimal.valueOf(date(arguments.getFirst()).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)), DATE));
    }

    /** {@code date}, {@code time}, {@code date and time} or {@code duration} of its arguments (Table 72). */
    private static Object temporal(String function, List<Object> arguments) {
        var values = arguments.size() == 1 ? Collections.singletonList(Arguments.single(arguments.getFirst())) : arguments;
        for (int i = 0; i < values.size(); i++) {
            // time(hour, minute, second, offset) takes a null offset, for a time without one
            if (values.get(i) == null && !(i == 3 && function.equals("time"))) {
                throw invalid("takes no null argument");
            }
        }
        try {
            return TemporalLiterals.invoke(function, values);
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw invalid("gives no value: " + e.getMessage());
        }
    }

    /** The date of a date, or of a date and time. */
    private static LocalDate date(Object value) {
        return date(value, DATE);
    }

    private static LocalDate date(Object value, String parameter) {
        return switch (Arguments.single(value)) {
            case LocalDate date -> date;
            case LocalDateTime dateTime -> dateTime.toLocalDate();
            case ZonedDateTime dateTime -> dateTime.toLocalDate();
            case null, default -> throw Arguments.invalid("a date or a date and time", value, parameter);
        };
    }

    /**
     * {@code years and months duration(from, to)}: the whole months from one date to another, negative if {@code to}
     * is before {@code from}; a date and time is its date, the date it has where it is, so that the months from
     * {@code 2014-12-31T23:59:59} to {@code 2019-10-01T12:32:59} are 57, {@code P4Y9M} (the DMN TCK's
     * {@code 1121-feel-years-and-months-duration-function}).
     */
    private static Object yearsAndMonths(Object from, Object to) {
        return new YearsAndMonths(ChronoUnit.MONTHS.between(date(from, FROM), date(to, "to")));
    }

    /**
     * {@code number(from, grouping separator?, decimal separator?)}: the number a string writes, its digits grouped
     * by a space, a comma or a period, its fraction after a period or a comma; the two separators differ.
     */
    private static Object number(List<Object> arguments, Env env) {
        var from = string(arguments.get(0), FROM);
        var grouping = separator(arguments, 1, "grouping separator", List.of(" ", ",", "."));
        var decimal = separator(arguments, 2, "decimal separator", List.of(".", ","));
        if (grouping != null && grouping.equals(decimal)) {
            throw invalid("takes a grouping separator and a decimal separator that differ, not two \"%s\"".formatted(grouping));
        }
        var text = grouping != null ? from.replace(grouping, "") : from;
        if (decimal != null && !decimal.equals(".")) {
            if (text.contains(".")) {
                throw invalid("takes a string that writes a number, not \"%s\"".formatted(from));
            }
            text = text.replace(decimal, ".");
        }
        if (!NUMBER_TEXT.matcher(text).matches()) {
            throw invalid("takes a string that writes a number, not \"%s\"".formatted(from));
        }
        try {
            var number = new BigDecimal(text);
            if (!FeelNumbers.isDecimal128(number)) {
                throw invalid("takes a number FEEL holds, not \"%s\"".formatted(from));
            }
            return FeelNumbers.plain(number);
        } catch (NumberFormatException e) {
            throw invalid("takes a string that writes a number, not \"%s\"".formatted(from));
        }
    }

    private static String separator(List<Object> arguments, int index, String parameter, List<String> allowed) {
        if (arguments.size() <= index || arguments.get(index) == null) {
            return null;
        }
        var separator = string(arguments.get(index), parameter);
        if (!allowed.contains(separator)) {
            throw invalid("takes a %s of %s, not \"%s\"".formatted(parameter,
                    allowed.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")), separator));
        }
        return separator;
    }

    /**
     * A value as {@code string(from)} writes it: a string as it is; a number, boolean, date, time, date and time or
     * duration as its plain text ({@code 1.5}, {@code 2025-01-31T08:30:00@Europe/Paris}, {@code P1DT2H}); a list as
     * {@code [1, "a"]} and a context as {@code {a: 1}}, their strings quoted; a range as FEEL writes it.
     */
    static String text(Object value, int depth) {
        if (depth > Env.MAX_NESTING) {
            throw invalid("takes a value nested at most %d deep".formatted(Env.MAX_NESTING));
        }
        return switch (value) {
            case null -> "null";
            case String text when depth == 0 -> text;
            case String text -> "\"" + FeelStrings.escaped(text) + "\"";
            case List<?> list -> list.stream().map(item -> text(item, depth + 1)).collect(Collectors.joining(", ", "[", "]"));
            case Map<?, ?> context -> context.entrySet().stream().map(entry -> entry.getKey() + ": " + text(entry.getValue(), depth + 1))
                    .collect(Collectors.joining(", ", "{", "}"));
            case FeelRange range -> range.toString();
            case FeelFunction function -> function.toString();
            case BigDecimal number -> number.toPlainString();
            default -> FeelValues.simpleType(value) != null ? FeelLiterals.text(value) : value.toString();
        };
    }

    /**
     * {@code range(from)}: the range a range literal writes (DMN 1.5, 10.3.4.1), {@code "[18..21)"}, whose
     * endpoints are literals of one type FEEL orders, the start not after the end: numbers, strings, and dates, times,
     * date-times and durations as at-literals ({@code @"2025-01-31"}) or date time literals
     * ({@code date("2025-01-31")}).
     */
    private static Object range(String from) {
        Node root;
        try {
            root = FeelParser.parse(from, new FeelParser.Names(List.of()), null).root();
        } catch (ExpressionSyntaxException e) {
            throw invalid("takes a range literal, not \"%s\"".formatted(from));
        }
        if (!(root instanceof Node.RangeLiteral(var start, var startIncluded, var end, var endIncluded))) {
            throw invalid("takes a range literal, not \"%s\"".formatted(from));
        }
        var low = literal(start);
        var high = literal(end);
        if (low == null || high == null) {
            throw invalid("takes a range literal whose endpoints are literals, not \"%s\"".formatted(from));
        }
        var order = FeelValues.compare(low, high);
        if (order == null || order > 0 || FeelValues.simpleType(low) != FeelValues.simpleType(high)) {
            throw invalid("takes a range literal whose endpoints are of one type FEEL orders, in order, not \"%s\"".formatted(from));
        }
        return FeelRange.between(low, startIncluded, high, endIncluded);
    }

    /** The value of a literal endpoint, a negative number included; null for any other. */
    private static Object literal(Node node) {
        return switch (node) {
            case Node.Constant(var value) -> value;
            case Node.Negation(Node.Constant(BigDecimal number)) -> number.negate();
            default -> null;
        };
    }

    /**
     * {@code is(value1, value2)}: whether two values are the same element of FEEL's semantic domain (DMN 1.5,
     * 10.3.4.2): of the same type, and equal as values of it, so that a time at an offset is not the same as one in a
     * zone, nor as one at another offset, though they are equal, and a date is not the date and time of its midnight.
     */
    private static Object same(Object value1, Object value2, Env env) {
        if (value1 == null || value2 == null) {
            return value1 == null && value2 == null;
        }
        var type = FeelValues.simpleType(value1);
        if (type != null) {
            if (type != FeelValues.simpleType(value2) || value1.getClass() != value2.getClass()) {
                return false;
            }
            return switch (value1) {
                case BigDecimal number -> number.compareTo((BigDecimal) value2) == 0;
                case OffsetTime time -> time.equals(value2);
                case Duration duration -> duration.equals(value2);
                default -> value1.equals(value2);
            };
        }
        return Boolean.TRUE.equals(FeelValues.equality(value1, value2, env));
    }
}
