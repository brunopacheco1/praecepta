/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Arrays;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/**
 * Matches inputs against all rules at once with bitmasks. Each rule owns one bit; each column
 * partitions its values into classes ({@link ColumnIndex}) carrying the mask of rules accepting them.
 * An input's matches are the AND of one class mask per column.
 *
 * <p>Memory is proportional to columns × classes × rules / 8 bytes, with no wildcard expansion.
 * Bits are assigned in a caller-chosen order (see {@link #ruleAt(int)}), which lets "lowest set bit"
 * mean "first in rule order" or "highest priority".
 */
final class BitsetIndex {

    /** Reads the raw value of column {@code column} (index order); the column converts it. */
    @FunctionalInterface
    interface ColumnReader<T> {
        Object read(T input, int column);
    }

    /** The result of {@link #match} when no rule matches: a real result always has a bit set. */
    static final long[] NO_MATCH = new long[0];

    private final int ruleCount;
    private final int words;
    private final int[] bitToRule;
    private final ColumnIndex[] columns;
    private final long[] allRules;

    /**
     * @param columns   one index per column, in evaluation order, built over the same bit order
     * @param bitToRule which rule (index in rule order) owns each bit
     */
    BitsetIndex(List<ColumnIndex> columns, int[] bitToRule) {
        this.ruleCount = bitToRule.length;
        this.words = wordsFor(ruleCount);
        this.bitToRule = bitToRule.clone();
        this.columns = columns.toArray(ColumnIndex[]::new);
        allRules = new long[words];
        for (int bit = 0; bit < ruleCount; bit++) {
            set(allRules, bit);
        }
    }

    static int wordsFor(int rules) {
        return (rules + 63) >>> 6;
    }

    /**
     * Rules matching {@code input}, as a bitmask (bit order), or {@link #NO_MATCH} if none match. A
     * match is freshly allocated and owned by the caller.
     *
     * @throws InvalidInputException if a value is outside its column's declared input values
     */
    <T> long[] match(T input, ColumnReader<T> reader) {
        if (words == 0) {
            validate(input, reader, 0);
            return NO_MATCH;
        }
        var matches = allRules.clone();
        for (int c = 0; c < columns.length; c++) {
            var mask = columns[c].maskFor(reader.read(input, c));
            long any = 0;
            for (int w = 0; w < words; w++) {
                any |= (matches[w] &= mask[w]);
            }
            if (any == 0) {
                // no rule left, but an invalid value further on must still be rejected
                validate(input, reader, c + 1);
                return NO_MATCH;
            }
        }
        return matches;
    }

    /**
     * Checks declared input values (and parsability) for the columns from {@code from} on. A STRING
     * column without declared input values accepts any value, so it is skipped.
     */
    private <T> void validate(T input, ColumnReader<T> reader, int from) {
        for (int c = from; c < columns.length; c++) {
            if (columns[c].hasAllowedValues || columns[c].type != ColumnType.STRING) {
                columns[c].maskFor(reader.read(input, c));
            }
        }
    }

    int ruleCount() {
        return ruleCount;
    }

    int words() {
        return words;
    }

    int columnCount() {
        return columns.length;
    }

    ColumnIndex column(int c) {
        return columns[c];
    }

    /** A new mask with every rule's bit set. */
    long[] allRules() {
        return allRules.clone();
    }

    /** The rule (index in rule order) owning {@code bit}. */
    int ruleAt(int bit) {
        return bitToRule[bit];
    }

    /**
     * Rules sharing at least one valid input with the rule owning {@code bit}: per column, the union of
     * the masks of the classes it accepts that a valid input can fall in, intersected across columns.
     */
    long[] overlapping(int bit) {
        var overlapping = allRules.clone();
        for (var column : columns) {
            var union = new long[words];
            for (int k = 0; k < column.classCount(); k++) {
                if (column.canOccur(k) && get(column.masks[k], bit)) {
                    or(union, column.masks[k]);
                }
            }
            and(overlapping, union);
        }
        return overlapping;
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

    /** Whether every bit set in {@code mask} is also set in {@code of}. */
    static boolean isSubset(long[] mask, long[] of) {
        for (int w = 0; w < mask.length; w++) {
            if ((mask[w] & ~of[w]) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Whether the two masks share a set bit. */
    static boolean intersects(long[] a, long[] b) {
        for (int w = 0; w < a.length; w++) {
            if ((a[w] & b[w]) != 0) {
                return true;
            }
        }
        return false;
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
