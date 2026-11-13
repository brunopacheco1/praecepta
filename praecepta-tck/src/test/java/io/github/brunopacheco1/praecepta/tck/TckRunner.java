/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.List;

import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Scores one result node: evaluates it with a {@link ModelEvaluator} and compares what it gives with the node's
 * expected result.
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

    /**
     * @param detail for UNSUPPORTED, the feature that stopped evaluation; for FAILED, what differed; else empty
     * @param needs  for UNSUPPORTED, every feature the node needs, the one that stopped evaluation first; else empty
     */
    record Outcome(Status status, String detail, List<Feature> needs) {
        static final Outcome PASSED = new Outcome(Status.PASSED, "", List.of());

        static Outcome failed(String detail) {
            return new Outcome(Status.FAILED, detail, List.of());
        }
    }

    private final ModelEvaluator evaluator;

    TckRunner(ModelEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    Outcome run(Check check) {
        Evaluation evaluation;
        try {
            evaluation = evaluator.evaluate(check);
        } catch (RuntimeException e) {
            evaluation = new Evaluation.Failure("evaluation threw " + e);
        }
        return switch (evaluation) {
            case Evaluation.Unsupported unsupported -> new Outcome(Status.UNSUPPORTED, unsupported.first().name(), unsupported.needs());
            case Evaluation.Failure(var message) -> Outcome.failed(message);
            case Evaluation.Error(var message) -> check.expectsError() ? Outcome.PASSED
                    : Outcome.failed("expected %s, but evaluation threw: %s".formatted(check.expected(), message));
            case Evaluation.Value(var actual, var ordered) when check.expectsError() -> Outcome.failed("expected an error, got " + actual);
            case Evaluation.Value(var actual, var ordered) -> check.expected().matches(actual, ordered) ? Outcome.PASSED
                    : Outcome.failed("expected %s, got %s".formatted(check.expected(), actual));
        };
    }
}
