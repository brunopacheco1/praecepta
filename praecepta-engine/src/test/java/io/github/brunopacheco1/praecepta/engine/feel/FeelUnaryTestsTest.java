/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's unary tests as a decision table's input entry holds them (DMN 1.5, grammar rules 13 to 17, 7.3.2 and
 * Table 55): what they read, whether they are the simple unary tests the bitset index evaluates, and whether a value
 * satisfies them.
 */
class FeelUnaryTestsTest {

    private static final FeelExpression.Scope SCOPE = FeelExpression.scope(List.of("Limit", "Low", "Applicant"), Map.of());

    private static FeelUnaryTests parse(String cell) {
        return FeelUnaryTests.parse(cell, SCOPE);
    }

    private static boolean matches(String cell, Object value) {
        return parse(cell).matches(value, Map.of("Limit", BigDecimal.TEN, "Low", BigDecimal.ONE)::get, null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // literals and names, as grammar rule 7 has them
        "1                     | true",
        "\"a\", \"b\"          | true",
        "-1                    | true",
        "null                  | true",
        "@\"2025-01-31\"       | true",
        "date(\"2025-01-31\")  | true",
        "Limit                 | true",
        "Applicant.age         | true",
        // comparisons with one, = e and != e among them, and intervals between two
        "< 10                  | true",
        ">= Limit              | true",
        "= Limit               | true",
        "!= \"a\"              | true",
        "[1..Limit)            | true",
        "]Low..10]             | true",
        // a negation, a path into what is not a name, an expression, a test of ?, a function: FEEL beyond them
        "- 1                   | false",
        "> - 1                 | false",
        "Limit + 1             | false",
        "< Limit * 2           | false",
        "[Low..Low + 10]       | false",
        "= Limit + 1           | false",
        "? > 5                 | false",
        "[1, 2]                | false",
        "function(x) x > 1     | false",
        "1, ? > 5              | false",
        // an invalid literal is still a literal: the subset rejects it
        "date(\"2025-13-01\")  | true",
    })
    void the_simple_unary_tests_are_literals_and_names_compared_or_between(String cell, boolean simple) {
        assertThat(parse(cell).isSimple()).as(cell).isEqualTo(simple);
    }

    @Test
    void the_names_read_are_those_of_other_inputs_not_the_value_tested() {
        assertThat(parse("? > Limit, [Low..Limit], ? < Low").names()).containsExactly("Limit", "Low");
        assertThat(parse("-").names()).isEmpty();
        assertThat(parse("not(Applicant.age, Other Input)").names()).containsExactly("Applicant", "Other Input");
    }

    @Test
    void the_literals_held_are_listed_in_order() {
        assertThat(parse("< 10, \"a\", [@\"2025-01-31\"..null), true").literals())
                .containsExactly(BigDecimal.TEN, "a", LocalDate.of(2025, 1, 31), true);
    }

    @Test
    void what_is_not_evaluated_yet_is_named() {
        assertThat(parse("> (function(a) external {java: {class: \"x\"}})(Limit)").unsupported())
                .containsExactly(new FeelExpression.Unsupported("an external function (Java or PMML)", "0.11", 119));
        assertThat(parse("> count(Limit)").unsupported()).isEmpty();
        assertThat(parse("> Limit").unsupported()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "-                         | 5      | true",
        "-                         |        | true",
        "> Limit, < 0              | 11     | true",
        "not(> Limit, < 0)         | 5      | true",
        "not(> Limit)              |        | false",
        "> Limit                   |        | false",
        "[Low..Limit]              | 10     | true",
        "? > 5 and ? < Limit       | 7      | true",
        "= null                    |        | true",
        "null                      |        | true",
        "function(x) x > Limit     | 11     | true",
        "function(x) x > Limit     | 9      | false",
        "[Low..null]               | 0      | false",
        "not([Low..null])          | 0      | false",
        "- 1                       | -1     | true",
        "> - 1                     | 0      | true",
    })
    void a_value_satisfies_unary_tests_as_table_55_and_7_3_2_have_it(String cell, String value, boolean satisfied) {
        // 7.3.2(c): a test whose value is a function is satisfied when it is true applied to the value; a range with a
        // missing endpoint is a test that is null, which neither the cell nor its not(...) is satisfied by (case 3)
        var tested = value == null ? null : new BigDecimal(value);
        assertThat(matches(cell, tested)).as("%s for %s", cell, value).isEqualTo(satisfied);
    }

