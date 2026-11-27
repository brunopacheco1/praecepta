/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import io.github.brunopacheco1.praecepta.integration.DecisionsException;

/**
 * Explains a startup that failed because decision tables could not be loaded, listing every problem, instead
 * of a stack trace.
 */
public class PraeceptaFailureAnalyzer extends AbstractFailureAnalyzer<DecisionsException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DecisionsException cause) {
        var description = new StringBuilder("Praecepta could not load the decision tables:\n");
        // a problem's validation report spans several lines: indent them under its bullet
        cause.problems().forEach(problem -> description.append("\n- ").append(problem.strip().replaceAll("\\R", "\n  ")));
        var action = """
                Fix the tables or settings listed above. praecepta.locations chooses the DMN files to load, and \
                praecepta.fail-on the validation severity that stops startup (ERROR, WARNING or NONE).""";
        return new FailureAnalysis(description.toString(), action, cause);
    }
}
