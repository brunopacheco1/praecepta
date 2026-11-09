/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/** Pins how the reader names tables and columns, treats blank value lists and reads output entries. */
class DmnReaderBehaviourTest {

    @Test
    void a_decision_without_a_name_is_named_by_its_id_and_an_output_by_its_label() {
        var table = DmnReader.read(stream(model("", """
                <decision id="pricing"><decisionTable>
                  <input label="age"><inputExpression typeRef="number"/></input>
                  <output label="Price" typeRef="number"/><output name="Tier"/>
                </decisionTable></decision>""")));

        Assertions.assertThat(table.name()).isEqualTo("pricing");
        Assertions.assertThat(table.inputs()).containsExactly(new InputColumn("age", ColumnType.NUMBER));
        Assertions.assertThat(table.outputs()).containsExactly(
                new OutputColumn("Price", ColumnType.NUMBER), new OutputColumn("Tier", ColumnType.STRING));
    }

    @Test
    void blank_value_lists_count_as_none() {
        var table = DmnReader.read(stream(model("""
                <itemDefinition name="tAge"><typeRef>number</typeRef><allowedValues><text>[0..150]</text></allowedValues></itemDefinition>
                <itemDefinition name="tAdult"><typeRef>tns:tAge</typeRef><allowedValues><text> </text></allowedValues></itemDefinition>""", """
                <decision id="d" name="d"><decisionTable>
                  <input><inputExpression typeRef="tAdult"><text>age</text></inputExpression><inputValues><text> </text></inputValues></input>
                  <input><inputExpression typeRef="string"><text>country</text></inputExpression><inputValues/></input>
                  <output name="o"><outputValues><text>
                  </text></outputValues></output>
                </decisionTable></decision>""")));

        Assertions.assertThat(table.inputs()).containsExactly(
                new InputColumn("age", ColumnType.NUMBER, "[0..150]"), // from the base type, through a prefixed name
                new InputColumn("country", ColumnType.STRING));
        Assertions.assertThat(table.outputs()).containsExactly(new OutputColumn("o", ColumnType.STRING));
    }

    @Test
    void unquoted_entries_of_a_string_output_keep_the_text_of_their_literal() {
        var table = DmnReader.read(stream(model("", """
                <decision id="d" name="d"><decisionTable hitPolicy="RULE ORDER">
                  <input><inputExpression><text>a</text></inputExpression></input>
                  <output name="o"/>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>1.50</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>date("2025-01-31")</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>time("08:30")</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>date and time("2025-01-31T08:30")</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"quoted"</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text> null </text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry/></rule>
                </decisionTable></decision>""")));

        var values = table.rules().stream().map(rule -> rule.output().values().get("o")).toList();
        Assertions.assertThat(values).containsExactly("1.50", "true", "2025-01-31", "08:30:00", "2025-01-31T08:30:00", "quoted", null, null);
    }

    @Test
    void output_entries_that_are_not_literals_are_rejected() {
        var word = stream(model("", table("string", "PT")));
        Assertions.assertThatThrownBy(() -> DmnReader.read(word))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'd', rule 1, output 'o': 'PT' is not supported; output entries must be literals (quote text)");
        var list = stream(model("", table("number", "1, 2")));
        Assertions.assertThatThrownBy(() -> DmnReader.read(list))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'd', rule 1, output 'o': '1, 2' is not supported; output entries must be literals");
    }

    @Test
    void a_rule_keeps_its_input_entries_as_written() {
        var table = DmnReader.read(stream(model("", """
                <decision id="d" name="d"><decisionTable>
                  <input><inputExpression typeRef="number"><text>a</text></inputExpression></input>
                  <output name="o"/>
                  <rule><inputEntry><text>  [1..2), &gt; 5 </text></inputEntry><outputEntry><text>"x"</text></outputEntry></rule>
                </decisionTable></decision>""")));

        Assertions.assertThat(table.rules().getFirst().input().values()).isEqualTo(Map.of("a", "[1..2), > 5"));
        Assertions.assertThat(table.rules().getFirst().priority()).isEqualTo(1);
        Assertions.assertThat(table.outputs()).isEqualTo(List.of(new OutputColumn("o", ColumnType.STRING)));
    }

    @Test
    void decisions_without_a_table_are_not_counted() {
        var xml = model("", """
                <decision id="x" name="Lookup"><literalExpression><text>1</text></literalExpression></decision>
                """ + table("string", "\"a\""));
        Assertions.assertThat(DmnReader.read(stream(xml)).name()).isEqualTo("d");
        var named = stream(xml);
        Assertions.assertThatThrownBy(() -> DmnReader.read(named, "Lookup"))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("DMN model has no decision table named 'Lookup'; it has [d]");
        var none = stream(model("", "<decision id=\"x\" name=\"Lookup\"/>"));
        Assertions.assertThatThrownBy(() -> DmnReader.read(none))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("DMN model has no decision table");
    }

    private static String table(String outputType, String outputEntry) {
        return """
                <decision id="d" name="d"><decisionTable>
                  <input><inputExpression><text>a</text></inputExpression></input>
                  <output name="o" typeRef="%s"/>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                </decisionTable></decision>""".formatted(outputType, outputEntry);
    }

    private static String model(String itemDefinitions, String decisions) {
        return """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:tns="n" id="m" name="m" namespace="n">
                %s
                %s
                </definitions>""".formatted(itemDefinitions, decisions);
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
