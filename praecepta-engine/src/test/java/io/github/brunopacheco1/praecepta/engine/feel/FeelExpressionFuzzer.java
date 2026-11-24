/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.List;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Literal expressions are written by people, so any text must either parse or be rejected with an
 * {@link ExpressionSyntaxException}; any other exception, or a stack overflow, is a bug. An expression that parses must
 * be typed and evaluated without throwing, whatever it computes: FEEL gives null, with an error, where it defines no
 * value, and the engine bounds nesting, iterations and invocations. Its names are evaluated as null, and as the
 * number 1, since what it does with a value differs from what it does with none.
 *
 * <p>A plain Jazzer fuzz target, so that both {@link FeelExpressionFuzzTest} (JUnit, in every build) and
 * ClusterFuzzLite (see {@code .clusterfuzzlite/build.sh}) run it.
 */
@SuppressWarnings("exports") // Jazzer's API is on the class path, not a module
public final class FeelExpressionFuzzer {

    private FeelExpressionFuzzer() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        var text = data.consumeRemainingAsString();
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, List.of("x", "y z"));
        } catch (ExpressionSyntaxException rejected) {
            return; // a clear rejection is the expected outcome for text that is not FEEL
        }
        try {
            expression.type(name -> null, reason -> {
                // reasons are only reported
            });
            expression.type(name -> FeelType.ANY);
            expression.evaluate(name -> null, error -> {
                // errors are FEEL's null, reported
            });
            expression.evaluate(name -> java.math.BigDecimal.ONE);
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("%s parsed, but typing or evaluating it threw %s".formatted(quote(text), e), e);
        }
    }

    private static String quote(String text) {
        return "'" + text.replace("\n", "\\n") + "'";
    }
}
