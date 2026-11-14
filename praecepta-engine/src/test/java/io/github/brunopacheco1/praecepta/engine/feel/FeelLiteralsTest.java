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
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

class FeelLiteralsTest {

    @Test
    void a_values_plain_text_reads_back_as_that_value() {
        var values = List.<Object>of(" a b ", new BigDecimal("-1.50"), Boolean.TRUE, LocalDate.of(2025, 1, 31), LocalTime.of(8, 30),
                OffsetTime.of(8, 30, 0, 0, ZoneOffset.ofHours(1)), LocalDateTime.of(2025, 1, 31, 8, 30),
                ZonedDateTime.of(2025, 1, 31, 8, 30, 0, 0, ZoneId.of("Europe/Paris")), Duration.ofHours(26), new YearsAndMonths(14));
        var types = List.of(ColumnType.STRING, ColumnType.NUMBER, ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.TIME, ColumnType.TIME,
                ColumnType.DATE_TIME, ColumnType.DATE_TIME, ColumnType.DAY_TIME_DURATION, ColumnType.YEAR_MONTH_DURATION);
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            Assertions.assertThat(FeelLiterals.value(FeelLiterals.text(value), types.get(i))).as("%s", value).isEqualTo(value);
        }
        Assertions.assertThat(FeelLiterals.value(" 2.5 ", ColumnType.NUMBER)).isEqualTo(new BigDecimal("2.5"));
    }

    /**
     * A year of more than four digits is written as XML Schema writes it (DMN 1.5, 10.3.4.1), without the {@code +} Java
     * puts before it and FEEL does not read (#124), and reads back; as the DMN TCK writes such years
     * ({@code 1117-feel-date-and-time-function}, {@code 011} and {@code 012}).
     */
    @Test
    void years_beyond_four_digits_are_written_without_a_plus_and_read_back() {
        var values = List.<Object>of(LocalDate.of(99999, 12, 31), LocalDate.of(-99999, 12, 31), LocalDateTime.of(99999, 12, 31, 11, 22, 33),
                ZonedDateTime.of(999999999, 12, 31, 23, 59, 59, 999999999, ZoneId.of("Europe/Paris")),
                ZonedDateTime.of(99999, 12, 31, 11, 22, 33, 0, ZoneOffset.ofHours(2)));
        var texts = List.of("99999-12-31", "-99999-12-31", "99999-12-31T11:22:33", "999999999-12-31T23:59:59.999999999@Europe/Paris",
                "99999-12-31T11:22:33+02:00");
        var literals = List.of("date(\"99999-12-31\")", "date(\"-99999-12-31\")", "date and time(\"99999-12-31T11:22:33\")",
                "date and time(\"999999999-12-31T23:59:59.999999999@Europe/Paris\")", "date and time(\"99999-12-31T11:22:33+02:00\")");
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            var type = value instanceof LocalDate ? ColumnType.DATE : ColumnType.DATE_TIME;
            Assertions.assertThat(FeelLiterals.text(value)).isEqualTo(texts.get(i));
            Assertions.assertThat(FeelLiterals.value(texts.get(i), type)).isEqualTo(value);
            Assertions.assertThat(FeelLiterals.literal(value)).isEqualTo(literals.get(i));
            Assertions.assertThat(CellParser.parse(literals.get(i), type)).isEqualTo(CellParser.parse("@\"" + texts.get(i) + "\"", type));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "x          | NUMBER              | 'x' is not a number",
            "yes        | BOOLEAN             | 'yes' is not a boolean",
            "2025-02-30 | DATE                | '2025-02-30' is not a date",
            "25:00      | TIME                | '25:00' is not a time",
            "P1D        | YEAR_MONTH_DURATION | 'P1D' is not a years and months duration",
            "P1Y        | DAY_TIME_DURATION   | 'P1Y' is not a days and time duration",
            "+2012-12-02 | DATE               | '+2012-12-02' is not a date",
            "+99999-12-31T00:00:00 | DATE_TIME | '+99999-12-31T00:00:00' is not a date and time",
    })
    void text_that_is_not_a_value_of_the_type_is_rejected(String text, ColumnType type, String message) {
        Assertions.assertThatThrownBy(() -> FeelLiterals.value(text, type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
