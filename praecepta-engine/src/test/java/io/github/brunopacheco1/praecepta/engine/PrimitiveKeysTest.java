/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/** The keyed lookup finds the class the search over the boundaries finds, or declines (-1). */
class PrimitiveKeysTest {

    @Test
    void numbers_are_scaled_to_the_finest_boundary() {
        var boundaries = numbers("-2", "0", "1.5", "10");
        var keys = PrimitiveKeys.of(ColumnType.NUMBER, boundaries);
        for (var value : List.of("-3", "-2", "-1.99", "-2.01", "0", "0.0", "1.4999", "1.5", "1.50", "1.51", "9", "10.000", "11", "-0.001")) {
            var number = new BigDecimal(value);
            Assertions.assertThat(keys.classOf(number)).as(value).isEqualTo(searched(boundaries, number));
        }
        // beyond a long, the keys decline and the column searches its boundaries
        Assertions.assertThat(keys.classOf(new BigDecimal("1E+30"))).isEqualTo(-1);
    }

    @Test
    void whole_number_boundaries_place_fractions_between_them() {
        var boundaries = numbers("1", "2");
        var keys = PrimitiveKeys.of(ColumnType.NUMBER, boundaries);
        for (var value : List.of("0.5", "1", "1.5", "2", "2.5", "-0.5", "1E+1")) {
            var number = new BigDecimal(value);
            Assertions.assertThat(keys.classOf(number)).as(value).isEqualTo(searched(boundaries, number));
        }
    }

    @Test
    void boundaries_that_do_not_fit_leave_the_column_without_keys() {
        Assertions.assertThat(PrimitiveKeys.of(ColumnType.NUMBER, numbers("1E-19"))).isNull();
        Assertions.assertThat(PrimitiveKeys.of(ColumnType.NUMBER, numbers("1E+19"))).isNull();
        Assertions.assertThat(PrimitiveKeys.of(ColumnType.DATE_TIME, new Comparable<?>[] {LocalDateTime.MAX})).isNull();
        Assertions.assertThat(PrimitiveKeys.of(ColumnType.STRING, new Comparable<?>[] {"a"})).isNull();
    }

    @Test
    void dates_times_and_durations_keep_their_order() {
        assertSameClasses(ColumnType.DATE, List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 1)),
                List.of(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1), LocalDate.MAX, LocalDate.MIN));
        assertSameClasses(ColumnType.TIME, List.of(LocalTime.of(8, 0), LocalTime.NOON),
                List.of(LocalTime.MIDNIGHT, LocalTime.of(8, 0), LocalTime.of(8, 0, 0, 1), LocalTime.MAX));
        assertSameClasses(ColumnType.DATE_TIME, List.of(LocalDateTime.of(1969, 12, 31, 23, 59), LocalDateTime.of(2025, 1, 1, 0, 0)),
                List.of(LocalDateTime.of(1969, 12, 31, 23, 58), LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2025, 1, 1, 0, 0)));
        assertSameClasses(ColumnType.DAY_TIME_DURATION, List.of(Duration.ofHours(-1), Duration.ofDays(1)),
                List.of(Duration.ofHours(-2), Duration.ZERO, Duration.ofDays(1), Duration.ofDays(2)));
        assertSameClasses(ColumnType.YEAR_MONTH_DURATION, List.of(new YearsAndMonths(0), new YearsAndMonths(12)),
                List.of(new YearsAndMonths(-1), new YearsAndMonths(6), new YearsAndMonths(12), new YearsAndMonths(13)));
        // a date-time beyond nanoseconds in a long declines
        var keys = PrimitiveKeys.of(ColumnType.DATE_TIME, new Comparable<?>[] {LocalDateTime.of(2025, 1, 1, 0, 0)});
        Assertions.assertThat(keys.classOf(LocalDateTime.MAX)).isEqualTo(-1);
    }

    @Test
    void zoned_times_and_date_times_are_keyed_by_instant() {
        var plusOne = ZoneOffset.ofHours(1);
        // 09:00+01:00 is the instant of 08:00Z, and 23:00-02:00 lies after both: 01:00 the next day at offset zero
        assertSameClasses(ColumnType.TIME, List.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC), OffsetTime.of(23, 0, 0, 0, ZoneOffset.ofHours(-2))),
                List.of(OffsetTime.of(9, 0, 0, 0, plusOne), OffsetTime.of(8, 59, 0, 0, plusOne), OffsetTime.of(23, 30, 0, 0, ZoneOffset.UTC),
                        OffsetTime.of(0, 0, 0, 0, ZoneOffset.ofHours(18)), OffsetTime.of(1, 0, 0, 1, ZoneOffset.UTC).withOffsetSameInstant(plusOne),
                        OffsetTime.MAX, OffsetTime.MIN));
        var paris = ZoneId.of("Europe/Paris");
        assertSameClasses(ColumnType.DATE_TIME,
                List.of(ZonedDateTime.of(2025, 3, 30, 3, 0, 0, 0, paris), ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, paris)),
                List.of(ZonedDateTime.of(2025, 3, 30, 1, 0, 0, 0, ZoneOffset.UTC),
                        ZonedDateTime.of(2025, 3, 30, 0, 59, 59, 999_999_999, ZoneOffset.UTC),
                        ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, paris).withLaterOffsetAtOverlap(),
                        ZonedDateTime.of(2025, 10, 26, 0, 30, 0, 0, ZoneOffset.UTC), ZonedDateTime.of(1969, 1, 1, 0, 0, 0, 0, plusOne)));
        // a zoned date-time beyond nanoseconds in a long (after 2262) declines, as LocalDateTime.MAX does
        var keys = PrimitiveKeys.of(ColumnType.DATE_TIME, new Comparable<?>[] {ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, paris)});
        Assertions.assertThat(keys.classOf(ZonedDateTime.of(3000, 1, 1, 0, 0, 0, 0, paris))).isEqualTo(-1);
    }

    private static void assertSameClasses(ColumnType type, List<? extends Comparable<?>> boundaryList, List<? extends Comparable<?>> values) {
        var boundaries = boundaryList.toArray(Comparable<?>[]::new);
        var keys = PrimitiveKeys.of(type, boundaries);
        for (var value : values) {
            Assertions.assertThat(keys.classOf(value)).as("%s", value).isEqualTo(searched(boundaries, value));
        }
    }

    private static Comparable<?>[] numbers(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toArray(Comparable<?>[]::new);
    }

    /** The class as {@link ColumnIndex} finds it without keys: values with a zone ordered by instant. */
    private static int searched(Comparable<?>[] boundaries, Object value) {
        int found = Arrays.binarySearch(boundaries, value, FeelTemporals::compare);
        return found >= 0 ? 2 * found + 1 : 2 * (-found - 1);
    }
}
