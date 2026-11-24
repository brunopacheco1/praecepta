/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Decision tables in the full FEEL language (#116): input entries that are any unary tests (DMN 1.5, grammar rules 13
 * to 17, 10.3.2.10 and Table 55), input expressions that are expressions, and output entries that are expressions
 * (chapter 10), with FEEL's nulls, bound to their outputs' and decisions' types (10.3.2.9.4).
 */
class FeelTablesTest {

    /** A table of one number column, {@code Amount}, whose cells may read {@code Limit} and {@code Low}, and one string output. */
    private static final String AMOUNTS = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <inputData id="i1" name="Amount"><variable name="Amount" typeRef="number"/></inputData>
              <inputData id="i2" name="Limit"><variable name="Limit" typeRef="number"/></inputData>
              <inputData id="i3" name="Low"><variable name="Low" typeRef="number"/></inputData>
              <decision id="c" name="Check"><variable name="Check" typeRef="string"/>
                <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                <informationRequirement><requiredInput href="#i2"/></informationRequirement>
                <informationRequirement><requiredInput href="#i3"/></informationRequirement>
                <decisionTable hitPolicy="FIRST">
                  <input><inputExpression typeRef="number"><text>Amount</text></inputExpression></input>
                  <output/>
                  <rule><inputEntry><text>%s</text></inputEntry><outputEntry><text>"yes"</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"no"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>""";

    /**
     * DMN 1.5, 10.3.2.10 and Table 55: each positive unary test is an expression; one that names {@code ?} is its boolean,
     * a range holds what is in it, a list what one of its elements holds or equals, any other value what equals it;
     * {@code not(...)} holds what none of them holds, and neither holds a value they are null for.
     */
    @ParameterizedTest(name = "{0} with Amount {1}, Limit {2}, Low {3}: {4}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "&gt; Limit * 2                   | 25   | 10   | 0    | yes",
            "&gt; Limit * 2                   | 20   | 10   | 0    | no",
            "&gt; Limit * 2                   | 25   | null | 0    | no",
            "not(&gt; Limit * 2)              | 25   | null | 0    | no",
            "not(&gt; Limit * 2)              | 5    | 10   | 0    | yes",
            "[Low..Low + 10]                  | 12   | 0    | 2    | yes",
            "[Low..Low + 10]                  | 13   | 0    | 2    | no",
            "? &gt; 5 and ? &lt; 10          | 7    | 0    | 0    | yes",
            "? &gt; 5 and ? &lt; 10          | 10   | 0    | 0    | no",
            "not(? &gt; 5)                    | 3    | 0    | 0    | yes",
            "[1, 2, Limit]                    | 7    | 7    | 0    | yes",
            "[1, 2, Limit]                    | 3    | 7    | 0    | no",
            "[[1..2], Limit]                  | 1.5  | 7    | 0    | yes",
            "= Limit + 1, &lt; 0              | 8    | 7    | 0    | yes",
            "= Limit + 1, &lt; 0              | -1   | 7    | 0    | yes",
            "!= Limit - 1                     | 6    | 7    | 0    | no",
            "= null, &gt; Low + 1             | null | 0    | 0    | yes",
            "= null, &gt; Low + 1             | 1    | 0    | 0    | no",
            "&gt; \"a\" + Limit               | 1    | 7    | 0    | no",
            "not(&gt; \"a\" + Limit)          | 1    | 7    | 0    | no",
            "-                                | null | null | null | yes",
    })
    void input_entries_are_any_unary_tests(String cell, BigDecimal amount, BigDecimal limit, BigDecimal low, String expected) {
        var graph = DecisionGraph.read(stream(AMOUNTS.formatted(cell)));
        Assertions.assertThat(graph.validationReport().issues()).isEmpty();
        var inputs = new HashMap<String, Object>();
        inputs.put("Amount", amount);
        inputs.put("Limit", limit);
        inputs.put("Low", low);
        Assertions.assertThat(graph.evaluate("Check", inputs)).isEqualTo(expected);
    }

    /** {@code true} is a value, which only {@code true} equals (Table 53), not a test that holds for everything. */
    @Test
    void a_boolean_entry_that_names_no_question_mark_is_compared() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <decision id="c" name="Flag"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="boolean"><text>ok</text></inputExpression></input>
                    <input><inputExpression typeRef="number"><text>n</text></inputExpression></input>
                    <output name="out" typeRef="string"/>
                    <rule><inputEntry><text>n &gt; 1</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>"n &gt; 1 as ok"</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>"other"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var engine = mapEngine(DmnReader.read(stream(xml)));
        Assertions.assertThat(engine.evaluate(Map.of("ok", "true", "n", "2"))).containsExactly(Map.of("out", "n > 1 as ok"));
        Assertions.assertThat(engine.evaluate(Map.of("ok", "false", "n", "2"))).containsExactly(Map.of("out", "other"));
        Assertions.assertThat(engine.evaluate(Map.of("ok", "false", "n", "0"))).containsExactly(Map.of("out", "n > 1 as ok"));
    }

    /** A rule with a cell the index cannot evaluate is never counted as overlapping, hiding another or covering a gap. */
    @Test
    void the_analysis_treats_rules_with_feel_cells_as_rules_that_compare_with_another_input() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <decision id="c" name="Grade"><decisionTable hitPolicy="UNIQUE">
                    <input><inputExpression typeRef="number"><text>score</text></inputExpression>
                      <inputValues><text>[0..100]</text></inputValues></input>
                    <output name="grade" typeRef="string"/>
                    <rule><inputEntry><text>[0..50)</text></inputEntry><outputEntry><text>"low"</text></outputEntry></rule>
                    <rule><inputEntry><text>[40..60]</text></inputEntry><outputEntry><text>"mid"</text></outputEntry></rule>
                    <rule><inputEntry><text>? &gt;= 60 and ? &lt;= bonus * 10</text></inputEntry><outputEntry><text>"high"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var table = DmnReader.read(stream(xml));
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .columnType("bonus", ColumnType.NUMBER).output(PraeceptaOutput::values).logValidationReport(false).build();
        // the literal rules overlap, as before; the FEEL rule is in no overlap, and no gap is looked for
        Assertions.assertThat(engine.validationReport().issues()).extracting(ValidationReport.Issue::kind)
                .containsExactly(Kind.OVERLAPPING_RULES);
        Assertions.assertThat(engine.validationReport().issues().getFirst().ruleIds()).containsExactly(1, 2);
        Assertions.assertThat(engine.inputs()).containsExactly("score", "bonus");
        Assertions.assertThat(engine.evaluate(Map.of("score", "80", "bonus", "9"))).containsExactly(Map.of("grade", "high"));
        Assertions.assertThat(engine.evaluate(Map.of("score", "95", "bonus", "9"))).isEmpty();
        // written back as it was read
        Assertions.assertThat(DmnReader.read(stream(DmnWriter.write(table))).rules()).isEqualTo(table.rules());
    }

    /** DMN 1.5, 7.3.2 and 10.3.2.10: an input expression is any expression, here computed from the input it reads. */
    @Test
    void an_input_expression_is_computed_from_the_inputs_it_reads() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <inputData id="i1" name="Age"><variable name="Age" typeRef="number"/></inputData>
                  <decision id="c" name="Months"><variable name="Months" typeRef="string"/>
                    <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression><text>Age * 12</text></inputExpression></input>
                      <output/>
                      <rule><inputEntry><text>&gt;= 216</text></inputEntry><outputEntry><text>"adult"</text></outputEntry></rule>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"minor"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""";
        var model = DmnReader.readModel(stream(xml));
        var table = ((io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic.Table) model.decisions().getFirst().logic()).table();
        Assertions.assertThat(table.inputs().getFirst().type()).as("typed as FEEL types it in the model").isEqualTo(ColumnType.NUMBER);
        var graph = DecisionGraph.builder().model(model).logValidationReport(false).build();
        Assertions.assertThat(graph.evaluate("Months", Map.of("Age", 18))).isEqualTo("adult");
        Assertions.assertThat(graph.evaluate("Months", Map.of("Age", 17))).isEqualTo("minor");
        // a table on its own computes it from the input it reads, which it reads as the column's type
        var engine = mapEngine(table);
        Assertions.assertThat(engine.inputs()).containsExactly("Age * 12", "Age");
        Assertions.assertThat(engine.evaluate(Map.of("Age", "20"))).containsExactly(Map.of("Months", "adult"));
    }

    /** A column that reads a property of a date (DMN 1.5, Table 65) reads it as FEEL does. */
    @Test
    void a_column_may_read_a_property_of_a_date() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <inputData id="i1" name="Birth"><variable name="Birth" typeRef="date"/></inputData>
                  <decision id="c" name="Century"><variable name="Century" typeRef="string"/>
                    <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression typeRef="number"><text>Birth.year</text></inputExpression></input>
                      <output/>
                      <rule><inputEntry><text>&gt;= 2000</text></inputEntry><outputEntry><text>"21st"</text></outputEntry></rule>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"20th"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""";
        var graph = DecisionGraph.read(stream(xml));
        Assertions.assertThat(graph.validationReport().issues()).isEmpty();
        Assertions.assertThat(graph.evaluate("Century", Map.of("Birth", LocalDate.of(2001, 5, 1)))).isEqualTo("21st");
        Assertions.assertThat(graph.evaluate("Century", Map.of("Birth", LocalDate.of(1999, 5, 1)))).isEqualTo("20th");
    }

    /** A table of one rule whose output entries are the given FEEL expressions, over {@code Base} and {@code Rate}. */
    private static final String PRICES = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <inputData id="i1" name="Base"><variable name="Base" typeRef="number"/></inputData>
              <inputData id="i2" name="Rate"><variable name="Rate" typeRef="number"/></inputData>
              <decision id="p" name="Price">
                <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                <informationRequirement><requiredInput href="#i2"/></informationRequirement>
                <decisionTable hitPolicy="FIRST">
                  <input><inputExpression><text>Base</text></inputExpression></input>
                  <output name="value"%s/>
                  <rule><inputEntry><text>&gt; 0</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>""";

    /** Output entries are FEEL expressions (DMN 1.5, chapter 10), evaluated for the rule that matches. */
    @Test
    void output_entries_are_any_feel_expression() {
        var graph = DecisionGraph.read(stream(PRICES.formatted(" typeRef=\"number\"",
                "if Rate &gt; 0.5 then Base * Rate else {a: Base, b: a + Rate}.b", "0")));
        Assertions.assertThat(graph.validationReport().issues()).isEmpty();
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 10, "Rate", 0.8)))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("8");
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 10, "Rate", 0.25)))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("10.25");
    }

    /**
     * DMN 1.5, the text before Tables 56 and 58: operands FEEL does not combine give null; the TCK's 0100-arithmetic. An
     * output entry that is null whatever its inputs is a WARNING, and the table is evaluated (0.7 rejected it).
     */
    @Test
    void an_output_entry_feel_gives_null_is_null_and_a_warning() {
        var graph = DecisionGraph.read(stream(PRICES.formatted(" typeRef=\"number\"", "\"a\" + Base", "1")));
        Assertions.assertThat(graph.validationReport().issues()).isEmpty();
        var report = graph.validationReport("Price");
        Assertions.assertThat(report.issues()).extracting(ValidationReport.Issue::kind).containsExactly(Kind.ALWAYS_NULL);
        Assertions.assertThat(report.issues().getFirst().severity()).isEqualTo(ValidationReport.Severity.WARNING);
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 10, "Rate", 1))).isNull();
        var outcome = graph.evaluateWithErrors("Price", Map.of("Base", 10, "Rate", 1));
        Assertions.assertThat(outcome.errors()).isNotEmpty();
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", -1, "Rate", 1)))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("1");
    }

    /**
     * DMN 1.5, 10.3.2.10, step 3c: an output whose type nothing declares gives each entry's own value, here a number and a
     * string (0.7 gave both as strings).
     */
    @Test
    void an_untyped_output_gives_each_entry_its_own_type() {
        var graph = DecisionGraph.read(stream(PRICES.formatted("", "Base * Rate", "\"none\"")));
        Assertions.assertThat(graph.type("Price")).isEqualTo(FeelType.ANY);
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 10, "Rate", 2)))
                .asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL).isEqualByComparingTo("20");
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", -1, "Rate", 2))).isEqualTo("none");
        // entries of one type type the output: a number, though they compute it
        var numbers = DecisionGraph.read(stream(PRICES.formatted("", "Base * Rate", "0")));
        Assertions.assertThat(numbers.type("Price")).isEqualTo(FeelType.of(ColumnType.NUMBER));
        // a list, which no column holds, is given as it is
        var lists = DecisionGraph.read(stream(PRICES.formatted("", "[Base, Rate]", "[]")));
        Assertions.assertThat(lists.evaluate("Price", Map.of("Base", 10, "Rate", 2))).isEqualTo(List.of(BigDecimal.TEN, new BigDecimal("2")));
    }

    /** An output of no type of its own is written without a {@code typeRef}, so the model reads back as it was. */
    @Test
    void an_untyped_output_is_written_without_a_type_and_reads_back_alike() {
        var model = DmnReader.readModel(stream(PRICES.formatted("", "Base * Rate", "\"none\"")));
        var table = ((io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic.Table) model.decisions().getFirst().logic()).table();
        Assertions.assertThat(table.outputs().getFirst().typed()).isFalse();
        Assertions.assertThat(table.rules().get(1).output().expressions()).as("its literal, kept as FEEL").containsEntry("value", "\"none\"");
        var written = DmnWriter.write(model);
        Assertions.assertThat(written).contains("<output id=\"decision_0_output_0\" name=\"value\"/>");
        Assertions.assertThat(DmnReader.readModel(stream(written))).isEqualTo(model);
    }

    /** A value that does not conform to the output's type is null (DMN 1.5, 10.3.2.9.4), which the report warns of. */
    @Test
    void an_output_entry_of_another_type_than_its_output_is_null() {
        var graph = DecisionGraph.read(stream(PRICES.formatted(" typeRef=\"date\"", "Base + Rate", "null")));
        Assertions.assertThat(graph.validationReport("Price").issues()).extracting(ValidationReport.Issue::detail)
                .containsExactly("rule 1, output 'value': 'Base + Rate' gives a number, but the output is a date, so its value is null "
                        + "(DMN 1.5, 10.3.2.9.4)");
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 10, "Rate", 2))).isNull();
    }

    /** FEEL's built-in functions are planned for 0.9 (#117): a table entry that invokes one is rejected as a literal expression is. */
    @Test
    void an_entry_that_invokes_a_built_in_function_is_not_supported_yet() {
        var cell = stream(AMOUNTS.formatted("&gt; abs(Limit)"));
        Assertions.assertThatThrownBy(() -> DmnReader.readModel(cell))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("the built-in function 'abs', planned for 0.9 (#117)")
                .extracting(e -> ((UnsupportedFeatureException) e).release()).isEqualTo("0.9");
        var output = stream(PRICES.formatted("", "string(Base)", "\"\""));
        Assertions.assertThatThrownBy(() -> DmnReader.readModel(output)).isInstanceOf(UnsupportedFeatureException.class);
        var all = stream(AMOUNTS.formatted("&gt; abs(Limit)"));
        Assertions.assertThatThrownBy(() -> DmnReader.readAll(all)).isInstanceOf(UnsupportedFeatureException.class);
    }

    /** A cell that is neither simple unary tests nor FEEL is rejected, naming where it is, as before. */
    @Test
    void a_cell_that_is_not_feel_is_rejected_where_it_is() {
        var dmn = stream(AMOUNTS.formatted("&gt; (Limit"));
        Assertions.assertThatThrownBy(() -> DmnReader.readModel(dmn))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageStartingWith("Decision 'Check', rule 1, column 'Amount'");
        // a literal of another type than the column's is a mistake, not FEEL beyond the subset: rejected, as before
        var literal = stream(AMOUNTS.formatted("\"10\""));
        Assertions.assertThatThrownBy(() -> DmnReader.readModel(literal))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("rule 1, column 'Amount'");
    }

    /** UNIQUE over {@code Age} and {@code Age * 12}: a minor, or an adult in months, which no age is both. */
    private static final String AGES = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
              <inputData id="i1" name="Age"><variable name="Age" typeRef="number"/></inputData>
              <decision id="c" name="Kind"><variable name="Kind" typeRef="string"/>
                <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                <decisionTable hitPolicy="UNIQUE">
                  <input><inputExpression typeRef="number"><text>Age</text></inputExpression></input>
                  <input><inputExpression typeRef="number"><text>Age * 12</text></inputExpression></input>
                  <output/>
                  <rule><inputEntry><text>&lt; 18</text></inputEntry><inputEntry><text>-</text></inputEntry>
                    <outputEntry><text>"minor"</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>&gt;= 216</text></inputEntry>
                    <outputEntry><text>"adult"</text></outputEntry></rule>
                  %s
                </decisionTable>
              </decision>
            </definitions>""";

    /**
     * The index sees a computed column as a column of its own, unrelated to {@code Age}, so {@code < 18} and
     * {@code >= 216} would seem to overlap: the analysis leaves the rules that test it out, as it leaves FEEL cells, and
     * a build that fails on errors builds. Rules 1 and 3 still overlap, and are reported.
     */
    @Test
    void a_computed_column_does_not_make_rules_overlap_that_cannot() {
        var model = DmnReader.readModel(stream(AGES.formatted("")));
        var graph = DecisionGraph.builder().model(model).failOn(ValidationReport.Severity.ERROR).logValidationReport(false).build();
        Assertions.assertThat(graph.validationReport("Kind").issues()).isEmpty();
        Assertions.assertThat(graph.evaluate("Kind", Map.of("Age", 10))).isEqualTo("minor");
        Assertions.assertThat(graph.evaluate("Kind", Map.of("Age", 20))).isEqualTo("adult");
        var table = ((io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic.Table) model.decisions().getFirst().logic()).table();
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values).failOn(ValidationReport.Severity.ERROR).logValidationReport(false).build();
        Assertions.assertThat(engine.validationReport().issues()).isEmpty();

        var overlapping = DecisionGraph.builder().model(DmnReader.readModel(stream(AGES.formatted("""
                <rule><inputEntry><text>&lt; 10</text></inputEntry><inputEntry><text>-</text></inputEntry>
                  <outputEntry><text>"child"</text></outputEntry></rule>""")))).logValidationReport(false).build();
        Assertions.assertThat(overlapping.validationReport("Kind").issues())
                .extracting(ValidationReport.Issue::kind, ValidationReport.Issue::ruleIds)
                .containsExactly(Assertions.tuple(Kind.OVERLAPPING_RULES, List.of(1, 3)));
    }

    /**
     * A computed column is computed once per evaluation, however often it is read: by the index, and by each FEEL cell
     * that tests it. Here three rules test it with FEEL, so it was computed four times, and its input read five.
     */
    @Test
    void a_computed_column_is_computed_once_per_evaluation() {
        var rules = new java.util.ArrayList<io.github.brunopacheco1.praecepta.engine.beans.Praeceptum>();
        for (int r = 1; r <= 3; r++) {
            rules.add(new io.github.brunopacheco1.praecepta.engine.beans.Praeceptum(r,
                    new PraeceptaInput(Map.of("Age * 12", "? > %d".formatted(r))), new PraeceptaOutput(Map.of("out", "r" + r))));
        }
        var table = new DecisionTable("t", io.github.brunopacheco1.praecepta.engine.beans.HitPolicy.COLLECT, null,
                List.of(new DecisionTable.InputColumn("Age * 12", ColumnType.NUMBER)),
                List.of(new DecisionTable.OutputColumn("out", ColumnType.STRING)), rules, true);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var engine = PraeceptaEngine.<Integer, Map<String, String>>builder().table(table)
                .numberInput("Age", age -> {
                    reads.incrementAndGet();
                    return age;
                })
                .output(PraeceptaOutput::values).logValidationReport(false).build();
        Assertions.assertThat(engine.evaluate(1)).containsExactly(Map.of("out", "r1"), Map.of("out", "r2"), Map.of("out", "r3"));
        // once for the column Age, which the index reads as an input its FEEL reads, and once for Age * 12
        Assertions.assertThat(reads).hasValue(2);
    }

    /**
     * DMN 1.5, 7.3.4: a table's FEEL may name the model's item definitions ({@code instance of}), as a literal expression's
     * may, whether or not a literal expression names them too.
     */
    @Test
    void a_table_names_the_item_definitions_of_its_model() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <itemDefinition name="tAmount"><typeRef>number</typeRef></itemDefinition>
                  <inputData id="i1" name="Amount"><variable name="Amount" typeRef="tAmount"/></inputData>
                  <decision id="c" name="Typed"><variable name="Typed" typeRef="boolean"/>
                    <informationRequirement><requiredInput href="#i1"/></informationRequirement>
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression><text>Amount</text></inputExpression></input>
                      <output/>
                      <rule><inputEntry><text>? &gt; 0 and ? instance of tAmount</text></inputEntry>
                        <outputEntry><text>Amount instance of tAmount</text></outputEntry></rule>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>false</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""";
        var model = DmnReader.readModel(stream(xml));
        Assertions.assertThat(model.types()).containsOnlyKeys("tAmount");
        var graph = DecisionGraph.builder().model(model).logValidationReport(false).build();
        Assertions.assertThat(graph.validationReport().issues()).isEmpty();
        Assertions.assertThat(graph.evaluate("Typed", Map.of("Amount", 5))).isEqualTo(true);
        var missing = new HashMap<String, Object>();
        missing.put("Amount", null);
        Assertions.assertThat(graph.evaluate("Typed", missing)).isEqualTo(false);
        // the model writes back as it was read, its item definition with it
        Assertions.assertThat(DmnReader.readModel(stream(DmnWriter.write(model)))).isEqualTo(model);
        // a table read alone has no model: what it names is no type of it, so the test is null and the rule never matches
        Assertions.assertThat(mapEngine(DmnReader.read(stream(xml))).evaluate(Map.of("Amount", "5")).getFirst()).containsEntry("Typed", "false");
    }

    /**
     * A value may hold a list many times over, whose text writes it out each time, so writing it could take exponentially
     * long: a graph never writes it, as it takes the values, and an engine whose output strategy takes text writes at most
     * {@code MAX_TEXT_LENGTH} characters of it.
     */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void an_output_entry_that_holds_a_value_many_times_over_is_not_written_out_whole() {
        var context = new StringBuilder("{a0: [1, 1]");
        for (int i = 1; i <= 40; i++) {
            context.append(", a%d: [a%d, a%d]".formatted(i, i - 1, i - 1));
        }
        var entry = context.append("}.a40").toString();
        var graph = DecisionGraph.read(stream(PRICES.formatted("", entry, "null")));
        Assertions.assertThat(graph.evaluate("Price", Map.of("Base", 1, "Rate", 1))).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(2);

        var model = DmnReader.readModel(stream(PRICES.formatted("", entry, "null")));
        var table = ((io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic.Table) model.decisions().getFirst().logic()).table();
        var text = mapEngine(table).evaluate(Map.of("Base", "1", "Rate", "1")).getFirst().get("value");
        Assertions.assertThat(text).startsWith("[[[[").endsWith("...").hasSizeLessThanOrEqualTo(PraeceptaEngine.MAX_TEXT_LENGTH + 3);
    }

    /** A name the table's FEEL reads that is neither a column nor a value given to it cannot be read: it is rejected. */
    @Test
    void a_name_read_from_nowhere_is_rejected() {
        var expressions = new TableExpressions(List.of("x"), Map.of(), TableExpressions.Parsed.NONE);
        expressions.column("x + 1", ColumnType.NUMBER);
        Assertions.assertThatThrownBy(() -> PraeceptaEngine.nameReads(expressions, List.of("y"), List.of("z"), Map.of()))
                .isInstanceOf(InvalidDecisionTableException.class).hasMessageContaining("'x'");
        Assertions.assertThat(PraeceptaEngine.nameReads(expressions, List.of("y"), List.of("z", "x"), Map.of())).containsEntry("x", -2);
    }

    /**
     * A literal expression that names a type the model does not define is FEEL: that construct is null with an error, as
     * the TCK has it, so the decision is always null; a table with the same entry reads, and its entry is null.
     */
    @Test
    void a_literal_expression_that_names_an_unknown_type_is_always_null() {
        var graph = DecisionGraph.builder().logValidationReport(false)
                .inputData("x", ColumnType.NUMBER)
                .literalDecision("Typed", "x instance of tUnknown")
                .build();
        Assertions.assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::kind)
                .containsExactly(Kind.ALWAYS_NULL);
        Assertions.assertThat(graph.validationReport().issues().getFirst().toString()).startsWith("WARNING decision 'Typed'")
                .contains("tUnknown");
        Assertions.assertThat(graph.evaluate("Typed", Map.of("x", 1))).isNull();
        var table = DmnReader.readModel(stream(PRICES.formatted("", "Base instance of tUnknown", "null")));
        var price = DecisionGraph.builder().model(table).logValidationReport(false).build();
        Assertions.assertThat(price.evaluate("Price", Map.of("Base", 1, "Rate", 1))).isNull();
    }

    private static PraeceptaEngine<Map<String, String>, Map<String, String>> mapEngine(DecisionTable table) {
        return PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values).logValidationReport(false).build();
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
