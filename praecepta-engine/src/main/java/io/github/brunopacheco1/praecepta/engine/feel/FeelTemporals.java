/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 */
public final class FeelTemporals {

    private FeelTemporals() {
    }

    /**
     * Parses a time: {@code hh:mm[:ss[.fffffffff]]}, optionally followed by an offset ({@code Z},
     * {@code +01:00}).
     *
     * @return a {@link LocalTime}, or an {@link OffsetTime} if the text has an offset
     * @throws DateTimeParseException if the text is not such a time; a time with a zone id
     *                                ({@code @Europe/Paris}) is not, as it has no fixed offset
     */
    public static Temporal parseTime(String text) {
        if (text.indexOf('@') >= 0) {
            throw new DateTimeParseException("a time with a zone id has no fixed offset", text, text.indexOf('@'));
        }
        return (Temporal) DateTimeFormatter.ISO_TIME.parseBest(text, OffsetTime::from, LocalTime::from);
    }

    /**
     * Parses a date and time: {@code yyyy-mm-ddThh:mm[:ss[.fffffffff]]}, optionally followed by an
     * offset ({@code Z}, {@code +01:00}), a zone id as FEEL writes it ({@code @Europe/Paris}), or an
     * offset and a zone id as {@link ZonedDateTime#toString()} writes them ({@code +02:00[Europe/Paris]}).
     *
     * @return a {@link LocalDateTime}, or a {@link ZonedDateTime} if the text has an offset or a zone id
     * @throws DateTimeParseException if the text is not such a date and time, or names an unknown zone
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
        return (Temporal) DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
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

    /** Whether a value is a time or date-time with a time zone: an {@link OffsetTime} or a {@link ZonedDateTime}. */
    public static boolean hasZone(Object value) {
        return value instanceof OffsetTime || value instanceof ZonedDateTime;
    }

    /**
     * A time or date-time without its time zone: the {@link LocalTime} of an {@link OffsetTime}, the
     * {@link LocalDateTime} of a {@link ZonedDateTime}; any other value as it is. A value with a zone is
     * compared by it when the other value has none.
     */
    public static Object local(Object value) {
        return switch (value) {
            case OffsetTime time -> time.toLocalTime();
            case ZonedDateTime dateTime -> dateTime.toLocalDateTime();
            default -> value;
        };
    }

    /**
     * Orders two values of one column type as FEEL does: times and date-times that both have a zone by
     * instant (so equal instants compare as 0, whatever their zones); when only one has a zone, the
     * other takes its offset, so the two are ordered by their {@linkplain #local local} time; every
     * other value by its natural order. Across values with and without a zone this order is not
     * transitive ({@code 08:00Z} is before {@code 08:30}, which is before {@code 09:00+02:00}, the
     * instant before {@code 08:00Z}), so sort only values of one kind with it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
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
            default -> ((Comparable) a).compareTo(b);
        };
    }

    /** -1, 1 or 0 as a value is before, after or at the same instant as another. */
    private static int order(boolean before, boolean after) {
        if (before) {
            return -1;
        }
        return after ? 1 : 0;
    }
}
