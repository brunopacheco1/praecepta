/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * One column of the {@link BitsetIndex}. The column's values are partitioned into <em>classes</em>
 * that no rule can tell apart, and each class carries the mask of rules accepting it.
 *
 * <ul>
 * <li>STRING: one class per value written in the column (or declared allowed), one for every other
 * value, and one for a missing value.</li>
 * <li>NUMBER, DATE, TIME and DATE_TIME, and STRING columns whose cells compare ({@code < "N"},
 * {@code ["A".."M"]}): with the boundary values {@code b0 < b1 < ... < bk} mentioned by the rules
 * (and the allowed values), the classes are {@code (-inf, b0), [b0], (b0, b1), [b1], ..., (bk, +inf)}
 * and a missing value. Lookup is a binary search; a STRING column first looks the value up among its
 * boundaries, as an unordered one does. An interval that holds no value (between consecutive days,
 * or consecutive nanoseconds; before midnight or after the last nanosecond of a day for times; between
 * a string and the same string followed by the character U+0000) is marked empty.</li>
 * <li>BOOLEAN: ordered like the others with {@code false < true}, both always boundaries, so the
 * classes are {@code [false]}, {@code [true]} and a missing value (the intervals are all empty).</li>
 * </ul>
 *
 * Membership is decided per class with FEEL's three-valued logic: a missing value fails comparisons
 * and intervals (unknown), equals only {@code null}, and so satisfies {@code not("A")}.
 *
 * <p>A STRING column bound to an enum precomputes one mask per constant, so its lookup is an array
 * access by ordinal instead of a hash lookup (the int-coded input path).
 */
final class ColumnIndex {

    final String name;
    final ColumnType type;
    private final boolean isString;
    /**
     * STRING fast path: written value -> mask, plus the masks of other values (null when the column
     * compares, as its other values fall in intervals) and of missing values.
     */
    private final Map<String, long[]> stringMasks;
    private final long[] otherMask;
    private final long[] nullMask;
    /** STRING values written in the rules but outside the declared input values (null if none declared). */
    private final Set<String> disallowedStrings;
    /** Ordered columns (all but STRING, and STRING columns whose cells compare): the sorted distinct boundaries; else null. */
    private final Comparable<?>[] boundaries;
    /** The value classes, by class number. */
    private final List<ValueClass> classes;
    final int nullClass;
    /** Per class, the rules (bits) accepting it. */
    final long[][] masks;
    /** Classes holding no value at all (see {@link OpenIntervals#isEmpty}). */
    final boolean[] empty;
    /** Classes allowed by the declared input values (all, if none declared). Null is always allowed. */
    final boolean[] allowed;
    final boolean hasAllowedValues;
    /** Enum-bound STRING column: mask per constant ordinal; null otherwise. */
    private final long[][] ordinalMasks;
    /** Enum-bound STRING column: each constant's value, by ordinal; null otherwise. */
    private final String[] enumValues;

    /**
     * @param enumValues for a STRING column bound to an enum: each constant's value, by ordinal; else null
     * @throws InvalidDecisionTableException if an enum value is outside the declared input values
     */
    ColumnIndex(String name, ColumnType type, List<UnaryTests> cellsByBit, UnaryTests allowedValues, int words, String[] enumValues) {
        this.name = name;
        this.type = type;
        this.isString = type == ColumnType.STRING;
        this.hasAllowedValues = allowedValues != null;

        var partition = new ArrayList<ValueClass>();
        Map<String, Integer> stringClasses;
        int otherClass = -1;
        if (isString && !compares(cellsByBit)) {
            stringClasses = addStringClasses(cellsByBit, allowedValues, partition);
            otherClass = partition.size();
            partition.add(new ValueClass.OtherString());
            boundaries = null;
        } else {
            boundaries = sortedBoundaries(type, cellsByBit, allowedValues);
            addOrderedClasses(boundaries, partition);
            stringClasses = isString ? boundaryClasses(boundaries) : null;
        }
        nullClass = partition.size();
        partition.add(new ValueClass.Missing());
        classes = List.copyOf(partition);

        masks = new long[classes.size()][words];
        empty = new boolean[classes.size()];
        allowed = new boolean[classes.size()];
        for (int k = 0; k < classes.size(); k++) {
            var valueClass = classes.get(k);
            empty[k] = valueClass instanceof ValueClass.Between(var low, var high) && OpenIntervals.isEmpty(type, low, high);
            allowed[k] = allowedValues == null || valueClass instanceof ValueClass.Missing || accepts(allowedValues, valueClass);
            if (!empty[k]) {
                markAcceptingRules(masks[k], cellsByBit, valueClass);
            }
        }

        if (isString) {
            stringMasks = masksByString(stringClasses);
            otherMask = otherClass < 0 ? null : masks[otherClass];
            disallowedStrings = hasAllowedValues ? writtenButNotAllowed(stringClasses) : null;
        } else {
            stringMasks = null;
            otherMask = null;
            disallowedStrings = null;
        }
        nullMask = masks[nullClass];
        ordinalMasks = enumValues == null ? null : masksByOrdinal(enumValues);
        this.enumValues = enumValues == null ? null : enumValues.clone();
    }

