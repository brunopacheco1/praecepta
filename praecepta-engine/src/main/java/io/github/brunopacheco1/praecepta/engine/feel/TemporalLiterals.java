/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * Reads FEEL's date time literals (DMN 1.5, grammar rule 60): an at-literal, {@code @} and a string (rule 65), or an
 * invocation of {@code date}, {@code time}, {@code date and time} or {@code duration}, "the only permitted functions"
 * there, in any of the forms of Table 72:
 *
 * <pre>
 *   &#64;"2025-01-31", &#64;"08:30:00", &#64;"2025-01-31T08:30:00Z", &#64;"P1D", &#64;"P1Y2M"
 *                                         a date, time, date and time or duration, as its string's syntax says (10.3.4.1)
 *   date("2025-01-31"), date(2025, 1, 31), date(date and time("2025-01-31T08:30:00"))
 *   time("08:30:00"), time(8, 30, 0), time(8, 30, 0, duration("PT1H")), time(date and time("2025-01-31T08:30:00Z"))
 *   date and time("2025-01-31T08:30:00"), date and time("2025-01-31") (at midnight),
 *   date and time(date("2025-01-31"), time("08:30:00"))
 *   duration("P1DT2H"), duration("P1Y2M")
 * </pre>
 *
 * The arguments are themselves literals: strings, numbers, date time literals, and {@code null} for a time's offset.
 * {@link CellParser} reads a cell's literals with it, and {@link ExpressionParser} the literals of output
 * expressions, so that both read the same forms.
 */
final class TemporalLiterals {

    /** The functions rule 60 permits, longest name first, so that {@code date and time} is not read as {@code date}. */
    private static final List<String> FUNCTIONS = List.of("date and time", "duration", "date", "time");
    private static final Pattern DATE = Pattern.compile("-?\\d{4,}-\\d{2}-\\d{2}");
    private static final Pattern YEARS_AND_MONTHS = Pattern.compile("-?P(\\d+Y)?(\\d+M)?");
    private static final Pattern NUMBER = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");
    /** Deeper nesting than {@code date and time(date(date and time(...)), ...)} is refused, so that reading never runs out of stack. */
    private static final int MAX_DEPTH = 8;

    /** A literal read from a text: its value and the index just after it. */
    record Read(Object value, int end) {
    }

    private final String text;
    /** The type the literal should have, which names it in messages and tells a duration's kind; null to infer it. */
    private final ColumnType expected;
    private int position;
    private int depth;

    private TemporalLiterals(String text, int from, ColumnType expected) {
        this.text = text;
        this.position = from;
        this.expected = expected;
    }

    /** Whether a date time literal starts at {@code from}: {@code @} and a quote, or a function's name and a parenthesis. */
    static boolean startsAt(String text, int from) {
        if (text.startsWith("@", from)) {
            return true;
        }
        return function(text, from) != null;
    }

    /**
     * Reads the date time literal that starts at {@code from}.
     *
     * @param expected the type the literal should have, which names it in messages and tells whether a duration is
     *                 one of days and time or of years and months; null to infer it from the text
     * @return a {@code LocalDate}, {@code LocalTime}, {@code OffsetTime}, {@code LocalDateTime}, {@code ZonedDateTime},
     *         {@code Duration} or {@link YearsAndMonths}, and where the literal ends
     * @throws IllegalArgumentException if the literal is not valid, with a message that says why
     */
    static Read read(String text, int from, ColumnType expected) {
        var reader = new TemporalLiterals(text, from, expected);
        var value = reader.literal();
        return new Read(value, reader.position);
    }

