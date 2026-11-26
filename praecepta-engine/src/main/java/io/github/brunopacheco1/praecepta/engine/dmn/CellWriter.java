/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * Writes a table's cells and values as standard FEEL: unary tests ({@code "PT", >= 18}), the tests a list column's cell
 * applies to the list's elements, and an output value as a literal of its column's type.
 */
final class CellWriter {

    private CellWriter() {
    }

    /** Standard FEEL text for parsed unary tests, as {@link DmnWriter#render} gives it. */
    static String render(UnaryTests tests) {
        return switch (tests) {
            case UnaryTests.Any() -> "-";
            case UnaryTests.Positive(var positive) -> join(positive);
            case UnaryTests.Negated(var negated) -> "not(" + join(negated) + ")";
        };
    }

    /** Standard FEEL text for the cell of a column that holds lists, as {@link DmnWriter#renderList} gives it. */
    static String renderList(UnaryTests tests) {
        return switch (tests) {
            case UnaryTests.Any() -> "-";
            case UnaryTests.Positive(var positive) -> joinList(positive);
            case UnaryTests.Negated(var negated) -> "not(" + joinList(negated) + ")";
        };
    }

    /**
     * A cell in Praecepta's syntax, or already strict FEEL (a DMN table's), as standard FEEL.
     *
     * @param list whether the column holds lists, whose cells test the elements
     */
    static String standardFeel(String cell, ColumnType type, boolean feel, boolean list, String where) {
        try {
            if (list) {
                return renderList(feel ? CellParser.parseFeelList(cell, type) : CellParser.parse(cell, type));
            }
            return render(feel ? CellParser.parseFeel(cell, type) : CellParser.parse(cell, type));
        } catch (CellSyntaxException e) {
            if (!feel) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
            return beyondSimple(cell, e, where);
        }
    }

    /** A strict FEEL cell beyond the simple unary tests ({@link FeelUnaryTests}): as written, once checked. */
    private static String beyondSimple(String cell, CellSyntaxException rejected, String where) {
        try {
            // no names in scope, nor types: its names are read as words and its types as any, which is all checking it needs
            FeelUnaryTests.parseCell(cell, FeelExpression.namesScope(List.of()), rejected);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
        return cell.strip();
    }

    private static String join(List<Test> tests) {
        return tests.stream().map(CellWriter::test).collect(Collectors.joining(", "));
    }

    private static String joinList(List<Test> tests) {
        return tests.stream().map(CellWriter::listTest).collect(Collectors.joining(" or "));
    }

    /** A test of a list column's cell, applied to the list's elements. */
    private static String listTest(Test test) {
        return switch (test) {
            case NullTest() -> "? = null";
            case UnaryTests.NullElement() -> "list contains(?, null)";
            case Equals(var value, var explicit) -> "list contains(?, " + literal(value) + ")";
            case Compare c -> "some x in ? satisfies x " + test(c);
            case NotEqual n -> "some x in ? satisfies x " + test(n);
            case Range r -> "some x in ? satisfies x in " + test(r);
        };
    }

    private static String test(Test test) {
        return switch (test) {
            case NullTest() -> "null";
            // only CellParser.parseFeelList reads it, for a list column, whose cells renderList writes
            case UnaryTests.NullElement() -> throw new IllegalArgumentException("list contains(?, null) only tests a list's elements");
            case Equals(var value, var explicit) -> (explicit ? "= " : "") + literal(value); // = home is not home of a list
            case NotEqual(var negated) -> "!= " + test(negated);
            case Compare(var operator, var value) -> operator.symbol() + " " + literal(value);
            case Range(var low, var lowClosed, var high, var highClosed) -> (lowClosed ? "[" : "(") + literal(low) + ".."
                    + literal(high) + (highClosed ? "]" : ")");
        };
    }

    /** A value as FEEL writes it: a literal, or the bare name of another input. */
    private static String literal(Comparable<?> value) {
        return value instanceof UnaryTests.Name(var name) ? name : FeelLiterals.literal(value);
    }

    /** An output value (plain text) as a FEEL literal of its column type. */
    static String outputLiteral(String value, ColumnType type, String where) {
        if (value == null) {
            return "null";
        }
        var text = value.strip();
        try {
            return FeelLiterals.literal(switch (type) {
                case STRING -> value;
                case NUMBER -> new BigDecimal(text);
                case DATE -> FeelTemporals.parseDate(text);
                case TIME -> FeelTemporals.parseTime(text);
                case DATE_TIME -> FeelTemporals.parseDateTime(text);
                case DAY_TIME_DURATION -> Duration.parse(text);
                case YEAR_MONTH_DURATION -> YearsAndMonths.parse(text);
                case BOOLEAN -> bool(text);
            });
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not a %s".formatted(where, value, type.feelName()));
        }
    }

    /** Parses {@code true} or {@code false}; like the parsers of the other types, it throws an {@link IllegalArgumentException} otherwise. */
    private static Boolean bool(String text) {
        if (!text.equals("true") && !text.equals("false")) {
            throw new IllegalArgumentException("not a boolean: " + text);
        }
        return Boolean.valueOf(text);
    }
}
