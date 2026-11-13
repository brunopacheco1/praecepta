/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.tools.ToolProvider;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.integration.Decision;

/**
 * Compiles tables, then compiles the generated sources with the JDK's compiler and runs them against
 * nothing but the engine jar: what an application gets at runtime.
 */
class TableCompilerTest {

    private static final String PACKAGE = "com.acme.rules";

    @TempDir
    Path out;

    @Test
    void a_csv_table_becomes_typed_classes_backed_by_a_dmn_resource() throws Exception {
        var config = new TableDefinition(table("discount.csv"), "Discount", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("country", ColumnType.STRING, "\"PT\",\"ES\",\"FR\"", List.of()),
                        new ColumnDefinition("age", ColumnType.NUMBER, "[0..120]", List.of()),
                        new ColumnDefinition("since", ColumnType.DATE, null, List.of())),
                List.of(new ColumnDefinition("discount", ColumnType.NUMBER, null, List.of()),
                        new ColumnDefinition("tier", ColumnType.STRING, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.sources()).containsOnlyKeys("DiscountInput", "DiscountOutput", "Discount");
        Assertions.assertThat(result.sources().get("DiscountInput"))
                .contains("public record DiscountInput(Country country, java.math.BigDecimal age, java.time.LocalDate since)")
                .contains("public enum Country", "PT(\"PT\")");
        Assertions.assertThat(result.dmn()).contains("<inputValues", "&lt; date(\"2020-01-01\")");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".DiscountInput");
            var country = enumConstant(loader, PACKAGE + ".DiscountInput$Country", "PT");
            var decision = loader.loadClass(PACKAGE + ".Discount");
            var evaluate = decision.getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];

            var gold = (Optional<?>) evaluate.invoke(null, constructor.newInstance(country, new BigDecimal(30), LocalDate.of(2019, 5, 1)));
            Assertions.assertThat(gold).get().hasToString("DiscountOutput[discount=0.15, tier=gold]");

            var junior = (Optional<?>) evaluate.invoke(null, constructor.newInstance(null, new BigDecimal(12), null));
            Assertions.assertThat(junior).get().hasToString("DiscountOutput[discount=0, tier=junior]");

            // the declared value list of age is enforced by the generated class
            var tooOld = constructor.newInstance(country, new BigDecimal(130), null);
            Assertions.assertThatThrownBy(() -> evaluate.invoke(null, tooOld))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().hasMessageContaining("age");

