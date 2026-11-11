/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * An output entry that computes its value, as DMN allows any FEEL expression there: the subset
 * Praecepta supports joins string and number literals and the names of inputs with {@code +},
 * {@code -}, {@code *}, {@code /}, unary minus and parentheses, as in
 * {@code Symptom + " is in the list of Flu symptoms"} or {@code (price - discount) * 1.2}.
 *
 * <p>FEEL's semantics: {@code +} concatenates two strings or adds two numbers; the other operators take
 * numbers. Numbers are decimals, and a quotient is rounded to 34 digits (FEEL's decimal128). An
 * operand that is missing (null) makes the result missing, and so does a division by zero. A string
 * and a number never combine (FEEL gives null); {@link #type} rejects them before any evaluation.
 */
public sealed interface OutputExpression {

    /** A string ({@code String}) or number ({@code BigDecimal}) literal. */
    record Literal(Object value) implements OutputExpression {
    }

    /** The name of an input, whose value the expression reads, such as {@code Symptom} or {@code Applicant.age}. */
    record Name(String name) implements OutputExpression {
    }

    /** {@code -operand}. */
    record Negation(OutputExpression operand) implements OutputExpression {
    }

    /** {@code left operator right}. */
    record Arithmetic(Operator operator, OutputExpression left, OutputExpression right) implements OutputExpression {
    }

    /** An arithmetic operator. */
    enum Operator {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        /** The operator as FEEL writes it. */
        public String symbol() {
            return symbol;
        }
    }

    /**
     * Parses an output entry.
     *
     * @throws ExpressionSyntaxException if the text is not in the supported subset (a function call, a
     *                                   comparison, an {@code if}, a list...)
     */
    static OutputExpression parse(String text) {
        return new ExpressionParser(text).parse();
    }

    /** The names of the inputs the expression reads, in the order written (repeated if read twice). */
    default List<String> names() {
        var names = new ArrayList<String>();
        collectNames(this, names);
        return names;
    }

    private static void collectNames(OutputExpression expression, List<String> names) {
        switch (expression) {
            case Literal literal -> {
                // no input
            }
            case Name(var name) -> names.add(name);
            case Negation(var operand) -> collectNames(operand, names);
            case Arithmetic(var operator, var left, var right) -> {
                collectNames(left, names);
                collectNames(right, names);
            }
        }
    }

    /**
     * The type of the expression's value, {@code STRING} or {@code NUMBER}, given the type of each input
     * it names.
     *
     * @param inputs the type of each input the expression names; null for a name that is not an input
     * @throws ExpressionTypeException if a name is not an input, an input is of another type, or an operator
     *                                 gets a string and a number, or a string where only numbers go
     */
    default ColumnType type(Function<String, ColumnType> inputs) {
        return switch (this) {
            case Literal(var value) -> value instanceof String ? ColumnType.STRING : ColumnType.NUMBER;
            case Name(var name) -> {
                var type = inputs.apply(name);
                if (type == null) {
                    throw new ExpressionTypeException("'%s' is not an input".formatted(name));
                }
                if (type != ColumnType.STRING && type != ColumnType.NUMBER) {
                    throw new ExpressionTypeException("'%s' is a %s; output expressions combine strings and numbers"
                            .formatted(name, type.feelName()));
                }
                yield type;
            }
            case Negation(var operand) -> {
                if (operand.type(inputs) != ColumnType.NUMBER) {
                    throw new ExpressionTypeException("- applies to numbers, not strings");
                }
                yield ColumnType.NUMBER;
            }
            case Arithmetic(var operator, var left, var right) -> {
                var leftType = left.type(inputs);
                var rightType = right.type(inputs);
                if (leftType != rightType) {
                    throw new ExpressionTypeException("%s combines a %s and a %s, which gives null in FEEL"
                            .formatted(operator.symbol(), leftType.feelName(), rightType.feelName()));
                }
                if (leftType == ColumnType.STRING && operator != Operator.ADD) {
                    throw new ExpressionTypeException(operator.symbol() + " applies to numbers, not strings");
                }
                yield leftType;
            }
        };
    }

    /**
     * The expression's value: a {@code String}, a {@code BigDecimal}, or null if an operand is missing
     * or a divisor is zero. The expression must have passed {@link #type}.
     *
     * @param inputs the value of each input it names: a {@code String} or {@code BigDecimal}, or null if missing
     */
    default Object evaluate(Function<String, Object> inputs) {
        return switch (this) {
            case Literal(var value) -> value;
            case Name(var name) -> inputs.apply(name);
            case Negation(var operand) -> operand.evaluate(inputs) instanceof BigDecimal number ? number.negate() : null;
            case Arithmetic(var operator, var left, var right) -> {
                var a = left.evaluate(inputs);
                var b = right.evaluate(inputs);
                if (a == null || b == null) {
                    yield null;
                }
                if (a instanceof String first) {
                    yield first + b;
                }
                var x = (BigDecimal) a;
                var y = (BigDecimal) b;
                yield switch (operator) {
                    case ADD -> x.add(y);
                    case SUBTRACT -> x.subtract(y);
                    case MULTIPLY -> x.multiply(y);
                    case DIVIDE -> y.signum() == 0 ? null : x.divide(y, MathContext.DECIMAL128);
                };
            }
        };
    }
}
