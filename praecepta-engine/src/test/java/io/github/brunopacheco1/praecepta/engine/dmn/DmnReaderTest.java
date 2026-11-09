/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

class DmnReaderTest {

    record Customer(String country, Integer age, LocalDate since) {
    }

    @Test
    void reads_a_camunda_modeler_table() {
        var table = DmnReader.read(resource("camunda-modeler-discount.dmn"));

        Assertions.assertThat(table.name()).isEqualTo("Discount");
        Assertions.assertThat(table.hitPolicy()).isEqualTo(HitPolicy.RULE_ORDER);
        Assertions.assertThat(table.inputs()).containsExactly(
                new InputColumn("country", ColumnType.STRING, "\"PT\",\"ES\",\"FR\""),
                new InputColumn("customer.age", ColumnType.NUMBER),
                new InputColumn("since", ColumnType.DATE));
        Assertions.assertThat(table.outputs()).containsExactly(
                new OutputColumn("discount", ColumnType.NUMBER),
                new OutputColumn("tier", ColumnType.STRING, List.of("gold", "silver")));
        Assertions.assertThat(table.rules()).hasSize(2);
        Assertions.assertThat(table.rules().getFirst().output().values()).containsEntry("discount", "0.15").containsEntry("tier", "gold");
    }

    @Test
    void a_dmn_table_drives_the_engine() {
        var engine = PraeceptaEngine.<Customer, String>builder()
                .table(DmnReader.read(resource("camunda-modeler-discount.dmn")))
                .input("country", Customer::country)
                .numberInput("customer.age", Customer::age)
                .dateInput("since", Customer::since)
                .output(output -> output.values().get("tier"))
                .build();

        Assertions.assertThat(engine.evaluate(new Customer("PT", 30, LocalDate.of(2019, 1, 1)))).containsExactly("gold", "silver");
        Assertions.assertThat(engine.evaluate(new Customer("ES", 70, null))).containsExactly("silver");
        Assertions.assertThat(engine.evaluate(new Customer("FR", 30, null))).isEmpty();
        // the table's input value list is enforced
        Assertions.assertThatThrownBy(() -> engine.evaluate(new Customer("DE", 30, null))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void reads_dmn_1_1_and_picks_a_decision_by_name() {
        Assertions.assertThatThrownBy(() -> DmnReader.read(resource("dmn11-two-decisions.dmn")))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("2 decision tables [Carrier, Fee]");

        var carrier = DmnReader.read(resource("dmn11-two-decisions.dmn"), "Carrier");
        Assertions.assertThat(carrier.inputs()).containsExactly(new InputColumn("weight", ColumnType.NUMBER));
        // a single unnamed output is named after the decision
        Assertions.assertThat(carrier.outputs()).containsExactly(new OutputColumn("Carrier", ColumnType.STRING));

        var fee = DmnReader.read(resource("dmn11-two-decisions.dmn"), "Fee");
        Assertions.assertThat(fee.aggregation()).isEqualTo(AggregationOperator.SUM);
        var engine = PraeceptaEngine.<String, BigDecimal>builder()
                .table(fee)
                .input("zone", zone -> zone)
                .output(new OutputStrategy<>() {
                    @Override
                    public BigDecimal transform(PraeceptaOutput output) {
                        return new BigDecimal(output.values().get("fee"));
                    }

                    @Override
                    public BigDecimal sum(BigDecimal a, BigDecimal b) {
                        return a.add(b);
                    }
                })
                .build();
        Assertions.assertThat(engine.evaluate("islands")).containsExactly(new BigDecimal("17.5"));
    }

    @Test
    void the_table_must_be_bound_with_its_types() {
        var table = DmnReader.read(resource("camunda-modeler-discount.dmn"));
        Assertions.assertThatThrownBy(() -> PraeceptaEngine.<Customer, String>builder()
                        .table(table)
                        .input("country", Customer::country)
                        .input("customer.age", customer -> String.valueOf(customer.age()))
                        .dateInput("since", Customer::since)
                        .output(output -> output.values().get("tier"))
                        .build())
                .hasMessageContaining("column 'customer.age' is a NUMBER column in the table but is bound as STRING");
        Assertions.assertThatThrownBy(() -> PraeceptaEngine.<Customer, String>builder()
                        .table(table)
                        .input("country", Customer::country)
                        .output(output -> output.values().get("tier"))
                        .build())
                .hasMessageContaining("has no input binding");
    }

    @Test
    void map_based_inputs_take_the_table_types() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(DmnReader.read(resource("camunda-modeler-discount.dmn")))
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("tier"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-05-01")))
                .containsExactly("gold", "silver");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "duration column         | <input><inputExpression typeRef=\"dayTimeDuration\"><text>at</text></inputExpression></input> | type 'dayTimeDuration' is not supported",
            "zoned time cell         | <input><inputExpression typeRef=\"time\"><text>at</text></inputExpression></input>#<text>time(\"10:00:00Z\")</text> | rule 1, column 'at': Invalid cell",
            "variable in a cell      | <input><inputExpression typeRef=\"string\"><text>a</text></inputExpression></input>#<text>not(Complex.aString)</text> | rule 1, column 'a': Invalid cell 'not(Complex.aString)': 'Complex.aString' is a FEEL name",
            "computed input          | <input><inputExpression typeRef=\"number\"><text>age * 2</text></inputExpression></input> | input expression 'age * 2' is not supported",
            "unsupported cell        | <input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>#<text>&gt; abs(x)</text> | rule 1, column 'age'",
            "computed output         | <input><inputExpression typeRef=\"string\"><text>a</text></inputExpression></input>#-#<text>a + \"x\"</text> | rule 1, output 'result': 'a + \"x\"' is not supported",
    })
    void unsupported_dmn_is_rejected_with_its_location(String description, String fragment, String message) {
        var parts = fragment.split("#");
        var input = parts[0];
        var inputEntry = parts.length > 1 && !parts[1].equals("-") ? parts[1] : "<text>-</text>";
        var outputEntry = parts.length > 2 ? parts[2] : "<text>\"x\"</text>";
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="t" name="t"><decisionTable>
                    %s
                    <output name="result" typeRef="string"/>
                    <rule><inputEntry>%s</inputEntry><outputEntry>%s</outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(input, inputEntry, outputEntry);
        Assertions.assertThatThrownBy(() -> DmnReader.read(stream(xml)))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 't'")
                .hasMessageContaining(message);
    }

    @Test
    void count_aggregation_is_not_supported() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="t" name="t"><decisionTable hitPolicy="COLLECT" aggregation="COUNT">
                    <input><inputExpression typeRef="string"><text>a</text></inputExpression></input>
                    <output name="result" typeRef="number"/>
                  </decisionTable></decision>
                </definitions>""";
        Assertions.assertThatThrownBy(() -> DmnReader.read(stream(xml))).hasMessageContaining("aggregation 'COUNT' is not supported");
    }

    @Test
    void input_names_may_contain_spaces() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="t" name="Approval"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>Credit Score</text></inputExpression></input>
                    <input><inputExpression typeRef="string"><text>Applicant data.Employment status</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                    <rule><inputEntry><text>&gt;= 700</text></inputEntry><inputEntry><text>"EMPLOYED"</text></inputEntry>
                      <outputEntry><text>"approved"</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>"referred"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var table = DmnReader.read(stream(xml));
        Assertions.assertThat(table.inputs()).extracting(InputColumn::name)
                .containsExactly("Credit Score", "Applicant data.Employment status");
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("result"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("Credit Score", "720", "Applicant data.Employment status", "EMPLOYED")))
                .containsExactly("approved");
        Assertions.assertThat(DmnReader.read(stream(DmnWriter.write(table))).inputs()).isEqualTo(table.inputs());
    }

    @Test
    void columns_typed_by_an_item_definition_take_its_base_type_and_allowed_values() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <itemDefinition name="tBureauCallType">
                    <typeRef>string</typeRef>
                    <allowedValues><text>"FULL","MINI","NONE"</text></allowedValues>
                  </itemDefinition>
                  <itemDefinition name="tEligibility">
                    <typeRef>string</typeRef>
                    <allowedValues><text>"INELIGIBLE","ELIGIBLE"</text></allowedValues>
                  </itemDefinition>
                  <itemDefinition name="tScore">
                    <typeRef>number</typeRef>
                    <allowedValues><text>[0..999]</text></allowedValues>
                  </itemDefinition>
                  <itemDefinition name="tCreditScore"><typeRef>tScore</typeRef></itemDefinition>
                  <itemDefinition name="tStrategy" isCollection="false"><typeRef>feel:string</typeRef></itemDefinition>
                  <decision id="s" name="Strategy"><decisionTable>
                    <input><inputExpression typeRef="tBureauCallType"><text>Bureau call type</text></inputExpression></input>
                    <input><inputExpression typeRef="tEligibility"><text>Eligibility</text></inputExpression>
                      <inputValues><text>"ELIGIBLE"</text></inputValues></input>
                    <input><inputExpression typeRef="tCreditScore"><text>Credit score</text></inputExpression></input>
                    <output name="Strategy" typeRef="tStrategy"/>
                    <rule><inputEntry><text>"FULL","MINI"</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <inputEntry><text>&gt;= 600</text></inputEntry><outputEntry><text>"BUREAU"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var table = DmnReader.read(stream(xml));

        Assertions.assertThat(table.inputs()).containsExactly(
                new InputColumn("Bureau call type", ColumnType.STRING, "\"FULL\",\"MINI\",\"NONE\""), // the type's allowed values
                new InputColumn("Eligibility", ColumnType.STRING, "\"ELIGIBLE\""), // the input's own value list wins
                new InputColumn("Credit score", ColumnType.NUMBER, "[0..999]")); // inherited from the base item definition
        Assertions.assertThat(table.outputs()).containsExactly(new OutputColumn("Strategy", ColumnType.STRING));

        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("Strategy"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("Bureau call type", "MINI", "Eligibility", "ELIGIBLE", "Credit score", "700")))
                .containsExactly("BUREAU");
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("Bureau call type", "OTHER")))
                .isInstanceOf(InvalidInputException.class);
        Assertions.assertThatThrownBy(() -> engine.evaluate(Map.of("Credit score", "1000")))
                .isInstanceOf(InvalidInputException.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "structured type      | <itemDefinition name=\"tT\"><itemComponent name=\"a\"><typeRef>string</typeRef></itemComponent></itemDefinition> "
                    + "| type 'tT' is a structured item definition",
            "collection           | <itemDefinition name=\"tT\" isCollection=\"true\"><typeRef>string</typeRef></itemDefinition> "
                    + "| type 'tT' is a collection",
            "no base type         | <itemDefinition name=\"tT\"/> | type 'tT' is not supported; its item definition has no base type",
            "unsupported base     | <itemDefinition name=\"tT\"><typeRef>dayTimeDuration</typeRef></itemDefinition> "
                    + "| type 'dayTimeDuration' is not supported",
            "defined by itself    | <itemDefinition name=\"tT\"><typeRef>tU</typeRef></itemDefinition><itemDefinition name=\"tU\"><typeRef>tT</typeRef></itemDefinition> "
                    + "| item definition 'tT' is defined in terms of itself",
            "invalid allowed list | <itemDefinition name=\"tT\"><typeRef>number</typeRef><allowedValues><text>\"high\"</text></allowedValues></itemDefinition> "
                    + "| input 'a', allowed values of type 'tT'",
            "undefined type       | <itemDefinition name=\"tU\"><typeRef>string</typeRef></itemDefinition> | type 'tT' is not supported",
    })
    void unsupported_item_definitions_are_rejected(String description, String itemDefinitions, String message) {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  %s
                  <decision id="t" name="t"><decisionTable>
                    <input><inputExpression typeRef="tT"><text>a</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                  </decisionTable></decision>
                </definitions>""".formatted(itemDefinitions);
        Assertions.assertThatThrownBy(() -> DmnReader.read(stream(xml)))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 't', input 'a'")
                .hasMessageContaining(message);
    }

