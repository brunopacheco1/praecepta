/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * One column of the {@link BitsetIndex}. The column's values are partitioned into <em>classes</em>
 * that no rule can tell apart, and each class carries the mask of rules accepting it.
 *
 * <ul>
 * <li>STRING: one class per value written in the column (or declared allowed), one for every other
 * value, and one for a missing value.</li>
 * <li>NUMBER, DATE, TIME, DATE_TIME, DAY_TIME_DURATION and YEAR_MONTH_DURATION, and STRING columns whose cells compare ({@code < "N"},
 * {@code ["A".."M"]}): with the boundary values {@code b0 < b1 < ... < bk} mentioned by the rules
 * (and the allowed values), the classes are {@code (-inf, b0), [b0], (b0, b1), [b1], ..., (bk, +inf)}
 * and a missing value. Lookup is a binary search; a STRING column first looks the value up among its
 * boundaries, as an unordered one does. An interval that holds no value (between consecutive days,
 * or consecutive nanoseconds; before midnight or after the last nanosecond of a day for times; between
 * durations a nanosecond or a month apart; between a string and the same string followed by the
 * character U+0000) is marked empty.</li>
 * <li>BOOLEAN: ordered like the others with {@code false < true}, both always boundaries, so the
 * classes are {@code [false]}, {@code [true]} and a missing value (the intervals are all empty).</li>
 * <li>TIME and DATE_TIME columns take values with and without a time zone, as their input binding gives
 * them ({@link Zoning}). FEEL compares two values with a zone by instant, and any other pair by local
 * time (a value without a zone takes the other's offset). So a value without a zone lies on the line of
 * local times, which the local time of every boundary splits, with or without a zone. A value with a zone
 * has two coordinates: its instant, on a line split by the boundaries with a zone, and its local time, on
 * a line split by the boundaries without one; its class is the pair of their classes, and a test looks at
 * the coordinate of its literal's kind. A pair is empty when no value has both coordinates: a local time
 * and an instant are a whole number of seconds apart, from -14:00 to +14:00, the offsets of FEEL's times and
 * date-times (a getter's value beyond them is no FEEL value, and no class's example). The classes of values
 * without a zone come first, then the pairs, instant by instant.</li>
 * <li>TIME columns also take times with a zone id ({@link ZonedTime}), which compare only with a time in the same
 * zone, by local time; any other comparison of them is one FEEL does not define, so unknown. Each zone id the
 * cells name has a line of local times, split by its boundaries; after those come a class for every other zone
 * id, then the missing class. A column whose inputs may have a zone id (map-based inputs, {@code zonedTimeInput}, a
 * list's elements) has that class even if its cells name none: such a time then matches only the rules that accept
 * any value, and the analyses visit it as they do every class (#114). A time in a zone with one fixed offset
 * ({@code Etc/UTC}) is no such time: it is the time at that offset (DMN 1.5, Table 72), in cells as in inputs
 * ({@link FeelTemporals#withFixedOffset}), so a column bound with {@code zonedTimeInput} also takes times with a zone
 * that has an offset.</li>
 * </ul>
 *
 * Membership is decided per class with FEEL's three-valued logic: a missing value fails comparisons
 * and intervals (unknown), equals only {@code null}, and so satisfies {@code not("A")} and {@code != "A"}.
 *
 * <p>A STRING column bound to an enum precomputes one mask per constant, so its lookup is an array
 * access by ordinal instead of a hash lookup (the int-coded input path).
 *
 * <p>Each class mask is stored dense or, when few rules accept the class, sparse ({@link Masks}); the
 * masks {@link #maskFor} returns are in either form.
 *
 * <p>A column that holds lists ({@link #ofLists}) delegates its lookup to a {@link ListColumn}, which
 * classifies each element with an index of the kinds above; the static analysis reads its lists as sets
 * of those element classes ({@link ListAnalysis}). The masks it returns are dense.
 */
final class ColumnIndex {

    final String name;
    final ColumnType type;
    private final boolean isString;
    /** TIME and DATE_TIME: a value with a time zone lies on the zoned line. */
    private final boolean temporal;
    /**
     * STRING fast path: written value -> mask, plus the masks of other values (null when the column
     * compares, as its other values fall in intervals) and of missing values.
     */
    private final Map<String, long[]> stringMasks;
    private final long[] otherMask;
    private final long[] nullMask;
    /** STRING values written in the rules but outside the declared input values (null if none declared). */
    private final Set<String> disallowedStrings;
    /**
     * Ordered columns (all but STRING, and STRING columns whose cells compare): the line of values without
     * a time zone, which is every value of types other than TIME and DATE_TIME. Null for other STRING
     * columns, and for TIME and DATE_TIME columns whose inputs all have a zone.
     */
    private final Line localLine;
    /** TIME and DATE_TIME columns whose inputs may have a time zone: the line of their instants; else null. */
    private final Line zonedLine;
    /** With {@link #zonedLine}: the line of the local times of values with a zone, split by the boundaries without one. */
    private final Line wallLine;
    /** The number of the first class of values with a time zone: the classes of {@link #localLine} come before. */
    private final int zonedBase;
    /** TIME columns that take times with a zone id: the line of local times of each zone id the cells name; else empty. */
    private final Map<ZoneId, Line> zoneLines;
    /** The number of the first class of each zone id's line. */
    private final Map<ZoneId, Integer> zoneBases;
    /** The number of the first class of times with a zone id; the missing class's when there is none. */
    private final int zoneIdBase;
    /** The class of times with a zone id that no cell names, or -1 if the column takes no time with a zone id. */
    private final int otherZoneIdClass;
    /** Whether the column's input binding takes times with a zone id. */
    private final boolean takesZoneIds;
    /** Whether the column's getter gives times with a zone id only ({@code zonedTimeInput}): its examples are such times. */
    private final boolean bindsZoneIds;
    /** The value classes, by class number. */
    private final List<ValueClass> classes;
    final int nullClass;
    /** Words in a dense mask: the table's rules / 64, rounded up. */
    private final int words;
    /** Per class, the rules (bits) accepting it, dense or sparse ({@link Masks}). */
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
    /** A column whose values are lists: how they match; null for a column of single values. */
    private final ListColumn lists;

    /**
     * A column's cells and how its masks are stored.
     *
     * @param byBit the column's cell of each rule, in bit order
     * @param words the words in a dense mask: the table's rules / 64, rounded up
     * @param form  the form each class mask is stored in
     */
    record Cells(List<UnaryTests> byBit, int words, Masks.Form form) {
    }

    /**
     * @param zoning     for a TIME or DATE_TIME column, whether its inputs come with a time zone, without
     *                   one or either way; ignored for other types
     * @param enumValues for a STRING column bound to an enum: each constant's value, by ordinal; else null
     * @throws InvalidDecisionTableException if an enum value is outside the declared input values
     */
    ColumnIndex(String name, ColumnType type, Cells cells, UnaryTests allowedValues, Zoning zoning, String[] enumValues) {
        this.name = name;
        this.type = type;
        this.isString = type == ColumnType.STRING;
        this.temporal = isTemporal(type);
        this.hasAllowedValues = allowedValues != null;
        this.lists = null;

        var partition = Partition.of(type, cells.byBit(), allowedValues, zoning);
        localLine = partition.localLine();
        zonedLine = partition.zonedLine();
        wallLine = partition.wallLine();
        zonedBase = localLine == null ? 0 : localLine.classCount();
        classes = partition.classes();
        nullClass = classes.size() - 1;
        zoneLines = partition.zoneLines();
        zoneBases = partition.zoneBases();
        zoneIdBase = partition.zoneIdBase();
        otherZoneIdClass = partition.otherZoneIdClass();
        takesZoneIds = type == ColumnType.TIME && zoning.takesZoneIds();
        bindsZoneIds = type == ColumnType.TIME && zoning == Zoning.ZONE_ID;

        this.words = cells.words();
        masks = new long[classes.size()][];
        empty = new boolean[classes.size()];
        allowed = new boolean[classes.size()];
        fillClasses(cells, allowedValues);

        stringMasks = isString ? masksByString(partition.stringClasses()) : null;
        otherMask = partition.otherClass() < 0 ? null : masks[partition.otherClass()];
        disallowedStrings = isString && hasAllowedValues ? writtenButNotAllowed(partition.stringClasses()) : null;
        nullMask = masks[nullClass];
        ordinalMasks = enumValues == null ? null : masksByOrdinal(enumValues);
        this.enumValues = enumValues == null ? null : enumValues.clone();
    }

    /**
     * A column whose values are lists of {@code type} (see {@link ListColumn}); {@code allowedValues}
     * apply to the elements. Its cells test elements, so the classes of single values say nothing about
     * which lists a rule matches: its own classes are "any list", accepted by every rule, and a missing
     * list, and the static analysis reads the lists from the classes of the elements instead
     * ({@link ListAnalysis}).
     *
     * @param cells the column's cells; the class masks of single elements are stored in their form, the list masks are dense
     */
    static ColumnIndex ofLists(String name, ColumnType type, Cells cells, UnaryTests allowedValues, Zoning zoning) {
        var elementCells = new Cells(ListColumn.elementCells(cells.byBit()), cells.words(), cells.form());
        var elements = new ColumnIndex(name, type, elementCells, allowedValues, zoning, null);
        var lists = new ListColumn(name, type, elements, cells.byBit(), cells.words());
        return new ColumnIndex(name, type, lists, cells.byBit().size(), cells.words());
    }

    private ColumnIndex(String name, ColumnType type, ListColumn lists, int rules, int words) {
        this.name = name;
        this.type = type;
        this.lists = lists;
        isString = false;
        temporal = false;
        hasAllowedValues = lists.elements().hasAllowedValues; // they apply to the elements
        stringMasks = null;
        otherMask = null;
        disallowedStrings = null;
        localLine = null;
        zonedLine = null;
        wallLine = null;
        zonedBase = 0;
        classes = List.of(new ValueClass.AnyList(), new ValueClass.Missing());
        nullClass = 1;
        zoneLines = Map.of();
        zoneBases = Map.of();
        zoneIdBase = nullClass;
        otherZoneIdClass = -1;
        takesZoneIds = false;
        bindsZoneIds = false;
        var all = new long[words];
        for (int bit = 0; bit < rules; bit++) {
            BitsetIndex.set(all, bit);
        }
        this.words = words;
        masks = new long[][] {all, all.clone()};
        empty = new boolean[2];
        allowed = new boolean[] {true, true};
        nullMask = masks[nullClass];
        ordinalMasks = null;
        enumValues = null;
    }

    /** Whether the column holds lists. */
    boolean holdsLists() {
        return lists != null;
    }

    /** A column that holds lists: the rules whose cell is {@code -}, which accept every list. */
    long[] rulesAcceptingAnyList() {
        return lists.anyRules();
    }

    /** A column that holds lists: how its lists match, for the static analysis ({@link ListAnalysis}). */
    ListColumn lists() {
        return lists;
    }

    /**
     * Whether a value can be rejected even when no rule is left to match it: the column declares its
     * input values, parses its values (all but STRING), or holds lists, whose elements must be values.
     */
    boolean checksValues() {
        return hasAllowedValues || type != ColumnType.STRING || lists != null;
    }

    // ---- building the classes ---------------------------------------------------------------------

    private static boolean isTemporal(ColumnType type) {
        return type == ColumnType.TIME || type == ColumnType.DATE_TIME;
    }

    /**
     * How a column's values are split into classes, numbered in order, the missing class last.
     *
     * @param localLine     the line of values without a time zone (see {@link ColumnIndex#localLine}), or null
     * @param zonedLine     the line of instants of values with a time zone, or null
     * @param wallLine      with {@code zonedLine}: the line of their local times; else null
     * @param stringClasses STRING: the class of each written, allowed or boundary value; else null
     * @param otherClass    an unordered STRING column: the class of every other value; else -1
     * @param zoneLines     TIME: the line of local times of each zone id the cells name, if the column takes them
     * @param zoneBases     the number of the first class of each of those lines
     * @param zoneIdBase    the number of the first class of times with a zone id (the missing class's if none)
     * @param otherZoneIdClass the class of times in zone ids no cell names, or -1
     */
    private record Partition(Line localLine, Line zonedLine, Line wallLine, List<ValueClass> classes,
            Map<String, Integer> stringClasses, int otherClass, Map<ZoneId, Line> zoneLines, Map<ZoneId, Integer> zoneBases,
            int zoneIdBase, int otherZoneIdClass) {

        static Partition of(ColumnType type, List<UnaryTests> cellsByBit, UnaryTests allowedValues, Zoning zoning) {
            var classes = new ArrayList<ValueClass>();
            if (type == ColumnType.STRING && !hasOrderTests(cellsByBit)) {
                var stringClasses = addStringClasses(cellsByBit, allowedValues, classes);
                int otherClass = classes.size();
                classes.add(new ValueClass.OtherString());
                classes.add(new ValueClass.Missing());
                return new Partition(null, null, null, List.copyOf(classes), stringClasses, otherClass, Map.of(), Map.of(),
                        classes.size() - 1, -1);
            }
            boolean temporal = isTemporal(type);
            Line localLine = null;
            if (!temporal || zoning.takesLocal()) {
                localLine = new Line(sortedBoundaries(type, cellsByBit, allowedValues, Boundaries.LOCAL_TIMES), type);
                classes.addAll(orderedClasses(localLine.boundaries, false));
            }
            Line zonedLine = null;
            Line wallLine = null;
            if (temporal && zoning.takesZoned()) {
                zonedLine = new Line(sortedBoundaries(type, cellsByBit, allowedValues, Boundaries.WITH_ZONE), type);
                wallLine = new Line(sortedBoundaries(type, cellsByBit, allowedValues, Boundaries.WITHOUT_ZONE), type);
                addZonedClasses(zonedLine.boundaries, wallLine.boundaries, classes);
            }
            int zoneIdBase = classes.size();
            var zoneLines = new LinkedHashMap<ZoneId, Line>();
            var zoneBases = new LinkedHashMap<ZoneId, Integer>();
            int otherZoneIdClass = -1;
            if (type == ColumnType.TIME && zoning.takesZoneIds()) {
                zoneIdBoundaries(cellsByBit, allowedValues).forEach((zone, times) -> {
                    zoneLines.put(zone, new Line(times.stream().map(ZonedTime::time).toArray(Comparable<?>[]::new), type));
                    zoneBases.put(zone, classes.size());
                    orderedClasses(times.toArray(Comparable<?>[]::new), false).forEach(part -> classes.add(new ValueClass.InZone(zone, part)));
                });
                // also where the cells name no zone id: such a time then matches only the rules that accept any value (#114)
                otherZoneIdClass = classes.size();
                classes.add(new ValueClass.OtherZoneId());
            }
            classes.add(new ValueClass.Missing());
            var stringClasses = type == ColumnType.STRING ? boundaryClasses(localLine.boundaries) : null;
            return new Partition(localLine, zonedLine, wallLine, List.copyOf(classes), stringClasses, -1, Collections.unmodifiableMap(zoneLines),
                    Collections.unmodifiableMap(zoneBases), zoneIdBase, otherZoneIdClass);
        }

        /** The times with a zone id the cells and the allowed values name, by zone id (in the order of their ids), sorted. */
        private static Map<ZoneId, TreeSet<ZonedTime>> zoneIdBoundaries(List<UnaryTests> cellsByBit, UnaryTests allowedValues) {
            var values = new ArrayList<Comparable<?>>();
            cellsByBit.forEach(cell -> collectBoundaries(cell, values));
            if (allowedValues != null) {
                collectBoundaries(allowedValues, values);
            }
            var byZone = new TreeMap<String, TreeSet<ZonedTime>>();
            values.stream().filter(ZonedTime.class::isInstance).map(ZonedTime.class::cast)
                    .forEach(time -> byZone.computeIfAbsent(time.zone().getId(), any -> new TreeSet<>()).add(time));
            var ordered = new LinkedHashMap<ZoneId, TreeSet<ZonedTime>>();
            byZone.values().forEach(times -> ordered.put(times.first().zone(), times));
            return ordered;
        }

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
        private static boolean hasOrderTests(List<UnaryTests> cellsByBit) {
            return cellsByBit.stream().flatMap(cell -> cell.tests().stream()).anyMatch(test -> test instanceof Compare || test instanceof Range);
        }

        /** Ordered STRING column: the class of each boundary, which is class {@code 2i + 1} for boundary {@code i}. */
        private static Map<String, Integer> boundaryClasses(Comparable<?>[] boundaries) {
            var byValue = new HashMap<String, Integer>();
            for (int i = 0; i < boundaries.length; i++) {
                byValue.put((String) boundaries[i], 2 * i + 1);
            }
            return Map.copyOf(byValue);
        }

        /** Which of the values the cells and the allowed values compare against split a line. */
        private enum Boundaries {
            /** Every value, by its local time: the line of values without a time zone, and of the types that have none. */
            LOCAL_TIMES,
            /** The values with a time zone, by instant. */
            WITH_ZONE,
            /** The values without a time zone: the local times a value with a zone is compared with. */
            WITHOUT_ZONE;

            /** The boundary a value is on this line, or null if it is none; a time with a zone id is on a line of its zone. */
            Object of(Comparable<?> value) {
                if (value instanceof ZonedTime) {
                    return null;
                }
                return switch (this) {
                    case LOCAL_TIMES -> FeelTemporals.local(value);
                    case WITH_ZONE -> FeelTemporals.hasZone(value) ? value : null;
                    case WITHOUT_ZONE -> FeelTemporals.hasZone(value) ? null : value;
                };
            }
        }

        /** Ordered columns: the distinct boundaries of a line, sorted. */
        private static Comparable<?>[] sortedBoundaries(ColumnType type, List<UnaryTests> cellsByBit, UnaryTests allowedValues,
                Boundaries which) {
            var values = new ArrayList<Comparable<?>>();
            if (type == ColumnType.BOOLEAN) {
                values.add(Boolean.FALSE);
                values.add(Boolean.TRUE);
            }
            cellsByBit.forEach(cell -> collectBoundaries(cell, values));
            if (allowedValues != null) {
                collectBoundaries(allowedValues, values);
            }
            // a line sorts values of one kind only: across the kinds, FEEL's order is not transitive
            var points = new TreeSet<>(ColumnIndex::compare);
            values.stream().map(which::of).filter(Objects::nonNull).forEach(points::add);
            return points.toArray(Comparable<?>[]::new);
        }

        private static void collectStrings(UnaryTests cell, Set<String> into) {
            for (var test : cell.tests()) {
                var equality = test instanceof NotEqual(var negated) ? negated : test;
                if (equality instanceof Equals(var value, var explicit)) {
                    into.add((String) value);
                }
            }
        }

        /** Adds the values of every test; with or without a time zone; each line then takes its own. */
        private static void collectBoundaries(UnaryTests cell, List<Comparable<?>> into) {
            cell.tests().forEach(test -> into.addAll(UnaryTests.values(test)));
        }

        /** A line's classes: the interval before each boundary, the boundary itself, and the interval after the last one. */
        private static List<ValueClass> orderedClasses(Comparable<?>[] boundaries, boolean zoned) {
            var classes = new ArrayList<ValueClass>();
            Comparable<?> low = null;
            for (var boundary : boundaries) {
                classes.add(new ValueClass.Between(low, boundary, zoned));
                classes.add(new ValueClass.Point(boundary));
                low = boundary;
            }
            classes.add(new ValueClass.Between(low, null, zoned));
            return classes;
        }

        /** The classes of values with a time zone: each class of instants paired with each class of local times. */
        private static void addZonedClasses(Comparable<?>[] instants, Comparable<?>[] localTimes, List<ValueClass> into) {
            var walls = orderedClasses(localTimes, false);
            for (var instant : orderedClasses(instants, true)) {
                for (var wall : walls) {
                    into.add(new ValueClass.Zoned(instant, wall));
                }
            }
        }
    }

    /**
     * Fills in each class whether it is empty and allowed, and the mask of the rules accepting it: built
     * dense one class at a time, then stored in its form, so never all dense at once.
     */
    private void fillClasses(Cells cells, UnaryTests allowedValues) {
        for (int k = 0; k < classes.size(); k++) {
            var valueClass = classes.get(k);
            empty[k] = isEmpty(valueClass);
            allowed[k] = allowedValues == null || valueClass instanceof ValueClass.Missing || accepts(allowedValues, valueClass);
            var mask = new long[words];
            if (!empty[k]) {
                markAcceptingRules(mask, cells.byBit(), valueClass);
            }
            masks[k] = Masks.compact(mask, cells.form());
        }
    }

    /** Whether no value lies in the class (see {@link OpenIntervals}). */
    private boolean isEmpty(ValueClass valueClass) {
        return switch (valueClass) {
            case ValueClass.Between(var low, var high, var zoned) -> OpenIntervals.isEmpty(type, low, high, zoned);
            case ValueClass.Zoned(var instant, var wall) -> OpenIntervals.isEmpty(type, span(instant), span(wall));
            case ValueClass.InZone(var zone, ValueClass.Between(var low, var high, var zoned)) ->
                    OpenIntervals.isEmpty(type, localTime(low), localTime(high), false);
            default -> false;
        };
    }

    /** The local time of a boundary of a zone id's line, a {@link ZonedTime}; null for an unbounded end. */
    private static LocalTime localTime(Comparable<?> boundary) {
        return boundary == null ? null : ((ZonedTime) boundary).time();
    }

    /** A class of one line as the values it spans: a boundary alone, or the open interval between two. */
    private static OpenIntervals.Span span(ValueClass part) {
        return switch (part) {
            case ValueClass.Point(var value) -> new OpenIntervals.Span(value, value, true);
            case ValueClass.Between(var low, var high, var zoned) -> new OpenIntervals.Span(low, high, false);
            default -> throw new IllegalStateException("not an ordered class");
        };
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
     * LocalDate, Boolean, LocalTime, OffsetTime, LocalDateTime, ZonedDateTime, Duration or Period) or its
     * text. Blank text counts as missing.
     *
     * @return the class's mask, dense or sparse ({@link Masks}); shared, so never to be changed (a list
     *         column's mask, see {@link ListColumn#maskFor}, is dense, and shared for a missing or empty list)
     * @throws InvalidInputException if the value is outside the declared input values, or text that
     *                               is not a valid value of the column's type (for a list, an element)
     */
    long[] maskFor(Object raw) {
        // a list column is not a STRING column (isString is false), so the STRING hot path below never asks
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
        if (lists != null) {
            return lists.maskFor(raw);
        }
        return maskOf(convert(raw));
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
            if (localLine != null) {
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

    /** The mask of a converted value of an ordered column (null if missing), checked against the declared values. */
    private long[] maskOf(Object value) {
        int valueClass = classOf(value);
        if (hasAllowedValues && !allowed[valueClass]) {
            throw new InvalidInputException(name, value);
        }
        return masks[valueClass];
    }

    /** The class of a converted value of an ordered column, or the missing class for null. */
    private int classOf(Object value) {
        if (value == null) {
            return nullClass;
        }
        if (!temporal) {
            return localLine.classOf(value);
        }
        if (value instanceof ZonedTime time) {
            return zoneIdClassOf(time);
        }
        boolean zoned = FeelTemporals.hasZone(value);
        if ((zoned ? zonedLine : localLine) == null) { // only a getter that breaks its declared type can get here
            throw new InvalidInputException(name, value, zoned
                    ? "has a time zone, which the column's input binding does not take"
                    : "has no time zone, which the column's input binding requires");
        }
        if (!zoned) {
            return localLine.classOf(value);
        }
        // a boundary with a zone is compared with the value's instant, one without with its local time
        int wall = wallLine.boundaries.length == 0 ? 0 : wallLine.classOf(FeelTemporals.local(value));
        return zonedBase + zonedLine.classOf(value) * wallLine.classCount() + wall;
    }

    /** The class of a time with a zone id: on its zone's line, or the class of the zones no cell names. */
    private int zoneIdClassOf(ZonedTime time) {
        if (!takesZoneIds) { // only a getter that breaks its declared type can get here
            throw new InvalidInputException(name, time, "has a zone id, which the column's input binding does not take");
        }
        var line = zoneLines.get(time.zone());
        return line == null ? otherZoneIdClass : zoneBases.get(time.zone()) + line.classOf(time.time());
    }

    /**
     * The class of a value from its binary search among the boundaries: boundary {@code i} is class
     * {@code 2i + 1}, and the interval just below it is class {@code 2i}.
     */
    static int classOfSearch(int found) {
        return found >= 0 ? 2 * found + 1 : 2 * (-found - 1);
    }

    /**
     * The value of a raw input, as {@link #maskFor} reads it: a String (an enum constant's value) for a
     * STRING column, else the column's Java type; null if missing. The value has already been checked
     * by {@link #maskFor}, when the input was matched.
     */
    Object value(Object raw) {
        if (lists != null) {
            return ListColumn.isMissing(raw) ? null : lists.values(raw);
        }
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
                    case DATE -> FeelTemporals.parseDate(value);
                    case TIME -> FeelTemporals.parseTime(value);
                    case DATE_TIME -> FeelTemporals.parseDateTime(value);
                    case DAY_TIME_DURATION -> Duration.parse(value);
                    case YEAR_MONTH_DURATION -> YearsAndMonths.parse(value);
                    case BOOLEAN -> bool(text);
                    case STRING -> throw new IllegalStateException("STRING values are looked up as they are, not converted");
                };
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw notValid(text);
            }
        }
        // typed getters already return the column's Java type (LocalDate, LocalTime, OffsetTime, ...); numbers vary,
        // a years and months duration is bound as a Period, and a time in a zone with one fixed offset is the time at
        // that offset (DMN 1.5, Table 72)
        return switch (type) {
            case NUMBER -> number((Number) raw);
            case TIME -> FeelTemporals.withFixedOffset(raw);
            // a list's elements read from text are already years and months
            case YEAR_MONTH_DURATION -> raw instanceof YearsAndMonths duration ? duration : yearsAndMonths((Period) raw);
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

    /** Whether the rule owning {@code bit} accepts the class. */
    boolean accepts(int valueClass, int bit) {
        return Masks.get(masks[valueClass], words, bit);
    }

    /**
     * Whether one class stands for every value the rules do not mention: a STRING column whose cells
     * do not compare. Every such value is then a gap unless the column declares its input values. For a
     * column that holds lists, whether its elements' index has such a class.
     */
    boolean groupsOtherValues() {
        return lists != null ? lists.elements().groupsOtherValues() : otherMask != null;
    }

    /** Whether a valid input can fall in the class: it holds some value and the declared input values allow it. */
    boolean canOccur(int valueClass) {
        return !empty[valueClass] && allowed[valueClass];
    }

    /** A human-readable description of a class, for validation reports. */
    String describe(int valueClass) {
        return describe(List.of(valueClass)).getFirst();
    }

    /**
     * Describes a set of distinct classes for a report. For ordered columns with comparisons (numbers,
     * dates, times and comparing strings), adjacent classes merge into intervals, e.g.
     * {@code [18], (18..65), [65]} becomes {@code [18..65]}. Empty classes do not break a run.
     *
     * <p>In a TIME or DATE_TIME column that takes values with and without a time zone, the classes of
     * those with a zone are described after the others: by their instants, when no boundary lacks a zone;
     * not at all when no boundary has one and the same local times are already described for values
     * without a zone (the zone then makes no difference); else instant by instant, with the local times.
     */
    List<String> describe(List<Integer> valueClasses) {
        if (valueClasses.stream().anyMatch(this::hasZoneId)) {
            return describeWithZoneIds(valueClasses);
        }
        if (localLine == null && zonedLine == null || !type.supportsComparisons()) { // no intervals to merge
            return valueClasses.stream().map(k -> plain(classes.get(k))).toList();
        }
        return describeAsIntervals(valueClasses);
    }

    /** Classes some of which are of times with a zone id: the others first, then those zone id by zone id, then null. */
    private List<String> describeWithZoneIds(List<Integer> valueClasses) {
        var parts = new ArrayList<String>();
        var others = valueClasses.stream().filter(k -> !hasZoneId(k) && k != nullClass).toList();
        if (!others.isEmpty()) {
            parts.addAll(describe(others));
        }
        parts.addAll(zoneIdRuns(valueClasses.stream().filter(this::hasZoneId).sorted().toList()));
        if (valueClasses.contains(nullClass)) {
            parts.add(plain(classes.get(nullClass)));
        }
        return parts;
    }

    /**
     * Classes of an ordered column, adjacent ones merged into intervals: those of values without a zone, then those
     * with one (unless they mirror the others), then null.
     */
    private List<String> describeAsIntervals(List<Integer> valueClasses) {
        var sorted = valueClasses.stream().sorted().toList();
        var local = sorted.stream().filter(k -> k != nullClass && !hasZone(k)).toList();
        var zoned = sorted.stream().filter(this::hasZone).map(k -> k - zonedBase).toList(); // numbered from the first class with a zone
        boolean bothKinds = localLine != null && zonedLine != null;
        // without boundaries with a zone, the classes with a zone mirror those without: same local times, same rules
        boolean mirrored = bothKinds && zonedLine.boundaries.length == 0 && zoned.equals(local);
        var localWhole = bothKinds && !mirrored ? whole(false) : null;
        // in a column that also takes times with a zone id (zonedTimeInput), "any time with a time zone" too
        var zonedWhole = bothKinds || zonedLine != null && takesZoneIds ? whole(true) : null;
        var parts = new ArrayList<String>();
        if (!local.isEmpty()) {
            parts.addAll(runs(local, classes::get, k -> empty[k], localLine.classCount(), localWhole));
        }
        if (!zoned.isEmpty() && !mirrored) {
            parts.addAll(zonedRuns(zoned, zonedWhole));
        }
        if (valueClasses.contains(nullClass)) {
            parts.add(plain(classes.get(nullClass)));
        }
        return parts;
    }

    /** Whether a class is one of values with a time zone that has an offset. */
    private boolean hasZone(int valueClass) {
        return zonedLine != null && valueClass >= zonedBase && valueClass < zoneIdBase;
    }

    /** Whether a class is one of times with a zone id. */
    private boolean hasZoneId(int valueClass) {
        return valueClass >= zoneIdBase && valueClass != nullClass;
    }

    /** Sorted classes of times with a zone id, as runs: zone id by zone id, then the zone ids no cell names. */
    private List<String> zoneIdRuns(List<Integer> zoneIdClasses) {
        var runs = new ArrayList<String>();
        for (var zone : zoneBases.entrySet()) {
            int base = zone.getValue();
            int count = zoneLines.get(zone.getKey()).classCount();
            var inZone = zoneIdClasses.stream().filter(k -> k >= base && k < base + count).map(k -> k - base).toList();
            if (!inZone.isEmpty()) {
                runs.addAll(runs(inZone, i -> ((ValueClass.InZone) classes.get(base + i)).part(), i -> empty[base + i], count,
                        "any time at " + zone.getKey().getId()));
            }
        }
        if (zoneIdClasses.contains(otherZoneIdClass)) {
            runs.add(plain(classes.get(otherZoneIdClass)));
        }
        return runs;
    }

    /** How every value of one kind is described in a column that takes both: {@code any time with a time zone}. */
    private String whole(boolean zoned) {
        return "any " + type.feelName() + (zoned ? " with" : " without") + " a time zone";
    }

    /** A class on its own. */
    private static String plain(ValueClass valueClass) {
        return switch (valueClass) {
            case ValueClass.Point(var value) -> FeelLiterals.literal(value);
            case ValueClass.OtherString other -> "any other value";
            case ValueClass.AnyList list -> "any list";
            case ValueClass.Missing missing -> "null";
            case ValueClass.Between between -> run(between, between);
            case ValueClass.Zoned zoned -> throw new IllegalStateException("classes with a time zone are described as runs");
            case ValueClass.InZone(var zone, var part) -> plain(part);
            case ValueClass.OtherZoneId other -> "any time with a zone id no cell names";
        };
    }

    /**
     * The classes of values with a time zone, numbered from the first one, as runs.
     *
     * @param whole how all of them are described, or null to describe them as an interval
     */
    private List<String> zonedRuns(List<Integer> zoned, String whole) {
        int walls = wallLine.classCount();
        if (walls == 1) { // no boundary without a zone: instants only
            return runs(zoned, i -> instantPart(i * walls), i -> empty[zonedBase + i], zonedLine.classCount(), whole);
        }
        if (zonedLine.boundaries.length == 0) { // no boundary with a zone: local times only
            var runs = runs(zoned, this::wallPart, w -> empty[zonedBase + w], walls, whole);
            if (whole == null) {
                return runs;
            }
            return runs.stream().map(run -> run.equals(whole) ? run : run + " with a time zone").toList();
        }
        return instantByInstantRuns(zoned, whole);
    }

    /**
     * The classes of values with a time zone, numbered from the first one, as runs, when boundaries both
     * with and without a zone split them: the instants taken with all their local times merge into runs;
     * the others are given with their local times.
     *
     * @param whole how all of them are described, or null to describe them as an interval
     */
    private List<String> instantByInstantRuns(List<Integer> zoned, String whole) {
        int walls = wallLine.classCount();
        var wholeInstants = new ArrayList<Integer>();
        var partial = new ArrayList<String>();
        int at = 0;
        while (at < zoned.size()) {
            int instant = zoned.get(at) / walls;
            var localTimes = new ArrayList<Integer>();
            while (at < zoned.size() && zoned.get(at) / walls == instant) {
                localTimes.add(zoned.get(at++) % walls);
            }
            int row = zonedBase + instant * walls;
            if (coversAll(localTimes, w -> empty[row + w], walls)) {
                wholeInstants.add(instant);
            } else {
                var instants = run(instantPart(instant * walls), instantPart(instant * walls));
                runs(localTimes, this::wallPart, w -> empty[row + w], walls, null)
                        .forEach(localRun -> partial.add(instants + " at local time " + localRun));
            }
        }
        var runs = new ArrayList<String>();
        if (!wholeInstants.isEmpty()) {
            IntPredicate noValueAt = instant -> IntStream.range(0, walls).allMatch(w -> empty[zonedBase + instant * walls + w]);
            runs.addAll(runs(wholeInstants, i -> instantPart(i * walls), noValueAt, zonedLine.classCount(), whole));
        }
        runs.addAll(partial);
        return runs;
    }

    /** The instants of a class with a time zone, numbered from the first one. */
    private ValueClass instantPart(int zonedClass) {
        return ((ValueClass.Zoned) classes.get(zonedBase + zonedClass)).instant();
    }

    /** The local times of a class with a time zone, numbered from the first one (any instant has the same). */
    private ValueClass wallPart(int zonedClass) {
        return ((ValueClass.Zoned) classes.get(zonedBase + zonedClass)).wall();
    }

    /**
     * Sorted classes of one line as runs: each maximal series with only empty classes in between becomes one
     * interval, such as {@code [18..65)}, or a single value.
     *
     * @param part    the class at a number of the line
     * @param isEmpty whether the class at a number holds no value
     * @param count   the number of classes of the line
     * @param whole   how a run that leaves out only empty classes is described, or null for {@code (-inf..+inf)}
     */
    private static List<String> runs(List<Integer> sorted, IntFunction<ValueClass> part, IntPredicate isEmpty, int count, String whole) {
        if (whole != null && coversAll(sorted, isEmpty, count)) {
            return List.of(whole);
        }
        var runs = new ArrayList<String>();
        int start = sorted.getFirst();
        int end = start;
        for (int next : sorted.subList(1, sorted.size())) {
            if (!IntStream.range(end + 1, next).allMatch(isEmpty)) {
                runs.add(run(part.apply(start), part.apply(end)));
                start = next;
            }
            end = next;
        }
        runs.add(run(part.apply(start), part.apply(end)));
        return runs;
    }

    /** Whether the classes left out of {@code sorted}, among {@code count}, are all empty. */
    private static boolean coversAll(List<Integer> sorted, IntPredicate isEmpty, int count) {
        var covered = new HashSet<>(sorted);
        return IntStream.range(0, count).allMatch(k -> covered.contains(k) || isEmpty.test(k));
    }

    /** The classes from {@code first} to {@code last} of a line: one value, or an interval such as {@code [18..65)}. */
    private static String run(ValueClass first, ValueClass last) {
        if (first == last && first instanceof ValueClass.Point(var value)) {
            return FeelLiterals.literal(value);
        }
        return lowEnd(first) + ".." + highEnd(last);
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
            case ValueClass.Between(var low, var high, var zoned) -> bindable(OpenIntervals.valueInside(type, low, high, zoned));
            case ValueClass.Zoned(var instant, var wall) -> zonedBindable(OpenIntervals.valueInside(type, span(instant), span(wall)));
            case ValueClass.InZone(var zone, ValueClass.Point(var value)) -> value;
            case ValueClass.InZone(var zone, ValueClass.Between(var low, var high, var zoned)) ->
                    new ZonedTime((LocalTime) OpenIntervals.valueInside(type, localTime(low), localTime(high), false), zone);
            case ValueClass.InZone other -> throw new IllegalStateException("not a class of a zone id's line: " + other);
            case ValueClass.OtherZoneId other -> new ZonedTime(LocalTime.NOON, unnamedZone());
            case ValueClass.OtherString other -> null;
            case ValueClass.AnyList list -> List.of();
            case ValueClass.Missing missing -> null;
        };
    }

    /**
     * A zone id no cell names: the first, by id, of those the JDK knows whose offset changes (one with a fixed offset
     * is a time at that offset, DMN 1.5, Table 72).
     */
    private ZoneId unnamedZone() {
        return ZoneId.getAvailableZoneIds().stream().sorted().map(ZoneId::of)
                .filter(zone -> !zone.getRules().isFixedOffset() && !zoneLines.containsKey(zone))
                .findFirst().orElseThrow();
    }

    /**
     * A time with a time zone as a getter of the column returns it: a {@code zonedTimeInput} getter gives a
     * {@link ZonedTime}, so a time at an offset is given in the zone id that has that offset fixed, which the engine
     * reads back as the time at that offset; any other value as it is.
     */
    private Object zonedBindable(Object value) {
        if (bindsZoneIds && value instanceof OffsetTime time) {
            return new ZonedTime(time.toLocalTime(), ZoneId.ofOffset("UTC", time.getOffset()));
        }
        return value;
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
            case UnaryTests.Positive(var tests) -> disjunction(tests, valueClass) == Truth.TRUE;
            // not(unknown) is unknown: no match
            case UnaryTests.Negated(var tests) -> disjunction(tests, valueClass) == Truth.FALSE;
        };
    }

    /** FEEL disjunction: true if any test is true, else unknown if any is unknown, else false. */
    private static Truth disjunction(List<Test> tests, ValueClass valueClass) {
        var result = Truth.FALSE;
        for (var test : tests) {
            var outcome = test(test, valueClass);
            if (outcome == Truth.TRUE) {
                return Truth.TRUE;
            }
            if (outcome == Truth.UNKNOWN) {
                result = Truth.UNKNOWN;
            }
        }
        return result;
    }

    private static Truth test(Test test, ValueClass valueClass) {
        if (test instanceof NotEqual(var negated)) { // != is not(=), so unknown stays unknown
            return test(negated, valueClass).not();
        }
        if (valueClass instanceof ValueClass.Missing) {
            return switch (test) {
                case NullTest n -> Truth.TRUE;
                case UnaryTests.NullElement n -> Truth.TRUE; // an element index's cell: the element is missing
                case Equals e -> Truth.FALSE;       // null = x is false
                case Compare c -> Truth.UNKNOWN;    // null < x is unknown
                case Range r -> Truth.UNKNOWN;
                case NotEqual n -> throw new IllegalStateException("decided above");
            };
        }
        if (!comparable(valueClass, test)) {
            return Truth.UNKNOWN; // FEEL gives null for a comparison it does not define, of a time with a zone id
        }
        return switch (test) {
            case NotEqual n -> throw new IllegalStateException("decided above");
            case NullTest n -> Truth.FALSE;
            case UnaryTests.NullElement n -> Truth.FALSE;
            // literals are boundaries / written values, never inside other classes
            case Equals(var literal, var explicit) -> Truth.of(
                    coordinate(valueClass, literal) instanceof ValueClass.Point(var value) && compare(value, literal) == 0);
            case Compare(var operator, var literal) -> Truth.of(switch (operator) {
                case LT -> below(coordinate(valueClass, literal), literal, false);
                case LE -> below(coordinate(valueClass, literal), literal, true);
                case GT -> above(coordinate(valueClass, literal), literal, false);
                case GE -> above(coordinate(valueClass, literal), literal, true);
            });
            case Range(var low, var lowClosed, var high, var highClosed) -> Truth.of(above(coordinate(valueClass, low), low, lowClosed)
                    && below(coordinate(valueClass, high), high, highClosed));
        };
    }

    /**
     * Whether FEEL compares the values of a class with a test's literals: a time with a zone id only with times in its
     * zone, and any other value with anything but such a time ({@link FeelTemporals#comparable}).
     */
    private static boolean comparable(ValueClass valueClass, Test test) {
        // run for every class and test while the masks are built: no list, and no zone unless a value has one
        ZoneId zone = switch (valueClass) {
            case ValueClass.InZone(var inZone, var part) -> inZone;
            case ValueClass.OtherZoneId other -> OTHER_ZONE;
            default -> null;
        };
        return switch (test) {
            case Equals(var literal, var explicit) -> inZone(literal, zone);
            case Compare(var operator, var literal) -> inZone(literal, zone);
            case Range(var low, var lowClosed, var high, var highClosed) -> inZone(low, zone) && inZone(high, zone);
            default -> true; // null, a missing element: no literal ({@code !=} is decided before)
        };
    }

    /** Stands for the zone ids no cell names, which no literal is in. */
    private static final ZoneId OTHER_ZONE = ZoneOffset.UTC;

    /** Whether a literal is a time with a zone id in {@code zone}, or, for a null zone, is not a time with a zone id. */
    private static boolean inZone(Comparable<?> literal, ZoneId zone) {
        if (literal instanceof ZonedTime time) {
            return time.zone().equals(zone);
        }
        return zone == null;
    }

    /**
     * What a class is compared with a literal by. A class of values with a time zone has two coordinates:
     * FEEL compares its instants with a literal that has a zone, and its local times with one that has
     * none. Any other class is compared as it is; {@link #compare} then takes a literal with a zone by
     * its local time against values without one.
     */
    private static ValueClass coordinate(ValueClass valueClass, Comparable<?> literal) {
        if (valueClass instanceof ValueClass.Zoned(var instant, var wall)) {
            return FeelTemporals.hasZone(literal) ? instant : wall;
        }
        if (valueClass instanceof ValueClass.InZone(var zone, var part)) {
            return part;
        }
        return valueClass;
    }

    /** Whether every value of a line's class is above the bound, or equal to it if {@code closed}. */
    private static boolean above(ValueClass part, Comparable<?> bound, boolean closed) {
        return switch (part) {
            case ValueClass.Point(var value) -> compare(value, bound) > (closed ? -1 : 0);
            // the bound is a boundary of the line, so it lies entirely on one side of the open interval
            case ValueClass.Between(var low, var high, var zoned) -> low != null && compare(low, bound) >= 0;
            default -> false;
        };
    }

    /** Whether every value of a line's class is below the bound, or equal to it if {@code closed}. */
    private static boolean below(ValueClass part, Comparable<?> bound, boolean closed) {
        return switch (part) {
            case ValueClass.Point(var value) -> compare(value, bound) < (closed ? 1 : 0);
            case ValueClass.Between(var low, var high, var zoned) -> high != null && compare(high, bound) <= 0;
            default -> false;
        };
    }

    /**
     * FEEL's order ({@link FeelTemporals#compare}): by instant for two values with a time zone, by local
     * time when only one has a zone.
     */
    private static int compare(Object a, Object b) {
        return FeelTemporals.compare(a, b);
    }

    // ---- value classes ------------------------------------------------------------------------

    private sealed interface ValueClass {
        /** One value: a boundary of an ordered column, or a string written in the rules or declared. */
        record Point(Comparable<?> value) implements ValueClass {
        }

        /** Open interval between two boundaries of a line, of instants ({@code zoned}) or not; null means unbounded. */
        record Between(Comparable<?> low, Comparable<?> high, boolean zoned) implements ValueClass {
        }

        /**
         * Times or date-times with a time zone: those whose instant is in one class of the line of instants
         * and whose local time is in one class of the line of local times (each a {@link Point} or a {@link Between}).
         */
        record Zoned(ValueClass instant, ValueClass wall) implements ValueClass {
        }

        /** Times with a zone id in one zone: a class of the line of that zone's local times, its ends {@link ZonedTime}s. */
        record InZone(ZoneId zone, ValueClass part) implements ValueClass {
        }

        /** Times with a zone id in the zones no cell names. */
        record OtherZoneId() implements ValueClass {
        }

        /** STRING: every value not written in the rules or declared. */
        record OtherString() implements ValueClass {
        }

        /** A column that holds lists, to the static analysis: every list that is not missing. */
        record AnyList() implements ValueClass {
        }

        record Missing() implements ValueClass {
        }
    }

    /**
     * An ordered line of values, split by its sorted distinct boundaries {@code b0 < ... < bk} into the
     * classes {@code (-inf, b0), [b0], ..., (bk, +inf)}, numbered from 0: boundary {@code i} is class
     * {@code 2i + 1}. A class, not a record: it holds an array and is never compared.
     */
    private static final class Line {
        final Comparable<?>[] boundaries;
        /** The boundaries as primitive keys, searched first; null for strings, booleans, or boundaries that do not fit. */
        final PrimitiveKeys keys;

        Line(Comparable<?>[] boundaries, ColumnType type) {
            this.boundaries = boundaries;
            this.keys = PrimitiveKeys.of(type, boundaries);
        }

        /** The class of a value of the line (see {@link #classOfSearch}). */
        int classOf(Object value) {
            if (keys != null) {
                int byKey = keys.classOf(value);
                if (byKey >= 0) {
                    return byKey;
                }
            }
            return classOfSearch(Arrays.binarySearch(boundaries, value, ColumnIndex::compare));
        }

        int classCount() {
            return 2 * boundaries.length + 1;
        }
    }
}
