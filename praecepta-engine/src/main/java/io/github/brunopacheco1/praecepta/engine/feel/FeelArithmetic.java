/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

/**
 * FEEL's arithmetic on numbers, strings, dates, times, date-times and durations (DMN 1.5, Tables 57, 59, 60 and 62):
 * which types an operator combines, and the value it gives, null where FEEL defines none.
 *
 * <pre>
 *   date and time - date and time, time - time, date - date      days and time duration
 *   date or date and time +/- years and months duration           the same type (the day kept; null if it does not exist)
 *   date, date and time or time +/- days and time duration        the same type (a date as a date and time at UTC midnight)
 *   duration +/- duration of the same kind                        that kind
 *   duration * number, number * duration, duration / number        that kind
 *   duration / duration of the same kind                          number
 *   -duration                                                     duration * -1
 * </pre>
 *
 * Subtracting date-times "requires either both values to have a timezone or both not to have a timezone" (Table 57),
 * and a date counts as a date and time at UTC midnight (10.3.2.3.5), so a date minus a date and time without a zone is
 * null. A years and months duration is a whole number of months, so one multiplied or divided by a number drops the
 * fraction of a month (the DMN TCK's {@code 0100-arithmetic}: {@code @"P1Y11M" * -2.5} is {@code -P4Y9M}). Times
 * subtract as they compare ({@link FeelTemporals#compare}): two with an offset by instant, one with an offset and one
 * without by local time, two with a zone id in the same zone by local time, and a time with a zone id and any other
 * not at all (null), as DMN 1.5 leaves those cases open.
 */
final class FeelArithmetic {

    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    private FeelArithmetic() {
    }

    /**
     * The type {@code left operator right} gives, or null if FEEL does not define it (Tables 57, 59 and 60).
     */
    static ColumnType type(Operator operator, ColumnType left, ColumnType right) {
        return switch (operator) {
            case ADD -> sumType(left, right) != null ? sumType(left, right) : sumType(right, left);
            case SUBTRACT -> differenceType(left, right);
            case MULTIPLY -> productType(left, right) != null ? productType(left, right) : productType(right, left);
            case DIVIDE -> quotientType(left, right);
            case POWER -> left == ColumnType.NUMBER && right == ColumnType.NUMBER ? ColumnType.NUMBER : null;
        };
    }

    /** {@code -operand}'s type: a number or a duration (Table 62: -e is e * -1); else null. */
    static ColumnType negationType(ColumnType operand) {
        return productType(operand, ColumnType.NUMBER);
    }

    /** {@code left + right}'s type, with {@code left} the operand whose type the sum keeps; null if none. */
    private static ColumnType sumType(ColumnType left, ColumnType right) {
        if (left == right && (left == ColumnType.NUMBER || left == ColumnType.STRING || isDuration(left))) {
            return left;
        }
        return switch (left) {
            case DATE, DATE_TIME -> isDuration(right) ? left : null;
            case TIME -> right == ColumnType.DAY_TIME_DURATION ? left : null;
            default -> null;
        };
    }

    private static ColumnType differenceType(ColumnType left, ColumnType right) {
        if (left != ColumnType.STRING && left != ColumnType.TIME && sumType(left, right) != null) {
            return left;
        }
        if (left == ColumnType.TIME && right == ColumnType.DAY_TIME_DURATION) {
            return left;
        }
        boolean dates = (left == ColumnType.DATE || left == ColumnType.DATE_TIME) && (right == ColumnType.DATE || right == ColumnType.DATE_TIME);
        return dates || left == ColumnType.TIME && right == ColumnType.TIME ? ColumnType.DAY_TIME_DURATION : null;
    }

    /** {@code left * right}'s type, with {@code left} the operand whose type the product keeps; null if none. */
    private static ColumnType productType(ColumnType left, ColumnType right) {
        if (right != ColumnType.NUMBER) {
            return null;
        }
        return left == ColumnType.NUMBER || isDuration(left) ? left : null;
    }

    private static ColumnType quotientType(ColumnType left, ColumnType right) {
        if (isDuration(left) && left == right) {
            return ColumnType.NUMBER;
        }
        return productType(left, right);
    }

