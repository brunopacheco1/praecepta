/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's function values (DMN 1.5, 10.3.2.13): a function definition's value keeps the names in scope where it was
 * defined, and nothing of the evaluation that defined it, so that it may be invoked from Java any number of times, from
 * any number of threads; and the built-in functions' shape, which 0.9 fills (#117).
 */
class FeelFunctionTest {

    private static FeelFunction function(String expression) {
        return (FeelFunction) FeelExpression.parse(expression).evaluate(name -> null);
    }

    @Test
    void a_function_value_reports_its_errors_to_whoever_invokes_it() {
        var function = function("function(x) x + 1");
        var errors = new ArrayList<String>();
        assertThat(function.invoke(List.of(BigDecimal.ONE), errors::add)).isEqualTo(BigDecimal.valueOf(2));
        assertThat(function.invoke(List.of("a"), errors::add)).isNull();
        assertThat(function.invoke(List.of(BigDecimal.ONE, BigDecimal.ONE), errors::add)).isNull();
        assertThat(errors).hasSize(2).last().asString().isEqualTo("the function takes 1 arguments, not 2");
        // ignoring them is fine too
        assertThat(function.invoke(List.of("a"), null)).isNull();
    }

    @Test
    void a_function_value_closes_over_the_names_in_scope_where_it_was_defined() {
        var function = (FeelFunction) FeelExpression.parse("{y: Base * 2, f: function(x) x + y}.f", List.of("Base"))
                .evaluate(name -> name.equals("Base") ? BigDecimal.TEN : null);
        assertThat(function.invoke(List.of(1), null)).isEqualTo(BigDecimal.valueOf(21));
    }

    @Test
    void a_recursive_function_value_invoked_from_many_threads_at_once_gives_each_its_value() throws InterruptedException {
        // each invocation from Java is an evaluation of its own: no count of steps or nesting is shared between them
        var function = function("{f: function(n) if n = 0 then 0 else 1 + f(n - 1)}.f");
        var pool = Executors.newFixedThreadPool(8);
        var wrong = new AtomicInteger();
        for (int i = 0; i < 2_000; i++) {
            pool.submit(() -> {
                if (!BigDecimal.valueOf(50).equals(function.invoke(List.of(BigDecimal.valueOf(50)), null))) {
                    wrong.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        assertThat(wrong).hasValue(0);
    }

    @Test
    void two_definitions_are_equal_with_the_same_parameters_body_and_scope() {
        // DMN 1.5, Table 53: "internal functions are equal if they have the same parameters, body and scope"
        var pair = (List<?>) FeelExpression.parse("[function(x) x + 1, function(x) x + 1, function(x) x + 2, function(y) y + 1]")
                .evaluate(name -> null);
        assertThat(pair.get(0)).isEqualTo(pair.get(1)).hasSameHashCodeAs(pair.get(1)).isNotEqualTo(pair.get(2)).isNotEqualTo(pair.get(3));
        // the same text, evaluated twice, is in two scopes
        assertThat(function("function(x) x + 1")).isNotEqualTo(function("function(x) x + 1"));
    }

    @Test
    void a_built_in_function_takes_a_fixed_number_of_arguments_some_optional_or_any_number_more() {
        assertThat(BuiltInFunction.Arity.fixed(2).accepts(2)).isTrue();
        assertThat(BuiltInFunction.Arity.fixed(2).accepts(1)).isFalse();
        assertThat(BuiltInFunction.Arity.optional(2, 1).accepts(3)).isTrue();
        assertThat(BuiltInFunction.Arity.optional(2, 1).accepts(4)).isFalse();
        assertThat(BuiltInFunction.Arity.variadic(1).accepts(100)).isTrue();
        assertThat(BuiltInFunction.Arity.variadic(1).accepts(0)).isFalse();
        assertThat(List.of(BuiltInFunction.Arity.fixed(2), BuiltInFunction.Arity.optional(2, 1), BuiltInFunction.Arity.variadic(1)))
                .map(Object::toString).containsExactly("2", "2 to 3", "1 or more");
        assertThatThrownBy(() -> new BuiltInFunction.Arity(1, 1, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_built_in_function_is_given_the_evaluation_that_invokes_it() {
        // substring(string, start position, length?), as 0.9 will have it: an optional parameter
        var substring = new BuiltInFunction("substring", List.of("string", "start position", "length"), BuiltInFunction.Arity.optional(2, 1),
                FeelType.ANY, (arguments, env) -> {
                    if (!(arguments.get(0) instanceof String text) || !(arguments.get(1) instanceof BigDecimal start)) {
                        return env.error("substring takes a string and a number");
                    }
                    int from = start.intValue() - 1;
                    return arguments.size() > 2 ? text.substring(from, from + ((BigDecimal) arguments.get(2)).intValue()) : text.substring(from);
                });
        var errors = new ArrayList<String>();
        assertThat(substring.invoke(List.of("foobar", 4), errors::add)).isEqualTo("bar");
        assertThat(substring.invoke(List.of("foobar", 2, 3), errors::add)).isEqualTo("oob");
        assertThat(substring.invoke(List.of("foobar"), errors::add)).isNull();
        assertThat(substring.invoke(Arrays.asList(null, 1), errors::add)).isNull();
        assertThat(errors).containsExactly("substring takes 2 to 3 arguments, not 1", "substring takes a string and a number");
        assertThat(substring).hasToString("substring").isNotEqualTo(function("function(string, start position) string"));
        assertThat(substring.parameters()).containsExactly("string", "start position", "length");

        // invoked inside an evaluation, it counts against that evaluation, and reports to it
        var env = Env.root(name -> null, errors::add, 1);
        assertThat(Node.Invocation.call(substring, List.of("foobar", BigDecimal.ONE), env)).isEqualTo("foobar");
        assertThatThrownBy(() -> new BuiltInFunction("f", List.of(), BuiltInFunction.Arity.fixed(1), FeelType.of(ColumnType.STRING),
                (arguments, e) -> null)).isInstanceOf(IllegalArgumentException.class);
    }
}
