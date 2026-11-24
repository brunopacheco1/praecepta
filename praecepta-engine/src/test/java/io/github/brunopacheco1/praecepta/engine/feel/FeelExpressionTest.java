/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL expressions (DMN 1.5, chapter 10): each construct of the grammar (10.3.1.2) with the semantics of 10.3.2. Each
 * case compares an expression's value with the value of a FEEL literal, by FEEL's equality.
 */
class FeelExpressionTest {

    /** The expression's value, with no names given. */
    private static Object value(String expression) {
        return FeelExpression.parse(expression).evaluate(name -> null);
    }

    /**
     * Whether the expression's value is that of the expected literal ({@code null} for null), by FEEL's equality; the
     * expected literal is checked to have a value, so that an equality of two nulls passes only where null is expected.
     */
    private static void assertFeel(String expression, String expected) {
        var actual = value(expression);
        var wanted = value(expected);
        if (!expected.equals("null")) {
            assertThat(wanted).as("the expected value %s", expected).isNotNull();
        }
        assertThat(FeelValues.equality(actual, wanted)).as("%s is %s, not %s", expression, actual, expected).isEqualTo(Boolean.TRUE);
    }

    /** Whether the expression's value is null and its evaluation reports at least one error. */
    private static void assertNullWithAnError(String expression) {
        assertThat(value(expression)).isNull();
        assertThat(errors(expression)).isNotEmpty();
    }

    /** The errors an evaluation reports. */
    private static List<String> errors(String expression) {
        var errors = new ArrayList<String>();
        FeelExpression.parse(expression).evaluate(name -> null, errors::add);
        return errors;
    }

