/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The type of a value in a decision model: of an input data, of a decision's result, or of a value one decision
 * passes to another. FEEL's types, as far as Praecepta has them (DMN 1.5, 10.3.2.9): a {@linkplain Simple simple}
 * type ({@code string}, {@code number}, {@code date}...), a {@linkplain ListOf list} of a type, a
 * {@linkplain Context context} of named components (a structured item definition, or the result of a decision
 * table with several outputs), or {@linkplain Any any} type, when the model declares none.
 *
 * <p>Its values, as {@code DecisionGraph} takes and gives them: a simple type's are those of its
 * {@link ColumnType} ({@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate}, {@code LocalTime},
 * {@code OffsetTime} or {@link ZonedTime}, {@code LocalDateTime} or {@code ZonedDateTime}, {@code Duration},
 * {@link YearsAndMonths}), a list's a {@code List}, a context's a {@code Map} from component name to value; any of
 * them may be null, FEEL's null.
 */
public sealed interface FeelType {

    /** Any type: a value whose type the model does not declare. */
    FeelType ANY = new Any();

    /** A simple type: one of the types a decision table's column may have. */
    record Simple(ColumnType type) implements FeelType {
        public Simple {
            Objects.requireNonNull(type, "type");
        }
    }

    /** A list whose elements are of {@code element}'s type (FEEL's {@code list<T>}). */
    record ListOf(FeelType element) implements FeelType {
        public ListOf {
            Objects.requireNonNull(element, "element");
        }
    }

    /**
     * A context (FEEL's {@code context<k1: T1, k2: T2>}): named components, in the order they are declared.
     *
     * @param components each component's type, by name
     */
    record Context(Map<String, FeelType> components) implements FeelType {
        public Context {
            components.forEach((name, type) -> {
                Objects.requireNonNull(name, "component name");
                Objects.requireNonNull(type, "component type");
            });
            components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        }
    }

    /** Any type (FEEL's {@code Any}): the type of a value whose type the model leaves undeclared. */
    record Any() implements FeelType {
    }

    /** The simple type of a column type. */
    static FeelType of(ColumnType type) {
        return new Simple(type);
    }

    /** A list of a column type. */
    static FeelType listOf(ColumnType type) {
        return new ListOf(new Simple(type));
    }

    /**
     * The type as FEEL writes it (DMN 1.5, 10.3.2.9.1): {@code number}, {@code date and time},
     * {@code list<string>}, {@code context<discount: number, grade: string>}, {@code Any}.
     */
    default String feelName() {
        return switch (this) {
            case Simple(var type) -> type.feelName();
            case ListOf(var element) -> "list<" + element.feelName() + ">";
            case Context(var components) -> components.entrySet().stream()
                    .map(component -> component.getKey() + ": " + component.getValue().feelName())
                    .collect(Collectors.joining(", ", "context<", ">"));
            case Any any -> "Any";
        };
    }
}
