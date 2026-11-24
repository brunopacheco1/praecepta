/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.List;
import java.util.function.Consumer;

/**
 * A FEEL function value (DMN 1.5, 10.3.2.8 and 10.3.2.13): a function definition's value, such as
 * {@code function(a, b) a + b}, which closes over the names in scope where it is defined, or a built-in function.
 * Invoking it with positional arguments passes them in order; with named arguments, by its parameters' names.
 */
public interface FeelFunction {

    /** Its parameters' names, in order. */
    List<String> parameters();

    /**
     * Its type ({@link FeelType.FunctionOf}): its parameters' declared types ({@link FeelType#ANY} where it declares
     * none) and the type of its value.
     */
    FeelType type();

    /**
     * Invokes it.
     *
     * @param arguments one value per parameter, in order (null where an argument is null or missing)
     * @param errors    receives a message for each error FEEL reports on the way (DMN 1.5, 10.3.2.15); may be null to
     *                  ignore them
     * @return its value, null where FEEL gives null
     */
    Object invoke(List<Object> arguments, Consumer<String> errors);
}
