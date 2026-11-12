/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Arithmetic;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Literal;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Name;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Negation;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

/**
 * Parses {@link OutputExpression}s by recursive descent, with FEEL's precedence:
 *
 * <pre>
 *   sum     = product { ("+" | "-") product }
 *   product = power { ("*" | "/") power }
 *   power   = unary { "**" unary }
 *   unary   = "-" unary | primary
 *   primary = string | number | name | "(" sum ")"
 * </pre>
 *
 * So unary minus binds tighter than {@code **}, and {@code **} associates to the left: {@code -4 ** 2} is
 * 16 and {@code 3 ** 4 ** 5} is {@code (3 ** 4) ** 5} (DMN 1.5, 10.3.1.2). A number is FEEL's numeric
 * literal (grammar rule 35), with an optional exponent as in {@code 1.2e3}; a string takes FEEL's escape
 * sequences (rule 64): {@code \n}, {@code \r}, {@code \t}, {@code \'}, {@code \"}, {@code \\}, and a
 * code point as a backslash, {@code u} and four hexadecimal digits or {@code U} and six.
 *
 * <p>A name is a FEEL name ({@link CellParser#isName}): words of letters, digits, {@code _} and {@code ?},
 * separated by single spaces or dots, as in {@code Credit Score} or {@code Applicant.age}. A name
 * followed by {@code (} is a function call, which is not supported. The number of operators and
 * parentheses is bounded, so that evaluating a parsed expression never runs out of stack.
 */
final class ExpressionParser {

    /** At most this many operators and parentheses: the depth of the tree and of every recursion over it. */
    private static final int MAX_OPERATIONS = 1000;
    /**
     * FEEL's literals, and keywords that start or join its other expressions ({@code if a then b else c},
     * {@code for x in xs return x}): a name holding one is FEEL Praecepta does not support. Words such
     * as {@code and}, {@code in} or {@code of} may be part of an input's name, so they are left to the
     * check that a name is an input.
     */
    private static final Set<String> KEYWORDS = Set.of("null", "true", "false", "if", "then", "else", "for", "return",
            "some", "every", "satisfies", "function");

    private final String text;
    private int position;
    private int operations;

    ExpressionParser(String text) {
        this.text = text == null ? "" : text;
    }

    OutputExpression parse() {
        skipWhitespace();
        if (position == text.length()) {
            throw unsupported("it is empty");
        }
        var expression = sum();
        skipWhitespace();
        if (position < text.length()) {
            throw unsupported("unexpected '" + text.substring(position) + "'");
        }
        return expression;
    }

    private OutputExpression sum() {
        var expression = product();
        while (true) {
            skipWhitespace();
            var operator = next('+', Operator.ADD, '-', Operator.SUBTRACT);
            if (operator == null) {
                return expression;
            }
            expression = new Arithmetic(operator, expression, product());
        }
    }

    private OutputExpression product() {
        var expression = power();
        while (true) {
            skipWhitespace();
            var operator = next('*', Operator.MULTIPLY, '/', Operator.DIVIDE);
            if (operator == null) {
                return expression;
            }
            expression = new Arithmetic(operator, expression, power());
        }
    }

    private OutputExpression power() {
        var expression = unary();
        while (true) {
            skipWhitespace();
            if (!text.startsWith("**", position)) {
                return expression;
            }
            position += 2;
            count();
            expression = new Arithmetic(Operator.POWER, expression, unary());
        }
    }

    /** The operator at the current position, consumed, if it is one of the two; else null. */
    private Operator next(char first, Operator firstOperator, char second, Operator secondOperator) {
        if (position < text.length() && (text.charAt(position) == first || text.charAt(position) == second)) {
            count();
            return text.charAt(position++) == first ? firstOperator : secondOperator;
        }
        return null;
    }

    private OutputExpression unary() {
        skipWhitespace();
        if (position < text.length() && text.charAt(position) == '-') {
            position++;
            count();
            return new Negation(unary());
        }
        return primary();
    }

