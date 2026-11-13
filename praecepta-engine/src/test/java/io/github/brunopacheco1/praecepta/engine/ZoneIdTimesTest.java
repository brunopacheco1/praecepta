/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/** Times with a zone id, such as {@code time("10:30:00@Europe/Paris")} (#82). */
class ZoneIdTimesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    record Call(ZonedTime at) {
    }

    @Test
    void two_times_in_the_same_zone_compare_by_local_time() {
        // DMN TCK, 0068-feel-equality: time("10:30:00@Europe/Paris") = time("10:30:00@Europe/Paris") is true
        var engine = PraeceptaEngine.<Call, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .zonedTimeInput("at", Call::at)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "time(\"10:30:00@Europe/Paris\")"), rule(2, "[@\"09:00:00@Europe/Paris\"..time(\"11:00:00@Europe/Paris\"))"),
                        rule(3, "> time(\"10:00:00@Asia/Dhaka\")"), rule(4, "not(time(\"10:30:00@Asia/Dhaka\"))"), rule(5, "!= null")))
                .build();
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(10, 30), PARIS)))).containsExactly("1", "2", "5");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(10, 30), ZoneId.of("Asia/Dhaka"))))).containsExactly("3", "5");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(9, 0), ZoneId.of("Asia/Dhaka"))))).containsExactly("4", "5");
        Assertions.assertThat(engine.evaluate(new Call(null))).containsExactly("4");
    }

    @Test
    void the_comparisons_dmn_leaves_open_are_unknown() {
        // the TCK's cases with a time in another zone, at an offset or without a zone are "pending RTF equality comparisons
        // with zones clarification"; FEEL gives null for a comparison it does not define, so no test and no not(...) holds
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "time(\"10:30:00@Europe/Paris\")"), rule(2, "not(time(\"10:30:00@Europe/Paris\"))"),
                        rule(3, "time(\"10:30:00\")"), rule(4, "!= time(\"10:30:00+02:00\")"), rule(5, "-")))
                .build();
        Assertions.assertThat(engine.evaluate(input("10:30:00@Europe/Paris"))).containsExactly("1", "5");
        Assertions.assertThat(engine.evaluate(input("10:30:00@Asia/Dhaka"))).containsExactly("5");
        // a time without a zone and one at an offset compare by local time (DMN 1.5, Table 54)
        Assertions.assertThat(engine.evaluate(input("10:30:00"))).containsExactly("3", "5");
        Assertions.assertThat(engine.evaluate(input("11:00:00+02:00"))).containsExactly("4", "5");
        Assertions.assertThat(engine.evaluate(input(null))).containsExactly("2", "4", "5");
    }

    @Test
    void a_column_whose_cells_name_no_zone_id_takes_such_a_time_from_text() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "< time(\"12:00:00\")"), rule(2, ">= time(\"12:00:00\")"), rule(3, "-")))
                .build();
        Assertions.assertThat(engine.evaluate(input("11:00@Europe/Paris"))).containsExactly("3");
        // the analysis keeps the classes it had: no gap among times with a zone id
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).isEmpty();
        var typed = PraeceptaEngine.<Map<String, Object>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .timeInput("at", input -> (LocalTime) input.get("at"))
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "-")))
                .build();
        Map<String, Object> zoneId = Map.of("at", new ZonedTime(LocalTime.NOON, PARIS));
        Assertions.assertThatThrownBy(() -> typed.evaluate(zoneId)).isInstanceOf(ClassCastException.class);
    }

    @Test
    void gaps_are_found_among_the_times_of_a_zone_the_cells_name() {
        var engine = PraeceptaEngine.<Call, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .zonedTimeInput("at", Call::at)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "< time(\"12:00:00@Europe/Paris\")"), rule(2, "<= time(\"12:00:00@Europe/Paris\")")))
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.OVERLAPPING_RULES)).singleElement()
                .satisfies(issue -> Assertions.assertThat(issue.ruleIds()).containsExactly(1, 2));
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).extracting(ValidationReport.Issue::detail)
                // a time in a zone with one fixed offset (Etc/UTC) is a time at that offset (DMN 1.5, Table 72), which the getter
                // may give, and which no cell compares with
                .containsExactly("at in {any time with a time zone, (time(\"12:00:00@Europe/Paris\")..+inf), "
                        + "any time with a zone id no cell names}");
    }

    @Test
    void declared_input_values_with_a_zone_id_reject_other_times() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.TIME)
                .allowedValues("at", "[time(\"08:00:00@Europe/Paris\")..time(\"18:00:00@Europe/Paris\")]")
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "-")))
                .build();
        Assertions.assertThat(engine.evaluate(input("09:00@Europe/Paris"))).containsExactly("1");
        for (var outside : List.of(input("19:00@Europe/Paris"), input("09:00@Asia/Dhaka"), input("09:00"))) {
            Assertions.assertThatThrownBy(() -> engine.evaluate(outside)).isInstanceOf(InvalidInputException.class);
        }
    }

    @Test
    void lists_and_cells_that_name_inputs_compare_the_same_way() {
        var lists = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .listColumnType("slots", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("slots", "time(\"10:00:00@Europe/Paris\")")),
                        new PraeceptaOutput(Map.of("id", "1")))))
                .build();
        Assertions.assertThat(lists.evaluate(Map.of("slots", "[time(\"10:00:00@Asia/Dhaka\"), time(\"10:00:00@Europe/Paris\")]")))
                .containsExactly("1");
        Assertions.assertThat(lists.evaluate(Map.of("slots", "[time(\"10:00:00@Asia/Dhaka\"), time(\"10:00:00\")]"))).isEmpty();
        var named = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table(true, "[opens..closes]"))
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(named.evaluate(Map.of("at", "10:00@Europe/Paris", "opens", "09:00@Europe/Paris", "closes", "11:00@Europe/Paris")))
                .containsExactly("1");
        Assertions.assertThat(named.evaluate(Map.of("at", "10:00@Europe/Paris", "opens", "09:00@Europe/Paris", "closes", "11:00"))).isEmpty();
        Assertions.assertThat(named.evaluate(Map.of("at", "12:00@Europe/Paris", "opens", "09:00@Europe/Paris", "closes", "11:00"))).isEmpty();
    }

    @Test
    void dmn_writes_times_with_a_zone_id_and_reads_them_back() {
        var xml = DmnWriter.write(table(false, "[@\"09:00:00@Europe/Paris\"..time(\"17:00:00@Europe/Paris\")), != time(\"12:00:00@Asia/Dhaka\")"));
        Assertions.assertThat(xml).contains(
                "<text>[time(\"09:00:00@Europe/Paris\")..time(\"17:00:00@Europe/Paris\")), != time(\"12:00:00@Asia/Dhaka\")</text>");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(read)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(input("16:59@Europe/Paris"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("12:00@Asia/Dhaka"))).isEmpty();
        Assertions.assertThat(engine.evaluate(input("13:00@Asia/Dhaka"))).containsExactly("1");
    }

    private static DecisionTable table(boolean feel, String cell) {
        return new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("at", ColumnType.TIME, null)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING, List.of())), List.of(rule(1, cell)), feel, Set.of());
    }

    private static Map<String, String> input(String at) {
        var input = new HashMap<String, String>();
        input.put("at", at);
        return input;
    }

    private static Praeceptum rule(int id, String cell) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("at", cell)), new PraeceptaOutput(Map.of("id", String.valueOf(id))));
    }
}
