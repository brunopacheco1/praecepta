/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's semantics on values, as {@link FeelExpression} evaluates them: equality and order (DMN 1.5, 10.3.2.2,
 * Tables 53 and 54), the type of a value and whether it is an instance of a type (10.3.2.9), and the properties of
 * dates, times, durations and ranges (Table 65). Where FEEL defines no result these give null, and a test of three
 * values (true, false or null) is returned as a FEEL value, a {@code Boolean} or null.
 */
final class FeelValues {

    /** How deep a value's lists, contexts and ranges may nest for equality and {@code instance of} to look into them. */
    private static final int MAX_DEPTH = 256;
    /**
     * How many values equality and {@code instance of} look at, at most, for two values: a value may share its parts
     * ({@code {a: [1], b: [a, a], c: [b, b]}}), so that its tree is exponentially larger than the value. Beyond it,
     * equality is null, and {@code instance of} false.
     */
    private static final int MAX_WORK = 1_000_000;

    /** What a comparison of two values may still look at. */
    private static final class Work {
        private int remaining = MAX_WORK;

        boolean spend() {
            return --remaining >= 0;
        }
    }

    private FeelValues() {
    }

    // ---- types ------------------------------------------------------------------------------------

    /**
     * The simple type of a value: a string, a number, a boolean, a date, a time, a date and time or a duration; null for
     * null, a list, a context, a range or a function.
     */
    static ColumnType simpleType(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> ColumnType.STRING;
            case Number number -> ColumnType.NUMBER;
            case Boolean bool -> ColumnType.BOOLEAN;
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

    /** A value's type as FEEL knows it from the value alone: lists and contexts by their elements and entries. */
    static FeelType typeOf(Object value) {
        if (value == null) {
            return FeelType.NULL;
        }
        var simple = simpleType(value);
        if (simple != null) {
            return FeelType.of(simple);
        }
        return switch (value) {
            case FeelRange range -> new FeelType.RangeOf(range.start() != null ? typeOf(range.start()) : typeOf(range.end()));
            case FeelFunction function -> function.type();
            case List<?> list -> new FeelType.ListOf(FeelType.ANY);
            case Map<?, ?> context -> new FeelType.Context(Map.of());
            default -> FeelType.ANY;
        };
    }

    /**
     * Whether a value is an instance of a type ({@code instance of}, DMN 1.5, 10.3.2.9): null is an instance of no type
     * but {@code Null} (the DMN TCK's {@code 0070-feel-instance-of} has {@code null instance of Any} false); any other
     * value is of {@code Any}; a list is of {@code list<T>} when its elements are of T (or null); a context is of
     * {@code context<k: T...>} when it has those entries, of those types (or null), whatever others it has; a range of
     * {@code range<T>} when its endpoints are of T; a function of {@code function<T...>->R} when it takes as many
     * parameters, each of a type the corresponding T conforms to, and its value's type conforms to R.
     */
    static boolean isInstance(Object value, FeelType type) {
        return isInstance(value, type, 0, new Work());
    }

    private static boolean isInstance(Object value, FeelType type, int depth, Work work) {
        if (value == null) {
            return type instanceof FeelType.Null;
        }
        if (depth > MAX_DEPTH || !work.spend()) {
            return false;
        }
        return switch (type) {
            case FeelType.Any any -> true;
            case FeelType.Null nothing -> false;
            case FeelType.Simple(var simple) -> simple == simpleType(value);
            case FeelType.ListOf(var element) -> value instanceof List<?> list
                    && list.stream().allMatch(item -> item == null || isInstance(item, element, depth + 1, work));
            case FeelType.Context(var components) -> value instanceof Map<?, ?> context
                    && components.entrySet().stream().allMatch(component -> context.containsKey(component.getKey())
                            && (context.get(component.getKey()) == null
                                    || isInstance(context.get(component.getKey()), component.getValue(), depth + 1, work)));
            case FeelType.RangeOf(var element) -> value instanceof FeelRange range
                    && (range.start() == null || isInstance(range.start(), element, depth + 1, work))
                    && (range.end() == null || isInstance(range.end(), element, depth + 1, work));
            case FeelType.FunctionOf function -> value instanceof FeelFunction f && f.type().conformsTo(function);
        };
    }

    // ---- equality and order -------------------------------------------------------------------------

    /**
     * {@code a = b} (DMN 1.5, Table 53): true for two nulls, false for null and any other value; null for values of two
     * types (a number and a string, a list and a single value: the TCK's {@code 0068-feel-equality} has
     * {@code [100] = 100} null), but for two zero durations of the two kinds, which are equal; numbers by value,
     * times and date-times as {@link FeelTemporals#compare} orders them, durations by their length, lists element by
     * element, contexts entry by entry whatever their order, ranges by their endpoints and how they are written, and
     * functions by identity. Lists and contexts are equal when every element or entry is; false if one is not; null if
     * none is not but one is null.
     */
    static Object equal(Object a, Object b) {
        return equal(a, b, 0, new Work());
    }

    private static Object equal(Object a, Object b, int depth, Work work) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (depth > MAX_DEPTH || !work.spend()) {
            return null;
        }
        return switch (a) {
            case List<?> x -> b instanceof List<?> y ? listsEqual(x, y, depth, work) : null;
            case Map<?, ?> x -> b instanceof Map<?, ?> y ? contextsEqual(x, y, depth, work) : null;
            case FeelRange x -> b instanceof FeelRange y ? rangesEqual(x, y, depth, work) : null;
            case FeelFunction x -> b instanceof FeelFunction ? x == b : null;
            default -> simpleEqual(a, b);
        };
    }

