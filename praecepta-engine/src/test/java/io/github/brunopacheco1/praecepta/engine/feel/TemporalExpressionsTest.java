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
import java.util.HashMap;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/** Output expressions over dates, times, date-times and durations (#85): DMN 1.5, Tables 57, 59 and 62. */
class TemporalExpressionsTest {

    private static final Map<String, ColumnType> TYPES = Map.of("due", ColumnType.DATE, "sent", ColumnType.DATE_TIME,
            "returned", ColumnType.DATE_TIME, "opens", ColumnType.TIME, "elapsed", ColumnType.DAY_TIME_DURATION,
            "tenure", ColumnType.YEAR_MONTH_DURATION, "days", ColumnType.NUMBER, "name", ColumnType.STRING, "ok", ColumnType.BOOLEAN);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // the examples of #85, one per row of Tables 57 and 59
            "returned - sent                      | DAY_TIME_DURATION   | P1DT2H30M",
            "due + duration(\"P1Y\")              | DATE                | 2026-01-31",
            "due + duration(\"P14D\")             | DATE                | 2025-02-14",
            "sent - duration(\"PT30M\")           | DATE_TIME           | 2025-01-31T07:30",
            "opens + @\"PT30M\"                   | TIME                | 09:00",
            "tenure + duration(\"P1M\")           | YEAR_MONTH_DURATION | P1Y3M",
            "duration(\"PT8H\") * days            | DAY_TIME_DURATION   | P1D",
            "elapsed / duration(\"PT1H\")         | NUMBER              | 26.5",
            "tenure / @\"P2M\"                    | NUMBER              | 7",
            "-elapsed                             | DAY_TIME_DURATION   | -P1DT2H30M",
            "-tenure * 2                          | YEAR_MONTH_DURATION | -P2Y4M",
            "@\"P1Y\" + due                       | DATE                | 2026-01-31",
            "due - date(2025, 1, 1)               | DAY_TIME_DURATION   | P30D",
            "opens - time(8, 0, 0)                | DAY_TIME_DURATION   | PT30M",
            "elapsed / 2 + @\"PT45M\"             | DAY_TIME_DURATION   | PT14H",
            "tenure * 1.5                         | YEAR_MONTH_DURATION | P1Y9M",
            "name + \" by \" + \"2025\"           | STRING              | a by 2025",
    })
    void dates_times_and_durations_combine_as_feel_defines(String text, ColumnType type, String value) {
        var expression = OutputExpression.parse(text);
        Assertions.assertThat(expression.type(TYPES::get)).isEqualTo(type);
        var result = expression.evaluate(inputs()::get);
        Assertions.assertThat(result).isEqualTo(type == ColumnType.NUMBER ? result : FeelLiterals.value(value, type));
        if (type == ColumnType.NUMBER) {
            Assertions.assertThat((BigDecimal) result).isEqualByComparingTo(value);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // a day the month does not have: date(2025, 2, 31) is null (Table 57)
            "due + duration(\"P1M\")",
            // a date and time with a zone minus one without (Table 57), and a date, which counts as UTC midnight
            "date and time(\"2025-01-31T10:00:00@Europe/Paris\") - sent",
            "due - sent",
            // a time with a zone id and one without: DMN 1.5 leaves it open
            "time(\"10:00:00@Europe/Paris\") - opens",
            "elapsed / 0",
            "tenure / @\"P0M\"",
            "duration(\"P999999999Y\") * 1e10",
    })
    void what_feel_does_not_define_for_these_values_is_null(String text) {
        Assertions.assertThat(OutputExpression.parse(text).evaluate(inputs()::get)).isNull();
    }

    /**
     * Months and years move the date's fields and keep its day (DMN 1.5, Table 57: {@code date(e1.year + ..., e1.month
     * + ..., e1.day)}), which is null where the month does not have that day, 29 February included; days and times
     * move a date and time with a zone by instant ({@code valuedt}, 10.3.2.3.5), across a change of offset too.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "date(\"2024-02-29\") + @\"P1Y\"                                        | DATE      | null",
            "date(\"2024-02-29\") - @\"P12M\"                                       | DATE      | null",
            "@\"P4Y\" + date(\"2024-02-29\")                                        | DATE      | 2028-02-29",
            "date(\"2024-02-29\") - @\"P48M\"                                       | DATE      | 2020-02-29",
            "date(\"2024-02-28\") + @\"P1D\"                                        | DATE      | 2024-02-29",
            "date(\"2025-02-28\") + @\"P1D\"                                        | DATE      | 2025-03-01",
            "date(\"2024-01-31\") + @\"P1M\"                                        | DATE      | null",
            "date(\"2024-01-29\") + @\"P1M\"                                        | DATE      | 2024-02-29",
            "date and time(\"2025-01-31T10:00:00\") + @\"P1M\"                      | DATE_TIME | null",
            "date and time(\"2025-03-31T10:00:00\") - @\"P1M\"                      | DATE_TIME | null",
            "date and time(\"2024-03-31T10:00:00Z\") - @\"P1M\"                     | DATE_TIME | null",
            "date and time(\"2024-02-29T10:00:00\") + @\"P1Y\"                      | DATE_TIME | null",
            "date and time(\"2025-12-31T23:30:00\") + @\"P1M\"                      | DATE_TIME | 2026-01-31T23:30",
            // a day later by instant: 24 hours, which the spring change of offset in Paris shows as 13:00
            "date and time(\"2025-03-29T12:00:00@Europe/Paris\") + @\"P1D\"         | DATE_TIME | 2025-03-30T13:00:00+02:00",
            "date and time(\"2025-10-26T12:00:00@Europe/Paris\") - @\"PT24H\"       | DATE_TIME | 2025-10-25T13:00:00+02:00",
    })
    void months_keep_the_day_and_days_move_by_instant(String text, ColumnType type, String value) {
        var result = OutputExpression.parse(text).evaluate(any -> null);
        if (value == null) {
            Assertions.assertThat(result).isNull();
        } else if (type == ColumnType.DATE_TIME && value.length() > 16) {
            Assertions.assertThat(FeelTemporals.compare(result, FeelTemporals.parseDateTime(value))).as("%s", result).isZero();
        } else {
            Assertions.assertThat(result).isEqualTo(FeelLiterals.value(value, type));
        }
    }

    /**
     * A month later in a zone can land on a local time the zone skips (02:30 on 30 March 2025 in Paris). DMN 1.5 builds
     * the result with {@code date and time(date(...), time(e1))} (Table 57), and does not say what such a time is;
     * Praecepta moves it forward by the length of the gap, as {@code ZonedDateTime.of} does, recorded in DMN conformance.
     */
    @Test
    void a_month_later_on_a_skipped_local_time_moves_forward_by_the_gap() {
        var result = OutputExpression.parse("date and time(\"2025-01-30T02:30:00@Europe/Paris\") + @\"P2M\"").evaluate(any -> null);
        Assertions.assertThat(result).hasToString("2025-03-30T03:30+02:00[Europe/Paris]");
    }

    @Test
    void a_missing_operand_gives_null() {
        var missing = new HashMap<>(inputs());
        missing.put("due", null);
        Assertions.assertThat(OutputExpression.parse("due + @\"P1D\"").evaluate(missing::get)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "due + due              | + combines a date and a date, which gives null in FEEL",
            "due + 1                | + combines a date and a number, which gives null in FEEL",
            "opens + tenure         | + combines a time and a years and months duration, which gives null in FEEL",
            "elapsed + tenure       | + combines a days and time duration and a years and months duration, which gives null in FEEL",
            "@\"P1D\" - due         | - combines a days and time duration and a date, which gives null in FEEL",
            "elapsed * elapsed      | * combines a days and time duration and a days and time duration, which gives null in FEEL",
            "days / elapsed         | / combines a number and a days and time duration, which gives null in FEEL",
            "elapsed / tenure       | / combines a days and time duration and a years and months duration, which gives null in FEEL",
            "elapsed ** 2           | ** combines a days and time duration and a number, which gives null in FEEL",
            "-due                   | - applies to numbers and durations, not to a date",
            "ok + 1                 | 'ok' is a boolean; output expressions combine strings, numbers, dates, times and durations",
    })
    void types_feel_does_not_combine_are_rejected(String text, String message) {
        var expression = OutputExpression.parse(text);
        Assertions.assertThatThrownBy(() -> expression.type(TYPES::get))
                .isInstanceOf(ExpressionTypeException.class)
                .hasMessage(message);
    }

    @Test
    void date_time_literals_are_read_as_cells_read_them() {
        Assertions.assertThat(OutputExpression.parse("@\"2025-01-31\" + date and time(\"2025-01-31T10:00:00\") - due"))
                .isNotNull();
        Assertions.assertThat(OutputExpression.parse("date(2025, 1, 31) + @\"P1D\"").evaluate(any -> null)).isEqualTo(LocalDate.of(2025, 2, 1));
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("date(\"2025-02-30\") + @\"P1D\""))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("invalid date '2025-02-30'");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("date(x)"))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("'x' is not a string, a number or a date time literal");
        // other functions are still not supported
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("years and months duration(due, due)"))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("function calls");
    }

    private static Map<String, Object> inputs() {
        var inputs = new HashMap<String, Object>();
        inputs.put("due", LocalDate.of(2025, 1, 31));
        inputs.put("sent", LocalDateTime.of(2025, 1, 31, 8, 0));
        inputs.put("returned", LocalDateTime.of(2025, 2, 1, 10, 30));
        inputs.put("opens", LocalTime.of(8, 30));
        inputs.put("elapsed", Duration.ofMinutes(26 * 60 + 30));
        inputs.put("tenure", new YearsAndMonths(14));
        inputs.put("days", new BigDecimal(3));
        inputs.put("name", "a");
        return inputs;
    }
}
