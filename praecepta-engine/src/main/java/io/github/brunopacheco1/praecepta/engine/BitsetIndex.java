package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches inputs against all rules at once with bitmasks. Each rule owns one bit; for every column,
 * every value written in that column maps to the mask of rules accepting it (wildcard rules
 * included), and any other value (missing, blank or unknown) maps to the mask of wildcard rules.
 * An input's matches are the AND of one mask per column.
 *
 * <p>Memory is proportional to columns × distinct values × rules / 8 bytes, with no wildcard
 * expansion. Bits are assigned in a caller-chosen order (see {@link #ruleAt(int)}), which lets
 * "lowest set bit" mean "first in rule order" or "highest priority".
 */
final class BitsetIndex {

    /** Reads the value of column {@code column} (in the index's column order) from an input. */
    @FunctionalInterface
    interface ColumnReader<T> {
        String read(T input, int column);
    }

    private final int ruleCount;
    private final int words;
    private final int[] bitToRule;
    /** Per column, in evaluation order. */
    private final Map<String, long[]>[] valueMasks;
    private final long[][] wildcardMasks;
    private final long[] allRules;

    /**
     * Parses table cells: per rule (rule order) and column (evaluation order), the accepted values,
     * or null for a wildcard. A null or blank cell is a wildcard; {@code a,b} accepts a or b. Only
     * needed while building, so the index does not retain it.
     */
    @SuppressWarnings("unchecked")
    static Set<String>[][] parseCells(List<List<String>> rows, int columns) {
        var cells = new Set[rows.size()][columns];
        for (int rule = 0; rule < rows.size(); rule++) {
            for (int c = 0; c < columns; c++) {
                var cell = rows.get(rule).get(c);
                cells[rule][c] = cell == null || cell.isBlank() ? null : new LinkedHashSet<>(List.of(cell.split(",")));
            }
        }
        return cells;
    }

    /**
     * @param cells     from {@link #parseCells}
     * @param columns   number of columns
     * @param bitToRule which rule (index into {@code cells}) owns each bit
     */
    @SuppressWarnings("unchecked")
    BitsetIndex(Set<String>[][] cells, int columns, int[] bitToRule) {
        ruleCount = cells.length;
        words = (ruleCount + 63) >>> 6;
        this.bitToRule = bitToRule.clone();
        valueMasks = new Map[columns];
        wildcardMasks = new long[columns][words];
        allRules = new long[words];
        for (int bit = 0; bit < ruleCount; bit++) {
            set(allRules, bit);
        }

        for (int c = 0; c < columns; c++) {
            var masks = new HashMap<String, long[]>();
            for (int bit = 0; bit < ruleCount; bit++) {
                var cell = cells[bitToRule[bit]][c];
                if (cell == null) {
                    set(wildcardMasks[c], bit);
                } else {
                    for (var value : cell) {
                        set(masks.computeIfAbsent(value, v -> new long[words]), bit);
                    }
                }
            }
            for (var mask : masks.values()) {
                or(mask, wildcardMasks[c]); // wildcard rules accept every value
            }
            valueMasks[c] = Map.copyOf(masks);
        }
    }

    /**
     * Rules matching {@code input}, as a bitmask (bit order), or {@code null} if none match. The
     * returned array is freshly allocated and owned by the caller.
     */
    <T> long[] match(T input, ColumnReader<T> reader) {
        if (words == 0) {
            return null;
        }
        var acc = allRules.clone();
        for (int c = 0; c < valueMasks.length; c++) {
            var value = reader.read(input, c);
            long[] mask = value == null ? null : valueMasks[c].get(value);
            if (mask == null) {
                mask = wildcardMasks[c];
            }
            long any = 0;
            for (int w = 0; w < words; w++) {
                any |= (acc[w] &= mask[w]);
            }
            if (any == 0) {
                return null;
            }
        }
        return acc;
    }

    int ruleCount() {
        return ruleCount;
    }

    int words() {
        return words;
    }

    int columns() {
        return valueMasks.length;
    }

    /** The rule (index in rule order) owning {@code bit}. */
    int ruleAt(int bit) {
        return bitToRule[bit];
    }

    // ---- static analysis support --------------------------------------------------------------

    /** Mask of rules accepting {@code value} in {@code column}; any unknown value behaves like {@code null}. */
    long[] maskFor(int column, String value) {
        if (value == null) {
            return wildcardMasks[column];
        }
        return valueMasks[column].getOrDefault(value, wildcardMasks[column]);
    }

    /** Every value written in a column, plus {@code null} standing for all other values. */
    List<String> valueClasses(int column) {
        var values = new ArrayList<String>(valueMasks[column].keySet());
        values.add(null);
        return values;
    }

    /**
     * Rules sharing at least one input with the rule owning {@code bit}: per column, the union of the
     * masks of its cell's values (all rules for a wildcard), intersected across columns.
     */
    long[] overlapping(int bit, Set<String>[][] cells) {
        var acc = allRules.clone();
        int rule = bitToRule[bit];
        for (int c = 0; c < valueMasks.length; c++) {
            var cell = cells[rule][c];
            if (cell == null) {
                continue;
            }
            var union = new long[words];
            for (var value : cell) {
                or(union, valueMasks[c].get(value));
            }
            and(acc, union);
        }
        return acc;
    }

    // ---- bit helpers ------------------------------------------------------------------------------

    static void set(long[] mask, int bit) {
        mask[bit >>> 6] |= 1L << bit;
    }

    static boolean get(long[] mask, int bit) {
        return (mask[bit >>> 6] & (1L << bit)) != 0;
    }

    static void or(long[] target, long[] other) {
        for (int w = 0; w < target.length; w++) {
            target[w] |= other[w];
        }
    }

    static void and(long[] target, long[] other) {
        for (int w = 0; w < target.length; w++) {
            target[w] &= other[w];
        }
    }

    static boolean isEmpty(long[] mask) {
        for (long word : mask) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    /** Lowest set bit, or -1. */
    static int first(long[] mask) {
        for (int w = 0; w < mask.length; w++) {
            if (mask[w] != 0) {
                return (w << 6) + Long.numberOfTrailingZeros(mask[w]);
            }
        }
        return -1;
    }

    /** Next set bit at or after {@code from}, or -1. */
    static int next(long[] mask, int from) {
        int w = from >>> 6;
        if (w >= mask.length) {
            return -1;
        }
        long word = mask[w] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (w << 6) + Long.numberOfTrailingZeros(word);
            }
            if (++w == mask.length) {
                return -1;
            }
            word = mask[w];
        }
    }

    static int count(long[] mask) {
        int count = 0;
        for (long word : mask) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** Mask with the bits strictly below {@code bit} set. */
    static long[] below(int words, int bit) {
        var mask = new long[words];
        Arrays.fill(mask, 0, bit >>> 6, -1L);
        if ((bit & 63) != 0) {
            mask[bit >>> 6] = (1L << bit) - 1;
        }
        return mask;
    }
}
