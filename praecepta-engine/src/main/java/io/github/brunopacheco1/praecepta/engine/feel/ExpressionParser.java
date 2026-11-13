/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Set;
import java.util.function.Predicate;

import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Arithmetic;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Literal;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Name;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Negation;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

/**
 * Parses {@link OutputExpression}s with FEEL's precedence, as this grammar gives it:
 *
 * <pre>
 *   sum     = product { ("+" | "-") product }
 *   product = power { ("*" | "/") power }
 *   power   = unary { "**" unary }
 *   unary   = "-" unary | primary
 *   primary = string | number | date time literal | name | "(" sum ")"
 * </pre>
 *
 * So unary minus binds tighter than {@code **}, and {@code **} associates to the left: {@code -4 ** 2} is
 * 16 and {@code 3 ** 4 ** 5} is {@code (3 ** 4) ** 5} (DMN 1.5, 10.3.1.2). A number is FEEL's numeric
 * literal (grammar rule 35), with an optional exponent as in {@code 1.2e3}; a string takes FEEL's escape
 * sequences (rule 64): {@code \n}, {@code \r}, {@code \t}, {@code \'}, {@code \"}, {@code \\}, and a
 * code point as a backslash, {@code u} and four hexadecimal digits or {@code U} and six. A date time literal is an
 * at-literal or an invocation of {@code date}, {@code time}, {@code date and time} or {@code duration}, read as cells
 * read it ({@link TemporalLiterals}): {@code @"2025-01-31"}, {@code duration("P14D")}, {@code date(2025, 1, 31)}.
 * Comments ({@code //} to the end of the line, {@code /* ... *&#47;}) are ignored.
 *
 * <p>A name is a FEEL name ({@link CellParser#isName}): words of letters, digits, {@code _} and {@code ?},
 * separated by single spaces or dots, as in {@code Credit Score} or {@code Applicant.age}. A name
 * followed by {@code (} is a function call, which is not supported.
 *
 * <p>The parser reads operators and parentheses with a stack of its own (operator precedence, or shunting yard),
 * not by recursion, so however deep the parentheses nest, reading them takes no more of the thread's stack, and
 * an expression with too many is refused whatever the stack's size. The number of operators and parentheses is
 * bounded, and with it the depth of the tree, which evaluating a parsed expression recurses over, one call a level.
 */
final class ExpressionParser {

    /** At most this many operators and parentheses: a bound on the depth of the tree and of every recursion over it. */
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
    /** The operands read and the trees built from them, the last on top. */
    private final Deque<OutputExpression> operands = new ArrayDeque<>();
    /** The operators, unary minuses and open parentheses waiting for their operands, the last on top. */
    private final Deque<Pending> pending = new ArrayDeque<>();
    private int openParentheses;

    ExpressionParser(String text) {
        this.text = text == null ? "" : withoutComments(text);
    }

    /** The expression without FEEL's comments, as {@link FeelStrings#withoutComments} removes them. */
    private static String withoutComments(String text) {
        try {
            return FeelStrings.withoutComments(text);
        } catch (IllegalArgumentException e) {
            throw new ExpressionSyntaxException("'%s' is not a supported output expression: %s".formatted(text, e.getMessage()));
        }
    }

    OutputExpression parse() {
        skipWhitespace();
        if (position == text.length()) {
            throw unsupported("it is empty");
        }
        do {
            prefixes();
            operands.push(primary());
        } while (operatorFollows());
        reduceWhile(top -> true);
        return operands.pop();
    }

    /**
     * Pushes the unary minuses and open parentheses before an operand. They wait on the stack, not on the call stack,
     * so nesting costs no recursion.
     */
    private void prefixes() {
        while (true) {
            skipWhitespace();
            if (position < text.length() && text.charAt(position) == '-') {
                pending.push(Pending.NEGATION);
            } else if (position < text.length() && text.charAt(position) == '(') {
                pending.push(Pending.PARENTHESIS);
                openParentheses++;
            } else {
                return;
            }
            position++;
            count();
        }
    }