            // and the gap analysis ran on the declared domains: FR adults are not covered
            var report = decision.getMethod("validationReport").invoke(null);
            Assertions.assertThat(report.toString()).contains(
                    "no rule matches age in [18..65), country = \"FR\"",
                    "no rule matches age in [65..120], country = \"FR\"");
        }
    }

    @Test
    void a_dmn_table_keeps_its_own_columns_and_value_lists() throws Exception {
        var config = new TableDefinition(table("camunda-modeler-discount.dmn"), null, null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.className()).isEqualTo("Discount");
        Assertions.assertThat(result.sources().get("DiscountInput"))
                .contains("public record DiscountInput(Country country, java.math.BigDecimal customerAge, java.time.LocalDate since)");
        Assertions.assertThat(result.sources().get("Discount")).contains("public static java.util.List<DiscountOutput> evaluate");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".DiscountInput");
            var evaluate = loader.loadClass(PACKAGE + ".Discount").getMethod("evaluate", input);
            var es = enumConstant(loader, PACKAGE + ".DiscountInput$Country", "ES");
            var request = input.getDeclaredConstructors()[0].newInstance(es, new BigDecimal(40), LocalDate.of(2010, 1, 1));
            var outputs = (List<?>) evaluate.invoke(null, request);
            Assertions.assertThat(outputs).map(Object::toString)
                    .containsExactly("DiscountOutput[discount=0.15, tier=gold]", "DiscountOutput[discount=0.05, tier=silver]");
        }
    }

    @Test
    void inputs_that_dmn_cells_compare_with_become_input_components() throws Exception {
        var config = new TableDefinition(table("limits.dmn"), "Limits", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("LimitsInput"))
                .contains("public record LimitsInput(java.math.BigDecimal amount, java.math.BigDecimal limit)");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".LimitsInput");
            var evaluate = loader.loadClass(PACKAGE + ".Limits").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(120), new BigDecimal(100))))
                    .get().hasToString("LimitsOutput[decision=review]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(80), new BigDecimal(100))))
                    .get().hasToString("LimitsOutput[decision=approve]");
        }
    }

    /** DMN 1.5, 10.3.2.10: when no rule matches, the table gives its default output values (#79). */
    @Test
    void a_dmn_tables_default_output_is_what_the_generated_class_gives_when_no_rule_matches() throws Exception {
        var config = new TableDefinition(table("approvals.dmn"), "Approvals", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        // the default's time has a zone, as the rules' have, so the component is an OffsetTime
        Assertions.assertThat(result.sources().get("ApprovalsOutput"))
                .contains("public record ApprovalsOutput(String status, String rate, java.time.OffsetTime until)");
        Assertions.assertThat(result.sources().get("Approvals"))
                .contains("@return the outputs of every matching rule, in hit-policy order; the table's default output alone if no rule matches");
        Assertions.assertThat(result.dmn())
                .contains("<defaultOutputEntry id=\"output_default_0\"><text>\"declined\"</text></defaultOutputEntry>")
                .contains("<defaultOutputEntry id=\"output_default_2\"><text>time(\"17:00:00Z\")</text></defaultOutputEntry>");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ApprovalsInput");
            var evaluate = loader.loadClass(PACKAGE + ".Approvals").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((List<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(500))))
                    .hasToString("[ApprovalsOutput[status=declined, rate=null, until=17:00Z]]");
            Assertions.assertThat((List<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(650))))
                    .hasToString("[ApprovalsOutput[status=approved, rate=standard, until=null]]");
        }
    }

    @Test
    void a_default_output_without_a_zone_beside_rule_outputs_with_one_is_rejected() throws Exception {
        var mixed = out.resolve("mixed-default.dmn");
        Files.writeString(mixed, Files.readString(table("approvals.dmn")).replace("time(\"17:00:00Z\")", "time(\"17:00:00\")"));
        var config = new TableDefinition(mixed, "Approvals", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'approvals': output 'until' takes time values both with and without a time zone, "
                        + "but its generated record component holds one kind");
    }

    @Test
    void a_single_hit_table_with_a_default_says_so_in_its_evaluate_method() throws Exception {
        var first = out.resolve("first.dmn");
        Files.writeString(first, Files.readString(table("approvals.dmn")).replace("RULE ORDER", "FIRST"));
        var result = compile(new TableDefinition(first, "Approvals", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("Approvals"))
                .contains("@return the selected output, or the table's default output if no rule matches");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ApprovalsInput");
            var evaluate = loader.loadClass(PACKAGE + ".Approvals").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(500))))
                    .get().hasToString("ApprovalsOutput[status=declined, rate=null, until=17:00Z]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(650))))
                    .get().hasToString("ApprovalsOutput[status=approved, rate=standard, until=null]");
        }
    }

    /**
     * A default of {@code null} is a default (#92): with several outputs whose defaults are all {@code null}, no rule
     * matching gives an output whose components are all null, a context of nulls in DMN (10.3.2.10, Table 46), in
     * the generated class and in the map-based engine alike; the DMN resource the generated class reads keeps them.
     */
    @Test
    void null_defaults_reach_the_generated_class_and_the_map_based_engine() throws Exception {
        var dmn = out.resolve("null-defaults.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="approval" name="approval"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                    <output name="status" typeRef="string"><defaultOutputEntry><text>null</text></defaultOutputEntry></output>
                    <output name="rate" typeRef="number"><defaultOutputEntry><text>null</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>&gt;= 700</text></inputEntry>
                      <outputEntry><text>"approved"</text></outputEntry><outputEntry><text>1.5</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Approval", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.dmn())
                .contains("<defaultOutputEntry id=\"output_default_0\"><text>null</text></defaultOutputEntry>")
                .contains("<defaultOutputEntry id=\"output_default_1\"><text>null</text></defaultOutputEntry>");
        Assertions.assertThat(result.sources().get("Approval"))
                .contains("@return the selected output, or the table's default output if no rule matches");
        var decision = Decision.of("approval", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ApprovalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Approval").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(500))))
                    .get().hasToString("ApprovalOutput[status=null, rate=null]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(new BigDecimal(800))))
                    .get().hasToString("ApprovalOutput[status=approved, rate=1.5]");
        }
        var nulls = new HashMap<String, String>();
        nulls.put("status", null);
        nulls.put("rate", null);
        Assertions.assertThat(decision.evaluate(Map.of("score", "500"))).containsExactly(nulls);

        // without the defaults, no rule matching gives nothing
        Files.writeString(dmn, Files.readString(dmn).replace("<defaultOutputEntry><text>null</text></defaultOutputEntry>", ""));
        var none = compile(new TableDefinition(dmn, "Approval", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(none.dmn()).doesNotContain("defaultOutputEntry");
        Assertions.assertThat(none.sources().get("Approval")).contains("@return the selected output, or empty if no rule matches");
        try (var loader = compileAndLoad(none)) {
            var input = loader.loadClass(PACKAGE + ".ApprovalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Approval").getMethod("evaluate", input);
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0].newInstance(new BigDecimal(500))))
                    .isEmpty();
        }
        Assertions.assertThat(Decision.of("approval", DmnReader.read(Files.newInputStream(dmn))).evaluate(Map.of("score", "500"))).isEmpty();
    }

    /** The default output is a COLLECT aggregation's result when no rule matches, COUNT's too (DMN 1.5, 10.3.2.10). */
    @ParameterizedTest
    @CsvSource({"SUM, 1234567890123456789012345678901235", "COUNT, 2", "MAX, 1234567890123456789012345678901234"})
    void a_generated_aggregation_gives_the_default_output_when_no_rule_matches(String aggregation, String islands) throws Exception {
        var result = compile(new TableDefinition(totals(aggregation), "Total", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".TotalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Total").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("mainland")))
                    .get().hasToString("TotalOutput[total=9.90]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("islands")))
                    .get().hasToString("TotalOutput[total=" + islands + "]");
        }
    }

    /**
     * Without a default output, a generated aggregation gives nothing when no rule matches, COUNT included: DMN 1.5,
     * 10.3.2.10, step 2, "else DTI=null", before any aggregation.
     */
    @ParameterizedTest
    @CsvSource({"SUM, 1234567890123456789012345678901235", "MIN, 0.6", "MAX, 1234567890123456789012345678901234", "COUNT, 2"})
    void a_generated_aggregation_without_a_default_gives_nothing_when_no_rule_matches(String aggregation, String islands)
            throws Exception {
        var dmn = totals(aggregation);
        Files.writeString(dmn, Files.readString(dmn).replace("<defaultOutputEntry><text>9.90</text></defaultOutputEntry>", ""));
        var result = compile(new TableDefinition(dmn, "Total", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("Total")).contains("or empty if no rule matches");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".TotalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Total").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("mainland"))).isEmpty();
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("islands")))
                    .get().hasToString("TotalOutput[total=" + islands + "]");
        }
    }

    /**
     * A sum of more than 34 digits is rounded to FEEL's precision (DMN 1.5, 10.3.2.3.1) in the generated class, as
     * the framework integrations' map-based engine rounds it, so the same table gives the same sum in both.
     */
    @Test
    void a_generated_sum_and_the_frameworks_map_based_sum_agree() throws Exception {
        var dmn = totals("SUM");
        var result = compile(new TableDefinition(dmn, "Total", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        var decision = Decision.of("total", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".TotalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Total").getMethod("evaluate", input);
            var output = ((Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0].newInstance("islands"))).orElseThrow();
            var generated = (BigDecimal) output.getClass().getMethod("total").invoke(output);
            Assertions.assertThat(decision.evaluate(Map.of("zone", "islands"))).containsExactly(Map.of("total", generated.toPlainString()));
            Assertions.assertThat(generated).isEqualByComparingTo("1234567890123456789012345678901235");
        }
    }

    /**
     * FEEL's {@code sum}, {@code min} and {@code max} are null for a list that holds null (DMN 1.5, 10.3.4.4), so a
     * generated SUM, MIN or MAX is missing where a matching rule's output is, as the map-based engine of the framework
     * integrations is; COUNT counts that rule (#90).
     */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // aggregation, islands without extra, islands with extra 1
            "SUM,   null, 12",
            "MIN,   null, 2",
            "MAX,   null, 10",
            "COUNT, 2,    2",
    })
    void a_generated_aggregation_is_missing_where_an_output_is_as_the_map_based_one(String aggregation, String withoutExtra,
            String withExtra) throws Exception {
        var dmn = out.resolve("extras-" + aggregation + ".dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="total" name="total"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <input><inputExpression typeRef="number"><text>extra</text></inputExpression></input>
                    <output name="total" typeRef="number"/>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>10</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>extra * 2</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(aggregation));
        var result = compile(new TableDefinition(dmn, "Total", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        var decision = Decision.of("total", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".TotalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Total").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("islands", null)))
                    .get().hasToString("TotalOutput[total=" + withoutExtra + "]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("islands", BigDecimal.ONE)))
                    .get().hasToString("TotalOutput[total=" + withExtra + "]");
        }
        var missing = new HashMap<String, String>();
        missing.put("total", withoutExtra);
        Assertions.assertThat(decision.evaluate(Map.of("zone", "islands"))).containsExactly(missing);
        Assertions.assertThat(decision.evaluate(Map.of("zone", "islands", "extra", "1"))).containsExactly(Map.of("total", withExtra));
    }

    /**
     * The 0.6 features together in generated code, as in the map-based engine: output expressions over a date (#85),
     * one of which has no result on 31 January, a {@code null} default output (#92), and cells with an at-literal and
     * comments (#81). The generated code aggregates numbers only, so the MIN of such dates (#90) is checked in the
     * map-based engine's tests.
     */
    @Test
    void generated_code_computes_dates_and_gives_a_null_default_as_the_map_based_engine() throws Exception {
        var dmn = out.resolve("due.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="due" name="due"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="date"><text>sent</text></inputExpression></input>
                    <input><inputExpression typeRef="string"><text>service</text></inputExpression></input>
                    <output name="due" typeRef="date"><defaultOutputEntry><text>null</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>&gt;= @"2025-01-01" // this year</text></inputEntry><inputEntry><text>"express"</text></inputEntry>
                      <outputEntry><text>sent + @"P1M" /* a month later, if that day exists */</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>"standard", "express"</text></inputEntry>
                      <outputEntry><text>sent + duration("P14D")</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Due", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        var decision = Decision.of("due", DmnReader.read(Files.newInputStream(dmn)));
        var cases = new String[][] {
            {"2025-01-10", "express", "2025-02-10"},
            {"2025-01-31", "express", null}, // no 31 February: FEEL gives null
            {"2025-01-31", "standard", "2025-02-14"},
            {"2024-12-31", "express", "2025-01-14"},
            {"2025-01-10", "economy", null}, // no rule: the null default
            {null, "standard", null},
        };
        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".DueInput");
            var evaluate = loader.loadClass(PACKAGE + ".Due").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            for (var row : cases) {
                var sent = row[0] == null ? null : LocalDate.parse(row[0]);
                Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(sent, row[1])))
                        .as("%s", Arrays.toString(row))
                        .get().hasToString("DueOutput[due=" + row[2] + "]");
                var values = new HashMap<String, String>();
                values.put("sent", row[0]);
                values.put("service", row[1]);
                var expected = new HashMap<String, String>();
                expected.put("due", row[2]);
                Assertions.assertThat(decision.evaluate(values)).as("%s", Arrays.toString(row)).containsExactly(expected);
            }
        }
    }

    /** A COLLECT table aggregating one number output whose default is 9.90; on the islands, two rules sum to 35 digits. */
    private Path totals(String aggregation) throws IOException {
        var dmn = out.resolve("totals-" + aggregation + ".dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="total" name="total"><decisionTable hitPolicy="COLLECT" aggregation="%s">
                    <input><inputExpression typeRef="string"><text>zone</text></inputExpression></input>
                    <output name="total" typeRef="number"><defaultOutputEntry><text>9.90</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>"islands"</text></inputEntry>
                      <outputEntry><text>1234567890123456789012345678901234</text></outputEntry></rule>
                    <rule><inputEntry><text>"islands"</text></inputEntry><outputEntry><text>0.6</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(aggregation));
        return dmn;
    }

    @Test
    void list_columns_become_lists_matched_element_by_element() throws Exception {
        var config = new TableDefinition(table("nationalities.csv"), "Offer", null, HitPolicy.FIRST, null,
                List.of(ColumnDefinition.listInput("nationalities", ColumnType.STRING, "\"PT\",\"ES\",\"BR\""),
                        ColumnDefinition.listInput("ages", ColumnType.NUMBER, null)),
                List.of(new ColumnDefinition("offer", ColumnType.STRING, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.sources().get("OfferInput"))
                .contains("public record OfferInput(java.util.List<String> nationalities, java.util.List<java.math.BigDecimal> ages)");
        Assertions.assertThat(result.sources().get("Offer"))
                .contains(".listInput(\"nationalities\", OfferInput::nationalities)",
                        ".listInput(\"ages\", io.github.brunopacheco1.praecepta.engine.beans.ColumnType.NUMBER, OfferInput::ages)");
        Assertions.assertThat(result.dmn()).contains("list contains(?, \"PT\") or list contains(?, \"ES\")", "isCollection=\"true\"");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".OfferInput");
            var evaluate = loader.loadClass(PACKAGE + ".Offer").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(List.of("BR", "ES"), List.of())))
                    .get().hasToString("OfferOutput[offer=iberian]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(List.of("BR"), List.of(new BigDecimal(70)))))
                    .get().hasToString("OfferOutput[offer=senior]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(null, null)))
                    .get().hasToString("OfferOutput[offer=standard]");
            // a missing element: null = "PT" is false, and null >= 65 unknown, as FEEL has them (#84)
            Assertions.assertThat((Optional<?>) evaluate.invoke(null,
                            constructor.newInstance(Arrays.asList("BR", null), Arrays.asList(new BigDecimal(70), null))))
                    .get().hasToString("OfferOutput[offer=senior]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null,
                            constructor.newInstance(Arrays.asList((String) null), Arrays.asList((BigDecimal) null))))
                    .get().hasToString("OfferOutput[offer=standard]");
            // the value list applies to the elements
            var unknown = constructor.newInstance(List.of("FR"), null);
            Assertions.assertThatThrownBy(() -> evaluate.invoke(null, unknown))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().hasMessageContaining("nationalities=FR");
        }
    }

    @Test
    void duration_columns_become_durations_and_periods() throws Exception {
        var config = new TableDefinition(table("waits.csv"), "Wait", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("delay", ColumnType.DAY_TIME_DURATION, null, List.of()),
                        new ColumnDefinition("tenure", ColumnType.YEAR_MONTH_DURATION, null, List.of())),
                List.of(new ColumnDefinition("extension", ColumnType.DAY_TIME_DURATION, null, List.of()),
                        new ColumnDefinition("plan", ColumnType.STRING, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.sources().get("WaitInput"))
                .contains("public record WaitInput(java.time.Duration delay, java.time.Period tenure)");
        Assertions.assertThat(result.dmn()).contains("typeRef=\"dayTimeDuration\"", "typeRef=\"yearMonthDuration\"",
                "&gt;= duration(\"P1Y\")", "duration(\"P2DT6H\")");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".WaitInput");
            var evaluate = loader.loadClass(PACKAGE + ".Wait").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            var waited = (Optional<?>) evaluate.invoke(null, constructor.newInstance(Duration.ofHours(30), Period.ofMonths(14)));
            Assertions.assertThat(waited).get().hasToString("WaitOutput[extension=PT54H, plan=long]");
            var brief = (Optional<?>) evaluate.invoke(null, constructor.newInstance(Duration.ofHours(2), null));
            Assertions.assertThat(brief).get().hasToString("WaitOutput[extension=PT12H, plan=short]");
        }
    }

    @Test
    void boolean_time_and_date_time_columns_become_java_types() throws Exception {
        var config = new TableDefinition(table("shifts.csv"), "Shift", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("overtime", ColumnType.BOOLEAN, null, List.of()),
                        new ColumnDefinition("start", ColumnType.TIME, null, List.of()),
                        new ColumnDefinition("at", ColumnType.DATE_TIME, null, List.of())),
                List.of(new ColumnDefinition("paid", ColumnType.BOOLEAN, null, List.of()),
                        new ColumnDefinition("until", ColumnType.TIME, null, List.of()),
                        new ColumnDefinition("reviewed", ColumnType.DATE_TIME, null, List.of()),
                        new ColumnDefinition("rate", ColumnType.STRING, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.sources().get("ShiftInput"))
                .contains("public record ShiftInput(Boolean overtime, java.time.LocalTime start, java.time.LocalDateTime at)");
        Assertions.assertThat(result.sources().get("ShiftOutput"))
                .contains("public record ShiftOutput(Boolean paid, java.time.LocalTime until, java.time.LocalDateTime reviewed, String rate)");
        Assertions.assertThat(result.dmn()).contains("typeRef=\"boolean\"", "typeRef=\"time\"", "typeRef=\"dateTime\"",
                "&gt;= time(\"22:00:00\")", "&lt; time(\"06:00:00\")", "date and time(\"2025-01-02T09:30:00\")");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ShiftInput");
            var evaluate = loader.loadClass(PACKAGE + ".Shift").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            var night = (Optional<?>) evaluate.invoke(null, constructor.newInstance(false, LocalTime.of(23, 0), LocalDateTime.of(2025, 3, 4, 23, 0)));
            Assertions.assertThat(night).get().hasToString("ShiftOutput[paid=true, until=06:00, reviewed=null, rate=night]");
            var overtime = (Optional<?>) evaluate.invoke(null, constructor.newInstance(true, null, null));
            Assertions.assertThat(overtime).get().hasToString("ShiftOutput[paid=true, until=null, reviewed=2025-01-02T09:30, rate=double]");
            var christmasNoon = LocalDateTime.of(2025, 12, 25, 12, 0);
            var christmas = (Optional<?>) evaluate.invoke(null, constructor.newInstance(false, LocalTime.NOON, christmasNoon));
            Assertions.assertThat(christmas).get().hasToString("ShiftOutput[paid=false, until=null, reviewed=null, rate=holiday]");
        }
    }

    @Test
    void times_and_date_times_written_with_a_zone_become_offset_times_and_zoned_date_times() throws Exception {
        var config = new TableDefinition(table("deliveries.csv"), "Delivery", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("cutOff", ColumnType.TIME, null, List.of()),
                        new ColumnDefinition("at", ColumnType.DATE_TIME, null, List.of())),
                List.of(new ColumnDefinition("slot", ColumnType.STRING, null, List.of()),
                        new ColumnDefinition("due", ColumnType.DATE_TIME, null, List.of())));
        var result = compile(config, PACKAGE, Severity.WARNING);

        Assertions.assertThat(result.sources().get("DeliveryInput"))
                .contains("public record DeliveryInput(java.time.OffsetTime cutOff, java.time.ZonedDateTime at)");
        Assertions.assertThat(result.sources().get("DeliveryOutput"))
                .contains("public record DeliveryOutput(String slot, java.time.ZonedDateTime due)",
                        "io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals.parseZonedDateTime(value)");
        Assertions.assertThat(result.sources().get("Delivery"))
                .contains(".offsetTimeInput(\"cutOff\", DeliveryInput::cutOff)", ".zonedDateTimeInput(\"at\", DeliveryInput::at)");
        Assertions.assertThat(result.dmn()).contains("&lt; time(\"12:00:00+01:00\")", "date and time(\"2025-12-24T09:00:00@Europe/Paris\")");
        Assertions.assertThat(result.report().isEmpty()).as("validated as the record gives values: with a zone only").isTrue();

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".DeliveryInput");
            var evaluate = loader.loadClass(PACKAGE + ".Delivery").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // 10:30Z is 11:30+01:00, before noon in Paris
            var morning = (Optional<?>) evaluate.invoke(null, constructor.newInstance(OffsetTime.of(10, 30, 0, 0, ZoneOffset.UTC), null));
            Assertions.assertThat(morning).get().hasToString("DeliveryOutput[slot=morning, due=2025-12-24T09:00+01:00[Europe/Paris]]");
            var eve = ZonedDateTime.of(2025, 12, 23, 22, 59, 0, 0, ZoneOffset.UTC);
            var afternoon = (Optional<?>) evaluate.invoke(null, constructor.newInstance(OffsetTime.of(11, 0, 0, 0, ZoneOffset.UTC), eve));
            Assertions.assertThat(afternoon).get().hasToString("DeliveryOutput[slot=afternoon, due=2025-12-24T15:00Z]");
        }
    }

    @Test
    void list_columns_of_times_written_with_a_zone_become_lists_of_offset_times() throws Exception {
        var config = new TableDefinition(table("windows.csv"), "Window", null, HitPolicy.FIRST, null,
                List.of(ColumnDefinition.listInput("slots", ColumnType.TIME, null)),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        Assertions.assertThat(result.sources().get("WindowInput"))
                .contains("public record WindowInput(java.util.List<java.time.OffsetTime> slots)");
        Assertions.assertThat(result.sources().get("Window"))
                .contains(".listInput(\"slots\", io.github.brunopacheco1.praecepta.engine.beans.ColumnType.TIME, WindowInput::slots)");
        Assertions.assertThat(result.dmn()).contains("some x in ? satisfies x &gt;= time(\"12:00:00+01:00\")", "isCollection=\"true\"");
        // the same table from DMN, whose list cells use FEEL's list functions, takes the zone too
        var fromDmn = DmnReader.read(new ByteArrayInputStream(result.dmn().getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(TimeZones.of(fromDmn).inputs()).containsExactly("slots");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".WindowInput");
            var evaluate = loader.loadClass(PACKAGE + ".Window").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // 13:00+02:00 is 11:00Z, within [08:00Z..12:00Z]
            var within = List.of(OffsetTime.of(13, 0, 0, 0, ZoneOffset.ofHours(2)));
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(within))).get().hasToString("WindowOutput[fee=5]");
            var late = List.of(OffsetTime.of(7, 0, 0, 0, ZoneOffset.UTC), OffsetTime.of(14, 0, 0, 0, ZoneOffset.ofHours(1)));
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(late))).get().hasToString("WindowOutput[fee=3]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(List.of()))).get().hasToString("WindowOutput[fee=9]");
        }
    }

    @Test
    void inputs_compared_with_a_zoned_column_take_a_zone_too() throws Exception {
        var config = new TableDefinition(table("cutoffs.dmn"), "Cutoffs", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("CutoffsInput"))
                .contains("public record CutoffsInput(java.time.OffsetTime at, java.time.OffsetTime limit)");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".CutoffsInput");
            var evaluate = loader.loadClass(PACKAGE + ".Cutoffs").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            var nineInParis = OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1));
            var eightUtc = OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC);
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(nineInParis, eightUtc)))
                    .as("the same instant is not later").get().hasToString("CutoffsOutput[decision=on time]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(nineInParis.plusNanos(1), eightUtc)))
                    .get().hasToString("CutoffsOutput[decision=late]");
        }
    }

    @Test
    void a_column_with_times_with_and_without_a_zone_is_rejected() {
        var config = new TableDefinition(table("mixed-times.csv"), "Mixed", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("start", ColumnType.TIME, null, List.of())),
                List.of(new ColumnDefinition("rate", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'mixed-times': input 'start' takes time values both with and without a time zone, "
                        + "but its generated record component holds one kind");
    }

    @Test
    void times_written_with_a_zone_id_become_zoned_times() throws Exception {
        var config = new TableDefinition(table("calls.dmn"), "Calls", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("CallsInput"))
                .contains("public record CallsInput(io.github.brunopacheco1.praecepta.engine.feel.ZonedTime at)");
        Assertions.assertThat(result.sources().get("CallsOutput"))
                .contains("public record CallsOutput(String desk, io.github.brunopacheco1.praecepta.engine.feel.ZonedTime opens)",
                        "io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals.parseZonedTime(value)");
        Assertions.assertThat(result.sources().get("Calls")).contains(".zonedTimeInput(\"at\", CallsInput::at)");
        Assertions.assertThat(result.dmn()).contains("[time(\"09:00:00@Europe/Paris\")..time(\"17:00:00@Europe/Paris\"))");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".CallsInput");
            var evaluate = loader.loadClass(PACKAGE + ".Calls").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // the generated code's ZonedTime is the engine's, as the loader holds it
            var parse = loader.loadClass("io.github.brunopacheco1.praecepta.engine.feel.ZonedTime").getMethod("parse", String.class);
            var open = (Optional<?>) evaluate.invoke(null, constructor.newInstance(parse.invoke(null, "10:00@Europe/Paris")));
            Assertions.assertThat(open).get().hasToString("CallsOutput[desk=Paris, opens=09:00:00@Europe/Paris]");
            // a time in another zone does not compare with the cell's, as DMN 1.5 leaves it open
            var dhaka = (Optional<?>) evaluate.invoke(null, constructor.newInstance(parse.invoke(null, "10:00@Asia/Dhaka")));
            Assertions.assertThat(dhaka).get().hasToString("CallsOutput[desk=closed, opens=null]");
        }
    }

    @Test
    void outputs_naming_a_zoned_input_and_zoned_time_outputs_keep_their_zone() throws Exception {
        var config = new TableDefinition(table("appointments.dmn"), "Appointment", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("AppointmentInput")).contains("public record AppointmentInput(java.time.ZonedDateTime at)");
        Assertions.assertThat(result.sources().get("AppointmentOutput"))
                .contains("public record AppointmentOutput(java.time.ZonedDateTime confirmed, java.time.OffsetTime opens)");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".AppointmentInput");
            var evaluate = loader.loadClass(PACKAGE + ".Appointment").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // 02:30 happens twice in Paris that night: the later one, at +01:00, keeps its instant
            var later = ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, ZoneId.of("Europe/Paris")).withLaterOffsetAtOverlap();
            for (var at : List.of(later, later.withEarlierOffsetAtOverlap(), ZonedDateTime.of(2025, 6, 1, 8, 0, 0, 0, ZoneOffset.ofHours(-5)))) {
                var output = ((Optional<?>) evaluate.invoke(null, constructor.newInstance(at))).orElseThrow();
                Assertions.assertThat(output.getClass().getMethod("confirmed").invoke(output)).as("%s", at).isEqualTo(at);
                Assertions.assertThat(output.getClass().getMethod("opens").invoke(output))
                        .isEqualTo(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(1)));
            }
        }
    }

    @Test
    void inputs_a_cell_links_must_be_written_with_one_kind_of_time() {
        var config = new TableDefinition(table("linked-times.dmn"), "Linked", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'linked': input 'start' takes time values both with and without a time zone, "
                        + "but its generated record component holds one kind");
    }

    @Test
    void a_time_output_that_is_not_a_time_is_rejected() {
        var config = new TableDefinition(table("bad-time-output.csv"), "Opening", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("start", ColumnType.TIME, null, List.of())),
                List.of(new ColumnDefinition("opens", ColumnType.TIME, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'bad-time-output': output 'opens' value 'noon' is not a time");
    }

    @Test
    void dmn_output_expressions_and_named_list_inputs_run_in_generated_code() throws Exception {
        var config = new TableDefinition(table("flu.dmn"), "Flu", null, null, null, List.of(), List.of());
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("FluInput"))
                .contains("public record FluInput(String symptom, java.math.BigDecimal days, java.util.List<String> fluSymptoms)");
        Assertions.assertThat(result.sources().get("FluOutput")).contains("public record FluOutput(String message, java.math.BigDecimal hours)");
        Assertions.assertThat(result.sources().get("Flu")).contains(".listInput(\"Flu Symptoms\", FluInput::fluSymptoms)");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".FluInput");
            var evaluate = loader.loadClass(PACKAGE + ".Flu").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            var flu = List.of("fever", "cough");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("cough", new BigDecimal("2.5"), flu)))
                    .get().hasToString("FluOutput[message=cough is in the list of Flu symptoms, hours=60]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("rash", new BigDecimal(2), flu)))
                    .get().hasToString("FluOutput[message=rash is not a Flu symptom, hours=0]");
            // a missing operand gives a missing output
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("fever", null, flu)))
                    .get().hasToString("FluOutput[message=fever is in the list of Flu symptoms, hours=null]");
        }
    }

    @Test
    void collect_sum_tables_return_the_aggregate() throws Exception {
        var config = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("FeesInput")).contains("public record FeesInput(String zone)");

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".FeesInput");
            var evaluate = loader.loadClass(PACKAGE + ".Fees").getMethod("evaluate", input);
            var islands = (Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0].newInstance("islands"));
            Assertions.assertThat(islands).get().hasToString("FeesOutput[fee=17.5]");
        }
    }

    @Test
    void collect_count_tables_return_the_number_of_matching_rules() throws Exception {
        var config = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.COUNT,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        var result = compile(config, PACKAGE, Severity.ERROR);

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".FeesInput");
            var evaluate = loader.loadClass(PACKAGE + ".Fees").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("islands")))
                    .get().hasToString("FeesOutput[fee=2]");
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance("mainland")))
                    .get().hasToString("FeesOutput[fee=1]");
        }
    }

    @Test
    void validation_errors_fail_the_build_with_the_report() {
        var config = new TableDefinition(table("overlapping.csv"), "Tier", null, HitPolicy.UNIQUE, null,
                List.of(new ColumnDefinition("country", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("tier", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table 'overlapping'")
                .hasMessageContaining("overlap");
        // with failOn NONE the report is returned instead
        Assertions.assertThat(compile(config, PACKAGE, null).report().issues()).isNotEmpty();
    }

    /** DMN 1.5, 10.3.2.10: "A complete decision table SHALL NOT specify a default output value" (#91): an ERROR. */
    @Test
    void a_default_output_on_a_complete_table_fails_the_build() throws IOException {
        var dmn = out.resolve("complete.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="grade" name="grade"><decisionTable hitPolicy="UNIQUE">
                    <input><inputExpression typeRef="boolean"><text>vip</text></inputExpression></input>
                    <output name="grade" typeRef="string"><defaultOutputEntry><text>"C"</text></defaultOutputEntry></output>
                    <rule><inputEntry><text>true</text></inputEntry><outputEntry><text>"A"</text></outputEntry></rule>
                    <rule><inputEntry><text>false</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
                    <rule><inputEntry><text>null</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var config = new TableDefinition(dmn, "Grade", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table 'grade'")
                .hasMessageContaining("ERROR every input matches a rule, so the default output is never given");
        Assertions.assertThat(compile(config, PACKAGE, null).report().issues())
                .extracting(issue -> issue.kind().name())
                .containsExactly("UNREACHABLE_DEFAULT_OUTPUT");
        // without the rule for a missing value, a missing vip gives the default
        Files.writeString(dmn, Files.readString(dmn).replace("<rule><inputEntry><text>null</text></inputEntry>"
                + "<outputEntry><text>\"B\"</text></outputEntry></rule>", ""));
        Assertions.assertThat(compile(config, PACKAGE, Severity.ERROR).report().issues()).isEmpty();
    }

    @Test
    void configuration_mistakes_are_explained() {
        var dmnWithPolicy = new TableDefinition(table("camunda-modeler-discount.dmn"), null, null, HitPolicy.FIRST, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(dmnWithPolicy, PACKAGE, Severity.ERROR))
                .hasMessageContaining("a DMN model declares its own hit policy and columns");

        var csvWithoutPolicy = new TableDefinition(table("fees.csv"), null, null, null, null,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(csvWithoutPolicy, PACKAGE, Severity.ERROR))
                .hasMessageContaining("<hitPolicy> is required");

        var sumOfText = new TableDefinition(table("fees.csv"), null, null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(sumOfText, PACKAGE, Severity.ERROR))
                .hasMessageContaining("SUM aggregation needs a number output, not a string");

        var countOfText = new TableDefinition(table("fees.csv"), null, null, HitPolicy.COLLECT, AggregationOperator.COUNT,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> compile(countOfText, PACKAGE, Severity.ERROR))
                .hasMessageContaining("the generated code supports COUNT aggregation over a number output only, not a string");

        var missing = new TableDefinition(Path.of("nope.xlsx"), null, null, HitPolicy.FIRST, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(missing, PACKAGE, Severity.ERROR))
                .hasMessageContaining("does not exist");
    }

    // REUSE-IgnoreStart: the header below is test data, not this file's licence
    @Test
    void generated_sources_start_with_the_configured_header() throws Exception {
        var config = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        var header = """

                    SPDX-FileCopyrightText: Acme Corp

                    SPDX-License-Identifier: MIT
                """;
        var result = compile(config, PACKAGE, Severity.ERROR, header);
        Assertions.assertThat(result.sources().values()).allSatisfy(source -> Assertions.assertThat(source)
                .startsWith("// SPDX-FileCopyrightText: Acme Corp\n//\n// SPDX-License-Identifier: MIT\n// Generated by"));
        compileAndLoad(result).close(); // still valid Java

        Assertions.assertThat(compile(config, PACKAGE, Severity.ERROR).sources().get("Fees")).startsWith("// Generated by");
    }

    @Test
    void file_headers_cannot_smuggle_unicode_escapes_into_comments() {
        var config = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        // javac would decode a backslash-u escape even inside a comment and could end it early
        Assertions.assertThatThrownBy(() -> compile(config, PACKAGE, Severity.ERROR, "Copyright \\u000a evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<fileHeader> must not contain backslashes");
    }
    // REUSE-IgnoreEnd

    @Test
    void column_and_value_names_become_java_identifiers() {
        Assertions.assertThat(JavaNames.member("customer.age")).isEqualTo("customerAge");
        Assertions.assertThat(JavaNames.member("Customer country")).isEqualTo("customerCountry");
        Assertions.assertThat(JavaNames.member("class")).isEqualTo("class_");
        Assertions.assertThat(JavaNames.member("toString")).isEqualTo("toString_");
        Assertions.assertThat(JavaNames.type("decision_table")).isEqualTo("DecisionTable");
        Assertions.assertThat(JavaNames.constant("small business")).isEqualTo("SMALL_BUSINESS");
        Assertions.assertThat(JavaNames.constant("1st")).isEqualTo("_1ST");
        Assertions.assertThat(JavaNames.constant("")).isEqualTo("EMPTY");
        Assertions.assertThatThrownBy(() -> JavaNames.distinct(List.of("a b", "a-b"), JavaNames::constant, "Values of column 'x'"))
                .hasMessageContaining("'a b' and 'a-b' both become the Java name A_B");
    }

    @Test
    void options_name_the_tool_and_its_settings() {
        var csvWithoutPolicy = new TableDefinition(table("fees.csv"), null, null, null, null,
                List.of(ColumnDefinition.of("zone", ColumnType.STRING)), List.of(ColumnDefinition.of("fee", ColumnType.NUMBER)));
        var gradle = CompilerOptions.of(PACKAGE, "praecepta-gradle-plugin");
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(csvWithoutPolicy, gradle))
                .hasMessageEndingWith(": 'hitPolicy' is required for Excel and CSV tables");
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(TableDefinition.dmn(null), gradle))
                .hasMessage("Every 'table' needs a 'source'");
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(TableDefinition.dmn(null), gradle.withSettingNames(String::toUpperCase)))
                .hasMessage("Every TABLE needs a SOURCE");

        var fees = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(ColumnDefinition.of("zone", ColumnType.STRING)), List.of(ColumnDefinition.of("fee", ColumnType.NUMBER)));
        var result = TableCompiler.compile(fees, gradle.withFileHeader("Acme"));
        Assertions.assertThat(result.sources().get("Fees"))
                .startsWith("// Acme\n// Generated by praecepta-gradle-plugin from fees.csv. Do not edit.\n")
                .contains(" * praecepta-gradle-plugin from {@code fees.csv} and validated at build time.");
    }

    @Test
    void fail_on_follows_the_options() {
        var overlapping = new TableDefinition(table("overlapping.csv"), "Tier", null, HitPolicy.UNIQUE, null,
                List.of(ColumnDefinition.of("country", ColumnType.STRING)), List.of(ColumnDefinition.of("tier", ColumnType.STRING)));
        var options = CompilerOptions.of(PACKAGE, "test");
        Assertions.assertThat(options.failOn()).isEqualTo(Severity.ERROR);
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(overlapping, options)).hasMessageContaining("overlap");
        Assertions.assertThat(TableCompiler.compile(overlapping, options.withFailOn(null)).report().issues()).isNotEmpty();
    }

    /** The build tool logs the report, one line per issue: the engine that validates the table does not log it too (#95). */
    @Test
    void compiling_leaves_logging_the_report_to_the_build_tool() {
        var overlapping = new TableDefinition(table("overlapping.csv"), "Tier", null, HitPolicy.UNIQUE, null,
                List.of(ColumnDefinition.of("country", ColumnType.STRING)), List.of(ColumnDefinition.of("tier", ColumnType.STRING)));
        var logger = Logger.getLogger(PraeceptaEngine.class.getName());
        var records = new ArrayList<LogRecord>();
        var handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        // the build's test-logging.properties keeps the engine's warnings quiet: let them through to the handler
        var level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            var compiled = TableCompiler.compile(overlapping, CompilerOptions.of(PACKAGE, "test").withFailOn(null));
            Assertions.assertThat(compiled.report().issues()).isNotEmpty();
            Assertions.assertThat(records).isEmpty();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test
    void a_compiled_table_writes_its_dmn_and_sources_into_the_package_directories() throws Exception {
        var fees = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(ColumnDefinition.of("zone", ColumnType.STRING)), List.of(ColumnDefinition.of("fee", ColumnType.NUMBER)));
        var result = TableCompiler.compile(fees, CompilerOptions.of(PACKAGE, "test"));
        Assertions.assertThat(result.resourcePath()).isEqualTo("com/acme/rules/Fees.dmn");

        var written = result.write(out.resolve("java"), out.resolve("resources"));
        Assertions.assertThat(written).containsExactly(out.resolve("resources/com/acme/rules/Fees.dmn"),
                out.resolve("java/com/acme/rules/FeesInput.java"), out.resolve("java/com/acme/rules/FeesOutput.java"),
                out.resolve("java/com/acme/rules/Fees.java"));
        Assertions.assertThat(out.resolve("resources/com/acme/rules/Fees.dmn")).content(StandardCharsets.UTF_8).isEqualTo(result.dmn());

        var inDefaultPackage = TableCompiler.compile(fees, CompilerOptions.of("", "test"));
        Assertions.assertThat(inDefaultPackage.resourcePath()).isEqualTo("Fees.dmn");
        inDefaultPackage.write(out.resolve("default"), out.resolve("default"));
        Assertions.assertThat(out.resolve("default/Fees.java")).exists();
        Assertions.assertThat(out.resolve("default/Fees.dmn")).exists();
    }

    /**
     * An output expression has the zone of its operands of the output's type (#85): {@code start + duration("PT8H")}
     * of a date and time with an offset is one too, a time with a zone id plus a duration keeps its zone id, and a
     * local time stays local. The generated components follow, and give what the map-based engine gives.
     */
    @Test
    void output_expressions_give_generated_components_the_zone_of_their_operands() throws Exception {
        var dmn = out.resolve("shift.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="shift" name="shift"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="dateTime"><text>start</text></inputExpression></input>
                    <input><inputExpression typeRef="time"><text>opens</text></inputExpression></input>
                    <input><inputExpression typeRef="time"><text>noon</text></inputExpression></input>
                    <output name="ends" typeRef="dateTime"/>
                    <output name="closes" typeRef="time"/>
                    <output name="lunch" typeRef="time"/>
                    <rule><inputEntry><text>&gt;= date and time("2025-01-01T00:00:00Z")</text></inputEntry>
                      <inputEntry><text>&gt;= @"08:00:00@Europe/Paris"</text></inputEntry>
                      <inputEntry><text>&lt;= time("12:00:00")</text></inputEntry>
                      <outputEntry><text>start + duration("PT8H")</text></outputEntry>
                      <outputEntry><text>opens + @"PT9H"</text></outputEntry>
                      <outputEntry><text>noon + duration("PT1H")</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Shift", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("ShiftOutput")).contains("public record ShiftOutput(java.time.ZonedDateTime ends, "
                + "io.github.brunopacheco1.praecepta.engine.feel.ZonedTime closes, java.time.LocalTime lunch)");
        var decision = Decision.of("shift", DmnReader.read(Files.newInputStream(dmn)));
        var expected = decision.evaluate(Map.of("start", "2025-03-01T08:00:00Z", "opens", "09:00:00@Europe/Paris", "noon", "11:30:00"))
                .getFirst();

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ShiftInput");
            var evaluate = loader.loadClass(PACKAGE + ".Shift").getMethod("evaluate", input);
            var parse = loader.loadClass("io.github.brunopacheco1.praecepta.engine.feel.ZonedTime").getMethod("parse", String.class);
            var start = ZonedDateTime.of(2025, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);
            var output = ((Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0]
                    .newInstance(start, parse.invoke(null, "09:00:00@Europe/Paris"), LocalTime.of(11, 30)))).orElseThrow();
            Assertions.assertThat(output.getClass().getMethod("ends").invoke(output))
                    .isEqualTo(ZonedDateTime.of(2025, 3, 1, 16, 0, 0, 0, ZoneOffset.UTC))
                    .isEqualTo(ZonedDateTime.parse(expected.get("ends")));
            Assertions.assertThat(output.getClass().getMethod("closes").invoke(output)).hasToString("18:00:00@Europe/Paris")
                    .hasToString(expected.get("closes"));
            Assertions.assertThat(output.getClass().getMethod("lunch").invoke(output)).isEqualTo(LocalTime.of(12, 30))
                    .isEqualTo(LocalTime.parse(expected.get("lunch")));
        }
    }

    /** An output whose rules give a value with a zone and one without, through an expression or a literal, is rejected. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "dateTime | start + duration(\"PT8H\")       | date and time(\"2025-01-01T00:00:00\") | date and time values both with and without a time zone",
        "time     | opens + @\"PT9H\"                | time(\"10:00:00\")                     | time values both with a zone id and without one",
        "time     | time(\"09:00:00@Europe/Paris\")  | time(\"09:00:00\")                     | time values both with a zone id and without one",
    })
    void an_output_whose_rules_mix_kinds_of_time_is_rejected(String type, String first, String second, String kinds) throws IOException {
        var dmn = out.resolve("mixed-output.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="mixed" name="mixed"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="dateTime"><text>start</text></inputExpression></input>
                    <input><inputExpression typeRef="time"><text>opens</text></inputExpression></input>
                    <output name="out" typeRef="%s"/>
                    <rule><inputEntry><text>&gt;= date and time("2025-01-01T00:00:00Z")</text></inputEntry>
                      <inputEntry><text>&gt;= @"08:00:00@Europe/Paris"</text></inputEntry><outputEntry><text>%s</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>%s</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(type, first, second));
        var definition = new TableDefinition(dmn, "Mixed", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(definition, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'mixed': output 'out' takes " + kinds + ", but its generated record component holds one kind");
    }

    /**
     * Output entries and defaults in the other literal forms (#81), at-literals and the functions' other forms, give
     * the generated components their kind as the plain forms do: a time with a zone id is a {@code ZonedTime}.
     */
    @Test
    void output_literals_in_every_form_give_the_component_their_kind() throws Exception {
        var dmn = out.resolve("desks.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="desks" name="desks"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="string"><text>desk</text></inputExpression></input>
                    <output name="opens" typeRef="time"><defaultOutputEntry><text>@"12:00:00@Europe/Paris"</text></defaultOutputEntry></output>
                    <output name="since" typeRef="date"/>
                    <rule><inputEntry><text>"Paris"</text></inputEntry><outputEntry><text>@"09:00:00@Europe/Paris"</text></outputEntry>
                      <outputEntry><text>date(2025, 1, 31)</text></outputEntry></rule>
                    <rule><inputEntry><text>"Lyon"</text></inputEntry><outputEntry><text>time("10:30:00@Europe/Paris")</text></outputEntry>
                      <outputEntry><text>@"2024-02-29"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Desks", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("DesksOutput"))
                .contains("public record DesksOutput(io.github.brunopacheco1.praecepta.engine.feel.ZonedTime opens, java.time.LocalDate since)");
        var decision = Decision.of("desks", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".DesksInput");
            var evaluate = loader.loadClass(PACKAGE + ".Desks").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            for (var desk : List.of("Paris", "Lyon", "Nice")) {
                var expected = decision.evaluate(Map.of("desk", desk)).getFirst();
                Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(desk))).as(desk)
                        .get().hasToString("DesksOutput[opens=" + expected.get("opens") + ", since=" + expected.get("since") + "]");
            }
        }
    }

    /**
     * A time input whose cells name a zone id and no zone has no one Java type, and is rejected; one whose cells name a
     * zone id and an offset is a {@code ZonedTime}, which a zone with one fixed offset makes a time at that offset.
     */
    @ParameterizedTest
    @CsvSource({"'time(\"10:00:00\")'", "'@\"10:00:00\"'"})
    void a_time_column_mixing_a_zone_id_with_other_times_is_rejected(String other) throws IOException {
        var dmn = out.resolve("mixed-zone-ids.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="calls" name="calls"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="time"><text>at</text></inputExpression></input>
                    <output name="desk" typeRef="string"/>
                    <rule><inputEntry><text>&lt; @"10:00:00@Europe/Paris"</text></inputEntry><outputEntry><text>"Paris"</text></outputEntry></rule>
                    <rule><inputEntry><text>&lt; %s</text></inputEntry><outputEntry><text>"elsewhere"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""".formatted(other));
        var definition = new TableDefinition(dmn, "Calls", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> compile(definition, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'calls': input 'at' takes time values both with a zone id and without one, "
                        + "but its generated record component holds one kind");
    }

    /**
     * A time in a zone with one fixed offset is the time at that offset (DMN 1.5, Table 72: {@code time("23:59:00z") +
     * duration("PT2M") = time("00:01:00@Etc/UTC")}): a column whose zone ids all have one takes an {@code OffsetTime},
     * and the generated code gives what the map-based engine gives.
     */
    @Test
    void times_in_zones_with_one_fixed_offset_are_offset_times_in_generated_code() throws Exception {
        var dmn = out.resolve("utc.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="utc" name="utc"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="time"><text>at</text></inputExpression></input>
                    <output name="desk" typeRef="string"/>
                    <output name="opens" typeRef="time"/>
                    <rule><inputEntry><text>time("00:01:00@Etc/UTC")</text></inputEntry><outputEntry><text>"utc"</text></outputEntry>
                      <outputEntry><text>at + duration("PT1M")</text></outputEntry></rule>
                    <rule><inputEntry><text>&lt; @"10:00:00@Etc/GMT-2"</text></inputEntry><outputEntry><text>"early"</text></outputEntry>
                      <outputEntry><text>time("09:00:00@Etc/UTC")</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"late"</text></outputEntry>
                      <outputEntry><text>time("12:00:00Z")</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Utc", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("UtcInput")).contains("public record UtcInput(java.time.OffsetTime at)");
        Assertions.assertThat(result.sources().get("UtcOutput")).contains("public record UtcOutput(String desk, java.time.OffsetTime opens)",
                "io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals.parseOffsetTime(value)");
        Assertions.assertThat(result.sources().get("Utc")).contains(".offsetTimeInput(\"at\", UtcInput::at)");
        var decision = Decision.of("utc", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".UtcInput");
            var evaluate = loader.loadClass(PACKAGE + ".Utc").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // 23:59Z + PT2M is 00:01@Etc/UTC; 07:59Z is before 10:00@Etc/GMT-2, which is 08:00Z
            for (var at : List.of(OffsetTime.of(23, 59, 0, 0, ZoneOffset.UTC).plusMinutes(2), OffsetTime.of(2, 1, 0, 0, ZoneOffset.ofHours(2)),
                    OffsetTime.of(7, 59, 0, 0, ZoneOffset.UTC), OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC))) {
                var expected = decision.evaluate(Map.of("at", at.toString())).getFirst();
                var output = ((Optional<?>) evaluate.invoke(null, constructor.newInstance(at))).orElseThrow();
                Assertions.assertThat(output).as("%s", at)
                        .hasToString("UtcOutput[desk=" + expected.get("desk") + ", opens=" + OffsetTime.parse(expected.get("opens")) + "]");
            }
            var utc = ((Optional<?>) evaluate.invoke(null, constructor.newInstance(OffsetTime.of(0, 1, 0, 0, ZoneOffset.UTC)))).orElseThrow();
            Assertions.assertThat(utc).hasToString("UtcOutput[desk=utc, opens=00:02Z]");
            Assertions.assertThat(decision.evaluate(Map.of("at", "00:01:00@Etc/UTC"))).containsExactly(Map.of("desk", "utc", "opens", "00:02:00Z"));
        }
    }

    /**
     * A time column whose cells mix zone ids with and without a fixed offset is a {@code ZonedTime}, as before: a time
     * given in {@code Etc/UTC} matches as the time at offset zero (DMN 1.5, Table 72), and the generated code reads back
     * the times the engine writes at an offset.
     */
    @Test
    void a_time_column_mixing_fixed_and_changing_zone_ids_is_a_zoned_time_matched_at_the_fixed_offset() throws Exception {
        var dmn = out.resolve("mixed-zones.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="zones" name="zones"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="time"><text>at</text></inputExpression></input>
                    <output name="desk" typeRef="string"/>
                    <output name="opens" typeRef="time"/>
                    <rule><inputEntry><text>&lt; @"10:00:00@Europe/Paris"</text></inputEntry><outputEntry><text>"Paris"</text></outputEntry>
                      <outputEntry><text>@"09:00:00@Europe/Paris"</text></outputEntry></rule>
                    <rule><inputEntry><text>time("00:01:00@Etc/UTC")</text></inputEntry><outputEntry><text>"utc"</text></outputEntry>
                      <outputEntry><text>at</text></outputEntry></rule>
                    <rule><inputEntry><text>[time("07:00:00@Etc/GMT-2")..time("08:00:00@Etc/GMT-2")]</text></inputEntry>
                      <outputEntry><text>"plus two"</text></outputEntry><outputEntry><text>at + duration("PT1M")</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"closed"</text></outputEntry>
                      <outputEntry><text>null</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Zones", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("ZonesInput"))
                .contains("public record ZonesInput(io.github.brunopacheco1.praecepta.engine.feel.ZonedTime at)");
        Assertions.assertThat(result.sources().get("ZonesOutput"))
                .contains("public record ZonesOutput(String desk, io.github.brunopacheco1.praecepta.engine.feel.ZonedTime opens)",
                        "io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals.parseZonedTime(value)");
        Assertions.assertThat(result.sources().get("Zones")).contains(".zonedTimeInput(\"at\", ZonesInput::at)");
        var decision = Decision.of("zones", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".ZonesInput");
            var evaluate = loader.loadClass(PACKAGE + ".Zones").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // the generated code's ZonedTime is the engine's, as the loader holds it
            var parse = loader.loadClass("io.github.brunopacheco1.praecepta.engine.feel.ZonedTime").getMethod("parse", String.class);
            var expectedOutputs = Map.of(
                    "00:01:00@Etc/UTC", "ZonesOutput[desk=utc, opens=00:01:00@UTC]",
                    "07:30:00@Etc/GMT-2", "ZonesOutput[desk=plus two, opens=07:31:00@UTC+02:00]",
                    "05:30:00@UTC", "ZonesOutput[desk=plus two, opens=05:31:00@UTC]",
                    "09:00:00@Europe/Paris", "ZonesOutput[desk=Paris, opens=09:00:00@Europe/Paris]",
                    "11:00:00@Europe/Paris", "ZonesOutput[desk=closed, opens=null]",
                    "00:01:00@Asia/Dhaka", "ZonesOutput[desk=closed, opens=null]");
            for (var at : expectedOutputs.entrySet()) {
                var output = ((Optional<?>) evaluate.invoke(null, constructor.newInstance(parse.invoke(null, at.getKey())))).orElseThrow();
                Assertions.assertThat(output).as(at.getKey()).hasToString(at.getValue());
                // the map-based engine writes the same times, at their offset where the zone has a fixed one
                var expected = decision.evaluate(Map.of("at", at.getKey())).getFirst();
                var opens = expected.get("opens");
                Assertions.assertThat(output).as(at.getKey()).hasToString("ZonesOutput[desk=" + expected.get("desk") + ", opens="
                        + (opens == null ? null : FeelTemporals.parseZonedTime(opens)) + "]");
            }
        }
    }

    /** {@code != home} names an input (#81): the generated input record gets it, and evaluates as the map-based engine. */
    @Test
    void a_not_equal_test_naming_an_input_becomes_an_input_component() throws Exception {
        var dmn = out.resolve("abroad.dmn");
        Files.writeString(dmn, """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="abroad" name="abroad"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="string"><text>country</text></inputExpression></input>
                    <output name="fee" typeRef="number"/>
                    <rule><inputEntry><text>!= home</text></inputEntry><outputEntry><text>5</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>0</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var result = compile(new TableDefinition(dmn, "Abroad", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        Assertions.assertThat(result.sources().get("AbroadInput")).contains("public record AbroadInput(String country, String home)");
        var decision = Decision.of("abroad", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".AbroadInput");
            var evaluate = loader.loadClass(PACKAGE + ".Abroad").getMethod("evaluate", input);
            var constructor = input.getDeclaredConstructors()[0];
            // != is not(=): a missing value differs from any value there, and two missing values are equal
            var cases = new String[][] {{"PT", "PT", "0"}, {"ES", "PT", "5"}, {null, "PT", "5"}, {"PT", null, "5"}, {null, null, "0"}};
            for (var row : cases) {
                Assertions.assertThat((Optional<?>) evaluate.invoke(null, constructor.newInstance(row[0], row[1])))
                        .as("%s", Arrays.toString(row)).get().hasToString("AbroadOutput[fee=" + row[2] + "]");
                var values = new HashMap<String, String>();
                values.put("country", row[0]);
                values.put("home", row[1]);
                Assertions.assertThat(decision.evaluate(values)).as("%s", Arrays.toString(row)).containsExactly(Map.of("fee", row[2]));
            }
        }
    }

    /**
     * A {@code null} default under an aggregation (#92) is the result when no rule matches, COUNT's too, in the
     * generated class and in the map-based engine alike: DMN 1.5, 10.3.2.10, step 2, comes before any aggregation.
     */
    @ParameterizedTest
    @CsvSource({"SUM", "MIN", "MAX", "COUNT"})
    void a_null_default_is_an_aggregations_result_when_no_rule_matches(String aggregation) throws Exception {
        var dmn = totals(aggregation);
        Files.writeString(dmn, Files.readString(dmn).replace("<text>9.90</text>", "<text>null</text>"));
        var result = compile(new TableDefinition(dmn, "Total", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        var decision = Decision.of("total", DmnReader.read(Files.newInputStream(dmn)));

        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".TotalInput");
            var evaluate = loader.loadClass(PACKAGE + ".Total").getMethod("evaluate", input);
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0].newInstance("mainland")))
                    .get().hasToString("TotalOutput[total=null]");
        }
        var nothing = new HashMap<String, String>();
        nothing.put("total", null);
        Assertions.assertThat(decision.evaluate(Map.of("zone", "mainland"))).containsExactly(nothing);
    }

    /** A table of a date, time or date and time input and output, holding literals in its value list, cell, output entry and default. */
    private static final String LEXICAL = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="slot" name="slot"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="%1$s"><text>at</text></inputExpression><inputValues><text>%2$s</text></inputValues></input>
                <output name="until" typeRef="%1$s"><defaultOutputEntry><text>%5$s</text></defaultOutputEntry></output>
                <rule><inputEntry><text>%3$s</text></inputEntry><outputEntry><text>%4$s</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /**
     * Dates, times and date-times take XML Schema's lexical forms (DMN 1.5, 10.3.4.1): an offset beyond -14:00..+14:00
     * (#123), or a year with a {@code +} (#124), fails the build wherever the table holds it, as the engine rejects it,
     * and the generated code reads back the outputs the engine writes at the edges, a year of more than four digits
     * without Java's {@code +} included.
     */
    @ParameterizedTest(name = "{0} {2}")
    @CsvSource(delimiter = ';', value = {
            "time;     time(\"08:00:00+14:00\");                     time(\"08:00:00+19:00\")",
            "time;     time(\"08:00:00-14:00\");                     @\"08:00:00-14:01\"",
            "dateTime; date and time(\"2025-01-31T08:00:00+14:00\"); date and time(\"2025-01-31T08:00:00+15:00\")",
            "date;     date(\"99999-12-31\");                        date(\"+2012-12-02\")",
            "dateTime; date and time(\"99999-12-31T11:22:33\");      date and time(\"+99999-12-01T11:22:33\")",
            "dateTime; date and time(\"-99999-12-31T11:22:33Z\");    @\"+2012-12-24T23:59:00Z\"",
    })
    void a_literal_beyond_xml_schemas_lexical_forms_fails_the_build(String type, String valid, String invalid) throws Exception {
        var dmn = out.resolve("slot.dmn");
        Files.writeString(dmn, LEXICAL.formatted(type, valid, valid, valid, valid));
        var result = compile(new TableDefinition(dmn, "Slot", null, null, null, List.of(), List.of()), PACKAGE, Severity.ERROR);
        var decision = Decision.of("slot", DmnReader.read(Files.newInputStream(dmn)));
        try (var loader = compileAndLoad(result)) {
            var input = loader.loadClass(PACKAGE + ".SlotInput");
            var evaluate = loader.loadClass(PACKAGE + ".Slot").getMethod("evaluate", input);
            var text = valid.substring(valid.indexOf('"') + 1, valid.lastIndexOf('"'));
            var columnType = switch (type) {
                case "date" -> ColumnType.DATE;
                case "time" -> ColumnType.TIME;
                default -> ColumnType.DATE_TIME;
            };
            var at = FeelLiterals.value(text, columnType);
            var expected = decision.evaluate(Map.of("at", text)).getFirst().get("until");
            Assertions.assertThat(expected).isEqualTo(text);
            Assertions.assertThat((Optional<?>) evaluate.invoke(null, input.getDeclaredConstructors()[0].newInstance(at)))
                    .get().hasToString("SlotOutput[until=" + at + "]");
        }

        for (int slot = 0; slot < 4; slot++) {
            var literals = new String[] {valid, valid, valid, valid};
            literals[slot] = invalid;
            Files.writeString(dmn, LEXICAL.formatted(type, literals[0], literals[1], literals[2], literals[3]));
            var definition = new TableDefinition(dmn, "Slot", null, null, null, List.of(), List.of());
            Assertions.assertThatThrownBy(() -> compile(definition, PACKAGE, Severity.ERROR)).as("slot %d", slot)
                    .hasMessageContaining(invalid.substring(invalid.indexOf('"') + 1, invalid.lastIndexOf('"')));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Compiles as the Maven plugin does, so messages name settings as pom elements. */
    static CompiledTable compile(TableDefinition definition, String packageName, Severity failOn) {
        return compile(definition, packageName, failOn, null);
    }

    static CompiledTable compile(TableDefinition definition, String packageName, Severity failOn, String fileHeader) {
        return TableCompiler.compile(definition,
                new CompilerOptions(packageName, failOn, fileHeader, "praecepta-maven-plugin", CompilerOptions.XML_ELEMENTS));
    }

    private static Path table(String name) {
        try {
            return Path.of(TableCompilerTest.class.getResource("/tables/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the generated sources and DMN, compiles them against the engine jar only, and loads them. */
    private URLClassLoader compileAndLoad(CompiledTable result) throws Exception {
        var packageDir = out.resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(packageDir);
        var files = new ArrayList<String>();
        for (var source : result.sources().entrySet()) {
            var file = packageDir.resolve(source.getKey() + ".java");
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        Files.writeString(packageDir.resolve(result.className() + ".dmn"), result.dmn(), StandardCharsets.UTF_8);

        var engineJar = Path.of(PraeceptaEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var arguments = new ArrayList<>(List.of("-classpath", engineJar.toString(), "-d", out.toString(), "-Xlint:all", "-Werror"));
        arguments.addAll(files);
        var errors = new java.io.ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, arguments.toArray(String[]::new));
        Assertions.assertThat(status).as("javac:\n%s\n%s", errors, result.sources()).isZero();

        // the platform class loader as parent: only the JDK and the engine are visible
        return new URLClassLoader(new URL[] {out.toUri().toURL(), engineJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(ClassLoader loader, String type, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) loader.loadClass(type), name);
    }
}
