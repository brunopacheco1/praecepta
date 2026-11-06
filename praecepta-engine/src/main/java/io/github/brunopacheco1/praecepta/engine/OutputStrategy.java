/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;

/**
 * Converts rule outputs to the engine's output type. {@link #sum}, {@link #min} and {@link #max}
 * are only needed for COLLECT aggregations, and are applied to the matching outputs on every
 * evaluation, so they should be cheap and side-effect free.
 */
@FunctionalInterface
public interface OutputStrategy<O> {

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
}
