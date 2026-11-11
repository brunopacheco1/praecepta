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
     * (a missing operand, a division by zero); under a COLLECT aggregation, the strategy decides what that
     * adds up to, for example by converting it to a value that {@link #sum}, {@link #min} and
     * {@link #max} skip. The result may not be null.
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
     * The output of a COUNT aggregation: the number of matching rules, zero when none match. The
     * engine calls it with zero once when it is built, so a strategy without it fails there.
     */
    default O count(int matches) {
        throw new UnsupportedOperationException("This OutputStrategy does not support aggregation");
    }
}
