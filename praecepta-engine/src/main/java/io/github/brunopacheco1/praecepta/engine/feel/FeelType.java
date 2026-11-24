/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The type of a value in a decision model: of an input data, of a decision's result, or of a value one decision
 * passes to another. FEEL's types (DMN 1.5, 10.3.2.9): a {@linkplain Simple simple}
 * type ({@code string}, {@code number}, {@code date}...), a {@linkplain ListOf list} of a type, a
 * {@linkplain Context context} of named components (a structured item definition, or the result of a decision
 * table with several outputs), a {@linkplain RangeOf range} of a type, a {@linkplain FunctionOf function}, the
 * {@linkplain Null type of null}, or {@linkplain Any any} type, when the model declares none.
 *
 * <p>Its values, as {@code DecisionGraph} takes and gives them: a simple type's are those of its
 * {@link ColumnType} ({@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate}, {@code LocalTime},
 * {@code OffsetTime} or {@link ZonedTime}, {@code LocalDateTime} or {@code ZonedDateTime}, {@code Duration},
 * {@link YearsAndMonths}), a list's a {@code List}, a context's a {@code Map} from component name to value, a range's
 * a {@link FeelRange}, a function's a {@link FeelFunction}; any of them may be null, FEEL's null.
 *
 * <p>Later releases may add types, so a {@code switch} over a {@code FeelType} should keep a {@code default} branch.
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

    /** The type of FEEL's null (DMN 1.5, 10.3.2.9.1, {@code Null}): an expression of this type is null whatever its inputs. */
    FeelType NULL = new Null();

    /** A range whose endpoints are of {@code element}'s type (FEEL's {@code range<T>}, DMN 1.5, 10.3.2.7). */
    record RangeOf(FeelType element) implements FeelType {
        public RangeOf {
            Objects.requireNonNull(element, "element");
        }
    }

    /**
     * A function (FEEL's {@code function<T1, T2>->R}, DMN 1.5, 10.3.2.9.1): of parameters of these types, giving a value
     * of {@code result}'s type.
     *
     * @param parameters each parameter's type, in order
     * @param result     the type of its value
     */
    record FunctionOf(List<FeelType> parameters, FeelType result) implements FeelType {
        public FunctionOf {
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(result, "result");
        }
    }

    /** The type of null alone (FEEL's {@code Null}), which conforms to every type. */
    record Null() implements FeelType {
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
     * The type of a component of a value of this type, as a path reads it ({@code Loan.principal}): a context's
     * component; of a list of contexts, the list of their components (DMN 1.5, 10.3.2.5: a selection on "a list of
     * contexts" is a list of selections); any type in a value of any type, or of a list of any type; null if a value of
     * this type has no such component.
     */
    default FeelType component(String name) {
        return switch (this) {
            case Context(var components) -> components.get(name);
            case ListOf(Context(var components)) -> components.containsKey(name) ? new ListOf(components.get(name)) : null;
            case ListOf(Any any) -> new ListOf(ANY);
            case Any any -> ANY;
            default -> null;
        };
    }

    /**
     * Whether every value of this type conforms to {@code target} (DMN 1.5, 10.3.2.9.2): {@code Null} to every type;
     * every type to {@code Any}; a type to an equivalent one; a list to a list whose elements' type its elements' type
     * conforms to, and a range likewise; a context to a context each of whose components it has, of a type that
     * conforms; a function to a function of as many parameters, each of the target's parameter types conforming to its
     * own (parameters are contravariant), whose result type conforms to the target's. {@code Any} conforms only to
     * {@code Any}: a value of any type may or may not conform to another, which only the value tells.
     */
    default boolean conformsTo(FeelType target) {
        if (this instanceof Null || target instanceof Any) {
            return true;
        }
        return switch (target) {
            case ListOf(var element) -> this instanceof ListOf(var own) && own.conformsTo(element);
            case RangeOf(var element) -> this instanceof RangeOf(var own) && own.conformsTo(element);
            case Context(var components) -> this instanceof Context(var own)
                    && components.entrySet().stream().allMatch(component -> own.containsKey(component.getKey())
                            && own.get(component.getKey()).conformsTo(component.getValue()));
            case FunctionOf(var parameters, var result) -> this instanceof FunctionOf(var own, var ownResult)
                    && own.size() == parameters.size() && ownResult.conformsTo(result)
                    && java.util.stream.IntStream.range(0, own.size()).allMatch(i -> parameters.get(i).conformsTo(own.get(i)));
            default -> equals(target);
        };
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
            case RangeOf(var element) -> "range<" + element.feelName() + ">";
            case FunctionOf(var parameters, var result) -> parameters.stream().map(FeelType::feelName)
                    .collect(Collectors.joining(", ", "function<", ">->")) + result.feelName();
            case Null nothing -> "Null";
            case Any any -> "Any";
        };
    }
}
