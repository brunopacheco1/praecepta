/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * The matching of a column whose values are lists, such as a customer's nationalities. A cell holds
 * when some element passes one of its tests ({@code "PT","ES"}: the list holds {@code "PT"} or
 * {@code "ES"}; {@code >= 18}: some element is at least 18), as FEEL's {@code list contains(?, "PT")}
 * and {@code some x in ? satisfies x >= 18} do, and its {@code not(...)} when no element does. A
 * missing list makes every test unknown, as FEEL's list functions return null for it: it passes only
 * {@code -} and {@code null} ({@code ? = null}, a test of the list). An empty list passes no test, so it
 * matches every {@code not(...)}.
 *
 * <p>An element may be missing (null). FEEL answers for it as for a missing single value: a literal is
 * false ({@code null = "PT"}), {@code list contains(?, null)} ({@link UnaryTests.NullElement}) true, and a
 * comparison or an interval unknown. A cell's tests are joined with FEEL's three-valued {@code or} over
 * every element (DMN 1.5, Table 49: {@code some} is "false or FEEL(e, s') or FEEL(e, s'') or ..."), so a
 * positive cell holds when some element passes a test, whatever the others, and its {@code not(...)} only
 * when every test is false for every element: one unknown element makes the cell unknown, and so not a
 * match ({@code not(some x in ? satisfies x >= 18)} does not match {@code [10, null]}).
 *
 * <p>The elements are looked up in an ordinary {@link ColumnIndex} over the same cells without their
 * {@code null} tests ({@link #elementCells}), whose masks hold, for an element, the rules with a positive
 * cell that one of its tests accepts and the rules with a {@code not(...)} cell whose tests are all false
 * for it. One pass over the elements ORs, per rule, "some element passes a test" for the positive rules
 * and "some test is not false for some element" for the negated rules; the list's mask is then the rules
 * with {@code -}, the positive rules some element passes, and the negated rules no element leaves true or
 * unknown: a few word operations per element. The element masks may be dense or sparse ({@link Masks});
 * the list masks are dense.
 */
final class ListColumn {

    private final String name;
    private final ColumnType type;
    /** The index of a single element. */
    private final ColumnIndex elements;
    private final int words;
    private final long[] anyRules;
    private final long[] positiveRules;
    private final long[] negatedRules;
    /** Whether some cell is a {@code not(...)}: if none is, an element's sparse mask is read only on its own words. */
    private final boolean hasNegatedRules;
    /** The rules a missing list matches: {@code -}, and positive cells with {@code null}. */
    private final long[] missingMask;
    /** The rules an empty list matches: {@code -} and every {@code not(...)}. */
    private final long[] emptyMask;

    ListColumn(String name, ColumnType type, ColumnIndex elements, List<UnaryTests> cellsByBit, int words) {
        this.name = name;
        this.type = type;
        this.elements = elements;
        this.words = words;
        anyRules = new long[words];
        positiveRules = new long[words];
        negatedRules = new long[words];
        missingMask = new long[words];
        for (int bit = 0; bit < cellsByBit.size(); bit++) {
            var cell = cellsByBit.get(bit);
            BitsetIndex.set(switch (cell) {
                case UnaryTests.Any any -> anyRules;
                case UnaryTests.Positive positive -> positiveRules;
                case UnaryTests.Negated negated -> negatedRules;
            }, bit);
            // a missing list: ? = null is true, every test of its elements unknown, so a positive cell holds only
            // with null (!= x holds for a missing value, but not for a missing list), and a not(...) never (its
            // inside is true or unknown)
            if (cell instanceof UnaryTests.Any || cell instanceof UnaryTests.Positive && testsTheList(cell)) {
                BitsetIndex.set(missingMask, bit);
            }
        }
        hasNegatedRules = !BitsetIndex.isEmpty(negatedRules);
        emptyMask = anyRules.clone();
        BitsetIndex.or(emptyMask, negatedRules);
    }

    /**
     * The cells as the index of a single element sees them: without their {@code null} tests, which ask
     * whether the list itself is missing ({@code ? = null}) and so are false for every list that is there. A
     * cell left without tests is false for every element ({@code not(null)}: true for every list there).
     */
    static List<UnaryTests> elementCells(List<UnaryTests> cellsByBit) {
        return cellsByBit.stream().map(cell -> switch (cell) {
            case UnaryTests.Any any -> cell;
            case UnaryTests.Positive(var tests) -> testsTheList(cell) ? new UnaryTests.Positive(elementTests(tests)) : cell;
            case UnaryTests.Negated(var tests) -> testsTheList(cell) ? new UnaryTests.Negated(elementTests(tests)) : cell;
        }).toList();
    }

    /** Whether a cell asks whether the list is missing: {@code null}, written {@code ? = null} in a DMN file. */
    static boolean testsTheList(UnaryTests cell) {
        return cell.tests().stream().anyMatch(UnaryTests.NullTest.class::isInstance);
    }

    private static List<UnaryTests.Test> elementTests(List<UnaryTests.Test> tests) {
        return tests.stream().filter(test -> !(test instanceof UnaryTests.NullTest)).toList();
    }

    /**
     * The mask of the rules accepting a raw list: a {@code Collection} of the element type's values (as a
     * getter of a single value returns them), or text holding a FEEL list such as {@code ["PT", "BR"]}
     * (the brackets may be left out, and the elements follow Praecepta's cell syntax, so {@code PT, BR}
     * works too). Null or blank text is a missing list, and {@code []} an empty one. An element may be
     * missing: null or blank text in a collection, {@code null} in text ({@code ["PT", null]}).
     *
     * @return the list's mask, dense; a fresh one for a list with elements, and for a missing or an empty
     *         list a shared one, so never to be changed (as {@link ColumnIndex#maskFor} says of its masks)
     * @throws InvalidInputException if an element is of another Java type than the column's values,
     *                               outside the declared input values or not a valid value of the type, or
     *                               the text is not a list
     */
    long[] maskFor(Object raw) {
        if (isMissing(raw)) {
            return missingMask;
        }
        var values = elementsOf(raw);
        if (values.isEmpty()) {
            return emptyMask;
        }
        var passed = new long[words];
        for (var value : values) {
            var mask = elements.maskFor(element(value, raw));
            if (!Masks.isSparse(mask, words)) {
                passDense(passed, mask);
            } else if (!hasNegatedRules) {
                passPositiveSparse(passed, mask);
            } else {
                passSparse(passed, mask);
            }
        }
        for (int w = 0; w < words; w++) {
            passed[w] = anyRules[w] | passed[w] & positiveRules[w] | ~passed[w] & negatedRules[w];
        }
        return passed;
    }

    /**
     * Adds the rules an element's dense mask passes: the positive rules accepting it, and the negated rules
     * whose inside is not false for it (true, or unknown for a missing element), which no list holding it matches.
     */
    private void passDense(long[] passed, long[] mask) {
        for (int w = 0; w < words; w++) {
            passed[w] |= mask[w] & positiveRules[w] | ~mask[w] & negatedRules[w];
        }
    }

    /**
     * As {@link #passDense}, for a sparse mask (see {@link Masks}) in a column without negated rules: only
     * the mask's own words can pass a positive rule.
     */
    private void passPositiveSparse(long[] passed, long[] mask) {
        for (int pair = 0; pair < mask.length; pair += 2) {
            int w = (int) mask[pair];
            passed[w] |= mask[pair + 1] & positiveRules[w];
        }
    }

    /** As {@link #passDense}, for a sparse mask (see {@link Masks}): the words it leaves out are zero. */
    private void passSparse(long[] passed, long[] mask) {
        int pair = 0;
        for (int w = 0; w < words; w++) {
            long bits = 0;
            if (pair < mask.length && mask[pair] == w) {
                bits = mask[pair + 1];
                pair += 2;
            }
            passed[w] |= bits & positiveRules[w] | ~bits & negatedRules[w];
        }
    }

    /** The rules whose cell is {@code -}. */
    long[] anyRules() {
        return anyRules.clone();
    }

    /** The rules whose cell holds when some element passes a test (neither {@code -} nor {@code not(...)}). */
    long[] positiveRules() {
        return positiveRules.clone();
    }

    /** The rules whose cell is a {@code not(...)}. */
    long[] negatedRules() {
        return negatedRules.clone();
    }

    /** The rules a missing list matches. */
    long[] missingRules() {
        return missingMask.clone();
    }

    /** The index of a single element, whose classes the static analysis builds lists from ({@link ListAnalysis}). */
    ColumnIndex elements() {
        return elements;
    }

    /** Whether a raw list is missing: null or blank text. */
    static boolean isMissing(Object raw) {
        return raw == null || raw instanceof String text && text.isBlank();
    }

    /** The values of a list that is not {@linkplain #isMissing missing}, each as the element index reads it. */
    List<Object> values(Object raw) {
        var values = elementsOf(raw);
        var read = new ArrayList<>(values.size());
        values.forEach(value -> read.add(elements.value(element(value, raw))));
        return read;
    }

    /** The raw elements of a list that is not {@linkplain #isMissing missing}. */
    private Collection<?> elementsOf(Object raw) {
        return switch (raw) {
            case Collection<?> collection -> collection;
            case String text -> parse(text);
            default -> throw new InvalidInputException(name, raw, "is not a list");
        };
    }

    /**
     * A list as text: {@code ["PT", "BR"]}, {@code PT, BR} or {@code []}; {@code null} is a missing element
     * ({@code ["PT", null]}), though the text {@code null} alone is no list: a missing list is null or blank.
     */
    private List<Comparable<?>> parse(String text) {
        var elementsText = text.strip();
        boolean bracketed = elementsText.startsWith("[") && elementsText.endsWith("]");
        if (bracketed) {
            elementsText = elementsText.substring(1, elementsText.length() - 1);
            if (elementsText.isBlank()) {
                return List.of();
            }
        }
        try {
            if (CellParser.parse(elementsText, type) instanceof UnaryTests.Positive(var tests)
                    && (bracketed || tests.stream().anyMatch(ListColumn::isLiteral))
                    && tests.stream().allMatch(test -> test instanceof UnaryTests.NullTest || isLiteral(test))) {
                var values = new ArrayList<Comparable<?>>(tests.size()); // null for a missing element
                tests.forEach(test -> values.add(test instanceof UnaryTests.Equals(var value) ? value : null));
                return values;
            }
        } catch (CellSyntaxException e) {
            // reported below
        }
        throw new InvalidInputException(name, text, "is not a list of %s values, such as [%s]".formatted(type.feelName(), example()));
    }

    private static boolean isLiteral(UnaryTests.Test test) {
        return test instanceof UnaryTests.Equals(var value) && !(value instanceof UnaryTests.Name);
    }

    private String example() {
        return switch (type) {
            case STRING -> "\"PT\", \"BR\"";
            case NUMBER -> "1, 2.5";
            case DATE -> "2025-01-31, 2025-02-01";
            case BOOLEAN -> "true, false";
            case TIME -> "08:30:00, 17:00:00";
            case DATE_TIME -> "2025-01-31T08:30:00";
            case DAY_TIME_DURATION -> "P1D, PT12H";
            case YEAR_MONTH_DURATION -> "P1Y, P6M";
        };
    }

    /**
     * An element, null if missing (null or blank text), else of a Java type the column takes: what a getter
     * of a single value of its type returns, or text, which the element index parses.
     */
    private Object element(Object value, Object list) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return null;
        }
        if (!(value instanceof String) && !takes(value)) {
            throw new InvalidInputException(name, list, "holds a %s, not a %s".formatted(value.getClass().getName(), type.feelName()));
        }
        return value;
    }

    /** Whether an element that is not text is of a Java type of the column's type, as its single-value getter returns it. */
    private boolean takes(Object value) {
        return switch (type) {
            case STRING -> false; // text only
            case NUMBER -> value instanceof Number;
            case DATE -> value instanceof LocalDate;
            case BOOLEAN -> value instanceof Boolean;
            case TIME -> value instanceof LocalTime || value instanceof OffsetTime;
            case DATE_TIME -> value instanceof LocalDateTime || value instanceof ZonedDateTime;
            case DAY_TIME_DURATION -> value instanceof Duration;
            case YEAR_MONTH_DURATION -> value instanceof Period || value instanceof YearsAndMonths;
        };
    }
}
