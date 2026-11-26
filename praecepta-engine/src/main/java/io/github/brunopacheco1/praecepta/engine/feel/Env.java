/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where an expression is evaluated: a {@linkplain Scope scope} of names (DMN 1.5, 10.3.2.11), and what one evaluation
 * shares: where its errors go, and the limits that keep a small expression from taking unbounded time, memory or stack.
 * A function value keeps the scope it was defined in, not the evaluation: invoked inside an evaluation, its body counts
 * against that evaluation's limits and reports to it; invoked from Java, it is an evaluation of its own.
 */
final class Env implements FeelValues.Budget {

    /**
     * At most this many steps in one evaluation: iterations of every {@code for}, {@code some} and {@code every} and
     * the elements of the ranges they iterate, function invocations, the elements a filter tests, a path projects or
     * {@code in} looks through, and the values equality and {@code instance of} look at. So
     * {@code for i in 1..1000000000 return i}, or a function that invokes itself twice over, gives null, with an error,
     * rather than run out of memory or time.
     */
    static final int MAX_STEPS = 1_000_000;
    /**
     * At most this many characters in all the strings {@code +} makes in one evaluation: a context whose entries each
     * join the one before with itself would otherwise double its length entry after entry, and a loop would make many
     * such strings.
     */
    static final int MAX_CHARACTERS = 10_000_000;
    /**
     * At most this much nesting at once, counted as the depth of the expression being evaluated plus that of each
     * function body being invoked: a function that invokes itself without end gives null, with an error, rather than
     * overflow the thread's stack (a 512 KiB stack holds it, with room).
     */
    static final int MAX_NESTING = 800;
    /** At most this many errors one evaluation reports; those beyond are counted, and reported as one more at its end. */
    static final int MAX_ERRORS = 100;

    /**
     * The names in scope: the frames the expression's constructs bind (iteration variables, parameters, context entries,
     * a filter's {@code item} and the entries of the item it tests), innermost first, then the values the evaluation is
     * given, a {@link Number} among them read as the {@code BigDecimal} FEEL takes it as. A function value keeps it.
     */
    static final class Scope {
        private final Map<String, ?> frame;
        private final Scope parent;
        private final Function<String, Object> names;

        private Scope(Map<String, ?> frame, Scope parent, Function<String, Object> names) {
            this.frame = frame;
            this.parent = parent;
            this.names = names;
        }

        /** A scope inside this one, binding the names of {@code frame}, which it reads as it is when a name is looked up. */
        Scope with(Map<String, ?> frame) {
            return new Scope(frame, this, names);
        }

        Object lookup(String name) {
            for (var scope = this; scope != null; scope = scope.parent) {
                if (scope.frame.containsKey(name)) {
                    return scope.frame.get(name);
                }
            }
            return FeelNumbers.normalise(names.apply(name));
        }
    }

    /**
     * What one evaluation counts against its limits: steps, nesting and characters. An evaluation that an embedded
     * expression starts, as a decision table within a boxed expression evaluates its FEEL from within the FEEL around
     * it, counts against the one that embeds it, so that a business knowledge model whose table invokes it again stops
     * at the limits rather than overflow the thread's stack.
     */
    private static final class Budget {
        private int steps;
        private int nesting;
        private long characters;
    }

    /** What every scope of one evaluation shares: where its errors go, and the budget it counts against. */
    private static final class Shared {
        private final Consumer<String> errors;
        private final Budget budget;
        /** The nesting this evaluation started with, given back when it finishes. */
        private int depth;
        private int reported;
        private int dropped;

        Shared(Consumer<String> errors, Budget budget) {
            this.errors = errors;
            this.budget = budget == null ? new Budget() : budget;
        }
    }

    /**
     * The budget of the evaluation that is evaluating an embedded expression on each thread ({@link #embedding}), which
     * an evaluation the embedded expression starts counts against; set only for the time of that call.
     */
    private static final ThreadLocal<Budget> EMBEDDING = new ThreadLocal<>();

    private static final Scope EMPTY = new Scope(Map.of(), null, name -> null);

    private final Scope scope;
    private final Shared shared;
    /** Whether this part of the evaluation reports no error, as a filter's probe of its condition does not. */
    private final boolean quiet;

    private Env(Scope scope, Shared shared, boolean quiet) {
        this.scope = scope;
        this.shared = shared;
        this.quiet = quiet;
    }

    /**
     * The outermost scope of an evaluation.
     *
     * @param names  the value of each name the expression is given (null for one it does not have)
     * @param errors receives each error's message; null to ignore them
     * @param depth  the depth of the expression's tree, the nesting its evaluation starts with
     */
    static Env root(Function<String, Object> names, Consumer<String> errors, int depth) {
        return new Env(new Scope(Map.of(), null, names), start(errors, depth), false);
    }

    /**
     * An evaluation of its own with no names in scope, as a function invoked from Java is; one an embedded expression
     * starts counts against the limits of the evaluation that embeds it.
     */
    static Env detached(Consumer<String> errors) {
        return new Env(EMPTY, start(errors, 0), false);
    }

