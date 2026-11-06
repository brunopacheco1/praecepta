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
                .inputs(io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput::new)
                .output(output -> output.values().get("tier"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-05-01")))
                .containsExactly("gold", "silver");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "date-time column        | <input><inputExpression typeRef=\"dateTime\"><text>at</text></inputExpression></input> | type 'dateTime' is not supported",
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
