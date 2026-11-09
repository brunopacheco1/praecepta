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
import java.util.function.IntPredicate;
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
 *   [18..65), (0..1], ]a..b[  intervals; [ and ] include their end, ( ) and outward-facing brackets exclude it
 *   null                     only a missing value
 *   not(t1, t2)              anything but these tests
 * </pre>
 *
 * Tests are separated by commas outside quotes and brackets. Surrounding whitespace is ignored.
 * Times and date-times have no zone: a literal with an offset ({@code Z}, {@code +02:00}) or a zone
 * ({@code @Europe/Paris}) is rejected.
 */
public final class CellParser {

    /** Two-character operators come first, so that {@code <= 5} is not read as {@code <} and {@code = 5}. */
    private static final List<Operator> OPERATORS = List.of(Operator.LE, Operator.GE, Operator.LT, Operator.GT);
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

    /** Compares two values of the same column type. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable<?> a, Comparable<?> b) {
        return ((Comparable) a).compareTo(b);
    }

    /**
     * One cell being parsed for a column type; {@code strictFeel} turns off Praecepta's shorter forms.
     * The cell is split into tests, each test is read as {@code null}, a comparison, an interval or a
     * literal, and each literal as a value of the column type. The scanners that find where a test
     * ends come last.
     */
    private record Parser(ColumnType type, String cell, boolean strictFeel) {

        UnaryTests parse() {
            var text = cell == null ? "" : cell.strip();
            if (text.isEmpty() || text.equals("-")) {
                return new UnaryTests.Any();
            }
            // FEEL allows not(...) only around the whole cell, so it is found by the cell's first and last
            // characters: matching its parentheses would trip over an interval inside, as in not([1..2))
            if (text.startsWith("not(") && text.endsWith(")")) {
                return new UnaryTests.Negated(tests(text.substring("not(".length(), text.length() - 1)));
            }
            return new UnaryTests.Positive(tests(text));
        }

        // ---- tests and literals ------------------------------------------------------------------

        private List<Test> tests(String text) {
            var tests = new ArrayList<Test>();
            for (var part : splitTests(text)) {
                tests.add(test(part.strip()));
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
            for (var operator : OPERATORS) {
                if (text.startsWith(operator.symbol())) {
                    requireComparisons("comparisons");
                    return new Compare(operator, literal(text.substring(operator.symbol().length()).strip()));
                }
            }
            if (isBracketed(text)) {
                int dots = topLevelDots(text);
                if (dots > 0) {
                    return interval(text, dots);
                }
            }
            return new Equals(literal(text));
        }

        /** Whether a test opens with {@code [}, {@code (} or {@code ]} and closes with {@code ]}, {@code )} or {@code [}. */
        private static boolean isBracketed(String text) {
            return "[(]".indexOf(text.charAt(0)) >= 0 && "])[".indexOf(text.charAt(text.length() - 1)) >= 0;
        }

        /**
         * An interval {@code low..high} whose {@code ..} is at {@code dots}. Only {@code [} at the start and
         * {@code ]} at the end include their end; {@code (}, {@code )} and outward-facing brackets exclude it.
         */
        private Range interval(String text, int dots) {
            requireComparisons("intervals");
            var low = literal(text.substring(1, dots).strip());
            var high = literal(text.substring(dots + 2, text.length() - 1).strip());
            if (compare(low, high) > 0) {
                throw new CellSyntaxException(cell, "interval starts after it ends");
            }
            return new Range(low, text.charAt(0) == '[', high, text.charAt(text.length() - 1) == ']');
        }

        private void requireComparisons(String what) {
            if (!type.supportsComparisons()) {
                throw new CellSyntaxException(cell, what + " are only supported on NUMBER, DATE, TIME and DATE_TIME columns");
            }
        }

        /** A single value of the column's type. */
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
            if (hasFeelSyntax(text)) {
                throw new CellSyntaxException(cell, "unexpected characters in '" + text + "'; quote the text");
            }
            return text; // Praecepta's original unquoted syntax
        }

        /** The contents of a quoted string, in which a backslash escapes the character after it. */
        private String quoted(String text) {
            if (text.length() < 2 || !text.endsWith("\"")) {
                throw new CellSyntaxException(cell, "unterminated string " + text);
            }
            var value = new StringBuilder();
            int end = text.length() - 1; // the closing quote
            int i = 1;
            while (i < end) {
                char c = text.charAt(i);
                if (c == '"') {
                    throw new CellSyntaxException(cell, "unexpected quote in " + text);
                }
                if (c == '\\' && i + 1 < end) {
                    value.append(text.charAt(i + 1));
                    i += 2;
                } else {
                    value.append(c);
                    i++;
                }
            }
            return value.toString();
        }

        /**
         * Whether unquoted text holds a quote, a parenthesis or a bracket, which belong to FEEL syntax.
         * Text over several lines is a legacy exception: it is accepted unchecked, as Praecepta always has
         * (tests pin it).
         */
        private static boolean hasFeelSyntax(String text) {
            return text.chars().anyMatch(c -> "\"()[]".indexOf(c) >= 0) && text.chars().noneMatch(Parser::isLineTerminator);
        }

        /** A line terminator, as Java's regular expressions define it. */
        private static boolean isLineTerminator(int c) {
            return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
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

        /**
         * A date, time or date-time: its FEEL function call, or, outside strict FEEL, the bare ISO text.
         * Strict FEEL cannot take the bare form, as it reads {@code 2025-01-31} as {@code 2025 - 1 - 31}.
         */
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

        // ---- scanners: where tests, strings and intervals end ------------------------------------

        /**
         * Splits a cell into its tests at the commas outside strings, parentheses and intervals. An
         * interval is skipped as a unit, not by counting brackets, because its brackets need not pair
         * up: {@code [1..2)} and {@code ]1..2[} are both intervals.
         */
        private List<String> splitTests(String text) {
            var parts = new ArrayList<String>();
            int end = -1;
            do {
                int start = end + 1;
                end = endOfTest(text, start);
                parts.add(text.substring(start, end));
            } while (end < text.length());
            return parts;
        }

        /**
         * Index of the comma that ends the test starting at {@code start}, or the text's length if it is
         * the last test. A leading interval is skipped whole; after it, commas inside strings or
         * parentheses do not count. A {@code )} with no {@code (} open takes the depth below zero, so
         * commas stop ending tests until a later {@code (} brings it back: {@code 1), 2} is one test.
         */
        private int endOfTest(String text, int start) {
            int i = afterLeadingInterval(text, start);
            int depth = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == ',' && depth == 0) {
                    return i;
                }
                if (c == '"') {
                    i = closingQuote(text, i);
                    if (i == text.length()) {
                        throw new CellSyntaxException(cell, "unterminated string");
                    }
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            return i;
        }

        /** Index just after the interval a test starts with (after whitespace), or of its first character if it has none. */
        private int afterLeadingInterval(String text, int start) {
            int i = start;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            boolean interval = i < text.length() && "[](".indexOf(text.charAt(i)) >= 0;
            return interval ? endOfInterval(text, i) + 1 : i;
        }

        /**
         * Index of the bracket closing the interval that opens at {@code open}: the first {@code ]},
         * {@code )} or {@code [} after its {@code ..}, outside strings and parentheses.
         */
        private int endOfInterval(String text, int open) {
            int dots = nextOutsideStringsAndParentheses(text, open + 1, i -> text.startsWith("..", i));
            int close = nextOutsideStringsAndParentheses(text, dots + 2, i -> "])[".indexOf(text.charAt(i)) >= 0);
            if (close == text.length()) {
                throw new CellSyntaxException(cell, "unterminated interval");
            }
            return close;
        }

        /**
         * The first index from {@code from} that is outside strings and parentheses and is {@code wanted},
         * or the text's length if there is none. Unlike {@link #endOfTest}, a {@code )} with none open is
         * skipped here, since it may be the closing bracket being looked for.
         */
        private static int nextOutsideStringsAndParentheses(String text, int from, IntPredicate wanted) {
            int depth = 0;
            int i = from;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (depth == 0 && wanted.test(i)) {
                    return i;
                }
                if (c == '"') {
                    i = closingQuote(text, i);
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                }
                i++;
            }
            return text.length();
        }

        /** Index of the quote closing the string that opens at {@code open}, or the text's length if there is none. */
        private static int closingQuote(String text, int open) {
            int i = open + 1;
            while (i < text.length() && text.charAt(i) != '"') {
                i += text.charAt(i) == '\\' ? 2 : 1; // a backslash escapes the next character
            }
            return Math.min(i, text.length());
        }

        /**
         * Index of the first {@code ..} outside quotes, after the opening bracket and before the closing
         * one, or -1 if there is none. Unlike {@link #closingQuote}, a backslash escapes nothing here:
         * every quote opens or closes a string, as it always has.
         */
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
}
