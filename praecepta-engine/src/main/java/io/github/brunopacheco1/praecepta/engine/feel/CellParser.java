/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
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
 *   "a\tb", "\U01F40E"       escape sequences in quoted strings: \" \\ \' \n \r \t and code points (FEEL rule 64)
 *   18, 2.5, .5, 1.2e3       number literals, with an optional exponent (NUMBER columns)
 *   date("2025-01-31")       date literal (DATE columns; a bare 2025-01-31 is accepted as well)
 *   &#64;"2025-01-31", date(2025, 1, 31), time(8, 30, 0), date and time(date("2025-01-31"), time("08:30:00"))
 *                            the other date time literals: at-literals and the forms of the date and time
 *                            functions (DMN 1.5, grammar rules 60 and 65, Table 72), in columns of their type
 *   time("08:30:00")         time literal (TIME columns; a bare 08:30:00 is accepted as well)
 *   time("08:30:00+01:00")   time with an offset (Z, +01:00), compared by instant with other such times
 *   time("08:30:00@Europe/Paris")
 *                            time with a zone id, compared by local time with times in the same zone only
 *   date and time("2025-01-31T08:30:00")
 *                            date-time literal (DATE_TIME columns; a bare 2025-01-31T08:30:00 too)
 *   date and time("2025-01-31T08:30:00Z"), date and time("2025-01-31T08:30:00@Europe/Paris")
 *                            date-time with an offset or a zone id, compared by instant with other such date-times
 *   duration("P1DT2H")       days and time duration (DAY_TIME_DURATION columns; a bare P1DT2H too)
 *   duration("P1Y2M")        years and months duration (YEAR_MONTH_DURATION columns; a bare P1Y2M too)
 *   true, false              boolean literals (BOOLEAN columns)
 *   &lt; 18, &gt;= 2.5, &lt; "N"      comparisons (all but BOOLEAN columns; strings compare by code point, {@link FeelStrings#compare})
 *   = 18, = null             the same as 18 and null
 *   != "PT", != null         anything but that value, or anything but a missing value ({@link UnaryTests.NotEqual})
 *   [18..65), (0..1], ]a..b[  intervals; [ and ] include their end, ( ) and outward-facing brackets exclude it
 *   null                     only a missing value
 *   not(t1, t2)              anything but these tests
 *   numB, &gt; dateE, [lo..hi]  strict FEEL only: a name compares with that input ({@link UnaryTests.Name})
 * </pre>
 *
 * Tests are separated by commas outside quotes and brackets. Surrounding whitespace is ignored, and so are
 * comments ({@code < 18 // minors}, {@code /* adults *&#47; >= 18}), except in a string column in Praecepta's syntax,
 * whose unquoted words keep them as text.
 * Times and date-times may have a time zone ({@link FeelTemporals}): an offset or a zone id. A value with an
 * offset and one without compare by their local time, so an interval may have one end of each kind. A time with a
 * zone id ({@code time("08:30:00@Europe/Paris")}, a {@link ZonedTime}) compares only with one in the same zone, so
 * an interval's other end must be one; one in a zone with one fixed offset ({@code time("08:30:00@Etc/UTC")}) is the
 * time at that offset (DMN 1.5, Table 72), an {@code OffsetTime}.
 *
 * <p>The same syntax serves columns that hold lists, whose cells test the elements. In strict FEEL such
 * a cell is written with FEEL's list functions instead, as in {@code list contains(?, "PT")}; see
 * {@link #parseFeelList}.
 */
public final class CellParser {

    /** Two-character operators come first, so that {@code <= 5} is not read as {@code <} and {@code = 5}. */
    private static final List<Operator> OPERATORS = List.of(Operator.LE, Operator.GE, Operator.LT, Operator.GT);
    /**
     * FEEL's numeric literal (DMN 1.5, grammar rule 35, and 10.3.2.3.1: "an optional decimal point, and an optional
     * exponent"): digits with an optional fraction, or a fraction alone, then an optional exponent.
     */
    private static final Pattern NUMBER = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern BARE_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** An offset after a bare time or date-time: Z, or a sign and hh:mm. */
    private static final String OFFSET = "([Zz]|[+-]\\d{2}:\\d{2})";
    private static final Pattern BARE_TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(" + OFFSET + "|@[\\w+\\-/]+)?");
    private static final Pattern BARE_DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(" + OFFSET + "|@[\\w+\\-/]+)?");
    /** ISO 8601 durations: an optional sign, P, then digits, fractions and designators. */
    private static final Pattern BARE_DURATION = Pattern.compile("-?P[0-9YMWDTHS.]+");
    /** Names FEEL reserves for literals, which never name an input. */
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null");

    private CellParser() {
    }

    /**
     * A FEEL name, or a qualified name such as {@code applicant.age}. Each part starts with a letter,
     * {@code _} or {@code ?}, continues with letters, digits, {@code _} and {@code ?}, and may hold single
     * spaces between words, as FEEL allows: {@code Credit Score}, {@code Applicant data.Monthly income}.
     * Checked one code point at a time, so long input costs linear time and no stack.
     */
    public static boolean isName(String text) {
        int previous = '.'; // the text starts a part, as if after a dot
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            boolean separator = c == '.' || c == ' ';
            boolean afterSeparator = previous == '.' || previous == ' ';
            boolean valid = separator
                    ? !afterSeparator // no empty part, no space next to a dot, no double space
                    : isNameStart(c) || previous != '.' && Character.isDigit(c); // digits never start a part
            if (!valid) {
                return false;
            }
            previous = c;
        }
        return previous != '.' && previous != ' ';
    }

    private static boolean isNameStart(int c) {
        return Character.isLetter(c) || c == '_' || c == '?';
    }

    /**
     * Parses a cell in Praecepta's syntax, as Excel, CSV and code tables write it: FEEL simple unary
     * tests, plus unquoted words as string literals and bare ISO dates, times and durations.
     *
     * @throws CellSyntaxException if the cell is not valid for the column type
     */
    public static UnaryTests parse(String cell, ColumnType type) {
        return new Parser(type, cell, false).parse();
    }

    /**
     * Parses a cell as strict DMN FEEL, as DMN files hold it. Strings must be quoted, and dates and
     * times written with their functions ({@code date("2025-01-31")}): in FEEL an unquoted word is a
     * name, a reference to another input ({@link UnaryTests.Name}), and a bare {@code 2025-01-31} is a
     * subtraction.
     *
     * @throws CellSyntaxException if the cell is not valid for the column type
     */
    public static UnaryTests parseFeel(String cell, ColumnType type) {
        return new Parser(type, cell, true).parse();
    }

    /**
     * Parses a cell of a column that holds lists, as a DMN file holds it. Strict FEEL compares a list
     * with a unary test as a whole ({@code "PT"} never equals {@code ["PT", "BR"]}), so there such a
     * cell tests the elements with FEEL's list functions instead, joined with {@code or}:
     *
     * <pre>
     *   list contains(?, "PT")                  some element is "PT"
     *   list contains(?, null)                  some element is missing
     *   some x in ? satisfies x &gt;= 18          some element compares that way
     *   some x in ? satisfies x in [18..65)     some element lies in the interval
     *   ? = null                                the list is missing
     *   not(...)                                none of these holds
     *   -                                       any list
     * </pre>
     *
     * The result holds the tests that Praecepta's syntax writes {@code "PT", >= 18, [18..65), null},
     * which a list column reads the same way: some element passes one of the tests (see
     * {@code DecisionTable#listInputs}), and {@code null} asks whether the list is missing.
     * {@code list contains(?, null)} becomes a {@link UnaryTests.NullElement}, for which Praecepta's syntax has
     * no form. A list column's cells test its elements against literals, never against another input.
     *
     * @param type the type of the list's elements
     * @throws CellSyntaxException if a part of the cell is none of these forms, or a value is not valid for the type
     */
    public static UnaryTests parseFeelList(String cell, ColumnType type) {
        return new Parser(type, cell, true).parseList();
    }

    /**
     * One cell being parsed for a column type; {@code strictFeel} turns off Praecepta's shorter forms.
     * The cell is split into tests, each test is read as {@code null}, a comparison, an interval or a
     * literal, and each literal as a value of the column type. The scanners that find where a test
     * ends come last.
     */
    private record Parser(ColumnType type, String cell, boolean strictFeel) {

        UnaryTests parse() {
            var text = text();
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

        /**
         * The cell without its comments ({@code //} to the end of the line, {@code /* ... *&#47;}), as FEEL allows them,
         * and stripped. In Praecepta's syntax a string column keeps them: an unquoted word is text there, and may hold
         * {@code //}, as a URL does.
         */
        private String text() {
            if (cell == null) {
                return "";
            }
            if (!strictFeel && type == ColumnType.STRING) {
                return cell.strip();
            }
            try {
                return FeelStrings.withoutComments(cell).strip();
            } catch (IllegalArgumentException e) {
                throw new CellSyntaxException(cell, e.getMessage());
            }
        }

        // ---- list columns ------------------------------------------------------------------------

        /** A list column's cell: {@code list contains(?, v)} and the other forms of {@link #parseFeelList}. */
        UnaryTests parseList() {
            var text = text();
            if (text.isEmpty() || text.equals("-")) {
                return new UnaryTests.Any();
            }
            if (text.startsWith("not(") && text.endsWith(")")) {
                return new UnaryTests.Negated(listTests(text.substring("not(".length(), text.length() - 1)));
            }
            return new UnaryTests.Positive(listTests(text));
        }

        private List<Test> listTests(String text) {
            var tests = new ArrayList<Test>();
            for (var part : splitDisjunction(text)) {
                var test = listTest(part.strip());
                var names = UnaryTests.names(test);
                if (!names.isEmpty()) {
                    throw new CellSyntaxException(cell, "a list column's cells test its elements against literals, not against '"
                            + names.getFirst() + "'");
                }
                tests.add(test);
            }
            return tests;
        }

        /** One disjunct of a list column's cell, as the test it applies to each element. */
        private Test listTest(String text) {
            var contains = containsTest(text);
            if (contains != null) {
                return contains;
            }
            if ("null".equals(after(after(text, "?"), "="))) {
                return new NullTest();
            }
            var some = someTest(text);
            if (some != null) {
                return some;
            }
            throw new CellSyntaxException(cell, "'" + text + "' does not test a list's elements; write list contains(?, value), "
                    + "list contains(?, null), some x in ? satisfies x < value, some x in ? satisfies x in [low..high] or ? = null");
        }

        /**
         * {@code list contains(?, value)}: an element equals the value, or with {@code null}, an element is
         * missing (DMN 1.5, Table 75: the element is "any element of the semantic domain including null");
         * null if the text is not that.
         */
        private Test containsTest(String text) {
            var contains = after(text, "list contains(");
            if (contains == null || !contains.endsWith(")")) {
                return null;
            }
            var argument = after(after(contains.substring(0, contains.length() - 1), "?"), ",");
            if (argument == null || argument.isEmpty()) {
                return null;
            }
            return "null".equals(argument) ? new UnaryTests.NullElement() : new Equals(literal(argument));
        }

        /**
         * {@code some x in ? satisfies x < value} or {@code some x in ? satisfies x in [low..high]}: an element
         * passes the comparison or lies in the interval; null if the text is not that.
         */
        private Test someTest(String text) {
            var some = after(text, "some");
            var element = some == null ? "" : firstWord(some);
            if (!isName(element) || KEYWORDS.contains(element)) {
                return null;
            }
            var test = after(after(after(after(after(some, element), "in"), "?"), "satisfies"), element);
            if (test == null) {
                return null;
            }
            var interval = after(test, "in");
            if (interval != null && !interval.isEmpty() && isBracketed(interval) && topLevelDots(interval) > 0) {
                return interval(interval, topLevelDots(interval));
            }
            if (OPERATORS.stream().anyMatch(operator -> test.startsWith(operator.symbol())) || test.startsWith("=")
                    || test.startsWith("!=")) {
                return test(test);
            }
            return null;
        }

        /** The text up to its first whitespace. */
        private static String firstWord(String text) {
            int end = 0;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            return text.substring(0, end);
        }

        /**
         * The text after {@code prefix}, both stripped, or null if the text is null or does not start with
         * it. A prefix ending in a letter must end a word: {@code in} does not match {@code inside}.
         */
        private static String after(String text, String prefix) {
            if (text == null) {
                return null;
            }
            var stripped = text.strip();
            if (!stripped.startsWith(prefix)) {
                return null;
            }
            var rest = stripped.substring(prefix.length());
            boolean word = Character.isLetterOrDigit(prefix.charAt(prefix.length() - 1));
            if (word && !rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
                return null;
            }
            return rest.strip();
        }

        /**
         * Splits a list column's cell at each {@code or} outside strings, parentheses and intervals.
         * Intervals are skipped as units, since their brackets need not pair up ({@code (1..2]}); a
         * parenthesis right after a letter opens a call ({@code list contains(}), any other one an interval.
         */
        private List<String> splitDisjunction(String text) {
            var parts = new ArrayList<String>();
            int depth = 0;
            int start = 0;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"') {
                    i = closingQuote(text, i);
                    if (i == text.length()) {
                        throw new CellSyntaxException(cell, "unterminated string");
                    }
                } else if (c == '[' || c == ']' || c == '(' && !afterLetter(text, i)) {
                    i = endOfInterval(text, i);
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && isOr(text, i)) {
                    parts.add(text.substring(start, i));
                    start = i + 3;
                    i = start;
                    continue;
                }
                i++;
            }
            parts.add(text.substring(start));
            return parts;
        }

        /** Whether the character just before {@code i} is a letter, as in {@code contains(}; {@code in (1..2]} is an interval. */
        private static boolean afterLetter(String text, int i) {
            return i > 0 && Character.isLetter(text.charAt(i - 1));
        }

        /** Whether {@code or} with whitespace on both sides starts at the whitespace at {@code i}. */
        private static boolean isOr(String text, int i) {
            return Character.isWhitespace(text.charAt(i)) && text.startsWith("or", i + 1) && i + 3 < text.length()
                    && Character.isWhitespace(text.charAt(i + 3));
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
            if (text.startsWith("!=")) {
                return new NotEqual(equality(text.substring("!=".length()).strip(), false));
            }
            if (text.startsWith("=")) {
                return equality(text.substring("=".length()).strip(), true);
            }
            if (isBracketed(text)) {
                int dots = topLevelDots(text);
                if (dots > 0) {
                    return interval(text, dots);
                }
            }
            return new Equals(literal(text));
        }

        /**
         * The value after {@code =} or {@code !=}: {@code null}, which {@code = null} tests as the cell {@code null}
         * does (DMN 1.5, 10.3.2.15: "e = null" is "FEEL(e) is null"), or a value, which {@code = x} tests as the cell
         * {@code x} does; a name after {@code =} is kept {@linkplain Equals#explicit explicit}, as a bare name of a list
         * input is not an equality.
         */
        private Test equality(String text, boolean afterEquals) {
            return text.equals("null") ? new NullTest() : new Equals(literal(text), afterEquals);
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
            if (!(low instanceof UnaryTests.Name) && !(high instanceof UnaryTests.Name)) {
                // a time with a zone id orders only with one in its zone: other ends could never be ordered (10.3.2.7)
                if (!FeelTemporals.comparable(low, high)) {
                    throw new CellSyntaxException(cell, "the ends of an interval must compare; a time with a zone id compares only "
                            + "with one in the same zone");
                }
                if (FeelTemporals.compare(low, high) > 0) {
                    throw new CellSyntaxException(cell, "interval starts after it ends");
                }
            }
            return new Range(low, text.charAt(0) == '[', high, text.charAt(text.length() - 1) == ']');
        }

        private void requireComparisons(String what) {
            if (!type.supportsComparisons()) {
                throw new CellSyntaxException(cell, what + " are not supported on " + type + " columns");
            }
        }

        /** A single value of the column's type, or in strict FEEL a name of another input. */
        private Comparable<?> literal(String text) {
            if (text.isEmpty()) {
                throw new CellSyntaxException(cell, "missing value");
            }
            if (strictFeel && isName(text) && !KEYWORDS.contains(text)) {
                return new UnaryTests.Name(text);
            }
            return switch (type) {
                case STRING -> string(text);
                case NUMBER -> number(text);
                case BOOLEAN -> bool(text);
                case DATE -> temporal(text, BARE_DATE, FeelTemporals::parseDate, "date(\"yyyy-mm-dd\")");
                case TIME -> temporal(text, BARE_TIME, iso -> (Comparable<?>) FeelTemporals.parseTime(iso), "time(\"hh:mm:ss\")");
                case DATE_TIME -> temporal(text, BARE_DATE_TIME, iso -> (Comparable<?>) FeelTemporals.parseDateTime(iso),
                        "date and time(\"yyyy-mm-ddThh:mm:ss\")");
                case DAY_TIME_DURATION -> temporal(text, BARE_DURATION, Duration::parse, "duration(\"P1DT2H\")");
                case YEAR_MONTH_DURATION -> temporal(text, BARE_DURATION, Parser::yearsAndMonths, "duration(\"P1Y2M\")");
            };
        }

        /** A years and months duration; one with days (or weeks) is not one, so it fails to parse like any other. */
        private static YearsAndMonths yearsAndMonths(String iso) {
            try {
                return YearsAndMonths.parse(iso);
            } catch (IllegalArgumentException e) {
                throw new DateTimeParseException(e.getMessage(), iso, 0, e);
            }
        }

        private String string(String text) {
            if (text.startsWith("\"")) {
                return quoted(text);
            }
            if (strictFeel) {
                throw new CellSyntaxException(cell, "'" + text + "' is not a string literal; quote it");
            }
            if (hasFeelSyntax(text)) {
                throw new CellSyntaxException(cell, "unexpected characters in '" + text + "'; quote the text");
            }
            return text; // Praecepta's original unquoted syntax
        }

        /**
         * The contents of a quoted string, with FEEL's escape sequences ({@link FeelStrings#escape}):
         * {@code \"}, {@code \\}, {@code \'}, {@code \n}, {@code \r}, {@code \t}, and a code point
         * ({@code \U01F40E}). A backslash before any other character is rejected, and so, in strict FEEL, is a
         * line break: a string literal may not hold vertical space (U+000A to U+000D, grammar rules 33 and 62),
         * which is written {@code \n} or {@code \r}.
         */
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
                if (strictFeel && FeelStrings.isVerticalSpace(c)) {
                    throw new CellSyntaxException(cell, FeelStrings.VERTICAL_SPACE + " in " + text);
                }
                if (c != '\\') {
                    value.append(c);
                    i++;
                } else if (i + 1 < end) {
                    i = FeelStrings.escape(text, i + 1, value, problem -> new CellSyntaxException(cell, problem + " in " + text));
                } else {
                    throw new CellSyntaxException(cell, "unterminated string " + text); // the backslash escapes the closing quote
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
            if (NUMBER.matcher(text).matches()) {
                BigDecimal number;
                try {
                    number = new BigDecimal(text);
                } catch (NumberFormatException e) {
                    throw new CellSyntaxException(cell, "'" + text + "' is not a number"); // beyond what a BigDecimal holds
                }
                if (!FeelNumbers.isDecimal128(number)) {
                    throw new CellSyntaxException(cell, "'" + text + "' is beyond FEEL's numbers (decimal128)");
                }
                return FeelNumbers.plain(number);
            }
            throw new CellSyntaxException(cell, "'" + text + "' is not a number");
        }

        private Boolean bool(String text) {
            return switch (text) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw new CellSyntaxException(cell, "'" + text + "' is not a boolean; write true or false");
            };
        }

        /**
         * A date, time, date-time or duration: a date time literal ({@link TemporalLiterals}: an at-literal, or an
         * invocation of the date and time functions) of the column's type, or, outside strict FEEL, the bare ISO text.
         * Strict FEEL cannot take the bare form, as it reads {@code 2025-01-31} as {@code 2025 - 1 - 31}.
         */
        private Comparable<?> temporal(String text, Pattern bare, Function<String, Comparable<?>> parse, String hint) {
            if (TemporalLiterals.startsAt(text, 0)) {
                return dateTimeLiteral(text);
            }
            if (strictFeel || !bare.matcher(text).matches()) {
                throw new CellSyntaxException(cell, "'" + text + "' is not a " + type.feelName() + "; write " + hint);
            }
            try {
                return parse.apply(text);
            } catch (DateTimeParseException e) {
                throw new CellSyntaxException(cell, "invalid " + type.feelName() + " '" + text + "'");
            }
        }

        /** A date time literal that is the whole text, of the column's type. */
        private Comparable<?> dateTimeLiteral(String text) {
            TemporalLiterals.Read read;
            try {
                read = TemporalLiterals.read(text, 0, type);
            } catch (IllegalArgumentException e) {
                throw new CellSyntaxException(cell, e.getMessage());
            }
            if (read.end() < text.length()) {
                throw new CellSyntaxException(cell, "unexpected '" + text.substring(read.end()).strip() + "' after a " + type.feelName());
            }
            var literalType = TemporalLiterals.typeOf(read.value());
            if (literalType != type) {
                throw new CellSyntaxException(cell, "'" + text + "' is a " + literalType.feelName() + ", not a " + type.feelName());
            }
            return (Comparable<?>) read.value();
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
         * Index of the first {@code ..} outside strings, after the opening bracket and before the closing
         * one, or -1 if there is none. As in {@link #closingQuote}, a backslash inside a string escapes
         * the next character, so the escaped quotes {@code DmnWriter} writes into a string end, as in
         * {@code ["a\"..b".."c"]}, do not end it.
         */
        private static int topLevelDots(String text) {
            int i = 1;
            while (i < text.length() - 2) {
                char c = text.charAt(i);
                if (c == '"') {
                    i = closingQuote(text, i);
                } else if (c == '.' && text.charAt(i + 1) == '.') {
                    return i;
                }
                i++;
            }
            return -1;
        }
    }
}
