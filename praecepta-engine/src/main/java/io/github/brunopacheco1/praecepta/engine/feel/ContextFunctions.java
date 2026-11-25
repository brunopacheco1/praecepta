/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.context;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.list;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.steps;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * FEEL's context functions (DMN 1.5, 10.3.4.10): an entry's value, a context's entries as a list of
 * {@code {key, value}} contexts and back, and contexts with an entry put or merged in. A context keeps its entries in
 * order: one put in or merged in replaces an entry of its key where it is, or comes after the others.
 */
final class ContextFunctions {

    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String CONTEXT = "context";
    private static final String KEYS = "keys";
    /** The signature {@code context put(context, key, value)}, of one key. */
    private static final int KEY_SIGNATURE = 0;

    private ContextFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        add.accept(BuiltInFunction.of("get value", FeelType.ANY, fixed(2), (arguments, env) -> {
            var context = context(arguments.get(0), "m");
            return context.get(Arguments.string(arguments.get(1), KEY));
        }, "m", KEY));
        add.accept(BuiltInFunction.of("get entries", new FeelType.ListOf(FeelType.ANY), fixed(1), (arguments, env) -> {
            var context = context(arguments.getFirst(), "m");
            steps(env, context.size());
            var entries = new ArrayList<Object>(context.size());
            context.forEach((key, value) -> {
                var entry = new LinkedHashMap<String, Object>();
                entry.put(KEY, key);
                entry.put(VALUE, value);
                entries.add(Collections.unmodifiableMap(entry));
            });
            return Arguments.result(entries);
        }, "m"));
        add.accept(BuiltInFunction.of(CONTEXT, FeelType.ANY, fixed(1), ContextFunctions::fromEntries, "entries"));
        add.accept(BuiltInFunction.of("context put", FeelType.ANY, fixed(3), (BuiltInFunction.Overloaded) ContextFunctions::contextPut,
                List.of(List.of(CONTEXT, KEY, VALUE), List.of(CONTEXT, KEYS, VALUE))));
        add.accept(BuiltInFunction.of("context merge", FeelType.ANY, fixed(1), (arguments, env) -> {
            var merged = new LinkedHashMap<String, Object>();
            for (var context : list(arguments.getFirst(), "contexts")) {
                if (!(context instanceof Map<?, ?> entries)) {
                    throw invalid("takes a list of contexts, not one holding " + FeelValues.text(context));
                }
                steps(env, entries.size());
                entries.forEach((key, value) -> merged.put(String.valueOf(key), value));
            }
            return Collections.unmodifiableMap(merged);
        }, "contexts"));
    }

    /** {@code context(entries)}: the context of a list of {@code {key, value}} contexts, of different keys. */
    private static Object fromEntries(List<Object> arguments, Env env) {
        var entries = list(arguments.getFirst(), "entries");
        steps(env, entries.size());
        var context = new LinkedHashMap<String, Object>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> pair) || !(pair.get(KEY) instanceof String key) || !pair.containsKey(VALUE)) {
                throw invalid("takes a list of contexts of a string 'key' and a 'value', not one holding " + FeelValues.text(entry));
            }
            if (context.containsKey(key)) {
                throw invalid("takes entries of different keys, not two of \"%s\"".formatted(key));
            }
            context.put(key, pair.get(VALUE));
        }
        return Collections.unmodifiableMap(context);
    }

    /** {@code context put(context, key, value)} or {@code context put(context, keys, value)}. */
    private static Object contextPut(List<Object> arguments, int signature, Env env) {
        var context = context(arguments.get(0), CONTEXT);
        List<?> keys;
        if (signature == KEY_SIGNATURE || signature == BuiltInFunction.POSITIONAL && !(arguments.get(1) instanceof List<?>)) {
            keys = List.of(Arguments.string(arguments.get(1), KEY));
        } else {
            keys = Arguments.list(arguments.get(1), KEYS);
            if (keys.isEmpty() || keys.stream().anyMatch(k -> !(k instanceof String))) {
                throw Arguments.invalid("a non-empty list of strings", arguments.get(1), KEYS);
            }
        }
        return put(context, keys, arguments.get(2), env);
    }

    /** A context with a value put at a path of keys: each key but the last names a context, or none, in the one before. */
    private static Map<String, Object> put(Map<?, ?> context, List<?> keys, Object value, Env env) {
        steps(env, context.size());
        var copy = new LinkedHashMap<String, Object>();
        context.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
        var key = (String) keys.getFirst();
        if (keys.size() == 1) {
            copy.put(key, value);
        } else {
            var inner = copy.get(key);
            if (inner != null && !(inner instanceof Map<?, ?>)) {
                throw invalid("takes keys that name contexts, but \"%s\" names %s".formatted(key, FeelValues.text(inner)));
            }
            copy.put(key, put(inner == null ? Map.of() : (Map<?, ?>) inner, keys.subList(1, keys.size()), value, env));
        }
        return Collections.unmodifiableMap(copy);
    }
}
