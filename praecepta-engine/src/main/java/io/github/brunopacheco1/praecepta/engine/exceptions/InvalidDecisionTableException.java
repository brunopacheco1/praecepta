/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

import io.github.brunopacheco1.praecepta.engine.ValidationReport;

/**
 * The decision table cannot be built: either its definition is inconsistent (for example an output
 * value missing from its priority list), or its validation report contains an issue at or above the
 * severity passed to {@code failOn}.
 */
public class InvalidDecisionTableException extends RuntimeException {

    private final transient ValidationReport report;

    public InvalidDecisionTableException(String message) {
        super(message);
        this.report = null;
    }

    public InvalidDecisionTableException(ValidationReport report) {
        super("Decision table failed validation:\n" + report);
        this.report = report;
    }

    /** The report that failed the build, or {@code null} if the definition itself was invalid. */
    public ValidationReport getReport() {
        return report;
    }
}
