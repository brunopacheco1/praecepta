/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * Writes column values ({@code String}, {@code BigDecimal}, {@code LocalDate}, {@code Boolean},
 * {@code LocalTime}, {@code OffsetTime}, {@link ZonedTime}, {@code LocalDateTime}, {@code ZonedDateTime}, {@code Duration},
 * {@link YearsAndMonths}) as FEEL literals, or as the plain text that outputs and map-based inputs use.
 * {@link FeelTemporals} reads times and date-times back from that text.
 */
public final class FeelLiterals {

    private FeelLiterals() {
    }

    /**
     * The FEEL literal of a value: {@code "PT"} (escaped), {@code 2.5}, {@code true},
     * {@code date("2025-01-31")}, {@code time("08:30:00")}, {@code time("08:30:00+01:00")}, {@code time("08:30:00@Europe/Paris")},
     * {@code date and time("2025-01-31T08:30:00")}, {@code date and time("2025-01-31T08:30:00Z")},
     * {@code date and time("2025-01-31T08:30:00@Europe/Paris")}, {@code duration("P1DT2H")},
     * {@code duration("P1Y2M")}, or {@code null}.
     */
    public static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> "\"" + FeelStrings.escaped(text) + "\"";
            case LocalDate date -> "date(\"" + date + "\")";
            case LocalTime time -> "time(\"" + text(time) + "\")";
            case OffsetTime time -> "time(\"" + text(time) + "\")";
            case ZonedTime time -> "time(\"" + time + "\")";
            case LocalDateTime dateTime -> "date and time(\"" + text(dateTime) + "\")";
            // FEEL has no literal for the later of two equal local times in a zone: the offset keeps its instant
            case ZonedDateTime dateTime -> "date and time(\"" + (isLaterAtOverlap(dateTime)
                    ? DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime) : text(dateTime)) + "\")";
            case Duration duration -> "duration(\"" + text(duration) + "\")";
            case YearsAndMonths duration -> "duration(\"" + duration + "\")";
            default -> text(value); // numbers and booleans
        };
    }

    /**
     * The plain text of a value: decimals without an exponent, ISO dates and times with their seconds
     * and, if they have one, their offset ({@code 08:30:00Z}, {@code 2025-01-31T08:30:00+01:00}) or zone
     * id as FEEL writes it ({@code 2025-01-31T08:30:00@Europe/Paris}), ISO durations as FEEL writes them
     * ({@code P1DT2H}, {@code P1Y2M}). A date-time whose local time occurs twice in its zone, as clocks go
     * back, and that is the later of the two, is written with its offset and zone id
     * ({@code 2025-10-26T02:30:00+01:00[Europe/Paris]}): the zone id alone would read back as the earlier
     * one. {@link FeelTemporals#parseDateTime} reads all of them back to the same instant.
     */
    public static String text(Object value) {
        return switch (value) {
            case BigDecimal number -> number.toPlainString();
            case Duration duration -> durationText(duration);
            case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
            case OffsetTime time -> DateTimeFormatter.ISO_OFFSET_TIME.format(time);
            case LocalDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
            case ZonedDateTime dateTime when dateTime.getZone() instanceof ZoneOffset -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
            case ZonedDateTime dateTime when isLaterAtOverlap(dateTime) -> DateTimeFormatter.ISO_ZONED_DATE_TIME.format(dateTime);
            case ZonedDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime) + "@" + dateTime.getZone().getId();
            default -> value.toString();
        };
    }

    /**
     * Reads a value of a column type back from its plain text, as {@link #text} writes it and as rules' outputs
     * and map-based inputs hold it: a number ({@code 2.5}), {@code true} or {@code false}, an ISO date, a time
     * or date-time with or without a time zone (as {@link FeelTemporals} reads them), or a duration
     * ({@code P1DT2H}, {@code P1Y2M}). A string is its own value; other text is read without its surrounding
     * whitespace.
     *
     * @return a {@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate}, {@code LocalTime},
     *         {@code OffsetTime} or {@link ZonedTime}, {@code LocalDateTime} or {@code ZonedDateTime}, {@code Duration}, or
     *         {@link YearsAndMonths}
     * @throws IllegalArgumentException if the text is not a value of the type
     */
    public static Object value(String text, ColumnType type) {
        if (type == ColumnType.STRING) {
            return text;
        }
        var value = text.strip();
        try {
            return switch (type) {
                case NUMBER -> new BigDecimal(value);
                case BOOLEAN -> bool(value);
                case DATE -> LocalDate.parse(value);
                case TIME -> FeelTemporals.parseTime(value);
                case DATE_TIME -> FeelTemporals.parseDateTime(value);
                case DAY_TIME_DURATION -> Duration.parse(value);
                case YEAR_MONTH_DURATION -> YearsAndMonths.parse(value);
                case STRING -> text;
            };
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IllegalArgumentException("'%s' is not a %s".formatted(text, type.feelName()), e);
        }
    }

    private static Boolean bool(String text) {
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("not a boolean");
        };
    }

    /** Whether the date-time's local time occurs twice in its zone (clocks going back), and it is the later one. */
    private static boolean isLaterAtOverlap(ZonedDateTime dateTime) {
        return !dateTime.equals(dateTime.withEarlierOffsetAtOverlap());
    }

    /** A days and time duration with its days, as FEEL writes it: {@code P1DT2H30M}, {@code -PT0.5S}, {@code PT0S}. */
    private static String durationText(Duration duration) {
        if (duration.isZero()) {
            return "PT0S";
        }
        var length = duration.abs();
        var text = new StringBuilder(duration.isNegative() ? "-P" : "P");
        long days = length.toDays();
        if (days != 0) {
            text.append(days).append('D');
        }
        var time = length.minusDays(days);
        if (!time.isZero()) {
            text.append('T');
            if (time.toHoursPart() != 0) {
                text.append(time.toHoursPart()).append('H');
            }
            if (time.toMinutesPart() != 0) {
                text.append(time.toMinutesPart()).append('M');
            }
            if (time.toSecondsPart() != 0 || time.toNanosPart() != 0) {
                var seconds = BigDecimal.valueOf(time.toSecondsPart()).add(BigDecimal.valueOf(time.toNanosPart(), 9));
                text.append(seconds.stripTrailingZeros().toPlainString()).append('S');
            }
        }
        return text.toString();
    }
}
