/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parsing, typing and evaluating take a bounded amount of the thread's stack, whatever the input: an expression's tree
 * nests at most {@link FeelParser#MAX_DEPTH} levels and its text at most {@link FeelParser#MAX_CALLS} of the parser's
 * calls, or it is a syntax error; functions invoke one another at most {@link Env#MAX_NESTING} levels deep, or the value is
 * null, with an error. Every construct, nested as deeply as the parser accepts, is checked on a thread of 448 KiB: 512 KiB,
 * half the default thread stack of Linux on x64, on which CI runs, less a margin of an eighth.
 *
 * <p>The build runs this class on its own, in an interpreted JVM ({@code -Xint}, the {@code feel-stack} execution of
 * surefire), so that the frames it measures are those a method has before the JIT compiles it, whichever tests ran
 * before and however warm the JIT is; in any other JVM it is skipped.
 */
@EnabledIf("interpreted")
class FeelExpressionStackTest {

    /** Whether the JVM interprets every method ({@code -Xint}), as the {@code feel-stack} execution runs it. */
    static boolean interpreted() {
        return System.getProperty("java.vm.info", "").contains("interpreted mode");
    }

    private static final int STACK = 448 * 1024;
    private static final int LARGE_STACK = 64 * 1024 * 1024;

    /** Runs a task on a thread of that stack, giving its result, or rethrowing what it throws. */
    private static <T> T onStack(long stack, Callable<T> task) throws Exception {
        var result = new AtomicReference<T>();
        var thrown = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(task.call());
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "stack of " + stack, stack);
        thread.start();
        thread.join();
        if (thrown.get() instanceof Exception e) {
            throw e;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    /**
     * What an expression gives on a small stack: its value's kind and the first error, {@code syntax: ...} for a syntax
     * error, or {@code stack overflow}; any other exception is rethrown.
     */
    private static String onSmallStack(String expression) throws Exception {
        return onStack(STACK, () -> {
            try {
                var errors = new ArrayList<String>();
                var parsed = FeelExpression.parse(expression);
                parsed.type(name -> null);
                parsed.literals();
                var value = parsed.evaluate(name -> null, errors::add);
                return (value == null ? "null" : "value") + (errors.isEmpty() ? "" : ": " + errors.getFirst());
            } catch (ExpressionSyntaxException e) {
                return "syntax: " + e.getMessage();
            } catch (StackOverflowError e) {
                return "stack overflow";
            }
        });
    }

    /** Whether the parser accepts an expression, checked on a stack large enough for any. */
    private static boolean parses(String expression) throws Exception {
        return onStack(LARGE_STACK, () -> {
            try {
                FeelExpression.parse(expression);
                return true;
            } catch (ExpressionSyntaxException e) {
                return false;
            }
        });
    }

    /** The deepest nesting of a construct the parser accepts: a search between 1 and 4,000 levels. */
    private static int deepestAccepted(IntFunction<String> nested) throws Exception {
        int low = 1;
        int high = 4_000;
        assertThat(parses(nested.apply(low))).as("one level parses").isTrue();
        assertThat(parses(nested.apply(high))).as("4,000 levels are too deep").isFalse();
        while (high - low > 1) {
            int middle = (low + high) / 2;
            if (parses(nested.apply(middle))) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Each construct nested n levels, and the deepest it nests: by the parser's calls ({@link FeelParser#MAX_CALLS},
     * seven for a level of parentheses, eight for an {@code if}, nine for a {@code for}...) or by the tree's depth
     * ({@link FeelParser#MAX_DEPTH}, two for a level of contexts, filters, invocations and {@code in}, four for one of
     * {@code for}, {@code some} and {@code every}), whichever is less.
     */
    static Stream<Arguments> constructs() {
        return Stream.of(
                Arguments.of("parentheses", (IntFunction<String>) n -> "(".repeat(n) + "1" + ")".repeat(n), 250),
                Arguments.of("lists", (IntFunction<String>) n -> "[".repeat(n) + "1" + "]".repeat(n), 250),
                Arguments.of("contexts", (IntFunction<String>) n -> "{a: ".repeat(n) + "1" + "}".repeat(n), 149),
                Arguments.of("ranges", (IntFunction<String>) n -> "[".repeat(n) + "1" + "..1]".repeat(n), 250),
                Arguments.of("minus", (IntFunction<String>) n -> "-".repeat(n) + "x", 299),
                Arguments.of("sums", (IntFunction<String>) n -> "1 + (".repeat(n) + "1" + ")".repeat(n), 194),
                Arguments.of("if, in its else", (IntFunction<String>) n -> "if true then 1 else ".repeat(n) + "2", 218),
                Arguments.of("if, in its condition", (IntFunction<String>) n -> "if ".repeat(n) + "true" + " then 1 else 2".repeat(n), 218),
                Arguments.of("for, in its body", (IntFunction<String>) n -> "for x in [1] return ".repeat(n) + "1", 74),
                Arguments.of("for, in its list", (IntFunction<String>) n -> "for x in ".repeat(n) + "[1]" + " return x".repeat(n), 74),
                Arguments.of("some", (IntFunction<String>) n -> "some x in [true] satisfies ".repeat(n) + "true", 74),
                Arguments.of("every", (IntFunction<String>) n -> "every x in [true] satisfies ".repeat(n) + "true", 74),
                Arguments.of("function", (IntFunction<String>) n -> "function(x) ".repeat(n) + "1", 218),
                Arguments.of("filters", (IntFunction<String>) n -> "[1][".repeat(n) + "1" + "]".repeat(n), 149),
                Arguments.of("invocations", (IntFunction<String>) n -> "f(".repeat(n) + "1" + ")".repeat(n), 149),
                Arguments.of("named arguments", (IntFunction<String>) n -> "f(a: ".repeat(n) + "1" + ")".repeat(n), 149),
                Arguments.of("built-in functions", (IntFunction<String>) n -> "count(".repeat(n) + "1" + ")".repeat(n), 250),
                Arguments.of("in", (IntFunction<String>) n -> "1 in (".repeat(n) + "1" + ")".repeat(n), 149),
                Arguments.of("between", (IntFunction<String>) n -> "1 between (".repeat(n) + "1" + ") and 2".repeat(n), 175),
                Arguments.of("unary ranges", (IntFunction<String>) n -> "< ".repeat(n) + "1", 291),
                Arguments.of("list types", (IntFunction<String>) n -> "[] instance of " + "list<".repeat(n) + "Any" + ">".repeat(n), 582),
                Arguments.of("context types", (IntFunction<String>) n -> "{} instance of " + "context<a: ".repeat(n) + "Any" + ">".repeat(n),
                        582),
                Arguments.of("function types", (IntFunction<String>) n -> "1 instance of " + "function<".repeat(n) + "Any" + ">->Any".repeat(n),
                        582));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructs")
    void each_construct_nested_as_deeply_as_it_parses_takes_a_bounded_stack(String construct, IntFunction<String> nested, int deepest)
            throws Exception {
        assertThat(deepestAccepted(nested)).as("the deepest %s parse", construct).isEqualTo(deepest);
        assertThat(onSmallStack(nested.apply(deepest))).as(construct).doesNotStartWith("syntax").isNotEqualTo("stack overflow");
        assertThat(onSmallStack(nested.apply(deepest + 1))).as(construct).endsWith("it nests more than 300 levels");
    }

    @Test
    void deeper_nesting_is_a_syntax_error_however_deep() throws Exception {
        for (var deep : List.of("(".repeat(50_000) + "1" + ")".repeat(50_000), "-".repeat(50_000) + "1", "1" + "+1".repeat(50_000),
                "[".repeat(50_000) + "]".repeat(50_000), "{a:".repeat(50_000) + "1" + "}".repeat(50_000),
                "if true then 1 else ".repeat(50_000) + "2", "x" + "[1]".repeat(50_000), "x" + ".a".repeat(50_000),
                "function(x) ".repeat(50_000) + "1", "x instance of " + "list<".repeat(50_000) + "Any" + ">".repeat(50_000),
                "x[".repeat(50_000) + "1" + "]".repeat(50_000), "f(".repeat(50_000) + "1" + ")".repeat(50_000), "< ".repeat(50_000) + "1",
                "1 in (".repeat(50_000) + "1" + ")".repeat(50_000), "for x in [1] return ".repeat(50_000) + "1")) {
            assertThat(onSmallStack(deep)).startsWith("syntax: ").endsWith("it nests more than 300 levels");
        }
    }

    /** What unary tests, as a table's cell holds them, give on a small stack: whether they hold for 1, or the exception. */
    private static String testsOnSmallStack(String cell) throws Exception {
        return onStack(STACK, () -> {
            try {
                var tests = FeelUnaryTests.parse(cell, FeelExpression.scope(List.of(), Map.of()));
                tests.literals();
                return String.valueOf(tests.matches(BigDecimal.ONE, name -> null, null));
            } catch (ExpressionSyntaxException e) {
                return "syntax: " + e.getMessage();
            } catch (StackOverflowError e) {
                return "stack overflow";
            }
        });
    }

    @Test
    void unary_tests_nest_as_deep_as_expressions_and_no_deeper() throws Exception {
        int depth = FeelParser.MAX_PARENTHESES - 1;
        assertThat(testsOnSmallStack("(".repeat(depth) + "1" + ")".repeat(depth))).isEqualTo("true");
        assertThat(testsOnSmallStack("not(" + "(".repeat(depth) + "2" + ")".repeat(depth) + ")")).isEqualTo("true");
        assertThat(testsOnSmallStack("= " + "(".repeat(depth - 1) + "1" + ")".repeat(depth - 1))).isEqualTo("true");
        for (var deep : List.of("(".repeat(50_000) + "1" + ")".repeat(50_000), "not(" + "[".repeat(50_000) + "]".repeat(50_000) + ")",
                "? > " + "-".repeat(50_000) + "1", "= " + "(".repeat(50_000) + "1" + ")".repeat(50_000), "< ".repeat(50_000) + "1")) {
            assertThat(testsOnSmallStack(deep)).startsWith("syntax: ").endsWith("it nests more than 300 levels");
        }
        // many tests are a loop, not recursion
        assertThat(testsOnSmallStack(String.join(", ", Collections.nCopies(100_000, "2")) + ", 1")).isEqualTo("true");
    }

    @Test
    void a_long_flat_expression_takes_no_stack() throws Exception {
        // a list or a context of many entries, and many iteration contexts, are loops, not recursion
        assertThat(onSmallStack("[" + String.join(", ", Collections.nCopies(100_000, "1")) + "]")).isEqualTo("value");
        assertThat(onSmallStack("for " + String.join(", ", Collections.nCopies(5_000, "x in [1]")) + " return 1")).isEqualTo("value");
    }

    @Test
    void functions_that_invoke_themselves_without_end_give_null_with_an_error() throws Exception {
        assertThat(onSmallStack("{f: function(n) 1 + f(n + 1), r: f(0)}.r"))
                .isEqualTo("null: functions invoke one another more deeply than Praecepta evaluates (800 levels)");
        // each invocation as deep as an expression may be, inside one as deep: still within the stack
        var body = "1 + (".repeat(140) + "f(n - 1)" + ")".repeat(140);
        var deepest = "{f: function(n) if n = 0 then 0 else " + body + ", r: " + "1 + (".repeat(140) + "f(100)" + ")".repeat(140) + "}.r";
        assertThat(onSmallStack(deepest)).startsWith("null: functions invoke one another");
        var iterating = "{f: function(n) if n = 0 then [] else " + "for x in [1] return ".repeat(60) + "f(n - 1), r: "
                + "for x in [1] return ".repeat(60) + "f(100)}.r";
        assertThat(onSmallStack(iterating)).startsWith("value: functions invoke one another");
        var contexts = "{f: function(n) if n = 0 then 0 else " + "{a: ".repeat(70) + "f(n - 1)" + "}".repeat(70) + ", r: f(100)}.r";
        assertThat(onSmallStack(contexts)).startsWith("value: functions invoke one another");
        var filtering = "{f: function(n) if n = 0 then 0 else [1][" + "item > 0 and (".repeat(60) + "f(n - 1) > -1" + ")".repeat(60)
                + "], r: f(100)}.r";
        assertThat(onSmallStack(filtering)).startsWith("value: functions invoke one another");
        var tests = "{f: function(n) if n = 0 then true else n in (" + "(".repeat(100) + "f(n - 1)" + ")".repeat(100) + "), r: f(100)}.r";
        assertThat(onSmallStack(tests)).startsWith("null: functions invoke one another");
    }
}
