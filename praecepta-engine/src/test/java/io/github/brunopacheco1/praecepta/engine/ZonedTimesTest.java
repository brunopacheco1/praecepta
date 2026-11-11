/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

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
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;

/** Times and date-times with a time zone (#70): compared by instant, and never with values without a zone. */
class ZonedTimesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    record Delivery(OffsetTime cutOff, ZonedDateTime at) {
    }

    //   cutOff                        at                                                  → slot
    // 1 < time("12:00:00+01:00")      -                                                    morning
    // 2 >= time("11:00:00Z")          < date and time("2025-12-24T00:00:00@Europe/Paris")  afternoon
    // 3 -                             >= date and time("2025-12-23T23:00:00Z")             holiday
    // 4 time("12:00:00")              -                                                    never: no time with a zone equals it
    private static final List<Praeceptum> DELIVERIES = List.of(
            delivery(1, "< time(\"12:00:00+01:00\")", "-", "morning"),
            delivery(2, ">= time(\"11:00:00Z\")", "< date and time(\"2025-12-24T00:00:00@Europe/Paris\")", "afternoon"),
            delivery(3, "-", ">= date and time(\"2025-12-23T23:00:00Z\")", "holiday"),
            delivery(4, "time(\"12:00:00\")", "-", "never"));

    @Test
    void zoned_inputs_are_compared_by_instant() {
        var engine = deliveries(HitPolicy.FIRST, DELIVERIES);
        var christmasEve = ZonedDateTime.of(2025, 12, 24, 0, 0, 0, 0, PARIS); // 2025-12-23T23:00Z
        var before = christmasEve.minusNanos(1);
        // 10:59+00:00 is 11:59+01:00, before noon in Paris
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(10, 59, 0, 0, ZoneOffset.UTC), before))).containsExactly("morning");
        // 06:00-05:00 is 11:00Z
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(6, 0, 0, 0, ZoneOffset.ofHours(-5)), before)))
                .containsExactly("afternoon");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC), christmasEve)))
                .containsExactly("holiday");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC),
                christmasEve.withZoneSameInstant(ZoneOffset.UTC)))).as("the same instant in another zone").containsExactly("holiday");
    }

    @Test
    void a_cell_without_a_zone_never_matches_a_zoned_input_not_even_negated() {
        var rules = List.of(delivery(1, "not(time(\"12:00:00\"))", "-", "not noon"), delivery(2, "time(\"12:00:00\"), null", "-", "noon or none"));
        var engine = deliveries(HitPolicy.COLLECT, rules);
        var noonUtc = OffsetTime.of(12, 0, 0, 0, ZoneOffset.UTC);
        // FEEL leaves the comparison undefined: unknown, so neither the test nor its negation holds
        Assertions.assertThat(engine.evaluate(new Delivery(noonUtc, null))).isEmpty();
        Assertions.assertThat(engine.evaluate(new Delivery(null, null))).containsExactly("not noon", "noon or none");
    }

    @Test
    void gaps_are_searched_among_zoned_values_and_their_examples_bind_back() {
        var engine = deliveries(HitPolicy.FIRST, List.of(delivery(1, "< time(\"12:00:00+01:00\")", "-", "morning"),
                delivery(2, "> time(\"12:00:00Z\")", "-", "afternoon")));
        var gaps = engine.validationReport().issues(Kind.GAP);
        Assertions.assertThat(gaps).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail()).isEqualTo("cutOff in [time(\"12:00:00+01:00\")..time(\"12:00:00Z\")]");
            Assertions.assertThat(gap.example()).containsEntry("cutOff", OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(1)));
            var cutOff = (OffsetTime) gap.example().get("cutOff");
            Assertions.assertThat(engine.evaluate(new Delivery(cutOff, null))).isEmpty();
        });
        // bound with a getter of times without a zone, the same table has no gap among them
        Assertions.assertThat(PraeceptaEngine.<LocalTime, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .timeInput("cutOff", time -> time)
                .output(output -> output.values().get("slot"))
                .rules(List.of(delivery(1, "< time(\"12:00:00+01:00\")", "-", "morning"), delivery(2, "-", "-", "rest")))
                .build()
                .validationReport().issues(Kind.GAP)).isEmpty();
    }

    @Test
    void map_inputs_take_both_kinds_and_report_gaps_on_each() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("cutOff", ColumnType.TIME)
                .output(output -> output.values().get("slot"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< time(\"12:00:00\")")),
                        new PraeceptaOutput(Map.of("slot", "morning")))))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00"))).containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00Z"))).isEmpty();
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).extracting(ValidationReport.Issue::detail)
                .containsExactly("cutOff in {[time(\"12:00:00\")..+inf), any time with a time zone}");
    }

    @Test
    void map_inputs_keep_to_times_without_a_zone_when_the_input_values_have_none() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .inputs(PraeceptaInput::new)
                .columnType("cutOff", ColumnType.TIME)
                .allowedValues("cutOff", "[time(\"00:00:00\")..time(\"23:59:59\")]")
                .output(output -> output.values().get("slot"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< time(\"12:00:00\")")),
                                new PraeceptaOutput(Map.of("slot", "morning"))),
                        new Praeceptum(2, new PraeceptaInput(Map.of("cutOff", ">= time(\"12:00:00\")")),
                                new PraeceptaOutput(Map.of("slot", "afternoon")))))
                .build();
        Assertions.assertThat(engine.validationReport().isEmpty()).as("%s", engine.validationReport()).isTrue();
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "13:00"))).containsExactly("afternoon");
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("cutOff", "13:00Z"))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void an_output_naming_a_zoned_input_keeps_its_instant_when_clocks_go_back() {
        // 02:30 happens twice in Paris on 2025-10-26: at +02:00 (00:30Z), then at +01:00 (01:30Z)
        var earlier = ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, PARIS);
        var later = earlier.withLaterOffsetAtOverlap();
        var engine = PraeceptaEngine.<Delivery, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .zonedDateTimeInput("at", Delivery::at)
                .output(output -> output.values().get("due"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of("due", "at")))))
                .build();
        var earlierText = engine.evaluate(new Delivery(null, earlier)).getFirst();
        var laterText = engine.evaluate(new Delivery(null, later)).getFirst();
        Assertions.assertThat(earlierText).isEqualTo("2025-10-26T02:30:00@Europe/Paris");
        Assertions.assertThat(laterText).isEqualTo("2025-10-26T02:30:00+01:00[Europe/Paris]");
        Assertions.assertThat(FeelTemporals.parseZonedDateTime(earlierText)).isEqualTo(earlier);
        Assertions.assertThat(FeelTemporals.parseZonedDateTime(laterText)).isEqualTo(later);
        // FEEL has no literal for it with the zone id: it is written with its offset, which keeps the instant
        Assertions.assertThat(FeelLiterals.literal(later)).isEqualTo("date and time(\"2025-10-26T02:30:00+01:00\")");
    }

    @Test
    void zoned_date_times_beyond_the_keys_range_are_still_compared_by_instant() {
        var engine = deliveries(HitPolicy.FIRST, DELIVERIES);
        var farFuture = ZonedDateTime.of(3000, 1, 1, 0, 0, 0, 0, PARIS);
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC), farFuture))).containsExactly("holiday");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC), farFuture.withYear(1000))))
                .containsExactly("afternoon");
    }

    @Test
    void cells_naming_a_zoned_input_compare_by_instant_and_not_across_kinds() {
        var table = new DecisionTable("compare", HitPolicy.COLLECT, null,
                List.of(new DecisionTable.InputColumn("at", ColumnType.DATE_TIME)),
                List.of(new DecisionTable.OutputColumn("result", ColumnType.STRING)),
                List.of(rule(1, "> other", "later"), rule(2, "other", "same"), rule(3, "< other", "earlier"),
                        rule(4, "not(other)", "different"), rule(5, "[other..other]", "within")), true);
        var engine = PraeceptaEngine.<Map<String, String>, PraeceptaOutput>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output)
                .build();
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00Z", "2018-07-30T18:12:00@Europe/Paris")).containsExactly("same", "within");
        Assertions.assertThat(result(engine, "2018-07-31T17:13:00Z", "2018-07-30T16:12:00Z")).containsExactly("later", "different");
        // undefined across kinds: no test holds, and neither does its negation
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00", "2018-07-30T16:12:00Z")).isEmpty();
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00Z", "2018-07-30T16:12:00")).isEmpty();
    }

    @Test
    void zoned_cells_survive_a_dmn_round_trip() {
        var table = new DecisionTable("deliveries", HitPolicy.FIRST, null,
                List.of(new DecisionTable.InputColumn("cutOff", ColumnType.TIME), new DecisionTable.InputColumn("at", ColumnType.DATE_TIME,
                        "[date and time(\"2025-01-01T00:00:00Z\")..date and time(\"2026-01-01T00:00:00@Europe/Paris\"))")),
                List.of(new DecisionTable.OutputColumn("slot", ColumnType.STRING), new DecisionTable.OutputColumn("due", ColumnType.DATE_TIME)),
                List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< 12:00+01:00", "at", "-")),
                        new PraeceptaOutput(Map.of("slot", "morning", "due", "2025-12-24T09:00:00@Europe/Paris")))));
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml).contains("&lt; time(\"12:00:00+01:00\")", "date and time(\"2026-01-01T00:00:00@Europe/Paris\")",
                "date and time(\"2025-12-24T09:00:00@Europe/Paris\")");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);
        Assertions.assertThat(read.rules().getFirst().output().values()).containsEntry("due", "2025-12-24T09:00:00@Europe/Paris");
        var engine = PraeceptaEngine.<Delivery, PraeceptaOutput>builder()
                .table(read)
                .offsetTimeInput("cutOff", Delivery::cutOff)
                .zonedDateTimeInput("at", Delivery::at)
                .output(output -> output)
                .build();
        var june = ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, PARIS);
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(10, 0, 0, 0, ZoneOffset.UTC), june)))
                .extracting(output -> output.values().get("slot")).containsExactly("morning");
    }

    private static List<String> result(PraeceptaEngine<Map<String, String>, PraeceptaOutput> engine, String at, String other) {
        return engine.evaluate(Map.of("at", at, "other", other)).stream().map(output -> output.values().get("result")).toList();
    }

    private static Praeceptum rule(int id, String at, String result) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("at", at)), new PraeceptaOutput(Map.of("result", result)));
    }

    private static PraeceptaEngine<Delivery, String> deliveries(HitPolicy policy, List<Praeceptum> rules) {
        return PraeceptaEngine.<Delivery, String>builder()
                .hitPolicy(policy)
                .offsetTimeInput("cutOff", Delivery::cutOff)
                .zonedDateTimeInput("at", Delivery::at)
                .output(output -> output.values().get("slot"))
                .rules(rules)
                .build();
    }

    private static Praeceptum delivery(int id, String cutOff, String at, String slot) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("cutOff", cutOff, "at", at)), new PraeceptaOutput(Map.of("slot", slot)));
    }
}
