/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Arrays;

/**
 * The two forms of a stored rule mask (one bit per rule, see {@link BitsetIndex}), chosen per mask by
 * how many of its words hold a set bit.
 *
 * <ul>
 * <li><b>Dense:</b> {@code words} longs, word {@code w} holding the bits {@code 64w} to {@code 64w + 63}.
 * The form of every mask that is not nearly empty, and of every mask the engine computes (matches,
 * candidates).</li>
 * <li><b>Sparse:</b> the words that are not zero, as pairs {@code [w0, bits0, w1, bits1, ...]} in
 * increasing word order. A value of a column that few rules accept, in a table of thousands of rules,
 * then takes a few longs instead of thousands of bytes.</li>
 * </ul>
 *
 * Both forms are a {@code long[]}, told apart by length: a sparse mask never has {@code words} longs.
 * So the evaluation loop reads a column's mask as before and pays one length comparison per column
 * (always equal, for a table without sparse masks). A stored mask is never changed after it is built.
 */
final class Masks {

    /** The sparse form of a mask with no bit set, shared. */
    static final long[] EMPTY = new long[0];

    /** Which form {@link #compact} stores a mask in. */
    enum Form {
        /** Always dense, as before compact masks: the reference the other forms are tested against. */
        DENSE,
        /**
         * Sparse when it takes at most half the memory of the dense form: when at most a quarter of the
         * words hold a set bit. The default.
         */
        AUTO,
        /** Sparse whenever the encoding allows it (not when it would have {@code words} longs too); for tests. */
        SPARSE,
        /**
         * Sparse as {@link #SPARSE} when the mask has an odd number of bits set, else dense; for tests,
         * so that even a table of one word mixes both forms.
         */
        MIXED
    }

    private Masks() {
    }

    /**
     * The mask, a dense one of {@code dense.length} words, in the form {@code form} chooses: {@code dense}
     * itself, or a new sparse mask. The caller must not change {@code dense} afterwards.
     */
    static long[] compact(long[] dense, Form form) {
        if (form == Form.DENSE) {
            return dense;
        }
        int nonZero = 0;
        int bits = 0;
        for (long word : dense) {
            if (word != 0) {
                nonZero++;
                bits += Long.bitCount(word);
            }
        }
        int words = dense.length;
        boolean sparse = switch (form) {
            case AUTO -> 4 * nonZero <= words;
            case SPARSE -> 2 * nonZero != words;
            case MIXED -> 2 * nonZero != words && bits % 2 == 1;
            case DENSE -> false;
        };
        if (!sparse) {
            return dense;
        }
        if (nonZero == 0) {
            return EMPTY;
        }
        var pairs = new long[2 * nonZero];
        int i = 0;
        for (int w = 0; w < words; w++) {
            if (dense[w] != 0) {
                pairs[i++] = w;
                pairs[i++] = dense[w];
            }
        }
        return pairs;
    }

    /** Whether a stored mask of a table with {@code words} words is sparse. */
    static boolean isSparse(long[] mask, int words) {
        return mask.length != words;
    }

    /** The mask, in either form, as a dense one of {@code words} words; a dense mask is returned itself. */
    static long[] dense(long[] mask, int words) {
        if (!isSparse(mask, words)) {
            return mask;
        }
        var dense = new long[words];
        for (int i = 0; i < mask.length; i += 2) {
            dense[(int) mask[i]] = mask[i + 1];
        }
        return dense;
    }

    /** Whether {@code bit} is set in the mask, in either form, of a table with {@code words} words. */
    static boolean get(long[] mask, int words, int bit) {
        if (!isSparse(mask, words)) {
            return BitsetIndex.get(mask, bit);
        }
        int pair = find(mask, bit >>> 6);
        return pair >= 0 && (mask[pair + 1] & (1L << bit)) != 0;
    }

    /** The index of the pair of word {@code word} in a sparse mask, or -1 if that word is zero. */
    private static int find(long[] pairs, int word) {
        int low = 0;
        int high = pairs.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long at = pairs[2 * mid];
            if (at < word) {
                low = mid + 1;
            } else if (at > word) {
                high = mid - 1;
            } else {
                return 2 * mid;
            }
        }
        return -1;
    }

    /** {@code target &= mask}: {@code target} is dense, {@code mask} in either form, of the same table. */
    static void and(long[] target, long[] mask) {
        if (!isSparse(mask, target.length)) {
            BitsetIndex.and(target, mask);
            return;
        }
        int next = 0; // the first word not yet visited
        for (int i = 0; i < mask.length; i += 2) {
            int w = (int) mask[i];
            Arrays.fill(target, next, w, 0L);
            target[w] &= mask[i + 1];
            next = w + 1;
        }
        Arrays.fill(target, next, target.length, 0L);
    }

    /** {@code target |= mask}: {@code target} is dense, {@code mask} in either form, of the same table. */
    static void or(long[] target, long[] mask) {
        if (!isSparse(mask, target.length)) {
            BitsetIndex.or(target, mask);
            return;
        }
        for (int i = 0; i < mask.length; i += 2) {
            target[(int) mask[i]] |= mask[i + 1];
        }
    }

    /**
     * Whether every bit set in {@code mask} (dense) is also set in {@code of} (either form, of the same
     * table).
     */
    static boolean isSubset(long[] mask, long[] of) {
        if (!isSparse(of, mask.length)) {
            return BitsetIndex.isSubset(mask, of);
        }
        int i = 0; // the first pair of {@code of} at or after word w
        for (int w = 0; w < mask.length; w++) {
            if (mask[w] == 0) {
                continue;
            }
            while (i < of.length && of[i] < w) {
                i += 2;
            }
            long allowed = i < of.length && of[i] == w ? of[i + 1] : 0L;
            if ((mask[w] & ~allowed) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Intersects candidates kept as pairs {@code [w, bits]} (the first {@code count} pairs of
     * {@code pairs}, in increasing word order, every bits non-zero) with a mask in either form, in place.
     *
     * @return the number of pairs left, still in order and non-zero
     */
    static int andPairs(long[] pairs, int count, long[] mask, int words) {
        return isSparse(mask, words) ? andPairsSparse(pairs, count, mask) : andPairsDense(pairs, count, mask);
    }

    /** {@link #andPairs} with a dense mask. */
    private static int andPairsDense(long[] pairs, int count, long[] mask) {
        int kept = 0;
        for (int i = 0; i < 2 * count; i += 2) {
            long bits = pairs[i + 1] & mask[(int) pairs[i]];
            if (bits != 0) {
                pairs[kept++] = pairs[i];
                pairs[kept++] = bits;
            }
        }
        return kept / 2;
    }

    /** {@link #andPairs} with a sparse mask: both lists of pairs are in word order, so they merge. */
    private static int andPairsSparse(long[] pairs, int count, long[] mask) {
        int kept = 0;
        int j = 0;
        for (int i = 0; i < 2 * count && j < mask.length; i += 2) {
            long w = pairs[i];
            while (j < mask.length && mask[j] < w) {
                j += 2;
            }
            if (j < mask.length && mask[j] == w) {
                long bits = pairs[i + 1] & mask[j + 1];
                if (bits != 0) {
                    pairs[kept++] = w;
                    pairs[kept++] = bits;
                }
            }
        }
        return kept / 2;
    }
}
