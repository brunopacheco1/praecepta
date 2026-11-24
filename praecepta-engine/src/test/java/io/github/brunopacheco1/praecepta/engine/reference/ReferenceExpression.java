/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * The arithmetic output expressions {@link DecisionTableScenario} writes, parsed independently of the engine's FEEL
 * parser, for {@link ReferenceMatcher} to evaluate on its own: numbers (with an exponent), strings (with escape
 * sequences), names of single words, date and time literals ({@code @"..."}, and {@code date}, {@code time},
 * {@code date and time} and {@code duration} of one string), {@code + - * / **}, unary minus (binding tighter than
 * {@code **}) and parentheses.
 */
sealed interface ReferenceExpression {

    /** A literal value. */
    record Literal(Object value) implements ReferenceExpression {
    }

    /** An input, by name. */
    record Name(String name) implements ReferenceExpression {
    }

    /** {@code -operand}. */
    record Negation(ReferenceExpression operand) implements ReferenceExpression {
    }

    /** {@code left operator right}. */
    record Arithmetic(Operator operator, ReferenceExpression left, ReferenceExpression right) implements ReferenceExpression {
    }

    /** An arithmetic operator. */
    enum Operator { ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER }

    static ReferenceExpression parse(String text) {
        return new Parser(text).all();
    }

    /** A recursive descent parser of the forms above; the scenarios' expressions are shallow. */
    final class Parser {
        private static final List<String> FUNCTIONS = List.of("date and time", "date", "time", "duration");
        private final String text;
        private int at;

        private Parser(String text) {
            this.text = text;
        }

        ReferenceExpression all() {
            var expression = sum();
            skipSpace();
            if (at != text.length()) {
                throw new IllegalArgumentException("unexpected '" + text.substring(at) + "' in " + text);
            }
            return expression;
        }

        private ReferenceExpression sum() {
            var left = product();
            while (true) {
                skipSpace();
                if (accept("+")) {
                    left = new Arithmetic(Operator.ADD, left, product());
                } else if (peek('-')) {
                    at++;
                    left = new Arithmetic(Operator.SUBTRACT, left, product());
                } else {
                    return left;
                }
            }
        }

        private ReferenceExpression product() {
            var left = power();
            while (true) {
                skipSpace();
                if (text.startsWith("*", at) && !text.startsWith("**", at)) {
                    at++;
                    left = new Arithmetic(Operator.MULTIPLY, left, power());
                } else if (accept("/")) {
                    left = new Arithmetic(Operator.DIVIDE, left, power());
                } else {
                    return left;
                }
            }
        }

        private ReferenceExpression power() {
            var left = unary();
            while (true) {
                skipSpace();
                if (accept("**")) {
                    left = new Arithmetic(Operator.POWER, left, unary());
                } else {
                    return left;
                }
            }
        }

        private ReferenceExpression unary() {
            skipSpace();
            if (accept("-")) {
                var operand = unary();
                return operand instanceof Literal(BigDecimal number) ? new Literal(number.negate()) : new Negation(operand);
            }
            return primary();
        }

        private ReferenceExpression primary() {
            skipSpace();
            if (accept("(")) {
                var inner = sum();
                skipSpace();
                expect(")");
                return inner;
            }
            if (accept("@")) {
                return new Literal(temporal(string()));
            }
            if (peek('"')) {
                return new Literal(string());
            }
            if (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                return new Literal(number());
            }
            for (var function : FUNCTIONS) {
                if (text.startsWith(function + "(", at)) {
                    at += function.length() + 1;
                    var argument = string();
                    expect(")");
                    return new Literal(function.equals("duration") ? temporal(argument) : literal(function, argument));
                }
            }
            int start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                at++;
            }
            if (start == at) {
                throw new IllegalArgumentException("a value is expected at '" + text.substring(at) + "' in " + text);
            }
            return new Name(text.substring(start, at));
        }

        private BigDecimal number() {
            int start = at;
            while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                at++;
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (text.charAt(at) == '+' || text.charAt(at) == '-') {
                    at++;
                }
                while (at < text.length() && Character.isDigit(text.charAt(at))) {
                    at++;
                }
            }
            var number = new BigDecimal(text.substring(start, at));
            return number.scale() < 0 ? number.setScale(0) : number;
        }

        private String string() {
            skipSpace();
            expect("\"");
            var value = new StringBuilder();
            while (text.charAt(at) != '"') {
                if (text.charAt(at) == '\\') {
                    escape(value);
                } else {
                    value.append(text.charAt(at++));
                }
            }
            at++;
            return value.toString();
        }

        /** An escape sequence of a string (DMN 1.5, grammar rule 64): a character after a backslash, or {@code \\u} and four hexadecimal digits. */
        private void escape(StringBuilder value) {
            char c = text.charAt(at + 1);
            if (c == 'u') {
                value.append((char) Integer.parseInt(text.substring(at + 2, at + 6), 16));
                at += 6;
                return;
            }
            value.append(switch (c) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> c;
            });
            at += 2;
        }

        /** A date, time, date and time or duration, as an at-literal's string says. */
        private static Object temporal(String iso) {
            if (iso.startsWith("P") || iso.startsWith("-P")) {
                return iso.contains("Y") || iso.contains("M") && !iso.contains("T") ? YearsAndMonths.parse(iso) : Duration.parse(iso);
            }
            if (iso.contains("T")) {
                return FeelTemporals.parseDateTime(iso);
            }
            return iso.contains(":") ? FeelTemporals.parseTime(iso) : FeelTemporals.parseDate(iso);
        }

        private static Object literal(String function, String iso) {
            return switch (function) {
                case "date" -> FeelTemporals.parseDate(iso);
                case "time" -> FeelTemporals.parseTime(iso);
                default -> FeelTemporals.parseDateTime(iso);
            };
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private boolean accept(String symbol) {
            if (text.startsWith(symbol, at)) {
                at += symbol.length();
                return true;
            }
            return false;
        }

        private void expect(String symbol) {
            if (!accept(symbol)) {
                throw new IllegalArgumentException("'" + symbol + "' expected at '" + text.substring(at) + "' in " + text);
            }
        }
    }
}
