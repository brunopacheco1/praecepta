/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            "STRING; < 10;            only supported on NUMBER and DATE",
            "STRING; [a..b];          only supported on NUMBER and DATE",
            "NUMBER; abc;             is not a number",
            "NUMBER; [5..1];          starts after it ends",
            "DATE;   date(\"2025-02-30\"); invalid date",
            "DATE;   2025;            is not a date",
            "STRING; \"open;          unterminated string",
            "NUMBER; [1..2;           unterminated interval",
            "STRING; a,,b;            empty test",
            "STRING; a(b;             quote the text",
    })
    void invalid_cells_are_rejected_with_a_reason(ColumnType type, String cell, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parse(cell, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }
}
