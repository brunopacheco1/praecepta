/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;

/**
 * A DMN file whose decisions require other decisions (#14), validated while Quarkus builds the application and rebuilt
 * from the DMN the build wrote: every decision is evaluated from the model's input data through the {@code Decisions}
 * bean, beside a declared spreadsheet table, and each decision table is also an engine bean, which evaluates it alone.
 */
class DecisionGraphBeansTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/credit.dmn", "rules/credit.dmn"))
            .overrideConfigKey("quarkus.praecepta.tables.tiers.source", "src/test/resources/tables/tiers.csv")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.hit-policy", "FIRST")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.inputs[0].name", "country")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.inputs[0].type", "STRING")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.outputs[0].name", "tier")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.outputs[0].type", "STRING");

    @Inject
    Decisions decisions;

    @Inject
    @Identifier("Credit Limit")
    PraeceptaEngine<Map<String, String>, Map<String, String>> creditLimit;

    @Inject
    @Any
    Instance<PraeceptaEngine<Map<String, String>, Map<String, String>>> engines;

    @Test
    void every_decision_of_the_graph_is_evaluated_from_the_input_data() {
        Assertions.assertThat(decisions.names()).containsExactly("Credit Limit", "Monthly Limit", "Risk Category", "tiers");
        Assertions.assertThat(decisions.evaluate("tiers", Map.of("country", "PT"))).containsExactly(Map.of("tier", "gold"));
        Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "30", "Income", "1200")))
                .containsExactly(Map.of("Monthly Limit", "200"));
        Assertions.assertThat(decisions.evaluate("Credit Limit", Map.of("Applicant Age", "20", "Income", "1200")))
                .containsExactly(Map.of("Credit Limit", "600"));
        Assertions.assertThat(decisions.models()).singleElement().satisfies(model -> {
            Assertions.assertThat(model.name()).isEqualTo("Credit");
            Assertions.assertThat(model.report().isEmpty()).isTrue();
        });
    }

    @Test
    void each_decision_table_is_an_engine_bean_and_a_literal_expression_is_not() {
        Assertions.assertThat(creditLimit.evaluate(Map.of("Risk Category", "Low", "Income", "1000"))).containsExactly(Map.of("Credit Limit", "2000"));
        Assertions.assertThat(engines.select(Identifier.Literal.of("Monthly Limit")).isUnsatisfied()).isTrue();
        Assertions.assertThat(engines.stream().count()).isEqualTo(List.of("Credit Limit", "Risk Category", "tiers").size());
    }
}
