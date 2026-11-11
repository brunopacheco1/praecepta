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

/**
 * Times and date-times with a time zone (#70): two of them compare by instant, and one compares with a value
 * without a zone by local time, since that value takes its offset (DMN 1.5, Table 54).
 */
class ZonedTimesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    record Delivery(OffsetTime cutOff, ZonedDateTime at) {
    }

    //   cutOff                        at                                                  → slot
    // 1 < time("12:00:00+01:00")      -                                                    morning
    // 2 >= time("11:00:00Z")          < date and time("2025-12-24T00:00:00@Europe/Paris")  afternoon
    // 3 -                             >= date and time("2025-12-23T23:00:00Z")             holiday
    // 4 time("12:00:00")              -                                                    noon on the clock, at any offset
    private static final List<Praeceptum> DELIVERIES = List.of(
            delivery(1, "< time(\"12:00:00+01:00\")", "-", "morning"),
            delivery(2, ">= time(\"11:00:00Z\")", "< date and time(\"2025-12-24T00:00:00@Europe/Paris\")", "afternoon"),
            delivery(3, "-", ">= date and time(\"2025-12-23T23:00:00Z\")", "holiday"),
            delivery(4, "time(\"12:00:00\")", "-", "noon"));

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
    void a_cell_without_a_zone_is_compared_with_a_zoned_input_by_local_time() {
        var rules = List.of(delivery(1, "not(time(\"12:00:00\"))", "-", "not noon"), delivery(2, "time(\"12:00:00\"), null", "-", "noon or none"),
                delivery(3, "-", "< date and time(\"2025-12-24T00:00:00\")", "before the 24th"));
        var engine = deliveries(HitPolicy.COLLECT, rules);
        // the literal takes the input's offset (DMN 1.5, Table 54), so only the input's local time counts
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(12, 0, 0, 0, ZoneOffset.UTC), null))).containsExactly("noon or none");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(5)), null)))
                .containsExactly("noon or none");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(13, 0, 0, 0, ZoneOffset.ofHours(1)), null)))
                .as("12:00Z, but 13:00 on the clock").containsExactly("not noon");
        Assertions.assertThat(engine.evaluate(new Delivery(null, null))).containsExactly("not noon", "noon or none");
        // 23:30 on the 23rd in New York is 04:30Z on the 24th, and still before the 24th there
        var lateInNewYork = ZonedDateTime.of(2025, 12, 23, 23, 30, 0, 0, ZoneId.of("America/New_York"));
        Assertions.assertThat(engine.evaluate(new Delivery(null, lateInNewYork))).containsExactly("not noon", "noon or none", "before the 24th");
        Assertions.assertThat(engine.evaluate(new Delivery(null, lateInNewYork.withZoneSameInstant(ZoneOffset.UTC))))
                .containsExactly("not noon", "noon or none");
    }

    @Test
    void a_cell_with_a_zone_is_compared_with_an_input_without_one_by_local_time() {
        var engine = PraeceptaEngine.<LocalTime, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .timeInput("cutOff", time -> time)
                .output(output -> output.values().get("slot"))
                .rules(List.of(delivery(1, "< time(\"12:00:00+05:00\")", "-", "morning"), delivery(2, "time(\"12:00:00Z\")", "-", "noon"),
                        delivery(3, "not(time(\"12:00:00-03:00\"))", "-", "not noon")))
                .build();
        Assertions.assertThat(engine.evaluate(LocalTime.of(11, 59))).containsExactly("morning", "not noon");
        Assertions.assertThat(engine.evaluate(LocalTime.NOON)).containsExactly("noon");
        // nothing lies between the cells: the analysis sees the same local times
        Assertions.assertThat(PraeceptaEngine.<LocalTime, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .timeInput("cutOff", time -> time)
                .output(output -> output.values().get("slot"))
                .rules(List.of(delivery(1, "< time(\"12:00:00+05:00\")", "-", "morning"), delivery(2, ">= time(\"12:00:00Z\")", "-", "rest")))
                .build()
                .validationReport().issues(Kind.GAP)).isEmpty();
    }

    @Test
    void a_column_with_cells_of_both_kinds_looks_at_the_instant_and_the_local_time_of_a_zoned_input() {
        //   cutOff
        // 1 < time("12:00:00")                         before noon on the clock
        // 2 >= time("11:00:00Z")                       from 11:00 at offset zero
        // 3 [time("09:00:00")..time("10:00:00Z")]      from 09:00 on the clock to 10:00 at offset zero
        // 4 not(time("08:00:00"), time("11:00:00Z"))   neither
        var engine = deliveries(HitPolicy.COLLECT, List.of(delivery(1, "< time(\"12:00:00\")", "-", "1"),
                delivery(2, ">= time(\"11:00:00Z\")", "-", "2"), delivery(3, "[time(\"09:00:00\")..time(\"10:00:00Z\")]", "-", "3"),
                delivery(4, "not(time(\"08:00:00\"), time(\"11:00:00Z\"))", "-", "4")));
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(11, 30, 0, 0, ZoneOffset.ofHours(1)), null)))
                .as("10:30Z").containsExactly("1", "4");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(13, 0, 0, 0, ZoneOffset.ofHours(2)), null)))
                .as("11:00Z").containsExactly("2");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(9, 30, 0, 0, ZoneOffset.UTC), null))).containsExactly("1", "3", "4");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(-4)), null)))
                .as("12:00Z, 08:00 on the clock").containsExactly("1", "2");
        Assertions.assertThat(engine.evaluate(new Delivery(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(-1)), null)))
                .as("10:00Z: both ends of the interval").containsExactly("1", "3", "4");
    }

    @Test
    void gaps_among_zoned_values_are_given_by_instant_and_local_time() {
        var engine = deliveries(HitPolicy.FIRST, List.of(delivery(1, "< time(\"12:00:00\")", "-", "before noon"),
                delivery(2, ">= time(\"11:00:00Z\")", "-", "from eleven")));
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail())
                    .isEqualTo("cutOff in (-inf..time(\"11:00:00Z\")) at local time [time(\"12:00:00\")..+inf)");
            // noon or later on the clock and before 11:00 at offset zero: at least an hour ahead of it
            var cutOff = (OffsetTime) gap.example().get("cutOff");
            Assertions.assertThat(cutOff.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
            Assertions.assertThat(cutOff.toLocalTime()).isAfterOrEqualTo(LocalTime.NOON);
            Assertions.assertThat(engine.evaluate(new Delivery(cutOff, null))).isEmpty();
        });
        // a time before 05:00 on the clock is at most 18 hours behind offset zero, so it is not after 23:00 there: no gap
        var covered = deliveries(HitPolicy.FIRST, List.of(delivery(1, ">= time(\"05:00:00\")", "-", "day"),
                delivery(2, "<= time(\"23:00:00Z\")", "-", "not too late")));
        Assertions.assertThat(covered.validationReport().issues(Kind.GAP)).isEmpty();
        // a second earlier, the times just before 05:00 at -18:00 are left
        var uncovered = deliveries(HitPolicy.FIRST, List.of(delivery(1, ">= time(\"05:00:00\")", "-", "day"),
                delivery(2, "<= time(\"22:59:59Z\")", "-", "not too late")));
        Assertions.assertThat(uncovered.validationReport().issues(Kind.GAP)).singleElement().satisfies(gap -> {
            var cutOff = (OffsetTime) gap.example().get("cutOff");
            Assertions.assertThat(cutOff.getOffset()).isEqualTo(ZoneOffset.MIN);
            Assertions.assertThat(cutOff.toLocalTime()).isBetween(LocalTime.of(4, 59, 59), LocalTime.of(5, 0));
            Assertions.assertThat(uncovered.evaluate(new Delivery(cutOff, null))).isEmpty();
        });
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
    void map_inputs_take_both_kinds_and_compare_them_with_the_cells_by_local_time() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("cutOff", ColumnType.TIME)
                .output(output -> output.values().get("slot"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< time(\"12:00:00\")")),
                        new PraeceptaOutput(Map.of("slot", "morning")))))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00"))).containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00Z"))).containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00-08:00"))).as("19:00Z, but 11:00 on the clock").containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "13:00+02:00"))).as("11:00Z, but 13:00 on the clock").isEmpty();
        // the zone makes no difference to cells without one, so the gap is given once, by local time
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).extracting(ValidationReport.Issue::detail)
                .containsExactly("cutOff in [time(\"12:00:00\")..+inf)");
    }

    @Test
    void map_inputs_with_cells_of_one_kind_report_gaps_among_times_of_both_kinds() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("cutOff", ColumnType.TIME)
                .output(output -> output.values().get("slot"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< time(\"12:00:00Z\")")),
                        new PraeceptaOutput(Map.of("slot", "morning")))))
                .build();
        // a time without a zone is compared by local time, one with a zone by instant
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00"))).containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "13:00+02:00"))).containsExactly("morning");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "11:00-08:00"))).isEmpty();
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).extracting(ValidationReport.Issue::detail)
                .containsExactly("cutOff in {[time(\"12:00:00\")..+inf), [time(\"12:00:00Z\")..+inf)}");
    }

    @Test
    void declared_input_values_without_a_zone_admit_zoned_inputs_by_local_time() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .inputs(PraeceptaInput::new)
                .columnType("cutOff", ColumnType.TIME)
                .allowedValues("cutOff", "[time(\"08:00:00\")..time(\"18:00:00\")]")
                .output(output -> output.values().get("slot"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("cutOff", "< time(\"12:00:00\")")),
                                new PraeceptaOutput(Map.of("slot", "morning"))),
                        new Praeceptum(2, new PraeceptaInput(Map.of("cutOff", ">= time(\"12:00:00\")")),
                                new PraeceptaOutput(Map.of("slot", "afternoon")))))
                .build();
        Assertions.assertThat(engine.validationReport().isEmpty()).as("%s", engine.validationReport()).isTrue();
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "13:00"))).containsExactly("afternoon");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "13:00Z"))).containsExactly("afternoon");
        Assertions.assertThat(engine.evaluate(Map.of("cutOff", "09:00+09:00"))).as("00:00Z, but 09:00 on the clock").containsExactly("morning");
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("cutOff", "19:00"))).isInstanceOf(InvalidInputException.class);
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("cutOff", "19:00+09:00"))).as("10:00Z, but 19:00 on the clock")
                .isInstanceOf(InvalidInputException.class);
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
    void cells_naming_a_zoned_input_compare_by_instant_and_across_kinds_by_local_time() {
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
        // across kinds, the value without a zone takes the other's offset: they compare by local time
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00", "2018-07-30T16:12:00Z")).containsExactly("same", "within");
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00Z", "2018-07-30T16:12:00")).containsExactly("same", "within");
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00+09:00", "2018-07-30T16:12:00")).containsExactly("same", "within");
        Assertions.assertThat(result(engine, "2018-07-30T16:12:00", "2018-07-30T18:12:00@Europe/Paris"))
                .as("16:12Z in Paris is 18:12 on the clock").containsExactly("earlier", "different");
        Assertions.assertThat(result(engine, "2018-07-30T18:12:00@Europe/Paris", "2018-07-30T16:12:00")).containsExactly("later", "different");
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
