/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Period;
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
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/** Days and time durations ({@code Duration}) and years and months durations ({@code Period}). */
class DurationColumnsTest {

    record Loan(Duration grace, Period term) {
    }

    //   grace                                  term                          → plan
    // 1 < duration("P1D")                      [duration("P1Y")..duration("P2Y")]   short
    // 2 [duration("P1D")..duration("P2DT12H")) > P2Y                         long
    private static final List<Praeceptum> RULES = List.of(
            rule(1, "< duration(\"P1D\")", "[duration(\"P1Y\")..duration(\"P2Y\")]", "short"),
            rule(2, "[duration(\"P1D\")..duration(\"P2DT12H\"))", "> P2Y", "long"));

    @Test
    void cells_read_durations_as_function_calls_or_iso_text() {
        Assertions.assertThat(CellParser.parse("[duration(\"PT1H\")..P1DT2H)", ColumnType.DAY_TIME_DURATION))
                .isEqualTo(new UnaryTests.Positive(List.of(new Range(Duration.ofHours(1), true, Duration.ofHours(26), false))));
        // years and months compare by their total of months
        Assertions.assertThat(CellParser.parseFeel(">= duration(\"P14M\")", ColumnType.YEAR_MONTH_DURATION))
                .isEqualTo(new UnaryTests.Positive(List.of(new Compare(Operator.GE, new YearsAndMonths(14)))));
        Assertions.assertThatThrownBy(() -> CellParser.parse("duration(\"P1Y2D\")", ColumnType.YEAR_MONTH_DURATION))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("invalid years and months duration 'P1Y2D'");
        Assertions.assertThatThrownBy(() -> CellParser.parse("duration(\"P1Y\")", ColumnType.DAY_TIME_DURATION))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("invalid days and time duration 'P1Y'");
        // in strict FEEL a bare P1D is a name, of another input
        Assertions.assertThat(CellParser.parseFeel("P1D", ColumnType.DAY_TIME_DURATION))
                .isEqualTo(new UnaryTests.Positive(List.of(new UnaryTests.Equals(new UnaryTests.Name("P1D")))));
    }

    @Test
    void durations_are_written_as_feel_writes_them() {
        Assertions.assertThat(FeelLiterals.text(Duration.ofHours(26).plusMinutes(30))).isEqualTo("P1DT2H30M");
        Assertions.assertThat(FeelLiterals.text(Duration.ofMillis(-1500))).isEqualTo("-PT1.5S");
        Assertions.assertThat(FeelLiterals.text(Duration.ZERO)).isEqualTo("PT0S");
        Assertions.assertThat(FeelLiterals.literal(Duration.ofDays(2))).isEqualTo("duration(\"P2D\")");
        Assertions.assertThat(new YearsAndMonths(14)).hasToString("P1Y2M");
        Assertions.assertThat(new YearsAndMonths(-3)).hasToString("-P3M");
        Assertions.assertThat(new YearsAndMonths(12)).hasToString("P1Y");
        Assertions.assertThat(new YearsAndMonths(0)).hasToString("P0M");
        Assertions.assertThat(FeelLiterals.literal(YearsAndMonths.of(Period.ofMonths(12)))).isEqualTo("duration(\"P1Y\")");
        Assertions.assertThat(new YearsAndMonths(14).toPeriod()).isEqualTo(Period.of(1, 2, 0));
    }

    @Test
    void typed_and_map_based_inputs_compare_by_length() {
        var engine = PraeceptaEngine.<Loan, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .durationInput("grace", Loan::grace)
                .periodInput("term", Loan::term)
                .output(output -> output.values().get("plan"))
                .rules(RULES)
                .build();
        Assertions.assertThat(engine.evaluate(new Loan(Duration.ofHours(23), Period.ofMonths(24)))).containsExactly("short");
        Assertions.assertThat(engine.evaluate(new Loan(Duration.ofHours(23), Period.ofYears(2)))).containsExactly("short");
        Assertions.assertThat(engine.evaluate(new Loan(Duration.ofHours(60), Period.ofMonths(25)))).isEmpty(); // P2DT12H excluded
        Assertions.assertThat(engine.evaluate(new Loan(Duration.ofDays(2), Period.of(2, 1, 0)))).containsExactly("long");
        var withDays = new Loan(Duration.ZERO, Period.of(1, 0, 3));
        Assertions.assertThatThrownBy(() -> engine.evaluate(withDays))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("has days; a years and months duration has only years and months");

        var byMap = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("grace", ColumnType.DAY_TIME_DURATION)
                .columnType("term", ColumnType.YEAR_MONTH_DURATION)
                .output(output -> output.values().get("plan"))
                .rules(RULES)
                .build();
        Assertions.assertThat(byMap.evaluate(Map.of("grace", "PT36H", "term", "P30M"))).containsExactly("long");
        var notADuration = Map.of("grace", "36 hours");
        Assertions.assertThatThrownBy(() -> byMap.evaluate(notADuration))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a valid days and time duration");
    }

    @Test
    void gaps_name_durations_and_give_examples_a_getter_returns() {
        var engine = PraeceptaEngine.<Loan, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .durationInput("grace", Loan::grace)
                .periodInput("term", Loan::term)
                .allowedValues("term", "[duration(\"P1Y\")..duration(\"P3Y\")]")
                .output(output -> output.values().get("plan"))
                .rules(List.of(rule(1, "-", "<= duration(\"P2Y\")", "short")))
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail()).isEqualTo("term in (duration(\"P2Y\")..duration(\"P3Y\")]");
            Assertions.assertThat(gap.example()).containsEntry("term", Period.of(2, 6, 0)); // the midpoint, as for numbers
        });
    }

    @Test
    void dmn_reads_and_writes_duration_columns() {
        var table = new DecisionTable("loan", HitPolicy.FIRST, null,
                List.of(new DecisionTable.InputColumn("grace", ColumnType.DAY_TIME_DURATION),
                        new DecisionTable.InputColumn("term", ColumnType.YEAR_MONTH_DURATION)),
                List.of(new DecisionTable.OutputColumn("plan", ColumnType.STRING)), RULES);
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml).contains("typeRef=\"dayTimeDuration\"", "typeRef=\"yearMonthDuration\"", "&gt; duration(\"P2Y\")");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(read.inputs()).extracting(DecisionTable.InputColumn::type)
                .containsExactly(ColumnType.DAY_TIME_DURATION, ColumnType.YEAR_MONTH_DURATION);
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);
    }

    private static Praeceptum rule(int id, String grace, String term, String plan) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("grace", grace, "term", term)), new PraeceptaOutput(Map.of("plan", plan)));
    }
}