    @Test
    void a_java_number_is_tested_as_the_feel_number_of_its_value() {
        var tests = FeelUnaryTests.parse("> 0, [1..10]", FeelExpression.scope(List.of(), Map.of()));
        assertThat(tests.matches(1, name -> null, null)).isTrue();
        assertThat(tests.matches(-1L, name -> null, null)).isFalse();
        assertThat(tests.matches(0.5, name -> null, null)).isTrue();
    }

    @Test
    void hour_24_in_a_cell_is_midnight_of_the_next_day() {
        // DMN 1.5, 10.3.4.1, and the DMN TCK's 0100-arithmetic: as a literal expression reads it (case 19)
        var cell = CellParser.parseFeel("< @\"2021-01-01T24:00:00\"", ColumnType.DATE_TIME);
        assertThat(cell).isEqualTo(new UnaryTests.Positive(List.of(new UnaryTests.Compare(UnaryTests.Operator.LT,
                LocalDateTime.of(2021, 1, 2, 0, 0)))));
        assertThat(matches("< @\"2021-01-01T24:00:00\"", LocalDateTime.of(2021, 1, 1, 23, 59))).isTrue();
        assertThat(matches("< @\"2021-01-01T24:00:00\"", LocalDateTime.of(2021, 1, 2, 0, 0))).isFalse();
    }

    @Test
    void a_cell_the_simple_unary_tests_reject_is_read_as_feel_unless_it_is_simple() {
        var rejected = new CellSyntaxException("x", "rejected");
        // the subset rejects - 1 and > - 1, which FEEL reads as negations: so they are FEEL
        for (var cell : List.of("- 1", "> - 1", "[Low..Low + 10]", "? > Limit")) {
            assertThat(FeelUnaryTests.parseCell(cell, SCOPE, rejected).text()).isEqualTo(cell);
        }
        // a simple cell it rejected is wrong, and stays rejected; so is a cell that is no FEEL
        for (var cell : List.of("\"a\"..\"b\"", "1 +", "[10..1]")) {
            assertThatThrownBy(() -> FeelUnaryTests.parseCell(cell, SCOPE, rejected)).isSameAs(rejected);
        }
    }

    @Test
    void a_cell_of_words_feel_reads_as_an_expression_is_no_name_of_another_input() {
        // ? and if ... then ... else ... are FEEL's, not words of a name the simple unary tests compare with
        for (var cell : List.of("? instance of number", "if true then Limit else Low")) {
            CellSyntaxException rejected = null;
            try {
                CellParser.parseFeel(cell, ColumnType.NUMBER);
            } catch (CellSyntaxException e) {
                rejected = e;
            }
            assertThat(rejected).as(cell).isNotNull();
            assertThat(FeelUnaryTests.parseCell(cell, SCOPE, rejected).isSimple()).isFalse();
        }
        assertThat(matches("? instance of number", BigDecimal.ONE)).isTrue();
        assertThat(matches("if true then Limit else Low", BigDecimal.TEN)).isTrue();
        // a name of a few words is one, whichever words they are
        assertThat(CellParser.parseFeel("days in weekend", ColumnType.NUMBER).namesInputs()).isTrue();
    }

    @Test
    void errors_are_reported_to_whoever_listens() {
        var errors = new ArrayList<String>();
        assertThat(parse("> Limit + \"a\"").matches(BigDecimal.ONE, name -> BigDecimal.ONE, errors::add)).isFalse();
        assertThat(errors.getFirst()).contains("does not combine");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not(1", "1,", ">", "[1..2"})
    void what_is_not_unary_tests_is_a_syntax_error(String cell) {
        assertThatThrownBy(() -> parse(cell)).isInstanceOf(ExpressionSyntaxException.class);
    }
}
