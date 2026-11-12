/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * An output entry that computes its value, as DMN allows any FEEL expression there: the subset
 * Praecepta supports joins literals (strings, numbers, and dates, times, date-times and durations written as
 * {@code TemporalLiterals} reads them) and the names of inputs with {@code +}, {@code -}, {@code *}, {@code /},
 * {@code **}, unary minus and parentheses, as in {@code Symptom + " is in the list of Flu symptoms"},
 * {@code (price - discount) * 1.2}, {@code amount * (1 + rate) ** years}, {@code due + duration("P14D")} or
 * {@code returned - sent}.
 *
 * <p>FEEL's semantics (DMN 1.5, Tables 57, 59, 60 and 62): {@code +} concatenates two strings or adds two
 * numbers; the other operators take numbers; dates, times, date-times and durations combine as
 * {@code FeelArithmetic} lists (a date plus a duration is a date, two date-times subtract to a days and time
 * duration, a duration scales by a number, two durations of a kind divide to a number). Numbers are decimals, and every result is rounded to 34
 * digits (FEEL's decimal128), ties to the even neighbour. An operand that is missing (null) makes the
 * result missing, and so does a result FEEL does not define: a division by zero, zero to a negative
 * power, a negative number to a power that is not whole, an exponent beyond 999,999,999 either way, or a
 * number too large or too small for a decimal128. As FEEL has it, unary minus binds tighter than
 * {@code **}, so {@code -4 ** 2} is 16, and {@code **} associates to the left like the other operators.
 * A string and a number never combine (FEEL gives null); {@link #type} rejects them, and any other pair of types
 * FEEL does not combine, before any evaluation. Where the types combine but a value does not, the result is null as
 * well: a date and time with a zone minus one without, a date plus a month that it does not have
 * ({@code date("2025-01-31") + duration("P1M")}), a result beyond the type's range.
 */
public sealed interface OutputExpression {

    /**
     * A literal: a {@code String}, a {@code BigDecimal}, or a date, time, date and time or duration ({@code LocalDate},
     * {@code LocalTime}, {@code OffsetTime}, {@link ZonedTime}, {@code LocalDateTime}, {@code ZonedDateTime},
     * {@code Duration}, {@link YearsAndMonths}).
     */
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
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), POWER("**");

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
     * The type of the expression's value, given the type of each input it names: a string, a number, or a date,
     * time, date and time or duration, as FEEL's arithmetic gives it ({@code FeelArithmetic}).
     *
     * @param inputs the type of each input the expression names; null for a name that is not an input
     * @throws ExpressionTypeException if a name is not an input, an input is a boolean, or an operator gets two
     *                                 types FEEL does not combine (a string and a number, a date and a time, two
     *                                 dates added...), for which FEEL gives null
     */
    default ColumnType type(Function<String, ColumnType> inputs) {
        return switch (this) {
            case Literal(var value) -> TemporalLiterals.typeOf(value);
            case Name(var name) -> {
                var type = inputs.apply(name);
                if (type == null) {
                    throw new ExpressionTypeException("'%s' is not an input".formatted(name));
                }
                if (type == ColumnType.BOOLEAN) {
                    throw new ExpressionTypeException(("'%s' is a %s; output expressions combine strings, numbers, dates, times "
                            + "and durations").formatted(name, type.feelName()));
                }
                yield type;
            }
            case Negation(var operand) -> {
                var operandType = operand.type(inputs);
                var type = FeelArithmetic.negationType(operandType);
                if (type == null) {
                    throw new ExpressionTypeException("- applies to numbers and durations, not to a " + operandType.feelName());
                }
                yield type;
            }
            case Arithmetic(var operator, var left, var right) -> {
                var leftType = left.type(inputs);
                var rightType = right.type(inputs);
                var type = FeelArithmetic.type(operator, leftType, rightType);
                if (type == null) {
                    throw new ExpressionTypeException("%s combines a %s and a %s, which gives null in FEEL"
                            .formatted(operator.symbol(), leftType.feelName(), rightType.feelName()));
                }
                yield type;
            }
        };
    }

    /**
     * The expression's value: a value of the type {@link #type} gives, or null if an operand is missing or FEEL
     * defines no result (a division by zero, a day the month does not have, say). The expression must have passed
     * {@link #type}.
     *
     * @param inputs the value of each input it names, of its type (a years and months duration as a
     *               {@link YearsAndMonths}), or null if missing
     */
    default Object evaluate(Function<String, Object> inputs) {
        return switch (this) {
            case Literal(var value) -> value;
            case Name(var name) -> inputs.apply(name);
            case Negation(var operand) -> {
                var value = operand.evaluate(inputs);
                yield value == null ? null : FeelArithmetic.negate(value);
            }
            case Arithmetic(var operator, var left, var right) -> {
                var a = left.evaluate(inputs);
                var b = right.evaluate(inputs);
                yield a == null || b == null ? null : FeelArithmetic.apply(operator, a, b);
            }
        };
    }
}
