/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

/** Tables whose cells use FEEL's literal forms and tests beyond Praecepta's first syntax (#81). */
class FeelLiteralFormsTest {

    // ---- = and != ----------------------------------------------------------------------------------

    @Test
    void not_equal_holds_for_other_values_and_for_a_missing_one() {
        // DMN 1.5, 10.3.2.15: FEEL(e1 != e2) is FEEL(not(e1 = e2)), and null = "PT" is false
        var engine = engine(ColumnType.STRING, "!= \"PT\"");
        Assertions.assertThat(engine.evaluate(input("ES"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("PT"))).isEmpty();
        Assertions.assertThat(engine.evaluate(input(null))).containsExactly("1");
    }

    @Test
    void not_equal_null_holds_for_any_value_that_is_not_missing() {
        var engine = engine(ColumnType.NUMBER, "!= null");
        Assertions.assertThat(engine.evaluate(input("0"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input(null))).isEmpty();
        var equalNull = engine(ColumnType.NUMBER, "= null");
        Assertions.assertThat(equalNull.evaluate(input(null))).containsExactly("1");
        Assertions.assertThat(equalNull.evaluate(input("0"))).isEmpty();
    }

    @Test
    void not_equal_is_one_test_among_the_others_of_a_cell() {
        var engine = engine(ColumnType.NUMBER, "!= 1, > 10");
        Assertions.assertThat(engine.evaluate(input("1"))).isEmpty();
        Assertions.assertThat(engine.evaluate(input("2"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("1.0"))).isEmpty(); // a number equals its value
        // not(...) of the same tests: neither "not 1" nor "above 10"
        var negated = engine(ColumnType.NUMBER, "not(!= 1, > 10)");
        Assertions.assertThat(negated.evaluate(input("1"))).containsExactly("1");
        Assertions.assertThat(negated.evaluate(input("11"))).isEmpty();
    }

    @Test
    void not_equal_another_input_is_decided_when_evaluating() {
        var table = table(ColumnType.STRING, true, "!= home");
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("x", "PT", "home", "ES"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(Map.of("x", "PT", "home", "PT"))).isEmpty();
        Assertions.assertThat(engine.evaluate(Map.of("home", "PT"))).containsExactly("1"); // null = "PT" is false
        Assertions.assertThat(engine.evaluate(Map.of())).isEmpty(); // null = null is true
    }

    @Test
    void not_equal_on_a_list_column_holds_when_some_element_differs() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .listColumnType("x", ColumnType.STRING)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "!= \"PT\"")))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("x", "[\"PT\", \"BR\"]"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(Map.of("x", "[\"PT\"]"))).isEmpty();
        Assertions.assertThat(engine.evaluate(Map.of("x", "[]"))).isEmpty(); // no element
        Assertions.assertThat(engine.evaluate(Map.of())).isEmpty(); // a missing list makes every element test unknown
    }

    @Test
    void the_analysis_counts_not_equal_tests() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, "!= \"PT\""), rule(2, "\"ES\""), rule(3, "= \"PT\"")))
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.OVERLAPPING_RULES))
                .singleElement()
                .satisfies(issue -> Assertions.assertThat(issue.ruleIds()).containsExactly(1, 2));
    }

    @Test
    void dmn_writes_equal_and_not_equal_tests_and_reads_them_back() {
        var table = table(ColumnType.NUMBER, false, "= 18, != 21, != null");
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml).contains("<text>18, != 21, != null</text>");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(read.rules().getFirst().input().values()).containsEntry("x", "18, != 21, != null");
        var lists = new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("x", ColumnType.STRING, null)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING, List.of())), List.of(rule(1, "!= \"PT\", != null")), false,
                Set.of("x"));
        Assertions.assertThat(DmnWriter.write(lists))
                .contains("<text>some x in ? satisfies x != \"PT\" or some x in ? satisfies x != null</text>");
    }

    // ---- numbers -------------------------------------------------------------------------------

    @Test
    void numbers_with_a_leading_fraction_or_an_exponent_are_values() {
        var engine = engine(ColumnType.NUMBER, "< 1E-2, [.5..1.2e3]");
        Assertions.assertThat(engine.evaluate(input("0.009"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("0.01"))).isEmpty();
        Assertions.assertThat(engine.evaluate(input("0.5"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("1200"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("1200.5"))).isEmpty();
        Assertions.assertThat(DmnWriter.write(table(ColumnType.NUMBER, false, "1.2e3"))).contains("<text>1200</text>");
    }

    // ---- date time literals ----------------------------------------------------------------------

    @Test
    void at_literals_and_the_date_and_time_functions_are_values_of_their_type() {
        var dates = engine(ColumnType.DATE, ">= @\"2019-03-31\", date(2019, 1, 1)");
        Assertions.assertThat(dates.evaluate(input("2019-03-31"))).containsExactly("1");
        Assertions.assertThat(dates.evaluate(input("2019-01-01"))).containsExactly("1");
        Assertions.assertThat(dates.evaluate(input("2019-03-30"))).isEmpty();
        var durations = engine(ColumnType.DAY_TIME_DURATION, "<= @\"PT1H\"");
        Assertions.assertThat(durations.evaluate(input("PT60M"))).containsExactly("1");
        Assertions.assertThat(durations.evaluate(input("PT61M"))).isEmpty();
        var times = engine(ColumnType.TIME, "[time(8, 30, 0)..@\"12:00:00\")");
        Assertions.assertThat(times.evaluate(input("08:30"))).containsExactly("1");
        Assertions.assertThat(times.evaluate(input("12:00"))).isEmpty();
    }

    @Test
    void a_date_and_time_without_a_time_is_at_midnight() {
        // DMN TCK, 0068-feel-equality: date and time("2018-12-08") = date and time("2018-12-08T00:00:00")
        var engine = engine(ColumnType.DATE_TIME, "date and time(\"2018-12-08\")");
        Assertions.assertThat(engine.evaluate(input("2018-12-08T00:00:00"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("2018-12-08T00:00:01"))).isEmpty();
    }

    @Test
    void dmn_writes_date_time_literals_as_the_date_and_time_functions_and_reads_them_back() {
        var table = table(ColumnType.DATE_TIME, true, "[@\"2025-01-01T00:00:00\"..date and time(date(2025, 1, 31), time(8, 30, 0))]");
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml)
                .contains("<text>[date and time(\"2025-01-01T00:00:00\")..date and time(\"2025-01-31T08:30:00\")]</text>");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(read)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(input("2025-01-31T08:30"))).containsExactly("1");
        Assertions.assertThat(engine.evaluate(input("2025-01-31T08:31"))).isEmpty();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static PraeceptaEngine<Map<String, String>, String> engine(ColumnType type, String cell) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("x", type)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, cell)))
                .build();
    }

    private static DecisionTable table(ColumnType type, boolean feel, String cell) {
        return new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("x", type, null)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING, List.of())), List.of(rule(1, cell)), feel, Set.of());
    }

    private static Map<String, String> input(String x) {
        var input = new HashMap<String, String>();
        input.put("x", x);
        return input;
    }

    private static Praeceptum rule(int id, String cell) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("x", cell)), new PraeceptaOutput(Map.of("id", String.valueOf(id))));
    }
}
