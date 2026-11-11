/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * FEEL's arithmetic on numbers (DMN 1.5, 10.3.2.3.1 and Tables 57, 59 and 60): decimals with 34 digits of
 * precision, rounded to the nearest neighbour with ties to the even one (IEEE 754-2008 decimal128). A
 * result FEEL does not define is null: a division by zero, zero to a negative power, a negative number
 * to a power that is not whole, or a result beyond the exponents a decimal128 holds.
 */
final class FeelNumbers {

    /** FEEL's precision and rounding. */
    static final MathContext PRECISION = MathContext.DECIMAL128;
    /** The precision powers are computed with before they are rounded to {@link #PRECISION}. */
    private static final MathContext WORKING = new MathContext(60, RoundingMode.HALF_EVEN);
    /** An exponent is a number from -999,999,999 to 999,999,999 (Table 60). */
    private static final BigDecimal MAX_EXPONENT = BigDecimal.valueOf(999_999_999);
    /** The largest power of ten of a decimal128's leading digit, and the smallest of its last digit. */
    private static final int MAX_MAGNITUDE = 6144;
    private static final int MIN_MAGNITUDE = -6176;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal LN_10 = lnNearOne(BigDecimal.TEN, Math.log(10));

    private FeelNumbers() {
    }

    static BigDecimal add(BigDecimal a, BigDecimal b) {
        return inRange(a.add(b, PRECISION));
    }

    static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return inRange(a.subtract(b, PRECISION));
    }

    static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return inRange(a.multiply(b, PRECISION));
    }

    /** {@code a / b}, or null if {@code b} is zero. */
    static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? null : inRange(a.divide(b, PRECISION));
    }

    static BigDecimal negate(BigDecimal a) {
        return a.negate(PRECISION);
    }

    /**
     * {@code base ** exponent}. A whole exponent multiplies; any other takes a positive base, as
     * {@code exp(exponent * ln(base))}. Null where FEEL defines no number: an exponent beyond
     * -999,999,999 to 999,999,999, zero to a negative power, a negative base with an exponent that is not
     * whole, or a result out of range.
     */
    static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        if (exponent.abs().compareTo(MAX_EXPONENT) > 0) {
            return null;
        }
        boolean whole = exponent.signum() == 0 || exponent.stripTrailingZeros().scale() <= 0;
        if (base.signum() == 0) {
            return exponent.signum() < 0 ? null : exponent.signum() == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        // the size of the result, to give up before computing one that is out of range
        double magnitude = exponent.doubleValue() * (base.precision() - base.scale() - 1 + Math.log10(leading(base)));
        if (magnitude > MAX_MAGNITUDE + 1 || magnitude < MIN_MAGNITUDE - 1) {
            return null;
        }
        if (whole) {
            return inRange(base.pow(exponent.intValueExact(), WORKING).round(PRECISION));
        }
        if (base.signum() < 0) {
            return null;
        }
        return inRange(exp(exponent.multiply(ln(base), WORKING)).round(PRECISION));
    }

    /** The absolute value of a number that is not zero, scaled into [1, 10). */
    private static double leading(BigDecimal number) {
        return number.abs().movePointLeft(number.precision() - number.scale() - 1).doubleValue();
    }

    /** A result, or null if it is beyond what a decimal128 holds. */
    private static BigDecimal inRange(BigDecimal result) {
        if (result.signum() == 0) {
            return result;
        }
        int magnitude = result.precision() - result.scale() - 1;
        return magnitude > MAX_MAGNITUDE || magnitude < MIN_MAGNITUDE ? null : result;
    }

    /** The natural logarithm of a positive number: that of its digits in [1, 10), plus its power of ten times ln 10. */
    private static BigDecimal ln(BigDecimal x) {
        int power = x.precision() - x.scale() - 1;
        var digits = x.movePointLeft(power);
        var ln = lnNearOne(digits, Math.log(digits.doubleValue()));
        return ln.add(LN_10.multiply(BigDecimal.valueOf(power)), WORKING);
    }

    /**
     * The natural logarithm of a number from 1 to 10, from an estimate good to 15 digits: two steps of
     * Halley's method, {@code y + 2 (x - exp(y)) / (x + exp(y))}, each of which triples the correct digits.
     */
    private static BigDecimal lnNearOne(BigDecimal x, double estimate) {
        var y = BigDecimal.valueOf(estimate);
        for (int step = 0; step < 2; step++) {
            var e = exp(y);
            y = y.add(TWO.multiply(x.subtract(e), WORKING).divide(x.add(e), WORKING), WORKING);
        }
        return y;
    }

    /**
     * {@code e} to a power: the argument is halved until it is small, its exponential summed as a Taylor
     * series, and the result squared back. Each squaring doubles the error, so the series carries as many
     * extra digits as there are squarings.
     */
    private static BigDecimal exp(BigDecimal z) {
        int halvings = 0;
        var small = z;
        while (small.abs().compareTo(BigDecimal.valueOf(0.25)) > 0) {
            small = small.divide(TWO);
            halvings++;
        }
        var context = new MathContext(WORKING.getPrecision() + halvings, RoundingMode.HALF_EVEN);
        var sum = BigDecimal.ONE;
        var term = BigDecimal.ONE;
        var tiny = BigDecimal.ONE.movePointLeft(context.getPrecision() + 2);
        for (int n = 1; term.abs().compareTo(tiny) > 0; n++) {
            term = term.multiply(small, context).divide(BigDecimal.valueOf(n), context);
            sum = sum.add(term, context);
        }
        for (int i = 0; i < halvings; i++) {
            sum = sum.multiply(sum, context);
        }
        return sum.round(WORKING);
    }
}
