/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/**
 * Inputs that hold lists (#25): a list column's cell holds when some element passes one of its tests,
 * and a cell that names a list input holds when the column's value is in that list.
 */
class ListInputsTest {

    record Customer(List<String> nationalities, List<Integer> ages) {
    }

    //   nationalities     → id
    // 1 "PT","ES"            iberian
    // 2 not("PT")            not-pt
    // 3 null                 none
    // 4 -                    any
    private static final List<Praeceptum> NATIONALITY_RULES = List.of(
            rule(1, Map.of("nationalities", "\"PT\",\"ES\""), "iberian"),
            rule(2, Map.of("nationalities", "not(\"PT\")"), "not-pt"),
            rule(3, Map.of("nationalities", "null"), "none"),
            rule(4, Map.of(), "any"));

    @Test
    void a_list_matches_a_cell_when_some_element_passes_one_of_its_tests() {
        var engine = byNationalities(HitPolicy.COLLECT);
        Assertions.assertThat(engine.evaluate(customer("PT", "BR"))).containsExactly("iberian", "any");
        Assertions.assertThat(engine.evaluate(customer("BR", "ES"))).containsExactly("iberian", "not-pt", "any");
        Assertions.assertThat(engine.evaluate(customer("BR"))).containsExactly("not-pt", "any");
    }

    @Test
    void a_missing_list_matches_only_any_value_and_null_and_an_empty_one_every_not() {
        var engine = byNationalities(HitPolicy.COLLECT);
        Assertions.assertThat(engine.evaluate(new Customer(null, null))).containsExactly("none", "any");
        Assertions.assertThat(engine.evaluate(customer())).containsExactly("not-pt", "any");
    }

    @Test
    void elements_must_be_values_within_the_declared_input_values() {
        var engine = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .listInput("nationalities", Customer::nationalities)
                .allowedValues("nationalities", List.of("PT", "ES", "BR"))
                .output(output -> output.values().get("id"))
                .rules(NATIONALITY_RULES)
                .build();
        Assertions.assertThat(engine.evaluate(customer("BR", "ES"))).containsExactly("iberian", "not-pt", "any");
        var french = customer("PT", "FR");
        Assertions.assertThatThrownBy(() -> engine.evaluate(french))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("nationalities=FR");
        var withNull = new Customer(Arrays.asList("PT", null), null);
        Assertions.assertThatThrownBy(() -> engine.evaluate(withNull))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("holds a missing value");
        var withBlank = customer("PT", " ");
        Assertions.assertThatThrownBy(() -> engine.evaluate(withBlank))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("holds a missing value");
    }

