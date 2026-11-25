/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's built-in functions (DMN 1.5, 10.3.4), since 0.9 (#117): each family's values, named arguments and the
 * signatures they pick, the implicit conversions to and from a list of one, the errors that make an invocation null, and
 * the evaluation's limits, which a function's work counts against.
 */
class BuiltInFunctionsTest {

    private static Object value(String expression) {
        return FeelExpression.parse(expression).evaluate(name -> null);
    }

    private static List<String> errors(String expression) {
        var errors = new ArrayList<String>();
        FeelExpression.parse(expression).evaluate(name -> null, errors::add);
        return errors;
    }

    /** Whether the expression's value equals the expected literal's, by FEEL's equality; the literal must have a value. */
    private static void assertFeel(String expression, String expected) {
        var actual = value(expression);
        var wanted = value(expected);
        assertThat(wanted).as("the expected value %s", expected).isNotNull();
        assertThat(FeelValues.equality(actual, wanted)).as("%s is %s, not %s", expression, actual, expected).isEqualTo(Boolean.TRUE);
        assertThat(errors(expression)).as("the errors of %s", expression).isEmpty();
    }

    @Test
    void every_function_dmn_names_is_there() {
        for (var name : BuiltInFunctions.NAMES) {
            assertThat(BuiltInFunctions.lookup(name)).as(name).isNotNull();
            assertThat(BuiltInFunctions.lookup(name)).hasToString(name);
        }
        assertThat(BuiltInFunctions.lookup("no such function")).isNull();
    }

