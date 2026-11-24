/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A built-in function of FEEL (DMN 1.5, 10.3.4), as {@link BuiltInFunctions} registers it: its name, its parameters'
 * names, how many arguments it takes ({@link Arity}: a fixed number, some optional, or any number more), its type, and
 * its body, which is given the evaluation that invokes it, so that a function that iterates (a sort, a sum) counts
 * against that evaluation's limits and reports its errors there. Invoked with arguments it does not take, its value is
 * null, with an error; invoked from Java ({@link #invoke}), it is an evaluation of its own.
 */
final class BuiltInFunction implements FeelFunction {

    /**
     * How many arguments a function takes: {@code required} of them, then up to {@code optional} more
     * ({@code substring(string, start position, length?)}), or, if {@code variadic}, any number more
     * ({@code min(c1, c2, ...)}).
     */
    record Arity(int required, int optional, boolean variadic) {
        Arity {
            if (required < 0 || optional < 0 || variadic && optional > 0) {
                throw new IllegalArgumentException("no function takes %d arguments, %d optional%s".formatted(required, optional,
                        variadic ? ", and any number more" : ""));
            }
        }

        /** Exactly that many arguments. */
        static Arity fixed(int count) {
            return new Arity(count, 0, false);
        }

        /** {@code required} arguments, then up to {@code optional} more. */
        static Arity optional(int required, int optional) {
            return new Arity(required, optional, false);
        }

        /** {@code required} arguments, then any number more. */
        static Arity variadic(int required) {
            return new Arity(required, 0, true);
        }

        /** Whether it takes that many arguments. */
        boolean accepts(int count) {
            return count >= required && (variadic || count <= required + optional);
        }

        /** How many arguments it takes, as a message says it: {@code 2}, {@code 1 to 3}, {@code 1 or more}. */
        @Override
        public String toString() {
            if (variadic) {
                return required + " or more";
            }
            return optional == 0 ? String.valueOf(required) : required + " to " + (required + optional);
        }
    }

    /** What a built-in function computes, from its arguments and in the evaluation that invokes it. */
    @FunctionalInterface
    interface Body {
        /**
         * @param arguments as many as its {@link Arity} accepts, in order; null where an argument is null, or was named
         *                  by none of a named invocation's arguments
         * @param env       the evaluation that invokes it: its errors and limits
         */
        Object apply(List<Object> arguments, Env env);
    }

    private final String name;
    private final List<String> parameters;
    private final Arity arity;
    private final FeelType type;
    private final Body body;

    /**
     * @param parameters its parameters' names, as named arguments name them: one for each it may take, the last
     *                   naming those beyond it for a variadic function
     */
    BuiltInFunction(String name, List<String> parameters, Arity arity, FeelType type, Body body) {
        this.name = Objects.requireNonNull(name, "name");
        this.parameters = List.copyOf(parameters);
        this.arity = Objects.requireNonNull(arity, "arity");
        this.type = Objects.requireNonNull(type, "type");
        this.body = Objects.requireNonNull(body, "body");
        if (this.parameters.size() < arity.required() + arity.optional()) {
            throw new IllegalArgumentException("%s takes %s arguments, but names %d".formatted(name, arity, this.parameters.size()));
        }
    }

    /** Its name, as FEEL writes it. */
    String name() {
        return name;
    }

    /** How many arguments it takes. */
    Arity arity() {
        return arity;
    }

    @Override
    public List<String> parameters() {
        return parameters;
    }

    @Override
    public FeelType type() {
        return type;
    }

    @Override
    public Object invoke(List<Object> arguments, Consumer<String> errors) {
        var values = new ArrayList<>(arguments.size());
        arguments.forEach(argument -> values.add(FeelNumbers.normalise(argument)));
        var env = Env.detached(errors);
        try {
            return env.result(call(values, env));
        } finally {
            env.finish();
        }
    }

    /** Invokes it from an evaluation, whose limits it counts against and to which it reports its errors. */
    Object call(List<Object> arguments, Env caller) {
        if (!arity.accepts(arguments.size())) {
            return caller.error(() -> "%s takes %s arguments, not %d".formatted(name, arity, arguments.size()));
        }
        return caller.step() ? body.apply(arguments, caller) : null;
    }

    @Override
    public String toString() {
        return name;
    }
}
