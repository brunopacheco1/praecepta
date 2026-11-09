/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/** Pins the writer's exact output, byte for byte, and how it reports invalid cells. */
class DmnWriterBehaviourTest {

    static DecisionTable table() {
        var inputs = List.of(
                new InputColumn("Country", ColumnType.STRING, "PT,ES"),
                new InputColumn("Age", ColumnType.NUMBER),
                new InputColumn("Day", ColumnType.DATE),
                new InputColumn("Member", ColumnType.BOOLEAN),
                new InputColumn("At", ColumnType.TIME),
                new InputColumn("Since", ColumnType.DATE_TIME));
        var outputs = List.of(new OutputColumn("Fee", ColumnType.NUMBER, List.of("1.50", "2")), new OutputColumn("Note", ColumnType.STRING));
        var first = new Praeceptum(1, new PraeceptaInput(Map.of("Country", "PT", "Age", "[18..65)", "Day", "> 2025-01-01",
                "Member", "true", "At", "time(\"08:30\")", "Since", "not(2025-01-31T08:30, null)")),
                new PraeceptaOutput(Map.of("Fee", " 1.50 ", "Note", "a & \"b\" <c>")));
        var second = new Praeceptum(2, new PraeceptaInput(Map.of("Country", "-", "Age", "", "Day", "-", "Member", "-", "At", "-", "Since", "-")),
                new PraeceptaOutput(Map.of("Fee", "2")));
        return new DecisionTable("1st \"Fee\" & <rate>", HitPolicy.RULE_ORDER, null, inputs, outputs, List.of(first, second));
    }

