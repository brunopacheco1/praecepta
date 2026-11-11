/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Map;

/**
 * The output values of a rule, keyed by output column name. An {@code OutputStrategy} converts them
 * to the engine's output type.
 *
 * <p>An output may instead name an input column, as a DMN output entry {@code a} returns the input
 * {@code a}, or compute its value with an expression, as in {@code Symptom + " is listed"} (see
 * {@code OutputExpression}). The engine fills such outputs in from each evaluated input, with the text
 * of the input's value or of the expression's ({@code null} if it is missing), so the
 * {@code OutputStrategy} only ever sees {@link #values}.
 *
 * @param values      the output values by column name
 * @param references  the outputs that name an input: output column name to input column name
 * @param expressions the outputs that are expressions: output column name to the expression, as written
 */
public record PraeceptaOutput(Map<String, String> values, Map<String, String> references, Map<String, String> expressions) {

    public PraeceptaOutput {
        references = Map.copyOf(references);
        expressions = Map.copyOf(expressions);
    }

    /** Outputs that are values or name inputs, with no expression. */
    public PraeceptaOutput(Map<String, String> values, Map<String, String> references) {
        this(values, references, Map.of());
    }

    /** Outputs that are all values, naming no input. */
    public PraeceptaOutput(Map<String, String> values) {
        this(values, Map.of());
    }

    /** Whether some output names an input or is an expression, so it is only known once an input is evaluated. */
    public boolean readsInputs() {
        return !references.isEmpty() || !expressions.isEmpty();
    }
}
