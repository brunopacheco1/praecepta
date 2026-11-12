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
                        "io.github.brunopacheco1.praecepta.engine.feel.ZonedTime.parse(value)");
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
