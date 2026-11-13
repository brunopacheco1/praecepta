/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/** Output entries that compute dates, times and durations (#85), in DMN tables. */
class TemporalOutputsTest {

    private static final String RETURNS = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="returns" name="Returns"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="date"><text>due</text></inputExpression></input>
                <input><inputExpression typeRef="dateTime"><text>sent</text></inputExpression></input>
                <input><inputExpression typeRef="dateTime"><text>returned</text></inputExpression></input>
                <output name="reminder" typeRef="date"/>
                <output name="took" typeRef="dayTimeDuration"/>
                <output name="note" typeRef="string"/>
                <rule>
                  <inputEntry><text>&lt; @"2025-06-01"</text></inputEntry>
                  <inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                  <outputEntry><text>due + duration("P14D")</text></outputEntry>
                  <outputEntry><text>returned - sent</text></outputEntry>
                  <outputEntry><text>due + @"P1M" // a month later</text></outputEntry>
                </rule>
              </decisionTable></decision>
            </definitions>""";

    @Test
    void a_dmn_table_computes_dates_and_durations_in_its_output_entries() {
        // the example of #85: due = date("2025-01-31") gives date("2025-02-14")
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(DmnReader.read(stream(RETURNS)))
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
        var outputs = engine.evaluate(Map.of("due", "2025-01-31", "sent", "2025-01-31T08:00", "returned", "2025-02-01T10:30"));
        Assertions.assertThat(outputs).singleElement().satisfies(output -> Assertions.assertThat(output)
                .containsEntry("reminder", "2025-02-14")
                .containsEntry("took", "P1DT2H30M")
                .containsEntry("note", null)); // 2025-02-31 does not exist: null, as date(2025, 2, 31) is (Table 57)
        var march = new HashMap<String, String>(Map.of("due", "2025-03-01", "sent", "2025-03-01T08:00Z"));
        march.put("returned", "2025-03-02T08:00");
        Assertions.assertThat(engine.evaluate(march)).singleElement().satisfies(output -> Assertions.assertThat(output)
                .containsEntry("reminder", "2025-03-15")
                .containsEntry("took", null) // one with a zone and one without (Table 57)
                .containsEntry("note", "2025-04-01"));
    }

    @Test
    void dmn_writes_temporal_output_entries_back_as_written() {
        var table = DmnReader.read(stream(RETURNS));
        var read = DmnReader.read(stream(DmnWriter.write(table)));
        Assertions.assertThat(read.rules().getFirst().output().expressions())
                .containsEntry("reminder", "due + duration(\"P14D\")")
                .containsEntry("took", "returned - sent");
    }

    @Test
    void an_entry_of_another_type_than_its_output_is_rejected() {
        var dmn = stream(RETURNS.replace("<output name=\"took\" typeRef=\"dayTimeDuration\"/>", "<output name=\"took\" typeRef=\"date\"/>"));
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("'returned - sent' gives a days and time duration, but the output is a date");
        var mixed = stream(RETURNS.replace("returned - sent", "due + sent"));
        Assertions.assertThatThrownBy(() -> DmnReader.read(mixed))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("+ combines a date and a date and time, which gives null in FEEL");
    }

    @Test
    void years_and_months_inputs_bound_as_periods_scale_by_whole_months() {
        record Contract(Period tenure) {
        }
        var engine = PraeceptaEngine.<Contract, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .periodInput("tenure", Contract::tenure)
                .output(output -> output.values().get("half"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of()),
                        new PraeceptaOutput(Map.of(), Map.of(), Map.of("half", "tenure / 2")))))
                .build();
        // 13 months / 2 is 6.5 months: a years and months duration drops the half (TCK 0100-arithmetic)
        Assertions.assertThat(engine.evaluate(new Contract(Period.ofMonths(13)))).containsExactly("P6M");
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
