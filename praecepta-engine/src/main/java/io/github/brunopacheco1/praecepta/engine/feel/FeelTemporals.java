/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;

/**
 * Times and date-times with or without a time zone, as FEEL reads and orders them.
 *
 * <p>A FEEL time is a {@link LocalTime}, or an {@link OffsetTime} when it has an offset
 * ({@code 16:12:00+01:00}, {@code 16:12:00Z}). A FEEL date and time is a {@link LocalDateTime}, or a
 * {@link ZonedDateTime} when it has an offset or a zone id ({@code 2018-07-30T16:12:00Z},
 * {@code 2018-07-30T16:12:00@Europe/Paris}). Values with a zone are ordered, and equal, by the instant
 * they denote, so {@code 16:12:00+01:00} equals {@code 15:12:00Z}; values without one by their fields.
 * When only one of two values has a zone, the other takes its offset (DMN 1.5, Table 54: "If one input
 * has a null timezone offset, that input uses the timezone offset of the other input"), so the two
 * compare by their local time: {@code 16:12:00} equals {@code 16:12:00+01:00} and is after
 * {@code 15:12:00Z}. The table gives this rule for {@code <}; DMN 1.5 does not say how {@code =} treats such
 * a pair (Table 53), and the DMN TCK has no case for it. Equality is kept consistent with the comparisons
 * here, {@code <=} being defined as {@code <} or {@code =} (10.3.2.15): the two are equal exactly when
 * neither is before the other. Two values with a zone are equal by instant, as the TCK expects
 * ({@code 0068-feel-equality}), and a zone id counts by the offset it has at the value's date and time.
 *
 * <p>A time may also have a zone id ({@code 10:30:00@Europe/Paris}, a {@link ZonedTime}), which has no offset
 * without a date. Two such times in the same zone compare by their local time, which is all the TCK asks
 * ({@code 0068-feel-equality}); DMN 1.5 does not say how one compares with a time in another zone, at an offset or
 * without a zone, and the TCK leaves those cases out, so such a pair is not {@linkplain #comparable comparable}: a
 * comparison of it is null in FEEL's terms. A zone id with one fixed offset ({@code Etc/UTC}, {@code Etc/GMT-2},
 * {@code UTC}) is the exception: DMN 1.5's example of {@code time(from)} (Table 72) has
 * {@code time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC")} true, so such a time is the time at that
 * offset, an {@link OffsetTime} ({@link #timeIn}, {@link #withFixedOffset}): {@link #parseTime} reads it as one, and
 * every comparison here takes a {@link ZonedTime} in such a zone as one.
 */
public final class FeelTemporals {

    /**
     * The largest offset from UTC a time or date and time may have, either way: 14:00. DMN 1.5 takes times and date-times
     * in XML Schema's lexical forms (10.3.4.1, time string and date time string), whose offsets lie in -14:00..+14:00,
     * and bounds the time offset property the same way, {@code [duration("-PT14H")..duration("PT14H")]} (Table 66).
     */
    private static final int MAX_OFFSET_SECONDS = 14 * 60 * 60;

    private FeelTemporals() {
    }

    /**
     * Parses a time: {@code hh:mm[:ss[.fffffffff]]}, optionally followed by an offset ({@code Z},
     * {@code +01:00}) in -14:00..+14:00 or a zone id as FEEL writes it
     * ({@code @Europe/Paris}).
     *
     * @return a {@link LocalTime}, an {@link OffsetTime} if the text has an offset or a zone id with one fixed offset
     *         ({@code 00:01:00@Etc/UTC} is {@code 00:01Z}, DMN 1.5, Table 72), or a {@link ZonedTime} if it has another
     *         zone id
     * @throws DateTimeParseException if the text is not such a time, has an offset beyond -14:00..+14:00 (which
     *                                {@code 13:20:00+19:00} has: it is not read as {@code 13:20:00}), or names an
     *                                unknown zone
     */
    public static Object parseTime(String text) {
        if (text.indexOf('@') >= 0) {
            return withFixedOffset(ZonedTime.parse(text));
        }
        var parsed = DateTimeFormatter.ISO_TIME.parse(text);
        if (!parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
            return LocalTime.from(parsed);
        }
        // read before Java checks it against its own range (-18:00..+18:00), beyond which it would drop the offset
        requireOffsetInRange(parsed.getLong(ChronoField.OFFSET_SECONDS), text);
        return OffsetTime.from(parsed);
    }

    /** Whether an offset from UTC, in seconds, is one FEEL takes: in -14:00..+14:00 ({@link #MAX_OFFSET_SECONDS}). */
    static boolean isOffsetInRange(long seconds) {
        return Math.abs(seconds) <= MAX_OFFSET_SECONDS;
    }

