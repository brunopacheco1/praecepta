package io.github.brunopacheco1.praecepta.benchmarks;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Guards the benchmark itself: every engine must compute the same answers as Praecepta. */
class ContendersAgreeTest {

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
            "camunda, real",
            "camunda-legacy-feel, real",
            "drools, real",
            "camunda, synthetic-100",
            "camunda-legacy-feel, synthetic-100",
            "drools, synthetic-100",
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
}
