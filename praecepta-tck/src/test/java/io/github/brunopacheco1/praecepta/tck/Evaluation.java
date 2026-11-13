/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.List;

/**
 * What a {@link ModelEvaluator} gives for one result node; {@link TckRunner} compares it with the node's expected
 * result.
 */
sealed interface Evaluation {

    /**
     * The value Praecepta computed.
     *
     * @param ordered whether a list result is compared in order (not for a COLLECT table, whose order DMN leaves open)
     */
    record Value(TckValue value, boolean ordered) implements Evaluation {
    }

    /**
     * Evaluation reported an error, which DMN gives as a null result the test case flags {@code errorResult}: a hit
     * policy violation, an input outside its value list.
     */
    record Error(String message) implements Evaluation {
    }

    /** Praecepta could not evaluate the node: the model is one it should take, but building or evaluating it failed. */
    record Failure(String message) implements Evaluation {
    }

    /**
     * The node needs features Praecepta does not have.
     *
     * @param needs the missing features, the one that stopped evaluation first; never empty
     */
    record Unsupported(List<Feature> needs) implements Evaluation {
        public Unsupported {
            if (needs.isEmpty()) {
                throw new IllegalArgumentException("an unsupported node needs a feature");
            }
            needs = List.copyOf(needs);
        }

        Unsupported(Feature feature) {
            this(List.of(feature));
        }

        /** The feature that stopped evaluation. */
        Feature first() {
            return needs.getFirst();
        }
    }
}
