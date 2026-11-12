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
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

class DmnReaderTest {

    /** A decision 't' with one input column 'a' of the given type, and no rules. */
    private static final String DECISION_ON_TYPE = """
            <decision id="t" name="t"><decisionTable>
              <input><inputExpression typeRef="%s"><text>a</text></inputExpression></input>
              <output name="result" typeRef="string"/>
            </decisionTable></decision>""";

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
        var unlistedCountry = new Customer("DE", 30, null);
        Assertions.assertThatThrownBy(() -> engine.evaluate(unlistedCountry)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void a_model_with_several_decision_tables_needs_a_decision_name() {
        var twoDecisions = resource("dmn11-two-decisions.dmn");
        Assertions.assertThatThrownBy(() -> DmnReader.read(twoDecisions))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("2 decision tables [Carrier, Fee]");
    }

    @Test
    void read_all_reads_every_decision_table_in_document_order() {
        var tables = DmnReader.readAll(resource("dmn11-two-decisions.dmn"));
        Assertions.assertThat(tables).extracting(DecisionTable::name).containsExactly("Carrier", "Fee");
        Assertions.assertThat(tables.getLast()).isEqualTo(DmnReader.read(resource("dmn11-two-decisions.dmn"), "Fee"));
        Assertions.assertThat(DmnReader.readAll(resource("camunda-modeler-discount.dmn"))).singleElement()
                .isEqualTo(DmnReader.read(resource("camunda-modeler-discount.dmn")));

        var noTables = new ByteArrayInputStream("""
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <inputData id="x" name="x"/>
                </definitions>""".getBytes(StandardCharsets.UTF_8));
        Assertions.assertThat(DmnReader.readAll(noTables)).isEmpty();
        var notDmn = new ByteArrayInputStream("<html/>".getBytes(StandardCharsets.UTF_8));
        Assertions.assertThatThrownBy(() -> DmnReader.readAll(notDmn)).isInstanceOf(InvalidDecisionTableException.class);
    }

    @Test
    void reads_dmn_1_1_and_picks_a_decision_by_name() {
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
    }

    @Test
    void every_column_of_the_table_must_be_bound() {
        var table = DmnReader.read(resource("camunda-modeler-discount.dmn"));
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

    /**
     * Each case is a decision with one input column and one rule. An empty input entry stands for
     * {@code -} and an empty output entry for {@code "x"}, which are both supported.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "untyped duration   | duration        | at      |                      |         | type 'duration' is not supported",
            "time with zone id  | time            | at      | time(\"10:00:00@Europe/Paris\") | | rule 1, column 'at': Invalid cell",
            "computed input     | number          | age * 2 |                      |         | input expression 'age * 2' is not supported",
            "unsupported cell   | number          | age     | &gt; abs(x)          |         | rule 1, column 'age'",
            "function output    | string          | a       |                      | upper case(a) | rule 1, output 'result': 'upper case(a)' is not supported",
    })
    void unsupported_dmn_is_rejected_with_its_location(String description, String typeRef, String inputExpression,
            String inputEntry, String outputEntry, String message) {
        var xml = model("""
                  <decision id="t" name="t"><decisionTable>
                    <input><inputExpression typeRef="%s"><text>%s</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                    <rule><inputEntry><text>%s</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                  </decisionTable></decision>""".formatted(typeRef, inputExpression,
                inputEntry == null ? "-" : inputEntry, outputEntry == null ? "\"x\"" : outputEntry));
        var dmn = stream(xml);
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 't'")
                .hasMessageContaining(message);
    }

    /** A collection of strings, and one of numbers. */
    private static final String LIST_TYPES = """
            <itemDefinition name="tStrings" isCollection="true"><typeRef>string</typeRef></itemDefinition>
            <itemDefinition name="tNumbers" isCollection="true"><typeRef>number</typeRef></itemDefinition>""";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "input values of a list   | <input><inputExpression typeRef=\"tStrings\"><text>a</text></inputExpression>"
                    + "<inputValues><text>\"PT\"</text></inputValues></input> | <output name=\"result\" typeRef=\"string\"/> | `\"x\"` "
                    + "| input 'a': input values of a list are not supported",
            "a list output            | <input><inputExpression typeRef=\"string\"><text>a</text></inputExpression></input> "
                    + "| <output name=\"result\" typeRef=\"tStrings\"/> | `\"x\"` "
                    + "| output 'result': type 'tStrings' is a collection, which is not supported for outputs",
            "an output naming a list  | <input><inputExpression typeRef=\"tStrings\"><text>a</text></inputExpression></input> "
                    + "| <output name=\"result\" typeRef=\"string\"/> | a "
                    + "| rule 1, output 'result': names input 'a', a list, which outputs cannot hold",
    })
    void lists_where_dmn_tables_cannot_hold_them_are_rejected(String description, String input, String output, String outputEntry,
            String message) {
        var dmn = stream(model(LIST_TYPES + """
                  <decision id="t" name="t"><decisionTable>
                    %s
                    %s
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                  </decisionTable></decision>""".formatted(input, output, outputEntry)));
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 't'")
                .hasMessageContaining(message);
    }

