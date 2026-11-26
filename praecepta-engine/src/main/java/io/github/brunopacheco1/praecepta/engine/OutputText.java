/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;

/**
 * Writes the values of outputs as text, and compares outputs by their text. An output strategy takes a rule's outputs
 * as text ({@link PraeceptaOutput#values()}), so a computed value is written out for it ({@link #of}); and the ANY hit
 * policy, output priority lists and the static analysis compare number outputs by value, so that {@code 1.50} is
 * {@code 1.5} ({@link #comparable}, {@link #sameValue}).
 */
final class OutputText {

    private OutputText() {
    }

    /** The deepest lists and contexts an output's text writes out; deeper ones are written {@code [...]} or <code>{...}</code>. */
    private static final int MAX_TEXT_DEPTH = 64;
    /**
     * The most characters an output's text writes of a list or a context; the rest is written {@code ...}. A value may
     * hold one list many times over ({@code {a: [1, 1], b: [a, a], c: [b, b]}.c}), which its text writes out each time,
     * so the text of a small value could be exponentially long.
     */
    static final int MAX_TEXT_LENGTH = 1 << 16;

    /**
     * The text of an output's value: a simple value's plain text ({@link FeelLiterals#text}), and a list, a context or a
     * range as FEEL writes it, its values as literals ({@code ["PT", "BR"]}, {@code {a: 1}}), as list inputs are read
     * from text; of a list or a context, at most {@value #MAX_TEXT_LENGTH} characters, then {@code ...}.
     */
    static String of(Object value) {
        if (!(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
            return FeelLiterals.text(value);
        }
        var text = new StringBuilder();
        if (!appendLiteral(text, value, 0)) {
            text.setLength(Math.min(text.length(), MAX_TEXT_LENGTH));
            text.append("...");
        }
        return text.toString();
    }

    /**
     * Writes a value as a literal. Each call writes a character at least before it calls itself, so the calls are no
     * more than the characters written, which {@link #MAX_TEXT_LENGTH} bounds, whatever the value's size.
     *
     * @return false if the text reached {@link #MAX_TEXT_LENGTH} characters before the value was written out
     */
    private static boolean appendLiteral(StringBuilder text, Object value, int depth) {
        if (text.length() >= MAX_TEXT_LENGTH) {
            return false;
        }
        if (value instanceof List<?> list) {
            if (depth >= MAX_TEXT_DEPTH) {
                text.append("[...]");
                return true;
            }
            text.append('[');
            var separator = "";
            for (var element : list) {
                text.append(separator);
                separator = ", ";
                if (!appendLiteral(text, element, depth + 1)) {
                    return false;
                }
            }
            text.append(']');
            return true;
        }
        if (value instanceof Map<?, ?> context) {
            return appendContext(text, context, depth);
        }
        text.append(value == null || isSimpleValue(value) ? FeelLiterals.literal(value) : value.toString());
        return true;
    }

    private static boolean appendContext(StringBuilder text, Map<?, ?> context, int depth) {
        if (depth >= MAX_TEXT_DEPTH) {
            text.append("{...}");
            return true;
        }
        text.append('{');
        var separator = "";
        for (var entry : context.entrySet()) {
            text.append(separator).append(FeelLiterals.literal(String.valueOf(entry.getKey()))).append(": ");
            separator = ", ";
            if (!appendLiteral(text, entry.getValue(), depth + 1)) {
                return false;
            }
        }
        text.append('}');
        return true;
    }

    /** Whether a value is one of a simple type, which {@link FeelLiterals#literal} writes. */
    private static boolean isSimpleValue(Object value) {
        return Arrays.stream(ColumnType.values()).anyMatch(type -> GraphValues.isValueOf(value, type));
    }

    /** A number as a computed output is written: without trailing zeros or an exponent, and 0 as {@code 0}. */
    static String numberText(BigDecimal number) {
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    /** Whether two texts of an output are the same value: for a number output, the same number ({@code 1.50} is {@code 1.5}). */
    static boolean sameValue(String a, String b, boolean number) {
        return number && a != null && b != null ? Objects.equals(normalisedNumber(a), normalisedNumber(b)) : Objects.equals(a, b);
    }

    /**
     * A rule's outputs as ANY compares them: as written, but each number output's value as a computed
     * number is written ({@link #numberText}), so that {@code 1.50} and {@code 1.5} are the same; text that
     * is not a number is kept.
     */
    static PraeceptaOutput comparable(PraeceptaOutput output, Set<String> numericOutputs) {
        if (numericOutputs.isEmpty()) {
            return output;
        }
        var values = new HashMap<>(output.values());
        values.replaceAll((column, text) -> numericOutputs.contains(column) && text != null ? normalisedNumber(text) : text);
        return new PraeceptaOutput(values, output.references(), output.expressions());
    }

    private static String normalisedNumber(String text) {
        try {
            return numberText(new BigDecimal(text.strip()));
        } catch (NumberFormatException e) {
            return text;
        }
    }
}
