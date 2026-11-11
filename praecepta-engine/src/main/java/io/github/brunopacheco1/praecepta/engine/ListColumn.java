/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * The matching of a column whose values are lists, such as a customer's nationalities. A cell holds
 * when some element passes one of its tests ({@code "PT","ES"}: the list holds {@code "PT"} or
 * {@code "ES"}; {@code >= 18}: some element is at least 18), as FEEL's {@code list contains(?, "PT")}
 * and {@code some x in ? satisfies x >= 18} do, and its {@code not(...)} when no element does. A
 * missing list makes every test unknown, as FEEL's list functions return null for it: it passes only
 * {@code -} and {@code null}. An empty list passes no test, so it matches every {@code not(...)}.
 *
 * <p>The elements are looked up in an ordinary {@link ColumnIndex} over the same cells, whose masks hold,
 * for an element, the rules with a positive cell that one of its tests accepts and the rules with a
 * {@code not(...)} cell that none of its tests accepts. An element is never missing, so its tests are
 * unknown only when FEEL cannot compare it with a literal (a time with a time zone and one without):
 * the positive rule then misses the element's bit, and the negated rule too, which counts here as "some
 * element may pass". So, as with FEEL's three-valued {@code or}, a positive cell holds when some element
 * is known to pass and a {@code not(...)} when every element is known to fail. One pass over the
 * elements ORs, per rule, "some element passes a test"; the list's mask is then the rules with
 * {@code -}, the positive rules some element passes, and the negated rules none passes: a few word
 * operations per element. The element masks may be dense or sparse ({@link Masks});
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
        for (int bit = 0; bit < cellsByBit.size(); bit++) {
            BitsetIndex.set(switch (cellsByBit.get(bit)) {
                case UnaryTests.Any any -> anyRules;
                case UnaryTests.Positive positive -> positiveRules;
                case UnaryTests.Negated negated -> negatedRules;
            }, bit);
        }
        // a missing element's class: positive cells with null pass, comparisons are unknown; not(...) of an
        // unknown list is unknown, so no negated rule matches
        missingMask = Masks.dense(elements.masks[elements.nullClass], words).clone();
        BitsetIndex.andNot(missingMask, negatedRules);
        emptyMask = anyRules.clone();
        BitsetIndex.or(emptyMask, negatedRules);
    }

    /**
     * The mask of the rules accepting a raw list: a {@code Collection} of the element type's values (as a
     * getter of a single value returns them), or text holding a FEEL list such as {@code ["PT", "BR"]}
     * (the brackets may be left out, and the elements follow Praecepta's cell syntax, so {@code PT, BR}
     * works too). Null or blank text is a missing list, and {@code []} an empty one. A fresh mask.
     *
     * @throws InvalidInputException if an element is missing, outside the declared input values or not a
     *                               valid value of the type, or the text is not a list
     */
    long[] maskFor(Object raw) {
        var values = elementsOf(raw);
        if (values == null) {
            return missingMask;
        }
        if (values.isEmpty()) {
            return emptyMask;
        }
        var passed = new long[words];
        for (var value : values) {
            var mask = elements.maskFor(element(value, raw));
            if (!Masks.isSparse(mask, words)) {
                for (int w = 0; w < words; w++) {
                    passed[w] |= mask[w] & positiveRules[w] | ~mask[w] & negatedRules[w];
                }
                continue;
            }
            // a sparse mask (see Masks): the words it leaves out are zero
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
        for (int w = 0; w < words; w++) {
            passed[w] = anyRules[w] | passed[w] & positiveRules[w] | ~passed[w] & negatedRules[w];
        }
        return passed;
    }

    /** The rules whose cell is {@code -}. */
    long[] anyRules() {
        return anyRules.clone();
    }

    /** The list's values, each as the element index reads it, or null if the list is missing. */
    List<Object> values(Object raw) {
        var values = elementsOf(raw);
        if (values == null) {
            return null;
        }
        var read = new ArrayList<>(values.size());
        values.forEach(value -> read.add(elements.value(element(value, raw))));
        return read;
    }

    /** The raw elements, or null if the list is missing. */
    private Collection<?> elementsOf(Object raw) {
        return switch (raw) {
            case null -> null;
            case Collection<?> collection -> collection;
            case String text -> text.isBlank() ? null : parse(text);
            default -> throw new InvalidInputException(name, raw, "is not a list");
        };
    }

    /** A list as text: {@code ["PT", "BR"]}, {@code PT, BR} or {@code []}. */
    private List<Comparable<?>> parse(String text) {
        var elementsText = text.strip();
        if (elementsText.startsWith("[") && elementsText.endsWith("]")) {
            elementsText = elementsText.substring(1, elementsText.length() - 1);
            if (elementsText.isBlank()) {
                return List.of();
            }
        }
        try {
            var values = CellParser.parse(elementsText, type).values();
            if (values.isPresent()) {
                return values.get();
            }
        } catch (CellSyntaxException e) {
            // reported below
        }
        throw new InvalidInputException(name, text, "is not a list of %s values, such as [%s]".formatted(type.feelName(), example()));
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

    /** An element, which may not be missing: a list holds values. */
    private Object element(Object value, Object list) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new InvalidInputException(name, list, "holds a missing value; the elements of a list must be values");
        }
        return value;
    }
}
