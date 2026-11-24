/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Parsing and evaluating take a bounded amount of the thread's stack, whatever the input: an expression nests at most
 * {@link FeelParser#MAX_DEPTH} levels, or is a syntax error; functions invoke one another at most {@link Env#MAX_NESTING}
 * levels deep, and one evaluation takes at most {@link Env#MAX_STEPS} steps, or its value is null, with an error. Checked
 * on a thread of 512 KiB, half the default thread stack of Linux on x64, on which CI runs.
 */
class FeelExpressionStackTest {

    private static final int STACK = 512 * 1024;

    /** What an expression gives on a small stack: its value's text and the first error, or the exception it throws. */
    private static String onSmallStack(String expression) throws InterruptedException {
        var result = new AtomicReference<String>();
        var thread = new Thread(null, () -> {
            try {
                var errors = new ArrayList<String>();
                var parsed = FeelExpression.parse(expression);
                parsed.type(name -> null);
                var value = parsed.evaluate(name -> null, errors::add);
                result.set((value == null ? "null" : "value") + (errors.isEmpty() ? "" : ": " + errors.getFirst()));
            } catch (ExpressionSyntaxException e) {
                result.set("syntax: " + e.getMessage());
            } catch (StackOverflowError e) {
                result.set("stack overflow");
            }
        }, "small stack", STACK);
        thread.start();
        thread.join();
        return result.get();
    }

    @Test
    void nesting_up_to_the_limit_parses_and_evaluates() throws InterruptedException {
        int depth = FeelParser.MAX_DEPTH - 1;
        assertThat(onSmallStack("(".repeat(depth) + "1" + ")".repeat(depth))).isEqualTo("value");
        assertThat(onSmallStack("-".repeat(depth - 1) + "1")).isEqualTo("value");
        assertThat(onSmallStack("1" + "+1".repeat(depth - 1))).isEqualTo("value");
        assertThat(onSmallStack("[".repeat(depth - 1) + "1" + "]".repeat(depth - 1))).isEqualTo("value");
        assertThat(onSmallStack("if true then 1 else ".repeat(depth - 1) + "2")).isEqualTo("value");
        assertThat(onSmallStack("for x in [1] return ".repeat(depth / 4 - 1) + "1")).isEqualTo("value");
    }

    @Test
    void deeper_nesting_is_a_syntax_error_however_deep() throws InterruptedException {
        for (var deep : List.of("(".repeat(50_000) + "1" + ")".repeat(50_000), "-".repeat(50_000) + "1", "1" + "+1".repeat(50_000),
                "[".repeat(50_000) + "]".repeat(50_000), "{a:".repeat(50_000) + "1" + "}".repeat(50_000),
                "if true then 1 else ".repeat(50_000) + "2", "x" + "[1]".repeat(50_000), "x" + ".a".repeat(50_000),
                "function(x) ".repeat(50_000) + "1", "x instance of " + "list<".repeat(50_000) + "Any" + ">".repeat(50_000))) {
            assertThat(onSmallStack(deep)).startsWith("syntax: ").endsWith("it nests more than 300 levels");
        }
    }

    @Test
    void a_long_flat_expression_takes_no_stack() throws InterruptedException {
        // a list or a context of many entries, and many iteration contexts, are loops, not recursion
        assertThat(onSmallStack("[" + String.join(", ", Collections.nCopies(100_000, "1")) + "]")).isEqualTo("value");
        assertThat(onSmallStack("for " + String.join(", ", Collections.nCopies(5_000, "x in [1]")) + " return 1")).isEqualTo("value");
    }

    @Test
    void functions_that_invoke_themselves_without_end_give_null_with_an_error() throws InterruptedException {
        assertThat(onSmallStack("{f: function(n) 1 + f(n + 1), r: f(0)}.r"))
                .isEqualTo("null: functions invoke one another more deeply than Praecepta evaluates (800 levels)");
        // each invocation as deep as an expression may be, inside one as deep: still within the stack
        var body = "1 + (".repeat(140) + "f(n - 1)" + ")".repeat(140);
        var deepest = "{f: function(n) if n = 0 then 0 else " + body + ", r: " + "1 + (".repeat(140) + "f(100)" + ")".repeat(140) + "}.r";
        assertThat(onSmallStack(deepest)).startsWith("null: functions invoke one another");
        var iterating = "{f: function(n) if n = 0 then [] else " + "for x in [1] return ".repeat(70) + "f(n - 1), r: "
                + "for x in [1] return ".repeat(70) + "f(100)}.r";
        assertThat(onSmallStack(iterating)).startsWith("value: functions invoke one another");
    }

    @Test
    void an_evaluation_beyond_a_million_steps_gives_null_with_an_error() throws InterruptedException {
        assertThat(onSmallStack("for i in 1..1000000000 return i"))
                .isEqualTo("null: an iteration context's range has more than 1000000 elements");
        assertThat(onSmallStack("for i in 1..2000, j in 1..2000 return i")).isEqualTo("null: the evaluation takes more than 1000000 steps");
        assertThat(onSmallStack("every i in 1..2000, j in 1..2000 satisfies true"))
                .isEqualTo("null: the evaluation takes more than 1000000 steps");
        // a function invoking itself twice over would take 2 ** 40 steps
        assertThat(onSmallStack("{f: function(n) if n = 0 then 0 else f(n - 1) + f(n - 1), r: f(40)}.r"))
                .isEqualTo("null: the evaluation takes more than 1000000 steps");
        assertThat(onSmallStack("for x in 1..1000 return (for y in 1..1000 return y)[item > 0]"))
                .isEqualTo("null: the evaluation takes more than 1000000 steps");
    }

    @Test
    void a_string_that_would_double_its_length_beyond_bounds_is_null_with_an_error() throws InterruptedException {
        var doubling = new StringBuilder("{s0: \"ab\"");
        for (int i = 1; i < 60; i++) {
            doubling.append(", s").append(i).append(": s").append(i - 1).append(" + s").append(i - 1);
        }
        assertThat(onSmallStack(doubling.append("}.s59").toString()))
                .isEqualTo("null: the string + makes would be longer than 10000000 characters");
    }
}
