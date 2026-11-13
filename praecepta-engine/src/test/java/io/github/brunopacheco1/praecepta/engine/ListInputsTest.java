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
        // a missing element is no value, so the declared values allow it
        Assertions.assertThat(engine.evaluate(new Customer(Arrays.asList("BR", null), null))).containsExactly("not-pt", "any");
    }

    // ---- missing elements (#84) --------------------------------------------------------------

    //   nationalities                                       → id
    // 1 list contains(?, "PT")                                 pt
    // 2 not(list contains(?, "PT"))                            not-pt
    // 3 list contains(?, null)                                 has-null
    // 4 not(list contains(?, null))                            no-null
    // 5 ? = null                                               no-list
    private static final DecisionTable NATIONALITIES_FEEL = new DecisionTable("nationalities", HitPolicy.COLLECT, null,
            List.of(new DecisionTable.InputColumn("nationalities", ColumnType.STRING)),
            List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
            List.of(rule(1, Map.of("nationalities", "list contains(?, \"PT\")"), "pt"),
                    rule(2, Map.of("nationalities", "not(list contains(?, \"PT\"))"), "not-pt"),
                    rule(3, Map.of("nationalities", "list contains(?, null)"), "has-null"),
                    rule(4, Map.of("nationalities", "not(list contains(?, null))"), "no-null"),
                    rule(5, Map.of("nationalities", "? = null"), "no-list")),
            true, Set.of("nationalities"));

    //   ages                                              → id
    // 1 some x in ? satisfies x >= 18                        adult
    // 2 not(some x in ? satisfies x >= 18)                   no-adult
    private static final DecisionTable AGES_FEEL = new DecisionTable("ages", HitPolicy.COLLECT, null,
            List.of(new DecisionTable.InputColumn("ages", ColumnType.NUMBER)),
            List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
            List.of(rule(1, Map.of("ages", "some x in ? satisfies x >= 18"), "adult"),
                    rule(2, Map.of("ages", "not(some x in ? satisfies x >= 18)"), "no-adult")),
            true, Set.of("ages"));

    /** The table of #84: FEEL's three-valued answers for a list that holds a missing element (DMN 1.5, Tables 49, 50 and 75). */
    @Test
    void a_missing_element_is_false_for_a_literal_and_unknown_for_a_comparison() {
        var nationalities = PraeceptaEngine.<Customer, String>builder()
                .table(NATIONALITIES_FEEL)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .build();
        // list contains(["PT", null], "PT") is true; null = "PT" is false, so ["BR", null] holds no "PT"
        Assertions.assertThat(nationalities.evaluate(new Customer(Arrays.asList("PT", null), null))).containsExactly("pt", "has-null");
        Assertions.assertThat(nationalities.evaluate(new Customer(Arrays.asList("BR", null), null))).containsExactly("not-pt", "has-null");
        Assertions.assertThat(nationalities.evaluate(customer("BR"))).containsExactly("not-pt", "no-null");
        // ? = null asks about the list, which is there even when it holds only a missing element
        Assertions.assertThat(nationalities.evaluate(new Customer(Arrays.asList((String) null), null))).containsExactly("not-pt", "has-null");
        // a missing list: list contains(null, x) is unknown, so only ? = null holds
        Assertions.assertThat(nationalities.evaluate(new Customer(null, null))).containsExactly("no-list");

        var ages = PraeceptaEngine.<Customer, String>builder()
                .table(AGES_FEEL)
                .listInput("ages", ColumnType.NUMBER, Customer::ages)
                .output(output -> output.values().get("id"))
                .build();
        // some x in [20, null] satisfies x >= 18: true or null, which is true
        Assertions.assertThat(ages.evaluate(new Customer(null, Arrays.asList(20, null)))).containsExactly("adult");
        // some x in [10, null] satisfies x >= 18: false or null, which is null, and so is its not(...)
        Assertions.assertThat(ages.evaluate(new Customer(null, Arrays.asList(10, null)))).isEmpty();
        Assertions.assertThat(ages.evaluate(new Customer(null, List.of(10)))).containsExactly("no-adult");
    }

    @Test
    void a_blank_element_is_a_missing_one() {
        var engine = byNationalities(HitPolicy.COLLECT);
        Assertions.assertThat(engine.evaluate(customer("BR", " "))).containsExactly("not-pt", "any");
        // Praecepta's null asks whether the list is missing, not whether an element is
        Assertions.assertThat(engine.evaluate(new Customer(Arrays.asList((String) null), null))).containsExactly("not-pt", "any");
    }

    @Test
    void map_based_inputs_read_null_in_a_list_as_a_missing_element() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(NATIONALITIES_FEEL)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[\"PT\", null]"))).containsExactly("pt", "has-null");
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[null]"))).containsExactly("not-pt", "has-null");
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "BR, null"))).containsExactly("not-pt", "has-null");
        // the text null alone is no list: a missing list is null or blank
        var bare = Map.of("nationalities", "null");
        Assertions.assertThatThrownBy(() -> engine.evaluate(bare))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a list of string values");
    }

    @Test
    void list_contains_null_survives_a_dmn_round_trip() {
        var xml = DmnWriter.write(NATIONALITIES_FEEL);
        Assertions.assertThat(xml).contains("<text>list contains(?, null)</text>", "<text>not(list contains(?, null))</text>");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(read.rules()).extracting(rule -> read.cell(rule, read.inputs().getFirst()))
                .containsExactlyElementsOf(NATIONALITIES_FEEL.rules().stream()
                        .map(rule -> NATIONALITIES_FEEL.cell(rule, NATIONALITIES_FEEL.inputs().getFirst())).toList());
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);
    }

    @Test
    void a_missing_value_is_in_a_named_list_that_holds_a_missing_element() {
        var engine = PraeceptaEngine.<Patient, String>builder()
                .table(SYMPTOMS)
                .input("Symptom", Patient::symptom)
                .listInput("Flu Symptoms", Patient::fluSymptoms)
                .output(output -> output.values().get("id"))
                .build();
        var withMissing = Arrays.asList("fever", null);
        // ? in list is list contains(list, ?) (DMN 1.5, Table 55): null = null is true, null = "fever" false
        Assertions.assertThat(engine.evaluate(new Patient(null, withMissing))).containsExactly("flu");
        Assertions.assertThat(engine.evaluate(new Patient("fever", withMissing))).containsExactly("flu");
        Assertions.assertThat(engine.evaluate(new Patient("rash", withMissing))).containsExactly("not-flu");
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
        Assertions.assertThat(engine.evaluate(Map.of("nationalities", "[\"PT\", null]"))).containsExactly("iberian", "any");
        var withComparison = Map.of("nationalities", "[< 5]");
        Assertions.assertThatThrownBy(() -> engine.evaluate(withComparison))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a list of string values");
    }

    @Test
    void rules_that_test_different_values_of_a_list_overlap() {
        // rules 1 and 2 overlap on ["PT", "BR"], though no single value matches both
        var engine = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, Map.of("nationalities", "PT"), "pt"), rule(2, Map.of("nationalities", "BR"), "br")))
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.OVERLAPPING_RULES))
                .extracting(ValidationReport.Issue::ruleIds).containsExactly(List.of(1, 2));
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

    /**
     * {@code = Flu Symptoms} is FEEL's {@code ? = Flu Symptoms}, an equality of a value and a list, which is null (DMN
     * 1.5, Tables 52 and 53), unlike the bare name, which asks whether the value is in the list (Table 55): rejected
     * as {@code != Flu Symptoms} is, and written back as it was read.
     */
    @Test
    void an_equality_with_a_list_is_rejected_as_any_other_comparison_with_it() {
        for (var cell : List.of("= Flu Symptoms", "!= Flu Symptoms", "\"cough\", = Flu Symptoms")) {
            var table = new DecisionTable("symptoms", HitPolicy.COLLECT, null, List.of(new DecisionTable.InputColumn("Symptom", ColumnType.STRING)),
                    List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)), List.of(rule(1, Map.of("Symptom", cell), "flu")),
                    true, Set.of("Flu Symptoms"));
            var builder = PraeceptaEngine.<Patient, String>builder()
                    .table(table)
                    .input("Symptom", Patient::symptom)
                    .listInput("Flu Symptoms", Patient::fluSymptoms)
                    .output(output -> output.values().get("id"));
            Assertions.assertThatThrownBy(builder::build).as(cell)
                    .isInstanceOf(InvalidDecisionTableException.class)
                    .hasMessage("Rule 1, column 'Symptom': compares with 'Flu Symptoms', which holds lists; a cell can only ask whether "
                            + "the value is in it, as 'Flu Symptoms' does");
            var xml = DmnWriter.write(table);
            Assertions.assertThat(xml).contains("<text>" + cell + "</text>");
            Assertions.assertThat(DmnWriter.write(DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))))
                    .isEqualTo(xml);
        }
        // of an input that holds one value, = home is the bare name's test
        var single = new DecisionTable("t", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("s", ColumnType.STRING)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)), List.of(rule(1, Map.of("s", "= home"), "x")), true);
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(single)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("s", "PT", "home", "PT"))).containsExactly("x");
        Assertions.assertThat(engine.evaluate(Map.of("s", "PT", "home", "ES"))).isEmpty();
    }

    /**
     * In Praecepta's syntax, {@code null} on a list column asks whether the list is missing, and the other tests look
     * at its elements; {@code != null} would be neither the negation of {@code null} nor clearly about the elements,
     * so it is rejected in favour of {@code not(null)}, a list that is there. A DMN file writes the elements' test
     * {@code some x in ? satisfies x != null}, which is read as it says.
     */
    @Test
    void not_equal_null_is_rejected_on_a_list_column_in_praeceptas_syntax() {
        var rules = List.of(rule(1, Map.of("nationalities", "!= null"), "there"));
        var builder = PraeceptaEngine.<Customer, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(rules);
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, column 'nationalities': '!= null' is not a test of a list column, where 'null' tests the list and "
                        + "other tests its elements; write not(null) for a list that is there");

        var present = PraeceptaEngine.<Customer, String>builder()
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .rules(List.of(rule(1, Map.of("nationalities", "not(null)"), "there"), rule(2, Map.of("nationalities", "= null"), "missing")))
                .hitPolicy(HitPolicy.COLLECT)
                .build();
        var withNull = new java.util.ArrayList<String>();
        withNull.add(null);
        Assertions.assertThat(present.evaluate(customer())).containsExactly("there");
        Assertions.assertThat(present.evaluate(new Customer(withNull, null))).containsExactly("there");
        Assertions.assertThat(present.evaluate(new Customer(null, null))).containsExactly("missing");

        var elements = new DecisionTable("t", HitPolicy.COLLECT, null, List.of(new DecisionTable.InputColumn("nationalities", ColumnType.STRING)),
                List.of(new DecisionTable.OutputColumn("id", ColumnType.STRING)),
                List.of(rule(1, Map.of("nationalities", "some x in ? satisfies x != null"), "an element")), true, Set.of("nationalities"));
        var dmn = PraeceptaEngine.<Customer, String>builder()
                .table(elements)
                .listInput("nationalities", Customer::nationalities)
                .output(output -> output.values().get("id"))
                .build();
        Assertions.assertThat(dmn.evaluate(customer("PT"))).containsExactly("an element");
        Assertions.assertThat(dmn.evaluate(customer())).isEmpty();
        Assertions.assertThat(dmn.evaluate(new Customer(withNull, null))).isEmpty();
        Assertions.assertThat(dmn.evaluate(new Customer(null, null))).isEmpty();
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
