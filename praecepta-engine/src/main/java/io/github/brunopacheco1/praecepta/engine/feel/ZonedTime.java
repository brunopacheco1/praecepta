/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * A FEEL time with a zone id, such as {@code time("10:30:00@Europe/Paris")}: "the extended form of a local time
 * representation as specified by ISO 8601, followed by the character "@", followed by [...] a time zone identifier in
 * the IANA Time Zones Database" (DMN 1.5, 10.3.4.1). Without a date, a zone id has no offset, so {@link java.time.OffsetTime}
 * cannot hold such a time.
 *
 * <p>FEEL compares two such times in the same zone by their local time ({@link FeelTemporals#compare}): the TCK's
 * {@code 0068-feel-equality} has {@code time("10:30:00@Europe/Paris") = time("10:30:00@Europe/Paris")} true. How one
 * compares with a time in another zone, with an offset or without a zone, DMN 1.5 does not say and the TCK leaves out
 * ("pending RTF equality comparisons with zones clarification"); Praecepta takes such a comparison as one FEEL does not
 * define, which is null, so neither a test nor its {@code not(...)} holds.
 *
 * <p>{@link #compareTo} orders by zone id, then local time, so that such times can be sorted; it is not FEEL's order.
 *
 * @param time the local time of day
 * @param zone the zone id, a region such as {@code Europe/Paris} (an offset makes an {@link java.time.OffsetTime})
 */
public record ZonedTime(LocalTime time, ZoneId zone) implements Comparable<ZonedTime> {

    public ZonedTime {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        if (zone instanceof ZoneOffset) {
            throw new IllegalArgumentException("a time at an offset is an OffsetTime, not a time with a zone id: " + zone);
        }
    }

    /**
     * Parses {@code hh:mm[:ss[.fffffffff]]@Zone/Id}, as FEEL writes such a time.
     *
     * @throws DateTimeParseException if the text is not such a time, or names an unknown zone or an offset
     */
    public static ZonedTime parse(String text) {
        int at = text.indexOf('@');
        if (at < 0) {
            throw new DateTimeParseException("a time with a zone id is written hh:mm:ss@Zone/Id", text, 0);
        }
        var time = LocalTime.parse(text.substring(0, at));
        try {
            return new ZonedTime(time, ZoneId.of(text.substring(at + 1)));
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new DateTimeParseException("not a zone id: " + e.getMessage(), text, at + 1, e);
        }
    }

    @Override
    public int compareTo(ZonedTime other) {
        int byZone = zone.getId().compareTo(other.zone.getId());
        return byZone != 0 ? byZone : time.compareTo(other.time);
    }

    /** The time as FEEL writes it: {@code 10:30:00@Europe/Paris}. */
    @Override
    public String toString() {
        return DateTimeFormatter.ISO_LOCAL_TIME.format(time) + "@" + zone.getId();
    }
}
