/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The value of a function definition (DMN 1.5, 10.3.2.13): its parameters and body, and the scope of names it was
 * defined in, which its body is evaluated in, with the parameters bound to the arguments. It keeps the names alone, not
 * the evaluation that defined it: invoked inside an evaluation, its body counts against that evaluation's limits and
 * reports its errors there; invoked from Java ({@link #invoke}), it is an evaluation of its own, so any number of
 * threads may invoke it at once. An argument of a declared type is bound as 10.3.2.9.4 binds an invocation's
 * parameters: as it is when it conforms, a single value to a list as a list of one, a list of one to a single value as
 * its element, a date to a date and time at UTC midnight; any other non-conforming argument makes the invocation null,
 * with an error. Its type is its parameters' declared types and its body's type, typed with the values its scope holds.
 *
 * <p>Two are equal (Table 53: "same parameters, body and scope") when their definitions are equal, parameter for
 * parameter, declared type for declared type and node for node of their bodies, and they were defined in the same
 * scope: the same frames of names, as two definitions evaluated side by side in one context or one expression are.
 */
final class Closure implements FeelFunction {

    private static final Object NOT_BOUND = new Object();

    private final Node.FunctionDefinition definition;
    private final Env.Scope scope;
    private volatile FeelType type;

    Closure(Node.FunctionDefinition definition, Env.Scope scope) {
        this.definition = definition;
        this.scope = scope;
    }

    @Override
    public List<String> parameters() {
        return definition.parameters();
    }

    @Override
    public FeelType type() {
        var known = type;
        if (known == null) {
            known = definition.type(TypeEnv.root(name -> FeelValues.typeOf(scope.lookup(name)), null));
            type = known;
        }
        return known;
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
        var parameters = definition.parameters();
        if (arguments.size() != parameters.size()) {
            return caller.error(() -> "the function takes %d arguments, not %d".formatted(parameters.size(), arguments.size()));
        }
        var bound = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parameters.size(); i++) {
            var argument = arguments.get(i);
            var declared = definition.types().get(i);
            var value = bind(argument, declared, caller);
            if (value == NOT_BOUND) {
                return caller.error(() -> "argument %s is not a %s".formatted(FeelValues.text(argument), declared.feelName()));
            }
            bound.put(parameters.get(i), value);
        }
        int depth = definition.depth() + 1;
        if (!caller.step()) {
            return null;
        }
        if (!caller.enter(depth)) {
            return caller.error("functions invoke one another more deeply than Praecepta evaluates (" + Env.MAX_NESTING + " levels)");
        }
        try {
            return definition.body().evaluate(caller.in(scope.with(bound)));
        } finally {
            caller.leave(depth);
        }
    }

    /** An argument bound to a parameter's declared type (DMN 1.5, 10.3.2.9.4), or {@link #NOT_BOUND}. */
    private static Object bind(Object value, FeelType type, Env budget) {
        if (value == null || type instanceof FeelType.Any || FeelValues.isInstance(value, type, budget)) {
            return value;
        }
        if (type instanceof FeelType.ListOf(var element) && FeelValues.isInstance(value, element, budget)) {
            return List.of(value);
        }
        if (value instanceof List<?> list && list.size() == 1 && FeelValues.isInstance(list.getFirst(), type, budget)) {
            return list.getFirst();
        }
        if (type.equals(FeelType.of(ColumnType.DATE_TIME)) && value instanceof LocalDate date) {
            return date.atStartOfDay(ZoneOffset.UTC);
        }
        return NOT_BOUND;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Closure closure && closure.scope == scope && Node.sameTree(closure.definition, definition);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(scope) + definition.parameters().hashCode();
    }

    @Override
    public String toString() {
        return "function(" + String.join(", ", definition.parameters()) + ")";
    }
}
