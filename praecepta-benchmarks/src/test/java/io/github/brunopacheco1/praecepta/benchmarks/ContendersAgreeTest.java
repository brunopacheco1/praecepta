/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/** Guards the benchmark itself: every engine must compute the same answers as Praecepta. */
class ContendersAgreeTest {

    @ParameterizedTest(name = "{0} on {1}, {2}")
    @CsvSource({
            "praecepta, real, match",
            "camunda, real, match",
            "drools, real, match",
            "praecepta, synthetic-100, match",
            "camunda, synthetic-100, match",
            "drools, synthetic-100, match",
            "praecepta, mixed-100, match",
            "camunda, mixed-100, match",
            "drools, mixed-100, match",
            "camunda, mixed-100, nomatch",
            "drools, mixed-100, nomatch",
            "camunda, mixed-100x18, match",
            "drools, mixed-100x18, match",
            "praecepta, mixed-100-first, match",
            "camunda, mixed-100-first, match",
            "drools, mixed-100-first, match",
            "praecepta, mixed-100-priority, match",
            "camunda, mixed-100-priority, match",
            "drools, mixed-100-priority, match",
            "camunda, mixed-100-ruleorder, match",
            "drools, mixed-100-ruleorder, match",
            "praecepta, unique-100, match",
            "camunda, unique-100, match",
            "drools, unique-100, match",
            "camunda, unique-100, nomatch",
            "drools, unique-100, nomatch",
    })
    void engines_agree_with_praecepta(String engine, String tableName, String shape) {
        var table = Table.named(tableName);
        var workload = Workload.generate(table, 2_000, 11, shape);
        var reference = Contender.reference(table);
        var other = Contender.create(engine, table);

        Assertions.assertThatCode(() -> CrossCheck.assertAgreement(table, workload, other, reference))
                .doesNotThrowAnyException();
        if (shape.equals(Workload.MATCH)) {
            Assertions.assertThat(workload).allSatisfy(input -> Assertions.assertThat(reference.evaluate(input)).isNotEmpty());
        } else {
            Assertions.assertThat(workload).allSatisfy(input -> Assertions.assertThat(reference.evaluate(input)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"praecepta", "camunda", "drools"})
    void engines_agree_when_shared_by_threads(String engine) throws Exception {
        var table = Table.named("mixed-100");
        var workload = Workload.generate(table, 2_000, 11);
        var reference = Contender.reference(table);
        var expected = workload.stream().map(input -> reference.evaluate(input).stream().sorted().toList()).toList();
        var shared = Contender.create(engine, table);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int t = 0; t < 8; t++) {
                int offset = t * 251;
                tasks.add(() -> {
                    for (int i = 0; i < workload.size(); i++) {
                        int at = (offset + i) % workload.size();
                        if (!shared.evaluate(workload.get(at)).stream().sorted().toList().equals(expected.get(at))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (var result : pool.invokeAll(tasks)) {
                Assertions.assertThat(result.get()).isTrue();
            }
        }
    }

    @Test
    void no_input_misses_a_table_with_a_default_rule() {
        // the real table ends with a catch-all rule, as hand-written FIRST tables often do
        var table = Table.real();
        Assertions.assertThatThrownBy(() -> Workload.generate(table, 10, 1, Workload.NO_MATCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches any input");
    }

    @Test
    void hit_policy_suffix_keeps_the_rules() {
        var collect = Table.named("mixed-200");
        Assertions.assertThat(Table.named("mixed-200-first").rules()).isEqualTo(collect.rules());
        Assertions.assertThat(Table.named("mixed-200-ruleorder").rules()).isEqualTo(collect.rules());
        Assertions.assertThat(Table.named("mixed-200-priority").rules())
                .extracting(rule -> rule.input().values())
                .isEqualTo(collect.rules().stream().map(rule -> rule.input().values()).toList());
        Assertions.assertThat(Table.named("mixed-200x18").inputs()).hasSize(18);
        Assertions.assertThat(Table.named("synthetic-200x5").inputs()).hasSize(5);
    }

    @Test
    void unique_tables_match_one_rule_per_input() {
        var table = Table.named("unique-1000");
        var collect = Contender.reference(Table.named("unique-1000-collect"));
        Assertions.assertThat(Workload.generate(table, 2_000, 5))
                .allSatisfy(input -> Assertions.assertThat(collect.evaluate(input)).hasSize(1));
    }

    @Test
    void first_decision_reads_its_fixtures(@TempDir Path directory) throws Exception {
        FirstDecisionFixtures.main(new String[] {directory.toString(), "mixed-100", "real"});
        System.setProperty("praecepta.fixtures", directory.toString());
        try {
            for (var name : List.of("mixed-100", "real")) {
                var benchmark = new FirstDecisionBenchmark();
                benchmark.engine = "praecepta";
                benchmark.table = name;
                benchmark.setUp();
                var table = Table.named(name);
                Assertions.assertThat(benchmark.firstDecision())
                        .isEqualTo(Contender.reference(table).evaluate(Workload.generate(table, 1, 7).getFirst()))
                        .isNotEmpty();
            }
        } finally {
            System.clearProperty("praecepta.fixtures");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"strings", "enums"})
    void generated_classes_agree_with_map_inputs(String variant) {
        var workload = Workload.generate(Table.real(), 2_000, 11);
        var generated = GeneratedInputs.variant(variant, workload);
        var map = GeneratedInputs.variant("map", workload);
        for (int i = 0; i < workload.size(); i++) {
            Assertions.assertThat(generated.ruleIdsOf(i))
                    .as("input %s", workload.get(i))
                    .isEqualTo(map.ruleIdsOf(i))
                    .isNotEmpty();
        }
    }

    @Test
    void mixed_tables_use_every_column_type_and_cell_form() {
        var table = Table.named("mixed-100");
        Assertions.assertThat(table.types()).containsOnly(ColumnType.STRING, ColumnType.NUMBER, ColumnType.DATE);
        var cells = table.rules().stream().flatMap(rule -> rule.input().values().values().stream()).toList();
        Assertions.assertThat(cells).anyMatch(cell -> cell.startsWith("not("))
                .anyMatch(cell -> cell.startsWith("<"))
                .anyMatch(cell -> cell.startsWith(">="))
                .anyMatch(cell -> cell.startsWith("[") && cell.endsWith(")"))
                .anyMatch(cell -> cell.startsWith("(") && cell.endsWith("]"))
                .anyMatch(cell -> cell.contains("date(\""));
        var workload = Workload.generate(table, 1_000, 3);
        Assertions.assertThat(workload).anySatisfy(input -> Assertions.assertThat(input.get("n0")).isInstanceOf(BigDecimal.class))
                .anySatisfy(input -> Assertions.assertThat(input.get("d0")).isInstanceOf(LocalDate.class))
                .anySatisfy(input -> Assertions.assertThat(input.get("d2")).isNull());
    }
}
