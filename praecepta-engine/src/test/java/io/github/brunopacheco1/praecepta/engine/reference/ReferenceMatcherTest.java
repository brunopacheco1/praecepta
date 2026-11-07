/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Aggregator;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Outcome;

/** Hand-checked examples pinning the reference to DMN 1.3 §8.2.12, so the oracle itself is trusted. */
class ReferenceMatcherTest {

    //            colour   size   → grade  points
    // rule 1:    red      -        A       10
    // rule 2:    red,blue S        B        5
    // rule 3:    -        S        A        1
    private static final List<Praeceptum> RULES = List.of(
            rule(1, "red", "", "A", "10"),
            rule(2, "red,blue", "S", "B", "5"),
            rule(3, "", "S", "A", "1"));

    private static final Map<String, String> RED_SMALL = input("red", "S");
    private static final Map<String, String> BLUE_SMALL = input("blue", "S");
    private static final Map<String, String> GREEN_LARGE = input("green", "L");
    private static final Map<String, String> MISSING_COLOUR_SMALL = input(null, "S");

    @Test
    void wildcards_and_multi_value_cells_match() {
        var reference = new ReferenceMatcher(RULES, HitPolicy.COLLECT);
        Assertions.assertThat(ids(reference.matches(RED_SMALL))).containsExactly("1", "2", "3");
        Assertions.assertThat(ids(reference.matches(BLUE_SMALL))).containsExactly("2", "3");
        Assertions.assertThat(ids(reference.matches(GREEN_LARGE))).isEmpty();
    }

    @Test
    void missing_input_only_matches_wildcards() {
        var reference = new ReferenceMatcher(RULES, HitPolicy.COLLECT);
        Assertions.assertThat(ids(reference.matches(MISSING_COLOUR_SMALL))).containsExactly("3");
    }

    @Test
    void unique_is_violated_by_overlapping_rules() {
        var reference = new ReferenceMatcher(RULES, HitPolicy.UNIQUE);
        Assertions.assertThat(reference.evaluate(RED_SMALL)).isInstanceOf(Outcome.Violation.class);
        Assertions.assertThat(hitIds(reference.evaluate(MISSING_COLOUR_SMALL))).containsExactly("3");
        Assertions.assertThat(hitIds(reference.evaluate(GREEN_LARGE))).isEmpty();
    }

    @Test
    void any_requires_matching_rules_to_agree() {
        var gradeOnly = RULES.stream()
                .map(r -> new Praeceptum(r.priority(), r.input(), new PraeceptaOutput(Map.of("grade", r.output().values().get("grade")))))
                .toList();
        var reference = new ReferenceMatcher(gradeOnly, HitPolicy.ANY);
        Assertions.assertThat(reference.evaluate(RED_SMALL)).isInstanceOf(Outcome.Violation.class); // A, B, A
        var red = input("red", "L"); // only rule 1
        Assertions.assertThat(((Outcome.Hits) reference.evaluate(red)).outputs())
                .containsExactly(new PraeceptaOutput(Map.of("grade", "A")));
    }

    @Test
    void first_and_rule_order_follow_table_order() {
        Assertions.assertThat(hitIds(new ReferenceMatcher(RULES, HitPolicy.FIRST).evaluate(BLUE_SMALL))).containsExactly("2");
        Assertions.assertThat(hitIds(new ReferenceMatcher(RULES, HitPolicy.RULE_ORDER).evaluate(RED_SMALL))).containsExactly("1", "2", "3");
    }

    @Test
    void priority_and_output_order_follow_output_value_lists() {
        var priority = new LinkedHashMap<String, List<String>>();
        priority.put("grade", List.of("B", "A")); // B outranks A
        Assertions.assertThat(hitIds(new ReferenceMatcher(RULES, HitPolicy.PRIORITY, priority).evaluate(RED_SMALL)))
                .containsExactly("2");
        // ties (rules 1 and 3 are both A) keep rule order
        Assertions.assertThat(hitIds(new ReferenceMatcher(RULES, HitPolicy.OUTPUT_ORDER, priority).evaluate(RED_SMALL)))
                .containsExactly("2", "1", "3");
    }

    @Test
    void collect_aggregates_numeric_outputs() {
        var reference = new ReferenceMatcher(RULES, HitPolicy.COLLECT);
        Assertions.assertThat(reference.aggregate(RED_SMALL, "points", Aggregator.SUM)).isEqualByComparingTo("16");
        Assertions.assertThat(reference.aggregate(RED_SMALL, "points", Aggregator.MIN)).isEqualByComparingTo("1");
        Assertions.assertThat(reference.aggregate(RED_SMALL, "points", Aggregator.MAX)).isEqualByComparingTo("10");
        Assertions.assertThat(reference.aggregate(RED_SMALL, "points", Aggregator.COUNT)).isEqualByComparingTo("3");
        Assertions.assertThat(reference.aggregate(GREEN_LARGE, "points", Aggregator.SUM)).isNull();
        Assertions.assertThat(reference.aggregate(GREEN_LARGE, "points", Aggregator.COUNT)).isEqualTo(BigDecimal.ZERO);
    }

    private static Praeceptum rule(int id, String colour, String size, String grade, String points) {
        var cells = new HashMap<String, String>();
        cells.put("colour", colour);
        cells.put("size", size);
        return new Praeceptum(id, new PraeceptaInput(cells),
                new PraeceptaOutput(Map.of("id", String.valueOf(id), "grade", grade, "points", points)));
    }

    private static Map<String, String> input(String colour, String size) {
        var input = new HashMap<String, String>();
        input.put("colour", colour);
        input.put("size", size);
        return input;
    }

    private static List<String> ids(List<Praeceptum> rules) {
        return rules.stream().map(r -> r.output().values().get("id")).toList();
    }

    private static List<String> hitIds(Outcome outcome) {
        return ((Outcome.Hits) outcome).outputs().stream().map(o -> o.values().get("id")).toList();
    }
}