    private static boolean isDuration(ColumnType type) {
        return type == ColumnType.DAY_TIME_DURATION || type == ColumnType.YEAR_MONTH_DURATION;
    }

    // ---- values ------------------------------------------------------------------------------------

    /** {@code a operator b} on values of types {@link #type} accepts, or null where FEEL defines none. */
    static Object apply(Operator operator, Object a, Object b) {
        try {
            return switch (operator) {
                case ADD -> add(a, b);
                case SUBTRACT -> subtract(a, b);
                case MULTIPLY -> multiply(a, b);
                case DIVIDE -> divide(a, b);
                case POWER -> FeelNumbers.power((BigDecimal) a, (BigDecimal) b);
            };
        } catch (DateTimeException | ArithmeticException e) {
            return null; // beyond the range of the type: FEEL gives null (as valuedt^-1 does for a year out of range)
        }
    }

    /** {@code -a}: a number or a duration times -1 (Table 62). */
    static Object negate(Object a) {
        return switch (a) {
            case BigDecimal number -> FeelNumbers.negate(number);
            case Duration duration -> duration.negated();
            case YearsAndMonths duration -> new YearsAndMonths(Math.negateExact(duration.months()));
            default -> null;
        };
    }

    private static Object add(Object a, Object b) {
        return switch (a) {
            case String text -> text + b;
            case BigDecimal x -> FeelNumbers.add(x, (BigDecimal) b);
            case Duration x when b instanceof Duration y -> x.plus(y);
            case YearsAndMonths x when b instanceof YearsAndMonths y -> new YearsAndMonths(Math.addExact(x.months(), y.months()));
            case Duration duration -> shift(b, duration, 1);
            case YearsAndMonths duration -> shift(b, duration, 1);
            default -> shift(a, b, 1);
        };
    }

    private static Object subtract(Object a, Object b) {
        return switch (a) {
            case BigDecimal x -> FeelNumbers.subtract(x, (BigDecimal) b);
            case Duration x -> x.minus((Duration) b);
            case YearsAndMonths x -> new YearsAndMonths(Math.subtractExact(x.months(), ((YearsAndMonths) b).months()));
            default -> b instanceof Duration || b instanceof YearsAndMonths ? shift(a, b, -1) : between(b, a);
        };
    }

    /** A date, date and time or time moved by a duration, forwards ({@code sign} 1) or backwards (-1). */
    private static Object shift(Object value, Object duration, int sign) {
        if (duration instanceof YearsAndMonths months) {
            long by = sign * months.months();
            return switch (value) {
                case LocalDate date -> plusMonths(date, by);
                case LocalDateTime dateTime -> plusMonths(dateTime.toLocalDate(), by).atTime(dateTime.toLocalTime());
                case ZonedDateTime dateTime -> ZonedDateTime.of(plusMonths(dateTime.toLocalDate(), by), dateTime.toLocalTime(),
                        dateTime.getZone());
                default -> throw new IllegalStateException("not a date: " + value);
            };
        }
        var by = sign > 0 ? (Duration) duration : ((Duration) duration).negated();
        return switch (value) {
            // a date as a date and time at UTC midnight (10.3.2.3.5), moved, then its date
            case LocalDate date -> date.atStartOfDay().plus(by).toLocalDate();
            case LocalDateTime dateTime -> dateTime.plus(by);
            case ZonedDateTime dateTime -> dateTime.plus(by);
            case LocalTime time -> time.plus(by);
            case OffsetTime time -> time.plus(by);
            case ZonedTime time -> new ZonedTime(time.time().plus(by), time.zone());
            default -> throw new IllegalStateException("not a date or time: " + value);
        };
    }

    /**
     * {@code date(e1.year +/- e2.years + floor((e1.month +/- e2.months) / 12), ..., e1.day)} (Table 57): the day kept, so
     * a day the month does not have gives no date ({@code date(2025, 2, 31)} is null).
     */
    private static LocalDate plusMonths(LocalDate date, long months) {
        long total = Math.addExact(date.getYear() * 12L + date.getMonthValue() - 1, months);
        return LocalDate.of(Math.toIntExact(Math.floorDiv(total, 12)), Math.floorMod(total, 12) + 1, date.getDayOfMonth());
    }

