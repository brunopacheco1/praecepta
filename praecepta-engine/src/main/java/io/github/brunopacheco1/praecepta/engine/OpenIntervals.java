/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

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

    private static final long NANOS_PER_HOUR = Duration.ofHours(1).toNanos();
    private static final long NANOS_PER_DAY = Duration.ofDays(1).toNanos();
    /** The earliest time with an offset, by instant: {@code 00:00+18:00}, as {@link PrimitiveKeys#utcNanos} keys it. */
    private static final long EARLIEST_ZONED_TIME = -18 * NANOS_PER_HOUR;
    /** The latest time with an offset, by instant: {@code 23:59:59.999999999-18:00}. */
    private static final long LATEST_ZONED_TIME = NANOS_PER_DAY - 1 + 18 * NANOS_PER_HOUR;

    private OpenIntervals() {
    }

    /**
     * Whether no value of the type lies in the interval: between consecutive days or nanoseconds,
     * before midnight or after the last nanosecond of a day, anywhere outside {@code false} and
     * {@code true} (both are always boundaries), or between a string and its successor. Below the
     * empty string, or the one-character string U+0000, there is no non-blank string, and blank ones
     * count as missing. On the line of times and date-times with a time zone ({@code zoned}), an interval
     * is empty when no instant lies in it: between instants a nanosecond apart, or for times, outside the
     * instants that times of day with an offset from -18:00 to +18:00 denote.
     */
    static boolean isEmpty(ColumnType type, Comparable<?> low, Comparable<?> high, boolean zoned) {
        if (zoned) {
            return type == ColumnType.TIME ? emptyZonedTimes((OffsetTime) low, (OffsetTime) high)
                    : low != null && high != null && !((ZonedDateTime) low).toInstant().plusNanos(1).isBefore(((ZonedDateTime) high).toInstant());
        }
        return switch (type) {
            case BOOLEAN -> true;
            case DATE -> low != null && high != null && !((LocalDate) low).plusDays(1).isBefore((LocalDate) high);
            case TIME -> emptyTimes((LocalTime) low, (LocalTime) high);
            case DATE_TIME -> low != null && high != null && !((LocalDateTime) low).plusNanos(1).isBefore((LocalDateTime) high);
            case STRING -> emptyStrings((String) low, (String) high);
            case DAY_TIME_DURATION -> low != null && high != null && ((Duration) low).plusNanos(1).compareTo((Duration) high) >= 0;
            case YEAR_MONTH_DURATION -> low != null && high != null && ((YearsAndMonths) low).months() + 1 >= ((YearsAndMonths) high).months();
            case NUMBER -> false;
        };
    }

    /**
     * A value inside a non-empty interval: the midpoint of two bounds, a round step beyond a single
     * bound, or a fixed value (zero, 1970-01-01, noon; at offset zero on the line with a time zone) when
     * unbounded.
     */
    static Object valueInside(ColumnType type, Comparable<?> low, Comparable<?> high, boolean zoned) {
        if (zoned) {
            return type == ColumnType.TIME ? betweenZonedTimes((OffsetTime) low, (OffsetTime) high)
                    : betweenZonedDateTimes((ZonedDateTime) low, (ZonedDateTime) high);
        }
        return switch (type) {
            case DATE -> betweenDates((LocalDate) low, (LocalDate) high);
            case TIME -> betweenTimes((LocalTime) low, (LocalTime) high);
            case DATE_TIME -> betweenDateTimes((LocalDateTime) low, (LocalDateTime) high);
            case NUMBER -> betweenNumbers((BigDecimal) low, (BigDecimal) high);
            case STRING -> betweenStrings((String) low, (String) high);
            case DAY_TIME_DURATION -> betweenDurations((Duration) low, (Duration) high);
            case YEAR_MONTH_DURATION -> betweenMonths((YearsAndMonths) low, (YearsAndMonths) high);
            // BOOLEAN intervals are always empty
            case BOOLEAN -> throw new IllegalStateException("no %s value between %s and %s".formatted(type, low, high));
        };
    }

    /** A day beyond a single bound, zero when unbounded, else the midpoint (at least two nanoseconds apart, so inside). */
    private static Duration betweenDurations(Duration low, Duration high) {
        if (low == null) {
            return high == null ? Duration.ZERO : high.minusDays(1);
        }
        if (high == null) {
            return low.plusDays(1);
        }
        return low.plus(high.minus(low).dividedBy(2));
    }

    /** A month beyond a single bound, zero when unbounded, else the midpoint (at least two months apart, so inside). */
    private static YearsAndMonths betweenMonths(YearsAndMonths low, YearsAndMonths high) {
        if (low == null) {
            return new YearsAndMonths(high == null ? 0 : high.months() - 1);
        }
        if (high == null) {
            return new YearsAndMonths(low.months() + 1);
        }
        return new YearsAndMonths(Math.floorDiv(low.months() + high.months(), 2));
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

    private static boolean emptyZonedTimes(OffsetTime low, OffsetTime high) {
        if (low == null) {
            return high != null && PrimitiveKeys.utcNanos(high) <= EARLIEST_ZONED_TIME;
        }
        if (high == null) {
            return PrimitiveKeys.utcNanos(low) >= LATEST_ZONED_TIME;
        }
        return PrimitiveKeys.utcNanos(high) - PrimitiveKeys.utcNanos(low) <= 1;
    }

    /**
     * Noon at offset zero when unbounded, an hour beyond a single bound (but no further than the earliest
     * or latest instant), else the midpoint; at the offset of a bound when the time of day fits.
     */
    private static OffsetTime betweenZonedTimes(OffsetTime low, OffsetTime high) {
        long key;
        if (low == null) {
            key = high == null ? NANOS_PER_DAY / 2 : Math.max(EARLIEST_ZONED_TIME, PrimitiveKeys.utcNanos(high) - NANOS_PER_HOUR);
        } else if (high == null) {
            key = Math.min(LATEST_ZONED_TIME, PrimitiveKeys.utcNanos(low) + NANOS_PER_HOUR);
        } else {
            // at least two nanoseconds apart (the interval is not empty), so the midpoint lies strictly inside
            key = Math.floorDiv(PrimitiveKeys.utcNanos(low) + PrimitiveKeys.utcNanos(high), 2);
        }
        var bound = low != null ? low : high;
        return zonedTime(key, bound == null ? ZoneOffset.UTC : bound.getOffset());
    }

    /**
     * The time with an offset at an instant ({@link PrimitiveKeys#utcNanos}): at {@code preferred} if the
     * time of day fits in a day there, else at the whole number of hours that brings it into the day.
     */
    private static OffsetTime zonedTime(long key, ZoneOffset preferred) {
        long local = key + preferred.getTotalSeconds() * 1_000_000_000L;
        if (local >= 0 && local < NANOS_PER_DAY) {
            return OffsetTime.of(LocalTime.ofNanoOfDay(local), preferred);
        }
        int hours = key < 0 ? (int) Math.ceilDiv(-key, NANOS_PER_HOUR) : key >= NANOS_PER_DAY
                ? -(int) Math.ceilDiv(key - NANOS_PER_DAY + 1, NANOS_PER_HOUR) : 0;
        return OffsetTime.of(LocalTime.ofNanoOfDay(key + hours * NANOS_PER_HOUR), ZoneOffset.ofHours(hours));
    }

    /** As for date-times without a zone, measured by instant; the midpoint in the lower bound's zone. */
    private static ZonedDateTime betweenZonedDateTimes(ZonedDateTime low, ZonedDateTime high) {
        if (low == null) {
            return high == null ? Instant.EPOCH.atZone(ZoneOffset.UTC) : high.minusDays(1);
        }
        if (high == null) {
            return low.plusDays(1);
        }
        var start = low.toInstant();
        return start.plus(Duration.between(start, high.toInstant()).dividedBy(2)).atZone(low.getZone());
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
