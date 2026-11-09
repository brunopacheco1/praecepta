/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

/**
 * One column of the {@link BitsetIndex}. The column's values are partitioned into <em>classes</em>
 * that no rule can tell apart, and each class carries the mask of rules accepting it.
 *
 * <ul>
 * <li>STRING: one class per value written in the column (or declared allowed), one for every other
 * value, and one for a missing value.</li>
 * <li>NUMBER, DATE, TIME and DATE_TIME: with the boundary values {@code b0 < b1 < ... < bk} mentioned
 * by the rules (and the allowed values), the classes are
 * {@code (-inf, b0), [b0], (b0, b1), [b1], ..., (bk, +inf)} and a missing value. Lookup is a binary
 * search. A gap that holds no value (between consecutive days, or consecutive nanoseconds; before
 * midnight or after the last nanosecond of a day for times) is marked empty.</li>
 * <li>BOOLEAN: ordered like the others with {@code false < true}, both always boundaries, so the
 * classes are {@code [false]}, {@code [true]} and a missing value (the gaps are all empty).</li>
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
    /** STRING: written/allowed value -> class. */
    private final Map<String, Integer> stringClasses;
    /** STRING fast path: written value -> mask, plus the masks of other and missing values. */
    private final Map<String, long[]> stringMasks;
    private final long[] otherMask;
    private final long[] nullMask;
    /** STRING values written in the rules but outside the declared input values (null if none declared). */
    private final Set<String> disallowedStrings;
    /** NUMBER/DATE: sorted distinct boundaries. */
    private final Comparable<?>[] boundaries;
    /** The value classes, by class number. */
    private final List<ValueClass> classes;
    private final int otherClass;
    final int nullClass;
    final long[][] masks;
    /** Classes containing no possible value (date gaps between consecutive days). */
    final boolean[] empty;
    /** Classes allowed by the declared input values (all, if none declared). Null is always allowed. */
    final boolean[] allowed;
    final boolean hasAllowedValues;
    /** Enum-bound STRING column: mask per constant ordinal; null otherwise. */
    private final long[][] ordinalMasks;

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
        if (isString) {
            stringClasses = addStringClasses(cellsByBit, allowedValues, partition);
            otherClass = partition.size();
            partition.add(new ValueClass.OtherString());
            boundaries = null;
        } else {
            boundaries = sortedBoundaries(type, cellsByBit, allowedValues);
            addOrderedClasses(boundaries, partition);
            stringClasses = null;
            otherClass = -1;
        }
        nullClass = partition.size();
        partition.add(new ValueClass.Missing());
        classes = List.copyOf(partition);

        masks = new long[classes.size()][words];
        empty = new boolean[classes.size()];
        allowed = new boolean[classes.size()];
        for (int k = 0; k < classes.size(); k++) {
            var valueClass = classes.get(k);
            empty[k] = isEmpty(valueClass);
            allowed[k] = allowedValues == null || valueClass instanceof ValueClass.Missing || accepts(allowedValues, valueClass);
            if (!empty[k]) {
                markAcceptingRules(masks[k], cellsByBit, valueClass);
            }
        }

        if (isString) {
            stringMasks = masksByString();
            otherMask = masks[otherClass];
            disallowedStrings = hasAllowedValues ? writtenButNotAllowed() : null;
        } else {
            stringMasks = null;
            otherMask = null;
            disallowedStrings = null;
        }
        nullMask = masks[nullClass];
        ordinalMasks = enumValues == null ? null : masksByOrdinal(enumValues);
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

    /** Ordered columns: the gap before each boundary, the boundary itself, and the gap after the last one. */
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
    private Map<String, long[]> masksByString() {
        var byValue = new HashMap<String, long[]>();
        stringClasses.forEach((value, k) -> byValue.put(value, masks[k]));
        return Map.copyOf(byValue);
    }

    /** STRING: the values written in the rules that the declared input values do not allow. */
    private Set<String> writtenButNotAllowed() {
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
     * The mask of rules accepting a raw input value: a String (typed getter or text map) or, if the
     * column is enum-bound, an enum constant for STRING columns; for the other types, the typed value
     * (a Number, LocalDate, Boolean, LocalTime or LocalDateTime) or its text. Blank text counts as
     * missing.
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
        var value = convert(raw);
        int valueClass = classOf(value);
        if (hasAllowedValues && !allowed[valueClass]) {
            throw new InvalidInputException(name, value);
        }
        return masks[valueClass];
    }

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
                    case BOOLEAN -> bool(text);
                    case STRING -> throw new IllegalStateException("STRING columns are not ordered");
                };
            } catch (NumberFormatException | DateTimeParseException e) {
                throw notValid(text);
            }
        }
        // typed getters already return the column's Java type (LocalDate, LocalTime, ...); numbers vary
        return type == ColumnType.NUMBER ? number((Number) raw) : raw;
    }

    /** {@code true} or {@code false} in any case. */
    private Boolean bool(String text) {
        var value = text.strip();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.valueOf(value);
        }
        throw notValid(text);
    }

    private InvalidInputException notValid(String text) {
        return new InvalidInputException(name, text, "is not a valid " + type.feelName());
    }

    private BigDecimal number(Number raw) {
        return switch (raw) {
            case BigDecimal decimal -> decimal;
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short sh -> BigDecimal.valueOf(sh);
            case Byte b -> BigDecimal.valueOf(b);
            case java.math.BigInteger big -> new BigDecimal(big);
            case Number number -> {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new InvalidInputException(name, number, "is not a finite number");
                }
                yield BigDecimal.valueOf(d);
            }
        };
    }

    /** Class of a value already converted to the column's Java type (or null). */
    int classOf(Object value) {
        if (value == null) {
            return nullClass;
        }
        if (type == ColumnType.STRING) {
            return stringClasses.getOrDefault(value, otherClass);
        }
        int found = Arrays.binarySearch(boundaries, value, ColumnIndex::compare);
        return found >= 0 ? 2 * found + 1 : 2 * (-found - 1);
    }

    int classCount() {
        return masks.length;
    }

    /** A human-readable description of a class, for validation reports. */
    String describe(int valueClass) {
        return classes.get(valueClass).describe();
    }

    /**
     * Describes a set of classes for a report. For NUMBER and DATE, adjacent classes merge into
     * intervals, e.g. {@code [18], (18..65), [65]} becomes {@code [18..65]}. Empty classes (date gaps)
     * do not break a run.
     */
    List<String> describe(List<Integer> valueClasses) {
        if (!type.supportsComparisons()) { // no intervals to merge
            return valueClasses.stream().map(this::describe).toList();
        }
        var sorted = valueClasses.stream().sorted().toList();
        var runs = new ArrayList<String>();
        int start = sorted.getFirst();
        int end = start;
        for (int i = 1; i <= sorted.size(); i++) {
            int next = i < sorted.size() ? sorted.get(i) : -1;
            boolean adjacent = next > end && java.util.stream.IntStream.range(end + 1, next).allMatch(k -> empty[k]);
            if (i < sorted.size() && adjacent) {
                end = next;
                continue;
            }
            runs.add(run(start, end));
            start = next;
            end = next;
        }
        return runs;
    }

    private String run(int from, int to) {
        if (from == to) {
            return describe(from);
        }
        var first = classes.get(from);
        var last = classes.get(to);
        var low = switch (first) {
            case ValueClass.Point(var value) -> "[" + FeelLiterals.literal(value);
            case ValueClass.Between b -> b.low() == null ? "(-inf" : "(" + FeelLiterals.literal(b.low());
            default -> throw new IllegalStateException("not an ordered class");
        };
        var high = switch (last) {
            case ValueClass.Point(var value) -> FeelLiterals.literal(value) + "]";
            case ValueClass.Between b -> b.high() == null ? "+inf)" : FeelLiterals.literal(b.high()) + ")";
            default -> throw new IllegalStateException("not an ordered class");
        };
        return low + ".." + high;
    }

    /** A concrete value inside a non-empty class, or null for the missing class / any other string. */
    Object representative(int valueClass) {
        return switch (classes.get(valueClass)) {
            case ValueClass.Point(var value) -> value;
            case ValueClass.Between gap -> valueInside(gap);
            case ValueClass.OtherString o -> null;
            case ValueClass.Missing m -> null;
        };
    }

    /** Whether the class stands for every string not written in the rules (no concrete example). */
    boolean isOtherStrings(int valueClass) {
        return valueClass == otherClass;
    }

    // ---- value classes ------------------------------------------------------------------------

    private sealed interface ValueClass {
        record Point(Comparable<?> value) implements ValueClass {
        }

        /** Open interval between two boundaries; null means unbounded. */
        record Between(Comparable<?> low, Comparable<?> high) implements ValueClass {
        }

        record OtherString() implements ValueClass {
        }

        record Missing() implements ValueClass {
        }

        default String describe() {
            return switch (this) {
                case Point(var value) -> FeelLiterals.literal(value);
                case Between(var low, var high) -> "(" + (low == null ? "-inf" : FeelLiterals.literal(low)) + ".."
                        + (high == null ? "+inf" : FeelLiterals.literal(high)) + ")";
                case OtherString o -> "any other value";
                case Missing m -> "null";
            };
        }
    }

    /** A value inside a non-empty open interval: a round step beyond an open end, else the midpoint. */
    private Object valueInside(ValueClass.Between gap) {
        return switch (type) {
            case DATE -> betweenDates((LocalDate) gap.low(), (LocalDate) gap.high());
            case TIME -> betweenTimes((LocalTime) gap.low(), (LocalTime) gap.high());
            case DATE_TIME -> betweenDateTimes((LocalDateTime) gap.low(), (LocalDateTime) gap.high());
            case NUMBER -> betweenNumbers((BigDecimal) gap.low(), (BigDecimal) gap.high());
            // BOOLEAN gaps are always empty, and STRING has no intervals
            case BOOLEAN, STRING -> throw new IllegalStateException("no value between " + gap);
        };
    }

    private static LocalDate betweenDates(LocalDate low, LocalDate high) {
        if (low != null) {
            return low.plusDays(1);
        }
        return high == null ? LocalDate.EPOCH : high.minusDays(1);
    }

    private static LocalTime betweenTimes(LocalTime low, LocalTime high) {
        if (low == null && high == null) {
            return LocalTime.NOON;
        }
        if (low == null) {
            // the gap is not empty, so high is after midnight: step back an hour unless that wraps around
            return high.getHour() >= 1 ? high.minusHours(1) : LocalTime.MIDNIGHT;
        }
        if (high == null) {
            return low.getHour() <= 22 ? low.plusHours(1) : LocalTime.MAX;
        }
        // at least two nanoseconds apart (the gap is not empty), so the midpoint lies strictly inside
        return LocalTime.ofNanoOfDay((low.toNanoOfDay() + high.toNanoOfDay()) / 2);
    }

    private static LocalDateTime betweenDateTimes(LocalDateTime low, LocalDateTime high) {
        if (low == null) {
            return high == null ? LocalDate.EPOCH.atStartOfDay() : high.minusDays(1);
        }
        if (high == null) {
            return low.plusDays(1);
        }
        // date-times have no zone: measure on the UTC timeline, which has no daylight-saving gaps or overlaps
        var start = low.toInstant(ZoneOffset.UTC);
        var middle = start.plus(Duration.between(start, high.toInstant(ZoneOffset.UTC)).dividedBy(2));
        return LocalDateTime.ofInstant(middle, ZoneOffset.UTC);
    }

    private static BigDecimal betweenNumbers(BigDecimal low, BigDecimal high) {
        if (low == null) {
            return high == null ? BigDecimal.ZERO : high.subtract(BigDecimal.ONE);
        }
        if (high == null) {
            return low.add(BigDecimal.ONE);
        }
        return low.add(high).divide(BigDecimal.TWO, low.scale() + high.scale() + 1, RoundingMode.HALF_EVEN);
    }

    private boolean isEmpty(ValueClass valueClass) {
        if (!(valueClass instanceof ValueClass.Between(var low, var high))) {
            return false;
        }
        return switch (type) {
            case BOOLEAN -> true; // false and true are both boundaries: nothing lies outside or between them
            case DATE -> low != null && high != null && !((LocalDate) low).plusDays(1).isBefore((LocalDate) high);
            case TIME -> emptyTimes((LocalTime) low, (LocalTime) high);
            case DATE_TIME -> low != null && high != null && !((LocalDateTime) low).plusNanos(1).isBefore((LocalDateTime) high);
            case NUMBER, STRING -> false;
        };
    }

    /** No time of day lies before midnight, after the last nanosecond, or between consecutive nanoseconds. */
    private static boolean emptyTimes(LocalTime low, LocalTime high) {
        if (low == null) {
            return LocalTime.MIDNIGHT.equals(high);
        }
        if (high == null) {
            return LocalTime.MAX.equals(low);
        }
        return !low.plusNanos(1).isBefore(high);
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

    // ---- collecting values ------------------------------------------------------------------------

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }
}
