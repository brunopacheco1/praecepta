/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.TableSource;

/**
 * DMN models whose decisions require other decisions (#14) compile to a model class and, per decision, an input record
 * and a class that evaluates it through the graph; compiled against the engine jar only and run, they give what the
 * engine's {@link DecisionGraph} and the framework integrations' map engine give.
 */
class GraphCompilerTest {

    private static final String PACKAGE = "com.acme.graph";

    @TempDir
    Path out;

    @Test
    void a_chain_of_decisions_becomes_a_class_per_decision_evaluated_through_the_graph() throws Exception {
        var result = compile(TableDefinition.dmn(table("chain.dmn")), Severity.ERROR);

        Assertions.assertThat(result.table()).isNull();
        Assertions.assertThat(result.className()).isEqualTo("CreditModel");
        Assertions.assertThat(result.resourcePath()).isEqualTo("com/acme/graph/CreditModel.dmn");
        Assertions.assertThat(result.summary()).isEqualTo("3 decisions");
        Assertions.assertThat(result.sources()).containsOnlyKeys("CreditModel", "RiskCategoryInput", "RiskCategory", "CreditLimitInput",
                "CreditLimit", "MonthlyLimitInput", "MonthlyLimit");
        Assertions.assertThat(result.sources().get("MonthlyLimitInput"))
                .contains("public record MonthlyLimitInput(java.math.BigDecimal applicantAge, java.math.BigDecimal income)");
        Assertions.assertThat(result.sources().get("RiskCategoryInput"))
                .contains("public record RiskCategoryInput(java.math.BigDecimal applicantAge)");
        Assertions.assertThat(result.reports()).containsOnlyKeys("CreditModel", "RiskCategory", "CreditLimit", "MonthlyLimit");
        Assertions.assertThat(result.dmn()).contains("<inputData", "<informationRequirement", "<literalExpression");

        var graph = graph("chain.dmn");
        var decisions = decisions("chain.dmn");
        try (var loader = compileAndLoad(result)) {
            var monthly = decision(loader, "MonthlyLimit");
            var limit = decision(loader, "CreditLimit");
            for (int age = 0; age <= 60; age += 7) {
                for (var income : List.of("0", "999.99", "1200", "40000")) {
                    var inputData = Map.<String, Object>of("Applicant Age", BigDecimal.valueOf(age), "Income", new BigDecimal(income));
                    var text = Map.of("Applicant Age", Integer.toString(age), "Income", income);
                    var expected = graph.evaluate("Monthly Limit", inputData);
                    Assertions.assertThat(monthly.evaluate(BigDecimal.valueOf(age), new BigDecimal(income))).isEqualTo(expected);
                    Assertions.assertThat(decisions.evaluate("Monthly Limit", text))
                            .containsExactly(Map.of("Monthly Limit", ((BigDecimal) expected).toPlainString()));
                    Assertions.assertThat(limit.evaluate(BigDecimal.valueOf(age), new BigDecimal(income)))
                            .isEqualTo(graph.evaluate("Credit Limit", inputData));
                }
            }
            Assertions.assertThat(monthly.evaluate(null, new BigDecimal(1200))).as("a missing input data").isNull();
            var model = loader.loadClass(PACKAGE + ".CreditModel");
            // the generated code's engine classes are the engine jar's, loaded apart from the test's
            Assertions.assertThat(model.getMethod("graph").invoke(null).getClass().getName()).isEqualTo(DecisionGraph.class.getName());
            Assertions.assertThat(model.getMethod("validationReport").invoke(null)).hasToString("no issues");
            Assertions.assertThat(loader.loadClass(PACKAGE + ".MonthlyLimit").getField("DECISION").get(null)).isEqualTo("Monthly Limit");
        }
    }

    @Test
    void decisions_listed_before_the_decisions_they_require_are_evaluated_after_them() throws Exception {
        // chain.dmn with each decision listed before the one it requires, and the input data last, Income first
        var result = compile(TableDefinition.dmn(table("chain-reversed.dmn")), Severity.ERROR);

        Assertions.assertThat(result.sources().get("MonthlyLimitInput"))
                .contains("public record MonthlyLimitInput(java.math.BigDecimal income, java.math.BigDecimal applicantAge)");
        var graph = graph("chain.dmn");
        try (var loader = compileAndLoad(result)) {
            var monthly = decision(loader, "MonthlyLimit");
            for (int age = 0; age <= 60; age += 15) {
                var inputData = Map.<String, Object>of("Applicant Age", BigDecimal.valueOf(age), "Income", new BigDecimal(1200));
                Assertions.assertThat(monthly.evaluate(new BigDecimal(1200), BigDecimal.valueOf(age)))
                        .isEqualTo(graph.evaluate("Monthly Limit", inputData));
            }
        }
    }