    /** {@code later - earlier} of two dates, date-times or times, as a days and time duration; null where it is undefined. */
    private static Duration between(Object earlier, Object later) {
        if (earlier instanceof LocalDate || later instanceof LocalDate) {
            // a date is a date and time at UTC midnight, with a zone (10.3.2.3.5): only one with a zone takes it
            return between(asDateTime(earlier), asDateTime(later));
        }
        return switch (later) {
            case LocalDateTime b when earlier instanceof LocalDateTime a -> Duration.between(a, b);
            case ZonedDateTime b when earlier instanceof ZonedDateTime a -> Duration.between(a.toInstant(), b.toInstant());
            case LocalDateTime b -> null; // one with a zone, one without: undefined (Table 57)
            case ZonedDateTime b -> null;
            default -> timeBetween(earlier, later);
        };
    }

    /** A date as the date and time at UTC midnight it is when converted (10.3.2.3.5); any other value as it is. */
    private static Object asDateTime(Object value) {
        return value instanceof LocalDate date ? date.atStartOfDay(ZoneOffset.UTC) : value;
    }

    /** {@code later - earlier} of two times, as they compare ({@link FeelTemporals#compare}); null if they do not. */
    private static Duration timeBetween(Object earlier, Object later) {
        if (!FeelTemporals.comparable(earlier, later)) {
            return null;
        }
        return switch (later) {
            case OffsetTime b when earlier instanceof OffsetTime a -> Duration.between(a.atDate(LocalDate.EPOCH).toInstant(),
                    b.atDate(LocalDate.EPOCH).toInstant());
            case ZonedTime b -> Duration.between(((ZonedTime) earlier).time(), b.time());
            default -> Duration.between((LocalTime) FeelTemporals.local(earlier), (LocalTime) FeelTemporals.local(later));
        };
    }

    private static Object multiply(Object a, Object b) {
        return switch (a) {
            case BigDecimal x when b instanceof BigDecimal y -> FeelNumbers.multiply(x, y);
            case BigDecimal x -> scale(b, x, false);
            default -> scale(a, (BigDecimal) b, false);
        };
    }

    private static Object divide(Object a, Object b) {
        return switch (a) {
            case BigDecimal x -> FeelNumbers.divide(x, (BigDecimal) b);
            case Duration x when b instanceof Duration y -> FeelNumbers.divide(seconds(x), seconds(y));
            case YearsAndMonths x when b instanceof YearsAndMonths y -> FeelNumbers.divide(BigDecimal.valueOf(x.months()),
                    BigDecimal.valueOf(y.months()));
            default -> scale(a, (BigDecimal) b, true);
        };
    }

    /**
     * A duration multiplied or divided by a number: a days and time duration to the nanosecond, ties to the even one;
     * a years and months duration to the whole month, toward zero. Null for a division by zero.
     */
    private static Object scale(Object duration, BigDecimal by, boolean divide) {
        if (divide && by.signum() == 0) {
            return null;
        }
        return switch (duration) {
            case Duration length -> {
                var seconds = seconds(length);
                var scaled = divide ? FeelNumbers.divide(seconds, by) : seconds.multiply(by);
                var nanos = scaled.multiply(NANOS_PER_SECOND).setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact();
                var whole = nanos.divideAndRemainder(NANOS_PER_SECOND.toBigIntegerExact());
                yield Duration.ofSeconds(whole[0].longValueExact(), whole[1].longValueExact());
            }
            case YearsAndMonths months -> {
                var value = BigDecimal.valueOf(months.months());
                var scaled = divide ? value.divide(by, FeelNumbers.PRECISION) : value.multiply(by);
                yield new YearsAndMonths(scaled.setScale(0, RoundingMode.DOWN).longValueExact());
            }
            default -> throw new IllegalStateException("not a duration: " + duration);
        };
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }
}
