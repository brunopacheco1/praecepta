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

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "x          | NUMBER              | 'x' is not a number",
            "yes        | BOOLEAN             | 'yes' is not a boolean",
            "2025-02-30 | DATE                | '2025-02-30' is not a date",
            "25:00      | TIME                | '25:00' is not a time",
            "P1D        | YEAR_MONTH_DURATION | 'P1D' is not a years and months duration",
            "P1Y        | DAY_TIME_DURATION   | 'P1Y' is not a days and time duration",
    })
    void text_that_is_not_a_value_of_the_type_is_rejected(String text, ColumnType type, String message) {
        Assertions.assertThatThrownBy(() -> FeelLiterals.value(text, type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
