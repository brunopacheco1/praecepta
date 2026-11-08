/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Parses table cells written as DMN FEEL simple unary tests:
 *
 * <pre>
 *   (blank) or -             any value
 *   "PT", "ES"               string literals (STRING columns)
 *   PT, ES                   unquoted words are string literals too (Praecepta's original syntax)
 *   18, 2.5                  number literals (NUMBER columns)
 *   date("2025-01-31")       date literal (DATE columns; a bare 2025-01-31 is accepted as well)
 *   time("08:30:00")         time literal (TIME columns; a bare 08:30:00 is accepted as well)
 *   date and time("2025-01-31T08:30:00")
 *                            date-time literal (DATE_TIME columns; a bare 2025-01-31T08:30:00 too)
 *   true, false              boolean literals (BOOLEAN columns)
 *   &lt; 18, &gt;= 2.5            comparisons (NUMBER, DATE, TIME and DATE_TIME columns)
 *   [18..65), (0..1], ]a..b[  intervals; [ and ] close, ( ) and outward-facing brackets open
 *   null                     only a missing value
 *   not(t1, t2)              anything but these tests
 * </pre>
 *
 * Tests are separated by commas outside quotes and brackets. Surrounding whitespace is ignored.
 * Times and date-times have no zone: a literal with an offset ({@code Z}, {@code +02:00}) or a zone
 * ({@code @Europe/Paris}) is rejected.
 */
public final class CellParser {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern DATE_CALL = Pattern.compile("date\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern BARE_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME_CALL = Pattern.compile("time\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern BARE_TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    private static final Pattern DATE_TIME_CALL = Pattern.compile("date\\s+and\\s+time\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern BARE_DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    /** The digits of an offset after its sign: 02, 0200 or 02:00. */
    private static final Pattern OFFSET_DIGITS = Pattern.compile("\\d{2}(:?\\d{2})?");

    private CellParser() {
    }

    /**
     * Parses a cell in Praecepta's syntax, as Excel, CSV and code tables write it: FEEL simple unary
     * tests, plus unquoted words as string literals and bare ISO dates and times.
     *
     * @throws CellSyntaxException if the cell is not valid for the column type
     */
    public static UnaryTests parse(String cell, ColumnType type) {
        return new Parser(type, cell, false).parse();
    }

    /**
     * Parses a cell as strict DMN FEEL, as DMN files hold it. Strings must be quoted, and dates and
     * times written with their functions ({@code date("2025-01-31")}): in FEEL an unquoted word is a
     * name (a reference to another variable, which Praecepta does not support), and a bare
     * {@code 2025-01-31} is a subtraction.
     *
     * @throws CellSyntaxException if the cell is not valid for the column type, or names a variable
     */
    public static UnaryTests parseFeel(String cell, ColumnType type) {
        return new Parser(type, cell, true).parse();
    }

    /** One cell being parsed for a column type; {@code strictFeel} turns off Praecepta's shorter forms. */
    private record Parser(ColumnType type, String cell, boolean strictFeel) {

        UnaryTests parse() {
            var text = cell == null ? "" : cell.strip();
            if (text.isEmpty() || text.equals("-")) {
                return new UnaryTests.Any();
            }
            // FEEL only allows not(...) around the whole cell; its contents may hold intervals like [1..2)
            if (text.startsWith("not(") && text.endsWith(")")) {
                return new UnaryTests.Negated(tests(text.substring(4, text.length() - 1)));
            }
            return new UnaryTests.Positive(tests(text));
        }

        private List<Test> tests(String text) {
            var tests = new ArrayList<Test>();
            for (var part : splitTopLevel(text, cell)) {
                tests.add(test(part.strip()));
            }
            if (tests.isEmpty()) {
                throw new CellSyntaxException(cell, "no tests");
            }
            return tests;
        }

        private Test test(String text) {
            if (text.isEmpty()) {
                throw new CellSyntaxException(cell, "empty test between commas");
            }
            if (text.equals("null")) {
                return new NullTest();
            }
            for (var operator : List.of(Operator.LE, Operator.GE, Operator.LT, Operator.GT)) {
                if (text.startsWith(operator.symbol())) {
                    requireComparisons("comparisons");
                    return new Compare(operator, literal(text.substring(operator.symbol().length()).strip()));
                }
            }
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '[' || first == '(' || first == ']') && (last == ']' || last == ')' || last == '[')) {
                int dots = topLevelDots(text);
                if (dots > 0) {
                    requireComparisons("intervals");
                    var low = literal(text.substring(1, dots).strip());
                    var high = literal(text.substring(dots + 2, text.length() - 1).strip());
                    if (compare(low, high) > 0) {
                        throw new CellSyntaxException(cell, "interval starts after it ends");
                    }
                    return new Range(low, first == '[', high, last == ']');
                }
            }
            return new Equals(literal(text));
        }

        private Comparable<?> literal(String text) {
            if (text.isEmpty()) {
                throw new CellSyntaxException(cell, "missing value");
            }
            return switch (type) {
                case STRING -> string(text);
                case NUMBER -> number(text);
                case BOOLEAN -> bool(text);
                case DATE -> temporal(text, DATE_CALL, BARE_DATE, LocalDate::parse, "date(\"yyyy-mm-dd\")");
                case TIME -> temporal(text, TIME_CALL, BARE_TIME, LocalTime::parse, "time(\"hh:mm:ss\")");
                case DATE_TIME -> temporal(text, DATE_TIME_CALL, BARE_DATE_TIME, LocalDateTime::parse,
                        "date and time(\"yyyy-mm-ddThh:mm:ss\")");
            };
        }

        private String string(String text) {
            if (text.startsWith("\"")) {
                return quoted(text);
            }
            if (strictFeel) {
                throw new CellSyntaxException(cell, "'" + text + "' is a FEEL name (a variable), which is not supported; quote string literals");
            }
            if (text.matches(".*[\"()\\[\\]].*")) {
                throw new CellSyntaxException(cell, "unexpected characters in '" + text + "'; quote the text");
            }
            return text; // Praecepta's original unquoted syntax
        }

        private BigDecimal number(String text) {
            if (!NUMBER.matcher(text).matches()) {
                throw new CellSyntaxException(cell, "'" + text + "' is not a number");
            }
            return new BigDecimal(text);
        }

        private Boolean bool(String text) {
            return switch (text) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw new CellSyntaxException(cell, "'" + text + "' is not a boolean; write true or false");
            };
        }

        /** A date, time or date-time: its FEEL function, or (outside strict FEEL) the bare ISO text. */
        private Comparable<?> temporal(String text, Pattern call, Pattern bare, Function<String, Comparable<?>> parse, String hint) {
            var matcher = call.matcher(text);
            String iso;
            if (matcher.matches()) {
                iso = matcher.group(1).strip();
            } else if (!strictFeel && bare.matcher(text).matches()) {
                iso = text;
            } else {
                throw new CellSyntaxException(cell, "'" + text + "' is not a " + type.feelName() + "; write " + hint);
            }
            if (hasZone(iso)) {
                throw new CellSyntaxException(cell, "'" + iso + "' has a time zone; only local times are supported");
            }
            try {
                return parse.apply(iso);
            } catch (DateTimeParseException e) {
                throw new CellSyntaxException(cell, "invalid " + type.feelName() + " '" + iso + "'");
            }
        }

        private String quoted(String text) {
            if (text.length() < 2 || !text.endsWith("\"")) {
                throw new CellSyntaxException(cell, "unterminated string " + text);
            }
            var value = new StringBuilder();
            for (int i = 1; i < text.length() - 1; i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length() - 1) {
                    value.append(text.charAt(++i));
                } else if (c == '"') {
                    throw new CellSyntaxException(cell, "unexpected quote in " + text);
                } else {
                    value.append(c);
                }
            }
            return value.toString();
        }

        private void requireComparisons(String what) {
            if (!type.supportsComparisons()) {
                throw new CellSyntaxException(cell, what + " are only supported on NUMBER, DATE, TIME and DATE_TIME columns");
            }
        }
    }

    /**
     * Whether a time or date-time ends with an offset or a zone: {@code Z}, {@code +02:00},
     * {@code -0500}, {@code @Europe/Paris}. An offset follows the time, so its sign comes after a
     * colon; the dashes of a date ({@code 2025-01-31}) do not count.
     */
    private static boolean hasZone(String iso) {
        if (iso.indexOf('@') >= 0 || iso.endsWith("Z") || iso.endsWith("z")) {
            return true;
        }
        int sign = Math.max(iso.lastIndexOf('+'), iso.lastIndexOf('-'));
        return sign > 0 && iso.lastIndexOf(':', sign) >= 0 && OFFSET_DIGITS.matcher(iso.substring(sign + 1)).matches();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Comparable<?> a, Comparable<?> b) {
        return ((Comparable) a).compareTo(b);
    }

    /**
     * Splits on commas outside quotes, parentheses and intervals. An interval may use outward-facing
     * brackets ({@code ]1..2[}), so it is skipped as a unit rather than by bracket depth.
     */
    private static List<String> splitTopLevel(String text, String cell) {
        var parts = new ArrayList<String>();
        int i = 0;
        while (true) {
            int start = i;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i < text.length() && "[](".indexOf(text.charAt(i)) >= 0) {
                i = endOfInterval(text, i, cell) + 1;
            }
            int depth = 0;
            boolean quoted = false;
            for (; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    break;
                }
            }
            if (quoted) {
                throw new CellSyntaxException(cell, "unterminated string");
            }
            parts.add(text.substring(start, Math.min(i, text.length())));
            if (i >= text.length()) {
                return parts;
            }
            i++; // skip the comma
        }
    }

    /** Index of the bracket closing the interval that starts at {@code open}. */
    private static int endOfInterval(String text, int open, String cell) {
        boolean quoted = false;
        boolean seenDots = false;
        int depth = 0;
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0 && !seenDots && c == '.' && i + 1 < text.length() && text.charAt(i + 1) == '.') {
                seenDots = true;
                i++;
            } else if (depth == 0 && seenDots && "])[".indexOf(c) >= 0) {
                return i;
            }
        }
        throw new CellSyntaxException(cell, "unterminated interval");
    }

    private static int topLevelDots(String text) {
        boolean quoted = false;
        for (int i = 1; i < text.length() - 2; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '.' && text.charAt(i + 1) == '.') {
                return i;
            }
        }
        return -1;
    }
}
