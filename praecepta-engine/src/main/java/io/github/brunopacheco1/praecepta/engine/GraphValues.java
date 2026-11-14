/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * The values a {@link DecisionGraph} passes between its elements: input data as the caller gives them, made into
 * FEEL's values of their type once per evaluation ({@link #normalise}), and read by path ({@link #read}).
 */
final class GraphValues {

    private GraphValues() {
    }

    /**
     * An input data's value as the graph passes it on: a value of its type in the form {@link FeelType} describes, a
     * number as a {@code BigDecimal}, a years and months duration as {@link YearsAndMonths}, a time in a zone with one
     * fixed offset as the time at that offset (DMN 1.5, Table 72), a list as an unmodifiable {@code List}, a context
     * as an unmodifiable {@code Map} of its declared components. Values of a simple type may also be given as text,
     * as a decision table's map-based inputs are ({@code 42}, {@code 2025-01-31}); blank text, and null, are missing.
     * A value of {@link FeelType#ANY any type} is passed on as it is, a number as a {@code BigDecimal}, a
     * {@code Collection} as an unmodifiable {@code List} and a {@code Map} as an unmodifiable {@code Map}, in which each
     * element and component is passed on the same way, up to {@value #MAX_DEPTH} levels deep.
     *
     * @throws InvalidInputException if the value is not one of the type
     */
    static Object normalise(Object raw, FeelType type, String name) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case FeelType.Simple(var simple) -> simple(raw, simple, name);
            case FeelType.ListOf(var element) -> list(raw, element, name);
            case FeelType.Context(var components) -> context(raw, components, name);
            case FeelType.Any any -> anyValue(raw, name, 0);
        };
    }

    /** The deepest lists and contexts a value of any type may nest: {@code DmnReader.MAX_TYPE_DEPTH}, with room. */
    static final int MAX_DEPTH = 64;

    /**
     * A value of any type, as FEEL's: numbers as {@code BigDecimal}s, lists and contexts copied, unmodifiable.
     *
     * @param depth how many lists and contexts enclose it, checked before going deeper, so that no value recurses
     *              without limit
     */
    private static Object anyValue(Object raw, String name, int depth) {
        if (depth > MAX_DEPTH) {
            throw new InvalidInputException(name, null, "nests more than %d lists and contexts".formatted(MAX_DEPTH));
        }
        return switch (raw) {
            case null -> null;
            case Number number -> number(number, name);
            case Collection<?> collection -> {
                var values = new ArrayList<>(collection.size());
                for (var item : collection) {
                    values.add(anyValue(item, name, depth + 1));
                }
                yield Collections.unmodifiableList(values);
            }
            case Map<?, ?> map -> {
                var values = new LinkedHashMap<String, Object>();
                map.forEach((key, value) -> values.put(String.valueOf(key), anyValue(value, name + "." + key, depth + 1)));
                yield Collections.unmodifiableMap(values);
            }
            default -> raw;
        };
    }

    private static Object simple(Object raw, ColumnType type, String name) {
        if (raw instanceof String text && type != ColumnType.STRING) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return FeelLiterals.value(text, type);
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException(name, text, "is not a valid " + type.feelName());
            }
        }
        var value = switch (type) {
            case STRING -> raw instanceof String ? raw : null;
            case NUMBER -> raw instanceof Number number ? number(number, name) : null;
            case BOOLEAN -> raw instanceof Boolean ? raw : null;
            case DATE -> raw instanceof LocalDate ? raw : null;
            case TIME -> raw instanceof LocalTime || raw instanceof OffsetTime || raw instanceof ZonedTime
                    ? FeelTemporals.withFixedOffset(raw) : null;
            case DATE_TIME -> dateTime(raw);
            case DAY_TIME_DURATION -> raw instanceof Duration ? raw : null;
            case YEAR_MONTH_DURATION -> yearsAndMonths(raw, name);
        };
        if (value == null) {
            throw new InvalidInputException(name, raw, "is not a " + type.feelName());
        }
        return value;
    }

    private static Object dateTime(Object raw) {
        return switch (raw) {
            case LocalDateTime dateTime -> dateTime;
            case ZonedDateTime dateTime -> dateTime;
            case OffsetDateTime dateTime -> dateTime.toZonedDateTime();
            default -> null;
        };
    }

    private static Object yearsAndMonths(Object raw, String name) {
        return switch (raw) {
            case YearsAndMonths duration -> duration;
            case Period period -> {
                try {
                    yield YearsAndMonths.of(period);
                } catch (IllegalArgumentException e) {
                    throw new InvalidInputException(name, period, "has days; a years and months duration has only years and months");
                }
            }
            default -> null;
        };
    }

    static BigDecimal number(Number raw, String name) {
        return switch (raw) {
            case BigDecimal decimal -> decimal;
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case BigInteger big -> new BigDecimal(big);
            case Number number -> {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new InvalidInputException(name, number, "is not a finite number");
                }
                yield BigDecimal.valueOf(d);
            }
        };
    }

    private static List<Object> list(Object raw, FeelType element, String name) {
        if (!(raw instanceof Collection<?> collection)) {
            throw new InvalidInputException(name, raw, "is not a list");
        }
        var values = new ArrayList<>(collection.size());
        for (var item : collection) {
            values.add(normalise(item, element, name));
        }
        return Collections.unmodifiableList(values);
    }

    private static Map<String, Object> context(Object raw, Map<String, FeelType> components, String name) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidInputException(name, raw, "is not a context (a map of its components)");
        }
        var values = new LinkedHashMap<String, Object>();
        components.forEach((component, type) -> values.put(component, normalise(map.get(component), type, name + "." + component)));
        return Collections.unmodifiableMap(values);
    }

    /**
     * The value at a path in a value ({@code Pricing.discount} is the path {@code discount} in {@code Pricing}): a
     * context's component, null if it has none, and of a list, the list of each element's (DMN 1.5, 10.3.2.5: "a
     * selection using the "." operator with a list of contexts on its left hand side returns a list of selections"),
     * null for an element that is not a context. The path into a missing value is missing. It loops over the path, so
     * however long the path, it takes no stack.
     */
    static Object read(Object value, String[] path) {
        var current = value;
        for (var component : path) {
            current = switch (current) {
                case null -> null;
                case Map<?, ?> context -> context.get(component);
                case List<?> list -> {
                    var components = new ArrayList<>(list.size());
                    for (var element : list) {
                        components.add(element instanceof Map<?, ?> context ? context.get(component) : null);
                    }
                    yield Collections.unmodifiableList(components);
                }
                default -> null;
            };
        }
        return current;
    }

    /**
     * Whether a value of a column of single values may be given to a column of that type: a value of its Java type,
     * as {@link #normalise} makes them. Checked for values whose type the model does not declare.
     */
    static boolean isValueOf(Object value, ColumnType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof LocalDate;
            case TIME -> value instanceof LocalTime || value instanceof OffsetTime || value instanceof ZonedTime;
            case DATE_TIME -> value instanceof LocalDateTime || value instanceof ZonedDateTime;
            case DAY_TIME_DURATION -> value instanceof Duration;
            case YEAR_MONTH_DURATION -> value instanceof YearsAndMonths || value instanceof Period;
        };
    }

    /** How a decision's value is bound to its variable (DMN 1.5, 10.3.2.9.4, binding contexts). */
    enum Binding {
        /** As it is: of the type the decision declares, or of its own if it declares none. */
        AS_IS,
        /** A date as a date and time at UTC midnight ("from date to date and time"). */
        DATE_TO_DATE_TIME,
        /** As a list of one ("to singleton list"). */
        SINGLETON_LIST,
        /** A list of one value as that value, any other list as null ("from singleton list", then "conforms to"). */
        FROM_SINGLETON_LIST,
        /** Converted, or checked to conform, when it is evaluated, its type being known only then ({@link #conform}). */
        CHECKED,
        /** As null: the value does not conform to the declared type ("conforms to"). */
        NULL
    }

    /**
     * A value of a decision as the graph passes it on, bound to the decision ({@link Binding}).
     *
     * @param declared the type the decision declares, which {@link Binding#CHECKED} checks the value against
     */
    static Object bind(Object value, Binding binding, FeelType declared) {
        if (value == null || binding == Binding.NULL) {
            return null;
        }
        return switch (binding) {
            case DATE_TO_DATE_TIME -> ((LocalDate) value).atStartOfDay(ZoneOffset.UTC);
            case SINGLETON_LIST -> List.of(value);
            case FROM_SINGLETON_LIST -> value instanceof List<?> list && list.size() == 1 ? list.getFirst() : null;
            case CHECKED -> conform(value, declared, 0);
            default -> value;
        };
    }

    /**
     * A literal expression's value as the graph passes it on: a number as a decision table's computed output gives it,
     * without trailing zeros ({@code 1.50} is {@code 1.5}, {@code 1E+3} is {@code 1000}); any other value as it is.
     */
    static Object literalValue(Object value) {
        if (!(value instanceof BigDecimal number)) {
            return value;
        }
        if (number.signum() == 0) {
            return BigDecimal.ZERO;
        }
        var stripped = number.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /**
     * A value of a type known only when it is evaluated, bound to a declared type (DMN 1.5, 10.3.2.9.4): as it is if it
     * conforms (10.3.2.9.2), a list of one as its element for a single value, a single value as a list of one for a
     * list, a date as a date and time at UTC midnight for a date and time; null otherwise.
     *
     * @param depth how many lists and contexts of the declared type enclose it, bounded as the type is
     */
    static Object conform(Object value, FeelType declared, int depth) {
        if (value == null || declared instanceof FeelType.Any) {
            return value;
        }
        if (depth > MAX_DEPTH) {
            return null;
        }
        return switch (declared) {
            case FeelType.Simple(var type) -> {
                var single = value instanceof List<?> list && list.size() == 1 ? list.getFirst() : value;
                if (single != null && isValueOf(single, type)) {
                    yield single;
                }
                yield type == ColumnType.DATE_TIME && single instanceof LocalDate date ? date.atStartOfDay(ZoneOffset.UTC) : null;
            }
            case FeelType.ListOf(var element) -> {
                if (!(value instanceof List<?> list)) {
                    var single = conform(value, element, depth + 1);
                    yield single == null ? null : List.of(single);
                }
                yield list.stream().allMatch(item -> conformsOrNull(item, element, depth + 1)) ? list : null;
            }
            default -> conforms(value, declared, depth) ? value : null;
        };
    }

    /** Whether a value is null, which conforms to every type, or conforms to the type ({@link #conforms}). */
    private static boolean conformsOrNull(Object value, FeelType type, int depth) {
        return value == null || conforms(value, type, depth);
    }

    /** Whether a value conforms to a type as it is, with no conversion (DMN 1.5, 10.3.2.9.2). */
    private static boolean conforms(Object value, FeelType type, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        return switch (type) {
            case FeelType.Simple(var simple) -> isValueOf(value, simple);
            case FeelType.ListOf(var element) -> value instanceof List<?> list
                    && list.stream().allMatch(item -> conformsOrNull(item, element, depth + 1));
            case FeelType.Context(var components) -> value instanceof Map<?, ?> context
                    && components.entrySet().stream().allMatch(component -> context.containsKey(component.getKey())
                            && conformsOrNull(context.get(component.getKey()), component.getValue(), depth + 1));
            default -> true;
        };
    }

    /**
     * The simple type of a value, as FEEL's arithmetic takes it: a string, a number, a date, a time, a date and time or
     * a duration; null for a missing value, a boolean, a list, a context, or anything else, with which FEEL's arithmetic
     * gives null.
     */
    static ColumnType arithmeticType(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> ColumnType.STRING;
            case Number number -> ColumnType.NUMBER;
            case LocalDate date -> ColumnType.DATE;
            case LocalTime time -> ColumnType.TIME;
            case OffsetTime time -> ColumnType.TIME;
            case ZonedTime time -> ColumnType.TIME;
            case LocalDateTime dateTime -> ColumnType.DATE_TIME;
            case ZonedDateTime dateTime -> ColumnType.DATE_TIME;
            case Duration duration -> ColumnType.DAY_TIME_DURATION;
            case YearsAndMonths duration -> ColumnType.YEAR_MONTH_DURATION;
            default -> null;
        };
    }

    /** A value of a table's output from its text ({@link FeelLiterals#value}), null for a missing one. */
    static Object outputValue(String text, ColumnType type) {
        if (text == null) {
            return null;
        }
        try {
            return FeelLiterals.value(text, type);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IllegalArgumentException("output value '%s' is not a %s".formatted(text, type.feelName()), e);
        }
    }
}