    private static void requireOffsetInRange(long seconds, String text) {
        if (!isOffsetInRange(seconds)) {
            throw new DateTimeParseException("an offset beyond -14:00..+14:00", text, 0);
        }
    }

    /**
     * A local time in a zone, as FEEL has it: the {@link OffsetTime} at the zone's offset when the zone has one fixed
     * offset (a {@link ZoneOffset}, or a zone id such as {@code Etc/UTC} or {@code Etc/GMT-2}), as DMN 1.5's example of
     * {@code time(from)} has {@code time("00:01:00@Etc/UTC")} equal {@code 00:01:00Z} (Table 72); else the
     * {@link ZonedTime}, whose zone id gives no offset without a date.
     */
    public static Object timeIn(LocalTime time, ZoneId zone) {
        var offset = fixedOffset(zone);
        return offset != null ? OffsetTime.of(time, offset) : new ZonedTime(time, zone);
    }

    /**
     * A {@link ZonedTime} in a zone with one fixed offset as the {@link OffsetTime} at that offset, which FEEL takes it
     * to be (DMN 1.5, Table 72; see {@link #timeIn}); any other value as it is. The engine reads every time it is given
     * through it, so that a time bound as {@code ZonedTime(00:01, Etc/UTC)} matches as {@code 00:01Z}.
     */
    public static Object withFixedOffset(Object value) {
        if (value instanceof ZonedTime time) {
            var offset = fixedOffset(time.zone());
            return offset != null ? OffsetTime.of(time.time(), offset) : time;
        }
        return value;
    }

    /** The offset of a zone that has one fixed offset, whatever the date; null for a zone whose offset changes. */
    private static ZoneOffset fixedOffset(ZoneId zone) {
        if (zone instanceof ZoneOffset offset) {
            return offset;
        }
        var rules = zone.getRules();
        return rules.isFixedOffset() ? rules.getOffset(Instant.EPOCH) : null;
    }

    /**
     * Parses a time with an offset, or a zone id with one fixed offset, as {@link #parseTime} reads it. Generated
     * code uses it to read the outputs of a time column whose values have a time zone, which may be written either
     * way ({@code 00:01:00Z}, {@code 00:01:00@Etc/UTC}).
     *
     * @throws DateTimeParseException if the text is not such a time
     */
    public static OffsetTime parseOffsetTime(String text) {
        if (parseTime(text) instanceof OffsetTime time) {
            return time;
        }
        throw new DateTimeParseException("a time without a time zone, or in a zone whose offset changes", text, text.length());
    }

    /**
     * Parses a time with a zone id ({@code 10:30:00@Europe/Paris}) or an offset ({@code 10:30:00+01:00}) as a
     * {@link ZonedTime}: an offset becomes the zone id that has it fixed ({@code UTC+01:00}, {@code UTC} for
     * {@code Z}), which the engine reads back as the time at that offset. Generated code uses it to read the outputs
     * of a time column whose values have zone ids, which may also be at a fixed offset (DMN 1.5, Table 72), and
     * which the engine may write with that offset ({@code 00:01:00Z} for an input bound as
     * {@code 00:01:00@Etc/UTC}).
     *
     * @throws DateTimeParseException if the text is not such a time
     */
    public static ZonedTime parseZonedTime(String text) {
        if (text.indexOf('@') >= 0) {
            return ZonedTime.parse(text);
        }
        if (parseTime(text) instanceof OffsetTime time) {
            return new ZonedTime(time.toLocalTime(), ZoneId.ofOffset("UTC", time.getOffset()));
        }
        throw new DateTimeParseException("a time without a time zone", text, text.length());
    }

    /**
     * Parses a date and time: {@code yyyy-mm-ddThh:mm[:ss[.fffffffff]]}, optionally followed by an
     * offset ({@code Z}, {@code +01:00}) in -14:00..+14:00, a zone id as FEEL writes it
     * ({@code @Europe/Paris}), or an offset and a zone id as {@link ZonedDateTime#toString()} writes them
     * ({@code +02:00[Europe/Paris]}). A zone id gives the offset its rules have at that date and time, which before
     * 1900 may be a local mean time beyond 14:00 ({@code America/Sitka}'s +14:58:47 in 1800).
     *
     * @return a {@link LocalDateTime}, or a {@link ZonedDateTime} if the text has an offset or a zone id
     * @throws DateTimeParseException if the text is not such a date and time, has an offset beyond -14:00..+14:00, or
     *                                names an unknown zone
     */
    public static Temporal parseDateTime(String text) {
        int at = text.indexOf('@');
        if (at >= 0) {
            var local = LocalDateTime.parse(text.substring(0, at));
            try {
                return ZonedDateTime.of(local, ZoneId.of(text.substring(at + 1)));
            } catch (DateTimeException e) {
                throw new DateTimeParseException("unknown zone id: " + e.getMessage(), text, at + 1, e);
            }
        }
        var dateTime = (Temporal) DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
        if (dateTime instanceof ZonedDateTime zoned && zoned.getZone() instanceof ZoneOffset offset) {
            requireOffsetInRange(offset.getTotalSeconds(), text);
        }
        return dateTime;
    }

