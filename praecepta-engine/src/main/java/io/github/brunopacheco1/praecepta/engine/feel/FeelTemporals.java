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

/**
 * Times and date-times with or without a time zone, as FEEL reads and orders them.
 *
 * <p>A FEEL time is a {@link LocalTime}, or an {@link OffsetTime} when it has an offset
 * ({@code 16:12:00+01:00}, {@code 16:12:00Z}). A FEEL date and time is a {@link LocalDateTime}, or a
 * {@link ZonedDateTime} when it has an offset or a zone id ({@code 2018-07-30T16:12:00Z},
 * {@code 2018-07-30T16:12:00@Europe/Paris}). Values with a zone are ordered, and equal, by the instant
 * they denote, so {@code 16:12:00+01:00} equals {@code 15:12:00Z}; values without one by their fields.
 * A value with a zone and one without cannot be compared: FEEL leaves that comparison undefined (null),
 * which a unary test treats as unknown.
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
    public static Comparable<?> parseTime(String text) {
        if (text.indexOf('@') >= 0) {
            throw new DateTimeParseException("a time with a zone id has no fixed offset", text, text.indexOf('@'));
        }
        var parsed = DateTimeFormatter.ISO_TIME.parseBest(text, OffsetTime::from, LocalTime::from);
        return (Comparable<?>) parsed;
    }

    /**
     * Parses a date and time: {@code yyyy-mm-ddThh:mm[:ss[.fffffffff]]}, optionally followed by an
     * offset ({@code Z}, {@code +01:00}), a zone id as FEEL writes it ({@code @Europe/Paris}), or an
     * offset and a zone id as {@link ZonedDateTime#toString()} writes them ({@code +02:00[Europe/Paris]}).
     *
     * @return a {@link LocalDateTime}, or a {@link ZonedDateTime} if the text has an offset or a zone id
     * @throws DateTimeParseException if the text is not such a date and time, or names an unknown zone
     */
    public static Comparable<?> parseDateTime(String text) {
        int at = text.indexOf('@');
        if (at >= 0) {
            var local = LocalDateTime.parse(text.substring(0, at));
            try {
                return ZonedDateTime.of(local, ZoneId.of(text.substring(at + 1)));
            } catch (DateTimeException e) {
                throw new DateTimeParseException("unknown zone id: " + e.getMessage(), text, at + 1, e);
            }
        }
        var parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
        return (Comparable<?>) parsed;
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
     * Whether FEEL can compare two values of one column type: always, unless one has a time zone and the
     * other has none.
     */
    public static boolean comparable(Object a, Object b) {
        return hasZone(a) == hasZone(b);
    }

    /**
     * Orders two {@linkplain #comparable comparable} values of one column type as FEEL does: times and
     * date-times with a zone by instant (so equal instants compare as 0, whatever their zones), every
     * other value by its natural order.
     *
     * @throws IllegalArgumentException if one value has a time zone and the other has none
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (!comparable(a, b)) {
            throw new IllegalArgumentException("'%s' and '%s' cannot be compared: only one has a time zone".formatted(a, b));
        }
        return switch (a) {
            case OffsetTime time -> {
                var other = (OffsetTime) b;
                yield time.isBefore(other) ? -1 : time.isAfter(other) ? 1 : 0;
            }
            case ZonedDateTime dateTime -> {
                var other = (ZonedDateTime) b;
                yield dateTime.isBefore(other) ? -1 : dateTime.isAfter(other) ? 1 : 0;
            }
            default -> ((Comparable) a).compareTo(b);
        };
    }
}
