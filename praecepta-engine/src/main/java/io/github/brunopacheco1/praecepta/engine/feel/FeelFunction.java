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
 *
 * <p>Only Praecepta makes function values: an evaluation gives one, which may be passed back as the value of a name
 * another evaluation reads; an object of a class of one's own is no function in FEEL. Two function definitions' values
 * are equal when they have the same parameters and body and were defined in the same scope (DMN 1.5, Table 53: "same
 * parameters, body and scope"); a built-in function equals itself alone.
 */
public sealed interface FeelFunction permits Closure, BuiltInFunction {

    /** Its parameters' names, in order. */
    List<String> parameters();

    /**
     * Its type ({@link FeelType.FunctionOf}): its parameters' declared types ({@link FeelType#ANY} where it declares
     * none) and the type of its value.
     */
    FeelType type();

    /**
     * Invokes it, as an evaluation of its own: with the limits of one ({@link FeelExpression}), and its errors reported
     * to {@code errors}. Safe to call from many threads at once.
     *
     * @param arguments one value per parameter, in order (null where an argument is null or missing); a
     *                  {@link Number} is taken as the {@code BigDecimal} of its value
     * @param errors    receives a message for each error FEEL reports on the way (DMN 1.5, 10.3.2.16); may be null to
     *                  ignore them
     * @return its value, null where FEEL gives null
     */
    Object invoke(List<Object> arguments, Consumer<String> errors);
}
