/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.tck.TckExport.Row;
import io.github.brunopacheco1.praecepta.tck.TckExport.Verdict;
import io.github.brunopacheco1.praecepta.tck.TckResults.Result;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Outcome;
import io.github.brunopacheco1.praecepta.tck.TckRunner.Status;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/** How the run's results map to the DMN TCK's own format: a line per test case, and the product's properties. */
class TckExportTest {

    private static final String LENDING = "compliance-level-3/0004-lending";
    private static final Outcome NOT_SUPPORTED = new Outcome(Status.UNSUPPORTED, "Decision 'Max' uses an external function");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 9);

    @TempDir
    Path directory;

    @Test
    void a_test_case_succeeds_when_every_one_of_its_result_nodes_passes() {
        var rows = TckExport.rows(List.of(
                result(LENDING, "0004-lending-test-01.xml", "001", "Strategy", Outcome.PASSED),
                result(LENDING, "0004-lending-test-01.xml", "001", "Routing", Outcome.PASSED)));

        assertThat(rows).containsExactly(new Row(LENDING, "0004-lending-test-01", "001", Verdict.SUCCESS, ""));
    }

    @Test
    void a_test_case_is_an_error_when_a_node_fails_and_its_message_names_each_failing_node() {
        var rows = TckExport.rows(List.of(
                result(LENDING, "0004-lending-test-01.xml", "001", "Strategy", Outcome.PASSED),
                result(LENDING, "0004-lending-test-01.xml", "001", "Routing", Outcome.failed("expected \"ACCEPT\", got \"DECLINE\"")),
                result(LENDING, "0004-lending-test-01.xml", "001", "Installment", Outcome.failed("expected 10, got 11")),
                result(LENDING, "0004-lending-test-01.xml", "001", "Max", NOT_SUPPORTED)));

        assertThat(rows).containsExactly(new Row(LENDING, "0004-lending-test-01", "001", Verdict.ERROR,
                "FAILURE: 'Routing' expected \"ACCEPT\", got \"DECLINE\"; 'Installment' expected 10, got 11"));
        assertThat(rows.getFirst().line()).isEqualTo("\"compliance-level-3/0004-lending\",\"0004-lending-test-01\",\"001\",\"ERROR\","
                + "\"FAILURE: 'Routing' expected 'ACCEPT', got 'DECLINE'; 'Installment' expected 10, got 11\"\n");
    }

    @Test
    void a_test_case_is_ignored_when_no_node_fails_and_one_is_not_supported_and_its_message_is_the_reason() {
        var rows = TckExport.rows(List.of(
                result(LENDING, "0004-lending-test-01.xml", "001", "Strategy", Outcome.PASSED),
                result(LENDING, "0004-lending-test-01.xml", "001", "Max", NOT_SUPPORTED),
                result(LENDING, "0004-lending-test-01.xml", "001", "Min", NOT_SUPPORTED)));

        assertThat(rows).containsExactly(
                new Row(LENDING, "0004-lending-test-01", "001", Verdict.IGNORED, "Decision 'Max' uses an external function"));
    }

    @Test
    void there_is_a_line_for_each_test_case_of_each_test_file_in_the_order_of_the_run_and_no_header() {
        var csv = TckExport.csv(TckExport.rows(List.of(
                result("compliance-level-2/0001-input-data-string", "0001-input-data-string-test-01.xml", "001", "Greeting", Outcome.PASSED),
                result(LENDING, "0004-lending-test-01.xml", "001", "Strategy", Outcome.PASSED),
                result(LENDING, "0004-lending-test-01.xml", "002", "Strategy", Outcome.failed("expected 1, got 2")),
                result(LENDING, "0004-lending-test-02.xml", "001", "Strategy", NOT_SUPPORTED))));

        assertThat(csv).isEqualTo("""
                "compliance-level-2/0001-input-data-string","0001-input-data-string-test-01","001","SUCCESS",""
                "compliance-level-3/0004-lending","0004-lending-test-01","001","SUCCESS",""
                "compliance-level-3/0004-lending","0004-lending-test-01","002","ERROR","FAILURE: 'Strategy' expected 1, got 2"
                "compliance-level-3/0004-lending","0004-lending-test-02","001","IGNORED","Decision 'Max' uses an external function"
                """);
    }

    @Test
    void a_field_holds_no_double_quote_and_no_line_break_as_the_kit_escapes_none() {
        assertThat(TckExport.field("expected \"a\",\n  got \"b\"\r\nand so on")).isEqualTo("expected 'a', got 'b' and so on");
        assertThat(new Row("level/dir", "file", "a \"quoted\" id", Verdict.SUCCESS, "").line())
                .isEqualTo("\"level/dir\",\"file\",\"a 'quoted' id\",\"SUCCESS\",\"\"\n");
    }

    @Test
    void the_properties_name_the_product_with_the_keys_the_kit_reads_sorted_and_nothing_else() throws IOException {
        var text = TckExport.properties("1.2.3", DAY);

        assertThat(text).isEqualTo("""
                instructions.url=https://github.com/brunopacheco1/praecepta/blob/main/praecepta-tck/README.md
                last.update=2026-03-09
                product.comment=A DMN 1.5 engine for Java. It rejects external functions (Java, PMML) by design.
                product.name=Praecepta
                product.url=https://bruno.pacheco.lu/praecepta/
                product.version=1.2.3
                vendor.name=Bruno Pacheco
                vendor.url=https://github.com/brunopacheco1
                """);
        assertThat(load(text)).containsOnlyKeys("vendor.name", "vendor.url", "product.name", "product.url", "product.version",
                "product.comment", "last.update", "instructions.url")
                .containsEntry("product.version", "1.2.3")
                .containsEntry("vendor.url", "https://github.com/brunopacheco1");
    }

    @Test
    void a_property_value_is_escaped_as_a_properties_file_needs() throws IOException {
        var version = " 1.0\\rc\t1\né#!=:";

        assertThat(TckExport.escaped(version)).isEqualTo("\\ 1.0\\\\rc\\t1\\n\\u00e9#!=:");
        assertThat(load(TckExport.properties(version, DAY))).containsEntry("product.version", version);
    }

    @Test
    void both_files_are_written_to_a_directory_that_is_created() throws IOException {
        var target = directory.resolve("results/Praecepta");

        TckExport.write(target, List.of(result(LENDING, "0004-lending-test-01.xml", "001", "Strategy", Outcome.PASSED)), "1.2.3", DAY);

        assertThat(Files.readString(target.resolve("tck_results.csv")))
                .isEqualTo("\"compliance-level-3/0004-lending\",\"0004-lending-test-01\",\"001\",\"SUCCESS\",\"\"\n");
        assertThat(Files.readString(target.resolve("tck_results.properties"))).isEqualTo(TckExport.properties("1.2.3", DAY));
    }

    private static Properties load(String text) throws IOException {
        var properties = new Properties();
        properties.load(new StringReader(text));
        return properties;
    }

    private static Result result(String testCase, String testFile, String id, String node, Outcome outcome) {
        var check = new Check(testCase, testFile, id, Invocable.DECISION, "", node, Path.of("model.dmn"), Map.of(), TckValue.NIL, false);
        return new Result(check, outcome);
    }
}