    @Test
    void a_context_becomes_a_record_and_lists_become_lists() throws Exception {
        var result = compile(TableDefinition.dmn(table("multi-output.dmn")), Severity.ERROR);
        Assertions.assertThat(result.sources()).containsKeys("PricingModel", "PricingOutput", "Pricing", "Shipping", "Total");
        Assertions.assertThat(result.sources().get("Pricing")).contains("public static PricingOutput evaluate(PricingInput input)");

        try (var loader = compileAndLoad(result)) {
            var gold = decision(loader, "Pricing").evaluate("Gold");
            Assertions.assertThat(gold).hasToString("PricingOutput[discount=0.2, grade=A]");
            Assertions.assertThat(decision(loader, "Shipping").evaluate("Silver")).isEqualTo("Standard");
            Assertions.assertThat(decision(loader, "Total").evaluate(new BigDecimal(100), "Gold"))
                    .isEqualTo(graph("multi-output.dmn").evaluate("Total", Map.of("Customer Type", "Gold", "Order Total", 100)));
        }

        var lists = compile(TableDefinition.dmn(table("multiple-results.dmn")), Severity.ERROR);
        Assertions.assertThat(lists.sources().get("Flags")).contains("public static java.util.List<String> evaluate(FlagsInput input)");
        Assertions.assertThat(lists.sources().get("Checks")).contains("public static java.util.List<ChecksOutput> evaluate(ChecksInput input)");
        var graph = graph("multiple-results.dmn");
        var decisions = decisions("multiple-results.dmn");
        try (var loader = compileAndLoad(lists)) {
            for (var amount : List.of(-5, 5, 20000)) {
                for (var country : List.of("PT", "XX")) {
                    var inputData = Map.<String, Object>of("Amount", amount, "Country", country);
                    var flags = decision(loader, "Flags").evaluate(BigDecimal.valueOf(amount), country);
                    Assertions.assertThat(flags).isEqualTo(graph.evaluate("Flags", inputData));
                    Assertions.assertThat(decisions.evaluate("Flags", Map.of("Amount", amount.toString(), "Country", country)))
                            .hasSize(flags == null ? 0 : ((List<?>) flags).size());
                    Assertions.assertThat(decision(loader, "Review").evaluate(BigDecimal.valueOf(amount), country))
                            .isEqualTo(graph.evaluate("Review", inputData));
                    Assertions.assertThat(decision(loader, "FlagCount").evaluate(BigDecimal.valueOf(amount), country))
                            .isEqualTo(graph.evaluate("Flag Count", inputData));
                }
            }
            Assertions.assertThat(decision(loader, "Checks").evaluate(new BigDecimal(20000))).asString()
                    .isEqualTo("[ChecksOutput[code=AML, weight=3], ChecksOutput[code=BASIC, weight=1]]");
            Assertions.assertThat(decision(loader, "DueDiligence").evaluate(new BigDecimal(20000))).isEqualTo("Enhanced");
        }
    }

