/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/**
 * DMN files whose decisions require other decisions (#14): each decision evaluated from the model's input data through
 * its graph, with the same values as the engine's {@link DecisionGraph} and as each table's own map-based engine.
 */
class GraphModelTest {

    private static Decisions load(String... files) {
        return load(Severity.ERROR, files);
    }

    private static Decisions load(Severity failOn, String... files) {
        var sources = new ArrayList<TableSource>();
        for (var file : files) {
            sources.add(source(file));
        }
        return Decisions.load(sources, failOn);
    }

    private static TableSource source(String file) {
        try (var dmn = GraphModelTest.class.getResourceAsStream("/graphs/" + file)) {
            return TableSource.of(file, dmn.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecisionGraph graph(String file) {
        try (var dmn = GraphModelTest.class.getResourceAsStream("/graphs/" + file)) {
            return DecisionGraph.read(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void decisions_that_require_other_decisions_are_evaluated_from_the_input_data() {
        var decisions = load("chain.dmn");

        Assertions.assertThat(decisions.names()).containsExactly("Credit Limit", "Monthly Limit", "Risk Category");
        var inputs = Map.of("Applicant Age", "30", "Income", "1200");
        Assertions.assertThat(decisions.evaluate("Monthly Limit", inputs)).containsExactly(Map.of("Monthly Limit", "200"));
        Assertions.assertThat(decisions.evaluate("Credit Limit", inputs)).containsExactly(Map.of("Credit Limit", "2400"));
        Assertions.assertThat(decisions.evaluate("Risk Category", inputs)).containsExactly(Map.of("Risk Category", "Low"));
        Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "20", "Income", "1200")))
                .containsExactly(Map.of("Monthly Limit", "50"));
        // a missing input data is null, and so is a value computed from it; a blank one is missing too
        Assertions.assertThat(decisions.evaluate("Risk Category", Map.of("Applicant Age", " "))).isEmpty();
        Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Income", "1200"))).isEmpty();

        var model = decisions.models().getFirst();
        Assertions.assertThat(model.name()).isEqualTo("Credit");
        Assertions.assertThat(decisions.get("Monthly Limit").model()).isSameAs(model);
        Assertions.assertThat(decisions.get("Monthly Limit").table()).isNull();
        Assertions.assertThat(decisions.get("Monthly Limit").engine()).isNull();
        Assertions.assertThatThrownBy(() -> decisions.engine("Monthly Limit"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("literal expression");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Risk Category", Map.of("Applicant Age", "old")))
                .isInstanceOf(InvalidInputException.class);
    }

    /**
     * The map engine of a decision table evaluates it alone from its columns, as in 0.6: given the values of the
     * decisions it requires, as the graph computes them, it gives what the graph gives from the input data.
     */
    @Test
    void a_tables_own_engine_and_the_graph_agree() {
        var decisions = load("chain.dmn");
        var graph = graph("chain.dmn");
        var limit = decisions.engine("Credit Limit");
        for (int age = 0; age <= 60; age += 3) {
            for (var income : List.of("0", "999.99", "1200", "40000")) {
                var inputData = Map.of("Applicant Age", Integer.toString(age), "Income", income);
                var risk = graph.evaluate("Risk Category", Map.of("Applicant Age", age));
                Assertions.assertThat(decisions.engine("Risk Category").evaluate(Map.of("Applicant Age", Integer.toString(age))))
                        .isEqualTo(decisions.evaluate("Risk Category", inputData));
                Assertions.assertThat(limit.evaluate(Map.of("Risk Category", (String) risk, "Income", income)))
                        .as("age %d, income %s", age, income)
                        .isEqualTo(decisions.evaluate("Credit Limit", inputData));
                var monthly = (BigDecimal) graph.evaluate("Monthly Limit", Map.of("Applicant Age", age, "Income", new BigDecimal(income)));
                Assertions.assertThat(decisions.evaluate("Monthly Limit", inputData))
                        .containsExactly(Map.of("Monthly Limit", monthly.toPlainString()));
            }
        }
    }

    @Test
    void a_table_with_several_outputs_gives_a_context_read_by_path() {
        var decisions = load("multi-output.dmn");

        Assertions.assertThat(decisions.evaluate("Pricing", Map.of("Customer Type", "Gold")))
                .containsExactly(Map.of("discount", "0.2", "grade", "A"));
        Assertions.assertThat(decisions.evaluate("Shipping", Map.of("Customer Type", "Silver"))).containsExactly(Map.of("Shipping", "Standard"));
        Assertions.assertThat(decisions.evaluate("Total", Map.of("Customer Type", "Gold", "Order Total", "100")))
                .containsExactly(Map.of("Total", "80"));
        // the table alone reads the component it reads as a column of that name
        Assertions.assertThat(decisions.engine("Shipping").evaluate(Map.of("Pricing.grade", "A"))).containsExactly(Map.of("Shipping", "Free"));
    }

    @Test
    void tables_with_several_results_give_one_map_per_result_and_lists_flow_downstream() {
        var decisions = load("multiple-results.dmn");
        var large = Map.of("Amount", "20000", "Country", "XX");

        Assertions.assertThat(decisions.evaluate("Flags", large)).containsExactly(Map.of("Flags", "large"), Map.of("Flags", "sanctioned"));
        Assertions.assertThat(decisions.engine("Flags").evaluate(large)).isEqualTo(decisions.evaluate("Flags", large));
        Assertions.assertThat(decisions.evaluate("Checks", large))
                .containsExactly(Map.of("code", "AML", "weight", "3"), Map.of("code", "BASIC", "weight", "1"));
        Assertions.assertThat(decisions.engine("Checks").evaluate(large)).isEqualTo(decisions.evaluate("Checks", large));
        Assertions.assertThat(decisions.evaluate("Review", large)).containsExactly(Map.of("Review", "Block"));
        Assertions.assertThat(decisions.evaluate("Due Diligence", large)).containsExactly(Map.of("Due Diligence", "Enhanced"));
        Assertions.assertThat(decisions.evaluate("Flag Count", large)).containsExactly(Map.of("Flag Count", "2"));

        var small = Map.of("Amount", "5", "Country", "PT");
        Assertions.assertThat(decisions.evaluate("Flags", small)).isEmpty();
        Assertions.assertThat(decisions.evaluate("Review", small)).containsExactly(Map.of("Review", "Auto"));
        // the table alone takes the list as text, as a list column does
        Assertions.assertThat(decisions.engine("Review").evaluate(Map.of("Flags", "[\"large\"]"))).containsExactly(Map.of("Review", "Manual"));
    }

    @Test
    void structured_input_data_are_given_by_the_paths_of_their_components() {
        var decisions = load("literals.dmn");
        var graph = graph("literals.dmn");

        var loan = Map.of("Loan.principal", "600000", "Loan.rate", "0.0375", "Loan.months", "360");
        var expected = graph.evaluate("Payment", Map.of("Loan", Map.of("principal", 600000, "rate", new BigDecimal("0.0375"), "months", 360)));
        Assertions.assertThat(decisions.evaluate("Payment", loan)).containsExactly(Map.of("Payment", ((BigDecimal) expected).toPlainString()));
        Assertions.assertThat(decisions.evaluate("Payment", Map.of("Loan.principal", "600000"))).as("missing components are null").isEmpty();
        Assertions.assertThat(decisions.evaluate("Payment", Map.of())).isEmpty();
        var whole = Map.of("Loan", "600000");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Payment", whole))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("Loan.principal");

        Assertions.assertThat(decisions.evaluate("Greeting", Map.of("Full Name", "Ada"))).containsExactly(Map.of("Greeting", "Hello Ada"));
        Assertions.assertThat(decisions.evaluate("Due", Map.of("Start", "2025-01-31"))).containsExactly(Map.of("Due", "2025-02-14"));
        Assertions.assertThat(decisions.evaluate("Starts At", Map.of("Start", "2025-01-31")))
                .containsExactly(Map.of("Starts At", "2025-01-31T00:00:00Z"));
        Assertions.assertThat(decisions.evaluate("Shift", Map.of("Hours", "3"))).containsExactly(Map.of("Shift", "PT3H"));
    }

    @Test
    void a_graphs_issues_fail_loading_and_are_described_and_logged() {
        var failure = Assertions.catchThrowableOfType(DecisionsException.class, () -> load("cycle.dmn"));
        Assertions.assertThat(failure.problems()).singleElement().asString()
                .startsWith("cycle.dmn: decision model 'Cycle' has validation issues at ERROR or above:")
                .contains("ERROR decisions 'A' -> 'B' -> 'A' require one another in a cycle", "decision 'E' requires itself");

        var decisions = load((Severity) null, "cycle.dmn");
        Assertions.assertThat(decisions.evaluate("D", Map.of("X", "1"))).containsExactly(Map.of("D", "2"));
        var none = Map.of("X", "1");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("C", none))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Decision 'C' cannot be evaluated");

        var description = decisions.describe();
        Assertions.assertThat(description).containsOnlyKeys("decisions", "models");
        @SuppressWarnings("unchecked")
        var models = (List<Map<String, Object>>) description.get("models");
        Assertions.assertThat(models).singleElement().satisfies(model -> {
            Assertions.assertThat(model).containsEntry("name", "Cycle").containsEntry("location", "cycle.dmn")
                    .containsEntry("decisions", List.of("A", "B", "C", "D", "E"))
                    .containsEntry("inputData", List.of(Map.of("name", "X", "type", "number")));
            Assertions.assertThat((List<?>) model.get("issues")).hasSize(2);
        });
        Assertions.assertThat(decisions.get("C").describe())
                .containsEntry("name", "C")
                .containsEntry("model", "Cycle")
                .containsEntry("logic", "literalExpression")
                .containsEntry("expression", "A * 2")
                .containsEntry("type", "number")
                .containsEntry("requiredDecisions", List.of("A"))
                .containsEntry("inputData", List.of())
                .containsEntry("canEvaluate", false)
                .doesNotContainKey("hitPolicy");
        Assertions.assertThat(decisions.get("A").describe())
                .containsEntry("logic", "decisionTable")
                .containsEntry("hitPolicy", "FIRST")
                .containsEntry("canEvaluate", false);
        Assertions.assertThat(decisions.get("D").describe()).containsEntry("inputData", List.of("X")).containsEntry("canEvaluate", true);

        var warnings = new ArrayList<String>();
        new DecisionsLoader("praecepta", List.of("classpath:graphs/cycle.dmn"), "NONE").load(
                location -> TableLocations.find(location, GraphModelTest.class.getClassLoader()), new DecisionsLoader.Log() {
                    @Override
                    public void info(String message) {
                        // not checked here
                    }

                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });
        Assertions.assertThat(warnings).hasSize(2).allMatch(warning -> warning.startsWith("Decision model Cycle: ERROR "));
    }

    @Test
    void a_file_of_tables_that_require_only_input_data_loads_as_before() {
        var file = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <inputData id="age" name="age"><variable name="age" typeRef="number"/></inputData>
                  <decision id="tier" name="Tier">
                    <informationRequirement><requiredInput href="#age"/></informationRequirement>
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression typeRef="number"><text>age</text></inputExpression></input>
                      <output name="tier" typeRef="string"/>
                      <rule><inputEntry><text>&gt;= 18</text></inputEntry><outputEntry><text>"adult"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>""";
        var decisions = Decisions.load(List.of(TableSource.of("tier.dmn", file.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        Assertions.assertThat(decisions.models()).isEmpty();
        Assertions.assertThat(decisions.get("Tier").model()).isNull();
        Assertions.assertThat(decisions.evaluate("Tier", Map.of("age", "30"))).containsExactly(Map.of("tier", "adult"));
        Assertions.assertThat(decisions.describe()).containsOnlyKeys("decisions");
    }

    /**
     * DmnWriter gives each column an input data, so a table with a column of its own name is not a well-formed decision
     * model (DMN 1.5, 6.3.11: two elements of one name); its file of tables never had to be one, and loads as before.
     */
    @Test
    void a_file_of_tables_that_is_no_well_formed_model_loads_its_tables_as_before() {
        var table = new DecisionTable("tier", HitPolicy.FIRST, null, List.of(new InputColumn("tier", ColumnType.STRING)),
                List.of(new OutputColumn("level", ColumnType.NUMBER)),
                List.of(new Praeceptum(1, new PraeceptaInput(Map.of("tier", "\"gold\"")), new PraeceptaOutput(Map.of("level", "3")))));
        var dmn = DmnWriter.write(table);
        var decisions = Decisions.load(List.of(TableSource.of("tier.dmn", dmn.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        Assertions.assertThat(decisions.models()).isEmpty();
        Assertions.assertThat(decisions.evaluate("tier", Map.of("tier", "gold"))).containsExactly(Map.of("level", "3"));
    }

    /**
     * A model with what a later release brings is no graph Praecepta evaluates: its decision tables load alone, as in
     * 0.6, and nothing else of it; without a decision table, the file fails naming the feature and its issue.
     */
    @Test
    void what_later_releases_bring_is_not_evaluated_and_a_file_without_tables_fails_naming_it() {
        var file = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <businessKnowledgeModel id="bkm" name="Rate"/>
                  <decision id="tier" name="Tier"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>age</text></inputExpression></input>
                    <output name="tier" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"any"</text></outputEntry></rule>
                  </decisionTable></decision>
                  <decision id="label" name="Label">
                    <informationRequirement><requiredDecision href="#tier"/></informationRequirement>
                    <literalExpression><text>upper case(Tier)</text></literalExpression>
                  </decision>
                </definitions>""";
        var decisions = Decisions.load(List.of(TableSource.of("bkm.dmn", file.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        Assertions.assertThat(decisions.names()).containsExactly("Tier");
        Assertions.assertThat(decisions.models()).isEmpty();
        Assertions.assertThat(decisions.evaluate("Tier", Map.of("age", "3"))).containsExactly(Map.of("tier", "any"));

        // the loader says so, one line at INFO per such file, naming it, the feature and the release that plans it
        var infos = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var bkm = TableSource.of("rules/bkm.dmn", file.getBytes(StandardCharsets.UTF_8));
        new DecisionsLoader("praecepta", null, "ERROR").declared(List.of(bkm, source("chain.dmn")), List.of())
                .load(location -> List.of(), new DecisionsLoader.Log() {
                    @Override
                    public void info(String message) {
                        infos.add(message);
                    }

                    @Override
                    public void warn(String message) {
                        warnings.add(message);
                    }
                });
        Assertions.assertThat(infos).hasSize(2).first().asString().startsWith("Loaded 4 decision(s) from 2 DMN file(s): ");
        Assertions.assertThat(infos.get(1))
                .startsWith("rules/bkm.dmn: its decision tables are loaded alone, without its decision requirements graph, "
                        + "which needs Praecepta 0.10 (#118): ")
                .contains("business knowledge model 'Rate'");
        Assertions.assertThat(warnings).isEmpty();

        var withoutTables = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <businessKnowledgeModel id="bkm" name="Rate"/>
                  <decision id="a" name="A"><literalExpression><text>1</text></literalExpression></decision>
                </definitions>""";
        var sources = List.of(TableSource.of("bkm.dmn", withoutTables.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR))
                .isInstanceOfSatisfying(DecisionsException.class, e -> Assertions.assertThat(e.problems()).singleElement().asString()
                        .startsWith("bkm.dmn: ")
                        .contains("business knowledge model 'Rate'", "#118"));
    }

    @Test
    void a_decision_of_a_graph_cannot_share_its_name_with_another_files() {
        var other = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="risk" name="Risk Category"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>age</text></inputExpression></input>
                    <output name="risk" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"any"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var sources = List.of(source("chain.dmn"), TableSource.of("risk.dmn", other.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThatThrownBy(() -> Decisions.load(sources, Severity.ERROR))
                .isInstanceOfSatisfying(DecisionsException.class, e -> Assertions.assertThat(e.problems())
                        .containsExactly("risk.dmn: decision 'Risk Category' is also defined in chain.dmn"));
    }

    @Test
    void list_input_data_are_read_as_list_columns_read_them() {
        var file = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="Lists" namespace="n">
                  <itemDefinition name="tCodes" isCollection="true"><typeRef>string</typeRef></itemDefinition>
                  <inputData id="codes" name="Codes"><variable name="Codes" typeRef="tCodes"/></inputData>
                  <decision id="flag" name="Flag">
                    <informationRequirement><requiredInput href="#codes"/></informationRequirement>
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression typeRef="tCodes"><text>Codes</text></inputExpression></input>
                      <output name="flag" typeRef="string"/>
                      <rule><inputEntry><text>list contains(?, "X")</text></inputEntry><outputEntry><text>"x"</text></outputEntry></rule>
                      <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"other"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                  <decision id="label" name="Label">
                    <informationRequirement><requiredDecision href="#flag"/></informationRequirement>
                    <literalExpression><text>"flag: " + Flag</text></literalExpression>
                  </decision>
                </definitions>""";
        var decisions = Decisions.load(List.of(TableSource.of("lists.dmn", file.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        for (var codes : List.of("[\"A\", \"X\"]", "[\"A\"]", "[]", "\"X\", null")) {
            var input = new HashMap<String, String>();
            input.put("Codes", codes);
            Assertions.assertThat(decisions.evaluate("Flag", input)).as(codes).isEqualTo(decisions.engine("Flag").evaluate(input));
        }
        Assertions.assertThat(decisions.evaluate("Label", Map.of("Codes", "[\"X\"]"))).containsExactly(Map.of("Label", "flag: x"));
        var notAList = Map.of("Codes", "> 3");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Label", notAList)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void decisions_listed_before_the_decisions_they_require_are_evaluated_after_them() {
        var decisions = load("chain-reversed.dmn");
        var inputs = Map.of("Applicant Age", "30", "Income", "1200");

        Assertions.assertThat(decisions.evaluate("Monthly Limit", inputs)).containsExactly(Map.of("Monthly Limit", "200"));
        Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "20", "Income", "1200")))
                .containsExactly(Map.of("Monthly Limit", "50"));
        Assertions.assertThat(decisions.evaluate("Credit Limit", inputs)).isEqualTo(load("chain.dmn").evaluate("Credit Limit", inputs));
    }

    private static final String UNTYPED = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="u" name="Untyped" namespace="n">
              <inputData id="a" name="A"><variable name="A"/></inputData>
              <decision id="t" name="T">
                <informationRequirement><requiredInput href="#a"/></informationRequirement>
                <decisionTable hitPolicy="FIRST">
                  <input><inputExpression typeRef="number"><text>A.d</text></inputExpression></input>
                  <output name="T" typeRef="string"/>
                  <rule><inputEntry><text>&gt; 1</text></inputEntry><outputEntry><text>"hit"</text></outputEntry></rule>
                  <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"other"</text></outputEntry></rule>
                </decisionTable>
              </decision>
              <decision id="u" name="U">
                <informationRequirement><requiredDecision href="#t"/></informationRequirement>
                <literalExpression><text>T + "!"</text></literalExpression>
              </decision>
              <decision id="n" name="Name">
                <variable name="Name" typeRef="string"/>
                <informationRequirement><requiredInput href="#a"/></informationRequirement>
                <literalExpression><text>A.e.f</text></literalExpression>
              </decision>
            </definitions>""";

    @Test
    void input_data_without_a_type_are_given_by_the_paths_a_decision_reads_into_them() {
        var decisions = Decisions.load(List.of(TableSource.of("untyped.dmn", UNTYPED.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);

        // the graph and the table's own engine agree
        Assertions.assertThat(decisions.evaluate("T", Map.of("A.d", "5"))).containsExactly(Map.of("T", "hit"))
                .isEqualTo(decisions.engine("T").evaluate(Map.of("A.d", "5")));
        Assertions.assertThat(decisions.evaluate("T", Map.of("A.d", "0"))).containsExactly(Map.of("T", "other"));
        Assertions.assertThat(decisions.evaluate("U", Map.of("A.d", "5"))).containsExactly(Map.of("U", "hit!"));
        // and so does the engine's graph given the same values as Java values
        var graph = decisions.models().getFirst().graph();
        Assertions.assertThat(graph.evaluate("U", Map.of("A", Map.of("d", 5)))).isEqualTo("hit!");
        // a path of several components is a context in a context
        Assertions.assertThat(decisions.evaluate("Name", Map.of("A.e.f", "x", "A.d", "1"))).containsExactly(Map.of("Name", "x"));
        Assertions.assertThat(decisions.evaluate("Name", Map.of())).isEmpty();
        Assertions.assertThatThrownBy(() -> decisions.evaluate("T", Map.of("A.d", "five"))).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("A.d=five is not a valid number");
        Assertions.assertThatThrownBy(() -> decisions.evaluate("Name", Map.of("A.e", "x", "A.e.f", "y")))
                .isInstanceOf(InvalidInputException.class).hasMessageContaining("continues the path 'A.e'");
    }

    @Test
    void blank_text_for_a_string_input_data_is_the_empty_string_as_the_engines_graph_has_it() {
        var file = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="g" name="Greeting" namespace="n">
                  <inputData id="name" name="Name"><variable name="Name" typeRef="string"/></inputData>
                  <inputData id="age" name="Age"><variable name="Age" typeRef="number"/></inputData>
                  <decision id="g" name="Greeting">
                    <informationRequirement><requiredInput href="#name"/></informationRequirement>
                    <literalExpression><text>"Hello " + Name</text></literalExpression>
                  </decision>
                  <decision id="n" name="Next">
                    <informationRequirement><requiredInput href="#age"/></informationRequirement>
                    <literalExpression><text>Age + 1</text></literalExpression>
                  </decision>
                </definitions>""";
        var decisions = Decisions.load(List.of(TableSource.of("greeting.dmn", file.getBytes(StandardCharsets.UTF_8))), Severity.ERROR);
        var graph = decisions.models().getFirst().graph();

        Assertions.assertThat(decisions.evaluate("Greeting", Map.of("Name", ""))).containsExactly(Map.of("Greeting", "Hello "));
        Assertions.assertThat(graph.evaluate("Greeting", Map.of("Name", ""))).isEqualTo("Hello ");
        Assertions.assertThat(decisions.evaluate("Greeting", Map.of())).isEmpty();
        // blank text for any other type is missing, as for a decision table's map-based inputs
        Assertions.assertThat(decisions.evaluate("Next", Map.of("Age", " "))).isEmpty();
    }

    @Test
    void fail_on_checks_the_tables_report_as_the_graph_and_the_build_plugins_have_it() {
        var decisions = load("chain.dmn");
        var graph = decisions.models().getFirst().graph();

        for (var name : List.of("Risk Category", "Credit Limit")) {
            Assertions.assertThat(decisions.get(name).report()).isEqualTo(graph.validationReport(name));
        }
    }
}
