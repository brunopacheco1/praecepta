/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * FEEL's built-in functions (DMN 1.5, 10.3.4): their names, which are in every expression's scope, and the functions,
 * since 0.9 (<a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>), each a {@link BuiltInFunction}
 * under its FEEL name, with its parameters, the arguments it takes ({@link BuiltInFunction.Arity}: fixed, optional or
 * variadic) and a body given the evaluation that invokes it, so that it counts against that evaluation's limits and
 * reports its errors there: the conversion, boolean, date and time and miscellaneous functions
 * ({@link ConversionFunctions}), the string functions ({@link StringFunctions}), the list functions and {@code sort}
 * ({@link ListFunctions}), the numeric functions ({@link NumericFunctions}), the range functions
 * ({@link RangeFunctions}) and the context functions ({@link ContextFunctions}). {@code date}, {@code time},
 * {@code date and time} and {@code duration} of literal arguments, FEEL's date time literals (grammar rule 60), are
 * evaluated when the expression is parsed.
 */
final class BuiltInFunctions {

    /** The names of DMN 1.5's built-in functions (10.3.4). */
    static final Set<String> NAMES = Set.of(
            // conversion (10.3.4.1)
            "date", "date and time", "time", "number", "string", "duration", "years and months duration", "range",
            // boolean (10.3.4.2) and miscellaneous (10.3.4.11)
            "not", "is", "now", "today",
            // string (10.3.4.3)
            "substring", "string length", "upper case", "lower case", "substring before", "substring after", "replace",
            "contains", "starts with", "ends with", "matches", "split", "string join",
            // list (10.3.4.4)
            "list contains", "count", "min", "max", "sum", "mean", "all", "any", "sublist", "append", "concatenate",
            "insert before", "remove", "reverse", "index of", "union", "distinct values", "flatten", "product", "median",
            "stddev", "mode", "list replace",
            // numeric (10.3.4.5)
            "decimal", "floor", "ceiling", "round up", "round down", "round half up", "round half down", "abs", "modulo",
            "sqrt", "log", "exp", "odd", "even",
            // date and time (10.3.4.6)
            "day of year", "day of week", "month of year", "week of year",
            // range (10.3.4.7)
            "before", "after", "meets", "met by", "overlaps", "overlaps before", "overlaps after", "finishes", "finished by",
            "includes", "during", "starts", "started by", "coincides",
            // sort (10.3.4.9) and context (10.3.4.10)
            "sort", "get value", "get entries", "context", "context put", "context merge");

    /** The functions, by name. */
    private static final Map<String, FeelFunction> FUNCTIONS = library();

    private BuiltInFunctions() {
    }

    private static Map<String, FeelFunction> library() {
        var functions = new HashMap<String, FeelFunction>();
        Consumer<BuiltInFunction> add = function -> {
            if (!NAMES.contains(function.name()) || functions.put(function.name(), function) != null) {
                throw new IllegalStateException("not one of DMN 1.5's built-in functions, or registered twice: " + function.name());
            }
        };
        ConversionFunctions.register(add);
        StringFunctions.register(add);
        ListFunctions.register(add);
        NumericFunctions.register(add);
        RangeFunctions.register(add);
        ContextFunctions.register(add);
        if (!functions.keySet().equals(NAMES)) {
            var missing = NAMES.stream().filter(name -> !functions.containsKey(name)).toList();
            throw new IllegalStateException("built-in functions not registered: " + missing);
        }
        return Map.copyOf(functions);
    }

    /** The built-in function of that name, or null if there is none. */
    static FeelFunction lookup(String name) {
        return FUNCTIONS.get(name);
    }
}
