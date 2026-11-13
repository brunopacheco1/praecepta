/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * How Praecepta evaluates a TCK model: the one seam between the kit and the engine. Given a result node (its
 * model, the test case's inputs, and the decision, decision service or business knowledge model it names), it
 * returns the node's value, an error, a failure, or the features it lacks. {@link TckRunner} does the rest:
 * comparing with the expected result, scoring, the baseline and RESULTS.md.
 *
 * <p>{@link #praecepta()} is what Praecepta has now. A release that adds a way to evaluate models puts its
 * evaluator in front of it, so the nodes it cannot take still get the reasons the others give; decision
 * requirement graphs (#14), for one: {@code graphs.orElse(new SingleDecisionEvaluator())}.
 */
@FunctionalInterface
interface ModelEvaluator {

    /** Evaluates one result node. It must not throw: a problem is a {@link Evaluation.Failure}. */
    Evaluation evaluate(Check check);

    /**
     * This evaluator, and {@code next} for the nodes this one does not support. When neither supports a node, this
     * one's reasons are kept: it is the more capable, so what it lacks is what the node waits for.
     */
    default ModelEvaluator orElse(ModelEvaluator next) {
        return check -> {
            var evaluation = evaluate(check);
            if (!(evaluation instanceof Evaluation.Unsupported)) {
                return evaluation;
            }
            var fallback = next.evaluate(check);
            return fallback instanceof Evaluation.Unsupported ? evaluation : fallback;
        };
    }

    /** How the TCK run evaluates models: each decision on its own, a decision table or a literal expression. */
    static ModelEvaluator praecepta() {
        return new SingleDecisionEvaluator();
    }
}
