/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Scores one result node: evaluates it through its model's decision requirements graph ({@link GraphEvaluator}) and
 * compares what that gives with the node's expected result.
 *
 * <p>A node flagged {@code errorResult} passes when evaluation reports an error, and fails when it gives a value;
 * a node that is not fails when evaluation reports one.
 */
final class TckRunner {

    enum Status {
        PASSED,
        FAILED,
        UNSUPPORTED,
    }

    /** @param detail for FAILED, what differed; for UNSUPPORTED, what the model uses that Praecepta rejects; else empty */
    record Outcome(Status status, String detail) {
        static final Outcome PASSED = new Outcome(Status.PASSED, "");

        static Outcome failed(String detail) {
            return new Outcome(Status.FAILED, detail);
        }
    }

    private final GraphEvaluator evaluator = new GraphEvaluator();

    /** Evaluates a node and scores it; an evaluation that throws fails the node, not the run. */
    Outcome run(Check check) {
        Evaluation evaluation;
        try {
            evaluation = evaluator.evaluate(check);
        } catch (RuntimeException e) {
            evaluation = new Evaluation.Failure("evaluation threw " + e);
        }
        return score(check, evaluation);
    }

    /** What an evaluation is worth against the node's expected result. */
    static Outcome score(Check check, Evaluation evaluation) {
        return switch (evaluation) {
            case Evaluation.Unsupported(var reason) -> new Outcome(Status.UNSUPPORTED, reason);
            case Evaluation.Failure(var message) -> Outcome.failed(message);
            case Evaluation.Error(var message) -> check.expectsError() ? Outcome.PASSED
                    : Outcome.failed("expected %s, but evaluation threw: %s".formatted(check.expected(), message));
            case Evaluation.Value(var actual, var ordered) when check.expectsError() -> Outcome.failed("expected an error, got " + actual);
            case Evaluation.Value(var actual, var ordered) -> check.expected().matches(actual, ordered) ? Outcome.PASSED
                    : Outcome.failed("expected %s, got %s".formatted(check.expected(), actual));
        };
    }
}
