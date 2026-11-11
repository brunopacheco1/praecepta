/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes column values ({@code String}, {@code BigDecimal}, {@code LocalDate}, {@code Boolean},
 * {@code LocalTime}, {@code LocalDateTime}, {@code Duration}, {@link YearsAndMonths}) as FEEL literals,
 * or as the plain text that outputs and map-based inputs use.
 */
public final class FeelLiterals {

    private FeelLiterals() {
    }

    /**
     * The FEEL literal of a value: {@code "PT"} (escaped), {@code 2.5}, {@code true},
     * {@code date("2025-01-31")}, {@code time("08:30:00")}, {@code date and time("2025-01-31T08:30:00")},
     * {@code duration("P1DT2H")}, {@code duration("P1Y2M")}, or {@code null}.
     */
    public static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case LocalDate date -> "date(\"" + date + "\")";
            case LocalTime time -> "time(\"" + text(time) + "\")";
            case LocalDateTime dateTime -> "date and time(\"" + text(dateTime) + "\")";
            case Duration duration -> "duration(\"" + text(duration) + "\")";
            case YearsAndMonths duration -> "duration(\"" + duration + "\")";
            default -> text(value); // numbers and booleans
        };
    }

    /**
     * The plain text of a value: decimals without an exponent, ISO dates and times with their seconds,
     * ISO durations as FEEL writes them ({@code P1DT2H}, {@code P1Y2M}).
     */
    public static String text(Object value) {
        return switch (value) {
            case BigDecimal number -> number.toPlainString();
            case Duration duration -> durationText(duration);
            case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
            case LocalDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
            default -> value.toString();
        };
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