    // ---- building the classes ---------------------------------------------------------------------

    /**
     * STRING: adds one class per value written in the cells or the allowed values, in first-seen
     * order, and returns the class of each value.
     */
    private static Map<String, Integer> addStringClasses(List<UnaryTests> cellsByBit, UnaryTests allowedValues,
            List<ValueClass> into) {
        var written = new LinkedHashSet<String>();
        cellsByBit.forEach(cell -> collectStrings(cell, written));
        if (allowedValues != null) {
            collectStrings(allowedValues, written);
        }
        var byValue = new HashMap<String, Integer>();
        for (var value : written) {
            byValue.put(value, into.size());
            into.add(new ValueClass.Point(value));
        }
        return Map.copyOf(byValue);
    }

    /** Whether some cell compares or tests an interval, which makes a STRING column ordered. */
    private static boolean compares(List<UnaryTests> cellsByBit) {
        return cellsByBit.stream().flatMap(cell -> tests(cell).stream()).anyMatch(test -> test instanceof Compare || test instanceof Range);
    }

    /** Ordered STRING column: the class of each boundary, which is class {@code 2i + 1} for boundary {@code i}. */
    private static Map<String, Integer> boundaryClasses(Comparable<?>[] boundaries) {
        var byValue = new HashMap<String, Integer>();
        for (int i = 0; i < boundaries.length; i++) {
            byValue.put((String) boundaries[i], 2 * i + 1);
        }
        return Map.copyOf(byValue);
    }

    /** Ordered columns: the distinct values the cells and the allowed values compare against, sorted. */
    private static Comparable<?>[] sortedBoundaries(ColumnType type, List<UnaryTests> cellsByBit, UnaryTests allowedValues) {
        var points = new TreeSet<Comparable<?>>(ColumnIndex::compare);
        if (type == ColumnType.BOOLEAN) {
            points.add(Boolean.FALSE);
            points.add(Boolean.TRUE);
        }
        cellsByBit.forEach(cell -> collectBoundaries(cell, points));
        if (allowedValues != null) {
            collectBoundaries(allowedValues, points);
        }
        return points.toArray(Comparable<?>[]::new);
    }

    private static void collectStrings(UnaryTests cell, Set<String> into) {
        for (var test : tests(cell)) {
            if (test instanceof Equals(var value)) {
                into.add((String) value);
            }
        }
    }

    private static void collectBoundaries(UnaryTests cell, Set<Comparable<?>> into) {
        for (var test : tests(cell)) {
            switch (test) {
                case Equals(var value) -> into.add(value);
                case Compare c -> into.add(c.value());
                case Range r -> {
                    into.add(r.low());
                    into.add(r.high());
                }
                case NullTest n -> {
                    // no boundary
                }
            }
        }
    }

    private static List<Test> tests(UnaryTests cell) {
        return switch (cell) {
            case UnaryTests.Any any -> List.of();
            case UnaryTests.Positive(var tests) -> tests;
            case UnaryTests.Negated(var tests) -> tests;
        };
    }

    /** Ordered columns: the interval before each boundary, the boundary itself, and the interval after the last one. */
    private static void addOrderedClasses(Comparable<?>[] boundaries, List<ValueClass> into) {
        Comparable<?> low = null;
        for (var boundary : boundaries) {
            into.add(new ValueClass.Between(low, boundary));
            into.add(new ValueClass.Point(boundary));
            low = boundary;
        }
        into.add(new ValueClass.Between(low, null));
    }

    /** Sets the bit of every rule whose cell accepts the class. */
    private static void markAcceptingRules(long[] mask, List<UnaryTests> cellsByBit, ValueClass valueClass) {
        for (int bit = 0; bit < cellsByBit.size(); bit++) {
            if (accepts(cellsByBit.get(bit), valueClass)) {
                BitsetIndex.set(mask, bit);
            }
        }
    }

