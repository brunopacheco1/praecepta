/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * An ordered column's boundaries as {@code long} keys that sort as the values do, so that a lookup is
 * a binary search over a {@code long[]} instead of over objects compared through {@link Comparable}:
 * days since the epoch for dates, nanoseconds for times, date-times and days and time durations, months
 * for years and months durations, and for numbers the value scaled by a power of ten that makes every
 * boundary whole. A value without an exact key (beyond {@code long}, or a number finer than the
 * boundaries) still finds its class, or falls back to the column's search over the boundaries.
 */
final class PrimitiveKeys {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** Numbers are scaled by at most 10^18, the largest power of ten a long holds. */
    private static final int MAX_SCALE = 18;

    private final ColumnType type;
    private final long[] keys;
    /** NUMBER: the power of ten the keys are scaled by. */
    private final int scale;

    private PrimitiveKeys(ColumnType type, long[] keys, int scale) {
        this.type = type;
        this.keys = keys;
        this.scale = scale;
    }

    /** The keys of an ordered column's boundaries, or null if its type has none or a boundary does not fit. */
    static PrimitiveKeys of(ColumnType type, Comparable<?>[] boundaries) {
        if (type == ColumnType.STRING || type == ColumnType.BOOLEAN) {
            return null;
        }
        int scale = 0;
        if (type == ColumnType.NUMBER) {
            for (var boundary : boundaries) {
                scale = Math.max(scale, ((BigDecimal) boundary).scale());
            }
            if (scale > MAX_SCALE) {
                return null;
            }
        }
        var keys = new long[boundaries.length];
        try {
            for (int i = 0; i < boundaries.length; i++) {
                keys[i] = switch (type) {
                    case NUMBER -> ((BigDecimal) boundaries[i]).setScale(scale).unscaledValue().longValueExact();
                    case DATE, TIME, DATE_TIME, DAY_TIME_DURATION, YEAR_MONTH_DURATION -> key(boundaries[i]);
                    case STRING, BOOLEAN -> throw new IllegalStateException("no keys for " + type);
                };
            }
        } catch (ArithmeticException e) {
            return null;
        }
        return new PrimitiveKeys(type, keys, scale);
    }

    /**
     * The class of a non-null value of the column, numbered as in {@link ColumnIndex}: boundary {@code i}
     * is class {@code 2i + 1}, the interval below it class {@code 2i}. -1 if the value has no key.
     */
    int classOf(Object value) {
        try {
            if (type != ColumnType.NUMBER) {
                return exact(key(value));
            }
            var number = (BigDecimal) value;
            if (number.scale() == 0 && scale == 0) {
                return exact(number.longValueExact()); // the usual case: whole numbers, compared without allocating
            }
            if (number.scale() <= scale) {
                return exact(number.setScale(scale).unscaledValue().longValueExact());
            }
            // finer than every boundary: it lies above its floor, strictly
            var floor = number.setScale(scale, RoundingMode.FLOOR);
            long key = floor.unscaledValue().longValueExact();
            return floor.compareTo(number) == 0 ? exact(key) : above(key);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private int exact(long key) {
        return ColumnIndex.classOfSearch(Arrays.binarySearch(keys, key));
    }

    /** A value strictly between {@code key} and the next key. */
    private int above(long key) {
        int found = Arrays.binarySearch(keys, key);
        return found >= 0 ? 2 * found + 2 : 2 * (-found - 1);
    }

    /** The key of a date, time, date-time or duration; throws ArithmeticException if it does not fit. */
    private static long key(Object value) {
        return switch (value) {
            case LocalDate date -> date.toEpochDay();
            case LocalTime time -> time.toNanoOfDay();
            case LocalDateTime dateTime -> Math.addExact(
                    Math.multiplyExact(dateTime.toEpochSecond(ZoneOffset.UTC), NANOS_PER_SECOND), dateTime.getNano());
            case Duration duration -> duration.toNanos();
            case YearsAndMonths(long months) -> months;
            default -> throw new ArithmeticException("no key for " + value.getClass().getSimpleName());
        };
    }
}
