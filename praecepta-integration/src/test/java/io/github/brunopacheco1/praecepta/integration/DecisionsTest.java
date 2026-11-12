/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

class DecisionsTest {

    private static final ClassLoader LOADER = DecisionsTest.class.getClassLoader();

    /** A UNIQUE table whose two rules overlap on "PT": an ERROR of the validation report. */
    private static final String OVERLAPPING = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="tier" name="Tier"><decisionTable hitPolicy="UNIQUE">
                <input><inputExpression typeRef="string"><text>country</text></inputExpression></input>
                <output name="tier" typeRef="string"/>
                <rule><inputEntry><text>"PT"</text></inputEntry><outputEntry><text>"gold"</text></outputEntry></rule>
                <rule><inputEntry><text>"PT","ES"</text></inputEntry><outputEntry><text>"silver"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /** A FIRST table whose second rule can never be selected: a WARNING. */
    private static final String UNREACHABLE = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="grade" name="Grade"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                <output name="grade" typeRef="string"/>
                <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"A"</text></outputEntry></rule>
                <rule><inputEntry><text>&gt; 5</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    @Test
    void every_decision_of_every_file_gets_a_map_based_engine_named_after_it() {
        var decisions = Decisions.load(TableLocations.find(TableLocations.DEFAULT, LOADER), Severity.ERROR);

        Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");
        Assertions.assertThat(decisions.get("Carrier").location()).endsWith("rules/shipping/shipping.dmn");
        Assertions.assertThat(decisions.evaluate("Carrier", Map.of("weight", "1.5"))).containsExactly(Map.of("Carrier", "post"));
        Assertions.assertThat(decisions.evaluate("Carrier", Map.of("weight", "30"))).containsExactly(Map.of("Carrier", "freight"));
        Assertions.assertThat(decisions.evaluate("Discount", Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")))
                .containsExactly(Map.of("discount", "0.15", "tier", "gold"), Map.of("discount", "0.05", "tier", "silver"));
        Assertions.assertThat(decisions.engine("Discount").evaluate(Map.of("country", "FR", "customer.age", "30"))).isEmpty();
        // the table's input value list holds
        var unlisted = Map.of("country", "DE");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Discount", unlisted)).isInstanceOf(InvalidInputException.class);

        Assertions.assertThat(decisions.find("Nope")).isEmpty();
        Assertions.assertThatThrownBy(() -> decisions.get("Nope"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("No decision named 'Nope'; there are [Carrier, Discount, Fee]");
    }

    @Test
    void collect_aggregations_give_one_map_with_the_aggregate() {
        var decisions = Decisions.load(TableLocations.find("classpath:rules/shipping/*.dmn", LOADER), Severity.ERROR);
        Assertions.assertThat(decisions.evaluate("Fee", Map.of("zone", "islands"))).containsExactly(Map.of("fee", "17.5"));
        Assertions.assertThat(decisions.evaluate("Fee", Map.of("zone", "mainland"))).containsExactly(Map.of("fee", "5"));

        var count = Decision.of("count", aggregated("COUNT")).engine();
        Assertions.assertThat(count.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", "2"));
        Assertions.assertThat(count.evaluate(Map.of("zone", "nowhere"))).containsExactly(Map.of("fee", "1"));
        var min = Decision.of("min", aggregated("MIN")).engine();
        Assertions.assertThat(min.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", "5"));
        var max = Decision.of("max", aggregated("MAX")).engine();
        Assertions.assertThat(max.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", "12.5"));
    }

    @Test
    void sums_keep_feel_precision_and_skip_missing_values() {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="total" name="Total"><decisionTable hitPolicy="COLLECT" aggregation="SUM">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <input><inputExpression typeRef="number"><text>extra</text></inputExpression></input>
                    <output name="total" typeRef="number"/>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>0.1</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>extra</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>1234567890123456789012345678901234</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        var engine = Decision.of("total", table).engine();
        // 1234567890123456789012345678901234.1 has 35 digits: rounded to 34, ties to even
        Assertions.assertThat(engine.evaluate(Map.of("zone", "x"))).containsExactly(Map.of("total", "1234567890123456789012345678901234"));
        Assertions.assertThat(engine.evaluate(Map.of("zone", "x", "extra", "0.9")))
                .containsExactly(Map.of("total", "1234567890123456789012345678901235"));
    }

    /** DMN 1.5, 10.3.2.10: a table's default output values are its result when no rule matches (#79). */
    @Test
    void a_tables_default_output_is_the_one_map_when_no_rule_matches() {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="SUM">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="fee" typeRef="number"><defaultOutputEntry><text>9.90</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>12.5</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>5</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        var engine = Decision.of("fee", table).engine();
        Assertions.assertThat(engine.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", "17.5"));
        Assertions.assertThat(engine.evaluate(Map.of("zone", "mainland"))).containsExactly(Map.of("fee", "9.90"));
    }

    @Test
    void outputs_that_are_missing_map_to_null() {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="echo" name="Echo"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="string"><text>name</text></inputExpression></input>
                    <output name="greeting" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"Hello " + name</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        var engine = Decision.of("echo", table).engine();
        Assertions.assertThat(engine.evaluate(Map.of("name", "Ana"))).containsExactly(Map.of("greeting", "Hello Ana"));
        var missing = new HashMap<String, String>();
        missing.put("greeting", null);
        Assertions.assertThat(engine.evaluate(Map.of())).containsExactly(missing);
    }

    @Test
    void validation_issues_fail_loading_at_the_configured_severity() {
        var overlapping = TableSource.of("overlapping.dmn", OVERLAPPING.getBytes(StandardCharsets.UTF_8));
        var unreachable = TableSource.of("unreachable.dmn", UNREACHABLE.getBytes(StandardCharsets.UTF_8));

        Assertions.assertThatThrownBy(() -> Decisions.load(List.of(overlapping, unreachable), Severity.ERROR))
                .isInstanceOf(DecisionsException.class)
                .hasMessageStartingWith("Cannot load decision tables: overlapping.dmn: decision 'Tier' has validation issues at ERROR")
                .hasMessageContaining("ERROR rules [1, 2] both match some inputs (UNIQUE)");
        var strict = List.of(overlapping, unreachable);
        Assertions.assertThatThrownBy(() -> Decisions.load(strict, Severity.WARNING))
                .isInstanceOf(DecisionsException.class)
                .satisfies(e -> Assertions.assertThat(((DecisionsException) e).problems()).hasSize(2));

        var lenient = Decisions.load(List.of(overlapping, unreachable), null);
        Assertions.assertThat(lenient.get("Tier").report().hasIssuesAtLeast(Severity.ERROR)).isTrue();
        Assertions.assertThat(Decisions.load(List.of(unreachable), Severity.ERROR).get("Grade").report().issues()).hasSize(1);
    }

    @Test
    void every_problem_is_reported_at_once() {
        var unreadable = new TableSource("gone.dmn", () -> {
            throw new IOException("no such file");
        });
        var notDmn = TableSource.of("page.dmn", "<html/>".getBytes(StandardCharsets.UTF_8));
        var empty = TableSource.of("empty.dmn", """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n"/>"""
                .getBytes(StandardCharsets.UTF_8));
        var first = TableSource.of("one.dmn", UNREACHABLE.getBytes(StandardCharsets.UTF_8));
        var again = TableSource.of("two.dmn", UNREACHABLE.getBytes(StandardCharsets.UTF_8));

        Assertions.assertThatThrownBy(() -> Decisions.load(List.of(unreadable, notDmn, empty, first, again), Severity.ERROR))
                .isInstanceOfSatisfying(DecisionsException.class, e -> Assertions.assertThat(e.problems()).containsExactly(
                        "gone.dmn: cannot be read (no such file)",
                        "page.dmn: Not a DMN model: root element is <html>",
                        "empty.dmn: no decision table",
                        "two.dmn: decision 'Grade' is also defined in one.dmn"))
                .hasMessageStartingWith("Cannot load decision tables (4 problems):");
    }

    /** DMN 1.5, Table 75: count takes any list, min and max "comparable items", sum numbers only. */
    @Test
    void count_takes_outputs_of_any_type_and_min_and_max_any_ordered_type() {
        var names = Decision.of("names", typed("COUNT", "string", "\"b\"", "\"a\"")).engine();
        Assertions.assertThat(names.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "2"));
        Assertions.assertThat(names.evaluate(Map.of("zone", "mainland"))).containsExactly(Map.of("out", "1"));

        // strings by character: U+1F40E comes after U+FF5E, though not by UTF-16 code unit
        var strings = Decision.of("strings", typed("MAX", "string", "\"～\"", "\"🐎\"")).engine();
        Assertions.assertThat(strings.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "🐎"));
        var earliest = Decision.of("dates", typed("MIN", "date", "date(\"2025-03-01\")", "date(\"2024-12-31\")")).engine();
        Assertions.assertThat(earliest.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "2024-12-31"));
        var longest = Decision.of("durations", typed("MAX", "dayTimeDuration", "duration(\"P1D\")", "duration(\"PT25H\")")).engine();
        Assertions.assertThat(longest.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "P1DT1H"));
        // times with an offset by instant
        var latest = Decision.of("times", typed("MAX", "time", "time(\"10:00:00+02:00\")", "time(\"09:00:00Z\")")).engine();
        Assertions.assertThat(latest.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "09:00:00Z"));
    }

    @Test
    void aggregations_dmn_does_not_define_are_rejected() {
        Assertions.assertThatThrownBy(() -> Decision.of("names", typed("SUM", "string", "\"a\"", "\"b\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'Out': SUM aggregation needs a number output, not a string");
        Assertions.assertThatThrownBy(() -> Decision.of("flags", typed("MIN", "boolean", "true", "false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'Out': MIN aggregation needs an output whose values are ordered, not a boolean");
        var twoOutputs = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="pair" name="Pair"><decisionTable hitPolicy="COLLECT" aggregation="COUNT">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="a" typeRef="number"/><output name="b" typeRef="number"/>
                    <rule><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>1</text></outputEntry><outputEntry><text>2</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        Assertions.assertThatThrownBy(() -> Decision.of("pair", twoOutputs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'Pair': COUNT aggregation needs exactly one output; DMN does not aggregate 2 outputs");
        // what the engine's builder rejects is named after the table too
        var table = new DecisionTable("Odd", HitPolicy.FIRST, AggregationOperator.COUNT,
                List.of(new InputColumn("zone", ColumnType.STRING)), List.of(new OutputColumn("n", ColumnType.NUMBER)),
                List.of(new Praeceptum(1, new PraeceptaInput(Map.of("zone", "-")), new PraeceptaOutput(Map.of("n", "1")))));
        Assertions.assertThatThrownBy(() -> Decision.of("odd", table))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Table 'Odd': ")
                .hasMessageContaining("COLLECT");
    }

    @Test
    void a_table_that_cannot_be_built_fails_loading_and_its_file_gives_no_decision() {
        var file = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="good" name="Good"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="out" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"a"</text></outputEntry></rule>
                  </decisionTable></decision>
                  <decision id="bad" name="Bad"><decisionTable hitPolicy="COLLECT" aggregation="MAX">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="out" typeRef="boolean"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var source = TableSource.of("both.dmn", file.getBytes(StandardCharsets.UTF_8));
        Assertions.assertThatThrownBy(() -> Decisions.load(List.of(source), Severity.ERROR))
                .isInstanceOfSatisfying(DecisionsException.class, e -> Assertions.assertThat(e.problems()).containsExactly(
                        "both.dmn: Table 'Bad': MAX aggregation needs an output whose values are ordered, not a boolean"));
    }

    @Test
    void a_count_that_no_rule_matches_is_zero() {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="COUNT">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="fee" typeRef="number"/>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>12.5</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        var count = Decision.of("count", table).engine();
        Assertions.assertThat(count.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", "1"));
        Assertions.assertThat(count.evaluate(Map.of("zone", "nowhere"))).containsExactly(Map.of("fee", "0"));
    }

    /**
     * A single output's type is the decision's, as DMN 1.5 has it (Table 34: its OutputClause SHALL NOT specify
     * one), so the conformant aggregation tables of the TCK load: here 0115-sum-collect-hitpolicy's pattern.
     */
    @Test
    void a_single_output_typed_by_its_decision_can_be_summed() {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="salary" name="Salary"><variable name="Salary" typeRef="number"/>
                    <decisionTable hitPolicy="COLLECT" aggregation="SUM">
                      <input><inputExpression typeRef="number"><text>years</text></inputExpression></input>
                      <output/>
                      <rule><inputEntry><text>&gt; 1</text></inputEntry><outputEntry><text>100.50</text></outputEntry></rule>
                      <rule><inputEntry><text>&gt; 2</text></inputEntry><outputEntry><text>200.50</text></outputEntry></rule>
                    </decisionTable></decision>
                </definitions>""")).getFirst();
        var salary = Decision.of("salary", table).engine();
        Assertions.assertThat(salary.evaluate(Map.of("years", "5"))).containsExactly(Map.of("Salary", "301.00"));
        Assertions.assertThat(salary.evaluate(Map.of("years", "2"))).containsExactly(Map.of("Salary", "100.50"));
    }

    @Test
    void decisions_describe_themselves_for_management_endpoints() {
        var decisions = Decisions.load(List.of(TableSource.of("grade.dmn", UNREACHABLE.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        var grade = new HashMap<String, Object>();
        grade.put("name", "Grade");
        grade.put("location", "grade.dmn");
        grade.put("hitPolicy", "FIRST");
        grade.put("aggregation", null);
        grade.put("rules", 2);
        grade.put("inputs", List.of(Map.of("name", "score", "type", "NUMBER", "list", false)));
        grade.put("outputs", List.of(Map.of("name", "grade", "type", "STRING")));
        grade.put("issues", List.of(issue("WARNING", "UNREACHABLE_RULE", List.of(2), "WARNING rule 2 can never be selected")));
        Assertions.assertThat(decisions.describe()).isEqualTo(Map.of("decisions", Map.of("Grade", grade)));
        Assertions.assertThat(decisions.all()).singleElement().extracting(Decision::name).isEqualTo("Grade");
        Assertions.assertThat(decisions).hasToString("Decisions[Grade]");
    }

    @Test
    void fail_on_settings_are_parsed_strictly() {
        Assertions.assertThat(Decisions.failOn("ERROR")).isEqualTo(Severity.ERROR);
        Assertions.assertThat(Decisions.failOn(" warning ")).isEqualTo(Severity.WARNING);
        Assertions.assertThat(Decisions.failOn("none")).isNull();
        Assertions.assertThatThrownBy(() -> Decisions.failOn("sometimes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fail-on must be ERROR, WARNING or NONE, not sometimes");
        Assertions.assertThatThrownBy(() -> Decisions.failOn(null)).isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThat(Decisions.empty().isEmpty()).isTrue();
    }

    private static Map<String, Object> issue(String severity, String kind, List<Integer> rules, String message) {
        var issue = new HashMap<String, Object>();
        issue.put("severity", severity);
        issue.put("kind", kind);
        issue.put("rules", rules);
        issue.put("detail", null);
        issue.put("example", Map.of());
        issue.put("message", message);
        return issue;
    }

    private static DecisionTable aggregated(String operator) {
        return DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="fee" typeRef="number"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>5</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>12.5</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(operator))).getFirst();
    }

    /** A COLLECT table named Out of one output, {@code out}, of a type, aggregated; rules: any zone, then the islands. */
    private static DecisionTable typed(String operator, String type, String always, String islands) {
        return DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="out" name="Out"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="out" typeRef="%s"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(operator, type, xml(always), xml(islands)))).getFirst();
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static InputStream stream(String dmn) {
        return new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8));
    }
}