    // ---- conversion (10.3.4.1), boolean (10.3.4.2), date and time (10.3.4.6) ---------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "date(\"2025-01-31\")                                   | @\"2025-01-31\"",
        "date(date and time(\"2025-01-31T10:00:00\"))           | @\"2025-01-31\"",
        "date(year: 2025, month: 1, day: 31)                    | @\"2025-01-31\"",
        "date(2025, 1, 31)                                      | @\"2025-01-31\"",
        "date and time(date: @\"2025-01-31\", time: @\"10:00:00\") | @\"2025-01-31T10:00:00\"",
        "time(hour: 10, minute: 30, second: 0)                  | @\"10:30:00\"",
        "time(10, 30, 0, duration(\"PT1H\"))                    | @\"10:30:00+01:00\"",
        "time(10, 30, 0, null)                                  | @\"10:30:00\"",
        "duration(\"P1Y2M\")                                    | @\"P14M\"",
        "years and months duration(@\"2014-12-31T23:59:59\", @\"2019-10-01T12:32:59\") | @\"P4Y9M\"",
        "years and months duration(@\"2025-03-01\", @\"2024-01-31\") | -@\"P1Y1M\"",
        "number(\"1 000,5\", \" \", \",\")                      | 1000.5",
        "number(\"1,000.5\", \",\", \".\")                      | 1000.5",
        "number(\"-1.5e3\")                                     | -1500",
        "number(from: \"12\", decimal separator: \".\")         | 12",
        "string(1.10)                                           | \"1.10\"",
        "string(@\"2025-01-31T08:30:00@Europe/Paris\")         | \"2025-01-31T08:30:00@Europe/Paris\"",
        "string(@\"P1DT2H\")                                    | \"P1DT2H\"",
        "string([1, \"a\", null])                               | \"[1, \\\"a\\\", null]\"",
        "string({a: [true]})                                    | \"{a: [true]}\"",
        "string(\"a\")                                          | \"a\"",
        "range(\"[18..21)\")                                    | [18..21)",
        "range(\" ]18..21] \")                                  | ]18..21]",
        "range(\"[date(\\\"2025-01-01\\\")..@\\\"2025-12-31\\\"]\") | [@\"2025-01-01\"..@\"2025-12-31\"]",
        "range(\"[-5..-1]\")                                    | [-5..-1]",
        "not(false)                                             | true",
        "is(1, 1.0)                                             | true",
        "is(@\"10:00:00+01:00\", @\"09:00:00Z\")                | false",
        "is(@\"2025-01-31\", @\"2025-01-31T00:00:00\")          | false",
        "is(null, null)                                         | true",
        "is([1, 2], [1, 2])                                     | true",
        "day of year(@\"2025-12-31\")                           | 365",
        "day of week(@\"2025-01-31T10:00:00\")                  | \"Friday\"",
        "month of year(@\"2025-01-31\")                         | \"January\"",
        "week of year(@\"2021-01-03\")                          | 53",
        "week of year(@\"2025-01-31\")                          | 5",
    })
    void conversion_boolean_and_date_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @Test
    void now_and_today_are_the_current_date_and_time() {
        var before = ZonedDateTime.now().minusSeconds(1);
        assertThat(value("now()")).isInstanceOfSatisfying(ZonedDateTime.class, now -> assertThat(now).isAfter(before));
        assertThat(value("today()")).isInstanceOfSatisfying(LocalDate.class,
                today -> assertThat(today).isBetween(LocalDate.now().minusDays(1), LocalDate.now()));
    }

    // ---- strings (10.3.4.3) -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "substring(\"foobar\", 3)                               | \"obar\"",
        "substring(\"foobar\", 3, 3)                            | \"oba\"",
        "substring(\"foobar\", -2, 1)                           | \"a\"",
        "substring(\"foobar\", 3, 3.8)                          | \"oba\"",
        "substring(\"foobar\", 5, 10)                           | \"ar\"",
        "substring(string: \"foobar\", start position: 2)       | \"oobar\"",
        "substring(\"\\U01F40Eab\", 2)                          | \"ab\"",
        "string length(\"\\U01F40Eab\")                         | 3",
        "string length([\"abc\"])                               | 3",
        "upper case(\"aBc4\")                                   | \"ABC4\"",
        "lower case(\"aBc4\")                                   | \"abc4\"",
        "substring before(\"foobar\", \"bar\")                  | \"foo\"",
        "substring before(\"foobar\", \"xyz\")                  | \"\"",
        "substring after(\"foobar\", \"ob\")                    | \"ar\"",
        "substring after(\"\", \"a\")                           | \"\"",
        "`replace(\"abcd\", \"(ab)|(a)\", \"[1=$1][2=$2]\")`    | \"[1=ab][2=]cd\"",
        "replace(\"a.b.c\", \".\", \"-\", \"q\")                | \"a-b-c\"",
        "replace(\"Abc\", \"a\", \"x\", \"i\")                  | \"xbc\"",
        "contains(\"foobar\", \"of\")                           | false",
        "starts with(\"foobar\", \"fo\")                        | true",
        "ends with(\"foobar\", \"r\")                           | true",
        "matches(\"foobar\", \"^fo*b\")                         | true",
        "matches(\"hello world\", \"hello\\ sworld\", \"x\")    | true",
        "matches(\"hello\", \"\\p{IsBasicLatin}+\")             | true",
        "matches(\"a-z\", \"[a-z-[aeiou]]-\")                   | false",
        "matches(\"b-z\", \"^[a-z-[aeiou]]-\")                  | true",
        "split(\"John Doe\", \"\\s\")                           | [\"John\", \"Doe\"]",
        "split(\"a;b;;\", \";\")                                | [\"a\", \"b\", \"\", \"\"]",
        "string join([\"a\", null, \"c\"], \", \")              | \"a, c\"",
        "string join([\"a\", \"b\"])                            | \"ab\"",
        "string join([], \"x\")                                 | \"\"",
    })
    void string_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    // ---- lists (10.3.4.4) and sort (10.3.4.9) -------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "list contains([1, null], null)                         | true",
        "list contains([1, 2], 2.0)                             | true",
        "count([1, [2, 3]])                                     | 2",
        "min([3, 1, 2])                                         | 1",
        "min(3, 1, 2)                                           | 1",
        "max(\"a\", \"c\", \"b\")                               | \"c\"",
        "max(@\"2025-01-01\", @\"2025-02-01\")                  | @\"2025-02-01\"",
        "sum([1, 2.5])                                          | 3.5",
        "sum(1, 2, 3)                                           | 6",
        "mean([1, 2, 3, 4])                                     | 2.5",
        "all([true, true])                                      | true",
        "all([])                                                | true",
        "all([true, false, null])                               | false",
        "any([false, true, 1])                                  | true",
        "any([])                                                | false",
        "sublist([1, 2, 3], 2)                                  | [2, 3]",
        "sublist([1, 2, 3], -2, 1)                              | [2]",
        "append([1], 2, 3)                                      | [1, 2, 3]",
        "append([1], null)                                      | [1, null]",
        "concatenate([1], [], [2, 3])                           | [1, 2, 3]",
        "insert before([1, 3], 2, 2)                            | [1, 2, 3]",
        "insert before([1, 2], -1, 0)                           | [1, 0, 2]",
        "remove([1, 2, 3], 2)                                   | [1, 3]",
        "reverse([1, 2, 3])                                     | [3, 2, 1]",
        "index of([1, 2, 1], 1)                                 | [1, 3]",
        "union([1, 2], [2, 3], [1.0])                           | [1, 2, 3]",
        "distinct values([1, 2, 1, [1], [1]])                   | [1, 2, [1]]",
        "flatten([[1, [2]], [], 3])                             | [1, 2, 3]",
        "product([2, 3, 4])                                     | 24",
        "median([8, 2, 5, 3, 4])                                | 4",
        "median([6, 1, 2, 3])                                   | 2.5",
        "stddev([2, 4, 7, 5])                                   | 2.081665999466132735282297706979931",
        "mode([6, 3, 9, 6, 6])                                  | [6]",
        "mode([6, 1, 9, 6, 1])                                  | [1, 6]",
        "mode([])                                               | []",
        "list replace([1, 2, 3], 2, 4)                          | [1, 4, 3]",
        "list replace([1, 2, 3], -1.5, 4)                       | [1, 2, 4]",
        "list replace([2, 4, 7], function(item, newItem) item < newItem, 5) | [5, 5, 7]",
        "list replace(list: [1, 2], match: function(a, b) a = 1, newItem: 0) | [0, 2]",
        "sort([3, 1, 2])                                        | [1, 2, 3]",
        "sort([3, 1, 2], function(x, y) x > y)                  | [3, 2, 1]",
        "sort(list: [{n: 2, k: \"a\"}, {n: 1, k: \"b\"}, {n: 2, k: \"c\"}], precedes: function(x, y) x.n < y.n).k | [\"b\", \"a\", \"c\"]",
    })
    void list_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @Test
    void functions_of_no_values_have_their_values_or_none() {
        assertThat(value("min([])")).isNull();
        assertThat(value("sum([])")).isNull();
        assertThat(value("median([])")).isNull();
        assertThat(errors("min([])")).isEmpty();
    }

    // ---- numbers (10.3.4.5) -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "decimal(1/3, 2)                                        | 0.33",
        "decimal(1/3, 2.5)                                      | 0.33",
        "decimal(1.5, 0)                                        | 2",
        "decimal(2.5, 0)                                        | 2",
        "decimal(1234, -2)                                      | 1200",
        "floor(-1.5)                                            | -2",
        "floor(1.56, 1)                                         | 1.5",
        "ceiling(-1.5)                                          | -1",
        "ceiling(1.51, 1)                                       | 1.6",
        "round up(-5.5, 0)                                      | -6",
        "round down(5.5, 0)                                     | 5",
        "round half up(-5.5, 0)                                 | -6",
        "round half down(-5.5, 0)                               | -5",
        "round half up(1.121, 2)                                | 1.12",
        "abs(-10)                                               | 10",
        "abs(@\"-P1D\")                                         | @\"P1D\"",
        "abs(@\"-P1Y\")                                         | @\"P1Y\"",
        "modulo(12, 5)                                          | 2",
        "modulo(-12, 5)                                         | 3",
        "modulo(12, -5)                                         | -3",
        "modulo(10.1, 4.5)                                      | 1.1",
        "modulo(-10.1, 4.5)                                     | 3.4",
        "sqrt(16)                                               | 4",
        "sqrt(2)                                                | 1.414213562373095048801688724209698",
        "log(10)                                                | 2.302585092994045684017991454684364",
        "exp(1)                                                 | 2.718281828459045235360287471352662",
        "exp(0)                                                 | 1",
        "odd(5)                                                 | true",
        "odd(-2)                                                | false",
        "even(0)                                                | true",
    })
    void numeric_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    // ---- ranges (10.3.4.7) --------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "before(1, 10)                          | true",
        "before(10, 1)                          | false",
        "before(1, [2..5])                      | true",
        "before(1, (1..5])                      | true",
        "before(1, [1..5])                      | false",
        "before([1..5], 10)                     | true",
        "before([1..5), 5)                      | true",
        "before([1..5], [6..8])                 | true",
        "before([1..5), [5..8])                 | true",
        "before([1..5], [5..8])                 | false",
        "before(range: [1..5], point: 10)       | true",
        "before(point: 10, range: [1..5])       | false",
        "after(10, 1)                           | true",
        "after([6..8], [1..5])                  | true",
        "after(12, [2..5])                      | true",
        "meets([1..5], [5..10])                 | true",
        "meets([1..5), [5..10])                 | false",
        "met by([5..10], [1..5])                | true",
        "overlaps([1..5], [3..8])               | true",
        "overlaps([1..5], [6..8])               | false",
        "overlaps([1..5], [5..8])               | true",
        "overlaps before([1..5], [3..8])        | true",
        "overlaps after([3..8], [1..5])         | true",
        "finishes(5, [1..5])                    | true",
        "finishes([3..5], [1..5])               | true",
        "finished by([1..5], [3..5])            | true",
        "includes([1..10], 5)                   | true",
        "includes([1..10], [4..6])              | true",
        "includes([1..10), 10)                  | false",
        "during(5, [1..10])                     | true",
        "during([4..6], [1..10])                | true",
        "starts(1, [1..5])                      | true",
        "starts([1..3], [1..5])                 | true",
        "started by([1..5], [1..3])             | true",
        "coincides(5, 5)                        | true",
        "coincides([1..5], [1..5])              | true",
        "coincides([1..5], (1..5])              | false",
        "before(1, > 5)                         | true",
        "includes(< 10, 5)                      | true",
        "includes(>= 3, [4..6])                 | true",
        "after(< 10, 5)                         | false",
    })
    void range_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    // ---- contexts (10.3.4.10) -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "get value({a: 1}, \"a\")                               | 1",
        "get value({a: 1}, \"b\") = null                        | true",
        "get entries({a: 1, b: 2})                              | [{key: \"a\", value: 1}, {key: \"b\", value: 2}]",
        "context([{key: \"a\", value: 1}, {key: \"b\", value: null}]) | {a: 1, b: null}",
        "context put({x: 1}, \"y\", 2)                          | {x: 1, y: 2}",
        "context put({x: 1, y: 2}, \"x\", 3)                    | {x: 3, y: 2}",
        "context put({x: 1, y: {a: 0}}, [\"y\", \"a\"], 2)      | {x: 1, y: {a: 2}}",
        "context put(context: {x: 1}, keys: [\"y\", \"z\"], value: 2) | {x: 1, y: {z: 2}}",
        "context merge([{x: 1, y: 0}, {y: 2}])                  | {x: 1, y: 2}",
    })
    void context_functions(String expression, String expected) {
        assertFeel(expression, expected);
    }

    @Test
    void context_put_keeps_the_entries_in_order() {
        assertThat(List.<Object>copyOf(((Map<?, ?>) value("context put({b: 1, a: 2}, \"b\", 3)")).keySet())).containsExactly("b", "a");
        assertThat(List.<Object>copyOf(((Map<?, ?>) value("context merge([{b: 1}, {a: 2}, {b: 3}])")).keySet())).containsExactly("b", "a");
    }

    // ---- what gives null, with an error ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        // arguments a function does not take
        "string length(1)", "string length()", "string length(\"a\", \"b\")", "string length(string: \"a\", other: 1)",
        "substring(\"abc\", 0)", "substring(\"abc\", 5)", "substring(\"abc\", 1, -1)", "substring(null, 1)",
        "upper case(null)", "date(\"2025-02-30\")", "date(2025, 13, 1)", "date(x)", "time(\"25:00:00\")",
        "number(\"1,000.5\", \",\", \",\")", "number(\"abc\")", "number(\"1.5\", null, \",\")", "number(\"1\", \";\")",
        "range(\"[1..]\")", "range(\">= 10\")", "range(\"[3..1]\")", "range(\"[1..\\\"b\\\"]\")",
        "range(\"[date(string(\\\"2025-01-01\\\"))..@\\\"2025-12-31\\\"]\")", "range(\"\")", "not(1)",
        "context put(context: {x: 1, y: {a: 0}}, key: [\"y\", \"a\"], value: 2)",
        "day of week(1)", "years and months duration(@\"2025-01-01\", 1)",
        // regular expressions XPath does not take
        "matches(\"a\", \"(\")", "matches(\"a\", \"a\", \"g\")", "matches(\"h\", \"(.)\\2\")", "matches(\"h\", \"\\3\")",
        "matches(\"abcd\", \"(a)[\\1]\")", "replace(\"abc\", \"x*\", \"y\")", "replace(\"abc\", \"b\", \"$\")",
        "replace(\"abc\", \"b\", \"\\x\")", "split(\"abc\", \"\")",
        // lists
        "sublist([1, 2], 3)", "sublist([1, 2], 1, 5)", "insert before([1], 3, 0)", "remove([1], 0)", "min([1, \"a\"])",
        "max(null)", "sum([1, \"a\"])", "mean([])", "all([true, 1])", "any([false, null])", "product([])",
        "stddev([1])", "string join([1], \",\")", "sort([1, \"a\"])", "sort([1, 2], function(x, y) 1)",
        "list replace([1], function(x, y) 1, 0)", "count(null)", "context([{key: \"a\", value: 1}, {key: \"a\", value: 2}])",
        "context([{key: 1, value: 1}])", "context([{key: \"a\"}])", "get value(1, \"a\")",
        // numbers
        "decimal(1, 6177)", "round up(1.5)", "modulo(1, 0)", "sqrt(-1)", "log(0)", "log(-1)", "exp(20000)", "odd(1.5)",
        "abs(\"a\")",
        // ranges
        "before([1..5], \"a\")", "meets(1, [1..5])", "includes(= 5, 5)", "before(1, null)",
    })
    void an_invocation_of_arguments_a_function_does_not_take_is_null_with_an_error(String expression) {
        assertThat(value(expression)).as(expression).isNull();
        assertThat(errors(expression)).as(expression).isNotEmpty();
    }

    @Test
    void an_error_names_the_function_the_parameter_and_what_it_was_given() {
        assertThat(errors("substring(\"abc\", \"x\")"))
                .containsExactly("substring(...) takes a number as its 'start position', not \"x\"");
        assertThat(errors("string length(1, 2)")).containsExactly("string length takes 1 arguments, not 2");
        assertThat(errors("string length(text: \"a\")")).containsExactly("the function has no parameter 'text'");
    }

    // ---- named arguments and signatures ---------------------------------------------------------------

    @Test
    void a_named_invocation_binds_by_the_signature_that_names_its_arguments() {
        var date = (BuiltInFunction) BuiltInFunctions.lookup("date");
        assertThat(date.parameters()).containsExactly("from");
        assertThat(date.signature(date.signatureNaming(List.of("day", "year")))).containsExactly("year", "month", "day");
        var before = (BuiltInFunction) BuiltInFunctions.lookup("before");
        // both before(point, range) and before(range, point) name a point and a range: the order of the names picks one
        assertThat(before.signature(before.signatureNaming(List.of("range", "point")))).containsExactly("range", "point");
        assertThat(before.signature(before.signatureNaming(List.of("point", "range")))).containsExactly("point", "range");
        assertThat(before.signatureNaming(List.of("nothing"))).isZero();
        // an argument a signature names but the invocation does not is null
        assertThat(value("number(from: \"1.5\")")).isEqualTo(new BigDecimal("1.5"));
        assertThat(value("substring(string: \"abc\")")).isNull();
    }

    @Test
    void a_function_typed_by_its_value() {
        assertThat(FeelExpression.parse("upper case(x)", List.of("x")).type(name -> FeelType.of(ColumnType.STRING)))
                .isEqualTo(FeelType.of(ColumnType.STRING));
        assertThat(BuiltInFunctions.lookup("count").type()).isEqualTo(new FeelType.FunctionOf(List.of(FeelType.ANY),
                FeelType.of(ColumnType.NUMBER)));
    }

    // ---- limits ---------------------------------------------------------------------------------------

    @Test
    void a_regular_expression_that_backtracks_without_end_stops_at_the_evaluation_s_limits() {
        // Java's regular expressions take ages over this one: 6.6 seconds for 30 characters
        var expression = "matches(\"" + "a".repeat(30) + "!\", \"(.*a){12}!x\")";
        long start = System.nanoTime();
        assertThat(value(expression)).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(errors(expression)).containsExactly("the evaluation takes more than 1000000 steps");
    }

    @Test
    void a_regular_expression_that_nests_beyond_the_stack_is_null_with_an_error() {
        // Java's regular expressions recurse for each repetition of an alternation: (a|b)* over 100,000 characters
        var text = "{s: string join(for i in 1..50000 return \"ab\")}.s";
        for (var expression : List.of("matches(" + text + ", \"^(a|b)*$\")", "replace(" + text + ", \"(a|b)+\", \"x\")",
                "split(" + text + ", \"(a|b)+c\")")) {
            assertThat(value(expression)).as(expression).isNull();
            assertThat(errors(expression)).as(expression).anyMatch(error -> error.contains("deeper than the thread's stack"));
        }
    }

    @Test
    void a_function_s_work_counts_against_the_evaluation_s_steps() {
        assertThat(value("count(sort(for i in 1..20000 return -i))")).isEqualTo(BigDecimal.valueOf(20_000));
        // each comparison of a sort invokes its function, a step each
        assertThat(value("sort(for i in 1..200000 return i, function(x, y) x > y)")).isNull();
        assertThat(errors("sort(for i in 1..200000 return i, function(x, y) x > y)"))
                .containsExactly("the evaluation takes more than 1000000 steps");
    }

    @Test
    void the_strings_a_function_makes_count_against_the_evaluation_s_characters() {
        // ten thousand characters, each replaced by the ten thousand: a hundred million characters
        var expression = "{s: string join(for i in 1..1000 return \"aaaaaaaaaa\"), r: replace(s, \"a\", s)}.r";
        assertThat(value(expression)).isNull();
        assertThat(errors(expression)).anyMatch(error -> error.startsWith("the strings + makes would hold more than"));
    }
}
