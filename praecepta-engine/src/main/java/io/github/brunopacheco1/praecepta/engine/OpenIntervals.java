/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
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
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_IN_A_DAY = BigInteger.valueOf(NANOS_PER_DAY);
    /** Offsets run from -18:00 to +18:00, in whole seconds. */
    private static final BigInteger MAX_OFFSET_SECONDS = BigInteger.valueOf(ZoneOffset.MAX.getTotalSeconds());
    /** Offsets an example is given at when it can be: whole hours, else quarters of an hour, in seconds. */
    private static final List<BigInteger> ROUND_OFFSETS = List.of(BigInteger.valueOf(3600), BigInteger.valueOf(900));

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

    // ---- times and date-times with a time zone: an instant and a local time ---------------------

    /**
     * The values of one class of a line ({@link ColumnIndex}): a single boundary ({@code point}, with
     * {@code low} and {@code high} both that boundary), or those strictly between two boundaries, null
     * meaning unbounded.
     */
    record Span(Comparable<?> low, Comparable<?> high, boolean point) {
        boolean unbounded() {
            return low == null && high == null;
        }
    }

    /**
     * Whether no time or date-time with a time zone has its instant in {@code instants} and its local time
     * in {@code localTimes}. A value's local time is its instant plus its offset, a whole number of seconds
     * from -18:00 to +18:00, so the two spans must hold an instant and a local time that far apart.
     */
    static boolean isEmpty(ColumnType type, Span instants, Span localTimes) {
        return new ZonedBox(type, instants, localTimes).offsetSeconds() == null;
    }

    /**
     * A time or date-time with a time zone whose instant is in {@code instants} and whose local time is in
     * {@code localTimes}, which are not {@linkplain #isEmpty(ColumnType, Span, Span) empty} together: as
     * {@link #valueInside(ColumnType, Comparable, Comparable, boolean)} picks one when only one of the two
     * is bounded (at offset zero for local times), else at offset zero if that fits, or at the nearest
     * offset that does.
     */
    static Object valueInside(ColumnType type, Span instants, Span localTimes) {
        if (localTimes.unbounded()) {
            return instants.point() ? instants.low() : valueInside(type, instants.low(), instants.high(), true);
        }
        if (instants.unbounded()) {
            var local = localTimes.point() ? localTimes.low() : valueInside(type, localTimes.low(), localTimes.high(), false);
            return type == ColumnType.TIME ? OffsetTime.of((LocalTime) local, ZoneOffset.UTC) : ((LocalDateTime) local).atZone(ZoneOffset.UTC);
        }
        return new ZonedBox(type, instants, localTimes).value();
    }

    /**
     * The instants and local times of a class of values with a time zone, as closed ranges of nanoseconds
     * (null: unbounded): instants as {@link PrimitiveKeys} keys them, local times since midnight or, for
     * date-times, since the epoch as if at offset zero.
     */
    private static final class ZonedBox {
        private final ColumnType type;
        private final BigInteger instantLow;
        private final BigInteger instantHigh;
        private final BigInteger localLow;
        private final BigInteger localHigh;

        ZonedBox(ColumnType type, Span instants, Span localTimes) {
            this.type = type;
            boolean time = type == ColumnType.TIME;
            instantLow = max(lowest(instants), time ? BigInteger.valueOf(EARLIEST_ZONED_TIME) : null);
            instantHigh = min(highest(instants), time ? BigInteger.valueOf(LATEST_ZONED_TIME) : null);
            localLow = max(lowest(localTimes), time ? BigInteger.ZERO : null);
            localHigh = min(highest(localTimes), time ? BigInteger.valueOf(NANOS_PER_DAY - 1) : null);
        }

        private static BigInteger lowest(Span span) {
            return span.low() == null ? null : span.point() ? nanos(span.low()) : nanos(span.low()).add(BigInteger.ONE);
        }

        private static BigInteger highest(Span span) {
            return span.high() == null ? null : span.point() ? nanos(span.high()) : nanos(span.high()).subtract(BigInteger.ONE);
        }

        private static BigInteger nanos(Object value) {
            return switch (value) {
                case LocalTime time -> BigInteger.valueOf(time.toNanoOfDay());
                case OffsetTime time -> BigInteger.valueOf(PrimitiveKeys.utcNanos(time));
                case LocalDateTime dateTime -> nanos(dateTime.toEpochSecond(ZoneOffset.UTC), dateTime.getNano());
                case ZonedDateTime dateTime -> nanos(dateTime.toEpochSecond(), dateTime.getNano());
                default -> throw new IllegalStateException("not a time or date-time: " + value);
            };
        }

        private static BigInteger nanos(long epochSecond, int nano) {
            return BigInteger.valueOf(epochSecond).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nano));
        }

        /** The larger of two lower bounds; null is unbounded. */
        private static BigInteger max(BigInteger a, BigInteger b) {
            return a == null ? b : b == null ? a : a.max(b);
        }

        /** The smaller of two upper bounds; null is unbounded. */
        private static BigInteger min(BigInteger a, BigInteger b) {
            return a == null ? b : b == null ? a : a.min(b);
        }

        private static boolean empty(BigInteger low, BigInteger high) {
            return low != null && high != null && low.compareTo(high) > 0;
        }

        /**
         * An offset, in seconds, that some value of the box has: zero if one has it, else the nearest to
         * zero. Null if the box holds no value. Local time minus instant spans every whole number of
         * nanoseconds from {@code localLow - instantHigh} to {@code localHigh - instantLow}.
         */
        BigInteger offsetSeconds() {
            if (empty(instantLow, instantHigh) || empty(localLow, localHigh)) {
                return null;
            }
            var least = MAX_OFFSET_SECONDS.negate();
            if (localLow != null && instantHigh != null) {
                var difference = localLow.subtract(instantHigh);
                var seconds = floorDiv(difference.add(NANOS_PER_SECOND).subtract(BigInteger.ONE)); // rounded up
                least = least.max(seconds);
            }
            var most = MAX_OFFSET_SECONDS;
            if (localHigh != null && instantLow != null) {
                most = most.min(floorDiv(localHigh.subtract(instantLow)));
            }
            if (least.compareTo(most) > 0) {
                return null;
            }
            if (least.signum() <= 0 && most.signum() >= 0) {
                return BigInteger.ZERO;
            }
            // the nearest to zero that is a whole number of hours, else of quarters of an hour, else of seconds
            for (var step : ROUND_OFFSETS) {
                var round = least.signum() > 0
                        ? floorDiv(least.add(step).subtract(BigInteger.ONE), step).multiply(step)
                        : floorDiv(most.negate().add(step).subtract(BigInteger.ONE), step).multiply(step).negate();
                if (round.compareTo(least) >= 0 && round.compareTo(most) <= 0) {
                    return round;
                }
            }
            return least.signum() > 0 ? least : most;
        }

        /** Whole seconds in a number of nanoseconds, rounded down. */
        private static BigInteger floorDiv(BigInteger nanos) {
            return floorDiv(nanos, NANOS_PER_SECOND);
        }

        private static BigInteger floorDiv(BigInteger number, BigInteger divisor) {
            var parts = number.divideAndRemainder(divisor);
            return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
        }

        /**
         * A value of the box, which is not empty: at {@link #offsetSeconds}, in the middle of the local times
         * that offset leaves (a day from their only bound, if they have one), on a whole minute or second if one is as near.
         */
        Object value() {
            var offset = offsetSeconds();
            var shift = offset.multiply(NANOS_PER_SECOND);
            var low = max(localLow, instantLow == null ? null : instantLow.add(shift));
            var high = min(localHigh, instantHigh == null ? null : instantHigh.add(shift));
            // between two bounds, the middle; beyond a single one (date-times only: a time's day bounds it), a day away
            var local = low == null ? high.subtract(NANOS_IN_A_DAY) : high == null ? low.add(NANOS_IN_A_DAY) : low.add(high).shiftRight(1);
            for (var unit : List.of(NANOS_PER_SECOND.multiply(BigInteger.valueOf(60)), NANOS_PER_SECOND)) {
                var whole = floorDiv(local, unit).multiply(unit);
                if (low == null || whole.compareTo(low) >= 0) {
                    local = whole;
                    break;
                }
            }
            var zone = ZoneOffset.ofTotalSeconds(offset.intValueExact());
            if (type == ColumnType.TIME) {
                return OffsetTime.of(LocalTime.ofNanoOfDay(local.longValueExact()), zone);
            }
            var parts = local.divideAndRemainder(NANOS_PER_SECOND);
            long seconds = parts[0].longValueExact();
            int nano = parts[1].intValueExact();
            if (nano < 0) {
                seconds--;
                nano += 1_000_000_000;
            }
            return ZonedDateTime.of(LocalDateTime.ofEpochSecond(seconds, nano, ZoneOffset.UTC), zone);
        }
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
