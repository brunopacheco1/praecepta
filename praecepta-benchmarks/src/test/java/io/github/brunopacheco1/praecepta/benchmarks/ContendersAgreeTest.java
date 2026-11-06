package io.github.brunopacheco1.praecepta.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/** Guards the benchmark itself: every engine must compute the same answers as Praecepta. */
class ContendersAgreeTest {

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
            "camunda, real",
            "drools, real",
            "camunda, synthetic-100",
            "drools, synthetic-100",
            "camunda, mixed-100",
            "drools, mixed-100",
    })
    void engines_agree_with_praecepta(String engine, String tableName) {
        var table = Table.named(tableName);
        var workload = Workload.generate(table, 2_000, 11);
        var praecepta = Contender.create("praecepta", table);
        var other = Contender.create(engine, table);

        Assertions.assertThatCode(() -> CrossCheck.assertAgreement(table, workload, other, praecepta))
                .doesNotThrowAnyException();
        Assertions.assertThat(workload).allSatisfy(input -> Assertions.assertThat(praecepta.evaluate(input)).isNotEmpty());
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