    /** Starts an evaluation, counting against that of the embedded expression being evaluated on this thread, if any. */
    private static Shared start(Consumer<String> errors, int depth) {
        var shared = new Shared(errors, EMBEDDING.get());
        shared.depth = depth;
        shared.budget.nesting += depth;
        return shared;
    }

    /**
     * Evaluates an embedded expression ({@link FeelExpression.Embedded}, a decision table within a boxed expression) in
     * this evaluation: the evaluations it starts on this thread count against this one's limits.
     */
    Object embedding(Supplier<Object> evaluation) {
        var previous = EMBEDDING.get();
        EMBEDDING.set(shared.budget);
        try {
            return evaluation.get();
        } finally {
            if (previous == null) {
                EMBEDDING.remove();
            } else {
                EMBEDDING.set(previous);
            }
        }
    }

    /** A scope inside this one, binding the names of {@code frame}, which it reads as it is when a name is looked up. */
    Env with(Map<String, ?> frame) {
        return new Env(scope.with(frame), shared, quiet);
    }

    /** The names in scope, as a function value keeps them. */
    Scope scope() {
        return scope;
    }

    /** A scope of names, in this evaluation: a function's body invoked from it. */
    Env in(Scope names) {
        return new Env(names, shared, quiet);
    }

    /** This scope in a part of the evaluation that reports no error, but counts against its limits. */
    Env quiet() {
        return quiet ? this : new Env(scope, shared, true);
    }

    /** The value of a name: the innermost binding of it, else the value the expression is given for it. */
    Object lookup(String name) {
        return scope.lookup(name);
    }

    /** Whether errors are reported, so that a message is worth building. */
    boolean reporting() {
        return shared.errors != null && !quiet;
    }

    /** Reports an error (DMN 1.5, 10.3.2.16), and gives null, FEEL's value for it. */
    Object error(String message) {
        if (reporting()) {
            if (shared.reported < MAX_ERRORS) {
                shared.reported++;
                shared.errors.accept(message);
            } else {
                shared.dropped++;
            }
        }
        return null;
    }

    /**
     * Reports an error whose message is built only if it is reported: where errors are, and fewer than
     * {@link #MAX_ERRORS} have been; gives null.
     */
    Object error(Supplier<String> message) {
        if (reporting()) {
            if (shared.reported < MAX_ERRORS) {
                shared.reported++;
                shared.errors.accept(message.get());
            } else {
                shared.dropped++;
            }
        }
        return null;
    }

    /**
     * Ends the evaluation: reports how many errors were beyond {@link #MAX_ERRORS}, if any were, and gives back the
     * nesting it started with to the evaluation it counted against.
     */
    void finish() {
        if (shared.dropped > 0 && shared.errors != null) {
            shared.errors.accept("and %d more errors".formatted(shared.dropped));
            shared.dropped = 0;
        }
        shared.budget.nesting -= shared.depth;
        shared.depth = 0;
    }

    /** Counts a step; false once there have been more than {@link #MAX_STEPS} in this evaluation, with an error reported. */
    boolean step() {
        return step(1);
    }

    /** Counts that many steps, as {@link #step()} does. */
    boolean step(long count) {
        if (!stopped() && count <= MAX_STEPS - (long) shared.budget.steps) {
            shared.budget.steps += (int) count;
            return true;
        }
        stop("the evaluation takes more than " + MAX_STEPS + " steps");
        return false;
    }

    /**
     * Stops the evaluation, once: every step after it fails, and its value is null ({@link #result}). The error is
     * reported even from a part of it that reports none, as it is the whole evaluation's.
     */
    private void stop(String message) {
        if (!stopped()) {
            shared.budget.steps = MAX_STEPS + 1;
            new Env(scope, shared, false).error(message);
        }
    }

    /**
     * Whether the evaluation has gone beyond {@link #MAX_STEPS} steps or {@link #MAX_CHARACTERS} characters, after which
     * its value is null.
     */
    boolean stopped() {
        return shared.budget.steps > MAX_STEPS;
    }

    /** The evaluation's value: its root's, or null if it stopped on the way, beyond its limits. */
    Object result(Object value) {
        return stopped() ? null : value;
    }

    @Override
    public boolean spend() {
        return step(1);
    }

    /**
     * Counts the characters of a string {@code +} makes; false, stopping the evaluation with an error, if that makes more
     * than {@link #MAX_CHARACTERS} in this evaluation.
     */
    boolean characters(long count) {
        if (!stopped() && count <= MAX_CHARACTERS - shared.budget.characters) {
            shared.budget.characters += count;
            return true;
        }
        stop("the strings + makes would hold more than %d characters".formatted(MAX_CHARACTERS));
        return false;
    }

    /** Enters a function body of that depth; false, entering nothing, if that is more nesting than {@link #MAX_NESTING}. */
    boolean enter(int depth) {
        if (shared.budget.nesting + depth > MAX_NESTING) {
            return false;
        }
        shared.budget.nesting += depth;
        return true;
    }

    /** Leaves a function body of that depth. */
    void leave(int depth) {
        shared.budget.nesting -= depth;
    }
}