    /**
     * Parses a date and time that has an offset or a zone id, as {@link #parseDateTime} reads it. Generated
     * code uses it to read zoned date-time outputs.
     *
     * @throws DateTimeParseException if the text is not a date and time, or has no offset or zone id
     */
    public static ZonedDateTime parseZonedDateTime(String text) {
        if (parseDateTime(text) instanceof ZonedDateTime zoned) {
            return zoned;
        }
        throw new DateTimeParseException("a date and time without a time zone", text, text.length());
    }

    /**
     * Whether a value is a time or date-time with a time zone that has an offset: an {@link OffsetTime} or a
     * {@link ZonedDateTime}, or a {@link ZonedTime} in a zone with one fixed offset ({@link #withFixedOffset}). Any
     * other {@link ZonedTime} has none, as its zone id gives no offset without a date.
     */
    public static boolean hasZone(Object value) {
        var fixed = withFixedOffset(value);
        return fixed instanceof OffsetTime || fixed instanceof ZonedDateTime;
    }

    /**
     * A time or date-time without its time zone: the {@link LocalTime} of an {@link OffsetTime}, the
     * {@link LocalDateTime} of a {@link ZonedDateTime}; any other value as it is. A value with a zone is
     * compared by it when the other value has none.
     */
    public static Object local(Object value) {
        return switch (withFixedOffset(value)) {
            case OffsetTime time -> time.toLocalTime();
            case ZonedDateTime dateTime -> dateTime.toLocalDateTime();
            default -> value;
        };
    }

    /**
     * Orders two values of one column type as FEEL does: times and date-times that both have a zone by
     * instant (so equal instants compare as 0, whatever their zones); when only one has a zone, the
     * other takes its offset, so the two are ordered by their {@linkplain #local local} time; two times with a
     * zone id in the same zone by local time, a time in a zone with one fixed offset being the time at that offset
     * ({@link #withFixedOffset}); every
     * string by character ({@link FeelStrings#compare}), and every other value by its natural order. Across
     * values with and without a zone this order is not
     * transitive ({@code 08:00Z} is before {@code 08:30}, which is before {@code 09:00+02:00}, the
     * instant before {@code 08:00Z}), so sort only values of one kind with it.
     *
     * @throws IllegalArgumentException if the two are not {@linkplain #comparable comparable}: a time with a zone id
     *                                  and a time in another zone, or of another kind
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object first, Object second) {
        var a = withFixedOffset(first);
        var b = withFixedOffset(second);
        if (a instanceof ZonedTime || b instanceof ZonedTime) {
            if (!comparable(a, b)) {
                throw new IllegalArgumentException("FEEL does not define how %s compares with %s".formatted(a, b));
            }
            return ((ZonedTime) a).time().compareTo(((ZonedTime) b).time());
        }
        if (hasZone(a) != hasZone(b)) {
            return ((Comparable) local(a)).compareTo(local(b));
        }
        return switch (a) {
            case OffsetTime time -> {
                var other = (OffsetTime) b;
                yield order(time.isBefore(other), time.isAfter(other));
            }
            case ZonedDateTime dateTime -> {
                var other = (ZonedDateTime) b;
                yield order(dateTime.isBefore(other), dateTime.isAfter(other));
            }
            case String text -> FeelStrings.compare(text, (String) b);
            default -> ((Comparable) a).compareTo(b);
        };
    }

    /**
     * Whether {@link #compare} orders two values of one column type: always, unless one is a time with a zone id
     * ({@link ZonedTime}) in a zone whose offset changes, which compares only with a time in the same zone. FEEL gives
     * null for a comparison it does not define. A time in a zone with one fixed offset is the time at that offset
     * ({@link #withFixedOffset}), which compares with every time.
     */
    public static boolean comparable(Object first, Object second) {
        var a = withFixedOffset(first);
        var b = withFixedOffset(second);
        if (a instanceof ZonedTime x) {
            return b instanceof ZonedTime y && x.zone().equals(y.zone());
        }
        return !(b instanceof ZonedTime);
    }

    /** -1, 1 or 0 as a value is before, after or at the same instant as another. */
    private static int order(boolean before, boolean after) {
        if (before) {
            return -1;
        }
        return after ? 1 : 0;
    }
}