    /**
     * After an operand, closes the parentheses that follow it and reads the binary operator after them, if any,
     * applying the pending operators that bind at least as tightly.
     *
     * @return whether an operator follows, so another operand must; false at the end of the expression
     */
    private boolean operatorFollows() {
        while (true) {
            skipWhitespace();
            var operator = binaryOperator();
            if (operator != null) {
                reduceWhile(top -> top.precedence >= operator.precedence);
                pending.push(operator);
                return true;
            }
            if (openParentheses == 0) {
                if (position < text.length()) {
                    throw unsupported("unexpected '" + text.substring(position) + "'");
                }
                return false;
            }
            closeParenthesis();
        }
    }

    /** Reads the {@code )} of the innermost open parenthesis, and applies the operators inside it. */
    private void closeParenthesis() {
        if (position == text.length() || text.charAt(position) != ')') {
            throw unsupported("a parenthesis is not closed");
        }
        position++;
        reduceWhile(top -> top != Pending.PARENTHESIS);
        pending.pop();
        openParentheses--;
    }

    /** The binary operator at the current position, consumed, or null if none is: {@code **} before {@code *}. */
    private Pending binaryOperator() {
        if (text.startsWith("**", position)) {
            position += 2;
            count();
            return Pending.POWER;
        }
        if (position == text.length()) {
            return null;
        }
        var operator = switch (text.charAt(position)) {
            case '*' -> Pending.MULTIPLY;
            case '/' -> Pending.DIVIDE;
            case '+' -> Pending.ADD;
            case '-' -> Pending.SUBTRACT;
            default -> null;
        };
        if (operator != null) {
            position++;
            count();
        }
        return operator;
    }

    /** Applies the pending operators to their operands, from the top of the stack, while the top one passes the test. */
    private void reduceWhile(Predicate<Pending> test) {
        while (!pending.isEmpty() && test.test(pending.peek())) {
            var top = pending.pop();
            if (top == Pending.NEGATION) {
                operands.push(new Negation(operands.pop()));
            } else {
                var right = operands.pop();
                operands.push(new Arithmetic(top.operator, operands.pop(), right));
            }
        }
    }

    /** A string, number, date time literal or name. */
    private OutputExpression primary() {
        if (position == text.length()) {
            throw unsupported("a value is missing at the end");
        }
        char c = text.charAt(position);
        if (c == '"') {
            return new Literal(string());
        }
        if (TemporalLiterals.startsAt(text, position)) {
            return dateTimeLiteral();
        }
        if (Character.isDigit(c) || c == '.' && position + 1 < text.length() && Character.isDigit(text.charAt(position + 1))) {
            return new Literal(number());
        }
        if (Character.isLetter(c) || c == '_' || c == '?') {
            return name();
        }
        throw unsupported("unexpected '" + c + "'");
    }

    /** A date time literal: an at-literal, or an invocation of the date and time functions. */
    private OutputExpression dateTimeLiteral() {
        try {
            var read = TemporalLiterals.read(text, position, null);
            position = read.end();
            return new Literal(read.value());
        } catch (IllegalArgumentException e) {
            throw unsupported(e.getMessage());
        }
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
        BigDecimal number;
        try {
            number = new BigDecimal(digits.startsWith(".") ? "0" + digits : digits);
        } catch (NumberFormatException e) {
            throw unsupported("'" + digits + "' is not a number");
        }
        if (!FeelNumbers.isDecimal128(number)) {
            throw unsupported("'" + digits + "' is beyond FEEL's numbers (decimal128)");
        }
        return FeelNumbers.plain(number);
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
    /**
     * What waits on the stack for its operands: a binary operator, with its precedence (operators of equal
     * precedence associate to the left), a unary minus, which binds tighter than any of them, or an open parenthesis.
     */
    private enum Pending {
        ADD(1, Operator.ADD), SUBTRACT(1, Operator.SUBTRACT), MULTIPLY(2, Operator.MULTIPLY), DIVIDE(2, Operator.DIVIDE),
        POWER(3, Operator.POWER), NEGATION(4, null), PARENTHESIS(0, null);

        private final int precedence;
        private final Operator operator;

        Pending(int precedence, Operator operator) {
            this.precedence = precedence;
            this.operator = operator;
        }
    }
}