    @Test
    void structured_input_data_are_records_of_the_model_class() throws Exception {
        var result = compile(TableDefinition.dmn(table("literals.dmn")), Severity.ERROR);
        Assertions.assertThat(result.sources().get("LiteralsModel"))
                .contains("public record Loan(java.math.BigDecimal principal, java.math.BigDecimal rate, java.math.BigDecimal months)");
        Assertions.assertThat(result.sources().get("PaymentInput")).contains("public record PaymentInput(LiteralsModel.Loan loan)");
        Assertions.assertThat(result.sources().get("StartsAt")).contains("public static java.time.temporal.TemporalAccessor evaluate(");

        var graph = graph("literals.dmn");
        try (var loader = compileAndLoad(result)) {
            var loanType = loader.loadClass(PACKAGE + ".LiteralsModel$Loan");
            var loan = loanType.getDeclaredConstructors()[0].newInstance(new BigDecimal(600000), new BigDecimal("0.0375"), new BigDecimal(360));
            Assertions.assertThat(decision(loader, "Payment").evaluate(loan)).isEqualTo(graph.evaluate("Payment",
                    Map.of("Loan", Map.of("principal", 600000, "rate", new BigDecimal("0.0375"), "months", 360))));
            Assertions.assertThat(decision(loader, "Payment").evaluate((Object) null)).isNull();
            Assertions.assertThat(decision(loader, "Greeting").evaluate("Ada")).isEqualTo("Hello Ada");
            Assertions.assertThat(decision(loader, "Due").evaluate(LocalDate.of(2025, 1, 31))).isEqualTo(LocalDate.of(2025, 2, 14));
            Assertions.assertThat(decision(loader, "StartsAt").evaluate(LocalDate.of(2025, 1, 31)))
                    .isEqualTo(LocalDate.of(2025, 1, 31).atStartOfDay(ZoneOffset.UTC));
            Assertions.assertThat(decision(loader, "Shift").evaluate(new BigDecimal(3))).isEqualTo(Duration.ofHours(3));
        }
    }