    /** STRING fast path: the mask of each written or allowed value. */
    private Map<String, long[]> masksByString(Map<String, Integer> stringClasses) {
        var byValue = new HashMap<String, long[]>();
        stringClasses.forEach((value, k) -> byValue.put(value, masks[k]));
        return Map.copyOf(byValue);
    }

    /** STRING: the values written in the rules that the declared input values do not allow. */
    private Set<String> writtenButNotAllowed(Map<String, Integer> stringClasses) {
        var disallowed = new HashSet<String>();
        stringClasses.forEach((value, k) -> {
            if (!allowed[k]) {
                disallowed.add(value);
            }
        });
        return Set.copyOf(disallowed);
    }

    /**
     * Enum-bound STRING column: the mask of each constant, by ordinal.
     *
     * @throws InvalidDecisionTableException if an enum value is outside the declared input values
     */
    private long[][] masksByOrdinal(String[] enumValues) {
        var byOrdinal = new long[enumValues.length][];
        for (int ordinal = 0; ordinal < enumValues.length; ordinal++) {
            try {
                byOrdinal[ordinal] = stringMaskSlow(enumValues[ordinal]);
            } catch (InvalidInputException e) {
                throw new InvalidDecisionTableException("Column '%s': enum value '%s' is not one of the column's input values"
                        .formatted(name, enumValues[ordinal]));
            }
        }
        return byOrdinal;
    }

    // ---- lookup -----------------------------------------------------------------------------------

    /**
     * The mask of rules accepting a raw input value. For a STRING column the value is a String, or an
     * enum constant if the column is enum-bound; for the other types it is the typed value (a Number,
     * LocalDate, Boolean, LocalTime or LocalDateTime) or its text. Blank text counts as missing.
     *
     * @throws InvalidInputException if the value is outside the declared input values, or text that
     *                               is not a valid value of the column's type
     */
    long[] maskFor(Object raw) {
        if (isString) {
            if (ordinalMasks != null) {
                // int-coded path: enum constants were checked against the declared values when built
                return raw == null ? nullMask : ordinalMasks[((Enum<?>) raw).ordinal()];
            }
            // hot path, kept tiny so it inlines: a known value in a column without declared values
            if (raw != null && disallowedStrings == null) {
                var mask = stringMasks.get(raw);
                if (mask != null) {
                    return mask;
                }
            }
            return stringMaskSlow((String) raw);
        }
        return orderedMask(raw);
    }

    private long[] stringMaskSlow(String text) {
        if (text == null) {
            return nullMask;
        }
        var mask = stringMasks.get(text);
        if (mask == null) {
            if (text.isBlank()) {
                return nullMask;
            }
            if (boundaries != null) {
                return maskOf(text); // the interval between the boundaries the text falls in
            }
            if (hasAllowedValues) {
                throw new InvalidInputException(name, text);
            }
            return otherMask;
        }
        if (disallowedStrings != null && disallowedStrings.contains(text)) {
            throw new InvalidInputException(name, text);
        }
        return mask;
    }

    private long[] orderedMask(Object raw) {
        return maskOf(convert(raw));
    }

    /** The mask of a converted value of an ordered column (null if missing), checked against the declared values. */
    private long[] maskOf(Object value) {
        int valueClass = classOf(value);
        if (hasAllowedValues && !allowed[valueClass]) {
            throw new InvalidInputException(name, value);
        }
        return masks[valueClass];
    }

    /**
     * The class of a converted value of an ordered column, or the missing class for null. Boundary
     * {@code i} is class {@code 2i + 1}; the interval just below it is class {@code 2i}.
     */
    private int classOf(Object value) {
        if (value == null) {
            return nullClass;
        }
        int found = Arrays.binarySearch(boundaries, value, ColumnIndex::compare);
        return found >= 0 ? 2 * found + 1 : 2 * (-found - 1);
    }

    /**
     * The value of a raw input, as {@link #maskFor} reads it: a String (an enum constant's value) for a
     * STRING column, else the column's Java type; null if missing. The value has already been checked
     * by {@link #maskFor}, when the input was matched.
     */
    Object value(Object raw) {
        if (!isString) {
            return convert(raw);
        }
        if (raw instanceof Enum<?> constant) {
            return enumValues[constant.ordinal()];
        }
        return raw == null || ((String) raw).isBlank() ? null : raw;
    }

    // ---- converting raw values --------------------------------------------------------------------

