/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One evaluation takes a bounded amount of time and memory, whatever the expression (docs/DMN-CONFORMANCE.md, case 23):
 * at most {@link Env#MAX_STEPS} steps, which count iterations and the elements of their ranges, invocations, and the
 * elements filters, paths, {@code in}, equality and {@code instance of} go through; strings of at most
 * {@link Env#MAX_CHARACTERS} characters in all from {@code +}; and at most {@link Env#MAX_ERRORS} errors reported, each
 * quoting values shortly. Beyond them, the value is null, with an error.
 */
@Timeout(30)
class FeelLimitsTest {

    /** The value's kind and the first error, as the stack test gives them. */
    private static String outcome(String expression) {
        var errors = new ArrayList<String>();
        var value = FeelExpression.parse(expression).evaluate(name -> null, errors::add);
        return (value == null ? "null" : "value") + (errors.isEmpty() ? "" : ": " + errors.getFirst());
    }

    private static final String TOO_MANY_STEPS = "null: the evaluation takes more than 1000000 steps";

    @Test
    void an_evaluation_beyond_a_million_steps_gives_null_with_an_error() {
        assertThat(outcome("for i in 1..1000000000 return i")).isEqualTo("null: an iteration context's range has more than 1000000 elements");
        assertThat(outcome("for i in 1..2000, j in 1..2000 return i")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("every i in 1..2000, j in 1..2000 satisfies true")).isEqualTo(TOO_MANY_STEPS);
        // a function invoking itself twice over would take 2 ** 40 steps
        assertThat(outcome("{f: function(n) if n = 0 then 0 else f(n - 1) + f(n - 1), r: f(40)}.r")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("for x in 1..1000 return (for y in 1..1000 return y)[item > 0]")).isEqualTo(TOO_MANY_STEPS);
    }

    @Test
    void a_range_costs_a_step_an_element_and_holds_none_of_them() {
        // each some stops at its first element, but each range is a step an element: 2 x 999,999 is beyond the bound
        long start = System.nanoTime();
        assertThat(outcome("for k in 1..2 return some i in 1..999999 satisfies true")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("for k in 1..80 return some d in @\"2000-01-01\"..@\"4700-01-01\" satisfies true")).isEqualTo(TOO_MANY_STEPS);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(outcome("some i in 1..400000 satisfies i = 400000")).isEqualTo("value");
    }

    @Test
    void work_on_long_lists_counts_against_the_bound() {
        long start = System.nanoTime();
        // a list of 200,000 takes 400,000 steps to make, and as many as it has elements to compare, project, look through or type
        assertThat(outcome("{l: for i in 1..200000 return i, r: for k in 1..3 return l = l}.r")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("{l: for i in 1..200000 return {a: i}, r: for k in 1..10 return l.a}.r")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("{l: for i in 1..200000 return i, r: for k in 1..10 return 0 in l}.r")).isEqualTo(TOO_MANY_STEPS);
        assertThat(outcome("{l: for i in 1..200000 return i, r: for k in 1..10 return l instance of list<number>}.r"))
                .isEqualTo(TOO_MANY_STEPS);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void strings_plus_makes_hold_at_most_ten_million_characters_in_all() {
        var doubling = new StringBuilder("{s0: \"ab\"");
        for (int i = 1; i < 60; i++) {
            doubling.append(", s").append(i).append(": s").append(i - 1).append(" + s").append(i - 1);
        }
        assertThat(outcome(doubling.append("}.s59").toString()))
                .isEqualTo("null: the strings + makes would hold more than 10000000 characters");
        // each string is short, but together they are not
        var many = new StringBuilder("{s0: \"xxxxxxxx\"");
        for (int i = 1; i <= 20; i++) {
            many.append(", s").append(i).append(": s").append(i - 1).append(" + s").append(i - 1);
        }
        assertThat(outcome(many.append(", r: for i in 1..400 return s20 + \"y\"}.r").toString()))
                .isEqualTo("null: the strings + makes would hold more than 10000000 characters");
    }

    @Test
    void errors_quote_values_shortly_and_are_kept_to_a_hundred() {
        var errors = new ArrayList<String>();
        FeelExpression.parse("{s: \"" + "x".repeat(10_000) + "\", r: s + 1}.r").evaluate(name -> null, errors::add);
        assertThat(errors).singleElement().asString().hasSizeLessThan(200).contains("\"" + "x".repeat(64) + "…\"");

        var many = new ArrayList<String>();
        assertThat(FeelExpression.parse("for i in 1..1000 return \"a\" + i").evaluate(name -> null, many::add)).isNotNull();
        assertThat(many).hasSize(Env.MAX_ERRORS + 1).last().isEqualTo("and 900 more errors");
    }

    @Test
    void a_message_is_built_only_where_errors_are_reported() {
        // an evaluation without a listener builds no message: a million of them would otherwise cost time and memory
        long start = System.nanoTime();
        assertThat(FeelExpression.parse("for i in 1..400000 return \"a\" + i between i and \"b\"").evaluate(name -> null)).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void a_function_value_keeps_no_limit_of_the_evaluation_that_made_it() {
        // invoked from Java, each invocation is an evaluation of its own, with a million steps of its own
        var function = (FeelFunction) FeelExpression.parse("function(x) x + 1").evaluate(name -> null);
        var errors = new ArrayList<String>();
        int nulls = 0;
        for (int i = 0; i < 1_000_001; i++) {
            nulls += function.invoke(List.of(i), errors::add) == null ? 1 : 0;
        }
        assertThat(nulls).isZero();
        assertThat(errors).isEmpty();
    }
}
