/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.io.Serial;

/**
 * Text that is not FEEL (DMN 1.5, 10.3.1.2): an expression or unary tests that do not parse, or nest too deeply.
 */
public class ExpressionSyntaxException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExpressionSyntaxException(String problem) {
        super(problem);
    }
}
