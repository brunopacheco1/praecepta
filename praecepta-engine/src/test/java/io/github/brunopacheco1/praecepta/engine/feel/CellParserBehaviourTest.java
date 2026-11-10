/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;

/** Pins the parser's corner cases: how a cell is split into tests and how each test is read. */
class CellParserBehaviourTest {

    @Test
    void two_character_operators_are_not_read_as_one_character_ones() {
        Assertions.assertThat(CellParser.parse("<= 5, >= 7", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Positive(List.of(
                        new Compare(Operator.LE, new BigDecimal(5)), new Compare(Operator.GE, new BigDecimal(7)))));
    }

    @Test
    void an_empty_not_has_an_empty_test() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("not()", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell 'not()': empty test between commas");
    }

    @Test
    void outward_brackets_exclude_their_end_on_either_side() {
        Assertions.assertThat(CellParser.parseFeel("]1..5], [1..5[", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Positive(List.of(
                        new Range(BigDecimal.ONE, false, new BigDecimal(5), true),
                        new Range(BigDecimal.ONE, true, new BigDecimal(5), false))));
    }

    @Test
    void an_interval_must_not_start_after_it_ends() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("[5..1]", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '[5..1]': interval starts after it ends");
    }

    @Test
    void an_interval_on_a_boolean_column_is_rejected_before_its_ends_are_read() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("[a..b]", ColumnType.BOOLEAN))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '[a..b]': intervals are not supported on BOOLEAN columns");
    }

    @Test
    void a_string_interval_must_not_start_after_it_ends() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("[\"b\"..\"a\"]", ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '[\"b\"..\"a\"]': interval starts after it ends");
    }

    @Test
    void an_unbalanced_closing_parenthesis_stops_commas_from_splitting_tests() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("1), 2", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '1), 2': '1), 2' is not a number");
        // a later opening parenthesis brings the depth back to zero, and commas split again
        Assertions.assertThatThrownBy(() -> CellParser.parse("1), (2, 3", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '1), (2, 3': '1), (2' is not a number");
    }

    @Test
    void the_dots_of_an_interval_are_found_outside_strings_whose_quotes_may_be_escaped() {
        // the escaped quote does not end the string, so the .. inside it is skipped and the second one taken
        Assertions.assertThatThrownBy(() -> CellParser.parse("[\" \\\" .. \" .. 2]", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '[\" \\\" .. \" .. 2]': '\" \\\" .. \"' is not a number");
        Assertions.assertThat(CellParser.parse("[\"a\\\"..b\"..\"c\"]", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Range("a\"..b", true, "c", true))));
    }

    @Test
    void a_multi_line_unquoted_interval_end_survives_being_written_as_feel() {
        // found by fuzzing: a multi-line unquoted end may hold quotes and dots, which DmnWriter escapes
        var cell = "]\\\r\"\"\"..\"..\\.[,+";
        var parsed = CellParser.parse(cell, ColumnType.STRING);
        Assertions.assertThat(parsed).isEqualTo(new UnaryTests.Positive(List.of(
                new Range("\\\r\"\"\"..\"", false, "\\.", false), new Equals("+"))));
        Assertions.assertThat(CellParser.parse(DmnWriter.render(parsed), ColumnType.STRING)).isEqualTo(parsed);
    }

    @Test
    void an_escaped_quote_does_not_close_a_string() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("\"a\\\"", ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '\"a\\\"': unterminated string");
        Assertions.assertThat(CellParser.parse("\"a\\\"b\"", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new Equals("a\"b"))));
    }

    @Test
    void an_unterminated_string_or_interval_is_rejected() {
        Assertions.assertThatThrownBy(() -> CellParser.parse("\"a, b", ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '\"a, b': unterminated string");
        Assertions.assertThatThrownBy(() -> CellParser.parse("[1..2, 3", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessage("Invalid cell '[1..2, 3': unterminated interval");
    }
}
