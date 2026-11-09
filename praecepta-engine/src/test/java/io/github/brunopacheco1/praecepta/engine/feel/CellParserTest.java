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
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;

class CellParserTest {

    @Test
    void blank_and_dash_match_anything() {
        for (var cell : new String[] {null, "", "   ", "-", " - "}) {
            Assertions.assertThat(CellParser.parse(cell, ColumnType.STRING)).as("'%s'", cell).isEqualTo(new UnaryTests.Any());
        }
    }

    @Test
    void legacy_unquoted_lists_still_mean_string_literals() {
        Assertions.assertThat(CellParser.parse("PT,ES", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("PT"), new Equals("ES"))));
        Assertions.assertThat(CellParser.parse(" PT , ES ", ColumnType.STRING)) // whitespace is trimmed
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("PT"), new Equals("ES"))));
        Assertions.assertThat(CellParser.parse("10", ColumnType.STRING)) // a code, not a number
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("10"))));
    }

    @Test
    void quoted_strings_may_contain_commas_and_escaped_quotes() {
        Assertions.assertThat(CellParser.parse("\"a,b\", \"say \\\"hi\\\"\"", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("a,b"), new Equals("say \"hi\""))));
    }

    @Test
    void not_negates_a_list() {
        Assertions.assertThat(CellParser.parse("not(\"FR\", DE)", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals("FR"), new Equals("DE"))));
    }

    @Test
    void not_may_wrap_half_open_intervals() {
        Assertions.assertThat(CellParser.parse("not(0, [0..1))", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Negated(List.of(
                        new Equals(BigDecimal.ZERO),
                        new Range(BigDecimal.ZERO, true, BigDecimal.ONE, false))));
    }

    @Test
    void null_is_the_missing_value_test() {
        Assertions.assertThat(CellParser.parse("null, \"PT\"", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new NullTest(), new Equals("PT"))));
    }

    @Test
    void numbers_support_literals_comparisons_and_intervals() {
        Assertions.assertThat(CellParser.parse("< 18, 21, >= 65.5", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Positive(List.of(
                        new Compare(Operator.LT, new BigDecimal("18")),
                        new Equals(new BigDecimal("21")),
                        new Compare(Operator.GE, new BigDecimal("65.5")))));
    }

    @Test
    void booleans_are_true_or_false_without_comparisons() {
        Assertions.assertThat(CellParser.parse("true", ColumnType.BOOLEAN))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals(true))));
        Assertions.assertThat(CellParser.parse("not(false)", ColumnType.BOOLEAN))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals(false))));
        Assertions.assertThat(CellParser.parse("true, null", ColumnType.BOOLEAN))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals(true), new NullTest())));
        Assertions.assertThat(CellParser.parse("true", ColumnType.STRING))
                .as("an unquoted word in a STRING column is still text")
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("true"))));
    }

    @Test
    void strict_feel_reads_unquoted_words_as_names_not_strings() {
        // Praecepta's own tables (Excel, CSV, code) keep the shorter syntax
        Assertions.assertThat(CellParser.parse("PT, ES", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("PT"), new Equals("ES"))));
        // in DMN an unquoted word refers to another variable, which is not supported
        Assertions.assertThat(CellParser.parseFeel("\"PT\", \"ES\"", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("PT"), new Equals("ES"))));
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("not(Complex.aString)", ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("'Complex.aString' is a FEEL name");
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
                .isEqualTo(new UnaryTests.Positive(List.of(new Range(morning, true, LocalTime.NOON, false))));
        Assertions.assertThat(CellParser.parse(">= 08:30", ColumnType.TIME))
                .isEqualTo(new UnaryTests.Positive(List.of(new Compare(Operator.GE, morning))));
        var moment = LocalDateTime.of(2025, 1, 31, 8, 30, 15, 500_000_000);
        Assertions.assertThat(CellParser.parse("date and time(\"2025-01-31T08:30:15.5\"), 2025-01-31T08:30:15.5", ColumnType.DATE_TIME))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals(moment), new Equals(moment))));
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
                .isEqualTo(new UnaryTests.Positive(List.of(new Range(BigDecimal.ONE, lowClosed, new BigDecimal(5), highClosed))));
    }

    @Test
    void intervals_with_outward_brackets_split_correctly_from_following_tests() {
        Assertions.assertThat(CellParser.parse("]1..2[, 7", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Positive(List.of(
                        new Range(BigDecimal.ONE, false, new BigDecimal(2), false),
                        new Equals(new BigDecimal(7)))));
    }

    @Test
    void escapes_and_parentheses_decide_where_a_test_ends() {
        // an escaped backslash does not escape the closing quote
        Assertions.assertThat(CellParser.parse("\"a\\\\\", \"b\"", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("a\\"), new Equals("b"))));
        // the parenthesis of a date call does not close an interval; the one after it does
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-01-31\")), date(\"2025-03-01\")", ColumnType.DATE))
                .isEqualTo(new UnaryTests.Positive(List.of(
                        new Range(LocalDate.of(2025, 1, 1), true, LocalDate.of(2025, 1, 31), false),
                        new Equals(LocalDate.of(2025, 3, 1)))));
    }

    @Test
    void unquoted_text_over_several_lines_is_not_checked_for_feel_syntax() {
        Assertions.assertThat(CellParser.parse("a(\nb", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("a(\nb"))));
    }

    @Test
    void dates_use_the_date_function_or_iso_text() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 12, 31);
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-12-31\")]", ColumnType.DATE))
                .isEqualTo(new UnaryTests.Positive(List.of(new Range(start, true, end, true))));
        Assertions.assertThat(CellParser.parse("< 2025-01-01", ColumnType.DATE))
                .isEqualTo(new UnaryTests.Positive(List.of(new Compare(Operator.LT, start))));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "STRING; < 10;            only supported on NUMBER, DATE, TIME and DATE_TIME",
            "STRING; [a..b];          only supported on NUMBER, DATE, TIME and DATE_TIME",
            "BOOLEAN; < true;         only supported on NUMBER, DATE, TIME and DATE_TIME",
            "BOOLEAN; yes;            is not a boolean",
            "TIME;   time(\"25:00:00\"); invalid time",
            "TIME;   8am;             is not a time",
            "TIME;   time(\"10:00:00Z\"); has a time zone",
            "TIME;   10:00:00+02:00;  is not a time",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00@Europe/Paris\"); has a time zone",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00-05:00\"); has a time zone",
            "DATE_TIME; 2025-01-01;   is not a date and time",
            "DATE_TIME; date and time(\"2025-01-31\"); invalid date and time",
            "TIME;   time(\"10:00:00+0200\"); has a time zone",
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
}