    // ---- literals (10.3.1.2, 10.3.2.1) ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "true                       | true",
        "false                      | false",
        "\"a\\\"b\"                 | \"a\\\"b\"",
        "1.2e3                      | 1200",
        ".5                         | 0.5",
        "@\"2025-01-31\"            | date(\"2025-01-31\")",
        "@\"P1Y2M\"                 | duration(\"P14M\")",
        "date(2025, 1, 31)          | @\"2025-01-31\"",
        "date and time(\"2021-01-01T24:00:00\") | @\"2021-01-02T00:00:00\"",
        "1.00000000000000000000000000000000001 = 1 | true",
        "123456789012345678901234567890123456789 | 123456789012345678901234567890123500000",
    })
    void literals_are_feel_values(String expression, String expected) {
        // DMN 1.5, 10.3.2.3.1: a number is a decimal128, so a literal of more than 34 significant digits is rounded
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "time(\"24:00:00\")             | time(\"00:00:00\")",
        "@\"24:00:00\"                  | @\"00:00:00\"",
        "@\"24:00:00.000Z\"             | @\"00:00:00Z\"",
        "@\"2021-01-01T24:00:00@Europe/Paris\" | @\"2021-01-02T00:00:00@Europe/Paris\"",
    })
    void hour_24_is_midnight_as_xml_schema_reads_a_time(String expression, String expected) {
        // DMN 1.5, 10.3.4.1: a time string is in XML Schema's lexical form for time, which takes 24:00:00 as 00:00:00;
        // a date time string is a date string, T and a time string, which the DMN TCK's 0100-arithmetic reads as the next day
        assertFeel(expression, expected);
        assertThat(FeelExpression.parse(expression).type(name -> null)).isNotEqualTo(FeelType.NULL);
    }

    @Test
    void times_and_date_times_compare_to_the_millisecond_and_keep_their_nanoseconds() {
        // DMN 1.5, 10.3.2.3.4 and 10.3.2.3.6: XML Schema's time and dateTime, whose precision XML Schema 1.0 (3.2.6) leaves
        // to the application, at least the millisecond; the DMN TCK's 0068-feel-equality (time_005, datetime_003_a)
        assertFeel("time(\"10:30:00.0001\") = time(\"10:30:00.0002\")", "true");
        assertFeel("date and time(\"2018-12-08T00:00:00.0001\") = date and time(\"2018-12-08T00:00:00.0000\")", "true");
        assertFeel("@\"10:30:00.0001Z\" < @\"10:30:00.0009Z\"", "false");
        assertFeel("@\"10:30:00.001\" > @\"10:30:00.0009\"", "true");
        assertFeel("@\"10:30:00.0005\" in [@\"10:30:00\"..@\"10:30:00\"]", "true");
        assertFeel("@\"10:30:00.123456789\".second", "0.123456789");
        assertFeel("@\"10:30:00.0002\" - @\"10:30:00.0001\"", "@\"PT0.0001S\"");
    }

    @Test
    void comments_are_whitespace() {
        // DMN 1.5, 10.3.1.2: // to the end of the line, and /* ... */
        assertFeel("1 /* a comment */ + // another\n 1", "2");
    }

    @Test
    void a_backslash_that_starts_no_escape_sequence_is_a_character_of_the_string() {
        // DMN 1.5, grammar rules 62 to 64; the DMN TCK's 0067-feel-split-function splits by the regular expression "\s"
        assertThat(value("\"a\\s\\\\b\\n\"")).isEqualTo("a\\s\\b\n");
    }

    @Test
    void null_is_null_and_its_type_is_null() {
        // DMN 1.5, 10.3.2.9.1: Null is the type of null alone
        assertThat(value("null")).isNull();
        assertThat(FeelExpression.parse("null").type(name -> null)).isEqualTo(FeelType.NULL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"date(\"2025-13-01\")", "@\"foo\"", "time(\"24:00:01\")", "date and time(\"2017-12-31T25:00:00\")", "time()"})
    void an_invalid_date_and_time_literal_is_null_with_an_error(String expression) {
        // the DMN TCK's 1115-feel-date-function to 1117-feel-date-and-time-function and 0093-feel-at-literals
        assertThat(value(expression)).isNull();
        assertThat(errors(expression)).hasSize(1);
        assertThat(FeelExpression.parse(expression).type(name -> null)).isEqualTo(FeelType.NULL);
    }

    // ---- arithmetic (10.3.2.3, Tables 57 to 62) -------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1 + 2 * 3                        | 7",
        "-4 ** 2                          | 16",
        "2 ** 3 ** 2                      | 64",
        "\"a\" + \"b\"                    | \"ab\"",
        "@\"2025-01-31\" + @\"P1D\"        | @\"2025-02-01\"",
        "@\"2021-01-01T24:00:00\" - @\"PT1S\" | @\"2021-01-01T23:59:59\"",
        "@\"P1D\" * 2                     | @\"P2D\"",
    })
    void arithmetic_combines_numbers_strings_dates_and_durations(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\" + 1", "1 + null", "null * null", "[1] + 1", "{a: 1} - 1", "true + 1", "10 / 0", "-\"a\"",
        "(function(a) a) * 2", "[1..2] + 1"})
    void operands_feel_does_not_combine_give_null_with_an_error(String expression) {
        // DMN 1.5, 10.3.2.15 and the text before Tables 56 and 58: "if input values are not of the listed types, the
        // result is null"; the DMN TCK's 0100-arithmetic reports each as an error, null operands included
        assertNullWithAnError(expression);
    }

    // ---- comparisons and logic (10.3.2.2, Tables 50, 53 and 54) ---------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1 = 1.0                  | true",
        "null = null              | true",
        "1 = null                 | false",
        "null != 1                | true",
        "[1, [2]] = [1, [2]]      | true",
        "{a: 1, b: 2} = {b: 2, a: 1} | true",
        "@\"P0D\" = @\"P0Y\"         | true",
        "(1..10] = ]1..10]        | true",
        "(< 10) = (null..10)      | false",
        "\"a\" < \"b\"            | true",
        "@\"2025-01-31\" >= @\"2025-01-30\" | true",
        "5 between 1 and 10       | true",
        "true and null            | null",
        "false and null           | false",
        "true or null             | true",
        "null or false            | null",
        "1 and true               | null",
    })
    void comparisons_and_three_valued_logic(String expression, String expected) {
        if (expected.equals("null")) {
            assertThat(value(expression)).isNull();
        } else {
            assertFeel(expression, expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 = \"1\"", "[1] = 1", "true < false", "1 < \"a\"", "null between 1 and 10", "@\"P1Y\" = @\"P365D\""})
    void values_of_two_types_compare_to_null_with_an_error(String expression) {
        // DMN 1.5, Tables 53 and 54; the DMN TCK's 0068-feel-equality and 0071-feel-between
        assertNullWithAnError(expression);
    }

    @Test
    void the_right_operand_of_and_and_or_is_not_evaluated_when_the_left_decides() {
        assertThat(errors("false and 1 + \"a\" = 1")).isEmpty();
        assertThat(errors("true or 1 + \"a\" = 1")).isEmpty();
    }

    // ---- in and unary tests (10.3.2.10, Table 55) -------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "5 in [1..10]                         | true",
        "10 in [1..10)                        | false",
        "5 in (< 3, > 4)                      | true",
        "5 in <= 5                            | true",
        "5 in (!= 5)                          | false",
        "\"b\" in [[\"f\"..\"h\"], [\"a\"..\"c\"]] | true",
        "[1, 2] in [[1, 2], [3]]              | true",
        "[1, 2] in [1, 2]                     | false",
        "true in [false, 2, 3]                | false",
        "{a: 1} in {a: 1}                     | true",
        "5 in (? > 3)                         | true",
        "5 in 5                               | true",
    })
    void in_tests_a_value_against_positive_unary_tests(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "5 in (null..10]      | null",
        "5 in [1..null]       | null",
        "5 in [null..10]      | null",
        "15 in [null..10]     | false",
        "-5 in [1..null]      | false",
        "5 in [10..1]         | false",
        "5 in [1..\"a\"]      | null",
        "5 between null and 1 | false",
    })
    void in_a_range_is_table_55s_comparisons_with_its_endpoints(String expression, String expected) {
        // DMN 1.5, Table 55: e1 in [e2..e3] is e1 >= e2 and e1 <= e3, three-valued as Table 50 has and; the DMN TCK's
        // 0072-feel-in has 5 in (null..10] null, and 5 in [1..null] an error
        assertFeel(expression, expected);
        assertThat(errors(expression)).hasSize(expected.equals("null") ? 1 : 0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "5 in (function(x) x > 3)                 | true",
        "2 in (function(x) x > 3)                 | false",
        "5 in (function(x) x)                     | null",
        "{f: function(x) x > 3, r: 5 in f}.r      | true",
    })
    void a_function_is_a_test_its_value_applied_to_holds(String expression, String expected) {
        // DMN 1.5, 7.3.2(c): a unary test is satisfied when "one of the expressions ... evaluates to true when the
        // implicit value is applied to it"
        assertFeel(expression, expected);
    }

    // ---- instance of (10.3.2.9) --------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1 instance of number                                  | true",
        "1 instance of Any                                     | true",
        "null instance of Any                                  | false",
        "null instance of Null                                 | true",
        "@\"2025-01-31\" instance of date and time             | false",
        "@\"P1D\" instance of days and time duration           | true",
        "[1, 2] instance of list<number>                       | true",
        "[1, \"a\"] instance of list<number>                   | false",
        "{a: 1, b: \"x\"} instance of context<a: number>       | true",
        "{a: 1} instance of context<a: number, b: string>      | false",
        "[1..2] instance of range<number>                      | true",
        "(function(a: number) a + 1) instance of function<number>->number | true",
        "(function(a: number) \"x\") instance of function<number>->number | false",
        "(function(a: context<a: string>) {b: 1}) instance of function<context<a: string, b: string>>->context<b: number> | true",
        "(< 10) instance of range<number>                      | true",
    })
    void instance_of_tests_a_value_against_a_type(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @Test
    void instance_of_reads_the_types_the_model_defines_and_no_other() {
        var types = Map.<String, FeelType>of("tAmounts", FeelType.listOf(ColumnType.NUMBER));
        var expression = FeelExpression.parse("x instance of tAmounts", List.of("x"), types);
        assertThat(expression.evaluate(Map.of("x", List.of(BigDecimal.ONE))::get)).isEqualTo(true);
        var errors = new ArrayList<String>();
        var other = FeelExpression.parse("x instance of tOther", List.of("x"), types);
        assertThat(other.evaluate(Map.of("x", BigDecimal.ONE)::get, errors::add)).isNull();
        assertThat(errors).singleElement().asString().contains("'tOther' is not a type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5 instance of foo", "5 instance of list", "[5] instance of list<foo>", "(function(a: foo) a)(1)",
        "5 instance of context"})
    void instance_of_a_type_feel_does_not_have_is_null_with_an_error(String expression) {
        // DMN 1.5, Table 61: "If e2 cannot be mapped to a node in the lattice L, the result is null", and "123 instance
        // of list is null as a list type requires parameters"; a parameter of such a type likewise
        assertThat(value(expression)).isNull();
        assertThat(errors(expression).getFirst()).contains("is not a type");
        assertThat(FeelExpression.parse(expression).type(name -> null)).isEqualTo(FeelType.NULL);
    }

    // ---- if, for, some, every (10.3.2.13, 10.3.2.14) --------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "if 1 > 0 then \"yes\" else \"no\"                          | \"yes\"",
        "if null then 1 else 2                                    | 2",
        "if \"true\" then 1 else 2                                | 2",
        "for i in 1..3 return i * 2                               | [2, 4, 6]",
        "for i in 3..1 return i                                   | [3, 2, 1]",
        "for i in [1, 2], j in [10, 20] return i + j              | [11, 21, 12, 22]",
        "for x in [[1, 2], [3]], y in x return y                  | [1, 2, 3]",
        "for i in 0..4 return if i = 0 then 1 else i * partial[-1] | [1, 1, 2, 6, 24]",
        "for i in 1..3 return partial                             | [[], [[]], [[], [[]]]]",
        "(for i in 1..3 return partial)[1] = []                   | true",
        "for d in @\"2025-01-30\"..@\"2025-02-01\" return d.day     | [30, 31, 1]",
        "some x in [1, 2, 3] satisfies x > 2                      | true",
        "every x in [1, 2, 3] satisfies x > 2                     | false",
        "every x in [] satisfies x > 2                            | true",
        "some x in [] satisfies x > 2                             | false",
        "some x in [1, 2], y in [3, 4] satisfies x + y = 6        | true",
        "every x in [1, 2], y in [3, 4] satisfies x < y           | true",
        "some x in [null, true] satisfies x                       | true",
        "every x in [false, null] satisfies x                     | false",
    })
    void conditionals_and_iterations(String expression, String expected) {
        // DMN 1.5, 10.3.2.14 and Table 49: partial is the list of the values before the current one
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"some x in [null, 1] satisfies x > 2", "every x in [1, null] satisfies x > 0", "every x in [1, 2] satisfies null",
        "some x in [1] satisfies 1", "every x in [true, null] satisfies x", "some x in null satisfies true", "every x in null satisfies true"})
    void some_and_every_are_or_and_and_of_three_values(String expression) {
        // DMN 1.5, Table 49 reads some as false or-ed with the condition for each element, and every as true and-ed with
        // it, by the or and the and of Table 50, so a condition that is null, or not a boolean, and decides nothing gives
        // null, as the DMN TCK's 1153-feel-some and 1154-feel-every (test 006) have it
        assertThat(value(expression)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"for i in \"a\"..\"z\" return i", "for i in [1..2] return i", "for i in 1 return i",
        "for i in 1..10000000 return i", "for i in 1..2000, j in 1..2000 return i", "for i in 1.5..3 return i"})
    void an_iteration_over_what_is_not_a_list_or_too_long_is_null_with_an_error(String expression) {
        // the DMN TCK's 0084-feel-for-loops; DMN 1.5, 10.3.2.14: a range iterates whole numbers; and Praecepta's bound of a
        // million steps an evaluation
        assertNullWithAnError(expression);
    }

    // ---- lists, contexts, paths and filters (10.3.2.5, 10.3.2.6, Table 65) ---------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[1, 2, 3][1]                                  | 1",
        "[1, 2, 3][-1]                                 | 3",
        "[1, 2, 3][item >= 2]                          | [2, 3]",
        "[{a: 1}, {a: 2}][a > 1]                       | [{a: 2}]",
        "[{item: 1}, {item: 2}][item > 1]              | [{item: 2}]",
        "[{x: 1}, {x: null}][x < 2]                    | [{x: 1}]",
        "[1, 2][true]                                  | [1, 2]",
        "100[1]                                        | 100",
        "3[item > 2]                                   | [3]",
        "true[false]                                   | []",
        "[{a: 1}, {b: 2}].b                            | [null, 2]",
        "[1, 2].a                                      | [null, null]",
        "{a: 1 + 2, b: a * 2}.b                        | 6",
        "{a b: 1, c: a b + 1}.c                        | 2",
        "{\"a-b\": 1}                                   | {\"a-b\": 1}",
        "{a: {b: {c: 3}}}.a.b.c                        | 3",
        "@\"2025-01-31\".weekday                        | 5",
        "@\"2025-01-31T10:20:30+02:00\".time offset     | @\"PT2H\"",
        "@\"2025-01-31T10:00:00@Europe/Paris\".timezone | \"Europe/Paris\"",
        "@\"10:20:30-01:00\".time offset                | @\"-PT1H\"",
        "@\"2025-01-31T10:20:30\".time offset           | null",
        "@\"2025-01-31T10:20:30\".timezone              | null",
        "@\"10:20:30\".time offset                      | null",
        "@\"10:20:30Z\".timezone                        | null",
        "@\"P1Y14M\".years                              | 2",
        "@\"PT36H\".days                                | 1",
        "[1..10).end included                          | false",
        "(> 5).start                                   | 5",
    })
    void lists_contexts_paths_and_filters(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{a: 1}.b", "null.a", "@\"P1Y\".days", "{a: 1, a: 2}"})
    void a_missing_entry_or_property_is_null_with_an_error(String expression) {
        // the DMN TCK's 0057-feel-context and 0074-feel-properties
        assertNullWithAnError(expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1, 2, 3][0]", "[1, 2, 3][4]", "[1, 2, 3][-4]", "[][1]"})
    void an_index_beyond_a_list_is_null(String expression) {
        // the DMN TCK's 0069-feel-list (decision002, decision003, decision008): null, not an error result
        assertThat(value(expression)).isNull();
        assertThat(errors(expression)).isEmpty();
    }

    @Test
    void a_filter_of_an_empty_list_reports_no_error_of_its_condition() {
        // no element is tested: the condition's value with item null only tells an index from a test
        assertThat(errors("[][item > 1]")).isEmpty();
        assertFeel("[][item > 1]", "[]");
    }

    // ---- functions (10.3.2.13) ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "(function(a, b) a - b)(5, 3)                                  | 2",
        "(function(a, b) a - b)(b: 5, a: 3)                            | -2",
        "{add: function(a, b) a + b, r: add(1, 2)}.r                   | 3",
        "{y: 10, f: function(x) x + y, r: f(1)}.r                      | 11",
        "{f: function(n) if n = 0 then 1 else n * f(n - 1), r: f(5)}.r | 120",
        "(function(xs: list<number>) xs[1])(7)                         | 7",
        "(function(a: number) a)([7])                                  | 7",
        "(function(a, b) b)(b: 2)                                      | 2",
        "(function(d: date and time) d)(@\"2025-01-31\")                | @\"2025-01-31T00:00:00Z\"",
    })
    void functions_close_over_their_scope_and_take_positional_or_named_arguments(String expression, String expected) {
        // DMN 1.5, 10.3.2.13 and 10.3.2.9.4: a list of one is bound to a parameter of its element's type
        assertFeel(expression, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"(function(a) a)(1, 2)", "(function(a) a)(b: 1)", "1(2)", "(function(a: number) a)(\"x\")",
        "{f: function(n) f(n), r: f(1)}.r", "(function(a, b) a)(a: 1, a: 2)"})
    void an_invocation_feel_does_not_define_is_null_with_an_error(String expression) {
        assertNullWithAnError(expression);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "(function(x) x) = (function(x) x)                                | true",
        "{a: function(x) x + 1, b: function(x) x + 1, r: a = b}.r          | true",
        "(function(x) x) = (function(y) y)                                | false",
        "(function(x) x) = (function(x) x + 0)                            | false",
        "(function(x: number) x) = (function(x) x)                        | false",
        "(for i in [1, 2] return function(x) x)[1] = (for i in [1, 2] return function(x) x)[2] | false",
        "{f: function(x) x, g: f, r: f = g}.r                             | true",
    })
    void functions_are_equal_with_the_same_parameters_body_and_scope(String expression, String expected) {
        // DMN 1.5, Table 53: internal functions are equal when they have the "same parameters, body and scope"; each
        // iteration of a for is a scope of its own
        assertFeel(expression, expected);
    }

    @Test
    void a_built_in_function_parses_and_is_named_as_planned_for_0_9() {
        var expression = FeelExpression.parse("string length(Name) + count([1])", List.of("Name"));
        assertThat(expression.names()).containsExactly("Name");
        assertThat(expression.unsupported()).containsExactly(new FeelExpression.Unsupported("the built-in function 'string length'", "0.9", 117),
                new FeelExpression.Unsupported("the built-in function 'count'", "0.9", 117));
        assertThat(expression.unsupported().getFirst()).hasToString("the built-in function 'string length', planned for 0.9 (#117)");
        assertThat(expression.evaluate(name -> "abc")).isNull();
        assertThat(FeelExpression.parse("function(a) external {java: {class: \"x\"}}").unsupported())
                .containsExactly(new FeelExpression.Unsupported("an external function (Java or PMML)", "0.11", 119));
    }

    @Test
    void a_function_value_invoked_from_java_is_an_evaluation_of_its_own() {
        var function = (FeelFunction) value("function(x) x + \"a\"");
        var errors = new ArrayList<String>();
        assertThat(function.invoke(List.of("b"), errors::add)).isEqualTo("ba");
        assertThat(function.invoke(List.of(BigDecimal.ONE), errors::add)).isNull();
        assertThat(errors).singleElement().asString().contains("does not combine");
        assertThat(function.parameters()).containsExactly("x");
        assertThat(function.type()).isEqualTo(new FeelType.FunctionOf(List.of(FeelType.ANY), FeelType.ANY));
    }

    @Test
    void a_number_given_as_a_java_number_is_a_feel_number() {
        // a value given to an evaluation as an Integer, a Long or a Double is the decimal of its value
        var values = Map.<String, Object>of("i", 1, "l", 2L, "d", 2.5, "f", 0.5f, "s", (short) 3);
        for (var expression : List.of("i = 1", "i < 2", "i + l = 3", "d * 2 = 5", "f + f = 1", "i in [1..2]", "l between 1 and 3",
                "s in (1, 3)", "i instance of number", "[i][1] = 1", "(for k in i..l return k) = [1, 2]")) {
            assertThat(FeelExpression.parse(expression, values.keySet()).evaluate(values::get)).as(expression).isEqualTo(true);
        }
        assertThat(FeelExpression.parse("i", List.of("i")).evaluate(values::get)).isEqualTo(BigDecimal.ONE);
        // nested in a list, it compares and computes as one too
        var nested = Map.<String, Object>of("xs", List.of(1, 2L, 3.0));
        assertThat(FeelExpression.parse("xs[1] + xs[2] = xs[3] and xs[3] > 2 and xs = [1, 2, 3]", nested.keySet()).evaluate(nested::get))
                .isEqualTo(true);
        assertThat(FeelExpression.parse("d", List.of("d")).evaluate(Map.of("d", Double.NaN)::get, null)).isEqualTo(Double.NaN);
        assertThat(FeelExpression.parse("d + 1", List.of("d")).evaluate(Map.of("d", Double.NaN)::get, null)).isNull();
    }

    // ---- names (10.3.1.2) ----------------------------------------------------------------------------

    @Test
    void the_longest_name_in_scope_wins_whatever_it_holds() {
        var names = List.of("Date", "Date-Time", "Another Date and Time", "Another Date", "Time", "days in weekend", "a/b");
        var expression = FeelExpression.parse("Date-Time + Another Date and Time + (Date - Time) + a/b + days in weekend", names);
        assertThat(expression.names()).containsExactly("Date-Time", "Another Date and Time", "Date", "Time", "a/b", "days in weekend");
    }

    @Test
    void a_name_with_spaces_matches_any_whitespace_but_one_without_none() {
        var expression = FeelExpression.parse("Full\n  Name + Date - Time", List.of("Full Name", "Date-Time", "Date", "Time"));
        assertThat(expression.names()).containsExactly("Full Name", "Date", "Time");
    }

    @Test
    void a_name_out_of_scope_is_read_as_words_up_to_a_keyword() {
        assertThat(FeelExpression.parse("Credit Score > 10 and Monthly Income in [1..2]").names())
                .containsExactly("Credit Score", "Monthly Income");
    }

    @Test
    void names_bound_by_the_expression_and_those_a_filter_may_find_in_its_elements_are_not_read_from_outside() {
        var expression = FeelExpression.parse("for x in Xs return {y: x, z: y}[item.z > Limit and w = 1]", List.of("Xs", "Limit"));
        assertThat(expression.names()).containsExactly("Xs", "Limit");
    }

    @Test
    void the_names_given_are_read_from_the_values_given() {
        var values = new HashMap<String, Object>();
        values.put("Order Total", new BigDecimal("100"));
        values.put("Discount", null);
        var expression = FeelExpression.parse("if Discount = null then Order Total else Order Total - Discount", List.of("Order Total",
                "Discount"));
        assertThat(expression.evaluate(values::get)).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void a_scope_made_once_parses_many_expressions() {
        var scope = FeelExpression.scope(List.of("Applicant Age", "Limit"), Map.of());
        assertThat(FeelExpression.parse("Applicant Age < Limit", scope).names()).containsExactly("Applicant Age", "Limit");
        assertThat(FeelExpression.parse("Limit * 2", scope).names()).containsExactly("Limit");
    }

    // ---- types (10.3.2.9) ----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1 + 2                     | number",
        "[1, 2]                    | list<number>",
        "[1, \"a\"]                | list<Any>",
        "{a: 1, b: a > 0}          | context<a: number, b: boolean>",
        "for x in [1, 2] return x  | list<number>",
        "if x then 1 else null     | number",
        "x + 1                     | Any",
        "[1..2]                    | range<number>",
        "function(a: number) a > 1 | function<number>->boolean",
        "@\"2025-01-31\".year      | number",
        "\"a\" + 1                 | Null",
        "[1, 2][1]                 | number",
        "[1, 2][item > 1]          | list<number>",
    })
    void the_type_of_an_expression_follows_from_the_types_of_its_parts(String expression, String type) {
        assertThat(FeelExpression.parse(expression, List.of("x")).type(name -> null).feelName()).isEqualTo(type);
    }

    @Test
    void the_reason_an_expression_is_always_null_is_given() {
        var reasons = new ArrayList<String>();
        var type = FeelExpression.parse("Name * 2", List.of("Name")).type(name -> FeelType.of(ColumnType.STRING), reasons::add);
        assertThat(type).isEqualTo(FeelType.NULL);
        assertThat(reasons).containsExactly("* combines a string and a number, which gives null in FEEL");
    }

    // ---- syntax ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "1 +", "(1", "[1, 2", "{a 1}", "if 1 then 2", "for x return 1", "\"abc", "1 /* x", "1 ~ 2",
        "x instance of", "f(a: 1, 2)", "@1", "1e400000"})
    void what_is_not_feel_is_a_syntax_error(String expression) {
        assertThatThrownBy(() -> FeelExpression.parse(expression)).isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("is not a valid FEEL expression");
    }

    @Test
    void values_are_feels_and_parsing_is_once() {
        var expression = FeelExpression.parse("Start + duration(\"P1D\")", List.of("Start"));
        assertThat(expression.evaluate(Map.of("Start", LocalDate.of(2025, 1, 31))::get)).isEqualTo(LocalDate.of(2025, 2, 1));
        assertThat(expression.evaluate(Map.of("Start", LocalDateTime.of(2025, 1, 31, 10, 0))::get))
                .isEqualTo(LocalDateTime.of(2025, 2, 1, 10, 0));
        assertThat(expression.evaluate(Map.of("Start", Duration.ofHours(1))::get)).isEqualTo(Duration.ofHours(25));
        assertThat(expression.text()).hasToString("Start + duration(\"P1D\")").isEqualTo(expression.toString());
    }
}
