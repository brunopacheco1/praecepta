/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.TableSource;

/**
 * The kit's aggregation models, loaded as the Spring Boot, Micronaut and Quarkus integrations load DMN files:
 * conformant single outputs leave their type to the decision (DMN 1.5, Table 34), which the integrations' map
 * outputs aggregate by, and the results are the kit's expected ones.
 */
class IntegrationLoadsTckModelsTest {

    @Test
    void the_collect_aggregation_models_load_and_give_the_kits_results() {
        var min = load("compliance-level-2/0114-min-collect-hitpolicy/0114-min-collect-hitpolicy.dmn");
        Assertions.assertThat(min.evaluate("CarInsurance", Map.of("NumOfYears", "5"))).containsExactly(Map.of("CarInsurance", "64.32"));
        Assertions.assertThat(min.evaluate("CarInsurance", Map.of("NumOfYears", "3"))).containsExactly(Map.of("CarInsurance", "98.83"));

        var sum = load("compliance-level-2/0115-sum-collect-hitpolicy/0115-sum-collect-hitpolicy.dmn");
        Assertions.assertThat(sum.evaluate("Salary", Map.of("NumOfYears", "6"))).containsExactly(Map.of("Salary", "1100"));
        Assertions.assertThat(sum.evaluate("Salary", Map.of("NumOfYears", "2"))).containsExactly(Map.of("Salary", "100"));

        var count = load("compliance-level-2/0116-count-collect-hitpolicy/0116-count-collect-hitpolicy.dmn");
        Assertions.assertThat(count.evaluate("Salary", Map.of("NumOfYears", "6"))).containsExactly(Map.of("Salary", "4"));
        Assertions.assertThat(count.evaluate("Salary", Map.of("NumOfYears", "2"))).containsExactly(Map.of("Salary", "1"));
    }

    /** 0020-vacation-days: its three decision tables MAX their outputs, which default to 0 (#79). */
    @Test
    void the_vacation_days_tables_load_and_give_their_maximum_or_default() {
        var days = load("compliance-level-3/0020-vacation-days/0020-vacation-days.dmn");
        Assertions.assertThat(days.names()).contains("Extra days case 1", "Extra days case 2", "Extra days case 3");
        Assertions.assertThat(days.evaluate("Extra days case 1", Map.of("Age", "16", "Years of Service", "1")))
                .containsExactly(Map.of("Extra days", "5"));
        Assertions.assertThat(days.evaluate("Extra days case 1", Map.of("Age", "30", "Years of Service", "1")))
                .containsExactly(Map.of("Extra days", "0"));
    }

    private static Decisions load(String model) {
        var path = TckSuite.root().resolve(Path.of(model));
        return Decisions.load(List.of(TableSource.of(path)), Severity.ERROR);
    }
}
