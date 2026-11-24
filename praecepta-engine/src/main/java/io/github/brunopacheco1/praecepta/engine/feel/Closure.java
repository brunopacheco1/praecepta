/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The value of a function definition (DMN 1.5, 10.3.2.13): its parameters and body, and the scope it was defined in,
 * which its body is evaluated in, with the parameters bound to the arguments. An argument of a declared type is bound
 * as 10.3.2.9.4 binds an invocation's parameters: as it is when it conforms, a single value to a list as a list of
 * one, a list of one to a single value as its element, a date to a date and time at UTC midnight; any other
 * non-conforming argument makes the invocation null, with an error. Its type is its parameters' declared types and its
 * body's type, typed with the values its scope holds.
 */
final class Closure implements FeelFunction {

    private final Node.FunctionDefinition definition;
    private final Env scope;
    private volatile FeelType type;

    Closure(Node.FunctionDefinition definition, Env scope) {
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

    /**
     * Invokes it from outside an evaluation: its body is evaluated in the scope it was defined in, in which the
     * evaluation that made it reports its errors.
     */
    @Override
    public Object invoke(List<Object> arguments, Consumer<String> errors) {
        return call(arguments, scope);
    }

    /** Invokes it from an evaluation, whose nesting it adds to. */
    Object call(List<Object> arguments, Env caller) {
        var parameters = definition.parameters();
        if (arguments.size() != parameters.size()) {
            return caller.error("the function takes %d arguments, not %d".formatted(parameters.size(), arguments.size()));
        }
        var bound = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parameters.size(); i++) {
            var value = bind(arguments.get(i), definition.types().get(i));
            if (value == NOT_BOUND) {
                return caller.error("argument %s is not a %s".formatted(FeelValues.text(arguments.get(i)), definition.types().get(i).feelName()));
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
            return definition.body().evaluate(scope.with(bound));
        } finally {
            caller.leave(depth);
        }
    }

    private static final Object NOT_BOUND = new Object();

    /** An argument bound to a parameter's declared type (DMN 1.5, 10.3.2.9.4), or {@link #NOT_BOUND}. */
    private static Object bind(Object value, FeelType type) {
        if (value == null || FeelValues.isInstance(value, type)) {
            return value;
        }
        if (type instanceof FeelType.ListOf(var element) && FeelValues.isInstance(value, element)) {
            return List.of(value);
        }
        if (value instanceof List<?> list && list.size() == 1 && FeelValues.isInstance(list.getFirst(), type)) {
            return list.getFirst();
        }
        if (type.equals(FeelType.of(ColumnType.DATE_TIME)) && value instanceof LocalDate date) {
            return date.atStartOfDay(ZoneOffset.UTC);
        }
        return NOT_BOUND;
    }

    @Override
    public String toString() {
        return "function(" + String.join(", ", definition.parameters()) + ")";
    }
}
