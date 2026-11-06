package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Exact static analysis of a decision table on top of its {@link BitsetIndex}.
 *
 * <ul>
 * <li>Overlaps are reported as rule pairs. Two rules overlap exactly when some input matches both,
 * and a UNIQUE (or ANY) violation occurs exactly when such a pair (with different outputs) exists,
 * so pairs capture every violation without enumerating input regions.</li>
 * <li>A rule is unreachable under FIRST or PRIORITY when every input it matches is also matched by
 * a rule with a lower bit (earlier, or higher priority). This is decided by splitting the rule's own
 * region on the values the rules distinguish, pruned as soon as a candidate covers what is left.</li>
 * </ul>
 */
final class TableAnalysis {

    private final BitsetIndex index;
    private final Set<String>[][] cells;
    private final List<Praeceptum> rules;

    private TableAnalysis(BitsetIndex index, Set<String>[][] cells, List<Praeceptum> rules) {
        this.index = index;
        this.cells = cells;
        this.rules = rules;
    }

    /** @param cells the table's parsed cells, from {@link BitsetIndex#parseCells} */
    static List<Issue> analyse(BitsetIndex index, Set<String>[][] cells, List<Praeceptum> rules, HitPolicy policy) {
        var analysis = new TableAnalysis(index, cells, rules);
        return switch (policy) {
            case UNIQUE -> analysis.overlaps(Kind.OVERLAPPING_RULES, false);
            case ANY -> analysis.overlaps(Kind.CONFLICTING_OUTPUTS, true);
            case FIRST, PRIORITY -> analysis.unreachable();
            default -> List.of();
        };
    }

    private List<Issue> overlaps(Kind kind, boolean onlyConflictingOutputs) {
        var pairs = new ArrayList<int[]>();
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            var overlapping = index.overlapping(bit, cells);
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

    private List<Issue> unreachable() {
        var unreachable = new ArrayList<Integer>();
        for (int bit = 0; bit < index.ruleCount(); bit++) {
            var candidates = index.overlapping(bit, cells);
            BitsetIndex.and(candidates, BitsetIndex.below(index.words(), bit));
            if (!BitsetIndex.isEmpty(candidates) && covered(index.ruleAt(bit), 0, candidates)) {
                unreachable.add(index.ruleAt(bit));
            }
        }
        return unreachable.stream()
                .sorted()
                .map(rule -> new Issue(Kind.UNREACHABLE_RULE, List.of(rules.get(rule).priority())))
                .toList();
    }

    /**
     * Whether every input in {@code rule}'s region, restricted to the values already fixed for the
     * columns before {@code column}, is matched by some rule in {@code candidates} (non-empty).
     */
    private boolean covered(int rule, int column, long[] candidates) {
        if (column == index.columns() || coversRemainder(rule, column, candidates)) {
            return true;
        }
        var cell = cells[rule][column];
        Iterable<String> branches = cell != null ? cell : index.valueClasses(column);
        Set<List<Long>> explored = new HashSet<>();
        for (var value : branches) {
            var next = candidates.clone();
            BitsetIndex.and(next, index.maskFor(column, value));
            if (BitsetIndex.isEmpty(next)) {
                return false; // inputs with this value reach the rule
            }
            if (explored.add(asKey(next)) && !covered(rule, column + 1, next)) {
                return false;
            }
        }
        return true;
    }

    /** Some candidate accepts everything the rule accepts in every column from {@code column} on. */
    private boolean coversRemainder(int rule, int column, long[] candidates) {
        for (int bit = BitsetIndex.first(candidates); bit >= 0; bit = BitsetIndex.next(candidates, bit + 1)) {
            int candidate = index.ruleAt(bit);
            boolean covers = true;
            for (int c = column; c < index.columns() && covers; c++) {
                var theirs = cells[candidate][c];
                var ours = cells[rule][c];
                covers = theirs == null || (ours != null && theirs.containsAll(ours));
            }
            if (covers) {
                return true;
            }
        }
        return false;
    }

    private static List<Long> asKey(long[] mask) {
        return Arrays.stream(mask).boxed().toList();
    }
}
