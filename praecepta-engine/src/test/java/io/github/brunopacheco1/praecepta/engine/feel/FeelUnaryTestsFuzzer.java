/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * A decision table's input entries are written by people, so any text must either be read or be rejected cleanly: as
 * unary tests ({@link FeelUnaryTests}) it parses or is an {@link ExpressionSyntaxException}; as a table reads a cell of
 * any column type (the simple unary tests {@link CellParser} reads, else FEEL beyond them), it is read or a
 * {@link CellSyntaxException}. Tests that parse must be applied to any value without throwing, a missing one and one of
 * another type included, with the names they read null or that value: FEEL gives null where it defines no value, and
 * the engine bounds nesting, iterations and invocations. Any other exception, or a stack overflow, is a bug.
 *
 * <p>A plain Jazzer fuzz target, so that both {@link FeelUnaryTestsFuzzTest} (JUnit, in every build) and
 * ClusterFuzzLite (see {@code .clusterfuzzlite/build.sh}) run it.
 */
@SuppressWarnings("exports") // Jazzer's API is on the class path, not a module
public final class FeelUnaryTestsFuzzer {

    private static final FeelExpression.Scope SCOPE = FeelExpression.scope(List.of("x", "y z"), Map.of());
    /** Values of several types, and a missing one. */
    private static final List<Object> VALUES = Arrays.asList(null, BigDecimal.ONE, "x", LocalDate.of(2025, 1, 31), Boolean.TRUE,
            List.of(BigDecimal.ONE, "x"));

    private FeelUnaryTestsFuzzer() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        var text = data.consumeRemainingAsString();
        for (var type : ColumnType.values()) {
            readAsATableDoes(text, type);
        }
        FeelUnaryTests tests;
        try {
            tests = FeelUnaryTests.parse(text, SCOPE);
        } catch (ExpressionSyntaxException rejected) {
            return; // a clear rejection is the expected outcome for text that is not unary tests
        }
        try {
            tests.isSimple();
            tests.literals();
            for (var value : VALUES) {
                tests.matches(value, name -> null, error -> {
                    // errors are FEEL's null, reported
                });
                tests.matches(value, name -> value, null);
            }
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("%s parsed, but applying it threw %s".formatted(quote(text), e), e);
        }
    }

    /** A cell, as a table of that column type reads it: simple unary tests, or FEEL beyond them, or a clear rejection. */
    private static void readAsATableDoes(String text, ColumnType type) {
        try {
            CellParser.parseFeel(text, type);
        } catch (CellSyntaxException rejected) {
            try {
                FeelUnaryTests.parseCell(text, SCOPE, rejected);
            } catch (CellSyntaxException expected) {
                // rejected, as a table rejects it
            } catch (RuntimeException | StackOverflowError e) {
                throw new AssertionError("%s cell %s threw %s".formatted(type, quote(text), e), e);
            }
        }
    }

    private static String quote(String text) {
        return "'" + text.replace("\n", "\\n") + "'";
    }
}
