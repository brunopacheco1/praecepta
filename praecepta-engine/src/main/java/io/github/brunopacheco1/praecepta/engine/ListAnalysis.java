/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The static analysis of a column that holds lists ({@link ListColumn}), for {@link TableAnalysis}.
 *
 * <p>Which rules a list matches depends only on which classes of its element index ({@link ColumnIndex})
 * its elements fall in: a rule with a positive cell matches when some element's class passes it, one with
 * a {@code not(...)} when no element's class leaves its inside true or unknown, one with {@code -} always.
 * So a list is, to the analysis, a set {@code S} of element classes, any set of classes that can occur
 * (an element can be repeated, and a list be as long as needed), and the rules it matches are
 *
 * <pre>
 *   any  ∪  (positive ∩ ⋃<sub>k ∈ S</sub> passes(k))  ∪  (negated ∖ ⋃<sub>k ∈ S</sub> rulesOut(k))
 * </pre>
 *
 * with a missing list apart. Adding an element only adds positive rules and only removes negated ones. Three
 * questions follow, each answered without listing the sets, which are exponentially many:
 * <ul>
 * <li><b>Overlap</b> of two rules ({@link #overlapping}): some list matches both. Two positive cells need one
 * element each, a positive and a negated cell one element that passes the first and leaves the second's
 * inside false, two negated cells only the empty list; a missing list matches both when both have {@code null}.</li>
 * <li><b>Escape</b> ({@link #escape}): is there a list that matches none of the rules {@code R} (and, for an
 * unreachable rule, matches it)? Every element must pass no positive rule of {@code R} (the classes left,
 * {@code A}), and every negated rule of {@code R} needs an element of {@code A} that rules it out; one element
 * per such rule then makes a list. So the lists escaping {@code R} are exactly "elements only in {@code A},
 * with one in each of these groups", which is how a gap is described.</li>
 * <li><b>Outcomes</b> ({@link #outcomes}): when a table has several list columns, the search fixes all but the
 * last by the rules each list leaves; only the minimal sets of rules (under inclusion) need searching, since
 * fewer remaining rules only make a gap or a witness easier. They are found by adding one element class
 * at a time and dropping every set another one is inside of. This is bounded ({@link #MAX_OUTCOMES}); past
 * the bound the analysis leaves out what it cannot decide, so it never reports a finding that is false.</li>
 * </ul>
 */
final class ListAnalysis {

    /** At most this many outcomes are kept for a column ({@link #outcomes}); beyond, the question is left undecided. */
    static final int MAX_OUTCOMES = 1024;

    private final ColumnIndex column;
    private final ColumnIndex elements;
    private final int words;
    private final long[] anyRules;
    private final long[] positiveRules;
    private final long[] negatedRules;
    private final long[] missingRules;
    /** The element classes that can occur (non-empty, allowed), the missing one included, in class order. */
    private final int[] classes;
    /** Per entry of {@link #classes}: the positive rules an element of the class passes. */
    private final long[][] passes;
    /** Per entry of {@link #classes}: the negated rules whose inside it leaves true or unknown, which no list holding it matches. */
    private final long[][] rulesOut;

    ListAnalysis(ColumnIndex column, int words) {
        this.column = column;
        var lists = column.lists();
        this.elements = lists.elements();
        this.words = words;
        anyRules = lists.anyRules();
        positiveRules = lists.positiveRules();
        negatedRules = lists.negatedRules();
        missingRules = lists.missingRules();
        var occurring = new ArrayList<Integer>();
        for (int k = 0; k < elements.classCount(); k++) {
            if (elements.canOccur(k)) {
                occurring.add(k);
            }
        }
        classes = occurring.stream().mapToInt(Integer::intValue).toArray();
        passes = new long[classes.length][];
        rulesOut = new long[classes.length][];
        for (int i = 0; i < classes.length; i++) {
            var mask = Masks.dense(elements.masks[classes[i]], words);
            passes[i] = new long[words];
            rulesOut[i] = new long[words];
            for (int w = 0; w < words; w++) {
                passes[i][w] = mask[w] & positiveRules[w];
                rulesOut[i][w] = ~mask[w] & negatedRules[w];
            }
        }
    }

    /** Whether the elements of a class may be in the lists searched: a missing element only if missing values are. */
    private boolean inDomain(int i, boolean withMissing) {
        return withMissing || classes[i] != elements.nullClass;
    }

    // ---- overlaps ----------------------------------------------------------------------------------

    /**
     * The rules that some list, a missing one or one holding missing elements included, matches together with
     * the rule owning {@code bit}; the rule itself among them if some list matches it.
     */
    long[] overlapping(int bit) {
        var result = new long[words];
        if (BitsetIndex.get(missingRules, bit)) {
            BitsetIndex.or(result, missingRules);
        }
        if (BitsetIndex.get(anyRules, bit)) {
            BitsetIndex.or(result, anyRules);
            BitsetIndex.or(result, negatedRules); // the empty list
            for (var passed : passes) {
                BitsetIndex.or(result, passed);
            }
        } else if (BitsetIndex.get(negatedRules, bit)) {
            BitsetIndex.or(result, anyRules);
            BitsetIndex.or(result, negatedRules); // the empty list
            for (int i = 0; i < classes.length; i++) {
                if (!BitsetIndex.get(rulesOut[i], bit)) {
                    BitsetIndex.or(result, passes[i]); // a list of that one element
                }
            }
        } else {
            boolean matches = false;
            for (int i = 0; i < classes.length; i++) {
                if (BitsetIndex.get(passes[i], bit)) {
                    matches = true;
                    var negated = negatedRules.clone(); // a list of that one element
                    BitsetIndex.andNot(negated, rulesOut[i]);
                    BitsetIndex.or(result, negated);
                }
            }
            if (matches) { // one element for this rule, another for each other positive rule
                BitsetIndex.or(result, anyRules);
                for (var passed : passes) {
                    BitsetIndex.or(result, passed);
                }
            }
        }
        return result;
    }

    // ---- escapes -----------------------------------------------------------------------------------

    /**
     * The lists that match none of some rules: a missing list, or the lists whose elements are all in
     * {@code allowed} (indices into {@link #classes}) and that hold an element of each group.
     *
     * @param missing whether the region is the missing list alone
     * @param allowed the element classes the lists may hold, as indices into {@link #classes}
     * @param groups  the classes of which a list must hold one each, none inside another
     * @param example one such list, as indices into {@link #classes}
     */
    record Region(boolean missing, List<Integer> allowed, List<List<Integer>> groups, List<Integer> example) {
    }

    /**
     * A region of lists that no rule of {@code rules} matches and the rule owning {@code required} (unless it is
     * negative) does; null if there is none. With {@code withMissing}, the region may be the missing list, and
     * lists may hold missing elements. Lists that are there are preferred to the missing one.
     */
    Region escape(long[] rules, int required, boolean withMissing) {
        var region = escapeWithElements(rules, required, withMissing);
        if (region != null || !withMissing) {
            return region;
        }
        boolean missingMatchesRequired = required < 0 || BitsetIndex.get(missingRules, required);
        return missingMatchesRequired && !BitsetIndex.intersects(missingRules, rules) ? new Region(true, List.of(), List.of(), List.of()) : null;
    }

    private Region escapeWithElements(long[] rules, int required, boolean withMissing) {
        if (BitsetIndex.intersects(anyRules, rules)) {
            return null;
        }
        boolean requiredNegated = required >= 0 && BitsetIndex.get(negatedRules, required);
        var allowed = new ArrayList<Integer>();
        for (int i = 0; i < classes.length; i++) {
            if (inDomain(i, withMissing) && !BitsetIndex.intersects(passes[i], rules)
                    && !(requiredNegated && BitsetIndex.get(rulesOut[i], required))) {
                allowed.add(i);
            }
        }
        var groups = new ArrayList<List<Integer>>();
        if (required >= 0 && BitsetIndex.get(positiveRules, required)) {
            groups.add(allowed.stream().filter(i -> BitsetIndex.get(passes[i], required)).toList());
        }
        var negated = rules.clone();
        BitsetIndex.and(negated, negatedRules);
        for (int bit = BitsetIndex.next(negated, 0); bit >= 0; bit = BitsetIndex.next(negated, bit + 1)) {
            int rule = bit;
            groups.add(allowed.stream().filter(i -> BitsetIndex.get(rulesOut[i], rule)).toList());
        }
        if (groups.stream().anyMatch(List::isEmpty)) {
            return null;
        }
        var minimal = minimalGroups(groups);
        return new Region(false, List.copyOf(allowed), minimal, example(minimal));
    }

    /** The distinct groups, without those that hold another: holding an element of the smaller one is enough. */
    private static List<List<Integer>> minimalGroups(List<List<Integer>> groups) {
        var distinct = List.copyOf(new LinkedHashSet<>(groups));
        var minimal = new ArrayList<List<Integer>>();
        for (var group : distinct) {
            if (distinct.stream().noneMatch(other -> other != group && group.containsAll(other))) {
                minimal.add(group);
            }
        }
        return List.copyOf(minimal);
    }

    /** One element per group not yet held, the first of each: a list of the region. */
    private static List<Integer> example(List<List<Integer>> groups) {
        var example = new ArrayList<Integer>();
        for (var group : groups) {
            if (group.stream().noneMatch(example::contains)) {
                example.add(group.getFirst());
            }
        }
        return List.copyOf(example);
    }

    // ---- outcomes ----------------------------------------------------------------------------------

    /**
     * The lists that leave some rules matching: one of them, its element classes (indices into
     * {@link #classes}) or the missing list, and the rules it matches.
     */
    record Outcome(long[] matches, List<Integer> elements, boolean missing) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Outcome(long[] otherMatches, var otherElements, boolean otherMissing)
                    && Arrays.equals(matches, otherMatches) && elements.equals(otherElements) && missing == otherMissing;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(matches), elements, missing);
        }

        @Override
        public String toString() {
            return "Outcome[" + Arrays.toString(matches) + ", " + elements + ", " + missing + "]";
        }
    }

    /**
     * Of the lists that the rule owning {@code required} matches (any list, if it is negative), the sets of rules
     * of {@code relevant} they match that are minimal under inclusion, each with one list that gives it; with
     * {@code withMissing}, the missing list and lists holding missing elements too. Null if there are more than
     * {@link #MAX_OUTCOMES}.
     */
    List<Outcome> outcomes(long[] relevant, int required, boolean withMissing) {
        var rules = relevant.clone();
        if (required >= 0) {
            BitsetIndex.set(rules, required);
        }
        boolean requiredNegated = required >= 0 && BitsetIndex.get(negatedRules, required);
        var outcomes = new ArrayList<Outcome>();
        var empty = anyRules.clone(); // the empty list
        BitsetIndex.or(empty, negatedRules);
        BitsetIndex.and(empty, rules);
        outcomes.add(new Outcome(empty, List.of(), false));
        var seenTypes = new LinkedHashSet<List<Long>>();
        for (int i = 0; i < classes.length; i++) {
            if (!inDomain(i, withMissing) || requiredNegated && BitsetIndex.get(rulesOut[i], required)) {
                continue;
            }
            var adds = passes[i].clone();
            BitsetIndex.and(adds, rules);
            var removes = rulesOut[i].clone();
            BitsetIndex.and(removes, rules);
            if (!seenTypes.add(key(adds, removes))) {
                continue; // a class that changes the same rules as one already added
            }
            for (var outcome : List.copyOf(outcomes)) {
                var matches = outcome.matches().clone();
                BitsetIndex.or(matches, adds);
                BitsetIndex.andNot(matches, removes);
                var elementsHeld = new ArrayList<>(outcome.elements());
                elementsHeld.add(i);
                if (!keepMinimal(outcomes, new Outcome(matches, List.copyOf(elementsHeld), false), required)) {
                    return null;
                }
            }
        }
        if (required >= 0) {
            outcomes.removeIf(outcome -> !BitsetIndex.get(outcome.matches(), required));
        }
        if (withMissing && (required < 0 || BitsetIndex.get(missingRules, required))) {
            var missing = missingRules.clone();
            BitsetIndex.and(missing, rules);
            keepMinimal(outcomes, new Outcome(missing, List.of(), true), required);
        }
        return List.copyOf(outcomes);
    }

    /**
     * Adds an outcome unless one inside it is already there (with the same answer for {@code required}), and
     * drops those it is inside of.
     *
     * @return false if there are now more than {@link #MAX_OUTCOMES}
     */
    private static boolean keepMinimal(List<Outcome> outcomes, Outcome added, int required) {
        boolean flag = required >= 0 && BitsetIndex.get(added.matches(), required);
        for (var outcome : outcomes) {
            if ((required >= 0 && BitsetIndex.get(outcome.matches(), required)) == flag && isSubset(outcome.matches(), added.matches())) {
                return true;
            }
        }
        outcomes.removeIf(outcome -> (required >= 0 && BitsetIndex.get(outcome.matches(), required)) == flag
                && isSubset(added.matches(), outcome.matches()));
        outcomes.add(added);
        return outcomes.size() <= MAX_OUTCOMES;
    }

    private static boolean isSubset(long[] a, long[] b) {
        for (int w = 0; w < a.length; w++) {
            if ((a[w] & ~b[w]) != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<Long> key(long[] adds, long[] removes) {
        var key = new ArrayList<Long>(adds.length * 2);
        Arrays.stream(adds).forEach(key::add);
        Arrays.stream(removes).forEach(key::add);
        return key;
    }

    // ---- reports -----------------------------------------------------------------------------------

    /**
     * A region of a gap, for the validation report: {@code ages = []}, or
     * {@code nationalities has no element in {"PT", "ES"}} and {@code nationalities has an element in {"FR", "DE"}}
     * (one part per group), joined with commas; null if every list is in it.
     */
    String describe(Region region) {
        if (region.missing()) {
            return column.name + " = null";
        }
        var parts = new ArrayList<String>();
        var excluded = new ArrayList<Integer>();
        for (int i = 0; i < classes.length; i++) {
            if (inDomain(i, false) && !region.allowed().contains(i)) {
                excluded.add(classes[i]);
            }
        }
        if (region.allowed().isEmpty()) {
            return column.name + " = []";
        }
        if (!excluded.isEmpty()) {
            parts.add(column.name + " has no element in " + set(excluded));
        }
        for (var group : region.groups()) {
            parts.add(column.name + " has an element in " + set(group.stream().map(i -> classes[i]).toList()));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Element classes as a report writes them: one interval or value alone, several in braces. */
    private String set(List<Integer> elementClasses) {
        var parts = elements.describe(elementClasses);
        return parts.size() == 1 ? parts.getFirst() : parts.stream().collect(Collectors.joining(", ", "{", "}"));
    }

    /** A list of the region, as a getter returns it: a value of each element class of its example; null for the missing list. */
    List<Object> example(Region region) {
        if (region.missing()) {
            return null;
        }
        var example = new ArrayList<Object>();
        region.example().forEach(i -> example.add(elements.representative(classes[i])));
        return example;
    }
}
