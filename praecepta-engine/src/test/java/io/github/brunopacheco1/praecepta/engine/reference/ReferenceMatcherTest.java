/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
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

    // ---- lists, zones and output expressions, as FEEL evaluates them --------------------------------

    @Test
    void a_list_cell_holds_when_some_element_passes_and_its_negation_when_none_does() {
        // FEEL: list contains(?, "PT"), not(list contains(?, "PT")), ? = null
        var rules = List.of(cell(1, "nat", "\"PT\""), cell(2, "nat", "not(\"PT\")"), cell(3, "nat", "null"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("nat", ColumnType.STRING), Map.of(), false,
                Set.of(), Set.of("nat"));
        Assertions.assertThat(ids(reference.matches(Map.of("nat", List.of("PT", "BR"))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("nat", List.of("BR"))))).containsExactly("2");
        // an empty list contains nothing; a missing one makes list contains null, so only ? = null holds
        Assertions.assertThat(ids(reference.matches(Map.of("nat", List.of())))).containsExactly("2");
        Assertions.assertThat(ids(reference.matches(nullValued("nat")))).containsExactly("3");
    }

    @Test
    void an_element_feel_cannot_compare_leaves_a_list_test_unknown() {
        // a time with an offset and one without: FEEL's comparison is null, so neither the test nor its not(...) holds
        var rules = List.of(cell(1, "t", "time(\"08:00:00\")"), cell(2, "t", "not(time(\"08:00:00\"))"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false,
                Set.of(), Set.of("t"));
        var zoned = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(reference.matches(Map.of("t", List.of(zoned)))).isEmpty();
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(zoned, LocalTime.of(8, 0)))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(zoned, LocalTime.of(9, 0)))))).isEmpty();
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(LocalTime.of(9, 0)))))).containsExactly("2");
    }

    @Test
    void a_value_is_in_a_named_list_as_feel_in_says() {
        // FEEL: at in slots, not(at in slots); a missing value is in no list
        var rules = List.of(cell(1, "at", "slots"), cell(2, "at", "not(slots)"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("at", ColumnType.TIME, "slots", ColumnType.TIME),
                Map.of(), true, Set.of(), Set.of("slots"));
        var eight = LocalTime.of(8, 0);
        Assertions.assertThat(ids(reference.matches(Map.of("at", eight, "slots", List.of(LocalTime.of(9, 0), eight))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("at", eight, "slots", List.of(LocalTime.of(9, 0)))))).containsExactly("2");
        // 08:00Z cannot be compared with 08:00: unknown, so neither holds
        Assertions.assertThat(reference.matches(Map.of("at", eight, "slots", List.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC))))).isEmpty();
        var missing = new HashMap<String, Object>(Map.of("slots", List.of(eight)));
        missing.put("at", null);
        Assertions.assertThat(ids(reference.matches(missing))).containsExactly("2");
    }

    @Test
    void output_expressions_follow_feel_numbers() {
        // FEEL: 1 / 3 has 34 digits (decimal128), 10.50 * 2 is the number 21, 0.0 * 5 is 0, and a missing operand gives null
        var rule = new Praeceptum(1, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of(),
                Map.of("third", "1 / 3", "double", "10.50 * 2", "zero", "0.0 * 5", "missing", "a * 2", "joined", "\"<\" + s + \">\"")));
        var reference = new ReferenceMatcher(List.of(rule), HitPolicy.COLLECT, Map.of(),
                Map.of("a", ColumnType.NUMBER, "s", ColumnType.STRING), Map.of());
        var values = reference.output(rule, Map.of("s", "x")).values();
        Assertions.assertThat(values).containsEntry("third", "0.3333333333333333333333333333333333")
                .containsEntry("double", "21").containsEntry("zero", "0").containsEntry("missing", null).containsEntry("joined", "<x>");
        Assertions.assertThat(values.get("third")).hasSize(36); // "0." and 34 digits
    }

    @Test
    void aggregations_skip_computed_outputs_that_are_missing() {
        var rules = List.of(
                new Praeceptum(1, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of("n", "5"))),
                new Praeceptum(2, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of(), Map.of("n", "a * 2"))),
                new Praeceptum(3, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of(), Map.of("n", "a / 0"))));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("a", ColumnType.NUMBER), Map.of());
        Assertions.assertThat(reference.aggregate(Map.of("a", new BigDecimal("1.5")), "n", Aggregator.SUM)).isEqualByComparingTo("8");
        Assertions.assertThat(reference.aggregate(nullValued("a"), "n", Aggregator.SUM)).isEqualByComparingTo("5");
        Assertions.assertThat(reference.aggregate(nullValued("a"), "n", Aggregator.MAX)).isEqualByComparingTo("5");
        Assertions.assertThat(reference.aggregate(nullValued("a"), "n", Aggregator.COUNT)).isEqualByComparingTo("3");
    }

    private static Praeceptum cell(int id, String column, String cell) {
        return new Praeceptum(id, new PraeceptaInput(Map.of(column, cell)), new PraeceptaOutput(Map.of("id", String.valueOf(id))));
    }

    private static Map<String, Object> nullValued(String column) {
        var input = new HashMap<String, Object>();
        input.put(column, null);
        return input;
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
