/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.assertj.core.api.Assertions;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Every operation on a stored mask gives, in every form, what it gives on the dense mask. Masks span one
 * to nine words, and their words are often zero, full, or a single bit at a word's edge.
 */
class MasksPropertiesTest {

    private static final int TRIES = 2000;

    /** Two dense masks of the same table. */
    record Pair(long[] a, long[] b) {
        int words() {
            return a.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pair(long[] otherA, long[] otherB) && Arrays.equals(a, otherA) && Arrays.equals(b, otherB);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(a) + Arrays.hashCode(b);
        }

        @Override
        public String toString() {
            return "Pair[" + Arrays.toString(a) + ", " + Arrays.toString(b) + "]";
        }
    }

    @Provide
    Arbitrary<Pair> pairs() {
        return Arbitraries.integers().between(1, 9).flatMap(words -> Combinators.combine(mask(words), mask(words)).as(Pair::new));
    }

    private static Arbitrary<long[]> mask(int words) {
        Arbitrary<Long> word = Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.just(0L)),
                Tuple.of(1, Arbitraries.just(-1L)),
                Tuple.of(1, Arbitraries.just(1L)),
                Tuple.of(1, Arbitraries.just(Long.MIN_VALUE)),
                Tuple.of(2, Arbitraries.integers().between(0, 63).map(bit -> 1L << bit)),
                Tuple.of(2, Arbitraries.longs()));
        return word.list().ofSize(words).map(list -> list.stream().mapToLong(Long::longValue).toArray());
    }

    @Property(tries = TRIES)
    void a_stored_mask_holds_the_same_bits(@ForAll("pairs") Pair p, @ForAll Masks.Form form) {
        var stored = Masks.compact(p.a().clone(), form);
        Assertions.assertThat(Masks.dense(stored, p.words())).isEqualTo(p.a());
        for (int bit = 0; bit < 64 * p.words(); bit++) {
            Assertions.assertThat(Masks.get(stored, p.words(), bit)).as("bit %d", bit).isEqualTo(BitsetIndex.get(p.a(), bit));
        }
        if (Masks.isSparse(stored, p.words())) {
            Assertions.assertThat(form).isNotEqualTo(Masks.Form.DENSE);
        }
    }

    @Property(tries = TRIES)
    void and_and_or_give_what_they_give_on_the_dense_mask(@ForAll("pairs") Pair p, @ForAll Masks.Form form) {
        var stored = Masks.compact(p.b().clone(), form);

        var expected = p.a().clone();
        BitsetIndex.and(expected, p.b());
        var actual = p.a().clone();
        Masks.and(actual, stored);
        Assertions.assertThat(actual).isEqualTo(expected);

        expected = p.a().clone();
        BitsetIndex.or(expected, p.b());
        actual = p.a().clone();
        Masks.or(actual, stored);
        Assertions.assertThat(actual).isEqualTo(expected);
    }

    @Property(tries = TRIES)
    void subsets_are_decided_as_on_the_dense_mask(@ForAll("pairs") Pair p, @ForAll Masks.Form form, @ForAll boolean inside) {
        var mask = p.a().clone();
        if (inside) { // a subset half of the time, else usually not one
            BitsetIndex.and(mask, p.b());
        }
        var stored = Masks.compact(p.b().clone(), form);
        Assertions.assertThat(Masks.isSubset(mask, stored)).isEqualTo(BitsetIndex.isSubset(mask, p.b()));
    }

    @Property(tries = TRIES)
    void pairs_intersect_as_the_dense_masks_do(@ForAll("pairs") Pair p, @ForAll Masks.Form form) {
        var candidates = pairsOf(p.a());
        int count = Masks.andPairs(candidates, candidates.length / 2, Masks.compact(p.b().clone(), form), p.words());

        var expected = p.a().clone();
        BitsetIndex.and(expected, p.b());
        Assertions.assertThat(Arrays.copyOf(candidates, 2 * count)).isEqualTo(pairsOf(expected));
    }

    /** The non-zero words of a dense mask as {@code [w, bits]} pairs, in word order. */
    private static long[] pairsOf(long[] dense) {
        return IntStream.range(0, dense.length)
                .filter(w -> dense[w] != 0)
                .boxed()
                .flatMapToLong(w -> LongStream.of(w, dense[w]))
                .toArray();
    }
}
