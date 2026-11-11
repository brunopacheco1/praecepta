/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.io.Serial;

/** An output expression is outside the supported subset, or combines values of types that do not go together. */
public class ExpressionSyntaxException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExpressionSyntaxException(String problem) {
        super(problem);
    }
}
