/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Default output values (DMN's {@code defaultOutputEntry}): DMN 1.5, 8.2.12, "Tables may specify a default
 * output"; Table 34, it "is selected when no rules match for the decision table"; 10.3.2.10, step 2, "if no
 * rules match, if a default output value d is specified, DTI=FEEL(d), else DTI=null". The default applies
 * before any hit policy or aggregation, so every hit policy gives it alone, and a COLLECT aggregation gives
 * it as its result, not aggregated.
 */
class DefaultOutputsTest {

    /** Two outputs, as the TCK's 0108-first-hitpolicy has them; only Status has a default. Rules never overlap. */
    private static final String APPROVAL = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <decision id="Approval" name="Approval"><decisionTable hitPolicy="%s">
                <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                <output name="Status" typeRef="string">
                  <outputValues><text>"Approved", "Declined"</text></outputValues>
                  <defaultOutputEntry><text>"Declined"</text></defaultOutputEntry>
                </output>
                <output name="Rate" typeRef="string"><outputValues><text>"Best", "Standard"</text></outputValues></output>
                <rule><inputEntry><text>&gt;= 700</text></inputEntry>
                  <outputEntry><text>"Approved"</text></outputEntry><outputEntry><text>"Best"</text></outputEntry></rule>
                <rule><inputEntry><text>[600..700)</text></inputEntry>
                  <outputEntry><text>"Approved"</text></outputEntry><outputEntry><text>"Standard"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /** One number output with a default, which overlapping rules aggregate. */
    private static final String FEES = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <decision id="Fee" name="Fee"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                <output typeRef="number"><defaultOutputEntry><text>%s</text></defaultOutputEntry></output>
                <rule><inputEntry><text>&gt;= 700</text></inputEntry><outputEntry><text>10</text></outputEntry></rule>
                <rule><inputEntry><text>&gt;= 600</text></inputEntry><outputEntry><text>20</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    @Test
    void dmn_reader_reads_each_outputs_default_value() {
        var table = read(APPROVAL.formatted("FIRST"));
        Assertions.assertThat(table.outputs()).containsExactly(
                new OutputColumn("Status", ColumnType.STRING, List.of("Approved", "Declined"), "Declined"),
                new OutputColumn("Rate", ColumnType.STRING, List.of("Best", "Standard"), null));
        Assertions.assertThat(table.defaultOutput()).contains(new PraeceptaOutput(values("Status", "Declined", "Rate", null)));
    }

    @ParameterizedTest
    @EnumSource(HitPolicy.class)
    void every_hit_policy_gives_the_default_output_alone_when_no_rule_matches(HitPolicy policy) {
        var engine = mapEngine(read(APPROVAL.formatted(policy.name().replace('_', ' '))));
        // an output without a default is null in it
        Assertions.assertThat(engine.evaluate(Map.of("score", "500"))).containsExactly(values("Status", "Declined", "Rate", null));
        Assertions.assertThat(engine.evaluate(Map.of())).as("a missing input matches no rule either")
                .containsExactly(values("Status", "Declined", "Rate", null));
        // a rule that matches is selected as before
        Assertions.assertThat(engine.evaluate(Map.of("score", "650"))).containsExactly(values("Status", "Approved", "Rate", "Standard"));
    }

    @ParameterizedTest
    @CsvSource({
            "SUM,   750, 30",
            "MIN,   750, 10",
            "MAX,   750, 20",
            "COUNT, 750, 2",
            "SUM,   500, 5",
            "MIN,   500, 5",
            "MAX,   500, 5",
            "COUNT, 500, 5",
    })
    void an_aggregation_gives_the_default_output_as_its_result_when_no_rule_matches(String aggregation, String score, String expected) {
        var engine = numberEngine(read(FEES.formatted(aggregation, "5")));
        Assertions.assertThat(engine.evaluate(Map.of("score", score))).containsExactly(new BigDecimal(expected));
    }

    @Test
    void without_a_default_no_rule_matching_still_gives_no_output_and_count_still_zero() {
        var withoutDefault = FEES.replace("<defaultOutputEntry><text>%s</text></defaultOutputEntry>", "");
        Assertions.assertThat(numberEngine(read(withoutDefault.formatted("SUM"))).evaluate(Map.of("score", "500"))).isEmpty();
        Assertions.assertThat(numberEngine(read(withoutDefault.formatted("COUNT"))).evaluate(Map.of("score", "500")))
                .containsExactly(BigDecimal.ZERO);
        Assertions.assertThat(read(withoutDefault.formatted("SUM")).defaultOutput()).isEmpty();
    }

    @Test
    void a_null_default_is_no_default() {
        var table = read(FEES.formatted("SUM", "null"));
        Assertions.assertThat(table.outputs().getFirst().defaultValue()).isNull();
        Assertions.assertThat(numberEngine(table).evaluate(Map.of("score", "500"))).isEmpty();
    }

    @Test
    void a_rule_whose_cell_compares_with_another_input_and_fails_leaves_the_default() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <decision id="Limit" name="Limit"><decisionTable hitPolicy="UNIQUE">
                    <input><inputExpression typeRef="number"><text>amount</text></inputExpression></input>
                    <output typeRef="string"><defaultOutputEntry><text>"over"</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>&lt;= limit</text></inputEntry><outputEntry><text>"within"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var engine = mapEngine(read(xml));
        Assertions.assertThat(engine.evaluate(Map.of("amount", "5", "limit", "10"))).containsExactly(values("Limit", "within"));
        Assertions.assertThat(engine.evaluate(Map.of("amount", "50", "limit", "10"))).containsExactly(values("Limit", "over"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "score + 1 | default output 'score + 1' is not supported; a default output must be a literal number",
            "score     | default output 'score' is not supported; a default output must be a literal number",
            "\"5\"     | default output '\"5\"' is not supported; a default output must be a literal number",
    })
    void a_default_that_is_not_a_literal_of_the_outputs_type_is_rejected(String text, String message) {
        var xml = FEES.formatted("SUM", text);
        Assertions.assertThatThrownBy(() -> read(xml))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'Fee', output 'Fee': " + message);
    }

    @Test
    void dmn_writer_writes_the_default_after_the_output_values_and_reads_it_back() {
        var table = read(APPROVAL.formatted("PRIORITY"));
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml).contains("""
                      <output id="output_0" name="Status" typeRef="string">
                        <outputValues id="output_values_0"><text>"Approved", "Declined"</text></outputValues>
                        <defaultOutputEntry id="output_default_0"><text>"Declined"</text></defaultOutputEntry>
                      </output>
                      <output id="output_1" name="Rate" typeRef="string">
                        <outputValues id="output_values_1"><text>"Best", "Standard"</text></outputValues>
                      </output>
                """);
        var read = read(xml);
        Assertions.assertThat(read.outputs()).isEqualTo(table.outputs());
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);

        var fees = read(FEES.formatted("MAX", "1.50"));
        var feesXml = DmnWriter.write(fees);
        Assertions.assertThat(feesXml).contains("""
                      <output id="output_0" name="Fee" typeRef="number">
                        <defaultOutputEntry id="output_default_0"><text>1.50</text></defaultOutputEntry>
                      </output>
                """);
        Assertions.assertThat(read(feesXml).outputs().getFirst().defaultValue()).isEqualTo("1.50");
    }

    @Test
    void an_engine_built_from_code_takes_a_default_output() {
        var rules = List.of(new Praeceptum(1, new PraeceptaInput(Map.of("country", "PT")), new PraeceptaOutput(Map.of("grade", "A"))));
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.RULE_ORDER)
                .input("country", input -> input.get("country"))
                .output(output -> output.values().get("grade"))
                .defaultOutput(new PraeceptaOutput(Map.of("grade", "C")))
                .rules(rules)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("country", "PT"))).containsExactly("A");
        Assertions.assertThat(engine.evaluate(Map.of("country", "FR"))).containsExactly("C");
    }

    @Test
    void the_default_output_is_converted_once_when_the_engine_is_built() {
        var conversions = new int[1];
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(read(APPROVAL.formatted("FIRST")))
                .inputs(PraeceptaInput::new)
                .output(output -> {
                    conversions[0]++;
                    return String.valueOf(output.values().get("Status"));
                })
                .build();
        int built = conversions[0];
        Assertions.assertThat(engine.evaluate(Map.of("score", "1"))).containsExactly("Declined");
        Assertions.assertThat(engine.evaluate(Map.of("score", "2"))).containsExactly("Declined");
        Assertions.assertThat(conversions[0]).isEqualTo(built);
    }

    @Test
    void a_default_output_that_reads_inputs_is_rejected() {
        var builder = PraeceptaEngine.<Map<String, String>, String>builder();
        var naming = new PraeceptaOutput(Map.of(), Map.of("grade", "country"));
        Assertions.assertThatThrownBy(() -> builder.defaultOutput(naming))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("A default output is a value; it cannot name an input or be an expression");
        var computing = new PraeceptaOutput(Map.of(), Map.of(), Map.of("grade", "\"x\" + country"));
        Assertions.assertThatThrownBy(() -> builder.defaultOutput(computing)).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static DecisionTable read(String xml) {
        return DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static PraeceptaEngine<Map<String, String>, Map<String, String>> mapEngine(DecisionTable table) {
        return PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
    }

    /** The table's one number output, summed, minimised, maximised or counted. */
    private static PraeceptaEngine<Map<String, String>, BigDecimal> numberEngine(DecisionTable table) {
        var column = table.outputs().getFirst().name();
        return PraeceptaEngine.<Map<String, String>, BigDecimal>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(new OutputStrategy<>() {
                    @Override
                    public BigDecimal transform(PraeceptaOutput output) {
                        return new BigDecimal(output.values().get(column));
                    }

                    @Override
                    public BigDecimal sum(BigDecimal a, BigDecimal b) {
                        return a.add(b);
                    }

                    @Override
                    public BigDecimal min(BigDecimal a, BigDecimal b) {
                        return a.min(b);
                    }

                    @Override
                    public BigDecimal max(BigDecimal a, BigDecimal b) {
                        return a.max(b);
                    }

                    @Override
                    public BigDecimal count(int matches) {
                        return BigDecimal.valueOf(matches);
                    }
                })
                .build();
    }

    /** A map of output values, which may be null: name, value, name, value... */
    private static Map<String, String> values(String... namesAndValues) {
        var values = new HashMap<String, String>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return values;
    }
}
