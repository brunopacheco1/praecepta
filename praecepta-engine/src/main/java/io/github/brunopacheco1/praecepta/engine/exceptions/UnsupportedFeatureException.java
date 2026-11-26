/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

import java.io.Serial;

/**
 * A DMN model uses what DMN 1.5 defines but Praecepta does not evaluate or does not read: an external function (Java
 * or PMML), which it rejects by design, as it runs no code from outside the model; the values a literal expression
 * imports; an import that is not a DMN model; a model that imports others, read without them. The model is not
 * wrong, and the message names what it uses. Callers that fall back to what they can evaluate (a framework
 * integration reading a file's decision tables alone) tell it from an invalid model by its type, never by its message.
 */
public class UnsupportedFeatureException extends InvalidDecisionTableException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what the model uses that Praecepta does not evaluate or read, naming where
     */
    public UnsupportedFeatureException(String message) {
        super(message);
    }
}