    /** A decision whose string column 'Symptom' compares with the input 'Flu Symptoms', of the given type, and outputs {@code output}. */
    private static String symptomTable(String inputDataType, String output) {
        return model(LIST_TYPES + """
                  <inputData id="i" name="Flu Symptoms"><variable name="Flu Symptoms" typeRef="%s"/></inputData>
                  <decision id="t" name="t"><decisionTable>
                    <input><inputExpression typeRef="string"><text>Symptom</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                    <rule><inputEntry><text>Flu Symptoms</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                  </decisionTable></decision>""".formatted(inputDataType, output));
    }

    @Test
    void a_named_list_input_must_hold_elements_of_the_type_of_the_column_that_names_it() {
        var dmn = stream(symptomTable("tNumbers", "\"flu\""));
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 't': a string column compares with 'Flu Symptoms', a list of number");
    }

    @Test
    void a_named_input_whose_type_cannot_be_resolved_holds_single_values() {
        var table = DmnReader.read(stream(symptomTable("tUnknown", "\"flu\"")));
        Assertions.assertThat(table.listInputs()).isEmpty();
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("result"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("Symptom", "cough", "Flu Symptoms", "cough"))).containsExactly("flu");
        Assertions.assertThat(engine.evaluate(Map.of("Symptom", "cough", "Flu Symptoms", "fever"))).isEmpty();
    }

    @Test
    void outputs_may_read_the_inputs_the_cells_compare_with_as_the_engine_does() {
        var xml = model("""
                  <decision id="t" name="t"><decisionTable hitPolicy="COLLECT">
                    <input><inputExpression typeRef="number"><text>amount</text></inputExpression></input>
                    <output name="headroom" typeRef="number"/>
                    <output name="cap" typeRef="number"/>
                    <rule><inputEntry><text>&lt;= limit</text></inputEntry>
                      <outputEntry><text>limit - amount</text></outputEntry><outputEntry><text>limit</text></outputEntry></rule>
                  </decisionTable></decision>""");
        var table = DmnReader.read(stream(xml));
        var output = table.rules().getFirst().output();
        Assertions.assertThat(output.expressions()).containsExactly(Map.entry("headroom", "limit - amount"));
        Assertions.assertThat(output.references()).containsExactly(Map.entry("cap", "limit"));

        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("amount", "30", "limit", "100.50")))
                .containsExactly(Map.of("headroom", "70.5", "cap", "100.50"));
        Assertions.assertThat(DmnReader.read(stream(DmnWriter.write(table))).rules()).isEqualTo(table.rules());
    }

    @Test
    void outputs_may_not_read_a_named_list_input() {
        var dmn = stream(symptomTable("tStrings", "Flu Symptoms + \"!\""));
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 't', rule 1, output 'result': 'Flu Symptoms + \"!\"' names 'Flu Symptoms', "
                        + "which is not one of the table's inputs of single values");
        var naming = stream(symptomTable("tStrings", "Flu Symptoms"));
        Assertions.assertThatThrownBy(() -> DmnReader.read(naming))
                .hasMessage("Decision 't', rule 1, output 'result': names input 'Flu Symptoms', a list, which outputs cannot hold");
    }

    @Test
    void reads_count_aggregation() {
        var xml = model("""
                  <decision id="t" name="t"><decisionTable hitPolicy="COLLECT" aggregation="COUNT">
                    <input><inputExpression typeRef="string"><text>a</text></inputExpression></input>
                    <output name="result" typeRef="number"/>
                  </decisionTable></decision>""");
        Assertions.assertThat(DmnReader.read(stream(xml)).aggregation()).isEqualTo(AggregationOperator.COUNT);
    }

    @Test
    void unknown_aggregation_is_not_supported() {
        var xml = model("""
                  <decision id="t" name="t"><decisionTable hitPolicy="COLLECT" aggregation="AVG">
                    <input><inputExpression typeRef="string"><text>a</text></inputExpression></input>
                    <output name="result" typeRef="number"/>
                  </decisionTable></decision>""");
        var dmn = stream(xml);
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .hasMessageContaining("aggregation 'AVG' is not supported (SUM, MIN, MAX and COUNT are)");
    }

    @Test
    void input_names_may_contain_spaces() {
        var xml = model("""
                  <decision id="t" name="Approval"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>Credit Score</text></inputExpression></input>
                    <input><inputExpression typeRef="string"><text>Applicant data.Employment status</text></inputExpression></input>
                    <output name="result" typeRef="string"/>
                    <rule><inputEntry><text>&gt;= 700</text></inputEntry><inputEntry><text>"EMPLOYED"</text></inputEntry>
                      <outputEntry><text>"approved"</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>"referred"</text></outputEntry></rule>
                  </decisionTable></decision>""");
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
        var xml = model("""
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
                  </decisionTable></decision>""");
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
        var unknownCallType = Map.of("Bureau call type", "OTHER");
        Assertions.assertThatThrownBy(() -> engine.evaluate(unknownCallType)).isInstanceOf(InvalidInputException.class);
        var scoreOutOfRange = Map.of("Credit score", "1000");
        Assertions.assertThatThrownBy(() -> engine.evaluate(scoreOutOfRange)).isInstanceOf(InvalidInputException.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "structured type      | <itemDefinition name=\"tT\"><itemComponent name=\"a\"><typeRef>string</typeRef></itemComponent></itemDefinition> "
                    + "| type 'tT' is a structured item definition",
            "list of lists        | <itemDefinition name=\"tT\" isCollection=\"true\"><typeRef>tU</typeRef></itemDefinition>"
                    + "<itemDefinition name=\"tU\" isCollection=\"true\"><typeRef>string</typeRef></itemDefinition> "
                    + "| type 'tU' is a collection of collections",
            "constrained list     | <itemDefinition name=\"tT\" isCollection=\"true\"><typeRef>string</typeRef>"
                    + "<allowedValues><text>\"a\"</text></allowedValues></itemDefinition> "
                    + "| type 'tT' constrains a collection as a whole",
            "no base type         | <itemDefinition name=\"tT\"/> | type 'tT' is not supported; its item definition has no base type",
            "unsupported base     | <itemDefinition name=\"tT\"><typeRef>context</typeRef></itemDefinition> "
                    + "| type 'context' is not supported",
            "defined by itself    | <itemDefinition name=\"tT\"><typeRef>tU</typeRef></itemDefinition><itemDefinition name=\"tU\"><typeRef>tT</typeRef></itemDefinition> "
                    + "| item definition 'tT' is defined in terms of itself",
            "invalid allowed list | <itemDefinition name=\"tT\"><typeRef>number</typeRef><allowedValues><text>\"high\"</text></allowedValues></itemDefinition> "
                    + "| input 'a', allowed values of type 'tT'",
            "undefined type       | <itemDefinition name=\"tU\"><typeRef>string</typeRef></itemDefinition> | type 'tT' is not supported",
    })
    void unsupported_item_definitions_are_rejected(String description, String itemDefinitions, String message) {
        var dmn = stream(model(itemDefinitions + DECISION_ON_TYPE.formatted("tT")));
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
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
        var xml = model(chain + DECISION_ON_TYPE.formatted("t100000"));
        Assertions.assertThat(DmnReader.read(stream(xml)).inputs()).containsExactly(new InputColumn("a", ColumnType.NUMBER));
    }

    @Test
    void doctypes_and_external_entities_are_refused() {
        var xml = """
                <?xml version="1.0"?>
                <!DOCTYPE definitions [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" name="&secret;"/>""";
        var dmn = stream(xml);
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Cannot read DMN model");
    }

    @Test
    void writer_output_is_read_back_with_hit_policy_value_lists_and_aggregation() {
        var fee = DmnReader.read(resource("dmn11-two-decisions.dmn"), "Fee");
        var again = DmnReader.read(stream(DmnWriter.write(fee)));
        Assertions.assertThat(again).isEqualTo(new DecisionTable(
                "Fee", HitPolicy.COLLECT, AggregationOperator.SUM, fee.inputs(), fee.outputs(), again.rules(), true));
        Assertions.assertThat(again.rules()).extracting(rule -> rule.output().values().get("fee")).containsExactly("5", "12.5");

        var discount = DmnReader.read(resource("camunda-modeler-discount.dmn"));
        var written = DmnWriter.write(discount);
        Assertions.assertThat(written).contains("hitPolicy=\"RULE ORDER\"", "<outputValues", "<inputValues");
        Assertions.assertThat(DmnReader.read(stream(written)).outputs()).isEqualTo(discount.outputs());
    }

    /**
     * DMN 1.5, Table 34: "The OutputClause of a single output decision table SHALL NOT specify a typeRef"; the
     * type is then the decision's result's: the table's typeRef, else the decision variable's, the item type of a
     * list for a multi-hit table, and nothing for COUNT, whose result is the number of outputs.
     */
    @ParameterizedTest(name = "{0} {1}, table ''{2}'', variable ''{3}'': {4}")
    @CsvSource(delimiter = '|', value = {
            "FIRST     |       | number   |          | NUMBER",
            "FIRST     |       |          | date     | DATE",
            "FIRST     |       | string   | number   | STRING",
            "FIRST     |       |          | tAmount  | NUMBER",
            "COLLECT   | SUM   | number   |          | NUMBER",
            "COLLECT   | MAX   |          | tAmount  | NUMBER",
            "COLLECT   | COUNT | number   | number   | STRING",
            "COLLECT   |       |          | tAmounts | NUMBER",
            "RULE ORDER|       |          | tAmounts | NUMBER",
            // a type that says nothing of one output leaves it untyped
            "COLLECT   |       |          | number   | STRING",
            "FIRST     |       |          | tAmounts | STRING",
            "FIRST     |       |          | tPerson  | STRING",
            "FIRST     |       |          |          | STRING",
    })
    void a_single_outputs_type_is_its_decisions(String hitPolicy, String aggregation, String tableType, String variableType,
            ColumnType expected) {
        var xml = model("""
                <itemDefinition name="tAmount"><typeRef>number</typeRef></itemDefinition>
                <itemDefinition name="tAmounts" isCollection="true"><typeRef>tAmount</typeRef></itemDefinition>
                <itemDefinition name="tPerson"><itemComponent name="age"><typeRef>number</typeRef></itemComponent></itemDefinition>
                <decision id="d1" name="Amount">%s
                  <decisionTable hitPolicy="%s"%s%s>
                    <input><inputExpression typeRef="number"><text>x</text></inputExpression></input>
                    <output/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>null</text></outputEntry></rule>
                  </decisionTable>
                </decision>""".formatted(variableType == null ? "" : "<variable name=\"Amount\" typeRef=\"" + variableType + "\"/>",
                hitPolicy, aggregation == null ? "" : " aggregation=\"" + aggregation + "\"",
                tableType == null ? "" : " typeRef=\"" + tableType + "\""));
        Assertions.assertThat(DmnReader.read(stream(xml)).outputs()).singleElement().extracting(OutputColumn::type).isEqualTo(expected);
    }

    /** An output entry is read with that type: a string where the decision says number is rejected. */
    @Test
    void a_single_output_typed_by_its_decision_checks_its_entries() {
        var xml = model("""
                <decision id="d1" name="Amount"><variable name="Amount" typeRef="number"/>
                  <decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>x</text></inputExpression></input>
                    <output/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"one"</text></outputEntry></rule>
                  </decisionTable>
                </decision>""");
        var dmn = stream(xml);
        Assertions.assertThatThrownBy(() -> DmnReader.read(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 'Amount', rule 1, output 'Amount'");
    }

    /** A DMN 1.3 model holding these elements: item definitions and decisions. */
    private static String model(String elements) {
        return """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                %s
                </definitions>""".formatted(elements);
    }

    private static InputStream resource(String name) {
        return DmnReaderTest.class.getResourceAsStream("/dmn/" + name);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