    @Test
    void years_and_months_durations_and_nested_contexts_cross_the_graph_as_periods_and_records() throws Exception {
        var dmn = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Terms" namespace="n">
                  <itemDefinition name="tTerm">
                    <itemComponent name="length"><typeRef>yearMonthDuration</typeRef></itemComponent>
                    <itemComponent name="extra"><itemComponent name="months"><typeRef>number</typeRef></itemComponent></itemComponent>
                  </itemDefinition>
                  <itemDefinition name="tLengths" isCollection="true"><typeRef>yearMonthDuration</typeRef></itemDefinition>
                  <inputData id="term" name="Term"><variable name="Term" typeRef="tTerm"/></inputData>
                  <inputData id="lengths" name="Lengths"><variable name="Lengths" typeRef="tLengths"/></inputData>
                  <decision id="longer" name="Longer">
                    <variable name="Longer" typeRef="yearMonthDuration"/>
                    <informationRequirement><requiredInput href="#term"/></informationRequirement>
                    <literalExpression><text>Term.length * 2</text></literalExpression>
                  </decision>
                  <decision id="months" name="Months">
                    <variable name="Months" typeRef="number"/>
                    <informationRequirement><requiredInput href="#term"/></informationRequirement>
                    <literalExpression><text>Term.extra.months + 1</text></literalExpression>
                  </decision>
                  <decision id="kept" name="Kept">
                    <variable name="Kept" typeRef="tLengths"/>
                    <informationRequirement><requiredDecision href="#longer"/></informationRequirement>
                    <literalExpression><text>Longer</text></literalExpression>
                  </decision>
                </definitions>""";
        var file = out.resolve("terms.dmn");
        Files.writeString(file, dmn, StandardCharsets.UTF_8);
        var result = compile(TableDefinition.dmn(file), Severity.ERROR);
        Assertions.assertThat(result.sources().get("TermsModel")).contains("public record Term(java.time.Period length, TermsModel.Term.Extra extra)",
                "public record Extra(java.math.BigDecimal months)");
        Assertions.assertThat(result.sources().get("Kept")).contains("public static java.util.List<java.time.Period> evaluate(KeptInput input)");

        try (var loader = compileAndLoad(result)) {
            var extra = loader.loadClass(PACKAGE + ".TermsModel$Term$Extra").getDeclaredConstructors()[0].newInstance(new BigDecimal(4));
            var term = loader.loadClass(PACKAGE + ".TermsModel$Term").getDeclaredConstructors()[0].newInstance(Period.ofMonths(14), extra);
            Assertions.assertThat(decision(loader, "Longer").evaluate(term)).isEqualTo(Period.of(2, 4, 0));
            Assertions.assertThat(decision(loader, "Months").evaluate(term)).isEqualTo(new BigDecimal(5));
            Assertions.assertThat(decision(loader, "Kept").evaluate(term)).isEqualTo(List.of(Period.of(2, 4, 0)));
        }
    }

    @Test
    void a_named_decision_compiles_alone_and_a_table_without_requirements_as_before() throws Exception {
        var monthly = compile(new TableDefinition(table("chain.dmn"), null, "Monthly Limit", null, null, List.of(), List.of()), Severity.ERROR);
        Assertions.assertThat(monthly.sources()).containsOnlyKeys("MonthlyLimitModel", "MonthlyLimitInput", "MonthlyLimit");
        Assertions.assertThat(monthly.reports()).containsOnlyKeys("MonthlyLimitModel", "MonthlyLimit");
        var named = compile(new TableDefinition(table("chain.dmn"), "Monthly", "Monthly Limit", null, null, List.of(), List.of()), Severity.ERROR);
        Assertions.assertThat(named.sources()).containsOnlyKeys("MonthlyModel", "MonthlyInput", "Monthly");
        try (var loader = compileAndLoad(named)) {
            Assertions.assertThat(decision(loader, "Monthly").evaluate(new BigDecimal(30), new BigDecimal(1200))).isEqualTo(new BigDecimal(200));
        }

        // Risk Category requires input data only: a table, as 0.6 compiled it
        var risk = compile(new TableDefinition(table("chain.dmn"), null, "Risk Category", null, null, List.of(), List.of()), Severity.ERROR);
        Assertions.assertThat(risk.table()).isNotNull();
        Assertions.assertThat(risk.model()).isNull();
        Assertions.assertThat(risk.summary()).isEqualTo("2 rules, UNIQUE");
        Assertions.assertThat(risk.sources()).containsOnlyKeys("RiskCategoryInput", "RiskCategoryOutput", "RiskCategory");
        Assertions.assertThat(risk.reports()).containsOnlyKeys("RiskCategory");
    }

    /**
     * Decision tables in the full FEEL language (#116): a column computed from the input data it reads, cells beyond the
     * simple unary tests, and output entries whose values are of several types, which the generated class gives as an
     * {@code Object}, each entry its own type (DMN 1.5, 10.3.2.10, step 3c).
     */
    @Test
    void tables_in_the_full_feel_language_compile_and_give_what_the_graph_gives() throws Exception {
        var result = compile(TableDefinition.dmn(table("feel.dmn")), Severity.ERROR);
        Assertions.assertThat(result.sources().get("BandInput"))
                .contains("public record BandInput(java.math.BigDecimal age, java.math.BigDecimal limit)");
        Assertions.assertThat(result.sources().get("Band")).contains("public static Object evaluate(BandInput input)");
        var graph = graph("feel.dmn");
        try (var loader = compileAndLoad(result)) {
            var band = decision(loader, "Band");
            var summary = decision(loader, "Summary");
            for (var age : List.of(10, 18, 30, 70)) {
                for (var limit : List.of(17, 40, 100)) {
                    var inputData = Map.<String, Object>of("Age", BigDecimal.valueOf(age), "Limit", BigDecimal.valueOf(limit));
                    Assertions.assertThat(band.evaluate(BigDecimal.valueOf(age), BigDecimal.valueOf(limit)))
                            .isEqualTo(graph.evaluate("Band", inputData));
                    Assertions.assertThat(summary.evaluate(BigDecimal.valueOf(age), BigDecimal.valueOf(limit)))
                            .isEqualTo(graph.evaluate("Summary", inputData));
                }
            }
            Assertions.assertThat(band.evaluate(new BigDecimal(30), new BigDecimal(100))).asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                    .isEqualByComparingTo("360");
            Assertions.assertThat(band.evaluate(new BigDecimal(30), new BigDecimal(20))).isEqualTo("over the limit");
            Assertions.assertThat(band.evaluate(new BigDecimal(10), new BigDecimal(20)))
                    .isEqualTo(List.of(new BigDecimal(10), new BigDecimal(20)));
        }
    }

    @Test
    void a_graphs_issues_fail_at_the_failing_severity_and_its_other_decisions_compile_below_it() throws Exception {
        var cycle = TableDefinition.dmn(table("cycle.dmn"));
        Assertions.assertThatThrownBy(() -> compile(cycle, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Decision model 'Cycle' has 2 validation issue(s) at ERROR or above:")
                .hasMessageContaining("decisions 'A' -> 'B' -> 'A' require one another in a cycle");

        var result = compile(cycle, null);
        Assertions.assertThat(result.sources()).containsOnlyKeys("CycleModel", "DInput", "D");
        Assertions.assertThat(result.report().issues()).hasSize(2);
        try (var loader = compileAndLoad(result)) {
            Assertions.assertThat(decision(loader, "D").evaluate(new BigDecimal(1))).isEqualTo(new BigDecimal(2));
        }
        var blocked = new TableDefinition(table("cycle.dmn"), null, "C", null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(blocked, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Decision model 'Cycle': Decision 'C' cannot be evaluated: ");
    }

    @Test
    void what_later_releases_bring_is_rejected_naming_its_issue() throws IOException {
        var file = out.resolve("bkm.dmn");
        Files.writeString(file, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <decision id="rate" name="Rate"><literalExpression>
                    <text>function(x) external {java: {class: "java.lang.Math", method signature: "abs(double)"}}</text>
                  </literalExpression></decision>
                  <decision id="a" name="A"><literalExpression><text>1</text></literalExpression></decision>
                </definitions>""", StandardCharsets.UTF_8);
        var definition = TableDefinition.dmn(file);
        Assertions.assertThatThrownBy(() -> compile(definition, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an external function (Java or PMML)")
                .hasMessageContaining("by design")
                .hasCauseInstanceOf(UnsupportedFeatureException.class);
    }

    /**
     * A file whose model uses what a later release plans compiles its decision table alone, as in 0.6, and the compiler
     * says so in one line for the build tool to log, naming the file, the feature and the release and issue that plan
     * it; a file it compiles as before, or as a graph, logs nothing.
     */
    @Test
    void a_table_compiled_alone_for_what_a_later_release_plans_is_logged_once() throws IOException {
        var file = out.resolve("later.dmn");
        Files.writeString(file, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <decision id="rate" name="Rate"><literalExpression>
                    <text>function(x) external {java: {class: "java.lang.Math", method signature: "abs(double)"}}</text>
                  </literalExpression></decision>
                  <decision id="tier" name="Tier"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>age</text></inputExpression></input>
                    <output name="tier" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"any"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""", StandardCharsets.UTF_8);
        var lines = new ArrayList<String>();
        var options = CompilerOptions.of(PACKAGE, "praecepta-maven-plugin").withLog(lines::add);

        var result = TableCompiler.compile(TableDefinition.dmn(file), options);
        Assertions.assertThat(result.table()).isNotNull();
        Assertions.assertThat(result.className()).isEqualTo("Tier");
        Assertions.assertThat(lines).singleElement().asString()
                .startsWith(file + ": its decision table is compiled alone, without its decision requirements graph, "
                        + "which uses what Praecepta does not evaluate: ")
                .contains("an external function (Java or PMML)");

        lines.clear();
        TableCompiler.compile(TableDefinition.dmn(table("chain.dmn")), options);
        TableCompiler.compile(new TableDefinition(table("chain.dmn"), null, "Risk Category", null, null, List.of(), List.of()), options);
        Assertions.assertThat(lines).isEmpty();
        // the options log nothing unless told where
        Assertions.assertThat(TableCompiler.compile(TableDefinition.dmn(file), CompilerOptions.of(PACKAGE, "praecepta-maven-plugin")).className())
                .isEqualTo("Tier");
    }

    /**
     * A decision whose value is a range or a function gives the engine's FeelRange or FeelFunction, and one that is
     * always null an Object, as the graph gives them.
     */
    @Test
    void ranges_functions_and_nulls_cross_the_generated_classes_as_the_graph_gives_them() throws Exception {
        var file = out.resolve("values.dmn");
        Files.writeString(file, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Values" namespace="n">
                  <inputData id="n" name="N"><variable name="N" typeRef="number"/></inputData>
                  <decision id="r" name="Window">
                    <informationRequirement><requiredInput href="#n"/></informationRequirement>
                    <literalExpression><text>[N..N + 10]</text></literalExpression>
                  </decision>
                  <decision id="f" name="Doubler">
                    <informationRequirement><requiredInput href="#n"/></informationRequirement>
                    <literalExpression><text>function(x) x * N</text></literalExpression>
                  </decision>
                  <decision id="z" name="Nothing">
                    <informationRequirement><requiredInput href="#n"/></informationRequirement>
                    <literalExpression><text>null</text></literalExpression>
                  </decision>
                </definitions>""", StandardCharsets.UTF_8);
        var result = compile(TableDefinition.dmn(file), Severity.ERROR);
        Assertions.assertThat(result.sources().get("Window"))
                .contains("public static io.github.brunopacheco1.praecepta.engine.feel.FeelRange evaluate(WindowInput input)");
        Assertions.assertThat(result.sources().get("Doubler"))
                .contains("public static io.github.brunopacheco1.praecepta.engine.feel.FeelFunction evaluate(DoublerInput input)");
        Assertions.assertThat(result.sources().get("Nothing")).contains("public static Object evaluate(NothingInput input)");
        try (var loader = compileAndLoad(result)) {
            var window = decision(loader, "Window").evaluate(new BigDecimal(2));
            // the generated classes load the engine of their own class loader
            var range = loader.loadClass("io.github.brunopacheco1.praecepta.engine.feel.FeelRange");
            Assertions.assertThat(window).isInstanceOf(range).hasToString(String.valueOf(graph(file).evaluate("Window", Map.of("N", 2))));
            var function = loader.loadClass("io.github.brunopacheco1.praecepta.engine.feel.FeelFunction");
            var doubler = decision(loader, "Doubler").evaluate(new BigDecimal(3));
            Assertions.assertThat(doubler).isInstanceOf(function);
            var invoke = function.getMethod("invoke", List.class, java.util.function.Consumer.class);
            Assertions.assertThat(invoke.invoke(doubler, List.of(new BigDecimal(2)), null)).asInstanceOf(InstanceOfAssertFactories.BIG_DECIMAL)
                    .isEqualByComparingTo("6");
            Assertions.assertThat(decision(loader, "Nothing").evaluate(new BigDecimal(3))).isNull();
        }
    }

    @Test
    void boxed_expressions_business_knowledge_models_and_the_tables_within_them_compile_and_give_what_the_graph_gives() throws Exception {
        var result = compile(TableDefinition.dmn(table("people.dmn")), Severity.WARNING);
        Assertions.assertThat(result.sources()).containsKeys("PersonSDescription", "Greeting");
        var graph = graph("people.dmn");
        var maps = decisions("people.dmn");
        try (var loader = compileAndLoad(result)) {
            var description = decision(loader, "PersonSDescription");
            var greeting = decision(loader, "Greeting");
            var person = loader.loadClass(PACKAGE + ".PeopleModel$Person").getDeclaredConstructors()[0];
            for (var who : List.of(List.of("Bob", "Male"), List.of("Eve", "Female"))) {
                var inputData = Map.<String, Object>of("Person", Map.of("Name", who.get(0), "Gender", who.get(1)));
                var record = person.newInstance(who.get(0), who.get(1));
                Assertions.assertThat(description.evaluate(record)).isEqualTo(graph.evaluate("Person's description", inputData));
                Assertions.assertThat(greeting.evaluate(record)).isEqualTo(graph.evaluate("Greeting", inputData));
                Assertions.assertThat(maps.evaluate("Greeting", Map.of("Person.Name", who.get(0), "Person.Gender", who.get(1))))
                        .containsExactly(Map.of("Greeting", String.valueOf(graph.evaluate("Greeting", inputData))));
            }
        }
    }

    @Test
    void a_model_that_imports_others_compiles_with_them_and_its_classes_read_them_at_run_time() throws Exception {
        var result = compile(TableDefinition.dmn(table("imports/main.dmn")), Severity.ERROR);

        Assertions.assertThat(result.imports()).containsOnlyKeys("MainModel-0.dmn", "MainModel-1.dmn");
        Assertions.assertThat(result.sources().get("MainModel")).contains("var imports = java.util.Map.of(\"https://example.com/people\", "
                + "\"MainModel-0.dmn\"");
        try (var loader = compileAndLoad(result)) {
            var welcome = loader.loadClass(PACKAGE + ".Welcome");
            var input = loader.loadClass(PACKAGE + ".WelcomeInput");
            var components = input.getRecordComponents();
            var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var person = components[i].getType().getDeclaredConstructors()[0];
                values[i] = components[i].getName().equals("guest") ? person.newInstance("Ada", new BigDecimal(36))
                        : person.newInstance("Bob", new BigDecimal(15));
            }
            var value = welcome.getMethod("evaluate", input).invoke(null, input.getDeclaredConstructors()[0].newInstance(values));
            Assertions.assertThat(value).isEqualTo("Hello Ada; host: Bob (minor)");
        }
    }

