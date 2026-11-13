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
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;

/** The strict FEEL cells of a column that holds lists: {@code list contains(?, "PT")} and its companions. */
class ListCellsTest {

    @Test
    void each_form_reads_as_the_test_it_applies_to_the_elements() {
        var cell = "list contains(?, \"PT\") or some x in ? satisfies x < \"M\" or some e in ? satisfies e in (\"a\"..\"b\"] or ? = null";
        Assertions.assertThat(CellParser.parseFeelList(cell, ColumnType.STRING)).isEqualTo(new UnaryTests.Positive(List.of(
                new Equals("PT"), new Compare(Operator.LT, "M"), new Range("a", false, "b", true), new NullTest())));
    }

    @Test
    void list_contains_null_tests_the_elements_and_equals_null_the_list() {
        Assertions.assertThat(CellParser.parseFeelList("list contains(?, null) or list contains(?,\"null\") or ? = null", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Positive(List.of(new UnaryTests.NullElement(), new Equals("null"), new NullTest())));
        var negated = CellParser.parseFeelList("not(list contains( ? , null ))", ColumnType.NUMBER);
        Assertions.assertThat(negated).isEqualTo(new UnaryTests.Negated(List.of(new UnaryTests.NullElement())));
        Assertions.assertThat(DmnWriter.renderList(negated)).isEqualTo("not(list contains(?, null))");
        Assertions.assertThatThrownBy(() -> DmnWriter.render(negated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("list contains(?, null) only tests a list's elements");
    }

    @Test
    void not_any_and_function_literals_are_read() {
        Assertions.assertThat(CellParser.parseFeelList("not( list contains( ? , date(\"2025-01-31\") ) )", ColumnType.DATE))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals(LocalDate.of(2025, 1, 31)))));
        Assertions.assertThat(CellParser.parseFeelList(" - ", ColumnType.NUMBER)).isEqualTo(new UnaryTests.Any());
        Assertions.assertThat(CellParser.parseFeelList("list contains(?, \"a or b\") or list contains(?, \"x)\")", ColumnType.STRING))
                .as("or and parentheses inside strings").isEqualTo(new UnaryTests.Positive(List.of(new Equals("a or b"), new Equals("x)"))));
    }

    @Test
    void writing_and_reading_back_keeps_the_tests() {
        var tests = CellParser.parse("1, >= 18, (2..5], null", ColumnType.NUMBER);
        var written = DmnWriter.renderList(tests);
        Assertions.assertThat(written)
                .isEqualTo("list contains(?, 1) or some x in ? satisfies x >= 18 or some x in ? satisfies x in (2..5] or ? = null");
        Assertions.assertThat(CellParser.parseFeelList(written, ColumnType.NUMBER)).isEqualTo(tests);
        Assertions.assertThat(CellParser.parseFeelList("not(" + written + ")", ColumnType.NUMBER))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals(BigDecimal.ONE), new Compare(Operator.GE, new BigDecimal(18)),
                        new Range(new BigDecimal(2), false, new BigDecimal(5), true), new NullTest())));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a plain literal      | \"PT\"                                  | does not test a list's elements",
            "a comparison         | < 5                                     | does not test a list's elements",
            "another function     | count(?) > 1                            | does not test a list's elements",
            "no argument          | list contains(?)                        | does not test a list's elements",
            "another variable     | some x in ? satisfies y > 1             | does not test a list's elements",
            "a word, not in       | some x inside ? satisfies x > 1         | does not test a list's elements",
            "an input's name      | list contains(?, limit)                 | not against 'limit'",
            "a bound named        | some x in ? satisfies x < limit         | not against 'limit'",
            "an invalid literal   | list contains(?, \"a\")                 | is not a number",
            "an empty disjunct    | list contains(?, 1) or                  | does not test a list's elements",
    })
    void other_expressions_are_rejected(String description, String cell, String message) {
        var type = cell.contains("\"") && !description.equals("an invalid literal") ? ColumnType.STRING : ColumnType.NUMBER;
        Assertions.assertThatThrownBy(() -> CellParser.parseFeelList(cell, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(message);
    }
}
