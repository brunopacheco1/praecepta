/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.File;
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
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.tools.ToolProvider;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

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
        var config = new TableConfig(table("discount.csv"), "Discount", null, HitPolicy.FIRST, null,
                List.of(new ColumnConfig("country", ColumnType.STRING, "\"PT\",\"ES\",\"FR\"", List.of()),
                        new ColumnConfig("age", ColumnType.NUMBER, "[0..120]", List.of()),
                        new ColumnConfig("since", ColumnType.DATE, null, List.of())),
                List.of(new ColumnConfig("discount", ColumnType.NUMBER, null, List.of()),
                        new ColumnConfig("tier", ColumnType.STRING, null, List.of())));
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);

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
        var config = new TableConfig(table("camunda-modeler-discount.dmn"), null, null, null, null, List.of(), List.of());
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);

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
        var config = new TableConfig(table("limits.dmn"), "Limits", null, null, null, List.of(), List.of());
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);
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

    @Test
    void duration_columns_become_durations_and_periods() throws Exception {
        var config = new TableConfig(table("waits.csv"), "Wait", null, HitPolicy.FIRST, null,
                List.of(new ColumnConfig("delay", ColumnType.DAY_TIME_DURATION, null, List.of()),
                        new ColumnConfig("tenure", ColumnType.YEAR_MONTH_DURATION, null, List.of())),
                List.of(new ColumnConfig("extension", ColumnType.DAY_TIME_DURATION, null, List.of()),
                        new ColumnConfig("plan", ColumnType.STRING, null, List.of())));
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);

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
        var config = new TableConfig(table("shifts.csv"), "Shift", null, HitPolicy.FIRST, null,
                List.of(new ColumnConfig("overtime", ColumnType.BOOLEAN, null, List.of()),
                        new ColumnConfig("start", ColumnType.TIME, null, List.of()),
                        new ColumnConfig("at", ColumnType.DATE_TIME, null, List.of())),
                List.of(new ColumnConfig("paid", ColumnType.BOOLEAN, null, List.of()),
                        new ColumnConfig("until", ColumnType.TIME, null, List.of()),
                        new ColumnConfig("reviewed", ColumnType.DATE_TIME, null, List.of()),
                        new ColumnConfig("rate", ColumnType.STRING, null, List.of())));
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);

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
    void collect_sum_tables_return_the_aggregate() throws Exception {
        var config = new TableConfig(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);
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
        var config = new TableConfig(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.COUNT,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR);

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
        var config = new TableConfig(table("overlapping.csv"), "Tier", null, HitPolicy.UNIQUE, null,
                List.of(new ColumnConfig("country", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("tier", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(config, PACKAGE, Severity.ERROR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table 'overlapping'")
                .hasMessageContaining("overlap");
        // with failOn NONE the report is returned instead
        Assertions.assertThat(TableCompiler.compile(config, PACKAGE, null).report().issues()).isNotEmpty();
    }

    @Test
    void configuration_mistakes_are_explained() {
        var dmnWithPolicy = new TableConfig(table("camunda-modeler-discount.dmn"), null, null, HitPolicy.FIRST, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(dmnWithPolicy, PACKAGE, Severity.ERROR))
                .hasMessageContaining("a DMN model declares its own hit policy and columns");

        var csvWithoutPolicy = new TableConfig(table("fees.csv"), null, null, null, null,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(csvWithoutPolicy, PACKAGE, Severity.ERROR))
                .hasMessageContaining("<hitPolicy> is required");

        var sumOfText = new TableConfig(table("fees.csv"), null, null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(sumOfText, PACKAGE, Severity.ERROR))
                .hasMessageContaining("SUM aggregation needs exactly one NUMBER output");

        var missing = new TableConfig(new File("nope.xlsx"), null, null, HitPolicy.FIRST, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(missing, PACKAGE, Severity.ERROR))
                .hasMessageContaining("does not exist");
    }

    // REUSE-IgnoreStart: the header below is test data, not this file's licence
    @Test
    void generated_sources_start_with_the_configured_header() throws Exception {
        var config = new TableConfig(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        var header = """

                    SPDX-FileCopyrightText: Acme Corp

                    SPDX-License-Identifier: MIT
                """;
        var result = TableCompiler.compile(config, PACKAGE, Severity.ERROR, header);
        Assertions.assertThat(result.sources().values()).allSatisfy(source -> Assertions.assertThat(source)
                .startsWith("// SPDX-FileCopyrightText: Acme Corp\n//\n// SPDX-License-Identifier: MIT\n// Generated by"));
        compileAndLoad(result).close(); // still valid Java

        Assertions.assertThat(TableCompiler.compile(config, PACKAGE, Severity.ERROR).sources().get("Fees")).startsWith("// Generated by");
    }

    @Test
    void file_headers_cannot_smuggle_unicode_escapes_into_comments() {
        var config = new TableConfig(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        // javac would decode a backslash-u escape even inside a comment and could end it early
        Assertions.assertThatThrownBy(() -> TableCompiler.compile(config, PACKAGE, Severity.ERROR, "Copyright \\u000a evil"))
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

    // ---- helpers -------------------------------------------------------------------------------

    private static File table(String name) {
        try {
            return Path.of(TableCompilerTest.class.getResource("/tables/" + name).toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the generated sources and DMN, compiles them against the engine jar only, and loads them. */
    private URLClassLoader compileAndLoad(TableCompiler.Result result) throws Exception {
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
