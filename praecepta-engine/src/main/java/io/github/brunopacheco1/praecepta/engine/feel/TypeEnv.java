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
 * The scope an expression is typed in, as {@link Env} is the one it is evaluated in: the types of the names its
 * constructs bind, innermost first, then those of the names it is given. It carries where the reasons an expression is
 * always null go.
 */
final class TypeEnv {

    private final Map<String, FeelType> frame;
    private final TypeEnv parent;
    private final Function<String, FeelType> names;
    private final Consumer<String> warnings;

    private TypeEnv(Map<String, FeelType> frame, TypeEnv parent, Function<String, FeelType> names, Consumer<String> warnings) {
        this.frame = frame;
        this.parent = parent;
        this.names = names;
        this.warnings = warnings;
    }

    /**
     * @param names    the type of each name the expression is given; null for a name of unknown type
     * @param warnings receives why a part of the expression is always null; null to ignore it
     */
    static TypeEnv root(Function<String, FeelType> names, Consumer<String> warnings) {
        return new TypeEnv(Map.of(), null, names, warnings);
    }

    TypeEnv with(Map<String, FeelType> bound) {
        return new TypeEnv(bound, this, names, warnings);
    }

    /** A name's type: its innermost binding's, else the type it is given, else {@link FeelType#ANY}. */
    FeelType lookup(String name) {
        for (var env = this; env != null; env = env.parent) {
            if (env.frame.containsKey(name)) {
                return env.frame.get(name);
            }
        }
        var type = names.apply(name);
        return type == null ? FeelType.ANY : type;
    }

    /** Records why a part of the expression is always null, and gives {@link FeelType#NULL}. */
    FeelType alwaysNull(String reason) {
        if (warnings != null) {
            warnings.accept(reason);
        }
        return FeelType.NULL;
    }

    /** The type of a value that is one of two types: the type if they are one, the other if one is {@code Null}, else {@code Any}. */
    static FeelType join(FeelType a, FeelType b) {
        if (a.equals(b) || b instanceof FeelType.Null) {
            return a;
        }
        return a instanceof FeelType.Null ? b : FeelType.ANY;
    }
}
