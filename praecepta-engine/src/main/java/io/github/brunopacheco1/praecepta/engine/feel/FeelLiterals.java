/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes column values ({@code String}, {@code BigDecimal}, {@code LocalDate}, {@code Boolean},
 * {@code LocalTime}, {@code LocalDateTime}) as FEEL literals, or as the plain text that outputs and
 * map-based inputs use.
 */
public final class FeelLiterals {

    private FeelLiterals() {
    }

    /**
     * The FEEL literal of a value: {@code "PT"} (escaped), {@code 2.5}, {@code true},
     * {@code date("2025-01-31")}, {@code time("08:30:00")}, {@code date and time("2025-01-31T08:30:00")},
     * or {@code null}.
     */
    public static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case LocalDate date -> "date(\"" + date + "\")";
            case LocalTime time -> "time(\"" + text(time) + "\")";
            case LocalDateTime dateTime -> "date and time(\"" + text(dateTime) + "\")";
            default -> text(value); // numbers and booleans
        };
    }

    /** The plain text of a value: decimals without an exponent, ISO dates and times with their seconds. */
    public static String text(Object value) {
        return switch (value) {
            case BigDecimal number -> number.toPlainString();
            case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
            case LocalDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
            default -> value.toString();
        };
    }
}
