/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.util.List;
import java.util.Optional;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads the simple values a decision table holds: a value list, as FEEL's simple unary tests, and an output entry that
 * is a single literal, as plain text.
 */
final class Literals {

    /** Literal types an unquoted output entry of an untyped (string) output may hold, kept as their text. */
    private static final List<ColumnType> UNQUOTED_OUTPUT_TYPES =
            List.of(ColumnType.NUMBER, ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.TIME, ColumnType.DATE_TIME);

    private Literals() {
    }

    /** A literal output entry's value as plain text ({@link FeelLiterals#text}), null for {@code null}. */
    record LiteralOutput(String text) {
    }

    /** Parses strict FEEL unary tests, naming {@code where} they are if they are invalid. */
    static UnaryTests parseCell(String text, ColumnType type, String where) {
        try {
            return CellParser.parseFeel(text, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /**
     * A literal output entry, or null if the entry is not one. An unquoted entry of a string output may
     * still be a number, boolean, date, time or date-time literal.
     */
    static LiteralOutput literalOutput(String text, ColumnType type) {
        var literal = text.strip();
        if (isNullEntry(literal, type)) {
            return new LiteralOutput(null);
        }
        var types = type == ColumnType.STRING && !literal.startsWith("\"") ? UNQUOTED_OUTPUT_TYPES : List.of(type);
        for (var literalType : types) {
            var value = literalValue(literal, literalType);
            if (value.isPresent()) {
                return new LiteralOutput(FeelLiterals.text(value.get()));
            }
        }
        return null;
    }

    /**
     * The type of an entry that is a single literal: a string for a quoted one, else a number, a boolean, a date, a time
     * or a date and time ({@code 10} is a number, as FEEL reads it); empty if it is no such literal.
     */
    static Optional<ColumnType> typeOf(String literal) {
        var candidates = literal.startsWith("\"") ? List.of(ColumnType.STRING) : UNQUOTED_OUTPUT_TYPES;
        return candidates.stream().filter(candidate -> literalValue(literal, candidate).isPresent()).findFirst();
    }

    /** Whether an entry gives no value: a blank one, or FEEL's {@code null}, with or without comments around it. */
    static boolean isNullEntry(String literal, ColumnType type) {
        return literal.isEmpty() || literal.equals("null") || isNull(literal, type);
    }

    /** Whether an entry is FEEL's {@code null} with comments around it, as {@code null // no fee}. */
    private static boolean isNull(String literal, ColumnType type) {
        try {
            return CellParser.parseFeel(literal, type) instanceof UnaryTests.Positive(var tests) && tests.equals(List.of(new UnaryTests.NullTest()));
        } catch (CellSyntaxException e) {
            return false;
        }
    }

    /** The value of an entry that is a single literal of the type (no list, comparison or name). */
    private static Optional<Object> literalValue(String literal, ColumnType type) {
        try {
            var values = CellParser.parseFeel(literal, type).values().orElse(List.of());
            if (values.size() == 1) {
                return Optional.of(values.getFirst());
            }
        } catch (CellSyntaxException e) {
            // not a literal of this type
        }
        return Optional.empty();
    }
}
