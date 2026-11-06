/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
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
 * <li>NUMBER and DATE: with the boundary values {@code b0 < b1 < ... < bk} mentioned by the rules
 * (and the allowed values), the classes are {@code (-inf, b0), [b0], (b0, b1), [b1], ..., (bk, +inf)}
 * and a missing value. Lookup is a binary search. For dates, an open gap between consecutive days
 * holds no date; such classes are marked empty.</li>
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
        var classes = new ArrayList<ValueClass>();
        if (type == ColumnType.STRING) {
            var written = new LinkedHashSet<String>();
            cellsByBit.forEach(cell -> collectStrings(cell, written));
            if (allowedValues != null) {
                collectStrings(allowedValues, written);
            }
            var byValue = new HashMap<String, Integer>();
            for (var value : written) {
                byValue.put(value, classes.size());
                classes.add(new ValueClass.Point(value));
            }
            stringClasses = Map.copyOf(byValue);
            otherClass = classes.size();
            classes.add(new ValueClass.OtherString());
            boundaries = null;
        } else {
            var points = new TreeSet<Comparable<?>>((a, b) -> compare(a, b));
            cellsByBit.forEach(cell -> collectBoundaries(cell, points));
            if (allowedValues != null) {
                collectBoundaries(allowedValues, points);
            }
            boundaries = points.toArray(Comparable<?>[]::new);
            for (int i = 0; i <= boundaries.length; i++) {
                var low = i == 0 ? null : boundaries[i - 1];
                var high = i == boundaries.length ? null : boundaries[i];
                classes.add(new ValueClass.Between(low, high));
                if (high != null) {
                    classes.add(new ValueClass.Point(high));
                }
            }
            stringClasses = null;
            otherClass = -1;
        }
        nullClass = classes.size();
        classes.add(new ValueClass.Missing());

        masks = new long[classes.size()][words];
        empty = new boolean[classes.size()];
        allowed = new boolean[classes.size()];
        for (int k = 0; k < classes.size(); k++) {
            var valueClass = classes.get(k);
            empty[k] = isEmpty(valueClass);
            allowed[k] = allowedValues == null || valueClass instanceof ValueClass.Missing || accepts(allowedValues, valueClass);
            for (int bit = 0; bit < cellsByBit.size(); bit++) {
                if (!empty[k] && accepts(cellsByBit.get(bit), valueClass)) {
                    BitsetIndex.set(masks[k], bit);
                }
            }
        }
        this.classes = List.copyOf(classes);
        if (type == ColumnType.STRING) {
            var byValue = new HashMap<String, long[]>();
            var disallowed = new java.util.HashSet<String>();
            stringClasses.forEach((value, k) -> {
                byValue.put(value, masks[k]);
                if (!allowed[k]) {
                    disallowed.add(value);
                }
            });
            stringMasks = Map.copyOf(byValue);
            otherMask = masks[otherClass];
            disallowedStrings = hasAllowedValues ? Set.copyOf(disallowed) : null;
        } else {
            stringMasks = null;
            otherMask = null;
            disallowedStrings = null;
        }
        nullMask = masks[nullClass];
        if (enumValues == null) {
            ordinalMasks = null;
        } else {
            ordinalMasks = new long[enumValues.length][];
            for (int ordinal = 0; ordinal < enumValues.length; ordinal++) {
                try {
                    ordinalMasks[ordinal] = stringMaskSlow(enumValues[ordinal]);
                } catch (InvalidInputException e) {
                    throw new InvalidDecisionTableException("Column '%s': enum value '%s' is not one of the column's input values"
                            .formatted(name, enumValues[ordinal]));
                }
            }
        }
    }

    private final List<ValueClass> classes;

    /**
     * The mask of rules accepting a raw input value: a String (typed getter or text map) or, if the
     * column is enum-bound, an enum constant for STRING columns; a Number, LocalDate or text for NUMBER
     * and DATE columns. Blank text counts as missing.
     *
     * @throws InvalidInputException if the value is outside the declared input values, or text that
     *                               is not a valid number or date
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

    /** Converts a raw NUMBER or DATE input to {@code BigDecimal} / {@code LocalDate}; null if missing. */
    private Object convert(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return type == ColumnType.NUMBER ? new BigDecimal(text.strip()) : LocalDate.parse(text.strip());
            } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
                throw new InvalidInputException(name, text, "is not a valid " + type.name().toLowerCase());
            }
        }
        if (type == ColumnType.DATE) {
            return (LocalDate) raw;
        }
        return switch ((Number) raw) {
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
        int found = Arrays.binarySearch(boundaries, value, (a, b) -> compare(a, b));
        return found >= 0 ? 2 * found + 1 : 2 * (-found - 1);
    }

    int classCount() {
        return masks.length;
    }

    /** A human-readable description of a class, for validation reports. */
    String describe(int valueClass) {
        return classes.get(valueClass).describe(type);
    }

    /**
     * Describes a set of classes for a report. For NUMBER and DATE, adjacent classes merge into
     * intervals, e.g. {@code [18], (18..65), [65]} becomes {@code [18..65]}. Empty classes (date gaps)
     * do not break a run.
     */
    List<String> describe(List<Integer> valueClasses) {
        if (type == ColumnType.STRING) {
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
            case ValueClass.Point p -> "[" + literal(p.value(), type);
            case ValueClass.Between b -> b.low() == null ? "(-inf" : "(" + literal(b.low(), type);
            default -> throw new IllegalStateException("not an ordered class");
        };
        var high = switch (last) {
            case ValueClass.Point p -> literal(p.value(), type) + "]";
            case ValueClass.Between b -> b.high() == null ? "+inf)" : literal(b.high(), type) + ")";
            default -> throw new IllegalStateException("not an ordered class");
        };
        return low + ".." + high;
    }

    /** A concrete value inside a non-empty class, or null for the missing class / any other string. */
    Object representative(int valueClass) {
        return classes.get(valueClass).representative(type);
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

        default String describe(ColumnType type) {
            return switch (this) {
                case Point p -> literal(p.value(), type);
                case Between b -> "(" + (b.low() == null ? "-inf" : literal(b.low(), type)) + ".."
                        + (b.high() == null ? "+inf" : literal(b.high(), type)) + ")";
                case OtherString o -> "any other value";
                case Missing m -> "null";
            };
        }

        default Object representative(ColumnType type) {
            return switch (this) {
                case Point p -> p.value();
                case Between b -> between(b, type);
                case OtherString o -> null;
                case Missing m -> null;
            };
        }
    }

    private static String literal(Comparable<?> value, ColumnType type) {
        return switch (type) {
            case STRING -> "\"" + value + "\"";
            case NUMBER -> ((BigDecimal) value).toPlainString();
            case DATE -> "date(\"" + value + "\")";
        };
    }

    private static Object between(ValueClass.Between b, ColumnType type) {
        if (type == ColumnType.DATE) {
            if (b.low() == null) {
                return b.high() == null ? LocalDate.EPOCH : ((LocalDate) b.high()).minusDays(1);
            }
            return ((LocalDate) b.low()).plusDays(1);
        }
        var low = (BigDecimal) b.low();
        var high = (BigDecimal) b.high();
        if (low == null) {
            return high == null ? BigDecimal.ZERO : high.subtract(BigDecimal.ONE);
        }
        if (high == null) {
            return low.add(BigDecimal.ONE);
        }
        return low.add(high).divide(BigDecimal.TWO, low.scale() + high.scale() + 1, RoundingMode.HALF_EVEN);
    }

    private boolean isEmpty(ValueClass valueClass) {
        return type == ColumnType.DATE
                && valueClass instanceof ValueClass.Between b
                && b.low() != null && b.high() != null
                && !((LocalDate) b.low()).plusDays(1).isBefore((LocalDate) b.high());
    }

    // ---- FEEL semantics per class ---------------------------------------------------------------

    /** Whether every value of the class satisfies the cell. Boundaries make this uniform per class. */
    private static boolean accepts(UnaryTests cell, ValueClass valueClass) {
        return switch (cell) {
            case UnaryTests.Any any -> true;
            case UnaryTests.Positive positive -> disjunction(positive.tests(), valueClass) == Boolean.TRUE;
            case UnaryTests.Negated negated -> {
                var inner = disjunction(negated.tests(), valueClass);
                yield inner == Boolean.FALSE; // not(unknown) is unknown: no match
            }
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
            case Equals e -> switch (valueClass) {
                case ValueClass.Point p -> compare(p.value(), e.value()) == 0;
                default -> false; // literals are boundaries / written values, never inside other classes
            };
            case Compare c -> switch (valueClass) {
                case ValueClass.Point p -> compares(p.value(), c);
                case ValueClass.Between b -> switch (c.operator()) {
                    // c.value() is a boundary, so it lies entirely on one side of the open interval
                    case LT, LE -> b.high() != null && compare(b.high(), c.value()) <= 0;
                    case GT, GE -> b.low() != null && compare(b.low(), c.value()) >= 0;
                };
                default -> false;
            };
            case Range r -> switch (valueClass) {
                case ValueClass.Point p -> inRange(p.value(), r);
                case ValueClass.Between b -> b.low() != null && b.high() != null
                        && compare(r.low(), b.low()) <= 0 && compare(r.high(), b.high()) >= 0;
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
            if (test instanceof Equals e) {
                into.add((String) e.value());
            }
        }
    }

    private static void collectBoundaries(UnaryTests cell, Set<Comparable<?>> into) {
        for (var test : tests(cell)) {
            switch (test) {
                case Equals e -> into.add(e.value());
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
            case UnaryTests.Positive positive -> positive.tests();
            case UnaryTests.Negated negated -> negated.tests();
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }
}
