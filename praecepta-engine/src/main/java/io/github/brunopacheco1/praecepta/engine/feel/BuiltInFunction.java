/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A built-in function of FEEL (DMN 1.5, 10.3.4), as {@link BuiltInFunctions} registers it: its name, its parameters'
 * names, how many arguments it takes ({@link Arity}: a fixed number, some optional, or any number more), its type, and
 * its body, which is given the evaluation that invokes it, so that a function that iterates (a sort, a sum) counts
 * against that evaluation's limits and reports its errors there. Invoked with arguments it does not take, its value is
 * null, with an error; invoked from Java ({@link #invoke}), it is an evaluation of its own.
 *
 * <p>Some functions have several signatures, whose parameters have other names: {@code date(from)} and
 * {@code date(year, month, day)}, {@code before(point, range)} and {@code before(range1, range2)}. A named invocation
 * binds its arguments by the signature that names them all ({@link #signatureNaming}).
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
         * @return its value; or it throws {@link Invalid}, for null with an error
         */
        Object apply(List<Object> arguments, Env env);
    }

    /**
     * What a built-in function of several signatures computes, knowing which signature a named invocation binds its
     * arguments by: {@code context put(context, key, value)} takes one key, {@code context put(context, keys, value)}
     * a list of them, and a positional invocation either.
     */
    @FunctionalInterface
    interface Overloaded {
        /**
         * @param signature the index of the signature that named the arguments, or {@link #POSITIONAL}
         */
        Object apply(List<Object> arguments, int signature, Env env);
    }

    /** The signature of a positional invocation, which names none. */
    static final int POSITIONAL = -1;

    /**
     * Thrown by a body for arguments it gives no value for: the invocation is null, with the message as its error (none
     * if the message is null, for an evaluation that has already stopped, beyond its limits). It carries no stack trace,
     * as it is how FEEL's errors are reported, not a fault.
     */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private final String name;
    private final List<List<String>> signatures;
    private final Arity arity;
    private final FeelType type;
    private final Overloaded body;

    /**
     * @param parameters its parameters' names, as named arguments name them: one for each it may take, the last
     *                   naming those beyond it for a variadic function
     */
    BuiltInFunction(String name, List<String> parameters, Arity arity, FeelType type, Body body) {
        this(name, arity, type, overloaded(body), List.of(parameters));
    }

    private static Overloaded overloaded(Body body) {
        Objects.requireNonNull(body, "body");
        return (arguments, signature, env) -> body.apply(arguments, env);
    }

    /**
     * @param signatures each signature's parameters' names, the first its {@link #parameters()}: one for each it may
     *                   take, the last naming those beyond it for a variadic function
     */
    private BuiltInFunction(String name, Arity arity, FeelType type, Overloaded body, List<List<String>> signatures) {
        this.name = Objects.requireNonNull(name, "name");
        this.signatures = signatures.stream().map(List::copyOf).toList();
        this.arity = Objects.requireNonNull(arity, "arity");
        this.type = Objects.requireNonNull(type, "type");
        this.body = Objects.requireNonNull(body, "body");
        int named = this.signatures.stream().mapToInt(List::size).max().orElse(0);
        if (this.signatures.isEmpty() || named < arity.required() + arity.optional()) {
            throw new IllegalArgumentException("%s takes %s arguments, but names %d".formatted(name, arity, named));
        }
    }

    /**
     * A function of that name and those parameters (each signature's), whose type is a function of its first
     * signature's parameters, of any type, giving a value of {@code result}'s type.
     */
    static BuiltInFunction of(String name, FeelType result, Arity arity, Body body, List<List<String>> signatures) {
        return of(name, result, arity, overloaded(body), signatures);
    }

    /** A function of several signatures, whose body knows which one named its arguments. */
    static BuiltInFunction of(String name, FeelType result, Arity arity, Overloaded body, List<List<String>> signatures) {
        var parameters = Collections.nCopies(signatures.getFirst().size(), FeelType.ANY);
        return new BuiltInFunction(name, arity, new FeelType.FunctionOf(parameters, result), body, signatures);
    }

    /** A function of one signature, as {@link #of(String, FeelType, Arity, Body, List)}. */
    static BuiltInFunction of(String name, FeelType result, Arity arity, Body body, String... parameters) {
        return of(name, result, arity, body, List.of(List.of(parameters)));
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
        return signatures.getFirst();
    }

    /**
     * The signature a named invocation binds its arguments by: one that names every argument, in the order the
     * invocation names them if one does ({@code before(range: r, point: p)} is {@code before(range, point)}); else its
     * first, which names some argument it does not have.
     *
     * @return the signature's index, for {@link #signature} and {@link #call(List, int, Env)}
     */
    int signatureNaming(List<String> names) {
        int naming = -1;
        for (int i = 0; i < signatures.size(); i++) {
            var signature = signatures.get(i);
            if (signature.containsAll(names)) {
                if (inOrder(signature, names)) {
                    return i;
                }
                naming = naming < 0 ? i : naming;
            }
        }
        return Math.max(naming, 0);
    }

    /** The parameters' names of a signature. */
    List<String> signature(int index) {
        return signatures.get(index);
    }

    private static boolean inOrder(List<String> signature, List<String> names) {
        for (int i = 1; i < names.size(); i++) {
            if (signature.indexOf(names.get(i - 1)) > signature.indexOf(names.get(i))) {
                return false;
            }
        }
        return true;
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
        return call(arguments, POSITIONAL, caller);
    }

    /**
     * Invokes it from an evaluation, with arguments a signature named ({@link #signatureNaming}), or
     * {@link #POSITIONAL} ones.
     */
    Object call(List<Object> arguments, int signature, Env caller) {
        if (!arity.accepts(arguments.size())) {
            return caller.error(() -> "%s takes %s arguments, not %d".formatted(name, arity, arguments.size()));
        }
        if (!caller.step()) {
            return null;
        }
        try {
            return body.apply(arguments, signature, caller);
        } catch (Invalid e) {
            return e.getMessage() == null ? null : caller.error(() -> name + "(...) " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
