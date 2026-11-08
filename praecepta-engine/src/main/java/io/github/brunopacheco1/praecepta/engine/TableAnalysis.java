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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Exact static analysis of a decision table on top of its {@link BitsetIndex}. Every column's values
 * are split into classes no rule can tell apart, so searching classes is searching all inputs. Values
 * outside a column's declared input values are rejected before matching, so they are left out.
 *
 * <ul>
 * <li>Overlaps are reported as rule pairs: two rules overlap exactly when some input (a missing value
 * included) matches both, and a UNIQUE (or ANY) violation occurs exactly when such a pair (with
 * different outputs) exists.</li>
 * <li>A rule is unreachable under FIRST or PRIORITY when it matches no valid input, or when every input
 * it matches is also matched by a rule with a lower bit (earlier, or higher priority): a search over
 * the rule's own classes, pruned as soon as a candidate covers what is left.</li>
 * <li>Gaps are regions of declared input values that no rule matches. They are only searched when
 * every string column the rules constrain has declared input values; otherwise every unknown string
 * would be a gap. Missing values are not part of the searched domain.</li>
 * </ul>
 */
final class TableAnalysis {

    static final int MAX_GAPS = 100;

    private final BitsetIndex index;
    private final List<Praeceptum> rules;

    private TableAnalysis(BitsetIndex index, List<Praeceptum> rules) {
        this.index = index;
        this.rules = rules;
    }

    static List<Issue> analyse(BitsetIndex index, List<Praeceptum> rules, HitPolicy policy) {
        var analysis = new TableAnalysis(index, rules);
        var issues = new ArrayList<Issue>(switch (policy) {
            case UNIQUE -> analysis.overlaps(Kind.OVERLAPPING_RULES, false);
            case ANY -> analysis.overlaps(Kind.CONFLICTING_OUTPUTS, true);
            case FIRST, PRIORITY -> analysis.unreachable();
            default -> List.of();
        });
        issues.addAll(analysis.gaps());
        return issues;
    }

    // ---- overlaps -------------------------------------------------------------------------------

