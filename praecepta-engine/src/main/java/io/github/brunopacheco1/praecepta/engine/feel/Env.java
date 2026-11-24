/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The scope an expression is evaluated in (DMN 1.5, 10.3.2.11): the names its constructs bind (iteration variables,
 * parameters, context entries, a filter's {@code item} and the entries of the item it tests), innermost first, then
 * the names the expression is given. It carries what one evaluation shares: where errors go, and the limits that keep
 * a small expression from taking unbounded time, memory or stack.
 */
final class Env {

    /**
     * At most this many steps in one evaluation: iterations of every {@code for}, {@code some} and {@code every} and
     * elements of the ranges they iterate, function invocations, and elements a filter tests. So
     * {@code for i in 1..1000000000 return i}, or a function that invokes itself twice over, gives null, with an error,
     * rather than run out of memory or time.
     */
    static final int MAX_STEPS = 1_000_000;
    /**
     * The longest string {@code +} makes, in characters: a context whose entries each join the one before with itself
     * would otherwise double its length entry after entry.
     */
    static final int MAX_STRING = 10_000_000;
    /**
     * At most this much nesting at once, counted as the depth of the expression being evaluated plus that of each
     * function body being invoked: a function that invokes itself without end gives null, with an error, rather than
     * overflow the thread's stack (a 512 KiB stack holds it, with room).
     */
    static final int MAX_NESTING = 800;

    /** What every scope of one evaluation shares. */
    private static final class Shared {
        private final Function<String, Object> names;
        private final Consumer<String> errors;
        private int steps;
        private int nesting;

        Shared(Function<String, Object> names, Consumer<String> errors) {
            this.names = names;
            this.errors = errors;
        }
    }

    private final Map<String, ?> frame;
    private final Env parent;
    private final Shared shared;

    private Env(Map<String, ?> frame, Env parent, Shared shared) {
        this.frame = frame;
        this.parent = parent;
        this.shared = shared;
    }

    /**
     * The outermost scope of an evaluation.
     *
     * @param names  the value of each name the expression is given (null for one it does not have)
     * @param errors receives each error's message; null to ignore them
     * @param depth  the depth of the expression's tree, the nesting its evaluation starts with
     */
    static Env root(Function<String, Object> names, Consumer<String> errors, int depth) {
        var shared = new Shared(names, errors);
        shared.nesting = depth;
        return new Env(Map.of(), null, shared);
    }

    /** A scope inside this one, binding the names of {@code frame}, which it reads as it is when a name is looked up. */
    Env with(Map<String, ?> frame) {
        return new Env(frame, this, shared);
    }

    /** The value of a name: the innermost binding of it, else the value the expression is given for it. */
    Object lookup(String name) {
        for (var env = this; env != null; env = env.parent) {
            if (env.frame.containsKey(name)) {
                return env.frame.get(name);
            }
        }
        return shared.names.apply(name);
    }

    /** Whether errors are reported, so that a message is worth building. */
    boolean reporting() {
        return shared.errors != null;
    }

    /** Reports an error (DMN 1.5, 10.3.2.15), and gives null, FEEL's value for it. */
    Object error(String message) {
        if (shared.errors != null) {
            shared.errors.accept(message);
        }
        return null;
    }

    /** Counts a step; false once there have been more than {@link #MAX_STEPS} in this evaluation, with an error reported. */
    boolean step() {
        if (++shared.steps <= MAX_STEPS) {
            return true;
        }
        if (shared.steps == MAX_STEPS + 1) {
            error("the evaluation takes more than " + MAX_STEPS + " steps");
        }
        return false;
    }

    /** The receiver of errors' messages, or null if they are ignored. */
    Consumer<String> errors() {
        return shared.errors;
    }

    /** Enters a function body of that depth; false, entering nothing, if that is more nesting than {@link #MAX_NESTING}. */
    boolean enter(int depth) {
        if (shared.nesting + depth > MAX_NESTING) {
            return false;
        }
        shared.nesting += depth;
        return true;
    }

    /** Leaves a function body of that depth. */
    void leave(int depth) {
        shared.nesting -= depth;
    }
}
