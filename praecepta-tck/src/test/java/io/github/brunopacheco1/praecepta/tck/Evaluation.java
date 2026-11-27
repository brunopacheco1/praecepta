/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

/** What {@link GraphEvaluator} gives for one result node; {@link TckRunner} compares it with the node's expected result. */
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
     * policy violation, an input outside its value list, a null value FEEL reported an error for.
     */
    record Error(String message) implements Evaluation {
    }

    /** Praecepta could not evaluate the node, though it should: reading the model, or building or evaluating its graph, failed. */
    record Failure(String message) implements Evaluation {
    }

    /**
     * The node's model uses what Praecepta rejects by design.
     *
     * @param reason what the model uses, as the DMN reader says it
     */
    record Unsupported(String reason) implements Evaluation {
    }
}
