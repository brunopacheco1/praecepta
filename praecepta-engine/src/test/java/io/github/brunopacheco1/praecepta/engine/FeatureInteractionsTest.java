/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * The 0.4 features together: lists of times with a time zone (#25, #70), compact masks under list
 * columns (#17, #25), and output expressions next to list and zoned columns (#71).
 */
class FeatureInteractionsTest {

    record Slots(List<Object> slots) {
    }

    private static final OffsetTime EIGHT_UTC = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);

    //   slots                      → id
    // 1 time("09:00:00+01:00")        eight-utc       (08:00Z)
    // 2 not(time("08:00:00"))         not-local-eight
    // 3 >= time("12:00:00Z")          afternoon
    private static final List<Praeceptum> SLOT_RULES = List.of(
            rule(1, Map.of("slots", "time(\"09:00:00+01:00\")"), "eight-utc"),
            rule(2, Map.of("slots", "not(time(\"08:00:00\"))"), "not-local-eight"),
            rule(3, Map.of("slots", ">= time(\"12:00:00Z\")"), "afternoon"));

    @Test
    void lists_of_times_with_and_without_a_zone_compare_each_element_as_feel_does() {
        var engine = PraeceptaEngine.<Slots, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .listInput("slots", ColumnType.TIME, Slots::slots)
                .output(output -> output.values().get("id"))
                .rules(SLOT_RULES)
                .build();
        // two times with a zone by instant: 09:00+01:00 is 08:00Z; against 08:00 without one, 08:00Z counts as 08:00
        Assertions.assertThat(engine.evaluate(slots(EIGHT_UTC))).containsExactly("eight-utc");
        // 10:30+02:00 is 08:30Z, and its local time is not 08:00
        Assertions.assertThat(engine.evaluate(slots(OffsetTime.of(10, 30, 0, 0, ZoneOffset.ofHours(2))))).containsExactly("not-local-eight");
        // some element passes the inner test, so the not(...) fails
        Assertions.assertThat(engine.evaluate(slots(EIGHT_UTC, LocalTime.of(8, 0)))).containsExactly("eight-utc");
        // a time without a zone takes the literal's offset: 09:00 equals 09:00+01:00, and is before 12:00Z
        Assertions.assertThat(engine.evaluate(slots(LocalTime.of(9, 0)))).containsExactly("eight-utc", "not-local-eight");
        Assertions.assertThat(engine.evaluate(slots(LocalTime.of(9, 0), OffsetTime.of(13, 0, 0, 0, ZoneOffset.UTC))))
                .containsExactly("eight-utc", "not-local-eight", "afternoon");
        Assertions.assertThat(engine.evaluate(slots(LocalTime.of(12, 0)))).containsExactly("not-local-eight", "afternoon");
    }

    @Test
    void map_based_lists_read_times_with_a_zone() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .inputs(PraeceptaInput::new)
                .listColumnType("slots", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(SLOT_RULES)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("slots", "[time(\"10:00:00+02:00\"), 13:00:00Z]")))
                .containsExactly("eight-utc", "not-local-eight", "afternoon");
        Assertions.assertThat(engine.evaluate(Map.of("slots", "[09:00:00]"))).containsExactly("eight-utc", "not-local-eight");
        Assertions.assertThat(engine.evaluate(Map.of("slots", "[08:00:00-03:00]"))).isEmpty();
    }

    record Visit(OffsetTime start, List<Object> openHours) {
    }

    @Test
    void a_value_with_a_zone_is_in_a_named_list_if_some_element_equals_it_as_feel_compares_them() {
        //   Start               → id
        // 1 Open Hours             open
        // 2 not(Open Hours)        closed
        var table = new DecisionTable("visits", HitPolicy.COLLECT, null,
                List.of(new DecisionTable.InputColumn("Start", ColumnType.TIME)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
                List.of(rule(1, Map.of("Start", "Open Hours"), "open"), rule(2, Map.of("Start", "not(Open Hours)"), "closed")),
                true, Set.of("Open Hours"));
        var engine = PraeceptaEngine.<Visit, String>builder()
                .table(table)
                .offsetTimeInput("Start", Visit::start)
                .listInput("Open Hours", ColumnType.TIME, Visit::openHours)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(new Visit(EIGHT_UTC, List.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1))))))
                .containsExactly("open");
        Assertions.assertThat(engine.evaluate(new Visit(EIGHT_UTC, List.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.UTC)))))
                .containsExactly("closed");
        // 08:00 without a zone takes the offset of 08:00Z, so they are equal; 09:00 is not
        Assertions.assertThat(engine.evaluate(new Visit(EIGHT_UTC, List.of(LocalTime.of(8, 0))))).containsExactly("open");
        Assertions.assertThat(engine.evaluate(new Visit(EIGHT_UTC, List.of(LocalTime.of(9, 0))))).containsExactly("closed");
        Assertions.assertThat(engine.evaluate(new Visit(EIGHT_UTC, List.of(LocalTime.of(8, 0), EIGHT_UTC)))).containsExactly("open");
    }

    record Basket(List<String> codes) {
    }

    @Test
    void list_columns_match_alike_whether_their_element_masks_are_dense_or_sparse() {
        // 300 rules, five words: the mask of one code holds one word, so AUTO stores it sparse
        var rules = new ArrayList<Praeceptum>();
        for (int r = 1; r <= 297; r++) {
            rules.add(rule(r, Map.of("codes", "\"k" + r + "\""), "k" + r));
        }
        rules.add(rule(298, Map.of("codes", "not(\"k3\")"), "without-k3"));
        rules.add(rule(299, Map.of("codes", "null"), "missing"));
        rules.add(rule(300, Map.of(), "any"));
        for (var form : Masks.Form.values()) {
            var engine = PraeceptaEngine.<Basket, String>builder()
                    .hitPolicy(HitPolicy.COLLECT)
                    .listInput("codes", Basket::codes)
                    .output(output -> output.values().get("id"))
                    .rules(rules)
                    .maskForm(form)
                    .build();
            Assertions.assertThat(engine.evaluate(new Basket(List.of("k3", "k250")))).as("%s", form).containsExactly("k3", "k250", "any");
            Assertions.assertThat(engine.evaluate(new Basket(List.of("k70", "x")))).as("%s", form).containsExactly("k70", "without-k3", "any");
            Assertions.assertThat(engine.evaluate(new Basket(List.of()))).as("%s", form).containsExactly("without-k3", "any");
            Assertions.assertThat(engine.evaluate(new Basket(null))).as("%s", form).containsExactly("missing", "any");
        }
    }

    record Booking(List<String> nationalities, OffsetTime at, BigDecimal price) {
    }

    @Test
    void output_expressions_compute_from_single_inputs_next_to_list_and_zoned_columns() {
        var engine = PraeceptaEngine.<Booking, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .listInput("nationalities", Booking::nationalities)
                .offsetTimeInput("at", Booking::at)
                .numberInput("price", Booking::price)
                .output(output -> output.values().get("total"))
                .rules(List.of(
                        expressionRule(1, Map.of("nationalities", "\"PT\"", "at", "< time(\"12:00:00Z\")"), "price * 0.9"),
                        expressionRule(2, Map.of(), "price")))
                .build();
        var morning = OffsetTime.of(11, 0, 0, 0, ZoneOffset.ofHours(-1)); // 12:00Z
        Assertions.assertThat(engine.evaluate(new Booking(List.of("BR", "PT"), EIGHT_UTC, new BigDecimal(20)))).containsExactly("18");
        Assertions.assertThat(engine.evaluate(new Booking(List.of("BR", "PT"), morning, new BigDecimal(20)))).containsExactly("20");
    }

    @Test
    void output_expressions_cannot_read_a_list_or_a_time() {
        var list = PraeceptaEngine.<Booking, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .listInput("nationalities", Booking::nationalities)
                .output(output -> output.values().get("total"))
                .rules(List.of(expressionRule(1, Map.of(), "nationalities + \"!\"")));
        Assertions.assertThatThrownBy(list::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("'nationalities', which holds lists");
        var time = PraeceptaEngine.<Booking, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .offsetTimeInput("at", Booking::at)
                .output(output -> output.values().get("total"))
                .rules(List.of(expressionRule(1, Map.of(), "at + 1")));
        Assertions.assertThatThrownBy(time::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, output 'total': 'at' is a time; output expressions combine strings and numbers");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Slots slots(Object... times) {
        return new Slots(List.of(times));
    }

    private static Praeceptum rule(int id, Map<String, String> cells, String output) {
        return new Praeceptum(id, new PraeceptaInput(new HashMap<>(cells)), new PraeceptaOutput(Map.of("id", output)));
    }

    private static Praeceptum expressionRule(int id, Map<String, String> cells, String expression) {
        return new Praeceptum(id, new PraeceptaInput(new HashMap<>(cells)), new PraeceptaOutput(Map.of(), Map.of(), Map.of("total", expression)));
    }
}
