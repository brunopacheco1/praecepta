/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a FEEL expression into tokens (DMN 1.5, 10.3.1.2): words that make up names and keywords, numeric
 * literals (grammar rule 37, with an optional exponent, {@code 1.2e3}), string literals with their escape sequences
 * (rule 35 and 64), and punctuation and operators. Comments ({@code //} to the end of the line,
 * {@code /* ... *&#47;}) and whitespace (rule 32) separate tokens; each token records whether whitespace came before
 * it, as a name with spaces ({@code Full Name}) is told from a name with an additional name symbol
 * ({@code Date-Time}) by it. It runs in one loop over the text, so long input costs no stack.
 */
final class FeelLexer {

    /** What a token is. */
    enum Kind {
        /** Letters, digits, {@code _}, {@code ?} and {@code '}, starting with a letter, {@code _} or {@code ?}. */
        WORD,
        NUMBER,
        /** A string literal; {@link Token#text} is its value, escapes resolved. */
        STRING,
        /** Punctuation or an operator: {@code ( ) [ ] { } , : . .. + - * ** / = != < <= > >= -> @}. */
        SYMBOL,
        END
    }

    /**
     * A token.
     *
     * @param text        its text; a string literal's value
     * @param start       where it starts in the expression
     * @param spaceBefore whether whitespace or a comment comes just before it
     */
    record Token(Kind kind, String text, int start, boolean spaceBefore) {

        boolean is(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }
    }

    private static final String[] SYMBOLS = {"**", "..", "!=", "<=", ">=", "->", "(", ")", "[", "]", "{", "}", ",", ":", ".",
        "+", "-", "*", "/", "=", "<", ">", "@"};

    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private int position;
    private boolean space;

    private FeelLexer(String text) {
        this.text = text;
    }

    /**
     * The tokens of an expression, ending with an {@link Kind#END} token.
     *
     * @throws ExpressionSyntaxException if it has a character FEEL does not take, an unterminated string or comment,
     *                                   or a line break in a string
     */
    static List<Token> tokens(String text) {
        var lexer = new FeelLexer(text);
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        while (true) {
            skipSpaceAndComments();
            if (position >= text.length()) {
                tokens.add(new Token(Kind.END, "", position, space));
                return;
            }
            int start = position;
            char c = text.charAt(position);
            if (c == '"') {
                add(Kind.STRING, string(), start);
            } else if (isDigit(c) || c == '.' && isDigitAt(position + 1)) {
                add(Kind.NUMBER, number(), start);
            } else if (isNameStart(text.codePointAt(position))) {
                add(Kind.WORD, word(), start);
            } else {
                add(Kind.SYMBOL, symbol(), start);
            }
        }
    }

    private void add(Kind kind, String value, int start) {
        tokens.add(new Token(kind, value, start, space));
        space = false;
    }

    private void skipSpaceAndComments() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                position++;
            } else if (text.startsWith("//", position)) {
                int end = text.indexOf('\n', position);
                position = end < 0 ? text.length() : end + 1;
            } else if (text.startsWith("/*", position)) {
                int end = text.indexOf("*/", position + 2);
                if (end < 0) {
                    throw error("a comment is not closed");
                }
                position = end + 2;
            } else {
                return;
            }
            space = true;
        }
    }

    /** A decimal digit, 0 to 9 (grammar rule 37). */
    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private boolean isDigitAt(int i) {
        return i < text.length() && isDigit(text.charAt(i));
    }

    /** A string literal's value: FEEL's escape sequences resolved, no line break allowed. */
    private String string() {
        var value = new StringBuilder();
        int i = position + 1;
        while (i < text.length() && text.charAt(i) != '"') {
            char c = text.charAt(i++);
            if (FeelStrings.isVerticalSpace(c)) {
                throw error(FeelStrings.VERTICAL_SPACE);
            }
            if (c != '\\') {
                value.append(c);
            } else if (i < text.length() && "nrt'\"\\uU".indexOf(text.charAt(i)) >= 0) {
                i = FeelStrings.escape(text, i, value, this::error);
            } else {
                // a backslash that starts no escape sequence is a character of the string (grammar rules 62 to 64), as
                // the regular expressions of the DMN TCK's 0067-feel-split-function have it ("\s")
                value.append(c);
            }
        }
        if (i >= text.length()) {
            throw error("a string is not terminated");
        }
        position = i + 1;
        return value.toString();
    }

    /** Digits with an optional fraction, or a fraction alone, and an optional exponent; {@code 1..2} is 1, {@code ..}, 2. */
    private String number() {
        int start = position;
        skipDigits();
        if (position < text.length() && text.charAt(position) == '.' && isDigitAt(position + 1)) {
            position++;
            skipDigits();
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            skipExponent();
        }
        return text.substring(start, position);
    }

    /** Moves past the digits that follow, if any. */
    private void skipDigits() {
        while (isDigitAt(position)) {
            position++;
        }
    }

    /** Moves past an exponent, from its {@code e} through an optional sign and its digits; without digits, stays at the {@code e}. */
    private void skipExponent() {
        int digit = position + 1;
        if (digit < text.length() && (text.charAt(digit) == '+' || text.charAt(digit) == '-')) {
            digit++;
        }
        if (isDigitAt(digit)) {
            position = digit;
            skipDigits();
        }
    }

    private String word() {
        int start = position;
        while (position < text.length() && isNamePart(text.codePointAt(position))) {
            position += Character.charCount(text.codePointAt(position));
        }
        return text.substring(start, position);
    }

    private String symbol() {
        for (var symbol : SYMBOLS) {
            if (text.startsWith(symbol, position)) {
                position += symbol.length();
                return symbol;
            }
        }
        throw error("unexpected '" + new String(Character.toChars(text.codePointAt(position))) + "'");
    }

    /**
     * A character that starts a name (DMN 1.5, grammar rule 28): {@code ?}, {@code _}, an ASCII letter, or a code point
     * of the ranges the rule lists, XML's name start characters, which take in the letters of every script and the code
     * points beyond U+FFFF (an emoji, in the DMN TCK's {@code 0083-feel-unicode}).
     */
    static boolean isNameStart(int c) {
        if (c < 0x80) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == '?';
        }
        for (int i = 0; i < NAME_START_RANGES.length; i += 2) {
            if (c >= NAME_START_RANGES[i] && c <= NAME_START_RANGES[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** The ranges of grammar rule 28 beyond ASCII, each a first and a last code point. */
    private static final int[] NAME_START_RANGES = {0xC0, 0xD6, 0xD8, 0xF6, 0xF8, 0x2FF, 0x370, 0x37D, 0x37F, 0x1FFF, 0x200C, 0x200D,
        0x2070, 0x218F, 0x2C00, 0x2FEF, 0x3001, 0xD7FF, 0xF900, 0xFDCF, 0xFDF0, 0xFFFD, 0x10000, 0xEFFFF};

    /**
     * A character that continues a name (rule 29): a name start, a digit, U+00B7, a combining mark of U+0300 to U+036F
     * or U+203F to U+2040; and {@code '}, one of the additional name symbols (rule 30) that is nothing else in FEEL.
     */
    static boolean isNamePart(int c) {
        return isNameStart(c) || isDigit(c) || c == '\'' || c == 0xB7 || c >= 0x300 && c <= 0x36F || c == 0x203F
                || c == 0x2040;
    }

    private ExpressionSyntaxException error(String problem) {
        return new ExpressionSyntaxException("'%s' is not a valid FEEL expression: %s".formatted(text, problem));
    }
}
