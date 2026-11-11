/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/** The two forms of a stored mask: which one is chosen, and that both read alike. */
class MasksTest {

    @Test
    void auto_keeps_a_mask_dense_unless_at_most_a_quarter_of_its_words_are_set() {
        Assertions.assertThat(Masks.compact(mask(8, 0, 64), Masks.Form.AUTO)).containsExactly(0, 1L, 1, 1L);
        var threeWords = mask(8, 0, 64, 128);
        Assertions.assertThat(Masks.compact(threeWords, Masks.Form.AUTO)).isSameAs(threeWords);
        // many bits in one word are still one word
        Assertions.assertThat(Masks.compact(mask(4, 0, 1, 2, 63), Masks.Form.AUTO)).containsExactly(0, 0x8000_0000_0000_0007L);
    }

    @Test
    void small_tables_keep_every_mask_but_the_empty_one_dense() {
        for (int words = 1; words < 4; words++) {
            var oneBit = mask(words, 64 * words - 1);
            Assertions.assertThat(Masks.compact(oneBit, Masks.Form.AUTO)).as("%d words", words).isSameAs(oneBit);
            Assertions.assertThat(Masks.compact(new long[words], Masks.Form.AUTO)).isSameAs(Masks.EMPTY);
        }
    }

    @Test
    void empty_masks_share_one_array_and_full_masks_stay_dense() {
        Assertions.assertThat(Masks.compact(new long[100], Masks.Form.AUTO)).isSameAs(Masks.EMPTY);
        Assertions.assertThat(Masks.compact(new long[3], Masks.Form.SPARSE)).isSameAs(Masks.EMPTY);
        var full = full(5);
        Assertions.assertThat(Masks.compact(full, Masks.Form.AUTO)).isSameAs(full);
        Assertions.assertThat(Masks.compact(full, Masks.Form.SPARSE)).hasSize(10);
    }

    @Test
    void the_dense_form_never_compacts() {
        var empty = new long[100];
        Assertions.assertThat(Masks.compact(empty, Masks.Form.DENSE)).isSameAs(empty);
    }

    @Test
    void sparse_is_forced_unless_it_would_have_as_many_longs_as_the_dense_form() {
        // one word of two would be two longs either way, so it stays dense: the length tells the forms apart
        var oneOfTwo = mask(2, 70);
        Assertions.assertThat(Masks.compact(oneOfTwo, Masks.Form.SPARSE)).isSameAs(oneOfTwo);
        Assertions.assertThat(Masks.compact(mask(1, 5), Masks.Form.SPARSE)).containsExactly(0, 1L << 5);
        Assertions.assertThat(Masks.compact(mask(2, 5, 70), Masks.Form.SPARSE)).containsExactly(0, 1L << 5, 1, 1L << 6);
    }

    @Test
    void bits_at_word_edges_read_alike_in_both_forms() {
        int words = 5;
        var edges = new int[] {0, 63, 64, 127, 128, 255, 256, 319};
        var dense = mask(words, edges);
        for (var form : Masks.Form.values()) {
            var stored = Masks.compact(dense, form);
            for (int bit = 0; bit < 64 * words; bit++) {
                Assertions.assertThat(Masks.get(stored, words, bit)).as("%s, bit %d", form, bit).isEqualTo(BitsetIndex.get(dense, bit));
            }
            Assertions.assertThat(Masks.dense(stored, words)).isEqualTo(dense);
        }
    }

    @Test
    void and_clears_the_words_a_sparse_mask_leaves_out() {
        var target = full(6);
        Masks.and(target, Masks.compact(mask(6, 64, 127, 300), Masks.Form.SPARSE));
        Assertions.assertThat(target).isEqualTo(mask(6, 64, 127, 300));

        var all = full(6);
        Masks.and(all, Masks.EMPTY);
        Assertions.assertThat(all).containsOnly(0L);

        var first = full(6);
        Masks.and(first, Masks.compact(mask(6, 0), Masks.Form.SPARSE));
        Assertions.assertThat(first).isEqualTo(mask(6, 0));

        var last = full(6);
        Masks.and(last, Masks.compact(mask(6, 383), Masks.Form.SPARSE));
        Assertions.assertThat(last).isEqualTo(mask(6, 383));
    }