    @Test
    void lists_of_numbers_compare_each_element() {
        var engine = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .listInput("ages", ColumnType.NUMBER, Customer::ages)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, Map.of("ages", "< 18"), "minor"), rule(2, Map.of("ages", "not([18..65])"), "no-adult"),
                        rule(3, Map.of("ages", "65.0, 70"), "65-or-70")))
                .build();
        Assertions.assertThat(engine.evaluate(new Customer(null, List.of(10, 40)))).containsExactly("minor");
        Assertions.assertThat(engine.evaluate(new Customer(null, List.of(10, 70)))).containsExactly("minor", "no-adult", "65-or-70");
        Assertions.assertThat(engine.evaluate(new Customer(null, List.of(65)))).containsExactly("65-or-70");
        // an empty list fails every test, a missing one makes them unknown
        Assertions.assertThat(engine.evaluate(new Customer(null, List.of()))).containsExactly("no-adult");
        Assertions.assertThat(engine.evaluate(new Customer(null, null))).isEmpty();
    }

    @Test
    void elements_must_be_of_a_java_type_of_the_element_type() {
        record Values(List<?> values) {
        }
        var engine = PraeceptaEngine.<Values, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .listInput("ages", ColumnType.NUMBER, Values::values)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, Map.of("ages", "< 18"), "minor")))
                .build();
        // numbers of any class, and text, which is parsed as a number
        Assertions.assertThat(engine.evaluate(new Values(List.of(10L, 40.5, "12")))).containsExactly("minor");
        var date = new Values(List.of(40, LocalDate.of(2025, 1, 1)));
        Assertions.assertThatThrownBy(() -> engine.evaluate(date))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("holds a java.time.LocalDate, not a number");
    }

    @Test
    void map_based_inputs_read_lists_as_feel_lists() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .inputs(PraeceptaInput::new)
                .listColumnType("nationalities", ColumnType.STRING)
                .output(output -> output.values().get("id"))
                .rules(NATIONALITY_RULES)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[\"PT\", \"BR\"]"))).containsExactly("iberian", "any");
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "BR, ES"))).containsExactly("iberian", "not-pt", "any");
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[]"))).containsExactly("not-pt", "any");
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", " "))).containsExactly("none", "any");
        Assertions.assertThat(engine.evaluate(Map.of())).containsExactly("none", "any");
        var withNull = Map.of("nationalities", "[\"PT\", null]");
        Assertions.assertThatThrownBy(() -> engine.evaluate(withNull))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a list of string values");
        var withComparison = Map.of("nationalities", "[< 5]");
        Assertions.assertThatThrownBy(() -> engine.evaluate(withComparison))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a list of string values");
    }

    @Test
    void the_analysis_leaves_out_rules_that_test_lists() {
        // rules 1 and 2 overlap on ["PT", "BR"], which the column's classes of single values cannot show
        var engine = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, Map.of("nationalities", "PT"), "pt"), rule(2, Map.of("nationalities", "BR"), "br")))
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.OVERLAPPING_RULES)).isEmpty();
        Assertions.assertThat(engine.evaluate(customer("PT"))).containsExactly("pt");
    }

    @Test
    void an_output_cannot_name_a_list() {
        var builder = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of()),
                        new PraeceptaOutput(Map.of(), Map.of("id", "nationalities")))));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1: output 'id' names 'nationalities', which holds lists; an output holds one value");
    }

    // ---- cells that name a list (DMN) ------------------------------------------------------------

    record Patient(String symptom, List<String> fluSymptoms) {
    }

    //   Symptom                           → id
    // 1 "cough", "sore throat"               cold
    // 2 Flu Symptoms                         flu
    // 3 not(Flu Symptoms)                    not-flu
    private static final DecisionTable SYMPTOMS = new DecisionTable("symptoms", HitPolicy.COLLECT, null,
            List.of(new DecisionTable.InputColumn("Symptom", ColumnType.STRING)),
            List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
            List.of(rule(1, Map.of("Symptom", "\"cough\", \"sore throat\""), "cold"), rule(2, Map.of("Symptom", "Flu Symptoms"), "flu"),
                    rule(3, Map.of("Symptom", "not(Flu Symptoms)"), "not-flu")),
            true, Set.of("Flu Symptoms"));

    @Test
    void a_cell_naming_a_list_holds_when_the_value_is_in_it() {
        var engine = PraeceptaEngine.<Patient, String>builder()
                .table(SYMPTOMS)
                .input("Symptom", Patient::symptom)
                .listInput("Flu Symptoms", Patient::fluSymptoms)
                .output(output -> output.values().get("id"))
                .build();
        var flu = List.of("fever", "cough");
        Assertions.assertThat(engine.evaluate(new Patient("cough", flu))).containsExactly("cold", "flu");
        Assertions.assertThat(engine.evaluate(new Patient("fever", flu))).containsExactly("flu");
        Assertions.assertThat(engine.evaluate(new Patient("rash", flu))).containsExactly("not-flu");
        Assertions.assertThat(engine.evaluate(new Patient("rash", List.of()))).containsExactly("not-flu");
        // a missing value is in no list; a missing list is compared as a value, which only a missing value equals
        Assertions.assertThat(engine.evaluate(new Patient(null, flu))).containsExactly("not-flu");
        Assertions.assertThat(engine.evaluate(new Patient(null, null))).containsExactly("flu");
    }

    @Test
    void map_based_inputs_take_the_tables_list_inputs() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(SYMPTOMS)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("Symptom", "fever", "Flu Symptoms", "[\"fever\", \"cough\"]"))).containsExactly("flu");
    }

    @Test
    void a_list_input_must_be_bound_as_a_list() {
        var builder = PraeceptaEngine.<Patient, String>builder()
                .table(SYMPTOMS)
                .input("Symptom", Patient::symptom)
                .input("Flu Symptoms", Patient::symptom)
                .output(output -> output.values().get("id"));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("input 'Flu Symptoms' holds lists in the table; bind it with listInput(...)");
    }

    @Test
    void an_input_of_single_values_cannot_be_bound_as_a_list() {
        // a column of the table
        var column = PraeceptaEngine.<Patient, String>builder()
                .table(SYMPTOMS)
                .listInput("Symptom", Patient::fluSymptoms)
                .listInput("Flu Symptoms", Patient::fluSymptoms)
                .output(output -> output.values().get("id"));
        Assertions.assertThatThrownBy(column::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("input 'Symptom' holds single values in the table but is bound with listInput(...)");
        // an input a cell compares with, which would otherwise turn equality into membership
        var limit = new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("s", ColumnType.STRING)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)), List.of(rule(1, Map.of("s", "limit"), "x")), true);
        var named = PraeceptaEngine.<Patient, String>builder()
                .table(limit)
                .input("s", Patient::symptom)
                .listInput("limit", Patient::fluSymptoms)
                .output(output -> output.values().get("id"));
        Assertions.assertThatThrownBy(named::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("input 'limit' holds single values in the table but is bound with listInput(...)");
        // with map-based inputs, listColumnType(...) cannot make one a list either
        var mapBased = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(limit)
                .inputs(PraeceptaInput::new)
                .listColumnType("limit", ColumnType.STRING)
                .output(output -> output.values().get("id"));
        Assertions.assertThatThrownBy(mapBased::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("input 'limit' holds single values in the table; listColumnType(...) cannot make it a list");
    }

    @Test
    void a_cell_cannot_compare_with_a_list() {
        var table = new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("n", ColumnType.NUMBER)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)), List.of(rule(1, Map.of("n", "> limits"), "x")),
                true, Set.of("limits"));
        var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, column 'n': compares with 'limits', which holds lists; a cell can only ask whether the value is in it, "
                        + "as 'limits' does");
    }

    // ---- DMN ---------------------------------------------------------------------------------

    @Test
    void list_columns_and_named_lists_survive_a_dmn_round_trip() {
        var table = new DecisionTable("customers", HitPolicy.COLLECT, null,
                List.of(new DecisionTable.InputColumn("nationalities", ColumnType.STRING, "\"PT\",\"ES\",\"BR\""),
                        new DecisionTable.InputColumn("ages", ColumnType.NUMBER)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
                List.of(rule(1, Map.of("nationalities", "PT,ES,null", "ages", ">= 18, [1..5)"), "a"),
                        rule(2, Map.of("nationalities", "not(PT)"), "b")),
                false, Set.of("nationalities", "ages"));
        var xml = DmnWriter.write(table);
        Assertions.assertThat(xml)
                .contains("<itemDefinition name=\"input_0_item\">", "<allowedValues><text>\"PT\", \"ES\", \"BR\"</text></allowedValues>",
                        "<itemDefinition name=\"input_0_list\" isCollection=\"true\">", "<typeRef>input_0_item</typeRef>",
                        "<inputExpression id=\"input_expression_0\" typeRef=\"input_0_list\">")
                .contains("<text>list contains(?, \"PT\") or list contains(?, \"ES\") or ? = null</text>",
                        "<text>some x in ? satisfies x &gt;= 18 or some x in ? satisfies x in [1..5)</text>",
                        "<text>not(list contains(?, \"PT\"))</text>")
                .doesNotContain("inputValues");

        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(read.listInputs()).containsExactlyInAnyOrder("nationalities", "ages");
        Assertions.assertThat(read.inputs().getFirst().allowedValues()).isEqualTo("\"PT\", \"ES\", \"BR\"");
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);

        var engine = PraeceptaEngine.<Customer, String>builder()
                .table(read)
                .listInput("nationalities", Customer::nationalities)
                .listInput("ages", ColumnType.NUMBER, Customer::ages)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(new Customer(List.of("ES"), List.of(3)))).containsExactly("a", "b");
        Assertions.assertThat(engine.evaluate(new Customer(List.of("PT"), List.of(10)))).isEmpty();
        Assertions.assertThat(engine.evaluate(new Customer(null, List.of(20)))).containsExactly("a");
    }

    @Test
    void dmn_reads_a_named_list_from_its_input_data() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <itemDefinition name="tStrings" isCollection="true"><typeRef>string</typeRef></itemDefinition>
                  <inputData name="Flu Symptoms" id="flu"><variable name="Flu Symptoms" typeRef="tStrings"/></inputData>
                  <inputData name="Symptom" id="s"><variable name="Symptom" typeRef="string"/></inputData>
                  <decision id="a" name="Analysis"><decisionTable hitPolicy="COLLECT">
                    <input><inputExpression typeRef="string"><text>Symptom</text></inputExpression></input>
                    <output name="id" typeRef="string"/>
                    <rule><inputEntry><text>Flu Symptoms</text></inputEntry><outputEntry><text>"flu"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var table = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(table.listInputs()).containsExactly("Flu Symptoms");
        Assertions.assertThat(DmnWriter.write(table))
                .contains("<itemDefinition name=\"input_1_list\" isCollection=\"true\">",
                        "<inputData id=\"input_data_1\" name=\"Flu Symptoms\">",
                        "<variable id=\"input_variable_1\" name=\"Flu Symptoms\" typeRef=\"input_1_list\"/>");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static PraeceptaEngine<Customer, String> byNationalities(HitPolicy policy) {
        return PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(policy)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(NATIONALITY_RULES)
                .build();
    }

    private static Customer customer(String... nationalities) {
        return new Customer(new ArrayList<>(List.of(nationalities)), null);
    }

    private static Praeceptum rule(int id, Map<String, String> cells, String output) {
        return new Praeceptum(id, new PraeceptaInput(new HashMap<>(cells)), new PraeceptaOutput(Map.of("id", output)));
    }
}
