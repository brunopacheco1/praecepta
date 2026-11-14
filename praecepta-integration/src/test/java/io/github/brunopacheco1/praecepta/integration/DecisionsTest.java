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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void sums_keep_feel_precision_and_are_missing_where_an_output_is() {
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
        Assertions.assertThat(engine.evaluate(Map.of("zone", "x", "extra", "0")))
                .containsExactly(Map.of("total", "1234567890123456789012345678901234"));
        Assertions.assertThat(engine.evaluate(Map.of("zone", "x", "extra", "0.9")))
                .containsExactly(Map.of("total", "1234567890123456789012345678901235"));
        // without extra, the second rule's output is missing, and so is the sum (FEEL: sum([0.1, null, ...]) is null, #90)
        var missing = new HashMap<String, String>();
        missing.put("total", null);
        Assertions.assertThat(engine.evaluate(Map.of("zone", "x"))).containsExactly(missing);
    }

    /** FEEL's min and max are null for a list that holds null, as sum is; count counts every item (#90). */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "SUM,   null",
            "MIN,   null",
            "MAX,   null",
            "COUNT, 2",
    })
    void a_literal_null_output_makes_sum_min_and_max_missing_but_is_counted(String aggregation, String expected) {
        var table = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="fee" typeRef="number"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>10</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>null</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(aggregation))).getFirst();
        var engine = Decision.of("fee", table).engine();
        var result = new HashMap<String, String>();
        result.put("fee", expected);
        Assertions.assertThat(engine.evaluate(Map.of("zone", "islands"))).containsExactly(result);
        Assertions.assertThat(engine.evaluate(Map.of("zone", "mainland")))
                .containsExactly(Map.of("fee", aggregation.equals("COUNT") ? "1" : "10"));
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

    /** A list column whose cells test the elements; FEEL's answers for a missing element (#84). */
    private static final String NATIONALITIES = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <itemDefinition name="tCountries" isCollection="true"><typeRef>string</typeRef></itemDefinition>
              <decision id="offer" name="Offer"><decisionTable hitPolicy="%s">
                <input><inputExpression typeRef="tCountries"><text>nationalities</text></inputExpression></input>
                <output name="offer" typeRef="string"/>
                <rule><inputEntry><text>list contains(?, "PT")</text></inputEntry><outputEntry><text>"pt"</text></outputEntry></rule>
                <rule><inputEntry><text>%s</text></inputEntry><outputEntry><text>"other"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    @Test
    void a_list_input_may_hold_missing_elements() {
        var table = DmnReader.readAll(stream(NATIONALITIES.formatted("COLLECT", "list contains(?, null)"))).getFirst();
        var engine = Decision.of("offer", table).engine();
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[\"PT\", null]")))
                .containsExactly(Map.of("offer", "pt"), Map.of("offer", "other"));
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[\"BR\"]"))).isEmpty();
    }

    /** Rules that test different values of a list overlap on a list holding both (#78): an ERROR that stops loading. */
    @Test
    void rules_that_test_a_list_column_are_validated() {
        var dmn = NATIONALITIES.formatted("UNIQUE", "list contains(?, \"BR\")");
        var sources = List.of(TableSource.of("offer.dmn", dmn.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR))
                .isInstanceOf(DecisionsException.class)
                .hasMessageContaining("rules [1, 2] both match some inputs (UNIQUE)");
        var table = DmnReader.readAll(stream(dmn)).getFirst();
        Assertions.assertThat(Decision.of("offer", table).report().issues()).hasSize(1);
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

        var sources = List.of(overlapping, unreachable);
        Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR))
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
        // times with a zone id by local time in their zone (the TCK's 0068-feel-equality)
        var paris = Decision.of("paris", typed("MAX", "time", "time(\"10:00:00@Europe/Paris\")", "@\"09:00:00@Europe/Paris\"")).engine();
        Assertions.assertThat(paris.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "10:00:00@Europe/Paris"));
    }

    /**
     * A time in a zone with one fixed offset is the time at that offset (DMN 1.5, Table 72: {@code time("23:59:00z") +
     * duration("PT2M") = time("00:01:00@Etc/UTC")}), so min and max order it with times at an offset.
     */
    @Test
    void min_and_max_take_a_time_in_a_zone_with_one_fixed_offset_as_the_time_at_that_offset() {
        // 10:00@Etc/GMT-2 is 08:00Z, before 09:00Z
        var latest = Decision.of("latest", typed("MAX", "time", "time(\"10:00:00@Etc/GMT-2\")", "time(\"09:00:00Z\")")).engine();
        Assertions.assertThat(latest.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "09:00:00Z"));
        var earliest = Decision.of("earliest", typed("MIN", "time", "time(\"10:00:00@Etc/GMT-2\")", "time(\"09:00:00Z\")")).engine();
        Assertions.assertThat(earliest.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("out", "10:00:00+02:00"));
        // outputs given as text keep it, and order the same way, as do outputs that compute such a time
        var rules = List.of(output(1, "-", "10:00:00@Etc/GMT-2", null), output(2, "islands", "09:00:00@Etc/UTC", null),
                output(3, "islands", null, "time(\"23:59:00@Etc/UTC\") + duration(\"PT2M\")"));
        for (var operator : List.of(AggregationOperator.MIN, AggregationOperator.MAX)) {
            var table = new DecisionTable("Out", HitPolicy.COLLECT, operator, List.of(new InputColumn("zone", ColumnType.STRING)),
                    List.of(new OutputColumn("out", ColumnType.TIME)), rules);
            var engine = Decision.of("text", table).engine();
            Assertions.assertThat(engine.evaluate(Map.of("zone", "islands"))).as("%s", operator)
                    .containsExactly(Map.of("out", operator == AggregationOperator.MIN ? "00:01:00Z" : "09:00:00@Etc/UTC"));
            Assertions.assertThat(engine.evaluate(Map.of("zone", "mainland"))).as("%s", operator)
                    .containsExactly(Map.of("out", "10:00:00@Etc/GMT-2"));
        }
        // with a time in a zone whose offset changes, it does not compare
        var nothing = Collections.singletonMap("out", (String) null);
        var paris = Decision.of("paris", typed("MAX", "time", "time(\"10:00:00@Etc/UTC\")", "time(\"10:00:00@Europe/Paris\")")).engine();
        Assertions.assertThat(paris.evaluate(Map.of("zone", "islands"))).containsExactly(nothing);
    }

    private static Praeceptum output(int id, String zone, String value, String expression) {
        var values = new HashMap<String, String>();
        if (value != null) {
            values.put("out", value);
        }
        return new Praeceptum(id, new PraeceptaInput(Map.of("zone", zone)),
                new PraeceptaOutput(values, Map.of(), expression == null ? Map.of() : Map.of("out", expression)));
    }

    @Test
    void min_and_max_of_times_that_do_not_compare_are_null() {
        // DMN 1.5 does not say how times with a zone id in two zones compare, and the TCK leaves it out; min and max
        // take "comparable items" (Table 75), and a built-in function is null outside its domain (10.3.2.16)
        var nothing = Collections.singletonMap("out", (String) null);
        for (var operator : List.of("MIN", "MAX")) {
            var zones = Decision.of("zones", typed(operator, "time", "time(\"10:00:00@Europe/Paris\")", "time(\"09:00:00@Asia/Dhaka\")"))
                    .engine();
            Assertions.assertThat(zones.evaluate(Map.of("zone", "mainland"))).containsExactly(Map.of("out", "10:00:00@Europe/Paris"));
            Assertions.assertThat(zones.evaluate(Map.of("zone", "islands"))).containsExactly(nothing);
        }
        // null once two values do not compare, whatever comes after: the third time is in the first one's zone
        var three = DmnReader.readAll(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="out" name="Out"><decisionTable hitPolicy="COLLECT" aggregation="MAX">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="out" typeRef="time"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>@"10:00:00@Europe/Paris"</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>@"09:00:00@Asia/Dhaka"</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>@"11:00:00@Europe/Paris"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""")).getFirst();
        Assertions.assertThat(Decision.of("three", three).engine().evaluate(Map.of("zone", "any"))).containsExactly(nothing);
    }

    @Test
    void aggregations_dmn_does_not_define_are_rejected() {
        var names = typed("SUM", "string", "\"a\"", "\"b\"");
        Assertions.assertThatThrownBy(() -> Decision.of("names", names))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'Out': SUM aggregation needs a number output, not a string");
        var flags = typed("MIN", "boolean", "true", "false");
        Assertions.assertThatThrownBy(() -> Decision.of("flags", flags))
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

    /**
     * DMN 1.5, 10.3.2.10, step 2: when no rule matches, the result is the default output value, or null without one,
     * before any aggregation: COUNT gives no map rather than zero, as SUM, MIN and MAX give none.
     */
    @ParameterizedTest
    @CsvSource({"SUM, 12.5", "MIN, 12.5", "MAX, 12.5", "COUNT, 1"})
    void an_aggregation_that_no_rule_matches_gives_the_default_or_nothing(String aggregation, String islands) {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="fee" typeRef="number">%s</output>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>12.5</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var withoutDefault = Decision.of("fee", DmnReader.readAll(stream(xml.formatted(aggregation, ""))).getFirst()).engine();
        Assertions.assertThat(withoutDefault.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("fee", islands));
        Assertions.assertThat(withoutDefault.evaluate(Map.of("zone", "nowhere"))).isEmpty();
        Assertions.assertThat(withoutDefault.evaluate(Map.of())).isEmpty();

        var defaultEntry = "<defaultOutputEntry><text>0</text></defaultOutputEntry>";
        var withDefault = Decision.of("fee", DmnReader.readAll(stream(xml.formatted(aggregation, defaultEntry))).getFirst()).engine();
        Assertions.assertThat(withDefault.evaluate(Map.of("zone", "nowhere"))).containsExactly(Map.of("fee", "0"));
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

    /**
     * DMN 1.5, 10.3.2.10: "A complete decision table SHALL NOT specify a default output value" (#91). A table that every
     * input matches, a missing score included, and that declares a default fails to load at ERROR, the default
     * fail-on, and describes the issue for the management endpoints.
     */
    @Test
    void a_default_output_on_a_complete_table_fails_loading_and_is_described() {
        var complete = TableSource.of("grade.dmn", """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="grade" name="Grade"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                    <output name="grade" typeRef="string"><defaultOutputEntry><text>"C"</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>&gt;= 5</text></inputEntry><outputEntry><text>"A"</text></outputEntry></rule>
                    <rule><inputEntry><text>&lt; 5, null</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".getBytes(StandardCharsets.UTF_8));
        var message = "ERROR every input matches a rule, so the default output is never given "
                + "(a complete table declares no default output: DMN 1.5, 10.3.2.10)";
        var sources = List.of(complete);
        Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR))
                .isInstanceOf(DecisionsException.class)
                .hasMessageStartingWith("Cannot load decision tables: grade.dmn: decision 'Grade' has validation issues at ERROR")
                .hasMessageContaining(message);

        var lenient = Decisions.load(sources, null);
        Assertions.assertThat(lenient.get("Grade").describe())
                .containsEntry("issues", List.of(issue("ERROR", "UNREACHABLE_DEFAULT_OUTPUT", List.of(), message)));
        // the default is never given
        Assertions.assertThat(lenient.evaluate("Grade", Map.of())).containsExactly(Map.of("grade", "B"));
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

    /**
     * The 0.6 features together in the map-based engine: an output expression over a date (#85), whose missing
     * result makes the MIN missing (#90), a {@code null} default output (#92), and cells with at-literals and
     * comments (#81).
     */
    static final String DUE = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="due" name="Due"><decisionTable hitPolicy="COLLECT" aggregation="MIN">
                <input><inputExpression typeRef="date"><text>sent</text></inputExpression></input>
                <input><inputExpression typeRef="string"><text>service</text></inputExpression></input>
                <output name="due" typeRef="date"><defaultOutputEntry><text>null</text></defaultOutputEntry></output>
                <rule><inputEntry><text>&gt;= @"2025-01-01" // this year</text></inputEntry>
                  <inputEntry><text>"express", "standard"</text></inputEntry>
                  <outputEntry><text>sent + duration("P14D")</text></outputEntry></rule>
                <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>"express"</text></inputEntry>
                  <outputEntry><text>sent + @"P1M" /* a month later, if that day exists */</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // sent, service: the rules' dates -> the earliest
            "2025-01-10, standard, 2025-01-24",
            "2025-01-10, express,  2025-01-24",        // 2025-01-24 and 2025-02-10
            "2025-01-20, express,  2025-02-03",        // 2025-02-03 and 2025-02-20
            "2025-01-31, express,  null",              // 2025-02-14 and no 31 February: FEEL's min([..., null]) is null
            "2024-12-31, standard, null",              // no rule: the default, null
            "2024-12-31, express,  2025-01-31",        // the second rule alone
            "null,       express,  null",              // a missing date: both outputs missing
    })
    void temporal_output_expressions_aggregate_with_missing_results_and_a_null_default(String sent, String service, String due) {
        var engine = Decision.of("due", DmnReader.read(stream(DUE))).engine();
        var input = new HashMap<String, String>();
        input.put("sent", sent);
        input.put("service", service);
        var expected = new HashMap<String, String>();
        expected.put("due", due);
        Assertions.assertThat(engine.evaluate(input)).containsExactly(expected);
    }

    /** A list of dates whose rules test it with the new literal forms (#81), and one asks for a missing element (#84). */
    @Test
    void list_cells_with_the_new_literal_forms_and_a_missing_element_test_text_lists() {
        var engine = Decision.of("due", DmnReader.read(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <itemDefinition name="tDates" isCollection="true"><typeRef>date</typeRef></itemDefinition>
                  <decision id="due" name="Due"><decisionTable hitPolicy="COLLECT">
                    <input><inputExpression typeRef="tDates"><text>due dates</text></inputExpression></input>
                    <output name="id" typeRef="string"/>
                    <rule><inputEntry><text>list contains(?, @"2025-01-31")</text></inputEntry>
                      <outputEntry><text>"january"</text></outputEntry></rule>
                    <rule><inputEntry><text>some x in ? satisfies x != date(2025, 1, 31) // any other</text></inputEntry>
                      <outputEntry><text>"other"</text></outputEntry></rule>
                    <rule><inputEntry><text>list contains(?, null)</text></inputEntry><outputEntry><text>"missing"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>"""))).engine();
        Assertions.assertThat(engine.evaluate(Map.of("due dates", "[2025-01-31]"))).containsExactly(Map.of("id", "january"));
        Assertions.assertThat(engine.evaluate(Map.of("due dates", "[2025-01-31, null]")))
                .containsExactly(Map.of("id", "january"), Map.of("id", "other"), Map.of("id", "missing"));
        Assertions.assertThat(engine.evaluate(Map.of("due dates", "[]"))).isEmpty();
    }

    /** The completeness search of #91 sees list columns (#78): a list table that every list matches has no use for a default. */
    @Test
    void a_list_table_that_every_list_matches_reports_its_default_output() {
        var table = DmnReader.read(stream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <itemDefinition name="tStrings" isCollection="true"><typeRef>string</typeRef></itemDefinition>
                  <decision id="offer" name="Offer"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="tStrings"><text>nationalities</text></inputExpression></input>
                    <output name="offer" typeRef="string"><defaultOutputEntry><text>"none"</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>list contains(?, "PT")</text></inputEntry><outputEntry><text>"local"</text></outputEntry></rule>
                    <rule><inputEntry><text>not(list contains(?, "PT"))</text></inputEntry><outputEntry><text>"abroad"</text></outputEntry></rule>
                    <rule><inputEntry><text>? = null</text></inputEntry><outputEntry><text>"unknown"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>"""));
        Assertions.assertThat(Decision.of("offer", table).describe().get("issues").toString()).contains("UNREACHABLE_DEFAULT_OUTPUT");
    }

    /** A table of a date, time or date and time input and output, holding literals in its cell, output entry and default. */
    private static final String SLOT = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="slot" name="Slot"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="%1$s"><text>at</text></inputExpression></input>
                <output name="until" typeRef="%1$s"><defaultOutputEntry><text>%4$s</text></defaultOutputEntry></output>
                <rule><inputEntry><text>%2$s</text></inputEntry><outputEntry><text>%3$s</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /**
     * Dates, times and date-times take XML Schema's lexical forms (DMN 1.5, 10.3.4.1): an offset beyond -14:00..+14:00
     * (#123), or a year with a {@code +} (#124), fails loading wherever a table holds it, and given as an input it is
     * rejected, as any text that is not of the column's type is ({@code InvalidInputException}); a year of more than
     * four digits is written and read without one.
     */
    @ParameterizedTest(name = "{0} {2}")
    @CsvSource(delimiter = ';', value = {
            "time;     08:00:00+14:00;            08:00:00+19:00",
            "time;     08:00:00-14:00;            08:00:00-14:01",
            "dateTime; 2025-01-31T08:00:00+14:00; 2025-01-31T08:00:00+15:00",
            "date;     99999-12-31;               +99999-12-31",
            "date;     2012-12-02;                +2012-12-02",
            "dateTime; 99999-12-31T11:22:33;      +99999-12-31T11:22:33",
            "dateTime; 2012-12-24T23:59:00@Europe/Paris; +2012-12-24T23:59:00@Europe/Paris",
    })
    void a_value_beyond_xml_schemas_lexical_forms_is_rejected(String type, String valid, String invalid) {
        var function = switch (type) {
            case "date", "time" -> type;
            default -> "date and time";
        };
        var good = "%s(\"%s\")".formatted(function, valid);
        var bad = "@\"%s\"".formatted(invalid);
        var decisions = Decisions.load(List.of(TableSource.of("slot.dmn", SLOT.formatted(type, good, good, good).getBytes(StandardCharsets.UTF_8))),
                Severity.ERROR);
        Assertions.assertThat(decisions.evaluate("Slot", Map.of("at", valid))).containsExactly(Map.of("until", valid));
        var beyond = Map.of("at", invalid);
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Slot", beyond)).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining(invalid);

        for (var file : List.of(SLOT.formatted(type, bad, good, good), SLOT.formatted(type, good, bad, good),
                SLOT.formatted(type, good, good, bad))) {
            var sources = List.of(TableSource.of("slot.dmn", file.getBytes(StandardCharsets.UTF_8)));
            Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR)).as(file)
                    .isInstanceOfSatisfying(DecisionsException.class, e -> Assertions.assertThat(e.problems()).singleElement()
                            .asString().startsWith("slot.dmn: ").contains(invalid));
        }
    }

    private static InputStream stream(String dmn) {
        return new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8));
    }
}
