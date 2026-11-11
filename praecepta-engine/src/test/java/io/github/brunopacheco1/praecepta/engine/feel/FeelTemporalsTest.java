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
    void values_with_and_without_a_zone_cannot_be_compared() {
        var local = LocalTime.of(8, 0);
        var zoned = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(FeelTemporals.comparable(local, zoned)).isFalse();
        Assertions.assertThat(FeelTemporals.comparable(zoned, zoned)).isTrue();
        Assertions.assertThat(FeelTemporals.comparable(local, local)).isTrue();
        Assertions.assertThatThrownBy(() -> FeelTemporals.compare(local, zoned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only one has a time zone");
    }
}