    /** Converts a raw input of an ordered column to the column's Java type; null if missing. */
    private Object convert(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            var value = text.strip();
            try {
                return switch (type) {
                    case NUMBER -> new BigDecimal(value);
                    case DATE -> LocalDate.parse(value);
                    case TIME -> LocalTime.parse(value);
                    case DATE_TIME -> LocalDateTime.parse(value);
                    case DAY_TIME_DURATION -> Duration.parse(value);
                    case YEAR_MONTH_DURATION -> YearsAndMonths.parse(value);
                    case BOOLEAN -> bool(text);
                    case STRING -> throw new IllegalStateException("STRING values are looked up as they are, not converted");
                };
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw notValid(text);
            }
        }
        // typed getters already return the column's Java type (LocalDate, LocalTime, ...); numbers vary,
        // and a years and months duration is bound as a Period
        return switch (type) {
            case NUMBER -> number((Number) raw);
            case YEAR_MONTH_DURATION -> yearsAndMonths((Period) raw);
            default -> raw;
        };
    }

    private YearsAndMonths yearsAndMonths(Period period) {
        try {
            return YearsAndMonths.of(period);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(name, period, "has days; a years and months duration has only years and months");
        }
    }

    /** {@code true} or {@code false} in any case. */
    private Boolean bool(String text) {
        var value = text.strip();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.valueOf(value);
        }
        throw notValid(text);
    }

    private BigDecimal number(Number raw) {
        return switch (raw) {
            case BigDecimal decimal -> decimal;
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short sh -> BigDecimal.valueOf(sh);
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

    private InvalidInputException notValid(String text) {
        return new InvalidInputException(name, text, "is not a valid " + type.feelName());
    }

    // ---- classes, for the validation report -------------------------------------------------------

    int classCount() {
        return masks.length;
    }

    /**
     * Whether one class stands for every value the rules do not mention: a STRING column whose cells
     * do not compare. Every such value is then a gap unless the column declares its input values.
     */
    boolean groupsOtherValues() {
        return otherMask != null;
    }

    /** Whether a valid input can fall in the class: it holds some value and the declared input values allow it. */
    boolean canOccur(int valueClass) {
        return !empty[valueClass] && allowed[valueClass];
    }

    /** A human-readable description of a class, for validation reports. */
    String describe(int valueClass) {
        return switch (classes.get(valueClass)) {
            case ValueClass.Point(var value) -> FeelLiterals.literal(value);
            case ValueClass.Between between -> lowEnd(between) + ".." + highEnd(between);
            case ValueClass.OtherString other -> "any other value";
            case ValueClass.Missing missing -> "null";
        };
    }

    /**
     * Describes a set of distinct classes for a report. For ordered columns with comparisons (numbers,
     * dates, times and comparing strings), adjacent classes merge into intervals, e.g.
     * {@code [18], (18..65), [65]} becomes {@code [18..65]}. Empty classes do not break a run.
     */
    List<String> describe(List<Integer> valueClasses) {
        if (boundaries == null || !type.supportsComparisons()) { // no intervals to merge
            return valueClasses.stream().map(this::describe).toList();
        }
        var sorted = valueClasses.stream().sorted().toList();
        var runs = new ArrayList<String>();
        int start = sorted.getFirst();
        int end = start;
        for (int next : sorted.subList(1, sorted.size())) {
            if (!adjacent(end, next)) {
                runs.add(run(start, end));
                start = next;
            }
            end = next;
        }
        runs.add(run(start, end));
        return runs;
    }

    /** Whether class {@code to} comes after class {@code from} with only empty classes between them. */
    private boolean adjacent(int from, int to) {
        if (to <= from) {
            return false;
        }
        for (int k = from + 1; k < to; k++) {
            if (!empty[k]) {
                return false;
            }
        }
        return true;
    }

    /** The classes {@code first} to {@code last}: one class as {@link #describe(int)}, a run as one interval. */
    private String run(int first, int last) {
        if (first == last) {
            return describe(first);
        }
        return lowEnd(classes.get(first)) + ".." + highEnd(classes.get(last));
    }

    /** How an interval starting with the class opens: {@code [18} at a value, {@code (18} or {@code (-inf} after one. */
    private static String lowEnd(ValueClass first) {
        return switch (first) {
            case ValueClass.Point(var value) -> "[" + FeelLiterals.literal(value);
            case ValueClass.Between between -> between.low() == null ? "(-inf" : "(" + FeelLiterals.literal(between.low());
            default -> throw new IllegalStateException("not an ordered class");
        };
    }

    /** How an interval ending with the class closes: {@code 65]} at a value, {@code 65)} or {@code +inf)} before one. */
    private static String highEnd(ValueClass last) {
        return switch (last) {
            case ValueClass.Point(var value) -> FeelLiterals.literal(value) + "]";
            case ValueClass.Between between -> between.high() == null ? "+inf)" : FeelLiterals.literal(between.high()) + ")";
            default -> throw new IllegalStateException("not an ordered class");
        };
    }

    /**
     * A concrete value inside a non-empty class, as a getter of the column returns it (a Period for a
     * years and months duration), or null for the missing class / any other string.
     */
    Object representative(int valueClass) {
        return switch (classes.get(valueClass)) {
            case ValueClass.Point(var value) -> bindable(value);
            case ValueClass.Between(var low, var high) -> bindable(OpenIntervals.valueInside(type, low, high));
            case ValueClass.OtherString other -> null;
            case ValueClass.Missing missing -> null;
        };
    }

    /** A value as a getter returns it: a years and months duration as the Period {@code periodInput} binds. */
    private static Object bindable(Object value) {
        return value instanceof YearsAndMonths duration ? duration.toPeriod() : value;
    }

    // ---- FEEL semantics per class ---------------------------------------------------------------

    /** Whether every value of the class satisfies the cell. Boundaries make this uniform per class. */
    private static boolean accepts(UnaryTests cell, ValueClass valueClass) {
        return switch (cell) {
            case UnaryTests.Any any -> true;
            case UnaryTests.Positive(var tests) -> disjunction(tests, valueClass) == Boolean.TRUE;
            // not(unknown) is unknown: no match
            case UnaryTests.Negated(var tests) -> disjunction(tests, valueClass) == Boolean.FALSE;
        };
    }

    /** FEEL disjunction: true if any test is true, else unknown (null) if any is unknown, else false. */
    private static Boolean disjunction(List<Test> tests, ValueClass valueClass) {
        Boolean result = Boolean.FALSE;
        for (var test : tests) {
            var outcome = test(test, valueClass);
            if (outcome == Boolean.TRUE) {
                return Boolean.TRUE;
            }
            if (outcome == null) {
                result = null;
            }
        }
        return result;
    }

    private static Boolean test(Test test, ValueClass valueClass) {
        if (valueClass instanceof ValueClass.Missing) {
            return switch (test) {
                case NullTest n -> true;
                case Equals e -> false;       // null = x is false
                case Compare c -> null;       // null < x is unknown
                case Range r -> null;
            };
        }
        return switch (test) {
            case NullTest n -> false;
            case Equals(var literal) -> switch (valueClass) {
                case ValueClass.Point(var value) -> compare(value, literal) == 0;
                default -> false; // literals are boundaries / written values, never inside other classes
            };
            case Compare c -> switch (valueClass) {
                case ValueClass.Point(var value) -> compares(value, c);
                case ValueClass.Between(var low, var high) -> switch (c.operator()) {
                    // c.value() is a boundary, so it lies entirely on one side of the open interval
                    case LT, LE -> high != null && compare(high, c.value()) <= 0;
                    case GT, GE -> low != null && compare(low, c.value()) >= 0;
                };
                default -> false;
            };
            case Range r -> switch (valueClass) {
                case ValueClass.Point(var value) -> inRange(value, r);
                case ValueClass.Between(var low, var high) -> low != null && high != null
                        && compare(r.low(), low) <= 0 && compare(r.high(), high) >= 0;
                default -> false;
            };
        };
    }

    private static boolean compares(Comparable<?> value, Compare c) {
        int cmp = compare(value, c.value());
        return switch (c.operator()) {
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
        };
    }

    private static boolean inRange(Comparable<?> value, Range r) {
        int low = compare(value, r.low());
        int high = compare(value, r.high());
        return (low > 0 || (low == 0 && r.lowClosed())) && (high < 0 || (high == 0 && r.highClosed()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }

    // ---- value classes ------------------------------------------------------------------------

    private sealed interface ValueClass {
        /** One value: a boundary of an ordered column, or a string written in the rules or declared. */
        record Point(Comparable<?> value) implements ValueClass {
        }

        /** Open interval between two boundaries; null means unbounded. */
        record Between(Comparable<?> low, Comparable<?> high) implements ValueClass {
        }

        /** STRING: every value not written in the rules or declared. */
        record OtherString() implements ValueClass {
        }

        record Missing() implements ValueClass {
        }
    }
}