    @Test
    void or_sets_only_the_words_a_sparse_mask_holds() {
        var target = mask(4, 1);
        Masks.or(target, Masks.compact(mask(4, 64, 255), Masks.Form.SPARSE));
        Assertions.assertThat(target).isEqualTo(mask(4, 1, 64, 255));
        Masks.or(target, Masks.EMPTY);
        Assertions.assertThat(target).isEqualTo(mask(4, 1, 64, 255));
    }

    @Test
    void subsets_of_a_sparse_mask() {
        var of = Masks.compact(mask(8, 3, 64, 500), Masks.Form.SPARSE);
        Assertions.assertThat(Masks.isSubset(mask(8, 3, 500), of)).isTrue();
        Assertions.assertThat(Masks.isSubset(new long[8], of)).isTrue();
        Assertions.assertThat(Masks.isSubset(mask(8, 3, 65), of)).isFalse();     // another bit of a word it holds
        Assertions.assertThat(Masks.isSubset(mask(8, 3, 200), of)).isFalse();    // a word it leaves out
        Assertions.assertThat(Masks.isSubset(mask(8, 511), of)).isFalse();       // after its last word
        Assertions.assertThat(Masks.isSubset(mask(8, 0), Masks.EMPTY)).isFalse();
        Assertions.assertThat(Masks.isSubset(new long[8], Masks.EMPTY)).isTrue();
    }

    @Test
    void pairs_intersect_with_both_forms_and_drop_the_words_left_empty() {
        int words = 6;
        var pairs = Masks.compact(mask(words, 0, 63, 64, 200, 383), Masks.Form.SPARSE).clone();
        int count = pairs.length / 2;
        count = Masks.andPairs(pairs, count, mask(words, 63, 64, 65, 383), words);
        Assertions.assertThat(Arrays.copyOf(pairs, 2 * count)).containsExactly(0, 1L << 63, 1, 1L, 5, 1L << 63);
        count = Masks.andPairs(pairs, count, Masks.compact(mask(words, 64, 383, 100), Masks.Form.SPARSE), words);
        Assertions.assertThat(Arrays.copyOf(pairs, 2 * count)).containsExactly(1, 1L, 5, 1L << 63);
        count = Masks.andPairs(pairs, count, Masks.EMPTY, words);
        Assertions.assertThat(count).isZero();
    }

    @Test
    void a_column_of_many_values_stores_their_masks_sparse_and_a_busy_column_dense() {
        int rules = 1000;
        int words = BitsetIndex.wordsFor(rules);
        var codes = new ArrayList<UnaryTests>();
        var busy = new ArrayList<UnaryTests>();
        for (int r = 0; r < rules; r++) {
            codes.add(CellParser.parse("\"k" + r + "\"", ColumnType.STRING));
            busy.add(CellParser.parse(r % 2 == 0 ? "-" : "\"x\"", ColumnType.STRING));
        }
        var byCode = new ColumnIndex("code", ColumnType.STRING, codes, null, words, Zoning.LOCAL, null, Masks.Form.AUTO);
        var mask = byCode.maskFor("k700");
        Assertions.assertThat(mask).containsExactly(10, 1L << (700 - 640));
        Assertions.assertThat(byCode.maskFor("unknown")).isSameAs(Masks.EMPTY);
        Assertions.assertThat(byCode.accepts(byCode.classCount() - 1, 700)).isFalse();

        var byWildcards = new ColumnIndex("busy", ColumnType.STRING, busy, null, words, Zoning.LOCAL, null, Masks.Form.AUTO);
        Assertions.assertThat(byWildcards.maskFor("x")).hasSize(words);
        Assertions.assertThat(byWildcards.maskFor("y")).hasSize(words);
    }