    @Test
    void an_import_whose_location_is_outside_the_models_directory_is_not_followed() throws IOException {
        var rules = Files.createDirectories(out.resolve("rules"));
        Files.copy(table("imports/people.dmn"), out.resolve("people.dmn"));
        var main = rules.resolve("main.dmn");
        Files.writeString(main, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="m" name="M" namespace="https://example.com/m">
                  <import name="people" namespace="https://example.com/people"
                          importType="https://www.omg.org/spec/DMN/20230324/MODEL/" locationURI="../people.dmn"/>
                  <decision id="d" name="D"><literalExpression><text>people.Greet("Ada")</text></literalExpression></decision>
                </definitions>""");

        Assertions.assertThatThrownBy(() -> compile(TableDefinition.dmn(main), Severity.ERROR))
                .hasMessageContaining("imports the model 'https://example.com/people' at '../people.dmn', which cannot be found");
    }

    private static DecisionGraph graph(Path file) {
        try (var dmn = Files.newInputStream(file)) {
            return DecisionGraph.read(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void two_decisions_whose_classes_would_clash_are_rejected() throws IOException {
        var file = out.resolve("clash.dmn");
        Files.writeString(file, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Clash" namespace="n">
                  <decision id="a" name="Total"><literalExpression><text>1</text></literalExpression></decision>
                  <decision id="b" name="Total Input"><literalExpression><text>2</text></literalExpression></decision>
                </definitions>""", StandardCharsets.UTF_8);
        var definition = TableDefinition.dmn(file);
        Assertions.assertThatThrownBy(() -> compile(definition, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Decision model 'Clash': decision 'Total' and decision 'Total Input' both generate the class TotalInput; rename one");
    }

    @Test
    void a_compiled_model_writes_its_dmn_and_sources_into_the_package_directories() throws Exception {
        var result = compile(TableDefinition.dmn(table("chain.dmn")), Severity.ERROR);
        var written = result.write(out.resolve("java"), out.resolve("resources"));
        Assertions.assertThat(written).hasSize(8).contains(out.resolve("java/com/acme/graph/MonthlyLimit.java"))
                .first().isEqualTo(out.resolve("resources/com/acme/graph/CreditModel.dmn"));
        try (var dmn = Files.newInputStream(written.getFirst())) {
            Assertions.assertThat(DecisionGraph.read(dmn).model()).isEqualTo(result.model());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** A generated decision class's {@code evaluate}, called with its input record's components. */
    private interface GeneratedDecision {
        Object evaluate(Object... components) throws Exception;
    }

    private static GeneratedDecision decision(ClassLoader loader, String className) throws ReflectiveOperationException {
        var input = loader.loadClass(PACKAGE + "." + className + "Input");
        var evaluate = loader.loadClass(PACKAGE + "." + className).getMethod("evaluate", input);
        Constructor<?> constructor = input.getDeclaredConstructors()[0];
        return components -> {
            try {
                return evaluate.invoke(null, constructor.newInstance(components));
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        };
    }

    private static CompiledTable compile(TableDefinition definition, Severity failOn) {
        return TableCompilerTest.compile(definition, PACKAGE, failOn);
    }

    private static Path table(String name) {
        try {
            return Path.of(GraphCompilerTest.class.getResource("/tables/graph/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DecisionGraph graph(String name) {
        try (var dmn = Files.newInputStream(table(name))) {
            return DecisionGraph.read(dmn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Decisions decisions(String name) {
        return Decisions.load(List.of(TableSource.of(table(name))), Severity.ERROR);
    }

    /** Writes the generated sources and DMN, compiles them against the engine jar only, and loads them. */
    private URLClassLoader compileAndLoad(CompiledTable result) throws Exception {
        var classes = out.resolve("classes-" + result.className());
        var files = new ArrayList<String>();
        for (var file : result.write(out.resolve("src-" + result.className()), classes)) {
            if (file.toString().endsWith(".java")) {
                files.add(file.toString());
            }
        }
        var engineJar = Path.of(PraeceptaEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var arguments = new ArrayList<>(List.of("-classpath", engineJar.toString(), "-d", classes.toString(), "-Xlint:all", "-Werror"));
        arguments.addAll(files);
        var errors = new java.io.ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
        Assertions.assertThat(status).as("javac:\n%s\n%s", errors, result.sources()).isZero();
        // the platform class loader as parent: only the JDK and the engine are visible
        return new URLClassLoader(new URL[] {classes.toUri().toURL(), engineJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
}
