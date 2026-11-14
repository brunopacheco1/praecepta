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
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeelTemporalsTest {

    @Test
    void times_with_a_zone_id_compare_by_local_time_in_their_zone_only() {
        var paris = ZonedTime.parse("10:30:00@Europe/Paris");
        var parisLater = ZonedTime.parse("11:00:00@Europe/Paris");
        var dhaka = ZonedTime.parse("10:30:00@Asia/Dhaka");
        // DMN TCK, 0068-feel-equality: time("10:30:00@Europe/Paris") = time("10:30:00@Europe/Paris")
        Assertions.assertThat(FeelTemporals.compare(paris, ZonedTime.parse("10:30@Europe/Paris"))).isZero();
        Assertions.assertThat(FeelTemporals.compare(paris, parisLater)).isNegative();
        // the cases the TCK leaves out, "pending RTF equality comparisons with zones clarification", are not compared
        for (var other : List.<Object>of(dhaka, LocalTime.of(10, 30), OffsetTime.parse("10:30:00+01:00"))) {
            Assertions.assertThat(FeelTemporals.comparable(paris, other)).as("%s", other).isFalse();
            Assertions.assertThat(FeelTemporals.comparable(other, paris)).as("%s", other).isFalse();
            Assertions.assertThatThrownBy(() -> FeelTemporals.compare(paris, other)).isInstanceOf(IllegalArgumentException.class);
        }
        Assertions.assertThat(FeelTemporals.comparable(LocalTime.NOON, OffsetTime.parse("10:30:00+01:00"))).isTrue();
        Assertions.assertThat(FeelTemporals.hasZone(paris)).isFalse();
    }

    @Test
    void a_zone_id_must_name_a_region() {
        Assertions.assertThat(FeelTemporals.parseTime("10:30:00@Asia/Dhaka")).isEqualTo(new ZonedTime(LocalTime.of(10, 30), ZoneId.of("Asia/Dhaka")));
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseTime("10:30:00@Mars/Olympus")).isInstanceOf(DateTimeParseException.class);
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseTime("10:30:00@+01:00")).isInstanceOf(DateTimeParseException.class);
        Assertions.assertThatThrownBy(() -> new ZonedTime(LocalTime.NOON, ZoneOffset.UTC)).isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThat(new ZonedTime(LocalTime.NOON, ZoneId.of("Europe/Paris")))
                .isGreaterThan(new ZonedTime(LocalTime.MIDNIGHT, ZoneId.of("Asia/Dhaka"))); // a total order for sorting: zone id first
    }

    @Test
    void a_time_in_a_zone_with_one_fixed_offset_is_the_time_at_that_offset() {
        // DMN 1.5, Table 72: time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC") is true
        var utc = OffsetTime.of(0, 1, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(FeelTemporals.parseTime("00:01:00@Etc/UTC")).isEqualTo(utc);
        Assertions.assertThat(FeelTemporals.parseTime("00:01@UTC")).isEqualTo(utc);
        Assertions.assertThat(FeelTemporals.parseTime("02:01:00@Etc/GMT-2")).isEqualTo(OffsetTime.of(2, 1, 0, 0, ZoneOffset.ofHours(2)));
        Assertions.assertThat(FeelTemporals.parseTime("23:01:00@Etc/GMT+1")).isEqualTo(OffsetTime.of(23, 1, 0, 0, ZoneOffset.ofHours(-1)));
        // a zone whose offset changes, even one at offset zero today, keeps its zone id
        Assertions.assertThat(FeelTemporals.parseTime("00:01:00@Africa/Abidjan")).isInstanceOf(ZonedTime.class);
        Assertions.assertThat(FeelTemporals.timeIn(LocalTime.of(0, 1), ZoneId.of("Etc/UTC"))).isEqualTo(utc);
        Assertions.assertThat(FeelTemporals.timeIn(LocalTime.of(0, 1), ZoneOffset.UTC)).isEqualTo(utc);
        Assertions.assertThat(FeelTemporals.timeIn(LocalTime.of(0, 1), ZoneId.of("Europe/Paris")))
                .isEqualTo(new ZonedTime(LocalTime.of(0, 1), ZoneId.of("Europe/Paris")));

        // a ZonedTime a getter gives in such a zone compares as that offset time, with every time
        var bound = new ZonedTime(LocalTime.of(0, 1), ZoneId.of("Etc/UTC"));
        Assertions.assertThat(FeelTemporals.withFixedOffset(bound)).isEqualTo(utc);
        var paris = new ZonedTime(LocalTime.of(0, 1), ZoneId.of("Europe/Paris"));
        Assertions.assertThat(FeelTemporals.withFixedOffset(paris)).isSameAs(paris);
        Assertions.assertThat(FeelTemporals.compare(bound, OffsetTime.of(2, 1, 0, 0, ZoneOffset.ofHours(2)))).isZero();
        Assertions.assertThat(FeelTemporals.compare(new ZonedTime(LocalTime.of(2, 1), ZoneId.of("Etc/GMT-2")), bound)).isZero();
        Assertions.assertThat(FeelTemporals.compare(bound, OffsetTime.of(0, 2, 0, 0, ZoneOffset.UTC))).isNegative();
        Assertions.assertThat(FeelTemporals.compare(bound, LocalTime.of(0, 1))).isZero(); // by local time (Table 54)
        Assertions.assertThat(FeelTemporals.hasZone(bound)).isTrue();
        Assertions.assertThat(FeelTemporals.local(bound)).isEqualTo(LocalTime.of(0, 1));
        Assertions.assertThat(FeelTemporals.comparable(bound, LocalTime.NOON)).isTrue();
        Assertions.assertThat(FeelTemporals.comparable(utc, bound)).isTrue();
        Assertions.assertThat(FeelTemporals.comparable(bound, paris)).isFalse();
        Assertions.assertThat(FeelTemporals.comparable(paris, bound)).isFalse();
    }

    @Test
    void generated_code_reads_back_a_time_with_a_zone_written_either_way() {
        var utc = OffsetTime.of(0, 1, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(FeelTemporals.parseOffsetTime("00:01:00Z")).isEqualTo(utc);
        Assertions.assertThat(FeelTemporals.parseOffsetTime("00:01:00@Etc/UTC")).isEqualTo(utc);
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseOffsetTime("00:01:00")).isInstanceOf(DateTimeParseException.class);
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseOffsetTime("00:01:00@Europe/Paris")).isInstanceOf(DateTimeParseException.class);

        Assertions.assertThat(FeelTemporals.parseZonedTime("00:01:00@Europe/Paris")).isEqualTo(new ZonedTime(LocalTime.of(0, 1),
                ZoneId.of("Europe/Paris")));
        Assertions.assertThat(FeelTemporals.parseZonedTime("00:01:00@Etc/UTC")).isEqualTo(new ZonedTime(LocalTime.of(0, 1), ZoneId.of("Etc/UTC")));
        // an offset as the zone id that has it fixed, which reads back as the same time
        var atOffset = FeelTemporals.parseZonedTime("00:01:00+05:30");
        Assertions.assertThat(atOffset).isEqualTo(new ZonedTime(LocalTime.of(0, 1), ZoneId.of("UTC+05:30")));
        Assertions.assertThat(FeelTemporals.withFixedOffset(atOffset)).isEqualTo(OffsetTime.of(0, 1, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)));
        Assertions.assertThat(FeelTemporals.withFixedOffset(FeelTemporals.parseZonedTime("00:01:00Z"))).isEqualTo(utc);
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseZonedTime("00:01:00")).isInstanceOf(DateTimeParseException.class);
    }

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
    @ValueSource(strings = {"08:30@Mars/Olympus", "08:30+1", "8:30", "08:30Z@UTC", "08:30@"})
    void times_with_an_unknown_zone_or_a_malformed_offset_do_not_parse(String text) {
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseTime(text)).isInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2018-07-30T16:12:00@Mars/Olympus", "2018-07-30T16:12:00@", "2018-07-30@UTC", "2018-07-30T16:12:00+0100"})
    void date_times_with_an_unknown_zone_or_a_malformed_offset_do_not_parse(String text) {
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseDateTime(text)).isInstanceOf(DateTimeParseException.class);
    }

    /**
     * XML Schema's offsets, which DMN 1.5 takes (10.3.4.1; Table 66 bounds the time offset to
     * {@code [duration("-PT14H")..duration("PT14H")]}), lie in -14:00..+14:00 (#123): one beyond is no time, as the DMN
     * TCK has {@code time("13:20:00+19:00")} and {@code date and time("2017-12-31T13:20:00+19:00")} null
     * ({@code 1116-feel-time-function}, {@code ErrorCase_067}; {@code 1117-feel-date-and-time-function},
     * {@code ErrorCase_078}). Java takes -18:00..+18:00, and dropped from a time an offset beyond them.
     */
    @Test
    void offsets_lie_in_minus_to_plus_fourteen_hours() {
        Assertions.assertThat(FeelTemporals.parseTime("13:20:00+14:00")).isEqualTo(OffsetTime.of(13, 20, 0, 0, ZoneOffset.ofHours(14)));
        Assertions.assertThat(FeelTemporals.parseTime("13:20:00-14:00")).isEqualTo(OffsetTime.of(13, 20, 0, 0, ZoneOffset.ofHours(-14)));
        Assertions.assertThat(FeelTemporals.parseDateTime("2017-12-31T13:20:00+14:00"))
                .isEqualTo(ZonedDateTime.of(2017, 12, 31, 13, 20, 0, 0, ZoneOffset.ofHours(14)));
        for (var offset : List.of("+19:00", "-19:00", "+14:01", "-14:00:01", "+15:00", "+18:00")) {
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseTime("13:20:00" + offset)).as(offset)
                    .isInstanceOf(DateTimeParseException.class);
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseDateTime("2017-12-31T13:20:00" + offset)).as(offset)
                    .isInstanceOf(DateTimeParseException.class);
            // as generated code reads the outputs of a time column with a zone
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseOffsetTime("13:20:00" + offset)).isInstanceOf(DateTimeParseException.class);
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseZonedTime("13:20:00" + offset)).isInstanceOf(DateTimeParseException.class);
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseZonedDateTime("2017-12-31T13:20:00" + offset))
                    .isInstanceOf(DateTimeParseException.class);
        }
        // a zone id gives the offset its rules have, a local mean time beyond 14:00 before 1900 included
        Assertions.assertThat(FeelTemporals.parseDateTime("1800-01-01T00:00:00@America/Sitka"))
                .isEqualTo(ZonedDateTime.of(1800, 1, 1, 0, 0, 0, 0, ZoneId.of("America/Sitka")));
    }

    /**
     * XML Schema's year, which DMN 1.5 takes (10.3.4.1, date string), has an optional {@code -}, no {@code +}, and four
     * digits or more, with no leading zero beyond four (#124), as the DMN TCK has it: {@code date("+2012-12-02")},
     * {@code date("01211-12-31")} and {@code date and time("+99999-12-01T11:22:33")} are null
     * ({@code 1115-feel-date-function}, {@code ErrorCase_041} and {@code _039}; {@code 1117-feel-date-and-time-function},
     * {@code ErrorCase_068}), {@code date and time("99999-12-31T11:22:33")} and {@code date("-999999999-12-31")} are
     * not ({@code 1117}, {@code 011}; {@code 1115}, {@code 016}). Java's ISO form takes the first and the last of these
     * and wants a {@code +} before more than four digits.
     */
    @Test
    void years_have_an_optional_minus_and_no_plus() {
        Assertions.assertThat(FeelTemporals.parseDate("99999-12-31")).isEqualTo(LocalDate.of(99999, 12, 31));
        Assertions.assertThat(FeelTemporals.parseDate("-99999-12-31")).isEqualTo(LocalDate.of(-99999, 12, 31));
        Assertions.assertThat(FeelTemporals.parseDate("999999999-12-31")).isEqualTo(LocalDate.of(999999999, 12, 31));
        Assertions.assertThat(FeelTemporals.parseDate("-999999999-12-31")).isEqualTo(LocalDate.of(-999999999, 12, 31));
        Assertions.assertThat(FeelTemporals.parseDate("-0044-03-15")).isEqualTo(LocalDate.of(-44, 3, 15));
        Assertions.assertThat(FeelTemporals.parseDate("0001-01-01")).isEqualTo(LocalDate.of(1, 1, 1));
        Assertions.assertThat(FeelTemporals.parseDateTime("99999-12-31T11:22:33")).isEqualTo(LocalDateTime.of(99999, 12, 31, 11, 22, 33));
        Assertions.assertThat(FeelTemporals.parseDateTime("-99999-12-31T11:22:33Z"))
                .isEqualTo(ZonedDateTime.of(-99999, 12, 31, 11, 22, 33, 0, ZoneOffset.UTC));
        Assertions.assertThat(FeelTemporals.parseDateTime("99999-12-31T11:22:33@Europe/Paris"))
                .isEqualTo(ZonedDateTime.of(99999, 12, 31, 11, 22, 33, 0, ZoneId.of("Europe/Paris")));
        // as generated code reads the outputs of a date or date and time column
        Assertions.assertThat(FeelTemporals.parseLocalDateTime("99999-12-31T11:22:33")).isEqualTo(LocalDateTime.of(99999, 12, 31, 11, 22, 33));
        Assertions.assertThatThrownBy(() -> FeelTemporals.parseLocalDateTime("2025-01-31T11:22:33Z")).isInstanceOf(DateTimeParseException.class)
                .hasMessageContaining("with a time zone");

        for (var date : List.of("+2012-12-02", "+99999-12-31", "01211-12-31", "-01211-12-31", "9999999999-12-25", "998-12-31", "+-2012-12-02")) {
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseDate(date)).as(date).isInstanceOf(DateTimeParseException.class);
            for (var time : List.of("T11:22:33", "T11:22:33Z", "T11:22:33@Europe/Paris")) {
                Assertions.assertThatThrownBy(() -> FeelTemporals.parseDateTime(date + time)).as(date + time)
                        .isInstanceOf(DateTimeParseException.class);
            }
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseLocalDateTime(date + "T11:22:33")).isInstanceOf(DateTimeParseException.class);
            Assertions.assertThatThrownBy(() -> FeelTemporals.parseZonedDateTime(date + "T11:22:33Z")).isInstanceOf(DateTimeParseException.class);
        }
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
        Assertions.assertThatComparable(nineInParis).as("unlike Java's order").isNotEqualByComparingTo(eightUtc);
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
