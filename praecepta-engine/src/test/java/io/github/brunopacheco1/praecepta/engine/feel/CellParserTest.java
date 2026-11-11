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
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;

class CellParserTest {

    @ParameterizedTest(name = "''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-", " - "})
    void blank_and_dash_match_anything(String cell) {
        Assertions.assertThat(CellParser.parse(cell, ColumnType.STRING)).isEqualTo(new UnaryTests.Any());
    }

    @Test
    void legacy_unquoted_lists_still_mean_string_literals() {
        Assertions.assertThat(CellParser.parse("PT,ES", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parse(" PT , ES ", ColumnType.STRING)) // whitespace is trimmed
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parse("10", ColumnType.STRING)) // a code, not a number
                .isEqualTo(positive(new Equals("10")));
    }

    @Test
    void quoted_strings_may_contain_commas_and_escaped_quotes() {
        Assertions.assertThat(CellParser.parse("\"a,b\", \"say \\\"hi\\\"\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a,b"), new Equals("say \"hi\"")));
    }

    @Test
    void not_negates_a_list() {
        Assertions.assertThat(CellParser.parse("not(\"FR\", DE)", ColumnType.STRING))
                .isEqualTo(negated(new Equals("FR"), new Equals("DE")));
    }

    @Test
    void not_may_wrap_half_open_intervals() {
        Assertions.assertThat(CellParser.parse("not(0, [0..1))", ColumnType.NUMBER))
                .isEqualTo(negated(new Equals(BigDecimal.ZERO), new Range(BigDecimal.ZERO, true, BigDecimal.ONE, false)));
    }

    @Test
    void null_is_the_missing_value_test() {
        Assertions.assertThat(CellParser.parse("null, \"PT\"", ColumnType.STRING))
                .isEqualTo(positive(new NullTest(), new Equals("PT")));
    }

    @Test
    void numbers_support_literals_comparisons_and_intervals() {
        Assertions.assertThat(CellParser.parse("< 18, 21, >= 65.5", ColumnType.NUMBER))
                .isEqualTo(positive(
                        new Compare(Operator.LT, new BigDecimal("18")),
                        new Equals(new BigDecimal("21")),
                        new Compare(Operator.GE, new BigDecimal("65.5"))));
    }

    @Test
    void booleans_are_true_or_false_without_comparisons() {
        Assertions.assertThat(CellParser.parse("true", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(true)));
        Assertions.assertThat(CellParser.parse("not(false)", ColumnType.BOOLEAN)).isEqualTo(negated(new Equals(false)));
        Assertions.assertThat(CellParser.parse("true, null", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(true), new NullTest()));
        Assertions.assertThat(CellParser.parse("true", ColumnType.STRING))
                .as("an unquoted word in a STRING column is still text")
                .isEqualTo(positive(new Equals("true")));
    }

    @Test
    void strict_feel_reads_unquoted_words_as_names_not_strings() {
        // Praecepta's own tables (Excel, CSV, code) keep the shorter syntax
        Assertions.assertThat(CellParser.parse("PT, ES", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        // in DMN an unquoted word names another input, compared with when evaluating
        Assertions.assertThat(CellParser.parseFeel("\"PT\", \"ES\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parseFeel("not(Complex.aString)", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals(new UnaryTests.Name("Complex.aString")))));
        Assertions.assertThat(CellParser.parseFeel("[numB..10], > dateE", ColumnType.NUMBER))
                .isEqualTo(positive(new Range(new UnaryTests.Name("numB"), true, new BigDecimal("10"), true),
                        new Compare(Operator.GT, new UnaryTests.Name("dateE"))));
        // true, false and null stay literals; and a list holding a name is not a list of values
        Assertions.assertThat(CellParser.parseFeel("true", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(Boolean.TRUE)));
        Assertions.assertThat(CellParser.parseFeel("\"PT\", ES", ColumnType.STRING).values()).isEmpty();
    }

    @Test
    void strict_feel_needs_the_date_and_time_functions() {
        // in FEEL a bare 2025-01-31 is a subtraction and 08:30 is not valid at all
        Assertions.assertThat(CellParser.parse("< 2025-01-31", ColumnType.DATE))
                .isEqualTo(CellParser.parseFeel("< date(\"2025-01-31\")", ColumnType.DATE));
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("< 2025-01-31", ColumnType.DATE))
                .hasMessageContaining("is not a date; write date(\"yyyy-mm-dd\")");
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("08:30", ColumnType.TIME))
                .hasMessageContaining("is not a time");
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("2025-01-31T08:30", ColumnType.DATE_TIME))
                .hasMessageContaining("is not a date and time");
    }

    @Test
    void times_and_date_times_use_their_functions_or_iso_text() {
        var morning = LocalTime.of(8, 30);
        Assertions.assertThat(CellParser.parse("[time(\"08:30:00\")..12:00)", ColumnType.TIME))
                .isEqualTo(positive(new Range(morning, true, LocalTime.NOON, false)));
        Assertions.assertThat(CellParser.parse(">= 08:30", ColumnType.TIME))
                .isEqualTo(positive(new Compare(Operator.GE, morning)));
        var moment = LocalDateTime.of(2025, 1, 31, 8, 30, 15, 500_000_000);
        Assertions.assertThat(CellParser.parse("date and time(\"2025-01-31T08:30:15.5\"), 2025-01-31T08:30:15.5", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(moment), new Equals(moment)));
    }

    @Test
    void times_may_have_an_offset_and_date_times_an_offset_or_a_zone_id() {
        var plusOne = ZoneOffset.ofHours(1);
        Assertions.assertThat(CellParser.parseFeel("time(\"08:30:00+01:00\"), < time(\"08:30:00Z\")", ColumnType.TIME))
                .isEqualTo(positive(new Equals(OffsetTime.of(8, 30, 0, 0, plusOne)),
                        new Compare(Operator.LT, OffsetTime.of(8, 30, 0, 0, ZoneOffset.UTC))));
        Assertions.assertThat(CellParser.parse("08:30-05:00", ColumnType.TIME))
                .isEqualTo(positive(new Equals(OffsetTime.of(8, 30, 0, 0, ZoneOffset.ofHours(-5)))));
        var paris = ZonedDateTime.of(2025, 1, 31, 8, 30, 0, 0, ZoneId.of("Europe/Paris"));
        Assertions.assertThat(CellParser.parseFeel("date and time(\"2025-01-31T08:30:00@Europe/Paris\")", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(paris)));
        Assertions.assertThat(CellParser.parse("2025-01-31T08:30@Europe/Paris, 2025-01-31T08:30Z", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(paris), new Equals(ZonedDateTime.of(2025, 1, 31, 8, 30, 0, 0, ZoneOffset.UTC))));
        // the ends of an interval are ordered by instant: 08:30+01:00 is 07:30Z
        Assertions.assertThat(CellParser.parseFeel("[time(\"08:30:00+01:00\")..time(\"07:30:00Z\")]", ColumnType.TIME))
                .isEqualTo(positive(new Range(OffsetTime.of(8, 30, 0, 0, plusOne), true, OffsetTime.of(7, 30, 0, 0, ZoneOffset.UTC), true)));
    }

    @Test
    void zoned_literals_are_written_back_as_they_were_read() {
        for (var cell : List.of("time(\"08:30:00+01:00\")", "time(\"08:30:00.5Z\")", "date and time(\"2025-01-31T08:30:00Z\")",
                "date and time(\"2025-01-31T08:30:00-05:00\")", "date and time(\"2025-01-31T08:30:00@Europe/Paris\")")) {
            var type = cell.startsWith("time") ? ColumnType.TIME : ColumnType.DATE_TIME;
            var value = CellParser.parseFeel(cell, type).values().orElseThrow().getFirst();
            Assertions.assertThat(FeelLiterals.literal(value)).isEqualTo(cell);
            Assertions.assertThat(type == ColumnType.TIME ? FeelTemporals.parseTime(FeelLiterals.text(value))
                    : FeelTemporals.parseDateTime(FeelLiterals.text(value))).isEqualTo(value);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "[1..5];  true;  true",
            "(1..5];  false; true",
            "]1..5];  false; true",
            "[1..5);  true;  false",
            "[1..5[;  true;  false",
            "]1..5[;  false; false",
    })
    void interval_brackets_set_open_and_closed_ends(String cell, boolean lowClosed, boolean highClosed) {
        Assertions.assertThat(CellParser.parse(cell, ColumnType.NUMBER))
                .isEqualTo(positive(new Range(BigDecimal.ONE, lowClosed, new BigDecimal(5), highClosed)));
    }

    @Test
    void intervals_with_outward_brackets_split_correctly_from_following_tests() {
        Assertions.assertThat(CellParser.parse("]1..2[, 7", ColumnType.NUMBER))
                .isEqualTo(positive(new Range(BigDecimal.ONE, false, new BigDecimal(2), false), new Equals(new BigDecimal(7))));
    }

    @Test
    void escapes_and_parentheses_decide_where_a_test_ends() {
        // an escaped backslash does not escape the closing quote
        Assertions.assertThat(CellParser.parse("\"a\\\\\", \"b\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a\\"), new Equals("b")));
        // the parenthesis of a date call does not close an interval; the one after it does
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-01-31\")), date(\"2025-03-01\")", ColumnType.DATE))
                .isEqualTo(positive(
                        new Range(LocalDate.of(2025, 1, 1), true, LocalDate.of(2025, 1, 31), false),
                        new Equals(LocalDate.of(2025, 3, 1))));
    }

    @Test
    void unquoted_text_over_several_lines_is_not_checked_for_feel_syntax() {
        Assertions.assertThat(CellParser.parse("a(\nb", ColumnType.STRING)).isEqualTo(positive(new Equals("a(\nb")));
    }

    @Test
    void strings_compare_and_form_intervals_quoted_or_unquoted() {
        Assertions.assertThat(CellParser.parse("[\"A\"..\"M\"]", ColumnType.STRING))
                .isEqualTo(positive(new Range("A", true, "M", true)));
        Assertions.assertThat(CellParser.parseFeel("< \"N\", >= \"x y\"", ColumnType.STRING))
                .isEqualTo(positive(new Compare(Operator.LT, "N"), new Compare(Operator.GE, "x y")));
        // Praecepta's unquoted words work as ends too, and the ends may be equal
        Assertions.assertThat(CellParser.parse("(a..m], [PT..PT]", ColumnType.STRING))
                .isEqualTo(positive(new Range("a", false, "m", true), new Range("PT", true, "PT", true)));
        Assertions.assertThat(CellParser.parse("not(< \"N\")", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Negated(List.of(new Compare(Operator.LT, "N"))));
    }

    @Test
    void dates_use_the_date_function_or_iso_text() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 12, 31);
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-12-31\")]", ColumnType.DATE))
                .isEqualTo(positive(new Range(start, true, end, true)));
        Assertions.assertThat(CellParser.parse("< 2025-01-01", ColumnType.DATE))
                .isEqualTo(positive(new Compare(Operator.LT, start)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "BOOLEAN; < true;         comparisons are not supported on BOOLEAN columns",
            "BOOLEAN; [false..true];  intervals are not supported on BOOLEAN columns",
            "BOOLEAN; yes;            is not a boolean",
            "TIME;   time(\"25:00:00\"); invalid time",
            "TIME;   8am;             is not a time",
            "TIME;   time(\"10:00:00@Europe/Paris\"); is a time with a zone id, which is not supported",
            "TIME;   10:00:00+2;      is not a time",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00@Mars/Olympus\"); invalid date and time",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00-0500\"); invalid date and time",
            "DATE_TIME; date and time(\"2025-10-26T02:30+01:00[Europe/Paris]\"); write a zone id as @Europe/Paris",
            "TIME;   [time(\"11:00:00\")..time(\"10:00:00+05:00\")]; starts after it ends",
            "TIME;   [time(\"10:00:00Z\")..time(\"10:00:00+01:00\")]; starts after it ends",
            "DATE_TIME; 2025-01-01;   is not a date and time",
            "DATE_TIME; date and time(\"2025-01-31\"); invalid date and time",
            "TIME;   time(\"10:00:00+0200\"); invalid time",
            "DATE_TIME; [date and time(\"2025-01-02T00:00:00\")..date and time(\"2025-01-01T00:00:00\")]; starts after it ends",
            "NUMBER; abc;             is not a number",
            "NUMBER; [5..1];          starts after it ends",
            "DATE;   date(\"2025-02-30\"); invalid date",
            "DATE;   2025;            is not a date",
            "STRING; \"open;          unterminated string",
            "NUMBER; [1..2;           unterminated interval",
            "STRING; a,,b;            empty test",
            "STRING; a(b;             quote the text",
            "STRING; \"a\\\";         unterminated string",
            "STRING; a), b;           unexpected characters in 'a), b'",
            "NUMBER; [1..(2, 3];      unterminated interval",
            "NUMBER; [1..\"2];        unterminated interval",
    })
    void invalid_cells_are_rejected_with_a_reason(ColumnType type, String cell, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parse(cell, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }

    private static UnaryTests positive(UnaryTests.Test... tests) {
        return new UnaryTests.Positive(List.of(tests));
    }

    private static UnaryTests negated(UnaryTests.Test... tests) {
        return new UnaryTests.Negated(List.of(tests));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "age                          | true",
            "Credit Score                 | true",
            "Applicant data.Monthly income | true",
            "Rate 2                       | true",
            "is_ok?                       | true",
            "𝓐ge                       | true",
            "Ⅷ rate                    | false",
            "2nd rate                     | false",
            "a..b                         | false",
            "a. b                         | false",
            "a  b                         | false",
            "a.2b                         | false",
            "age * 2                      | false",
            "f(x)                         | false",
    })
    void feel_names_allow_single_spaces_between_words(String text, boolean name) {
        Assertions.assertThat(CellParser.isName(text)).as(text).isEqualTo(name);
    }

    @Test
    void very_long_names_are_checked_without_a_stack_overflow() {
        Assertions.assertThat(CellParser.isName("a".repeat(1_000_000))).isTrue();
        Assertions.assertThat(CellParser.isName("a b.".repeat(250_000) + "c")).isTrue();
    }
}
