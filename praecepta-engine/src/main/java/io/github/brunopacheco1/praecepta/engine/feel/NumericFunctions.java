/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.number;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.optional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's numeric functions (DMN 1.5, 10.3.4.5): rounding to a scale, the absolute value, the modulo, the square root,
 * the natural logarithm and exponential, and whether a whole number is odd or even. Their values are numbers FEEL
 * holds, decimal128s; one beyond them is null, with an error.
 */
final class NumericFunctions {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final FeelType BOOLEAN = FeelType.of(ColumnType.BOOLEAN);
    private static final String N = "n";
    private static final String SCALE = "scale";
    private static final String NUMBER_PARAMETER = "number";
    /** The scales a number may be rounded to (Table 76: "scale is in the range [−6111..6176]"). */
    private static final int MIN_SCALE = -6111;
    private static final int MAX_SCALE = 6176;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private NumericFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        add.accept(BuiltInFunction.of("decimal", NUMBER, fixed(2),
                (arguments, env) -> round(arguments, RoundingMode.HALF_EVEN), N, SCALE));
        add.accept(BuiltInFunction.of("floor", NUMBER, optional(1, 1), (arguments, env) -> round(arguments, RoundingMode.FLOOR), N, SCALE));
        add.accept(BuiltInFunction.of("ceiling", NUMBER, optional(1, 1),
                (arguments, env) -> round(arguments, RoundingMode.CEILING), N, SCALE));
        add.accept(BuiltInFunction.of("round up", NUMBER, fixed(2), (arguments, env) -> round(arguments, RoundingMode.UP), N, SCALE));
        add.accept(BuiltInFunction.of("round down", NUMBER, fixed(2), (arguments, env) -> round(arguments, RoundingMode.DOWN), N, SCALE));
        add.accept(BuiltInFunction.of("round half up", NUMBER, fixed(2),
                (arguments, env) -> round(arguments, RoundingMode.HALF_UP), N, SCALE));
        add.accept(BuiltInFunction.of("round half down", NUMBER, fixed(2),
                (arguments, env) -> round(arguments, RoundingMode.HALF_DOWN), N, SCALE));
        add.accept(BuiltInFunction.of("abs", FeelType.ANY, fixed(1), (arguments, env) -> abs(arguments.getFirst()), N));
        add.accept(BuiltInFunction.of("modulo", NUMBER, fixed(2), NumericFunctions::modulo, "dividend", "divisor"));
        add.accept(BuiltInFunction.of("sqrt", NUMBER, fixed(1),
                (arguments, env) -> defined(FeelNumbers::sqrt, number(arguments.getFirst(), NUMBER_PARAMETER), "a number of 0 or more"),
                NUMBER_PARAMETER));
        add.accept(BuiltInFunction.of("log", NUMBER, fixed(1),
                (arguments, env) -> defined(FeelNumbers::log, number(arguments.getFirst(), NUMBER_PARAMETER), "a number above 0"),
                NUMBER_PARAMETER));
        add.accept(BuiltInFunction.of("exp", NUMBER, fixed(1),
                (arguments, env) -> defined(FeelNumbers::exp, number(arguments.getFirst(), NUMBER_PARAMETER), "a number e can be raised to"),
                NUMBER_PARAMETER));
        add.accept(BuiltInFunction.of("odd", BOOLEAN, fixed(1),
                (arguments, env) -> whole(arguments.getFirst()).remainder(TWO).signum() != 0, NUMBER_PARAMETER));
        add.accept(BuiltInFunction.of("even", BOOLEAN, fixed(1),
                (arguments, env) -> whole(arguments.getFirst()).remainder(TWO).signum() == 0, NUMBER_PARAMETER));
    }

    /**
     * A number rounded to a scale, the digits it keeps after the decimal point (before it, if negative), in a rounding
     * mode: {@code decimal(1/3, 2)} is {@code .33}; one of a scale not above it as it is. The scale is 0 if it is not
     * given.
     */
    private static BigDecimal round(List<Object> arguments, RoundingMode mode) {
        var n = number(arguments.getFirst(), N);
        int scale = arguments.size() > 1 ? Arguments.whole(arguments.get(1), SCALE) : 0;
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw invalid("takes a scale from %d to %d, not %d".formatted(MIN_SCALE, MAX_SCALE, scale));
        }
        if (n.scale() <= scale) {
            return n;
        }
        var rounded = n.setScale(scale, mode);
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    /** {@code abs(n)} of a number or a duration. */
    private static Object abs(Object value) {
        return switch (Arguments.single(value)) {
            case BigDecimal number -> number.abs();
            case Duration duration -> duration.abs();
            case YearsAndMonths(var months) -> new YearsAndMonths(Math.abs(months));
            case null, default -> throw Arguments.invalid("a number or a duration", value, N);
        };
    }

    /**
     * {@code modulo(dividend, divisor)}: {@code dividend - divisor * floor(dividend / divisor)}, which has the
     * divisor's sign; null for a divisor of zero.
     */
    private static Object modulo(List<Object> arguments, Env env) {
        var dividend = number(arguments.get(0), "dividend");
        var divisor = number(arguments.get(1), "divisor");
        if (divisor.signum() == 0) {
            throw invalid("takes a divisor that is not zero");
        }
        var remainder = dividend.remainder(divisor);
        if (remainder.signum() != 0 && remainder.signum() != divisor.signum()) {
            remainder = remainder.add(divisor);
        }
        return FeelNumbers.add(remainder, BigDecimal.ZERO);
    }

    private static BigDecimal whole(Object value) {
        var number = number(value, NUMBER_PARAMETER);
        if (number.signum() != 0 && number.stripTrailingZeros().scale() > 0) {
            throw Arguments.invalid("a whole number", value, NUMBER_PARAMETER);
        }
        return number;
    }

    private static BigDecimal defined(UnaryOperator<BigDecimal> function, BigDecimal argument, String what) {
        var value = function.apply(argument);
        if (value == null) {
            throw Arguments.invalid(what, argument, NUMBER_PARAMETER);
        }
        return value;
    }
}