    @Test
    void writes_every_element_byte_for_byte() {
        Assertions.assertThat(DmnWriter.write(table())).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions_d_1st__Fee_____rate_" name="1st &quot;Fee&quot; &amp; &lt;rate&gt;" namespace="https://github.com/brunopacheco1/praecepta/d_1st__Fee_____rate_">
                  <inputData id="input_data_0" name="Country">
                    <variable id="input_variable_0" name="Country" typeRef="string"/>
                  </inputData>
                  <inputData id="input_data_1" name="Age">
                    <variable id="input_variable_1" name="Age" typeRef="number"/>
                  </inputData>
                  <inputData id="input_data_2" name="Day">
                    <variable id="input_variable_2" name="Day" typeRef="date"/>
                  </inputData>
                  <inputData id="input_data_3" name="Member">
                    <variable id="input_variable_3" name="Member" typeRef="boolean"/>
                  </inputData>
                  <inputData id="input_data_4" name="At">
                    <variable id="input_variable_4" name="At" typeRef="time"/>
                  </inputData>
                  <inputData id="input_data_5" name="Since">
                    <variable id="input_variable_5" name="Since" typeRef="dateTime"/>
                  </inputData>
                  <decision id="decision_d_1st__Fee_____rate_" name="1st &quot;Fee&quot; &amp; &lt;rate&gt;">
                    <variable id="decision_variable" name="1st &quot;Fee&quot; &amp; &lt;rate&gt;"/>
                    <informationRequirement id="requirement_0"><requiredInput href="#input_data_0"/></informationRequirement>
                    <informationRequirement id="requirement_1"><requiredInput href="#input_data_1"/></informationRequirement>
                    <informationRequirement id="requirement_2"><requiredInput href="#input_data_2"/></informationRequirement>
                    <informationRequirement id="requirement_3"><requiredInput href="#input_data_3"/></informationRequirement>
                    <informationRequirement id="requirement_4"><requiredInput href="#input_data_4"/></informationRequirement>
                    <informationRequirement id="requirement_5"><requiredInput href="#input_data_5"/></informationRequirement>
                    <decisionTable id="table" hitPolicy="RULE ORDER">
                      <input id="input_0" label="Country">
                        <inputExpression id="input_expression_0" typeRef="string"><text>Country</text></inputExpression>
                        <inputValues id="input_values_0"><text>"PT", "ES"</text></inputValues>
                      </input>
                      <input id="input_1" label="Age">
                        <inputExpression id="input_expression_1" typeRef="number"><text>Age</text></inputExpression>
                      </input>
                      <input id="input_2" label="Day">
                        <inputExpression id="input_expression_2" typeRef="date"><text>Day</text></inputExpression>
                      </input>
                      <input id="input_3" label="Member">
                        <inputExpression id="input_expression_3" typeRef="boolean"><text>Member</text></inputExpression>
                      </input>
                      <input id="input_4" label="At">
                        <inputExpression id="input_expression_4" typeRef="time"><text>At</text></inputExpression>
                      </input>
                      <input id="input_5" label="Since">
                        <inputExpression id="input_expression_5" typeRef="dateTime"><text>Since</text></inputExpression>
                      </input>
                      <output id="output_0" name="Fee" typeRef="number">
                        <outputValues id="output_values_0"><text>1.50, 2</text></outputValues>
                      </output>
                      <output id="output_1" name="Note" typeRef="string"/>
                      <rule id="rule_0">
                        <inputEntry id="input_entry_0_0"><text>"PT"</text></inputEntry>
                        <inputEntry id="input_entry_0_1"><text>[18..65)</text></inputEntry>
                        <inputEntry id="input_entry_0_2"><text>&gt; date("2025-01-01")</text></inputEntry>
                        <inputEntry id="input_entry_0_3"><text>true</text></inputEntry>
                        <inputEntry id="input_entry_0_4"><text>time("08:30:00")</text></inputEntry>
                        <inputEntry id="input_entry_0_5"><text>not(date and time("2025-01-31T08:30:00"), null)</text></inputEntry>
                        <outputEntry id="output_entry_0_0"><text>1.50</text></outputEntry>
                        <outputEntry id="output_entry_0_1"><text>"a &amp; \\"b\\" &lt;c&gt;"</text></outputEntry>
                      </rule>
                      <rule id="rule_1">
                        <inputEntry id="input_entry_1_0"><text>-</text></inputEntry>
                        <inputEntry id="input_entry_1_1"><text>-</text></inputEntry>
                        <inputEntry id="input_entry_1_2"><text>-</text></inputEntry>
                        <inputEntry id="input_entry_1_3"><text>-</text></inputEntry>
                        <inputEntry id="input_entry_1_4"><text>-</text></inputEntry>
                        <inputEntry id="input_entry_1_5"><text>-</text></inputEntry>
                        <outputEntry id="output_entry_1_0"><text>2</text></outputEntry>
                        <outputEntry id="output_entry_1_1"><text>null</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """);
    }

    @Test
    void writes_the_aggregation_after_the_hit_policy() {
        var table = new DecisionTable("t", HitPolicy.COLLECT, AggregationOperator.SUM, List.of(),
                List.of(new OutputColumn("o", ColumnType.NUMBER)), List.of());
        Assertions.assertThat(DmnWriter.write(table)).contains("<decisionTable id=\"table\" hitPolicy=\"COLLECT\" aggregation=\"SUM\">");
    }

    @Test
    void invalid_cells_and_values_are_reported_with_their_place() {
        var cell = new DecisionTable("t", HitPolicy.UNIQUE, null, List.of(new InputColumn("a", ColumnType.NUMBER)),
                List.of(new OutputColumn("o", ColumnType.STRING)),
                List.of(new Praeceptum(7, new PraeceptaInput(Map.of("a", "x")), new PraeceptaOutput(Map.of("o", "y")))));
        Assertions.assertThatThrownBy(() -> DmnWriter.write(cell))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 7, column 'a': Invalid cell 'x': 'x' is not a number");
        var value = new DecisionTable("t", HitPolicy.UNIQUE, null, List.of(), List.of(new OutputColumn("o", ColumnType.BOOLEAN)),
                List.of(new Praeceptum(3, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of("o", "TRUE")))));
        Assertions.assertThatThrownBy(() -> DmnWriter.write(value))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 3, output 'o': 'TRUE' is not a boolean");
        var allowed = new DecisionTable("t", HitPolicy.UNIQUE, null, List.of(new InputColumn("a", ColumnType.DATE, "[x..y]")),
                List.of(new OutputColumn("o", ColumnType.DATE, List.of("2025-02-30"))), List.of());
        Assertions.assertThatThrownBy(() -> DmnWriter.write(allowed))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageStartingWith("input values of column 'a': Invalid cell '[x..y]': ");
    }
}
