/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a table is complete: whether every input the engine accepts matches one of some rules. DMN 1.5 calls a
 * table complete when "its rules cover all combinations of expected input values" (8.2.4), and forbids such a table
 * a default output (10.3.2.10), which no input could then reach.
 *
 * <p>Unlike the gap search of {@link TableAnalysis}, which looks among declared, non-missing values, this search
 * also visits a missing value in every column, since the engine accepts one wherever a column declares its input
 * values ({@code -} matches it, and so does {@code null}): a table whose rules cover every declared value but not a
 * missing one still gives its default for a missing input. And it visits the class of strings no rule mentions in a
 * string column without declared values, since such a string is an input too. Every class an input can fall in is
 * visited ({@link ColumnIndex#canOccur}), and so is a time with a zone id in a column of map-based inputs whose cells
 * name none, which the other analyses leave out ({@link ColumnIndex#hiddenZoneIdMask}: only the cells that accept any
 * value match it), so the search is exact over the rules it is given.
 *
 * <p>A column that holds lists is searched as the sets of rules its lists can match ({@link ListAnalysis#everyOutcome}):
 * every list, a missing one, the empty one and ones holding missing elements included, of which only the minimal sets
 * under inclusion need searching, since fewer rules left only make an input that matches none easier to find. A list
 * matching none of the rules left makes the table incomplete. Past {@link ListAnalysis#MAX_OUTCOMES} sets the search
 * gives up and the table counts as incomplete, so a table is never reported complete wrongly.
 *
 * <p>The search fixes one class per column, keeping the rules that accept every class fixed so far; it stops as
 * soon as no rule is left (an input that matches none) or one rule accepts every class of the columns left. Classes
 * that leave the same rules are searched once, and a state (column, rules) proven covered is remembered, since
 * whether it is covered depends on nothing else.
 */
final class Completeness {

    /** Per column, the masks of the classes an input can fall in, a missing value's included; one per distinct mask. */
    private final long[][][] classMasks;
    /** Per column, its analysis if it holds lists (its {@link #classMasks} are then null); else null. */
    private final ListAnalysis[] lists;
    /**
     * Per column, the rules that accept every class of that column and the columns after it, so that "some rule
     * covers what is left" is one intersection. {@code coversRest[columns]} is all rules.
     */
    private final long[][] coversRest;
    private final Set<State> covered = new HashSet<>();

    private Completeness(BitsetIndex index) {
        int columns = index.columnCount();
        classMasks = new long[columns][][];
        lists = new ListAnalysis[columns];
        coversRest = new long[columns + 1][];
        coversRest[columns] = index.allRules();
        for (int c = columns - 1; c >= 0; c--) {
            var col = index.column(c);
            var rest = coversRest[c + 1].clone();
            if (col.holdsLists()) {
                lists[c] = new ListAnalysis(col, index.words());
                BitsetIndex.and(rest, col.rulesAcceptingAnyList()); // only - accepts every list, a missing one included
                coversRest[c] = rest;
                continue;
            }
            var distinct = new LinkedHashMap<List<Long>, long[]>();
            for (int k = 0; k < col.classCount(); k++) {
                if (col.canOccur(k)) {
                    var dense = index.allRules();
                    Masks.and(dense, col.masks[k]);
                    distinct.putIfAbsent(key(dense), dense);
                    BitsetIndex.and(rest, dense);
                }
            }
            var hidden = col.hiddenZoneIdMask();
            if (hidden != null) {
                var dense = index.allRules();
                Masks.and(dense, hidden);
                distinct.putIfAbsent(key(dense), dense);
                BitsetIndex.and(rest, dense);
            }
            classMasks[c] = distinct.values().toArray(long[][]::new);
            coversRest[c] = rest;
        }
    }

    /**
     * Whether every input the index accepts, a missing value in any column included, matches one of {@code rules}.
     * With other rules left out, it says whether the table is complete through these rules alone: if so, it is
     * complete.
     *
     * @param rules a dense mask of the rules to count
     */
    static boolean isComplete(BitsetIndex index, long[] rules) {
        return new Completeness(index).covered(0, rules.clone());
    }

    /** Whether every input with the classes already fixed for the columns before {@code column} matches one of {@code candidates}. */
    private boolean covered(int column, long[] candidates) {
        if (BitsetIndex.isEmpty(candidates)) {
            return false;
        }
        if (column == classMasks.length || BitsetIndex.intersects(candidates, coversRest[column])) {
            return true;
        }
        var state = new State(column, candidates);
        if (covered.contains(state)) {
            return true;
        }
        var next = new LinkedHashMap<List<Long>, long[]>(); // classes leading to the same candidates are searched once
        boolean everyInputLeavesSome = lists[column] != null ? addListCandidates(column, candidates, next)
                : addClassCandidates(column, candidates, next);
        if (!everyInputLeavesSome) {
            return false;
        }
        var fewestFirst = new ArrayList<>(next.values());
        fewestFirst.sort(Comparator.comparingInt(BitsetIndex::count));
        for (var left : fewestFirst) {
            if (!covered(column + 1, left)) {
                return false;
            }
        }
        covered.add(state);
        return true;
    }

    /**
     * Adds to {@code next} the candidates each set of rules the column's lists can match leaves, by key.
     *
     * @return false if some lists match none of the candidates, or the sets are too many to search (not proven
     *         complete)
     */
    private boolean addListCandidates(int column, long[] candidates, Map<List<Long>, long[]> next) {
        var outcomes = lists[column].everyOutcome(candidates);
        if (outcomes.isEmpty()) {
            return false; // too many to search: not proven complete
        }
        for (var outcome : outcomes.get()) {
            if (BitsetIndex.isEmpty(outcome.matches())) {
                return false; // such lists match none of the rules
            }
            next.putIfAbsent(key(outcome.matches()), outcome.matches());
        }
        return true;
    }

    /**
     * Adds to {@code next} the candidates each class of the column leaves, by key.
     *
     * @return false if the inputs of some class match none of the candidates
     */
    private boolean addClassCandidates(int column, long[] candidates, Map<List<Long>, long[]> next) {
        for (var mask : classMasks[column]) {
            var left = candidates.clone();
            BitsetIndex.and(left, mask);
            if (BitsetIndex.isEmpty(left)) {
                return false; // inputs of this class match none of the rules
            }
            next.putIfAbsent(key(left), left);
        }
        return true;
    }

    /** A mask as a map key: arrays compare by identity. */
    private static List<Long> key(long[] mask) {
        return Arrays.stream(mask).boxed().toList();
    }

    /** A search state: the next column to fix, and the rules left. */
    private record State(int column, long[] candidates) {
        @Override
        public boolean equals(Object other) {
            return other instanceof State(int otherColumn, long[] otherCandidates)
                    && column == otherColumn && Arrays.equals(candidates, otherCandidates);
        }

        @Override
        public int hashCode() {
            return 31 * column + Arrays.hashCode(candidates);
        }

        @Override
        public String toString() {
            return "State[" + column + ", " + Arrays.toString(candidates) + "]";
        }
    }
}
