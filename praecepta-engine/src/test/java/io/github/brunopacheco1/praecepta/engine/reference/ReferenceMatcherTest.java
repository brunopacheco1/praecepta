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
    void an_element_with_an_offset_is_compared_with_a_literal_without_one_by_local_time() {
        // DMN 1.5, Table 54: the literal takes the element's offset, so 08:00 equals 08:00 at any offset
        var rules = List.of(cell(1, "t", "time(\"08:00:00\")"), cell(2, "t", "not(time(\"08:00:00\"))"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false,
                Set.of(), Set.of("t"));
        var zoned = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(zoned))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(5))))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1)))))))
                .as("08:00Z, but 09:00 on the clock").containsExactly("2");
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(zoned, LocalTime.of(9, 0)))))).containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("t", List.of(LocalTime.of(9, 0)))))).containsExactly("2");
    }

    @Test
    void times_with_and_without_an_offset_compare_as_dmn_table_54_says() {
        // one cell per comparison, on a column of single times
        var rules = List.of(cell(1, "t", "< time(\"12:00:00+05:00\")"), cell(2, "t", "time(\"12:00:00+05:00\")"),
                cell(3, "t", "> time(\"12:00:00+05:00\")"), cell(4, "t", "[time(\"11:00:00\")..time(\"12:00:00Z\")]"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false);
        // without an offset, a time takes the literal's: 12:00 is 12:00+05:00
        Assertions.assertThat(ids(reference.matches(Map.of("t", LocalTime.of(11, 59))))).containsExactly("1", "4");
        Assertions.assertThat(ids(reference.matches(Map.of("t", LocalTime.NOON)))).containsExactly("2", "4");
        Assertions.assertThat(ids(reference.matches(Map.of("t", LocalTime.of(12, 1))))).containsExactly("3");
        // with offsets on both sides, by instant: 12:00+05:00 is 07:00Z
        Assertions.assertThat(ids(reference.matches(Map.of("t", OffsetTime.of(7, 0, 0, 0, ZoneOffset.UTC))))).containsExactly("2");
        Assertions.assertThat(ids(reference.matches(Map.of("t", OffsetTime.of(12, 0, 0, 0, ZoneOffset.UTC))))).containsExactly("3", "4");
        // 11:30+02:00 is 09:30Z: after 11:00 on the clock, and before 12:00Z by instant
        Assertions.assertThat(ids(reference.matches(Map.of("t", OffsetTime.of(11, 30, 0, 0, ZoneOffset.ofHours(2)))))).containsExactly("3", "4");
        Assertions.assertThat(ids(reference.matches(Map.of("t", OffsetTime.of(10, 30, 0, 0, ZoneOffset.ofHours(6)))))).containsExactly("1");
    }

    @Test
    void zoned_representatives_visit_every_pair_of_instants_and_local_times_that_some_value_has() {
        // instants: before, at and after 11:00Z; local times: before, at and after 12:00
        var rules = List.of(cell(1, "t", "< time(\"12:00:00\")"), cell(2, "t", "time(\"12:00:00\")"), cell(3, "t", "< time(\"11:00:00Z\")"),
                cell(4, "t", "time(\"11:00:00Z\")"));
        var reference = new ReferenceMatcher(rules, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false, Set.of("t"),
                Set.of());
        var regions = reference.representativeValues("t", false).stream()
                .map(value -> String.join("", ids(reference.matches(Map.of("t", value)))))
                .toList();
        Assertions.assertThat(regions).containsExactlyInAnyOrder("13", "14", "1", "23", "24", "2", "3", "4", "");
        // before 05:00 on the clock and after 23:00Z would be more than 18 hours behind: that region has no value
        var apart = List.of(cell(1, "t", "< time(\"05:00:00\")"), cell(2, "t", "> time(\"23:00:00Z\")"));
        var none = new ReferenceMatcher(apart, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false, Set.of("t"), Set.of());
        Assertions.assertThat(none.representativeValues("t", false).stream().map(value -> ids(none.matches(Map.of("t", value)))))
                .contains(List.of("1"), List.of("2"), List.of()).doesNotContain(List.of("1", "2"));
        var near = List.of(cell(1, "t", "< time(\"05:00:00\")"), cell(2, "t", "> time(\"22:59:59Z\")"));
        var some = new ReferenceMatcher(near, HitPolicy.COLLECT, Map.of(), Map.of("t", ColumnType.TIME), Map.of(), false, Set.of("t"), Set.of());
        Assertions.assertThat(some.representativeValues("t", false).stream().map(value -> ids(some.matches(Map.of("t", value)))))
                .contains(List.of("1", "2"));
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
        // 08:00 takes the offset of the element it is compared with: it equals 08:00Z, and not 09:00+01:00
        Assertions.assertThat(ids(reference.matches(Map.of("at", eight, "slots", List.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC))))))
                .containsExactly("1");
        Assertions.assertThat(ids(reference.matches(Map.of("at", eight, "slots", List.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1)))))))
                .containsExactly("2");
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