    private List<Issue> overlaps(Kind kind, boolean onlyConflictingOutputs) {
        var pairs = new ArrayList<int[]>();
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            var overlapping = index.overlapping(bit);
            for (int other = BitsetIndex.next(overlapping, bit + 1); other >= 0; other = BitsetIndex.next(overlapping, other + 1)) {
                int a = Math.min(index.ruleAt(bit), index.ruleAt(other));
                int b = Math.max(index.ruleAt(bit), index.ruleAt(other));
                if (!onlyConflictingOutputs || !Objects.equals(rules.get(a).output(), rules.get(b).output())) {
                    pairs.add(new int[] {a, b});
                }
            }
        }
        pairs.sort(Arrays::compare); // rule order
        return pairs.stream()
                .map(pair -> new Issue(kind, List.of(rules.get(pair[0]).priority(), rules.get(pair[1]).priority())))
                .toList();
    }

    // ---- unreachable rules ----------------------------------------------------------------------

    private List<Issue> unreachable() {
        var unreachable = new ArrayList<Integer>();
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            var candidates = index.overlapping(bit);
            if (!BitsetIndex.get(candidates, bit)) {
                unreachable.add(index.ruleAt(bit)); // matches no valid input at all
                continue;
            }
            BitsetIndex.and(candidates, BitsetIndex.below(index.words(), bit));
            if (!BitsetIndex.isEmpty(candidates) && new CoverSearch(bit).covered(0, candidates)) {
                unreachable.add(index.ruleAt(bit));
            }
        }
        return unreachable.stream()
                .sorted()
                .map(rule -> new Issue(Kind.UNREACHABLE_RULE, List.of(rules.get(rule).priority())))
                .toList();
    }

    /**
     * Decides whether one rule is unreachable: whether every input it accepts is also matched by a rule
     * with a lower bit. The search fixes one class per column, keeping the candidates that accept every
     * class fixed so far; it is exact, and three things keep it small:
     * <ul>
     * <li>{@code coversRest[c]}: the rules that accept every class this rule accepts in columns
     * {@code c} and after, so "one candidate covers what is left" is a single mask intersection;</li>
     * <li>each column's classes are tried for an immediate witness (no candidate left) before any is
     * searched further, and then fewest candidates first;</li>
     * <li>a state (column, candidates) proven covered is remembered: whether it is covered depends on
     * nothing else, since the candidates already accept every class fixed before that column.</li>
     * </ul>
     */
    private final class CoverSearch {
        /** Per column, the masks of the non-empty, allowed classes this rule accepts. */
        private final long[][][] acceptedMasks;
        /**
         * Per column, the rules that accept all of this rule's classes in that column and after.
         * {@code coversRest[columns]} is all rules, so the recursion ends on an empty suffix.
         */
        private final long[][] coversRest;
        private final Set<State> coveredStates = new HashSet<>();

        CoverSearch(int bit) {
            int columns = index.columnCount();
            acceptedMasks = new long[columns][][];
            coversRest = new long[columns + 1][];
            coversRest[columns] = index.allRules();
            for (int c = columns - 1; c >= 0; c--) {
                var col = index.column(c);
                var masks = new ArrayList<long[]>();
                var rest = coversRest[c + 1].clone();
                for (int k = 0; k < col.classCount(); k++) {
                    if (!col.empty[k] && col.allowed[k] && BitsetIndex.get(col.masks[k], bit)) {
                        masks.add(col.masks[k]);
                        BitsetIndex.and(rest, col.masks[k]);
                    }
                }
                acceptedMasks[c] = masks.toArray(long[][]::new);
                coversRest[c] = rest;
            }
        }

        /**
         * Whether every input the rule accepts, with the classes already fixed for the columns before
         * {@code column}, is matched by some rule in {@code candidates} (non-empty).
         */
        boolean covered(int column, long[] candidates) {
            if (column == acceptedMasks.length || BitsetIndex.intersects(candidates, coversRest[column])) {
                return true;
            }
            var state = new State(column, candidates);
            if (coveredStates.contains(state)) {
                return true;
            }
            var nexts = new LinkedHashSet<State>(); // classes leading to the same candidates are searched once
            for (var mask : acceptedMasks[column]) {
                var next = candidates.clone();
                BitsetIndex.and(next, mask);
                if (BitsetIndex.isEmpty(next)) {
                    return false; // inputs of this class reach the rule
                }
                nexts.add(new State(column + 1, next));
            }
            var fewestCandidatesFirst = nexts.stream()
                    .sorted(Comparator.comparingInt((State next) -> BitsetIndex.count(next.candidates())))
                    .toList();
            for (var next : fewestCandidatesFirst) {
                if (!covered(next.column(), next.candidates())) {
                    return false;
                }
            }
            coveredStates.add(state);
            return true;
        }
    }

    /** A search state: the next column to fix, and the candidates left. */
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

    // ---- gaps ------------------------------------------------------------------------------------

    private record Step(int column, List<Integer> classes) {
    }

    private List<Issue> gaps() {
        if (!gapAnalysisApplies() || anyColumnWithoutValidValues()) {
            return List.of();
        }
        var gaps = new ArrayList<Issue>();
        searchGaps(0, index.allRules(), new ArrayList<>(), gaps);
        return gaps;
    }

    /** Only when every constrained string column has declared input values (else everything is a gap). */
    private boolean gapAnalysisApplies() {
        for (int c = 0; c < index.columnCount(); c++) {
            var col = index.column(c);
            if (col.type == ColumnType.STRING && !col.hasAllowedValues && isConstrained(col)) {
                return false;
            }
        }
        return true;
    }

    /** A column whose declared input values admit no (non-missing) value: then no input exists at all. */
    private boolean anyColumnWithoutValidValues() {
        for (int c = 0; c < index.columnCount(); c++) {
            var col = index.column(c);
            boolean any = false;
            for (int k = 0; k < col.classCount() && !any; k++) {
                any = !col.empty[k] && k != col.nullClass && col.allowed[k];
            }
            if (!any) {
                return true;
            }
        }
        return false;
    }

    private boolean isConstrained(ColumnIndex col) {
        var all = index.allRules();
        for (int k = 0; k < col.classCount(); k++) {
            if (!col.empty[k] && !Arrays.equals(col.masks[k], all)) {
                return true;
            }
        }
        return false;
    }

    private void searchGaps(int column, long[] candidates, List<Step> path, List<Issue> gaps) {
        if (gaps.size() >= MAX_GAPS || column == index.columnCount()) {
            return;
        }
        var col = index.column(column);
        // classes leading to the same candidates behave identically from here on
        var groups = new LinkedHashMap<List<Long>, List<Integer>>();
        var nexts = new LinkedHashMap<List<Long>, long[]>();
        for (int k = 0; k < col.classCount(); k++) {
            if (col.empty[k] || k == col.nullClass || !col.allowed[k]) {
                continue;
            }
            var next = candidates.clone();
            BitsetIndex.and(next, col.masks[k]);
            var key = key(next);
            groups.computeIfAbsent(key, x -> new ArrayList<>()).add(k);
            nexts.putIfAbsent(key, next);
        }
        boolean single = groups.size() == 1;
        for (var group : groups.entrySet()) {
            var next = nexts.get(group.getKey());
            var nextPath = path;
            if (!single) { // a column that does not discriminate here is left out ("any value")
                nextPath = new ArrayList<>(path);
                nextPath.add(new Step(column, group.getValue()));
            }
            if (BitsetIndex.isEmpty(next)) {
                gaps.add(gapIssue(nextPath));
                if (gaps.size() >= MAX_GAPS) {
                    return;
                }
            } else {
                searchGaps(column + 1, next, nextPath, gaps);
            }
        }
    }

    private Issue gapIssue(List<Step> path) {
        var detail = path.isEmpty() ? "any input" : path.stream().map(this::describe).collect(Collectors.joining(", "));
        var example = new LinkedHashMap<String, Object>();
        for (var step : path) {
            var col = index.column(step.column());
            example.put(col.name, col.representative(step.classes().getFirst()));
        }
        return new Issue(Kind.GAP, List.of(), detail, example);
    }

    private String describe(Step step) {
        var col = index.column(step.column());
        var parts = col.describe(step.classes());
        if (parts.size() > 1) {
            return col.name + " in {" + String.join(", ", parts) + "}";
        }
        var only = parts.getFirst();
        return only.startsWith("(") || only.startsWith("[") ? col.name + " in " + only : col.name + " = " + only;
    }

    private static List<Long> key(long[] mask) {
        return Arrays.stream(mask).boxed().toList();
    }
}