    private static Object simpleEqual(Object a, Object b) {
        var typeA = simpleType(a);
        var typeB = simpleType(b);
        if (typeA == null || typeA != typeB) {
            return isZeroDuration(a) && isZeroDuration(b) ? Boolean.TRUE : null;
        }
        return switch (typeA) {
            case STRING, BOOLEAN, DATE, YEAR_MONTH_DURATION, DAY_TIME_DURATION -> a.equals(b);
            case NUMBER -> ((BigDecimal) a).compareTo((BigDecimal) b) == 0;
            default -> FeelTemporals.comparable(a, b) ? FeelTemporals.compare(a, b) == 0 : null;
        };
    }

    private static boolean isZeroDuration(Object value) {
        return value instanceof Duration duration && duration.isZero() || value instanceof YearsAndMonths(var months) && months == 0;
    }

    private static Object listsEqual(List<?> x, List<?> y, int depth, Work work) {
        if (x.size() != y.size()) {
            return false;
        }
        boolean unknown = false;
        for (int i = 0; i < x.size(); i++) {
            var equal = equal(x.get(i), y.get(i), depth + 1, work);
            if (Boolean.FALSE.equals(equal)) {
                return false;
            }
            unknown |= equal == null;
        }
        return unknown ? null : Boolean.TRUE;
    }

    private static Object contextsEqual(Map<?, ?> x, Map<?, ?> y, int depth, Work work) {
        if (!x.keySet().equals(y.keySet())) {
            return false;
        }
        boolean unknown = false;
        for (var entry : x.entrySet()) {
            var equal = equal(entry.getValue(), y.get(entry.getKey()), depth + 1, work);
            if (Boolean.FALSE.equals(equal)) {
                return false;
            }
            unknown |= equal == null;
        }
        return unknown ? null : Boolean.TRUE;
    }

    private static Object rangesEqual(FeelRange x, FeelRange y, int depth, Work work) {
        if (x.startIncluded() != y.startIncluded() || x.endIncluded() != y.endIncluded()
                || !Objects.equals(x.comparison(), y.comparison())) {
            return false;
        }
        var start = equal(x.start(), y.start(), depth + 1, work);
        var end = equal(x.end(), y.end(), depth + 1, work);
        if (Boolean.FALSE.equals(start) || Boolean.FALSE.equals(end)) {
            return false;
        }
        return start == null || end == null ? null : Boolean.TRUE;
    }

    /**
     * How two values compare (DMN 1.5, Table 54): numbers, strings, dates, times, date-times and durations of one kind
     * are ordered; any other pair, null included, is not.
     *
     * @return a negative number, zero or a positive number as {@code a} is before, at or after {@code b}; null if they
     *         are not ordered
     */
    static Integer compare(Object a, Object b) {
        var type = simpleType(a);
        if (type == null || type == ColumnType.BOOLEAN || type != simpleType(b) || !FeelTemporals.comparable(a, b)) {
            return null;
        }
        return FeelTemporals.compare(a, b);
    }

