/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeelTemporalsTest {

    @Test
    void times_parse_with_or_without_an_offset() {
        Assertions.assertThat(FeelTemporals.parseTime("08:30")).isEqualTo(LocalTime.of(8, 30));
        Assertions.assertThat(FeelTemporals.parseTime("08:30:00+01:00")).isEqualTo(OffsetTime.of(8, 30, 0, 0, ZoneOffset.ofHours(1)));
        Assertions.assertThat(FeelTemporals.parseTime("08:30:00z")).isEqualTo(OffsetTime.of(8, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void date_times_parse_with_an_offset_a_feel_zone_id_or_a_java_zone_id() {
        var paris = ZonedDateTime.of(2018, 7, 30, 16, 12, 0, 0, ZoneId.of("Europe/Paris"));
        Assertions.assertThat(FeelTemporals.parseDateTime("2018-07-30T16:12:00")).isEqualTo(LocalDateTime.of(2018, 7, 30, 16, 12));
        Assertions.assertThat(FeelTemporals.parseDateTime("2018-07-30T16:12:00Z"))
                .isEqualTo(ZonedDateTime.of(2018, 7, 30, 16, 12, 0, 0, ZoneOffset.UTC));
        Assertions.assertThat(FeelTemporals.parseDateTime("2018-07-30T16:12:00@Europe/Paris")).isEqualTo(paris);
        Assertions.assertThat(FeelTemporals.parseDateTime(paris.toString())).isEqualTo(paris);
        Assertions.assertThat(FeelTemporals.parseZonedDateTime("2018-07-30T16:12:00@Europe/Paris")).isEqualTo(paris);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08:30@Europe/Paris", "08:30+1", "8:30", "08:30Z@UTC"})
    void times_with_a_zone_id_or_a_malformed_offset_do_not_parse(String text) {
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseTime(text)).isInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2018-07-30T16:12:00@Mars/Olympus", "2018-07-30T16:12:00@", "2018-07-30@UTC", "2018-07-30T16:12:00+0100"})
    void date_times_with_an_unknown_zone_or_a_malformed_offset_do_not_parse(String text) {
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseDateTime(text)).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void a_zoned_date_time_is_required_where_one_is_expected() {
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseZonedDateTime("2018-07-30T16:12:00"))
                .isInstanceOf(DateTimeParseException.class)
                .hasMessageContaining("without a time zone");
    }

    @Test
    void zoned_values_are_ordered_and_equal_by_instant() {
        var nineInParis = OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1));
        var eightUtc = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(FeelTemporals.compare(nineInParis, eightUtc)).isZero();
        Assertions.assertThat(nineInParis.compareTo(eightUtc)).as("unlike Java's order").isNotZero();
        Assertions.assertThat(FeelTemporals.compare(OffsetTime.of(23, 0, 0, 0, ZoneOffset.ofHours(-2)), eightUtc))
                .as("23:00-02:00 is 01:00 the next day at offset zero").isPositive();
        var summer = ZonedDateTime.of(2025, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"));
        Assertions.assertThat(FeelTemporals.compare(summer, summer.withZoneSameInstant(ZoneOffset.UTC))).isZero();
        Assertions.assertThat(FeelTemporals.compare(summer, summer.plusNanos(1).withZoneSameInstant(ZoneOffset.UTC))).isNegative();
        Assertions.assertThat(FeelTemporals.compare(BigDecimal.ONE, new BigDecimal("1.0"))).isZero();
    }

    @Test
    void a_value_without_a_zone_takes_the_offset_of_the_one_it_is_compared_with() {
        // DMN 1.5, Table 54: "If one input has a null timezone offset, that input uses the timezone offset of the other input"
        var eight = LocalTime.of(8, 0);
        Assertions.assertThat(FeelTemporals.compare(eight, OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC))).isZero();
        Assertions.assertThat(FeelTemporals.compare(eight, OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(5)))).isZero();
        Assertions.assertThat(FeelTemporals.compare(OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(-5)), eight)).isZero();
        Assertions.assertThat(FeelTemporals.compare(eight, OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(5))))
                .as("09:00+05:00 is 04:00 at offset zero, but 08:00 is taken at +05:00 too").isNegative();
        Assertions.assertThat(FeelTemporals.compare(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(5)), eight)).isPositive();

        var noon = LocalDateTime.of(2025, 7, 1, 12, 0);
        var noonInParis = ZonedDateTime.of(2025, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"));
        Assertions.assertThat(FeelTemporals.compare(noon, noonInParis)).isZero();
        Assertions.assertThat(FeelTemporals.compare(noonInParis, noon)).isZero();
        Assertions.assertThat(FeelTemporals.compare(noon, noonInParis.withZoneSameInstant(ZoneOffset.UTC)))
                .as("the same instant is 10:00 at offset zero").isPositive();
        Assertions.assertThat(FeelTemporals.compare(noon.plusNanos(1), noonInParis)).isPositive();
        Assertions.assertThat(FeelTemporals.compare(noonInParis, noon.plusNanos(1))).isNegative();
    }

    @Test
    void a_value_with_a_zone_is_local_without_it() {
        Assertions.assertThat(FeelTemporals.local(OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(5)))).isEqualTo(LocalTime.of(8, 0));
        Assertions.assertThat(FeelTemporals.local(ZonedDateTime.of(2025, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"))))
                .isEqualTo(LocalDateTime.of(2025, 7, 1, 12, 0));
        Assertions.assertThat(FeelTemporals.local(LocalTime.NOON)).isEqualTo(LocalTime.NOON);
        Assertions.assertThat(FeelTemporals.local("text")).isEqualTo("text");
    }
}