    @Test
    void long_chains_of_item_definitions_are_resolved_without_a_stack_overflow() {
        var chain = new StringBuilder("<itemDefinition name=\"t0\"><typeRef>number</typeRef></itemDefinition>");
        for (int i = 1; i <= 100_000; i++) {
            chain.append("<itemDefinition name=\"t%d\"><typeRef>t%d</typeRef></itemDefinition>".formatted(i, i - 1));
        }
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  %s
                  <decision id="t" name="t"><decisionTable>
                    <input><inputExpression typeRef="t100000"><text>a</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                  </decisionTable></decision>
                </definitions>""".formatted(chain);
        Assertions.assertThat(DmnReader.read(stream(xml)).inputs()).containsExactly(new InputColumn("a", ColumnType.NUMBER));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "age                          | true",
            "Credit Score                 | true",
            "Applicant data.Monthly income | true",
            "Rate 2                       | true",
            "is_ok?                       | true",
            "𝓐ge                       | true",
            "Ⅷ rate                    | false",
            "2nd rate                     | false",
            "a..b                         | false",
            "a. b                         | false",
            "a  b                         | false",
            "a.2b                         | false",
            "age * 2                      | false",
            "f(x)                         | false",
    })
    void feel_names_allow_single_spaces_between_words(String text, boolean name) {
        Assertions.assertThat(DmnReader.isName(text)).as(text).isEqualTo(name);
    }

    @Test
    void very_long_names_are_checked_without_a_stack_overflow() {
        Assertions.assertThat(DmnReader.isName("a".repeat(1_000_000))).isTrue();
        Assertions.assertThat(DmnReader.isName("a b.".repeat(250_000) + "c")).isTrue();
    }

    @Test
    void doctypes_and_external_entities_are_refused() {
        var xml = """
                <?xml version="1.0"?>
                <!DOCTYPE definitions [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" name="&secret;"/>""";
        Assertions.assertThatThrownBy(() -> DmnReader.read(stream(xml)))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Cannot read DMN model");
    }

    @Test
    void writer_output_is_read_back_with_hit_policy_value_lists_and_aggregation() {
        var fee = DmnReader.read(resource("dmn11-two-decisions.dmn"), "Fee");
        var again = DmnReader.read(stream(DmnWriter.write(fee)));
        Assertions.assertThat(again).isEqualTo(new io.github.brunopacheco1.praecepta.engine.beans.DecisionTable(
                "Fee", HitPolicy.COLLECT, AggregationOperator.SUM, fee.inputs(), fee.outputs(),
                again.rules()));
        Assertions.assertThat(again.rules()).extracting(rule -> rule.output().values().get("fee")).containsExactly("5", "12.5");

        var discount = DmnReader.read(resource("camunda-modeler-discount.dmn"));
        var written = DmnWriter.write(discount);
        Assertions.assertThat(written).contains("hitPolicy=\"RULE ORDER\"", "<outputValues", "<inputValues");
        Assertions.assertThat(DmnReader.read(stream(written)).outputs()).isEqualTo(discount.outputs());
    }

    private static InputStream resource(String name) {
        return DmnReaderTest.class.getResourceAsStream("/dmn/" + name);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
