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
     * A value of {@link FeelType#ANY any type} is passed on as it is, a number as a {@code BigDecimal}.
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
            case FeelType.Any any -> raw instanceof Number number ? number(number, name) : raw;
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

    /** How a literal expression's value is bound to its decision's variable (DMN 1.5, 10.3.2.9.4, binding contexts). */
    enum Binding {
        /** As it is: of the type the decision declares, or of its own if it declares none. */
        AS_IS,
        /** A date as a date and time at UTC midnight ("from date to date and time"). */
        DATE_TO_DATE_TIME,
        /** As a list of one ("to singleton list"). */
        SINGLETON_LIST,
        /** As null: the value does not conform to the declared type ("conforms to"). */
        NULL
    }

    /**
     * A value of a literal expression as the graph passes it on: a number as a decision table's computed output gives
     * it, without trailing zeros, then bound to the decision ({@link Binding}).
     */
    static Object literalResult(Object value, Binding binding) {
        if (value == null || binding == Binding.NULL) {
            return null;
        }
        var result = value instanceof BigDecimal number
                ? number.signum() == 0 ? BigDecimal.ZERO : new BigDecimal(number.stripTrailingZeros().toPlainString())
                : value;
        return switch (binding) {
            case DATE_TO_DATE_TIME -> ((LocalDate) result).atStartOfDay(ZoneOffset.UTC);
            case SINGLETON_LIST -> List.of(result);
            default -> result;
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
