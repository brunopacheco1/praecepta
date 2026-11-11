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
 * {@code a}. The engine fills such an output in from each evaluated input, with the text of the
 * input's value ({@code null} if it is missing), so the {@code OutputStrategy} only ever sees
 * {@link #values}.
 *
 * @param values     the output values by column name
 * @param references the outputs that name an input: output column name to input column name
 */
public record PraeceptaOutput(Map<String, String> values, Map<String, String> references) {

    public PraeceptaOutput {
        references = Map.copyOf(references);
    }

    /** Outputs that are all values, naming no input. */
    public PraeceptaOutput(Map<String, String> values) {
        this(values, Map.of());
    }
}