    private OutputExpression primary() {
        skipWhitespace();
        if (position == text.length()) {
            throw unsupported("a value is missing at the end");
        }
        char c = text.charAt(position);
        if (c == '(') {
            position++;
            count();
            var inner = sum();
            skipWhitespace();
            if (position == text.length() || text.charAt(position) != ')') {
                throw unsupported("a parenthesis is not closed");
            }
            position++;
            return inner;
        }
        if (c == '"') {
            return new Literal(string());
        }
        if (Character.isDigit(c) || c == '.' && position + 1 < text.length() && Character.isDigit(text.charAt(position + 1))) {
            return new Literal(number());
        }
        if (Character.isLetter(c) || c == '_' || c == '?') {
            return name();
        }
        throw unsupported("unexpected '" + c + "'");
    }

    /**
     * A string literal, with FEEL's escape sequences ({@link FeelStrings#escape}) and without a line break, as
     * {@code CellParser} reads one in strict FEEL.
     */
    private String string() {
        var value = new StringBuilder();
        int i = position + 1;
        while (i < text.length() && text.charAt(i) != '"') {
            char c = text.charAt(i++);
            if (FeelStrings.isVerticalSpace(c)) {
                throw unsupported(FeelStrings.VERTICAL_SPACE);
            }
            if (c != '\\') {
                value.append(c);
            } else if (i < text.length()) {
                i = FeelStrings.escape(text, i, value, this::unsupported);
            }
        }
        if (i == text.length()) {
            throw unsupported("a string is not terminated");
        }
        position = i + 1;
        return value.toString();
    }

    /** FEEL's numeric literal: digits with an optional fraction, or a fraction alone, and an optional exponent ({@code 1.2e3}). */
    private BigDecimal number() {
        int start = position;
        while (position < text.length() && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) {
            position++;
        }
        skipExponent();
        var digits = text.substring(start, position);
        try {
            return new BigDecimal(digits.startsWith(".") ? "0" + digits : digits);
        } catch (NumberFormatException e) {
            throw unsupported("'" + digits + "' is not a number");
        }
    }

    /** Moves past an exponent ({@code e3}, {@code E-2}) if one follows; an {@code e} without digits is not one. */
    private void skipExponent() {
        if (position >= text.length() || (text.charAt(position) != 'e' && text.charAt(position) != 'E')) {
            return;
        }
        int digit = position + 1;
        if (digit < text.length() && (text.charAt(digit) == '+' || text.charAt(digit) == '-')) {
            digit++;
        }
        if (digit < text.length() && Character.isDigit(text.charAt(digit))) {
            position = digit;
            while (position < text.length() && Character.isDigit(text.charAt(position))) {
                position++;
            }
        }
    }

    /**
     * A name: words of name characters joined by dots or single spaces, as long as the next word starts
     * with a letter. FEEL's keywords would read as part of a name, so they end up in an unknown name.
     */
    private OutputExpression name() {
        int start = position;
        int end = position;
        int i = position;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '?') {
                i++;
                end = i;
            } else if ((c == ' ' || c == '.') && i + 1 < text.length() && isNameStart(text.charAt(i + 1))) {
                i++;
            } else {
                break;
            }
        }
        position = end;
        var name = text.substring(start, end);
        skipWhitespace();
        if (position < text.length() && text.charAt(position) == '(') {
            throw unsupported("function calls such as " + name + "(...) are not supported");
        }
        if (!CellParser.isName(name) || Arrays.stream(name.split("[ .]")).anyMatch(KEYWORDS::contains)) {
            throw unsupported("'" + name + "' is not supported");
        }
        return new Name(name);
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '?';
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private void count() {
        if (++operations > MAX_OPERATIONS) {
            throw unsupported("it has more than " + MAX_OPERATIONS + " operators and parentheses");
        }
    }

    private ExpressionSyntaxException unsupported(String problem) {
        return new ExpressionSyntaxException("'%s' is not a supported output expression: %s".formatted(text, problem));
    }
}
