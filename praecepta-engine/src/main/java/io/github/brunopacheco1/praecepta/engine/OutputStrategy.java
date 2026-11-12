/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;

/**
 * Converts rule outputs to the engine's output type. {@link #sum}, {@link #min}, {@link #max} and
 * {@link #count} are only needed for COLLECT aggregations, and are applied on every evaluation, so
 * they should be cheap and side-effect free.
 */
@FunctionalInterface
public interface OutputStrategy<O> {

    /**
     * Converts one rule's outputs. Called once per rule, when the engine is built, except for a rule
     * whose output reads inputs (names an input or is an expression): its output is converted on every
     * evaluation that selects it, with the inputs' values. Such an output is null in
     * {@link PraeceptaOutput#values()} when the input it names is missing, or the expression gives null
     * (a missing operand, a division by zero). The result may not be null.
     *
     * <p>Under SUM, MIN and MAX, a table with one output (the one DMN aggregates) has a missing aggregate
     * when that output is missing in some matching rule, as FEEL's {@code sum}, {@code min} and {@code max}
     * give null for a list that holds null (DMN 1.5, 10.3.4.4 and 10.3.2.16): the engine then returns that
     * rule's output, converted by this method, and calls none of {@link #sum}, {@link #min} and {@link #max},
     * which so only ever see outputs that are not missing. With several outputs, which DMN does not aggregate,
     * the strategy decides what a missing one adds up to.
     */
    O transform(PraeceptaOutput output);

    default O max(O obj1, O obj2) {
        throw new UnsupportedOperationException("This OutputStrategy does not support aggregation");
    }

    default O min(O obj1, O obj2) {
        throw new UnsupportedOperationException("This OutputStrategy does not support aggregation");
    }

    default O sum(O obj1, O obj2) {
        throw new UnsupportedOperationException("This OutputStrategy does not support aggregation");
    }

    /**
     * The output of a COUNT aggregation: the number of matching rules, at least one. When no rule
     * matches, the engine gives the default output, or no output, without calling it (DMN 1.5,
     * 10.3.2.10, step 2: the result is then the default output value, or null). The engine calls it once
     * when it is built, so a strategy without it fails there.
     */
    default O count(int matches) {
        throw new UnsupportedOperationException("This OutputStrategy does not support aggregation");
    }
}
