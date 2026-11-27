/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.feel.FeelRange;

/**
 * The text of an output's value, which an output strategy that takes text is given: how lists and contexts are written,
 * where their text stops, and which texts of a number output are the same value.
 */
class OutputTextTest {

    @Test
    void a_simple_value_is_its_plain_text() {
        assertThat(OutputText.of("PT")).isEqualTo("PT");
        assertThat(OutputText.of(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(OutputText.of(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(OutputText.of(true)).isEqualTo("true");
        assertThat(OutputText.of(LocalDate.of(2025, 1, 31))).isEqualTo("2025-01-31");
        assertThat(OutputText.of(Duration.ofHours(26))).isEqualTo("P1DT2H");
    }

    @Test
    void a_list_is_written_as_feel_writes_it_its_values_as_literals() {
        assertThat(OutputText.of(List.of("PT", "BR"))).isEqualTo("[\"PT\", \"BR\"]");
        assertThat(OutputText.of(List.of())).isEqualTo("[]");
        assertThat(OutputText.of(Arrays.asList(new BigDecimal("1.50"), null, true, LocalDate.of(2025, 1, 31), 7)))
                .isEqualTo("[1.50, null, true, date(\"2025-01-31\"), 7]");
        assertThat(OutputText.of(List.of(List.of("a \"quoted\" one"), List.of()))).isEqualTo("[[\"a \\\"quoted\\\" one\"], []]");
        assertThat(OutputText.of(List.of(FeelRange.between(BigDecimal.ONE, true, BigDecimal.TEN, false))))
                .as("a value that has no literal, as it writes itself").isEqualTo("[[1..10)]");
    }

    @Test
    void a_context_is_written_with_its_keys_as_strings_in_their_order() {
        var context = new LinkedHashMap<Object, Object>();
        context.put("b c", List.of("x"));
        context.put("a", new BigDecimal("1"));
        context.put(3, null);
        context.put("inner", Map.of("deep", false));

        assertThat(OutputText.of(context)).isEqualTo("{\"b c\": [\"x\"], \"a\": 1, \"3\": null, \"inner\": {\"deep\": false}}");
        assertThat(OutputText.of(Map.of())).isEqualTo("{}");
    }

    /** A string inside that many lists, or contexts of one entry. */
    private static Object nested(int levels, boolean contexts) {
        Object value = "x";
        for (int i = 0; i < levels; i++) {
            value = contexts ? Map.of("k", value) : List.of(value);
        }
        return value;
    }

    @Test
    void lists_and_contexts_are_written_64_deep_and_deeper_ones_as_an_ellipsis() {
        assertThat(OutputText.of(nested(64, false))).isEqualTo("[".repeat(64) + "\"x\"" + "]".repeat(64));
        assertThat(OutputText.of(nested(65, false))).isEqualTo("[".repeat(64) + "[...]" + "]".repeat(64));
        assertThat(OutputText.of(nested(64, true))).isEqualTo("{\"k\": ".repeat(64) + "\"x\"" + "}".repeat(64));
        assertThat(OutputText.of(nested(65, true))).isEqualTo("{\"k\": ".repeat(64) + "{...}" + "}".repeat(64));
        assertThat(OutputText.of(List.of(nested(64, true)))).as("a list counts as a context does")
                .isEqualTo("[" + "{\"k\": ".repeat(63) + "{...}" + "}".repeat(63) + "]");
    }

    @Test
    void the_text_of_a_list_stops_at_its_longest_with_an_ellipsis() {
        var many = OutputText.of(Collections.nCopies(20_000, "abcdefgh"));

        assertThat(many).hasSize(OutputText.MAX_TEXT_LENGTH + 3).startsWith("[\"abcdefgh\", \"abcdefgh\", ").endsWith("...");
        assertThat(OutputText.of(Collections.nCopies(20_000, Map.of("k", "abcdefgh")))).hasSize(OutputText.MAX_TEXT_LENGTH + 3)
                .startsWith("[{\"k\": \"abcdefgh\"}, {").endsWith("...");
        assertThat(OutputText.of(Map.of("k", Collections.nCopies(20_000, "abcdefgh")))).hasSize(OutputText.MAX_TEXT_LENGTH + 3)
                .startsWith("{\"k\": [\"abcdefgh\", ").endsWith("...");
    }

    @Test
    void a_list_whose_text_is_at_its_longest_before_its_last_element_stops_there() {
        // [ and the string in quotes and the separator are as many characters as a text may have: the next element is not written
        var longest = "a".repeat(OutputText.MAX_TEXT_LENGTH - 5);
        var shorter = "a".repeat(OutputText.MAX_TEXT_LENGTH - 6);

        assertThat(OutputText.of(List.of(longest, "x"))).isEqualTo("[\"" + longest + "\", ...");
        assertThat(OutputText.of(List.of(shorter, "x"))).as("one character short of the longest").isEqualTo("[\"" + shorter + "\", \"x\"]");
        assertThat(OutputText.of(Map.of(longest, "x"))).as("a context's key and separator, likewise").isEqualTo("{\"" + longest + "\": ...");
        assertThat(OutputText.of(Map.of(shorter, "x"))).isEqualTo("{\"" + shorter + "\": \"x\"}");
    }

    @Test
    void a_computed_number_is_written_without_trailing_zeros_or_an_exponent() {
        assertThat(OutputText.numberText(new BigDecimal("1.50"))).isEqualTo("1.5");
        assertThat(OutputText.numberText(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(OutputText.numberText(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(OutputText.numberText(new BigDecimal("0E+7"))).isEqualTo("0");
        assertThat(OutputText.numberText(new BigDecimal("-0.0100"))).isEqualTo("-0.01");
    }

    @Test
    void two_texts_of_a_number_output_are_the_same_value_if_they_are_the_same_number() {
        assertThat(OutputText.sameValue("1.50", "1.5", true)).isTrue();
        assertThat(OutputText.sameValue(" 1.0 ", "1", true)).as("spaces around a number").isTrue();
        assertThat(OutputText.sameValue("1.50", "1.6", true)).isFalse();
        assertThat(OutputText.sameValue("1.50", "1.5", false)).as("an output that holds no number, by its text").isFalse();
        assertThat(OutputText.sameValue("high", "high", true)).as("text that is no number is kept").isTrue();
        assertThat(OutputText.sameValue("high", "low", true)).isFalse();
        assertThat(OutputText.sameValue(null, null, true)).isTrue();
        assertThat(OutputText.sameValue(null, "1", true)).isFalse();
        assertThat(OutputText.sameValue("1", null, true)).isFalse();
        assertThat(OutputText.sameValue(null, null, false)).isTrue();
    }

    @Test
    void outputs_are_compared_with_each_number_output_written_as_a_computed_number_and_the_others_as_they_are() {
        var values = new HashMap<String, String>();
        values.put("total", "1.50");
        values.put("label", "1.50");
        values.put("grade", "high");
        values.put("missing", null);
        var output = new PraeceptaOutput(values, Map.of("copy", "price"), Map.of("sum", "price + 1"));

        var comparable = OutputText.comparable(output, Set.of("total", "grade", "missing", "absent"));

        var expected = new HashMap<String, String>();
        expected.put("total", "1.5");
        expected.put("label", "1.50");
        expected.put("grade", "high");
        expected.put("missing", null);
        assertThat(comparable.values()).isEqualTo(expected);
        assertThat(comparable.references()).isEqualTo(Map.of("copy", "price"));
        assertThat(comparable.expressions()).isEqualTo(Map.of("sum", "price + 1"));
        assertThat(values).as("the rule's own outputs are left as written").containsEntry("total", "1.50");
        assertThat(OutputText.comparable(output, Set.of())).as("no number output: the outputs themselves").isSameAs(output);
    }
}