    /**
     * Whether a value is in a range (DMN 1.5, 10.3.2.7, Table 55): above its start, or at it if the start is included,
     * and below its end, or at it if the end is included; for {@code = e} and {@code != e}, whether it equals {@code e}
     * or not. Null where a comparison is: an endpoint or the value null, or of another type.
     */
    static Object inRange(Object value, FeelRange range) {
        if ("=".equals(range.comparison())) {
            return equal(value, range.start());
        }
        if ("!=".equals(range.comparison())) {
            return not(equal(value, range.start()));
        }
        Object above = Boolean.TRUE;
        if (range.comparison() == null || range.start() != null) {
            var order = compare(value, range.start());
            above = order == null ? null : order > 0 || order == 0 && range.startIncluded();
        }
        Object below = Boolean.TRUE;
        if (range.comparison() == null || range.end() != null) {
            var order = compare(value, range.end());
            below = order == null ? null : order < 0 || order == 0 && range.endIncluded();
        }
        return and(above, below);
    }

    // ---- three-valued logic -----------------------------------------------------------------------

    /** {@code a and b} (DMN 1.5, Table 50): false if either is false, true if both are true, else null. */
    static Object and(Object a, Object b) {
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
            return false;
        }
        return Boolean.TRUE.equals(a) && Boolean.TRUE.equals(b) ? Boolean.TRUE : null;
    }

    /** {@code a or b} (DMN 1.5, Table 50): true if either is true, false if both are false, else null. */
    static Object or(Object a, Object b) {
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
            return true;
        }
        return Boolean.FALSE.equals(a) && Boolean.FALSE.equals(b) ? Boolean.FALSE : null;
    }

    /** The negation of a test's value: true for false, false for true, null otherwise. */
    static Object not(Object value) {
        return value instanceof Boolean bool ? !bool : null;
    }

    // ---- properties --------------------------------------------------------------------------------

    /**
     * A property of a date, time, date and time, duration or range (DMN 1.5, Table 65): {@code year}, {@code month},
     * {@code day}, {@code weekday} (1 for Monday), {@code hour}, {@code minute}, {@code second}, {@code time offset} (a
     * days and time duration, null without an offset), {@code timezone} (the zone id, null without one);
     * {@code years} and {@code months} of a years and months duration, {@code days}, {@code hours}, {@code minutes} and
     * {@code seconds} of a days and time duration; {@code start}, {@code end}, {@code start included} and
     * {@code end included} of a range.
     *
     * @return the property's value, or {@link #NO_PROPERTY} if the value has no such property
     */
    static Object property(Object value, String name) {
        return switch (value) {
            case LocalDate date -> dateProperty(date, name);
            case LocalDateTime dateTime -> dateTimeProperty(dateTime, null, name);
            case ZonedDateTime dateTime -> dateTimeProperty(dateTime.toLocalDateTime(), dateTime, name);
            case LocalTime time -> timeProperty(time, null, null, name);
            case OffsetTime time -> timeProperty(time.toLocalTime(), time.getOffset(), null, name);
            case ZonedTime time -> timeProperty(time.time(), null, time.zone().getId(), name);
            case Duration duration -> durationProperty(duration, name);
            case YearsAndMonths(var months) -> switch (name) {
                case "years" -> BigDecimal.valueOf(months / 12);
                case "months" -> BigDecimal.valueOf(months % 12);
                default -> NO_PROPERTY;
            };
            case FeelRange range -> rangeProperty(range, name);
            default -> NO_PROPERTY;
        };
    }

    /** What {@link #property} gives for a property a value does not have. */
    static final Object NO_PROPERTY = new Object();

    private static final Set<String> DATE_PROPERTIES = Set.of("year", "month", "day", "weekday");
    private static final Set<String> TIME_PROPERTIES = Set.of("hour", "minute", "second", "time offset", "timezone");
    /** The properties of each simple type that has some (Table 65). */
    private static final Map<ColumnType, Set<String>> PROPERTIES = Map.of(
            ColumnType.DATE, DATE_PROPERTIES,
            ColumnType.DATE_TIME, union(DATE_PROPERTIES, TIME_PROPERTIES),
            ColumnType.TIME, TIME_PROPERTIES,
            ColumnType.YEAR_MONTH_DURATION, Set.of("years", "months"),
            ColumnType.DAY_TIME_DURATION, Set.of("days", "hours", "minutes", "seconds"));

    private static Set<String> union(Set<String> a, Set<String> b) {
        var union = new HashSet<>(a);
        union.addAll(b);
        return Set.copyOf(union);
    }

    /** The type of a property of a value of a simple type or a range, as {@link #property} gives it; null if it has none. */
    static FeelType propertyType(FeelType type, String name) {
        return switch (type) {
            case FeelType.Simple(var simple) when PROPERTIES.getOrDefault(simple, Set.of()).contains(name) -> switch (name) {
                case "time offset" -> FeelType.of(ColumnType.DAY_TIME_DURATION);
                case "timezone" -> FeelType.of(ColumnType.STRING);
                default -> FeelType.of(ColumnType.NUMBER);
            };
            case FeelType.RangeOf(var element) -> switch (name) {
                case "start", "end" -> element;
                case "start included", "end included" -> FeelType.of(ColumnType.BOOLEAN);
                default -> null;
            };
            default -> null;
        };
    }

    private static Object dateProperty(LocalDate date, String name) {
        return switch (name) {
            case "year" -> BigDecimal.valueOf(date.getYear());
            case "month" -> BigDecimal.valueOf(date.getMonthValue());
            case "day" -> BigDecimal.valueOf(date.getDayOfMonth());
            case "weekday" -> BigDecimal.valueOf(date.getDayOfWeek().getValue());
            default -> NO_PROPERTY;
        };
    }

    private static Object dateTimeProperty(LocalDateTime local, ZonedDateTime zoned, String name) {
        var date = dateProperty(local.toLocalDate(), name);
        if (date != NO_PROPERTY) {
            return date;
        }
        var zone = zoned == null || zoned.getZone() instanceof ZoneOffset ? null : zoned.getZone().getId();
        return timeProperty(local.toLocalTime(), zoned == null ? null : zoned.getOffset(), zone, name);
    }

    private static Object timeProperty(LocalTime time, ZoneOffset offset, String zone, String name) {
        return switch (name) {
            case "hour" -> BigDecimal.valueOf(time.getHour());
            case "minute" -> BigDecimal.valueOf(time.getMinute());
            case "second" -> BigDecimal.valueOf(time.getSecond()).add(BigDecimal.valueOf(time.getNano(), 9)).stripTrailingZeros();
            case "time offset" -> offset == null ? null : Duration.ofSeconds(offset.getTotalSeconds());
            case "timezone" -> zone;
            default -> NO_PROPERTY;
        };
    }

    private static Object durationProperty(Duration duration, String name) {
        return switch (name) {
            case "days" -> BigDecimal.valueOf(duration.toDaysPart());
            case "hours" -> BigDecimal.valueOf(duration.toHoursPart());
            case "minutes" -> BigDecimal.valueOf(duration.toMinutesPart());
            case "seconds" -> BigDecimal.valueOf(duration.toSecondsPart()).add(BigDecimal.valueOf(duration.toNanosPart(), 9))
                    .stripTrailingZeros();
            default -> NO_PROPERTY;
        };
    }

    private static Object rangeProperty(FeelRange range, String name) {
        return switch (name) {
            case "start" -> range.start();
            case "end" -> range.end();
            case "start included" -> range.startIncluded();
            case "end included" -> range.endIncluded();
            default -> NO_PROPERTY;
        };
    }

    // ---- text --------------------------------------------------------------------------------------

    /**
     * A value as messages name it: a simple value's text, a string quoted, null as {@code null}, a short list of simple
     * values in full, any other list by its size.
     */
    static String text(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> "\"" + text + "\"";
            case List<?> list -> list.size() > 8 || list.stream().anyMatch(item -> item != null && simpleType(item) == null)
                    ? "a list of " + list.size() : String.valueOf(list.stream().map(FeelValues::text).toList());
            case Map<?, ?> context -> "a context";
            case FeelFunction function -> "a function";
            case FeelRange range -> range.toString();
            default -> simpleType(value) != null ? FeelLiterals.text(value instanceof Boolean ? value.toString() : value) : value.toString();
        };
    }

    /** The name of a value's type, as messages give it: {@code number}, {@code list}, {@code null}. */
    static String typeName(Object value) {
        return switch (value) {
            case null -> "null";
            case List<?> list -> "list";
            case Map<?, ?> context -> "context";
            case FeelRange range -> "range";
            case FeelFunction function -> "function";
            default -> {
                var type = simpleType(value);
                yield type == null ? value.getClass().getSimpleName() : type.feelName();
            }
        };
    }
}
