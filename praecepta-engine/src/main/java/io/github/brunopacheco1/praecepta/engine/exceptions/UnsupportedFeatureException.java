/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

import java.io.Serial;

/**
 * A DMN model uses what DMN 1.5 defines but Praecepta does not evaluate yet, and a later release of its plan to
 * become a full DMN 1.5 engine brings (<a href="https://github.com/brunopacheco1/praecepta/issues/120">#120</a>): the
 * full FEEL language (0.8), its built-in functions (0.9), business knowledge models and boxed expressions (0.10),
 * decision services and imports (0.11); or what it does not evaluate by design, an external function. The model is
 * not wrong; {@link #release()} and {@link #issue()} name the release that plans the feature, as the message does, or
 * none. Callers that fall back to what they can
 * evaluate (a framework integration reading a file's decision tables alone) tell it apart from an invalid model by its
 * type, never by its message.
 */
public class UnsupportedFeatureException extends InvalidDecisionTableException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String release;
    private final int issue;

    /**
     * @param message what the model uses, naming the release that plans it
     * @param release the release that plans it, such as {@code 0.8}; null for what Praecepta does not evaluate by design
     * @param issue   the number of the issue that plans it, such as {@code 116}; 0 for what no release plans
     */
    public UnsupportedFeatureException(String message, String release, int issue) {
        super(message);
        this.release = release;
        this.issue = issue;
    }

    /**
     * The release that plans the feature, such as {@code 0.8}; null for what Praecepta does not evaluate by design, an
     * external function (Java or PMML), as it runs no code from outside the model.
     */
    public String release() {
        return release;
    }

    /** The number of the issue that plans the feature, such as {@code 116}; 0 for what no release plans. */
    public int issue() {
        return issue;
    }
}
