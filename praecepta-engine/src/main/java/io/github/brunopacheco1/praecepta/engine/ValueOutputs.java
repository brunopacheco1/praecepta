/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;

/**
 * An output strategy that takes the values a rule's outputs compute as FEEL values, not only as text, for
 * {@link DecisionGraph}: a list or a context, or the number an output of no declared type computes, has no text that
 * reads back as itself.
 */
interface ValueOutputs<O> extends OutputStrategy<O> {

    /**
     * Converts the outputs of a rule that reads inputs (names an input or computes its value), on every evaluation that
     * selects it.
     *
     * @param output the outputs that read no input, as text, as {@link #transform(PraeceptaOutput)} takes them; those
     *               that read inputs are left out, as their values may have no text short enough to write
     * @param values the values of the outputs that read inputs, by output name, each bound to its output's type; null
     *               for one whose value is null
     */
    O transform(PraeceptaOutput output, Map<String, Object> values);
}