    /** The column type of a value: of a date time literal, or a string, number or boolean. */
    static ColumnType typeOf(Object value) {
        return switch (value) {
            case String s -> ColumnType.STRING;
            case BigDecimal n -> ColumnType.NUMBER;
            case Boolean b -> ColumnType.BOOLEAN;
            case LocalDate d -> ColumnType.DATE;
            case LocalTime t -> ColumnType.TIME;
            case OffsetTime t -> ColumnType.TIME;
            case LocalDateTime d -> ColumnType.DATE_TIME;
            case ZonedDateTime d -> ColumnType.DATE_TIME;
            case Duration d -> ColumnType.DAY_TIME_DURATION;
            case YearsAndMonths d -> ColumnType.YEAR_MONTH_DURATION;
            default -> throw new IllegalArgumentException("not a FEEL value: " + value);
        };
    }

    /** The name of the function whose invocation starts at {@code from}, or null if none does. */
    private static String function(String text, int from) {
        for (var name : FUNCTIONS) {
            int end = afterName(text, from, name);
            if (end >= 0) {
                int open = skipWhitespace(text, end);
                if (open < text.length() && text.charAt(open) == '(') {
                    return name;
                }
            }
        }
        return null;
    }

    /** The index after {@code name} at {@code from}, its words separated by any whitespace; -1 if it is not there. */
    private static int afterName(String text, int from, String name) {
        int i = from;
        var words = name.split(" ");
        for (int w = 0; w < words.length; w++) {
            if (w > 0) {
                int next = skipWhitespace(text, i);
                if (next == i) {
                    return -1;
                }
                i = next;
            }
            if (!text.startsWith(words[w], i)) {
                return -1;
            }
            i += words[w].length();
        }
        // the name must end there: dates is not date
        return i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_') ? -1 : i;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    // ---- reading ---------------------------------------------------------------------------------

    private Object literal() {
        if (++depth > MAX_DEPTH) {
            throw new IllegalArgumentException("date time literals nested more than " + MAX_DEPTH + " deep");
        }
        position = skipWhitespace(text, position);
        Object value;
        if (text.startsWith("@", position)) {
            position++;
            if (!text.startsWith("\"", position)) {
                throw new IllegalArgumentException("@ must be followed by a string, as in @\"2025-01-31\"");
            }
            value = atLiteral(string());
        } else {
            var name = function(text, position);
            if (name == null) {
                throw new IllegalArgumentException("'" + text.substring(position) + "' is not a date time literal");
            }
            position = text.indexOf('(', afterName(text, position, name)) + 1;
            value = invocation(name, arguments());
        }
        depth--;
        return value;
    }

    /** The arguments up to the closing parenthesis, which is consumed. */
    private List<Object> arguments() {
        var arguments = new ArrayList<Object>();
        position = skipWhitespace(text, position);
        if (text.startsWith(")", position)) {
            position++;
            return arguments;
        }
        while (true) {
            arguments.add(argument());
            position = skipWhitespace(text, position);
            if (position >= text.length()) {
                throw new IllegalArgumentException("a parenthesis is not closed in '" + text + "'");
            }
            char c = text.charAt(position++);
            if (c == ')') {
                return arguments;
            }
            if (c != ',') {
                throw new IllegalArgumentException("unexpected '" + c + "' in '" + text + "'");
            }
        }
    }

    /** A string, a number, {@code null} or a date time literal. */
    private Object argument() {
        position = skipWhitespace(text, position);
        if (text.startsWith("\"", position)) {
            return string();
        }
        if (startsAt(text, position)) {
            return literal();
        }
        int end = position;
        while (end < text.length() && ",)".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        var word = text.substring(position, end).strip();
        position = end;
        if (word.equals("null")) {
            return null;
        }
        if (NUMBER.matcher(word).matches()) {
            return new BigDecimal(word);
        }
        throw new IllegalArgumentException("'" + word + "' is not a string, a number or a date time literal");
    }

    /** A string literal, with FEEL's escape sequences; the closing quote is consumed. */
    private String string() {
        var value = new StringBuilder();
        int i = position + 1;
        while (i < text.length() && text.charAt(i) != '"') {
            char c = text.charAt(i++);
            if (c != '\\') {
                value.append(c);
            } else if (i < text.length()) {
                i = FeelStrings.escape(text, i, value, IllegalArgumentException::new);
            }
        }
        if (i >= text.length()) {
            throw new IllegalArgumentException("unterminated string in '" + text + "'");
        }
        position = i + 1;
        return value.toString();
    }

    // ---- values ----------------------------------------------------------------------------------

    /** An at-literal's value: its string's syntax says which type it has (10.3.4.1). */
    private Object atLiteral(String value) {
        var iso = value.strip();
        if (DATE.matcher(iso).matches()) {
            return date(iso);
        }
        int t = iso.indexOf('T');
        if (t > 0 && DATE.matcher(iso.substring(0, t)).matches()) {
            return dateTime(iso);
        }
        if (iso.startsWith("P") || iso.startsWith("-P")) {
            return duration(iso);
        }
        return time(iso);
    }

    private Object invocation(String name, List<Object> arguments) {
        return switch (name) {
            case "date" -> dateOf(arguments);
            case "time" -> timeOf(arguments);
            case "date and time" -> dateTimeOf(arguments);
            default -> durationOf(arguments);
        };
    }

    /** {@code date(from)} of a date string or a date and time, or {@code date(year, month, day)}. */
    private LocalDate dateOf(List<Object> arguments) {
        if (arguments.size() == 1) {
            return switch (arguments.getFirst()) {
                case String iso -> date(iso.strip());
                case LocalDate date -> date;
                case LocalDateTime dateTime -> dateTime.toLocalDate();
                case ZonedDateTime dateTime -> dateTime.toLocalDate();
                case null, default -> throw takes("date", "a date string or a date and time");
            };
        }
        if (arguments.size() == 3 && arguments.stream().allMatch(BigDecimal.class::isInstance)) {
            try {
                return LocalDate.of(whole(arguments.get(0)), whole(arguments.get(1)), whole(arguments.get(2)));
            } catch (DateTimeException | ArithmeticException e) {
                throw new IllegalArgumentException("invalid date " + arguments);
            }
        }
        throw takes("date", "a date string, a date and time, or a year, a month and a day");
    }

    /** {@code time(from)} of a time string, a time or a date and time, or {@code time(hour, minute, second, offset?)}. */
    private Object timeOf(List<Object> arguments) {
        if (arguments.size() == 1) {
            return switch (arguments.getFirst()) {
                case String iso -> time(iso.strip());
                case LocalTime time -> time;
                case OffsetTime time -> time;
                case LocalDateTime dateTime -> dateTime.toLocalTime();
                case ZonedDateTime dateTime when dateTime.getZone() instanceof ZoneOffset offset -> OffsetTime.of(dateTime.toLocalTime(), offset);
                case ZonedDateTime dateTime -> throw zoneId(FeelLiterals.text(dateTime));
                case null, default -> throw takes("time", "a time string, a time or a date and time");
            };
        }
        boolean numbers = arguments.size() >= 3 && arguments.subList(0, 3).stream().allMatch(BigDecimal.class::isInstance);
        if (numbers && arguments.size() == 3 || numbers && arguments.size() == 4
                && (arguments.get(3) == null || arguments.get(3) instanceof Duration)) {
            try {
                var seconds = (BigDecimal) arguments.get(2);
                var nanos = seconds.subtract(new BigDecimal(seconds.toBigInteger())).movePointRight(9).intValueExact();
                var time = LocalTime.of(whole(arguments.get(0)), whole(arguments.get(1)), seconds.toBigInteger().intValueExact(), nanos);
                if (arguments.size() == 3 || arguments.get(3) == null) {
                    return time;
                }
                var offset = (Duration) arguments.get(3);
                if (offset.getNano() != 0) {
                    throw new ArithmeticException("an offset is a whole number of seconds");
                }
                return OffsetTime.of(time, ZoneOffset.ofTotalSeconds(Math.toIntExact(offset.getSeconds())));
            } catch (DateTimeException | ArithmeticException e) {
                throw new IllegalArgumentException("invalid time " + arguments);
            }
        }
        throw takes("time", "a time string, a time, a date and time, or an hour, a minute, a second and an optional offset");
    }

    /** {@code date and time(from)} of a date time string, or {@code date and time(date, time)}. */
    private Object dateTimeOf(List<Object> arguments) {
        if (arguments.size() == 1 && arguments.getFirst() instanceof String iso) {
            return dateTime(iso.strip());
        }
        if (arguments.size() == 2) {
            var date = switch (arguments.get(0)) {
                case LocalDate day -> day;
                case LocalDateTime dateTime -> dateTime.toLocalDate();
                case ZonedDateTime dateTime -> dateTime.toLocalDate();
                case null, default -> null;
            };
            if (date != null) {
                switch (arguments.get(1)) {
                    case LocalTime time -> {
                        return date.atTime(time);
                    }
                    case OffsetTime time -> {
                        return ZonedDateTime.of(date, time.toLocalTime(), time.getOffset());
                    }
                    case null, default -> {
                        // not a time
                    }
                }
            }
        }
        throw takes("date and time", "a date time string, or a date (or date and time) and a time");
    }

    private Object durationOf(List<Object> arguments) {
        if (arguments.size() == 1 && arguments.getFirst() instanceof String iso) {
            return duration(iso.strip());
        }
        throw takes("duration", "a duration string");
    }

    private LocalDate date(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            throw invalid(ColumnType.DATE, iso);
        }
    }

    private Object time(String iso) {
        if (iso.indexOf('@') >= 0) {
            throw zoneId(iso);
        }
        try {
            return FeelTemporals.parseTime(iso);
        } catch (DateTimeParseException e) {
            throw invalid(ColumnType.TIME, iso);
        }
    }

    /**
     * A date time string: a date string, which is that date at midnight without a zone, "optionally followed by the
     * character "T" followed by a time string value" (DMN 1.5, 10.3.4.1).
     */
    private Object dateTime(String iso) {
        if (DATE.matcher(iso).matches()) {
            return date(iso).atStartOfDay();
        }
        // FEEL writes a zone id as @Europe/Paris only; Java's [Europe/Paris] could carry an offset FEEL cannot write back
        if (iso.indexOf('[') >= 0) {
            throw new IllegalArgumentException("invalid date and time '" + iso + "'; write a zone id as @Europe/Paris");
        }
        try {
            return FeelTemporals.parseDateTime(iso);
        } catch (DateTimeParseException e) {
            throw invalid(ColumnType.DATE_TIME, iso);
        }
    }

    /**
     * A days and time duration ({@code P1DT2H}) or a years and months one ({@code P1Y2M}): the expected type's, else
     * the one the text's designators say.
     */
    private Object duration(String iso) {
        boolean yearsAndMonths = expected == ColumnType.YEAR_MONTH_DURATION
                || expected != ColumnType.DAY_TIME_DURATION && YEARS_AND_MONTHS.matcher(iso).matches() && iso.length() > iso.indexOf('P') + 1;
        try {
            return yearsAndMonths ? YearsAndMonths.parse(iso) : Duration.parse(iso);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid(yearsAndMonths ? ColumnType.YEAR_MONTH_DURATION : ColumnType.DAY_TIME_DURATION, iso);
        }
    }

    private IllegalArgumentException invalid(ColumnType type, String iso) {
        var name = expected == ColumnType.DAY_TIME_DURATION || expected == ColumnType.YEAR_MONTH_DURATION ? expected : type;
        return new IllegalArgumentException("invalid " + name.feelName() + " '" + iso + "'");
    }

    private static IllegalArgumentException zoneId(String iso) {
        return new IllegalArgumentException("'" + iso + "' is a time with a zone id, which is not supported; write an offset such as +01:00");
    }

    private static IllegalArgumentException takes(String function, String what) {
        return new IllegalArgumentException(function + "(...) takes " + what);
    }

    /** A whole number, as an int. */
    private static int whole(Object number) {
        return ((BigDecimal) number).intValueExact();
    }
}
