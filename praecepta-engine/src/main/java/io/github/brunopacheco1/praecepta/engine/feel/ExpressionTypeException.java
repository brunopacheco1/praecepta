/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.io.Serial;

/**
 * An output expression parses, but its values do not go together: it names something that is not an
 * input, or an input of a type other than string or number, or an operator gets a string and a number,
 * or a string where only numbers go (see {@link OutputExpression#type}).
 */
public class ExpressionTypeException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExpressionTypeException(String problem) {
        super(problem);
    }
}
