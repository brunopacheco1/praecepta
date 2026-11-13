/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Exact static analysis of a decision table on top of its {@link BitsetIndex}. Every column's values
 * are split into classes no rule can tell apart, so searching classes is searching all inputs. Values
 * outside a column's declared input values are rejected before matching, so they are left out.
 *
 * <ul>
 * <li>Overlaps are reported as rule pairs: two rules overlap exactly when some input (a missing value
 * included) matches both, and a UNIQUE (or ANY) violation occurs exactly when such a pair (with
 * different outputs) exists. The first {@link #MAX_OVERLAPS} pairs, in rule order, are reported.</li>
 * <li>A rule is unreachable under FIRST or PRIORITY when it matches no valid input, or when every input
 * it matches is also matched by a rule with a lower bit (earlier, or higher priority): a search over
 * the rule's own classes, pruned as soon as a candidate covers what is left.</li>
 * <li>Rules with cells that compare with another input match inputs known only when evaluating, so
 * they take part only as rules that may be unreachable themselves (judged on their other cells).</li>
 * <li>Gaps are regions of declared input values that no rule matches. They are only searched when
 * every string column the rules constrain has declared input values; otherwise every unknown string
 * would be a gap. Missing values are not part of the searched domain.</li>
 * <li>A column that holds lists is searched as sets of its element classes ({@link ListAnalysis}): every
 * list, a missing one and ones holding missing elements included, for overlaps and unreachable rules, and
 * the lists of values for gaps. The searches fix such columns after the others; the last one is decided
 * directly, and a gap there is described as all the lists that escape the rules left
 * ({@code nationalities has no element in {"PT", "ES"}}). Before it, a list column branches on the minimal
 * sets of rules its lists can leave, of which at most {@link ListAnalysis#MAX_OUTCOMES} are searched: past
 * that, the rule (for unreachability) or the table (for gaps) is left unreported, never reported wrongly.</li>
 * </ul>
 */
final class TableAnalysis {

    /** At most this many gaps are reported. */
    static final int MAX_GAPS = 100;
    /** At most this many pairs of overlapping rules (UNIQUE) or of rules with conflicting outputs (ANY) are reported. */
    static final int MAX_OVERLAPS = 100;

    private final BitsetIndex index;
    private final List<Praeceptum> rules;
    /**
     * Rules with cells that compare with another input, which the index sees as "any value", or that
     * test a column holding lists: the inputs they match are known only when evaluating, so they never
     * count as overlapping, never cover another rule, and while there are any, gaps are not searched.
     */
    private final long[] variableRules;
    /** ANY: a rule's outputs as they are compared; two rules conflict when theirs differ. */
    private final Function<PraeceptaOutput, ?> comparableOutput;
    /** The columns in the order the searches fix them, as positions in the index: those of single values, then those of lists. */
    private final int[] order;
    /** Per column of the index, its analysis if it holds lists; else null. */
    private final ListAnalysis[] lists;
    /** Whether a gap search met a list column with more outcomes than it searches: no gap is then reported. */
    private boolean undecidedGaps;

    private TableAnalysis(BitsetIndex index, List<Praeceptum> rules, long[] variableRules, Function<PraeceptaOutput, ?> comparableOutput) {
        this.index = index;
        this.rules = rules;
        this.variableRules = variableRules;
        this.comparableOutput = comparableOutput;
        lists = new ListAnalysis[index.columnCount()];
        var singles = new ArrayList<Integer>();
        var listColumns = new ArrayList<Integer>();
        for (int c = 0; c < index.columnCount(); c++) {
            if (index.column(c).holdsLists()) {
                lists[c] = new ListAnalysis(index.column(c), index.words());
                listColumns.add(c);
            } else {
                singles.add(c);
            }
        }
        singles.addAll(listColumns);
        order = singles.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The column the searches fix at {@code position}. */
    private ColumnIndex column(int position) {
        return index.column(order[position]);
    }

    /** The analysis of the column at {@code position} if it holds lists; else null. */
    private ListAnalysis lists(int position) {
        return lists[order[position]];
    }

    /** The rules sharing at least one valid input with the rule owning {@code bit} (see {@link BitsetIndex#overlapping}), lists included. */
    private long[] overlapping(int bit) {
        var overlapping = index.overlapping(bit); // a list column's own classes accept every rule
        for (var list : lists) {
            if (list != null) {
                BitsetIndex.and(overlapping, list.overlapping(bit));
            }
        }
        return overlapping;
    }

    /**
     * @param comparableOutput a rule's outputs as ANY compares them (number outputs by value), so that
     *                         rules conflict exactly when evaluation would find them different
     */
    static List<Issue> analyse(BitsetIndex index, List<Praeceptum> rules, HitPolicy policy, long[] variableRules,
            Function<PraeceptaOutput, ?> comparableOutput) {
        var analysis = new TableAnalysis(index, rules, variableRules, comparableOutput);
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

    /**
     * The overlapping pairs in rule order, at most {@link #MAX_OVERLAPS}: a list column with a value per rule makes
     * almost every pair of rules overlap, and a report of millions of pairs helps no one.
     */
    private List<Issue> overlaps(Kind kind, boolean onlyConflictingOutputs) {
        var bitOf = new int[index.ruleCount()];
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            bitOf[index.ruleAt(bit)] = bit;
        }
        var issues = new ArrayList<Issue>();
        for (int first = 0; first < index.ruleCount() && issues.size() < MAX_OVERLAPS; first++) {
            int bit = bitOf[first];
            if (BitsetIndex.get(variableRules, bit)) {
                continue;
            }
            var overlapping = overlapping(bit);
            BitsetIndex.andNot(overlapping, variableRules);
            var seconds = new ArrayList<Integer>();
            for (int other = BitsetIndex.next(overlapping, 0); other >= 0; other = BitsetIndex.next(overlapping, other + 1)) {
                if (index.ruleAt(other) > first) { // each pair once, from its first rule
                    seconds.add(index.ruleAt(other));
                }
            }
            seconds.sort(null);
            for (int second : seconds) {
                if (issues.size() == MAX_OVERLAPS) {
                    break;
                }
                if (!onlyConflictingOutputs || !Objects.equals(comparableOutput.apply(rules.get(first).output()),
                        comparableOutput.apply(rules.get(second).output()))) {
                    issues.add(new Issue(kind, List.of(rules.get(first).priority(), rules.get(second).priority())));
                }
            }
        }
        return issues;
    }

    // ---- unreachable rules ----------------------------------------------------------------------

    private List<Issue> unreachable() {
        var unreachable = new ArrayList<Integer>();
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            var candidates = overlapping(bit);
            if (!BitsetIndex.get(candidates, bit)) {
                unreachable.add(index.ruleAt(bit)); // matches no valid input at all
                continue;
            }
            // only rules with a lower bit win over this one, and only rules matching what the index sees
            BitsetIndex.and(candidates, BitsetIndex.below(index.words(), bit));
            BitsetIndex.andNot(candidates, variableRules);
            if (BitsetIndex.isEmpty(candidates)) {
                continue;
            }
            var search = new CoverSearch(bit, candidates);
            if (search.decidable && search.covered(0, candidates)) {
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
     * A column that holds lists has, for classes, the minimal sets of rules its lists leave
     * ({@link ListAnalysis#outcomes}); the last column, if it holds lists, is decided by
     * {@link ListAnalysis#escape} instead.
     */
    private final class CoverSearch {
        private final int bit;
        /** The last column the search fixes, if it holds lists, which is decided directly; else null. */
        private final ListAnalysis lastLists;
        /** False if a list column has too many outcomes to search: the rule is then not reported. */
        private boolean decidable = true;
        /** Per column, the masks of the non-empty, allowed classes this rule accepts. */
        private final long[][][] acceptedMasks;
        /**
         * Per column, the rules that accept all of this rule's classes in that column and after.
         * {@code coversRest[columns]} is all rules, so the recursion ends on an empty suffix.
         */
        private final long[][] coversRest;
        private final Set<State> coveredStates = new HashSet<>();

        /** @param candidates the rules that may cover this one: every candidate later is one of them */
        CoverSearch(int bit, long[] candidates) {
            this.bit = bit;
            int columns = index.columnCount();
            acceptedMasks = new long[columns][][];
            coversRest = new long[columns + 1][];
            coversRest[columns] = index.allRules();
            lastLists = columns == 0 ? null : lists(columns - 1);
            for (int c = columns - 1; c >= 0; c--) {
                var col = column(c);
                var masks = new ArrayList<long[]>();
                var rest = coversRest[c + 1].clone();
                if (c == columns - 1 && lastLists != null) {
                    Masks.and(rest, lastLists.covering(candidates, bit));
                } else if (lists(c) != null) {
                    var outcomes = lists(c).outcomes(candidates, bit, true);
                    if (outcomes == null) {
                        decidable = false;
                        return;
                    }
                    outcomes.forEach(outcome -> masks.add(outcome.matches()));
                    masks.forEach(mask -> Masks.and(rest, mask));
                } else {
                    for (int k = 0; k < col.classCount(); k++) {
                        if (col.canOccur(k) && col.accepts(k, bit)) {
                            masks.add(col.masks[k]);
                            Masks.and(rest, col.masks[k]);
                        }
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
            if (column == acceptedMasks.length - 1 && lastLists != null) {
                return lastLists.escape(candidates, bit, true) == null; // no list this rule matches escapes them
            }
            var state = new State(column, candidates);
            if (coveredStates.contains(state)) {
                return true;
            }
            var nextStates = new LinkedHashSet<State>(); // classes leading to the same candidates are searched once
            for (var mask : acceptedMasks[column]) {
                var next = candidates.clone();
                Masks.and(next, mask);
                if (BitsetIndex.isEmpty(next)) {
                    return false; // inputs of this class reach the rule
                }
                nextStates.add(new State(column + 1, next));
            }
            var fewestCandidatesFirst = nextStates.stream()
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

    /**
     * One column along a gap's path (its position in the search), and the classes it takes there; for a column
     * that holds lists, the lists it takes there, described, and one of them.
     */
    private record Step(int column, List<Integer> classes, String listDetail, List<Object> listExample) {
        Step(int column, List<Integer> classes) {
            this(column, classes, null, null);
        }
    }

    /**
     * The classes of a column that leave the same candidates, which behave alike from there on. A plain
     * class, not a record: branches are grouped by their candidates' content, never compared themselves.
     */
    private static final class Branch {
        final long[] candidates;
        final List<Integer> classes = new ArrayList<>();
        /** Whether the column holds lists; its lists are then {@link #listStep}, or every list if that is null. */
        boolean lists;
        Step listStep;

        Branch(long[] candidates) {
            this.candidates = candidates;
        }
    }

    private List<Issue> gaps() {
        if (!BitsetIndex.isEmpty(variableRules) || !gapAnalysisApplies() || anyColumnWithoutValidValues()) {
            return List.of();
        }
        var gaps = new ArrayList<Issue>();
        searchGaps(0, index.allRules(), new ArrayList<>(), gaps);
        return undecidedGaps ? List.of() : gaps;
    }

    /**
     * Only when every constrained string column that groups the values its rules do not mention has
     * declared input values (else every such value is a gap).
     */
    private boolean gapAnalysisApplies() {
        for (int c = 0; c < index.columnCount(); c++) {
            var col = index.column(c);
            if (col.groupsOtherValues() && !col.hasAllowedValues && isConstrained(col)) {
                return false;
            }
        }
        return true;
    }

    /** Whether some rule does not accept every value of the column. */
    private boolean isConstrained(ColumnIndex col) {
        var all = index.allRules();
        if (col.holdsLists()) {
            return !Arrays.equals(col.rulesAcceptingAnyList(), all);
        }
        for (int k = 0; k < col.classCount(); k++) {
            if (!col.empty[k] && !Arrays.equals(Masks.dense(col.masks[k], all.length), all)) {
                return true;
            }
        }
        return false;
    }

    /** A column whose declared input values admit no (non-missing) value: then no input exists at all. */
    private boolean anyColumnWithoutValidValues() {
        for (int c = 0; c < index.columnCount(); c++) {
            if (!hasValidValue(index.column(c))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValidValue(ColumnIndex col) {
        for (int k = 0; k < col.classCount(); k++) {
            if (k != col.nullClass && col.canOccur(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fixes the classes of {@code column} and the columns after it, depth first, and reports a gap
     * wherever no candidate is left. {@code path} holds the classes fixed before {@code column}.
     */
    private void searchGaps(int column, long[] candidates, List<Step> path, List<Issue> gaps) {
        if (gaps.size() >= MAX_GAPS || column == index.columnCount() || undecidedGaps) {
            return;
        }
        if (column == index.columnCount() - 1 && lists(column) != null) {
            lastListGap(column, candidates, path, gaps);
            return;
        }
        var branches = branches(column, candidates);
        for (var branch : branches) {
            var nextPath = path;
            // a column that does not discriminate here is left out ("any value")
            if (branch.lists ? branch.listStep != null : branches.size() > 1) {
                nextPath = new ArrayList<>(path);
                nextPath.add(branch.lists ? branch.listStep : new Step(column, branch.classes));
            }
            if (BitsetIndex.isEmpty(branch.candidates)) {
                gaps.add(gapIssue(nextPath));
                if (gaps.size() >= MAX_GAPS) {
                    return;
                }
            } else {
                searchGaps(column + 1, branch.candidates, nextPath, gaps);
            }
        }
    }

    /**
     * The last column the search fixes, which holds lists: a gap if some list of values escapes the candidates,
     * described as every such list.
     */
    private void lastListGap(int column, long[] candidates, List<Step> path, List<Issue> gaps) {
        var region = lists(column).escape(candidates, -1, false);
        if (region != null) {
            var step = listStep(column, region);
            var nextPath = path;
            if (step != null) {
                nextPath = new ArrayList<>(path);
                nextPath.add(step);
            }
            gaps.add(gapIssue(nextPath));
        }
    }

    /** The lists of a region as a step of a gap's path; null if they are every list (the column is then left out). */
    private Step listStep(int column, ListAnalysis.Region region) {
        var detail = lists(column).describe(region);
        return detail == null ? null : new Step(column, List.of(), detail, lists(column).example(region));
    }

    /**
     * A column that holds lists, before the last: one branch per minimal set of candidates its lists of values
     * leave ({@link ListAnalysis#outcomes}), with the lists that leave no more than that set.
     */
    private Collection<Branch> listBranches(int column, long[] candidates) {
        var outcomes = lists(column).outcomes(candidates, -1, false);
        if (outcomes == null) {
            undecidedGaps = true;
            return List.of();
        }
        var branches = new ArrayList<Branch>();
        for (var outcome : outcomes) {
            var branch = new Branch(outcome.matches());
            var left = candidates.clone();
            BitsetIndex.andNot(left, outcome.matches()); // the candidates these lists match none of
            branch.lists = true;
            branch.listStep = listStep(column, lists(column).escape(left, -1, false));
            branches.add(branch);
        }
        return branches;
    }

    /** The column's valid, non-missing classes, grouped by the candidates each leaves, in class order. */
    private Collection<Branch> branches(int column, long[] candidates) {
        if (lists(column) != null) {
            return listBranches(column, candidates);
        }
        var col = column(column);
        var byCandidates = new LinkedHashMap<List<Long>, Branch>();
        for (int k = 0; k < col.classCount(); k++) {
            if (k == col.nullClass || !col.canOccur(k)) {
                continue;
            }
            var next = candidates.clone();
            Masks.and(next, col.masks[k]);
            byCandidates.computeIfAbsent(key(next), mask -> new Branch(next)).classes.add(k);
        }
        return byCandidates.values();
    }

    private Issue gapIssue(List<Step> path) {
        var detail = path.isEmpty() ? "any input" : path.stream().map(this::describe).collect(Collectors.joining(", "));
        var example = new LinkedHashMap<String, Object>();
        for (var step : path) {
            var col = column(step.column());
            example.put(col.name, step.listDetail() != null ? step.listExample() : col.representative(step.classes().getFirst()));
        }
        return new Issue(Kind.GAP, List.of(), detail, example);
    }

    /** {@code age = 18}, {@code age in [18..65)} or {@code age in {[0..18), [65..+inf)}}. */
    private String describe(Step step) {
        if (step.listDetail() != null) {
            return step.listDetail();
        }
        var col = column(step.column());
        var parts = col.describe(step.classes());
        if (parts.size() > 1) {
            return col.name + " in {" + String.join(", ", parts) + "}";
        }
        var only = parts.getFirst();
        boolean interval = only.startsWith("(") || only.startsWith("[");
        return interval ? col.name + " in " + only : col.name + " = " + only;
    }

    /** A mask as a map key: arrays compare by identity. */
    private static List<Long> key(long[] mask) {
        return Arrays.stream(mask).boxed().toList();
    }
}
