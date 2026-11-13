/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * A time in a zone with one fixed offset ({@code Etc/UTC}, {@code Etc/GMT-2}) is the time at that offset: DMN 1.5's
 * example of {@code time(from)} (Table 72) has {@code time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC")}
 * true (#82).
 */
class FixedOffsetZoneTimesTest {

    private static final ZoneId UTC = ZoneId.of("Etc/UTC");
    private static final ZoneId PLUS_TWO = ZoneId.of("Etc/GMT-2"); // POSIX signs: Etc/GMT-2 is +02:00
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    record Call(ZonedTime at) {
    }

    record Slots(List<?> slots) {
    }

    //   at                                                          08:00Z    08:00 (local)  08:30@Europe/Paris
    // 1 time("10:00:00@Etc/GMT-2")                                  yes       no             no
    // 2 < time("09:00:00Z")                                         yes       yes            no
    // 3 [time("07:00:00@Etc/UTC")..time("10:30:00+02:00")]          yes       yes            no
    // 4 not(time("08:00:00@Etc/UTC"))                               no        no             no (unknown)
    // 5 time("08:30:00@Europe/Paris")                               no        no             yes
    // 6 != time("09:00:00@Etc/GMT-2"), which is 07:00Z              yes       yes            no (unknown)
    private static final List<Praeceptum> RULES = List.of(rule(1, "time(\"10:00:00@Etc/GMT-2\")"), rule(2, "< time(\"09:00:00Z\")"),
            rule(3, "[time(\"07:00:00@Etc/UTC\")..time(\"10:30:00+02:00\")]"), rule(4, "not(time(\"08:00:00@Etc/UTC\"))"),
            rule(5, "time(\"08:30:00@Europe/Paris\")"), rule(6, "!= time(\"09:00:00@Etc/GMT-2\")"));

    @Test
    void the_example_of_table_72_holds_in_cells_both_ways() {
        // time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC")
        var sum = OffsetTime.of(23, 59, 0, 0, ZoneOffset.UTC).plus(Duration.ofMinutes(2));
        var atOffset = PraeceptaEngine.<OffsetTime, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .offsetTimeInput("at", time -> time)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "time(\"00:01:00@Etc/UTC\")"), rule(2, "@\"00:01:00@Etc/UTC\""), rule(3, "time(\"00:01:00z\")"),
                        rule(4, "not(time(\"00:01:00@Etc/UTC\"))")))
                .build();
        Assertions.assertThat(atOffset.evaluate(sum)).containsExactly("1", "2", "3");
        // and the other way: a cell at the offset holds for the time in the zone, given as text or by a getter
        var fromText = mapEngine(HitPolicy.RULE_ORDER, List.of(rule(1, "time(\"00:01:00z\")"), rule(2, "time(\"00:01:00@Etc/UTC\")"),
                rule(3, "> time(\"00:00:59Z\")"), rule(4, "not(time(\"00:01:00z\"))")));
        Assertions.assertThat(fromText.evaluate(input("00:01:00@Etc/UTC"))).containsExactly("1", "2", "3");
        Assertions.assertThat(fromText.evaluate(input("00:01:00z"))).containsExactly("1", "2", "3");
        Assertions.assertThat(fromText.evaluate(input("00:02:00@Etc/UTC"))).containsExactly("3", "4");
        var bound = PraeceptaEngine.<Call, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .zonedTimeInput("at", Call::at)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "time(\"00:01:00z\")"), rule(2, "time(\"00:01:00@Etc/UTC\")"), rule(3, "> time(\"00:00:59Z\")"),
                        rule(4, "not(time(\"00:01:00z\"))")))
                .build();
        Assertions.assertThat(bound.evaluate(new Call(new ZonedTime(LocalTime.of(0, 1), UTC)))).containsExactly("1", "2", "3");
        Assertions.assertThat(bound.evaluate(new Call(new ZonedTime(LocalTime.of(2, 1), PLUS_TWO)))).containsExactly("1", "2", "3");
    }

    @Test
    void the_example_of_table_72_holds_in_output_expressions_both_ways() {
        // the difference of two equal times is zero
        Assertions.assertThat(evaluate("time(\"23:59:00z\") + duration(\"PT2M\") - time(\"00:01:00@Etc/UTC\")")).isEqualTo(Duration.ZERO);
        Assertions.assertThat(evaluate("time(\"23:59:00@Etc/UTC\") + duration(\"PT2M\") - time(\"00:01:00z\")")).isEqualTo(Duration.ZERO);
        Assertions.assertThat(evaluate("time(\"00:01:00@Etc/UTC\") - (time(\"23:59:00z\") + duration(\"PT2M\"))")).isEqualTo(Duration.ZERO);
        Assertions.assertThat(evaluate("time(\"23:59:00@Etc/UTC\") + duration(\"PT2M\")")).isEqualTo(OffsetTime.of(0, 1, 0, 0, ZoneOffset.UTC));
        Assertions.assertThat(evaluate("time(date and time(\"2025-01-01T00:01:00@Etc/UTC\"))")).isEqualTo(OffsetTime.of(0, 1, 0, 0, ZoneOffset.UTC));
        // as an output of a rule, which a table writes at the offset
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.TIME)
                .output(PraeceptaOutput::values)
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("at", "time(\"23:59:00z\")")),
                        new PraeceptaOutput(Map.of(), Map.of(), Map.of("next", "at + duration(\"PT2M\")", "gap",
                                "at + duration(\"PT2M\") - time(\"00:01:00@Etc/UTC\")")))))
                .build();
        Assertions.assertThat(engine.evaluate(input("23:59:00@Etc/UTC"))).containsExactly(Map.of("next", "00:01:00Z", "gap", "PT0S"));
    }

    @Test
    void such_times_subtract_as_times_at_their_offset() {
        Assertions.assertThat(evaluate("time(\"10:00:00@Etc/GMT-2\") - time(\"07:00:00Z\")")).isEqualTo(Duration.ofHours(1));
        Assertions.assertThat(evaluate("time(\"07:00:00Z\") - time(\"10:00:00@Etc/GMT-2\")")).isEqualTo(Duration.ofHours(-1));
        Assertions.assertThat(evaluate("time(\"10:00:00@Etc/GMT-2\") - time(\"09:00:00@Etc/UTC\")")).isEqualTo(Duration.ofHours(-1));
        // with a time without a zone, by local time (Table 57), and with a zone whose offset changes, not at all
        Assertions.assertThat(evaluate("time(\"10:00:00@Etc/GMT-2\") - time(\"09:00:00\")")).isEqualTo(Duration.ofHours(1));
        Assertions.assertThat(evaluate("time(\"10:00:00@Etc/GMT-2\") - time(\"09:00:00@Europe/Paris\")")).isNull();
        // a bound time in such a zone, in an output expression
        var engine = PraeceptaEngine.<Call, Map<String, String>>builder()
                .hitPolicy(HitPolicy.FIRST)
                .zonedTimeInput("at", Call::at)
                .output(PraeceptaOutput::values)
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("at", "-")),
                        new PraeceptaOutput(Map.of(), Map.of("echo", "at"), Map.of("late", "at - time(\"07:00:00Z\")")))))
                .build();
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(10, 0), PLUS_TWO))))
                .containsExactly(Map.of("echo", "10:00:00+02:00", "late", "PT1H"));
        var paris = new HashMap<String, String>();
        paris.put("echo", "10:00:00@Europe/Paris");
        paris.put("late", null);
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(10, 0), PARIS)))).containsExactly(paris);
    }

    @Test
    void equality_order_ranges_and_not_treat_such_a_time_as_the_time_at_its_offset() {
        var engine = mapEngine(HitPolicy.RULE_ORDER, RULES);
        for (var at : List.of("08:00:00Z", "10:00:00+02:00", "08:00:00@Etc/UTC", "10:00@Etc/GMT-2", "07:00:00@Etc/GMT+1", "08:00@UTC")) {
            Assertions.assertThat(engine.evaluate(input(at))).as(at).containsExactly("1", "2", "3", "6");
        }
        Assertions.assertThat(engine.evaluate(input("08:30:00@Etc/UTC"))).containsExactly("2", "3", "4", "6");
        Assertions.assertThat(engine.evaluate(input("09:00:00@Etc/UTC"))).containsExactly("4", "6");
        // without a zone, by local time against each literal (Table 54)
        Assertions.assertThat(engine.evaluate(input("08:00:00"))).containsExactly("2", "3", "6");
        // a zone whose offset changes compares with none of them: neither a test nor its not(...) holds
        Assertions.assertThat(engine.evaluate(input("08:30:00@Europe/Paris"))).containsExactly("5");
    }

    @Test
    void a_time_a_getter_gives_in_such_a_zone_matches_as_the_time_at_its_offset() {
        var engine = PraeceptaEngine.<Call, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .zonedTimeInput("at", Call::at)
                .output(output -> output.values().get("id"))
                .rules(RULES)
                .build();
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(8, 0), UTC)))).containsExactly("1", "2", "3", "6");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(10, 0), PLUS_TWO)))).containsExactly("1", "2", "3", "6");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(8, 0), ZoneId.of("UTC"))))).containsExactly("1", "2", "3", "6");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(7, 0), UTC)))).containsExactly("2", "3", "4");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(8, 30), UTC)))).containsExactly("2", "3", "4", "6");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(8, 30), PARIS)))).containsExactly("5");
        Assertions.assertThat(engine.evaluate(new Call(new ZonedTime(LocalTime.of(9, 0), PARIS)))).isEmpty();
        // the getter's times at an offset are among those the analysis looks at: the first two rules cover every one of
        // them, so the gaps are only among times in Europe/Paris and in the zones no cell names
        var single = PraeceptaEngine.<Call, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .zonedTimeInput("at", Call::at)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "< time(\"09:00:00@Etc/UTC\")"), rule(2, ">= time(\"11:00:00@Etc/GMT-2\")"),
                        rule(3, "time(\"08:30:00@Europe/Paris\")")))
                .build();
        Assertions.assertThat(single.validationReport().issues(Kind.GAP)).extracting(ValidationReport.Issue::detail)
                .containsExactly("at in {(-inf..time(\"08:30:00@Europe/Paris\")), (time(\"08:30:00@Europe/Paris\")..+inf), "
                        + "any time with a zone id no cell names}");
        Assertions.assertThat(single.evaluate(new Call(new ZonedTime(LocalTime.of(10, 59), PLUS_TWO)))).containsExactly("1");
        Assertions.assertThat(single.evaluate(new Call(new ZonedTime(LocalTime.of(11, 0), PLUS_TWO)))).containsExactly("2");
    }

    @Test
    void a_list_holds_such_a_time_as_the_time_at_its_offset() {
        var cells = List.of(rule(1, "time(\"00:01:00z\")"), rule(2, "< time(\"02:02:00@Etc/GMT-2\")"), rule(3, "time(\"00:01:00@Europe/Paris\")"));
        var fromText = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .inputs(PraeceptaInput::new)
                .listColumnType("at", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(cells)
                .build();
        Assertions.assertThat(fromText.evaluate(input("[time(\"00:01:00@Etc/UTC\")]"))).containsExactly("1", "2");
        Assertions.assertThat(fromText.evaluate(input("[@\"02:01:00@Etc/GMT-2\", time(\"00:02:00@Europe/Paris\")]"))).containsExactly("1", "2");
        var bound = PraeceptaEngine.<Slots, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .listInput("at", ColumnType.TIME, Slots::slots)
                .output(output -> output.values().get("id"))
                .rules(cells)
                .build();
        Assertions.assertThat(bound.evaluate(new Slots(List.of(new ZonedTime(LocalTime.of(0, 1), UTC))))).containsExactly("1", "2");
        Assertions.assertThat(bound.evaluate(new Slots(List.of(new ZonedTime(LocalTime.of(2, 1), PLUS_TWO),
                new ZonedTime(LocalTime.of(0, 1), PARIS))))).containsExactly("1", "2", "3");
        Assertions.assertThat(bound.evaluate(new Slots(List.of(new ZonedTime(LocalTime.of(0, 2), PARIS))))).isEmpty();
    }

    @Test
    void dmn_writes_such_a_time_at_its_offset_and_reads_it_back_equal() {
        var table = new DecisionTable("t", HitPolicy.RULE_ORDER, null, List.of(new DecisionTable.InputColumn("at", ColumnType.TIME, null)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING, List.of())), RULES, false, Set.of());
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml).contains("time(\"10:00:00+02:00\")", "[time(\"07:00:00Z\")..time(\"10:30:00+02:00\")]",
                "time(\"08:30:00@Europe/Paris\")");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var written = mapEngine(HitPolicy.RULE_ORDER, RULES);
        var readBack = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(read)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        for (var at : List.of("08:00:00Z", "08:00:00@Etc/UTC", "08:30:00@Etc/UTC", "09:00:00@Etc/UTC", "08:00:00", "08:30:00@Europe/Paris")) {
            Assertions.assertThat(readBack.evaluate(input(at))).as(at).isEqualTo(written.evaluate(input(at)));
        }
    }

    @Test
    void a_date_and_time_in_such_a_zone_equals_the_one_at_its_offset() {
        // DMN TCK, 0068-feel-equality: date and time("2018-12-08T00:00:00+00:00") = date and time("2018-12-08T00:00:00@Etc/UTC")
        var atOffset = FeelTemporals.parseDateTime("2018-12-08T00:00:00+00:00");
        var inZone = FeelTemporals.parseDateTime("2018-12-08T00:00:00@Etc/UTC");
        Assertions.assertThat(FeelTemporals.compare(atOffset, inZone)).isZero();
        Assertions.assertThat(evaluate("date and time(\"2018-12-08T00:00:00+00:00\") - date and time(\"2018-12-08T00:00:00@Etc/UTC\")"))
                .isEqualTo(Duration.ZERO);
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.DATE_TIME)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "date and time(\"2018-12-08T00:00:00@Etc/UTC\")"), rule(2, "date and time(\"2018-12-08T00:00:00+00:00\")"),
                        rule(3, "not(date and time(\"2018-12-08T00:00:00@Etc/UTC\"))")))
                .build();
        Assertions.assertThat(engine.evaluate(input("2018-12-08T00:00:00+00:00"))).containsExactly("1", "2");
        Assertions.assertThat(engine.evaluate(input("2018-12-08T00:00:00@Etc/UTC"))).containsExactly("1", "2");
        Assertions.assertThat(engine.evaluate(input("2018-12-08T02:00:00@Etc/GMT-2"))).containsExactly("1", "2");
        Assertions.assertThat(engine.evaluate(input("2018-12-08T00:00:01Z"))).containsExactly("3");
    }

    private static Object evaluate(String expression) {
        return OutputExpression.parse(expression).evaluate(name -> null);
    }

    private static PraeceptaEngine<Map<String, String>, String> mapEngine(HitPolicy policy, List<Praeceptum> rules) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .inputs(PraeceptaInput::new)
                .columnType("at", ColumnType.TIME)
                .output(output -> output.values().get("id"))
                .rules(rules)
                .build();
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
