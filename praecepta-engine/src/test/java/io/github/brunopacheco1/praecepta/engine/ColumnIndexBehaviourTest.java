/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Pins how a {@link ColumnIndex} splits each column type into value classes: each class's
 * description, whether it is empty or allowed, the rules (bits) accepting it and its example value;
 * how runs of classes are described; and which rules, or which error, each raw input gets.
 */
class ColumnIndexBehaviourTest {

    enum Letter { A, B, C }

    @Test
    void number_classes() {
        var column = column(ColumnType.NUMBER, null, "< 10", "[10..20]", "> 20", "15", "-", "null", "not(5)", "[1.5..2.25)");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..1.5) rules=[0, 4, 6] example=0.5 (BigDecimal)
                1 1.5 rules=[0, 4, 6, 7] example=1.5 (BigDecimal)
                2 (1.5..2.25) rules=[0, 4, 6, 7] example=1.8750 (BigDecimal)
                3 2.25 rules=[0, 4, 6] example=2.25 (BigDecimal)
                4 (2.25..5) rules=[0, 4, 6] example=3.625 (BigDecimal)
                5 5 rules=[0, 4] example=5 (BigDecimal)
                6 (5..10) rules=[0, 4, 6] example=7.5 (BigDecimal)
                7 10 rules=[1, 4, 6] example=10 (BigDecimal)
                8 (10..15) rules=[1, 4, 6] example=12.5 (BigDecimal)
                9 15 rules=[1, 3, 4, 6] example=15 (BigDecimal)
                10 (15..20) rules=[1, 4, 6] example=17.5 (BigDecimal)
                11 20 rules=[1, 4, 6] example=20 (BigDecimal)
                12 (20..+inf) rules=[2, 4, 6] example=21 (BigDecimal)
                13 null missing rules=[4, 5, 6] example=-
                """);
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 3, 4, 7, 8, 9, 12)))
                .containsExactly("(-inf..5)", "[10..15]", "(20..+inf)");
        Assertions.assertThat(column.describe(List.of(12, 2, 5))).containsExactly("(1.5..2.25)", "5", "(20..+inf)");
        Assertions.assertThat(lookups(column, null, "", "  ", "15", " 15 ", "abc", 15, 15L, 15.0d, 10.5f, (short) 5, (byte) 5,
                BigInteger.valueOf(20), new BigDecimal("1E+1"), Double.NaN, Double.POSITIVE_INFINITY)).isEqualTo("""
                null -> [4, 5, 6]
                '' (String) -> [4, 5, 6]
                '  ' (String) -> [4, 5, 6]
                '15' (String) -> [1, 3, 4, 6]
                ' 15 ' (String) -> [1, 3, 4, 6]
                'abc' (String) -> Input c=abc is not a valid number
                '15' (Integer) -> [1, 3, 4, 6]
                '15' (Long) -> [1, 3, 4, 6]
                '15.0' (Double) -> [1, 3, 4, 6]
                '10.5' (Float) -> [1, 4, 6]
                '5' (Short) -> [0, 4]
                '5' (Byte) -> [0, 4]
                '20' (BigInteger) -> [1, 4, 6]
                '1E+1' (BigDecimal) -> [1, 4, 6]
                'NaN' (Double) -> Input c=NaN is not a finite number
                'Infinity' (Double) -> Input c=Infinity is not a finite number
                """);
    }

    @Test
    void number_classes_with_declared_values() {
        var column = column(ColumnType.NUMBER, "[0..100]", "< 18", ">= 65");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..0) disallowed rules=[0] example=-1 (BigDecimal)
                1 0 rules=[0] example=0 (BigDecimal)
                2 (0..18) rules=[0] example=9.0 (BigDecimal)
                3 18 rules=[] example=18 (BigDecimal)
                4 (18..65) rules=[] example=41.5 (BigDecimal)
                5 65 rules=[1] example=65 (BigDecimal)
                6 (65..100) rules=[1] example=82.5 (BigDecimal)
                7 100 rules=[1] example=100 (BigDecimal)
                8 (100..+inf) disallowed rules=[1] example=101 (BigDecimal)
                9 null missing rules=[] example=-
                """);
        Assertions.assertThat(lookups(column, null, -1, 0, 50, 100, 101, "101")).isEqualTo("""
                null -> []
                '-1' (Integer) -> Input c=-1 is not one of the column's allowed input values
                '0' (Integer) -> [0]
                '50' (Integer) -> []
                '100' (Integer) -> [1]
                '101' (Integer) -> Input c=101 is not one of the column's allowed input values
                '101' (String) -> Input c=101 is not one of the column's allowed input values
                """);
    }

    @Test
    void date_classes() {
        var column = column(ColumnType.DATE, null, ">= date(\"2024-01-01\")", "[date(\"2024-01-01\")..date(\"2024-01-02\")]",
                "< date(\"2024-01-03\")", "date(\"2024-03-01\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..date("2024-01-01")) rules=[2] example=2023-12-31 (LocalDate)
                1 date("2024-01-01") rules=[0, 1, 2] example=2024-01-01 (LocalDate)
                2 (date("2024-01-01")..date("2024-01-02")) empty rules=[]
                3 date("2024-01-02") rules=[0, 1, 2] example=2024-01-02 (LocalDate)
                4 (date("2024-01-02")..date("2024-01-03")) empty rules=[]
                5 date("2024-01-03") rules=[0] example=2024-01-03 (LocalDate)
                6 (date("2024-01-03")..date("2024-03-01")) rules=[0] example=2024-01-04 (LocalDate)
                7 date("2024-03-01") rules=[0, 3] example=2024-03-01 (LocalDate)
                8 (date("2024-03-01")..+inf) rules=[0] example=2024-03-02 (LocalDate)
                9 null missing rules=[] example=-
                """);
        // empty classes do not break a run
        Assertions.assertThat(column.describe(List.of(1, 3, 5))).containsExactly("[date(\"2024-01-01\")..date(\"2024-01-03\")]");
        Assertions.assertThat(column.describe(List.of(0, 1, 3, 5, 6, 8)))
                .containsExactly("(-inf..date(\"2024-03-01\"))", "(date(\"2024-03-01\")..+inf)");
        Assertions.assertThat(lookups(column, null, LocalDate.of(2024, 1, 2), "2024-01-02", " 2024-02-01 ", "2024-13-01")).isEqualTo("""
                null -> []
                '2024-01-02' (LocalDate) -> [0, 1, 2]
                '2024-01-02' (String) -> [0, 1, 2]
                ' 2024-02-01 ' (String) -> [0]
                '2024-13-01' (String) -> Input c=2024-13-01 is not a valid date
                """);
    }

    @Test
    void time_classes() {
        var column = column(ColumnType.TIME, null, "< time(\"00:00:00\")", "> time(\"23:59:59.999999999\")",
                "[time(\"08:00:00\")..time(\"08:00:00.000000001\")]", ">= time(\"12:00:00\")", "< time(\"00:30:00\")",
                "> time(\"23:30:00\")", "[time(\"10:00:00\")..time(\"10:00:00.000000003\")]");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("00:00:00")) empty rules=[]
                1 time("00:00:00") rules=[4] example=00:00 (LocalTime)
                2 (time("00:00:00")..time("00:30:00")) rules=[4] example=00:15 (LocalTime)
                3 time("00:30:00") rules=[] example=00:30 (LocalTime)
                4 (time("00:30:00")..time("08:00:00")) rules=[] example=04:15 (LocalTime)
                5 time("08:00:00") rules=[2] example=08:00 (LocalTime)
                6 (time("08:00:00")..time("08:00:00.000000001")) empty rules=[]
                7 time("08:00:00.000000001") rules=[2] example=08:00:00.000000001 (LocalTime)
                8 (time("08:00:00.000000001")..time("10:00:00")) rules=[] example=09:00 (LocalTime)
                9 time("10:00:00") rules=[6] example=10:00 (LocalTime)
                10 (time("10:00:00")..time("10:00:00.000000003")) rules=[6] example=10:00:00.000000001 (LocalTime)
                11 time("10:00:00.000000003") rules=[6] example=10:00:00.000000003 (LocalTime)
                12 (time("10:00:00.000000003")..time("12:00:00")) rules=[] example=11:00:00.000000001 (LocalTime)
                13 time("12:00:00") rules=[3] example=12:00 (LocalTime)
                14 (time("12:00:00")..time("23:30:00")) rules=[3] example=17:45 (LocalTime)
                15 time("23:30:00") rules=[3] example=23:30 (LocalTime)
                16 (time("23:30:00")..time("23:59:59.999999999")) rules=[3, 5] example=23:44:59.999999999 (LocalTime)
                17 time("23:59:59.999999999") rules=[3, 5] example=23:59:59.999999999 (LocalTime)
                18 (time("23:59:59.999999999")..+inf) empty rules=[]
                19 null missing rules=[] example=-
                """);
        Assertions.assertThat(column.describe(List.of(5, 7))).containsExactly("[time(\"08:00:00\")..time(\"08:00:00.000000001\")]");
        Assertions.assertThat(column.describe(List.of(13, 14, 15, 16, 17)))
                .containsExactly("[time(\"12:00:00\")..time(\"23:59:59.999999999\")]");
        Assertions.assertThat(lookups(column, null, LocalTime.MIDNIGHT, LocalTime.MAX, "08:00", "10:00:00Z", "bad")).isEqualTo("""
                null -> []
                '00:00' (LocalTime) -> [4]
                '23:59:59.999999999' (LocalTime) -> [3, 5]
                '08:00' (String) -> [2]
                '10:00:00Z' (String) -> Input c=10:00Z has a time zone, which the column's input binding does not take
                'bad' (String) -> Input c=bad is not a valid time
                """);
    }

    @Test
    void times_with_an_offset_are_ordered_by_instant_among_cells_with_an_offset() {
        // 09:00+01:00 is the instant of 08:00Z, and 10:00-02:00 that of 12:00Z: each pair is one boundary, written as first seen
        var column = columnTaking(ColumnType.TIME, Zoning.ZONED, null, "< time(\"08:00:00Z\")", "time(\"09:00:00+01:00\")",
                "> time(\"10:00:00-02:00\")", "-", "null, time(\"12:00:00Z\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("08:00:00Z")) rules=[0, 3] example=07:00Z (OffsetTime)
                1 time("08:00:00Z") rules=[1, 3] example=08:00Z (OffsetTime)
                2 (time("08:00:00Z")..time("10:00:00-02:00")) rules=[3] example=10:00Z (OffsetTime)
                3 time("10:00:00-02:00") rules=[3, 4] example=10:00-02:00 (OffsetTime)
                4 (time("10:00:00-02:00")..+inf) rules=[2, 3] example=11:00-02:00 (OffsetTime)
                5 null missing rules=[3, 4] example=-
                """);
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 3, 4))).containsExactly("(-inf..+inf)");
        Assertions.assertThat(lookups(column, null, OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1)), "07:59:59.999999999Z",
                "13:00:00+01:00", "12:00:00.000000001Z", "08:00", "08:00@Europe/Paris")).isEqualTo("""
                null -> [3, 4]
                '09:00+01:00' (OffsetTime) -> [1, 3]
                '07:59:59.999999999Z' (String) -> [0, 3]
                '13:00:00+01:00' (String) -> [3, 4]
                '12:00:00.000000001Z' (String) -> [2, 3]
                '08:00' (String) -> Input c=08:00 has no time zone, which the column's input binding requires
                '08:00@Europe/Paris' (String) -> Input c=08:00:00@Europe/Paris has a zone id, which the column's input binding does not take
                """);
    }

    @Test
    void times_with_an_offset_have_an_instant_and_a_local_time_when_cells_of_both_kinds_test_them() {
        // a cell with an offset looks at the instant, one without at the local time: a class is a pair of the two
        var column = columnTaking(ColumnType.TIME, Zoning.ZONED, null, "< time(\"08:00:00Z\")", "time(\"09:00:00+01:00\")",
                "> time(\"10:00:00-02:00\")", "> time(\"08:00:00\")", "not(time(\"08:00:00\"))", "-", "null, time(\"12:00:00Z\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("08:00:00Z")) at local time (-inf..time("08:00:00")) rules=[0, 4, 5] example=03:59Z (OffsetTime)
                1 (-inf..time("08:00:00Z")) at local time time("08:00:00") rules=[0, 5] example=08:00+01:00 (OffsetTime)
                2 (-inf..time("08:00:00Z")) at local time (time("08:00:00")..+inf) rules=[0, 3, 4, 5] example=08:30+01:00 (OffsetTime)
                3 time("08:00:00Z") at local time (-inf..time("08:00:00")) rules=[1, 4, 5] example=07:00-01:00 (OffsetTime)
                4 time("08:00:00Z") at local time time("08:00:00") rules=[1, 5] example=08:00Z (OffsetTime)
                5 time("08:00:00Z") at local time (time("08:00:00")..+inf) rules=[1, 3, 4, 5] example=09:00+01:00 (OffsetTime)
                6 (time("08:00:00Z")..time("10:00:00-02:00")) at local time (-inf..time("08:00:00")) rules=[4, 5] example=07:30-01:00 (OffsetTime)
                7 (time("08:00:00Z")..time("10:00:00-02:00")) at local time time("08:00:00") rules=[5] example=08:00-01:00 (OffsetTime)
                8 (time("08:00:00Z")..time("10:00:00-02:00")) at local time (time("08:00:00")..+inf) rules=[3, 4, 5] example=10:00Z (OffsetTime)
                9 time("10:00:00-02:00") at local time (-inf..time("08:00:00")) rules=[4, 5, 6] example=07:00-05:00 (OffsetTime)
                10 time("10:00:00-02:00") at local time time("08:00:00") rules=[5, 6] example=08:00-04:00 (OffsetTime)
                11 time("10:00:00-02:00") at local time (time("08:00:00")..+inf) rules=[3, 4, 5, 6] example=12:00Z (OffsetTime)
                12 (time("10:00:00-02:00")..+inf) at local time (-inf..time("08:00:00")) rules=[2, 4, 5] example=07:30-05:00 (OffsetTime)
                13 (time("10:00:00-02:00")..+inf) at local time time("08:00:00") rules=[2, 5] example=08:00-05:00 (OffsetTime)
                14 (time("10:00:00-02:00")..+inf) at local time (time("08:00:00")..+inf) rules=[2, 3, 4, 5] example=18:00Z (OffsetTime)
                15 null missing rules=[4, 5, 6] example=-
                """);
        // instants taken with all their local times merge; the others are given with theirs
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 3, 4, 5, 7, 8)))
                .containsExactly("(-inf..time(\"08:00:00Z\")]",
                        "(time(\"08:00:00Z\")..time(\"10:00:00-02:00\")) at local time [time(\"08:00:00\")..+inf)");
        Assertions.assertThat(column.describe(List.of(1, 4))).containsExactly(
                "(-inf..time(\"08:00:00Z\")) at local time time(\"08:00:00\")", "time(\"08:00:00Z\") at local time time(\"08:00:00\")");
        Assertions.assertThat(column.describe(IntStream.range(0, 15).boxed().toList())).containsExactly("(-inf..+inf)");
        Assertions.assertThat(lookups(column, null, OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1)), "07:59:59.999999999Z",
                "13:00:00+01:00", "08:00:00-04:00", "08:00:00.000000001-04:00", "08:00")).isEqualTo("""
                null -> [4, 5, 6]
                '09:00+01:00' (OffsetTime) -> [1, 3, 4, 5]
                '07:59:59.999999999Z' (String) -> [0, 4, 5]
                '13:00:00+01:00' (String) -> [3, 4, 5, 6]
                '08:00:00-04:00' (String) -> [5, 6]
                '08:00:00.000000001-04:00' (String) -> [2, 3, 4, 5]
                '08:00' (String) -> Input c=08:00 has no time zone, which the column's input binding requires
                """);
    }

    @Test
    void no_time_has_an_instant_and_a_local_time_more_than_14_hours_apart() {
        var column = columnTaking(ColumnType.TIME, Zoning.ZONED, null, "< time(\"05:00:00\")", "> time(\"19:00:00Z\")", "< time(\"01:00:00Z\")");
        // before 05:00 on the clock and after 19:00 at offset zero would be more than 14 hours behind it, beyond FEEL's offsets
        Assertions.assertThat(column.describe(3)).isEqualTo("time(\"01:00:00Z\") at local time (-inf..time(\"05:00:00\"))");
        Assertions.assertThat(column.empty[3]).isFalse();
        Assertions.assertThat(column.describe(12)).isEqualTo("(time(\"19:00:00Z\")..+inf) at local time (-inf..time(\"05:00:00\"))");
        Assertions.assertThat(column.empty[12]).isTrue();
        Assertions.assertThat(column.describe(13)).isEqualTo("(time(\"19:00:00Z\")..+inf) at local time time(\"05:00:00\")");
        Assertions.assertThat(column.empty[13]).isTrue();
        // 05:00:00.000000001-14:00 is 19:00:00.000000001Z
        Assertions.assertThat(column.empty[14]).isFalse();
        Assertions.assertThat(column.representative(14)).isInstanceOfSatisfying(OffsetTime.class, time -> {
            Assertions.assertThat(time.toLocalTime()).isAfter(LocalTime.of(5, 0));
            Assertions.assertThat(PrimitiveKeys.utcNanos(time)).isGreaterThan(LocalTime.of(19, 0).toNanoOfDay());
        });
        // on the clock at 05:00 exactly and at 19:00 at offset zero exactly: 05:00-14:00
        Assertions.assertThat(column.representative(10)).isEqualTo(OffsetTime.of(5, 0, 0, 0, ZoneOffset.ofHours(-14)));
    }

    @Test
    void times_with_an_offset_span_from_minus_14_to_38_hours_by_instant() {
        var column = columnTaking(ColumnType.TIME, Zoning.ZONED, null, "<= time(\"00:00:00+14:00\")", ">= time(\"23:59:59.999999999-14:00\")",
                "[time(\"01:00:00+02:00\")..time(\"23:00:00-02:00\")]");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("00:00:00+14:00")) empty rules=[]
                1 time("00:00:00+14:00") rules=[0] example=00:00+14:00 (OffsetTime)
                2 (time("00:00:00+14:00")..time("01:00:00+02:00")) rules=[] example=06:30+14:00 (OffsetTime)
                3 time("01:00:00+02:00") rules=[2] example=01:00+02:00 (OffsetTime)
                4 (time("01:00:00+02:00")..time("23:00:00-02:00")) rules=[2] example=14:00+02:00 (OffsetTime)
                5 time("23:00:00-02:00") rules=[2] example=23:00-02:00 (OffsetTime)
                6 (time("23:00:00-02:00")..time("23:59:59.999999999-14:00")) rules=[] example=23:29:59.999999999-08:00 (OffsetTime)
                7 time("23:59:59.999999999-14:00") rules=[1] example=23:59:59.999999999-14:00 (OffsetTime)
                8 (time("23:59:59.999999999-14:00")..+inf) empty rules=[]
                9 null missing rules=[] example=-
                """);
    }

    @Test
    void map_inputs_take_times_without_a_zone_by_local_time_and_those_with_one_by_instant_and_local_time() {
        var column = columnTaking(ColumnType.TIME, Zoning.EITHER, null, "< time(\"12:00:00\")", ">= time(\"12:00:00+01:00\")", "not(null)",
                "not(time(\"12:00:00\"))");
        // a time without a zone takes the offset of 12:00+01:00, so that cell is ">= 12:00" to it
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("12:00:00")) rules=[0, 2, 3] example=11:00 (LocalTime)
                1 time("12:00:00") rules=[1, 2] example=12:00 (LocalTime)
                2 (time("12:00:00")..+inf) rules=[1, 2, 3] example=13:00 (LocalTime)
                3 (-inf..time("12:00:00+01:00")) at local time (-inf..time("12:00:00")) rules=[0, 2, 3] example=05:29Z (OffsetTime)
                4 (-inf..time("12:00:00+01:00")) at local time time("12:00:00") rules=[2] example=12:00+02:00 (OffsetTime)
                5 (-inf..time("12:00:00+01:00")) at local time (time("12:00:00")..+inf) rules=[2, 3] example=12:30+02:00 (OffsetTime)
                6 time("12:00:00+01:00") at local time (-inf..time("12:00:00")) rules=[0, 1, 2, 3] example=11:00Z (OffsetTime)
                7 time("12:00:00+01:00") at local time time("12:00:00") rules=[1, 2] example=12:00+01:00 (OffsetTime)
                8 time("12:00:00+01:00") at local time (time("12:00:00")..+inf) rules=[1, 2, 3] example=13:00+02:00 (OffsetTime)
                9 (time("12:00:00+01:00")..+inf) at local time (-inf..time("12:00:00")) rules=[0, 1, 2, 3] example=11:30Z (OffsetTime)
                10 (time("12:00:00+01:00")..+inf) at local time time("12:00:00") rules=[1, 2] example=12:00Z (OffsetTime)
                11 (time("12:00:00+01:00")..+inf) at local time (time("12:00:00")..+inf) rules=[1, 2, 3] example=18:00Z (OffsetTime)
                12 any time with a zone id no cell names rules=[2] example=12:00:00@Africa/Abidjan (ZonedTime)
                13 null missing rules=[3] example=-
                """);
        // runs never cross from one kind to the other; all of a kind says which it is
        Assertions.assertThat(column.describe(List.of(1, 2, 3)))
                .containsExactly("[time(\"12:00:00\")..+inf)", "(-inf..time(\"12:00:00+01:00\")) at local time (-inf..time(\"12:00:00\"))");
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 6, 7, 8, 9, 10, 11)))
                .containsExactly("any time without a time zone", "[time(\"12:00:00+01:00\")..+inf)");
        Assertions.assertThat(column.describe(List.of(3, 4, 5, 6, 7, 8, 9, 10, 11))).containsExactly("any time with a time zone");
        Assertions.assertThat(lookups(column, LocalTime.NOON, "11:00:00Z", "12:00", "11:00:00.000000001Z", "12:00:00+02:00")).isEqualTo("""
                '12:00' (LocalTime) -> [1, 2]
                '11:00:00Z' (String) -> [0, 1, 2, 3]
                '12:00' (String) -> [1, 2]
                '11:00:00.000000001Z' (String) -> [0, 1, 2, 3]
                '12:00:00+02:00' (String) -> [2]
                """);
    }

    @Test
    void times_with_a_zone_id_have_a_line_per_zone_and_compare_with_nothing_else() {
        var column = columnTaking(ColumnType.TIME, Zoning.EITHER, null, "< time(\"10:00:00@Europe/Paris\")", "time(\"10:00:00\")",
                "not(time(\"10:00:00@Asia/Dhaka\"))", "!= null", "-");
        // TCK 0068-feel-equality compares two times in one zone; the rest is left open, so unknown: neither a test nor its not(...)
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("10:00:00")) rules=[3, 4] example=09:00 (LocalTime)
                1 time("10:00:00") rules=[1, 3, 4] example=10:00 (LocalTime)
                2 (time("10:00:00")..+inf) rules=[3, 4] example=11:00 (LocalTime)
                3 (-inf..time("10:00:00")) with a time zone rules=[3, 4] example=09:00Z (OffsetTime)
                4 time("10:00:00") with a time zone rules=[1, 3, 4] example=10:00Z (OffsetTime)
                5 (time("10:00:00")..+inf) with a time zone rules=[3, 4] example=11:00Z (OffsetTime)
                6 (-inf..time("10:00:00@Asia/Dhaka")) rules=[2, 3, 4] example=09:00:00@Asia/Dhaka (ZonedTime)
                7 time("10:00:00@Asia/Dhaka") rules=[3, 4] example=10:00:00@Asia/Dhaka (ZonedTime)
                8 (time("10:00:00@Asia/Dhaka")..+inf) rules=[2, 3, 4] example=11:00:00@Asia/Dhaka (ZonedTime)
                9 (-inf..time("10:00:00@Europe/Paris")) rules=[0, 3, 4] example=09:00:00@Europe/Paris (ZonedTime)
                10 time("10:00:00@Europe/Paris") rules=[3, 4] example=10:00:00@Europe/Paris (ZonedTime)
                11 (time("10:00:00@Europe/Paris")..+inf) rules=[3, 4] example=11:00:00@Europe/Paris (ZonedTime)
                12 any time with a zone id no cell names rules=[3, 4] example=12:00:00@Africa/Abidjan (ZonedTime)
                13 null missing rules=[2, 4] example=-
                """);
        Assertions.assertThat(column.describe(List.of(2, 9, 10, 11, 13))).containsExactly("(time(\"10:00:00\")..+inf)",
                "any time at Europe/Paris", "null");
        Assertions.assertThat(column.describe(List.of(6, 7, 12))).containsExactly("(-inf..time(\"10:00:00@Asia/Dhaka\")]",
                "any time with a zone id no cell names");
        Assertions.assertThat(lookups(column, "09:00@Europe/Paris", "10:00@Asia/Dhaka", "10:00@America/New_York")).isEqualTo("""
                '09:00@Europe/Paris' (String) -> [0, 3, 4]
                '10:00@Asia/Dhaka' (String) -> [3, 4]
                '10:00@America/New_York' (String) -> [3, 4]
                """);
    }

    @Test
    void a_map_input_with_a_zone_id_has_a_class_when_no_cell_names_one() {
        var column = columnTaking(ColumnType.TIME, Zoning.EITHER, null, "< time(\"12:00:00\")", "not(time(\"12:00:00\"))", "!= null",
                "not(null)", "-");
        // #114: the analyses visit it as any other class; only the cells that accept any value match it
        Assertions.assertThat(dump(column)).contains("""
                6 any time with a zone id no cell names rules=[2, 3, 4] example=12:00:00@Africa/Abidjan (ZonedTime)
                7 null missing rules=[1, 4] example=-
                """);
        // FEEL does not define how a time with a zone id compares with one without: < and not(...) are both unknown
        Assertions.assertThat(lookups(column, "11:00@Europe/Paris", "11:00")).isEqualTo("""
                '11:00@Europe/Paris' (String) -> [2, 3, 4]
                '11:00' (String) -> [0, 1, 2, 3, 4]
                """);
    }

    @Test
    void map_inputs_with_cells_without_a_zone_treat_both_kinds_alike() {
        var column = columnTaking(ColumnType.TIME, Zoning.EITHER, null, "< time(\"12:00:00\")", "not(time(\"12:00:00\"))");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("12:00:00")) rules=[0, 1] example=11:00 (LocalTime)
                1 time("12:00:00") rules=[] example=12:00 (LocalTime)
                2 (time("12:00:00")..+inf) rules=[1] example=13:00 (LocalTime)
                3 (-inf..time("12:00:00")) with a time zone rules=[0, 1] example=11:00Z (OffsetTime)
                4 time("12:00:00") with a time zone rules=[] example=12:00Z (OffsetTime)
                5 (time("12:00:00")..+inf) with a time zone rules=[1] example=13:00Z (OffsetTime)
                6 any time with a zone id no cell names rules=[] example=12:00:00@Africa/Abidjan (ZonedTime)
                7 null missing rules=[1] example=-
                """);
        // the same local times of both kinds are described once
        Assertions.assertThat(column.describe(List.of(1, 2, 4, 5))).containsExactly("[time(\"12:00:00\")..+inf)");
        Assertions.assertThat(column.describe(List.of(1, 2, 5))).containsExactly("[time(\"12:00:00\")..+inf)",
                "(time(\"12:00:00\")..+inf) with a time zone");
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 3, 4, 5))).containsExactly("(-inf..+inf)");
        Assertions.assertThat(column.describe(List.of(0, 1, 2))).containsExactly("any time without a time zone");
        Assertions.assertThat(column.describe(List.of(3, 4, 5))).containsExactly("any time with a time zone");
        Assertions.assertThat(lookups(column, "12:00", "12:00:00+09:00", "11:59:59-09:00")).isEqualTo("""
                '12:00' (String) -> []
                '12:00:00+09:00' (String) -> []
                '11:59:59-09:00' (String) -> [0, 1]
                """);
    }

    @Test
    void date_times_with_a_zone_are_ordered_by_instant_whatever_the_zone() {
        var column = columnTaking(ColumnType.DATE_TIME, Zoning.ZONED,
                "[date and time(\"2025-01-01T00:00:00Z\")..date and time(\"2026-01-01T00:00:00Z\"))",
                "< date and time(\"2025-03-30T03:00:00@Europe/Paris\")", "date and time(\"2025-03-30T01:00:00Z\")",
                "> date and time(\"2025-03-30T01:00:00.000000001Z\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..date and time("2025-01-01T00:00:00Z")) disallowed rules=[0] example=2024-12-31T00:00Z (ZonedDateTime)
                1 date and time("2025-01-01T00:00:00Z") rules=[0] example=2025-01-01T00:00Z (ZonedDateTime)
                2 (date and time("2025-01-01T00:00:00Z")..date and time("2025-03-30T03:00:00@Europe/Paris")) rules=[0] \
                example=2025-02-14T00:30Z (ZonedDateTime)
                3 date and time("2025-03-30T03:00:00@Europe/Paris") rules=[1] example=2025-03-30T03:00+02:00[Europe/Paris] (ZonedDateTime)
                4 (date and time("2025-03-30T03:00:00@Europe/Paris")..date and time("2025-03-30T01:00:00.000000001Z")) empty rules=[]
                5 date and time("2025-03-30T01:00:00.000000001Z") rules=[] example=2025-03-30T01:00:00.000000001Z (ZonedDateTime)
                6 (date and time("2025-03-30T01:00:00.000000001Z")..date and time("2026-01-01T00:00:00Z")) rules=[2] \
                example=2025-08-15T12:30Z (ZonedDateTime)
                7 date and time("2026-01-01T00:00:00Z") disallowed rules=[2] example=2026-01-01T00:00Z (ZonedDateTime)
                8 (date and time("2026-01-01T00:00:00Z")..+inf) disallowed rules=[2] example=2026-01-02T00:00Z (ZonedDateTime)
                9 null missing rules=[] example=-
                """);
        Assertions.assertThat(lookups(column, ZonedDateTime.of(2025, 3, 30, 3, 0, 0, 0, ZoneOffset.ofHours(2)), "2025-03-30T01:00:00Z",
                "2025-03-30T03:00:00+02:00[Europe/Paris]", "2025-03-30T01:00:00@UTC", "2025-06-01T00:00", "2025-06-01T00:00:00+00:00",
                "2027-01-01T00:00Z", "2025-01-01T00:00@Mars/Olympus")).isEqualTo("""
                '2025-03-30T03:00+02:00' (ZonedDateTime) -> [1]
                '2025-03-30T01:00:00Z' (String) -> [1]
                '2025-03-30T03:00:00+02:00[Europe/Paris]' (String) -> [1]
                '2025-03-30T01:00:00@UTC' (String) -> [1]
                '2025-06-01T00:00' (String) -> Input c=2025-06-01T00:00 has no time zone, which the column's input binding requires
                '2025-06-01T00:00:00+00:00' (String) -> [2]
                '2027-01-01T00:00Z' (String) -> Input c=2027-01-01T00:00Z is not one of the column's allowed input values
                '2025-01-01T00:00@Mars/Olympus' (String) -> Input c=2025-01-01T00:00@Mars/Olympus is not a valid date and time
                """);
    }

    @Test
    void map_inputs_take_date_times_of_both_kinds_and_compare_across_them_by_local_time() {
        // allowed: from 2025 to 2026 at offset zero, which a date-time without a zone is held to by its local time
        var column = columnTaking(ColumnType.DATE_TIME, Zoning.EITHER,
                "[date and time(\"2025-01-01T00:00:00Z\")..date and time(\"2026-01-01T00:00:00Z\")), date and time(\"2025-06-01T00:00:00\")",
                "< date and time(\"2025-03-30T03:00:00@Europe/Paris\")", "date and time(\"2025-03-30T01:00:00Z\")",
                "> date and time(\"2025-03-30T01:00:00.000000001Z\")");
        // without a zone: split by the local time of every boundary (the Paris one is 03:00 on the clock)
        Assertions.assertThat(column.describe(List.of(2, 3))).containsExactly(
                "(date and time(\"2025-01-01T00:00:00\")..date and time(\"2025-03-30T01:00:00\")]");
        Assertions.assertThat(column.describe(7)).isEqualTo("date and time(\"2025-03-30T03:00:00\")");
        // with a zone: by instant, and by local time around the one boundary without a zone; no date-time is six months from its instant
        Assertions.assertThat(column.describe(15)).isEqualTo(
                "(-inf..date and time(\"2025-01-01T00:00:00Z\")) at local time (date and time(\"2025-06-01T00:00:00\")..+inf)");
        Assertions.assertThat(column.empty[15]).isTrue();
        Assertions.assertThat(column.representative(32)).isEqualTo(ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        Assertions.assertThat(lookups(column, ZonedDateTime.of(2025, 3, 30, 3, 0, 0, 0, ZoneOffset.ofHours(2)), "2025-03-30T01:00:00Z",
                "2025-03-30T01:00:00", "2025-03-30T02:30:00", "2025-03-30T03:00:00", "2025-06-01T00:00", "2025-06-01T00:00:00+00:00",
                "2025-12-31T23:00:00", "2025-12-31T23:00:00-05:00", "2024-12-31T23:00:00", "2027-01-01T00:00Z")).isEqualTo("""
                '2025-03-30T03:00+02:00' (ZonedDateTime) -> [1]
                '2025-03-30T01:00:00Z' (String) -> [1]
                '2025-03-30T01:00:00' (String) -> [0, 1]
                '2025-03-30T02:30:00' (String) -> [0, 2]
                '2025-03-30T03:00:00' (String) -> [2]
                '2025-06-01T00:00' (String) -> [2]
                '2025-06-01T00:00:00+00:00' (String) -> [2]
                '2025-12-31T23:00:00' (String) -> [2]
                '2025-12-31T23:00:00-05:00' (String) -> Input c=2025-12-31T23:00-05:00 is not one of the column's allowed input values
                '2024-12-31T23:00:00' (String) -> Input c=2024-12-31T23:00 is not one of the column's allowed input values
                '2027-01-01T00:00Z' (String) -> Input c=2027-01-01T00:00Z is not one of the column's allowed input values
                """);
    }

    @Test
    void time_classes_near_the_ends_of_the_day() {
        var column = column(ColumnType.TIME, null, "< time(\"00:20:00\")", "> time(\"23:10:00\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..time("00:20:00")) rules=[0] example=00:00 (LocalTime)
                1 time("00:20:00") rules=[] example=00:20 (LocalTime)
                2 (time("00:20:00")..time("23:10:00")) rules=[] example=11:45 (LocalTime)
                3 time("23:10:00") rules=[] example=23:10 (LocalTime)
                4 (time("23:10:00")..+inf) rules=[1] example=23:59:59.999999999 (LocalTime)
                5 null missing rules=[] example=-
                """);
        var noBoundaries = column(ColumnType.TIME, null, "-");
        Assertions.assertThat(dump(noBoundaries)).isEqualTo("""
                0 (-inf..+inf) rules=[0] example=12:00 (LocalTime)
                1 null missing rules=[0] example=-
                """);
    }

    @Test
    void date_time_classes() {
        var column = column(ColumnType.DATE_TIME, null, "< date and time(\"2025-01-01T00:00:00\")",
                "[date and time(\"2025-01-01T00:00:00\")..date and time(\"2025-03-30T02:30:00\"))",
                "> date and time(\"2025-03-30T02:30:00.000000001\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..date and time("2025-01-01T00:00:00")) rules=[0] example=2024-12-31T00:00 (LocalDateTime)
                1 date and time("2025-01-01T00:00:00") rules=[1] example=2025-01-01T00:00 (LocalDateTime)
                2 (date and time("2025-01-01T00:00:00")..date and time("2025-03-30T02:30:00")) rules=[1] example=2025-02-14T01:15 \
                (LocalDateTime)
                3 date and time("2025-03-30T02:30:00") rules=[] example=2025-03-30T02:30 (LocalDateTime)
                4 (date and time("2025-03-30T02:30:00")..date and time("2025-03-30T02:30:00.000000001")) empty rules=[]
                5 date and time("2025-03-30T02:30:00.000000001") rules=[] example=2025-03-30T02:30:00.000000001 (LocalDateTime)
                6 (date and time("2025-03-30T02:30:00.000000001")..+inf) rules=[2] example=2025-03-31T02:30:00.000000001 (LocalDateTime)
                7 null missing rules=[] example=-
                """);
        Assertions.assertThat(column.describe(List.of(3, 5))).containsExactly(
                "[date and time(\"2025-03-30T02:30:00\")..date and time(\"2025-03-30T02:30:00.000000001\")]");
        Assertions.assertThat(lookups(column, null, LocalDateTime.of(2025, 2, 1, 0, 0), "2025-03-30T02:30", "2025-03-30")).isEqualTo("""
                null -> []
                '2025-02-01T00:00' (LocalDateTime) -> [1]
                '2025-03-30T02:30' (String) -> []
                '2025-03-30' (String) -> Input c=2025-03-30 is not a valid date and time
                """);
    }

    @Test
    void boolean_classes() {
        var column = column(ColumnType.BOOLEAN, null, "true", "false", "-", "null", "not(true)");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf..false) empty rules=[]
                1 false rules=[1, 2, 4] example=false (Boolean)
                2 (false..true) empty rules=[]
                3 true rules=[0, 2] example=true (Boolean)
                4 (true..+inf) empty rules=[]
                5 null missing rules=[2, 3, 4] example=-
                """);
        // booleans are not compared, so their classes never merge into intervals
        Assertions.assertThat(column.describe(List.of(1, 3))).containsExactly("false", "true");
        Assertions.assertThat(lookups(column, null, true, false, "TRUE", " false ", "yes", "")).isEqualTo("""
                null -> [2, 3, 4]
                'true' (Boolean) -> [0, 2]
                'false' (Boolean) -> [1, 2, 4]
                'TRUE' (String) -> [0, 2]
                ' false ' (String) -> [1, 2, 4]
                'yes' (String) -> Input c=yes is not a valid boolean
                '' (String) -> [2, 3, 4]
                """);
    }

    @Test
    void string_classes() {
        var column = column(ColumnType.STRING, null, "\"A\",\"B\"", "not(\"A\")", "-", "null", "\"C\"");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 "A" rules=[0, 2] example=A (String)
                1 "B" rules=[0, 1, 2] example=B (String)
                2 "C" rules=[1, 2, 4] example=C (String)
                3 any other value rules=[1, 2] example=-
                4 null missing rules=[1, 2, 3] example=-
                """);
        Assertions.assertThat(column.describe(List.of(2, 0, 3))).containsExactly("\"C\"", "\"A\"", "any other value");
        Assertions.assertThat(lookups(column, null, "A", "B", "C", "D", "", "  ")).isEqualTo("""
                null -> [1, 2, 3]
                'A' (String) -> [0, 2]
                'B' (String) -> [0, 1, 2]
                'C' (String) -> [1, 2, 4]
                'D' (String) -> [1, 2]
                '' (String) -> [1, 2, 3]
                '  ' (String) -> [1, 2, 3]
                """);
    }

    @Test
    void string_classes_with_declared_values() {
        var column = column(ColumnType.STRING, "\"A\",\"C\"", "\"A\",\"B\"", "not(\"A\")");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 "A" rules=[0] example=A (String)
                1 "B" disallowed rules=[0, 1] example=B (String)
                2 "C" rules=[1] example=C (String)
                3 any other value disallowed rules=[1] example=-
                4 null missing rules=[1] example=-
                """);
        Assertions.assertThat(lookups(column, null, "A", "B", "C", "D", "")).isEqualTo("""
                null -> [1]
                'A' (String) -> [0]
                'B' (String) -> Input c=B is not one of the column's allowed input values
                'C' (String) -> [1]
                'D' (String) -> Input c=D is not one of the column's allowed input values
                '' (String) -> [1]
                """);
    }

    @Test
    void comparing_string_classes() {
        var column = column(ColumnType.STRING, null, "< \"B\"", "[\"B\"..\"D\")", ">= \"D\"", "\"C\"", "not(\"A\")", "null");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf.."A") rules=[0, 4] example=0 (String)
                1 "A" rules=[0] example=A (String)
                2 ("A".."B") rules=[0, 4] example=Aa (String)
                3 "B" rules=[1, 4] example=B (String)
                4 ("B".."C") rules=[1, 4] example=Ba (String)
                5 "C" rules=[1, 3, 4] example=C (String)
                6 ("C".."D") rules=[1, 4] example=Ca (String)
                7 "D" rules=[2, 4] example=D (String)
                8 ("D"..+inf) rules=[2, 4] example=Da (String)
                9 null missing rules=[4, 5] example=-
                """);
        Assertions.assertThat(column.describe(List.of(0, 1, 2, 3, 4, 8))).containsExactly("(-inf..\"C\")", "(\"D\"..+inf)");
        // strings compare by code point: lower case sorts after upper case, a space before both
        Assertions.assertThat(lookups(column, null, "A", "Aardvark", "B", "Ba", "C", "Cz", "D", "Zed", "a", " B", "", "  ")).isEqualTo("""
                null -> [4, 5]
                'A' (String) -> [0]
                'Aardvark' (String) -> [0, 4]
                'B' (String) -> [1, 4]
                'Ba' (String) -> [1, 4]
                'C' (String) -> [1, 3, 4]
                'Cz' (String) -> [1, 4]
                'D' (String) -> [2, 4]
                'Zed' (String) -> [2, 4]
                'a' (String) -> [2, 4]
                ' B' (String) -> [0, 4]
                '' (String) -> [4, 5]
                '  ' (String) -> [4, 5]
                """);
    }

    /**
     * FEEL orders strings by character, a code point (DMN 1.5, Table 54 and 10.3.2.3.2): U+1F40E comes after
     * U+FF5E, although UTF-16 writes it with a first unit, U+D83D, that comes before.
     */
    @Test
    void comparing_string_classes_order_strings_by_code_point() {
        var tilde = "～"; // U+FF5E
        var horse = "🐎"; // U+1F40E
        var privateUse = String.valueOf((char) 0xE000);
        var column = column(ColumnType.STRING, null, "< \"" + tilde + "\"", "[\"" + tilde + "\"..\"" + horse + "\"]", "> \"" + horse + "\"");
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf.."～") rules=[0] example=a (String)
                1 "～" rules=[1] example=～ (String)
                2 ("～".."🐎") rules=[1] example=～a (String)
                3 "🐎" rules=[1] example=🐎 (String)
                4 ("🐎"..+inf) rules=[2] example=🐎a (String)
                5 null missing rules=[] example=-
                """);
        var grin = new String(Character.toChars(0x1F600)); // after U+1F40E
        Assertions.assertThat(lookups(column, "z", tilde, tilde + "z", horse, grin, horse + "a")).isEqualTo("""
                'z' (String) -> [0]
                '～' (String) -> [1]
                '～z' (String) -> [1]
                '🐎' (String) -> [1]
                '😀' (String) -> [2]
                '🐎a' (String) -> [2]
                """);
        // U+E000 and U+FFFF lie between U+D83D and U+1F40E: UTF-16 would put them after the horse
        Assertions.assertThat(lookups(column, privateUse)).isEqualTo("'" + privateUse + "' (String) -> [0]\n");
        var last = String.valueOf((char) 0xFFFF);
        Assertions.assertThat(lookups(column, last)).isEqualTo("'" + last + "' (String) -> [1]\n");
    }

    @Test
    void comparing_string_classes_hold_no_value_between_a_string_and_its_successor() {
        // the successor of "A" is "A" followed by U+0000; below "" and U+0000 only blank (missing) strings lie
        Assertions.assertThat(column(ColumnType.STRING, null, "[\"A\"..\"A\u0000\"]").empty).containsExactly(false, false, true, false, false, false);
        Assertions.assertThat(column(ColumnType.STRING, null, "< \"\"").empty[0]).isTrue();
        Assertions.assertThat(column(ColumnType.STRING, null, "< \"\u0000\"").empty[0]).isTrue();
        Assertions.assertThat(column(ColumnType.STRING, null, "< \"\u0001\"").representative(0)).isEqualTo("\u0000");
        // examples take a readable suffix when one fits, else U+0000
        Assertions.assertThat(column(ColumnType.STRING, null, "\"A\"", "< \"A0\"").representative(2)).isEqualTo("A!");
        Assertions.assertThat(column(ColumnType.STRING, null, "\"A\"", "< \"A!\"").representative(2)).isEqualTo("A\u0000");
    }

    @Test
    void comparing_string_classes_with_declared_values_and_an_enum() {
        var cells = cells(ColumnType.STRING, "< \"B\"", "> \"A\"");
        var allowed = CellParser.parse("\"A\",\"B\",\"C\"", ColumnType.STRING);
        var column = new ColumnIndex("c", ColumnType.STRING, new ColumnIndex.Cells(cells, 1, Masks.Form.AUTO), allowed, Zoning.LOCAL, null);
        Assertions.assertThat(dump(column)).isEqualTo("""
                0 (-inf.."A") disallowed rules=[0] example=0 (String)
                1 "A" rules=[0] example=A (String)
                2 ("A".."B") disallowed rules=[0, 1] example=Aa (String)
                3 "B" rules=[1] example=B (String)
                4 ("B".."C") disallowed rules=[1] example=Ba (String)
                5 "C" rules=[1] example=C (String)
                6 ("C"..+inf) disallowed rules=[1] example=Ca (String)
                7 null missing rules=[] example=-
                """);
        Assertions.assertThat(lookups(column, null, "A", "B", "C", "Ab", "D")).isEqualTo("""
                null -> []
                'A' (String) -> [0]
                'B' (String) -> [1]
                'C' (String) -> [1]
                'Ab' (String) -> Input c=Ab is not one of the column's allowed input values
                'D' (String) -> Input c=D is not one of the column's allowed input values
                """);
        var byEnum = new ColumnIndex("letter", ColumnType.STRING, new ColumnIndex.Cells(cells, 1, Masks.Form.AUTO), allowed, Zoning.LOCAL,
                new String[] {"A", "B", "C"});
        Assertions.assertThat(lookups(byEnum, null, Letter.A, Letter.B, Letter.C)).isEqualTo("""
                null -> []
                'A' (Letter) -> [0]
                'B' (Letter) -> [1]
                'C' (Letter) -> [1]
                """);
    }

    @Test
    void enum_bound_string_classes() {
        var cells = cells(ColumnType.STRING, "\"A\",\"B\"", "not(\"A\")", "null");
        var enumValues = new String[] {"A", "B", "C"};
        var oneWord = new ColumnIndex.Cells(cells, 1, Masks.Form.AUTO);
        var column = new ColumnIndex("letter", ColumnType.STRING, oneWord, CellParser.parse("\"A\",\"B\",\"C\"", ColumnType.STRING),
                Zoning.LOCAL, enumValues);
        Assertions.assertThat(lookups(column, null, Letter.A, Letter.B, Letter.C)).isEqualTo("""
                null -> [1, 2]
                'A' (Letter) -> [0]
                'B' (Letter) -> [0, 1]
                'C' (Letter) -> [1]
                """);
        var withoutC = CellParser.parse("\"A\",\"B\"", ColumnType.STRING);
        Assertions.assertThatThrownBy(() -> new ColumnIndex("letter", ColumnType.STRING, oneWord, withoutC, Zoning.LOCAL, enumValues))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Column 'letter': enum value 'C' is not one of the column's input values");
    }

    /** A column whose masks are all sparse that can be: the classes and lookups do not depend on the form. */
    private static ColumnIndex column(ColumnType type, String allowed, String... cells) {
        return columnTaking(type, Zoning.LOCAL, allowed, cells);
    }

    private static ColumnIndex columnTaking(ColumnType type, Zoning zoning, String allowed, String... cells) {
        var allowedTests = allowed == null ? null : CellParser.parse(allowed, type);
        var columnCells = new ColumnIndex.Cells(cells(type, cells), BitsetIndex.wordsFor(cells.length), Masks.Form.SPARSE);
        return new ColumnIndex("c", type, columnCells, allowedTests, zoning, null);
    }

    private static List<UnaryTests> cells(ColumnType type, String... cells) {
        return Arrays.stream(cells).map(cell -> CellParser.parse(cell, type)).toList();
    }

    /** One line per class: number, description, flags, accepting rules (bits) and example value. */
    private static String dump(ColumnIndex column) {
        var lines = new StringBuilder();
        for (int k = 0; k < column.classCount(); k++) {
            lines.append(k).append(' ').append(column.describe(k))
                    .append(column.empty[k] ? " empty" : "")
                    .append(column.allowed[k] ? "" : " disallowed")
                    .append(k == column.nullClass ? " missing" : "")
                    .append(" rules=").append(bits(column.masks[k]));
            if (!column.empty[k]) {
                var example = column.representative(k);
                lines.append(" example=").append(example == null ? "-" : example + " (" + example.getClass().getSimpleName() + ")");
            }
            lines.append('\n');
        }
        return lines.toString();
    }

    /** One line per raw input: the rules (bits) accepting it, or the error it raises. */
    private static String lookups(ColumnIndex column, Object... raws) {
        var lines = new StringBuilder();
        for (var raw : raws) {
            lines.append(raw == null ? "null" : "'" + raw + "' (" + raw.getClass().getSimpleName() + ")").append(" -> ");
            try {
                lines.append(bits(column.maskFor(raw)));
            } catch (InvalidInputException e) {
                lines.append(e.getMessage());
            }
            lines.append('\n');
        }
        return lines.toString();
    }

    /** The bits of a mask in either form; these columns have fewer than 64 rules, so one word. */
    private static List<Integer> bits(long[] stored) {
        var mask = Masks.dense(stored, 1);
        var bits = new ArrayList<Integer>();
        for (int bit = BitsetIndex.first(mask); bit >= 0; bit = BitsetIndex.next(mask, bit + 1)) {
            bits.add(bit);
        }
        return bits;
    }
}
