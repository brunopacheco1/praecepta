/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
 *   &lt; 18, &gt;= 2.5            comparisons (NUMBER and DATE columns)
 *   [18..65), (0..1], ]a..b[  intervals; [ and ] close, ( ) and outward-facing brackets open
 *   null                     only a missing value
 *   not(t1, t2)              anything but these tests
 * </pre>
 *
 * Tests are separated by commas outside quotes and brackets. Surrounding whitespace is ignored.
 */
public final class CellParser {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern DATE_CALL = Pattern.compile("date\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern BARE_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private CellParser() {
    }

    /**
     * @throws CellSyntaxException if the cell is not valid for the column type
     */
    public static UnaryTests parse(String cell, ColumnType type) {
        var text = cell == null ? "" : cell.strip();
        if (text.isEmpty() || text.equals("-")) {
            return new UnaryTests.Any();
        }
        // FEEL only allows not(...) around the whole cell; its contents may hold intervals like [1..2)
        if (text.startsWith("not(") && text.endsWith(")")) {
            var inner = text.substring(4, text.length() - 1);
            return new UnaryTests.Negated(tests(inner, type, cell));
        }
        return new UnaryTests.Positive(tests(text, type, cell));
    }

    private static List<Test> tests(String text, ColumnType type, String cell) {
        var tests = new ArrayList<Test>();
        for (var part : splitTopLevel(text, cell)) {
            tests.add(test(part.strip(), type, cell));
        }
        if (tests.isEmpty()) {
            throw new CellSyntaxException(cell, "no tests");
        }
        return tests;
    }

    private static Test test(String text, ColumnType type, String cell) {
        if (text.isEmpty()) {
            throw new CellSyntaxException(cell, "empty test between commas");
        }
        if (text.equals("null")) {
            return new NullTest();
        }
        for (var operator : List.of(Operator.LE, Operator.GE, Operator.LT, Operator.GT)) {
            if (text.startsWith(operator.symbol())) {
                requireOrdered(type, cell, "comparisons");
                return new Compare(operator, literal(text.substring(operator.symbol().length()).strip(), type, cell));
            }
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '[' || first == '(' || first == ']') && (last == ']' || last == ')' || last == '[')) {
            int dots = topLevelDots(text, cell);
            if (dots > 0) {
                requireOrdered(type, cell, "intervals");
                var low = literal(text.substring(1, dots).strip(), type, cell);
                var high = literal(text.substring(dots + 2, text.length() - 1).strip(), type, cell);
                if (compare(low, high) > 0) {
                    throw new CellSyntaxException(cell, "interval starts after it ends");
                }
                return new Range(low, first == '[', high, last == ']');
            }
        }
        return new Equals(literal(text, type, cell));
    }

    private static Comparable<?> literal(String text, ColumnType type, String cell) {
        if (text.isEmpty()) {
            throw new CellSyntaxException(cell, "missing value");
        }
        return switch (type) {
            case STRING -> {
                if (text.startsWith("\"")) {
                    yield quoted(text, cell);
                }
                if (text.matches(".*[\"()\\[\\]].*")) {
                    throw new CellSyntaxException(cell, "unexpected characters in '" + text + "'; quote the text");
                }
                yield text; // Praecepta's original unquoted syntax
            }
            case NUMBER -> {
                if (!NUMBER.matcher(text).matches()) {
                    throw new CellSyntaxException(cell, "'" + text + "' is not a number");
                }
                yield new BigDecimal(text);
            }
            case DATE -> {
                var call = DATE_CALL.matcher(text);
                var iso = call.matches() ? call.group(1) : BARE_DATE.matcher(text).matches() ? text : null;
                if (iso == null) {
                    throw new CellSyntaxException(cell, "'" + text + "' is not a date; write date(\"yyyy-mm-dd\")");
                }
                try {
                    yield LocalDate.parse(iso);
                } catch (DateTimeParseException e) {
                    throw new CellSyntaxException(cell, "invalid date '" + iso + "'");
                }
            }
        };
    }

    private static String quoted(String text, String cell) {
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

    private static void requireOrdered(ColumnType type, String cell, String what) {
        if (type == ColumnType.STRING) {
            throw new CellSyntaxException(cell, what + " are only supported on NUMBER and DATE columns");
        }
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

    private static int topLevelDots(String text, String cell) {
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
