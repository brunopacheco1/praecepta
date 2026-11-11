/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.function.Function;

/**
 * FEEL strings: their order, by character, and their escape sequences, read the same way in cells
 * ({@link CellParser}) and output expressions ({@link OutputExpression}), and written back by
 * {@link FeelLiterals#literal}.
 *
 * <p>A FEEL string is a sequence of Unicode characters, that is of code points (DMN 1.5, 10.3.2.3.2), and
 * strings are ordered lexicographically by character (Table 54). Java's {@link String#compareTo} orders
 * them by UTF-16 code unit instead, which differs where a character beyond U+FFFF, written as two
 * surrogates (U+D800 to U+DFFF), meets one from U+E000 to U+FFFF: {@code "🐎"} (U+1F40E) comes after
 * {@code "～"} (U+FF5E) by character, but before it by code unit.
 */
public final class FeelStrings {

    private FeelStrings() {
    }

    /**
     * Orders two strings as FEEL does: lexicographically by code point, so a string comes before every
     * longer string it starts. An unpaired surrogate counts as the code point of its value.
     *
     * @return a negative number, zero or a positive number as {@code a} is before, equal to or after {@code b}
     */
    public static int compare(String a, String b) {
        int length = Math.min(a.length(), b.length());
        int i = 0;
        while (i < length) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(i);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
        }
        return Integer.compare(a.length() - i, b.length() - i);
    }

    /**
     * Appends the character an escape sequence of a string literal stands for (DMN 1.5, grammar rule 64):
     * {@code \'}, {@code \"}, {@code \\}, {@code \n}, {@code \r}, {@code \t}, or a code point (10.3.1.1),
     * a {@code u} and four hexadecimal digits or a {@code U} and six. Four digits may name half of a
     * surrogate pair, so two such sequences in a row may stand for one character beyond U+FFFF, as six
     * digits do. A backslash before any other character is not an escape sequence.
     *
     * @param text  the text that holds the literal
     * @param at    the index just after the backslash, which must be within the text
     * @param value where the character is appended
     * @param error the exception for a problem, given a description of it
     * @return the index just after the sequence
     */
    static int escape(String text, int at, StringBuilder value, Function<String, ? extends RuntimeException> error) {
        char kind = text.charAt(at);
        switch (kind) {
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case '\'', '"', '\\' -> value.append(kind);
            case 'u', 'U' -> {
                int digits = kind == 'u' ? 4 : 6;
                int end = at + 1 + digits;
                boolean hexadecimal = end <= text.length() && text.substring(at + 1, end).chars().allMatch(c -> Character.digit(c, 16) >= 0);
                int codePoint = hexadecimal ? Integer.parseInt(text, at + 1, end, 16) : -1;
                if (!Character.isValidCodePoint(codePoint)) {
                    throw error.apply("\\" + kind + " is not followed by the " + digits + " hexadecimal digits of a character");
                }
                value.appendCodePoint(codePoint);
                return end;
            }
            default -> throw error.apply("\\" + kind + " is not an escape sequence; write \\\\ for a backslash");
        }
        return at + 1;
    }

    /**
     * A string as the body of a FEEL string literal: a backslash and a quote escaped, and the characters
     * a literal may not hold, vertical space (U+000A to U+000D, grammar rules 33 and 62), written as escape
     * sequences, as is a tab: {@code \n}, {@code \r}, {@code \t}, and a code point for the others.
     */
    static String escaped(String text) {
        var escaped = new StringBuilder(text.length() + 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case 0x0B, 0x0C -> escaped.append("\\u%04X".formatted((int) c)); // a vertical tab and a form feed
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
