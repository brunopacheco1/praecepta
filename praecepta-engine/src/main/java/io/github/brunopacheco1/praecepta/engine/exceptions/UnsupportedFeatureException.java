/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

import java.io.Serial;
import java.util.Objects;

/**
 * A DMN model uses what DMN 1.5 defines but Praecepta does not evaluate yet, and a later release of its plan to
 * become a full DMN 1.5 engine brings (<a href="https://github.com/brunopacheco1/praecepta/issues/120">#120</a>): the
 * full FEEL language, its built-in functions, business knowledge models and boxed expressions, decision services or
 * imports. The model is not wrong; {@link #release()} and {@link #issue()} name the release that plans the feature, as
 * the message does. Callers that fall back to what they can evaluate (a framework integration reading a file's
 * decision tables alone) tell it apart from an invalid model by its type, never by its message.
 */
public class UnsupportedFeatureException extends InvalidDecisionTableException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String release;
    private final int issue;

    /**
     * @param message what the model uses, naming the release that plans it
     * @param release the release that plans it, such as {@code 0.8}
     * @param issue   the number of the issue that plans it, such as {@code 116}
     */
    public UnsupportedFeatureException(String message, String release, int issue) {
        super(message);
        this.release = Objects.requireNonNull(release, "release");
        this.issue = issue;
    }

    /** The release that plans the feature, such as {@code 0.8}. */
    public String release() {
        return release;
    }

    /** The number of the issue that plans the feature, such as {@code 116}. */
    public int issue() {
        return issue;
    }
}