    @Test
    void a_large_lookup_table_evaluates_alike_in_every_form() {
        int rules = 700;
        var praecepta = new ArrayList<Praeceptum>();
        for (int r = 0; r < rules; r++) {
            var cells = new HashMap<String, String>();
            cells.put("code", "k" + r);
            cells.put("group", "g" + r % 50);
            cells.put("flag", r % 3 == 0 ? "-" : "yes");
            praecepta.add(new Praeceptum(r + 1,
                    new PraeceptaInput(cells),
                    new PraeceptaOutput(Map.of("id", String.valueOf(r + 1)))));
        }
        var inputs = new ArrayList<Map<String, String>>();
        for (int r = 0; r < rules; r += 7) {
            inputs.add(Map.of("code", "k" + r, "group", "g" + r % 50, "flag", "yes"));
            inputs.add(Map.of("code", "k" + r, "group", "g" + (r + 1) % 50, "flag", "yes"));
            inputs.add(Map.of("code", "k" + r, "group", "g" + r % 50, "flag", "no"));
        }
        List<List<String>> expected = null;
        for (var form : Masks.Form.values()) {
            var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                    .hitPolicy(HitPolicy.COLLECT)
                    .rules(praecepta)
                    .input("code", input -> input.get("code"))
                    .input("group", input -> input.get("group"))
                    .input("flag", input -> input.get("flag"))
                    .output(output -> output.values().get("id"))
                    .maskForm(form)
                    .build();
            var results = inputs.stream().map(engine::evaluate).toList();
            if (expected == null) {
                expected = results;
            }
            Assertions.assertThat(results).as("%s", form).isEqualTo(expected);
        }
        Assertions.assertThat(expected.get(0)).containsExactly("1");
        Assertions.assertThat(expected.get(1)).isEmpty();
        Assertions.assertThat(expected.get(2)).containsExactly("1"); // rule 1's flag is a wildcard
        Assertions.assertThat(expected.get(5)).isEmpty();             // rule 8 wants flag yes
    }

    @Test
    void any_compares_rules_in_different_words_by_their_shared_output_masks() {
        // rules 1-69 give "x" for code k<r>; rule 70 (in the second word) gives "y" for k3, and rule 71 "x" for k5
        var praecepta = new ArrayList<Praeceptum>();
        for (int r = 1; r <= 69; r++) {
            praecepta.add(new Praeceptum(r, new PraeceptaInput(Map.of("code", "k" + r)), new PraeceptaOutput(Map.of("out", "x"))));
        }
        praecepta.add(new Praeceptum(70, new PraeceptaInput(Map.of("code", "k3")), new PraeceptaOutput(Map.of("out", "y"))));
        praecepta.add(new Praeceptum(71, new PraeceptaInput(Map.of("code", "k5")), new PraeceptaOutput(Map.of("out", "x"))));
        for (var form : Masks.Form.values()) {
            var engine = PraeceptaEngine.<String, String>builder()
                    .hitPolicy(HitPolicy.ANY)
                    .rules(praecepta)
                    .input("code", code -> code)
                    .output(output -> output.values().get("out"))
                    .maskForm(form)
                    .build();
            Assertions.assertThat(engine.evaluate("k5")).as("%s: rules 5 and 71 agree", form).containsExactly("x");
            Assertions.assertThat(engine.evaluate("k10")).as("%s", form).containsExactly("x");
            Assertions.assertThatThrownBy(() -> engine.evaluate("k3")).as("%s: rules 3 and 70 disagree", form)
                    .isInstanceOf(HitPolicyViolationException.class)
                    .hasMessage("ANY hit policy violated: matching rules [3, 70] have different outputs");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A dense mask of {@code words} words with the given bits set. */
    static long[] mask(int words, int... bits) {
        var mask = new long[words];
        for (int bit : bits) {
            BitsetIndex.set(mask, bit);
        }
        return mask;
    }

    private static long[] full(int words) {
        var mask = new long[words];
        Arrays.fill(mask, -1L);
        return mask;
    }
}
