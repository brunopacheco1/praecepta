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
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The values strictly between two boundaries of an ordered column ({@link ColumnIndex}'s classes
 * between boundaries). A null bound means unbounded. Used when the index is built and by the
 * validation report, never while evaluating.
 */
final class OpenIntervals {

    /** The smallest string after another is that string followed by this character. */
    private static final String LOWEST_CHARACTER = String.valueOf(Character.MIN_VALUE);
    /** Suffixes tried for a readable string inside an interval, before falling back to {@link #LOWEST_CHARACTER}. */
    private static final List<String> READABLE_SUFFIXES = List.of("a", "A", "0", "!");

    private OpenIntervals() {
    }

    /**
     * Whether no value of the type lies in the interval: between consecutive days or nanoseconds,
     * before midnight or after the last nanosecond of a day, anywhere outside {@code false} and
     * {@code true} (both are always boundaries), or between a string and its successor. Below the
     * empty string, or the one-character string U+0000, there is no non-blank string, and blank ones
     * count as missing.
     */
    static boolean isEmpty(ColumnType type, Comparable<?> low, Comparable<?> high) {
        return switch (type) {
            case BOOLEAN -> true;
            case DATE -> low != null && high != null && !((LocalDate) low).plusDays(1).isBefore((LocalDate) high);
            case TIME -> emptyTimes((LocalTime) low, (LocalTime) high);
            case DATE_TIME -> low != null && high != null && !((LocalDateTime) low).plusNanos(1).isBefore((LocalDateTime) high);
            case STRING -> emptyStrings((String) low, (String) high);
            case NUMBER -> false;
        };
    }

    /**
     * A value inside a non-empty interval: the midpoint of two bounds, a round step beyond a single
     * bound, or a fixed value (zero, 1970-01-01, noon) when unbounded.
     */
    static Object valueInside(ColumnType type, Comparable<?> low, Comparable<?> high) {
        return switch (type) {
            case DATE -> betweenDates((LocalDate) low, (LocalDate) high);
            case TIME -> betweenTimes((LocalTime) low, (LocalTime) high);
            case DATE_TIME -> betweenDateTimes((LocalDateTime) low, (LocalDateTime) high);
            case NUMBER -> betweenNumbers((BigDecimal) low, (BigDecimal) high);
            case STRING -> betweenStrings((String) low, (String) high);
            // BOOLEAN intervals are always empty
            case BOOLEAN -> throw new IllegalStateException("no %s value between %s and %s".formatted(type, low, high));
        };
    }

    private static boolean emptyStrings(String low, String high) {
        if (high == null) {
            return false;
        }
        if (low == null) {
            return high.isEmpty() || high.equals(LOWEST_CHARACTER);
        }
        return high.equals(low + LOWEST_CHARACTER);
    }

    /**
     * A non-blank string inside a non-empty interval: the lower bound (or nothing) followed by a
     * readable suffix if one fits, else by U+0000, which is the lower bound's successor and so lies
     * inside any non-empty interval.
     */
    private static String betweenStrings(String low, String high) {
        var prefix = low == null ? "" : low;
        for (var suffix : READABLE_SUFFIXES) {
            var candidate = prefix + suffix;
            if (high == null || candidate.compareTo(high) < 0) {
                return candidate;
            }
        }
        return prefix + LOWEST_CHARACTER;
    }

    private static boolean emptyTimes(LocalTime low, LocalTime high) {
        if (low == null) {
            return LocalTime.MIDNIGHT.equals(high);
        }
        if (high == null) {
            return LocalTime.MAX.equals(low);
        }
        return !low.plusNanos(1).isBefore(high);
    }

    private static LocalDate betweenDates(LocalDate low, LocalDate high) {
        if (low != null) {
            return low.plusDays(1);
        }
        return high == null ? LocalDate.EPOCH : high.minusDays(1);
    }

    private static LocalTime betweenTimes(LocalTime low, LocalTime high) {
        if (low == null && high == null) {
            return LocalTime.NOON;
        }
        if (low == null) {
            // the interval is not empty, so high is after midnight: step back an hour unless that wraps around
            return high.getHour() >= 1 ? high.minusHours(1) : LocalTime.MIDNIGHT;
        }
        if (high == null) {
            return low.getHour() <= 22 ? low.plusHours(1) : LocalTime.MAX;
        }
        // at least two nanoseconds apart (the interval is not empty), so the midpoint lies strictly inside
        return LocalTime.ofNanoOfDay((low.toNanoOfDay() + high.toNanoOfDay()) / 2);
    }

    private static LocalDateTime betweenDateTimes(LocalDateTime low, LocalDateTime high) {
        if (low == null) {
            return high == null ? LocalDate.EPOCH.atStartOfDay() : high.minusDays(1);
        }
        if (high == null) {
            return low.plusDays(1);
        }
        // date-times have no zone: measure on the UTC timeline, which has no daylight-saving gaps or overlaps
        var start = low.toInstant(ZoneOffset.UTC);
        var middle = start.plus(Duration.between(start, high.toInstant(ZoneOffset.UTC)).dividedBy(2));
        return LocalDateTime.ofInstant(middle, ZoneOffset.UTC);
    }

    private static BigDecimal betweenNumbers(BigDecimal low, BigDecimal high) {
        if (low == null) {
            return high == null ? BigDecimal.ZERO : high.subtract(BigDecimal.ONE);
        }
        if (high == null) {
            return low.add(BigDecimal.ONE);
        }
        return low.add(high).divide(BigDecimal.TWO, low.scale() + high.scale() + 1, RoundingMode.HALF_EVEN);
    }
}
