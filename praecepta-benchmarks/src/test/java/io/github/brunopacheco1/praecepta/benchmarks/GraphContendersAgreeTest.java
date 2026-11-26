/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the graph benchmark: every engine must give the model's answers, computed in plain Java. */
class GraphContendersAgreeTest {

    @ParameterizedTest
    @ValueSource(strings = {"praecepta", "camunda", "drools"})
    void engines_give_the_models_answers(String engine) {
        var workload = GraphContender.workload(2000, 11);

        assertThatCode(() -> GraphContender.assertAgreement(workload, GraphContender.create(engine))).doesNotThrowAnyException();
    }

    @Test
    void the_expected_answers_follow_the_model() {
        assertThat(GraphContender.expected(input(30, 6_000))).isEqualByComparingTo("0");
        assertThat(GraphContender.expected(input(20, 24_000))).isEqualByComparingTo("500");
        assertThat(GraphContender.expected(input(20, 120_000))).isEqualByComparingTo("5000");
        assertThat(GraphContender.expected(input(30, 60_000))).isEqualByComparingTo("5000");
        assertThat(GraphContender.expected(input(70, 120_000))).isEqualByComparingTo("15000");
        assertThat(GraphContender.expected(input(50, 60_000))).isEqualByComparingTo("10000");
    }

    @Test
    void the_workload_reaches_every_rule() {
        var limits = GraphContender.workload(2000, 11).stream().map(GraphContender::expected).distinct().count();

        assertThat(limits).isGreaterThan(1000);
    }

    private static Map<String, Object> input(int age, int income) {
        return Map.of(GraphContender.AGE, BigDecimal.valueOf(age), GraphContender.INCOME, BigDecimal.valueOf(income));
    }
}
