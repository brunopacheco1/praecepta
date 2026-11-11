/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.io.File;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.assertj.core.api.Assertions;
import org.jboss.shrinkwrap.api.asset.FileAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;

/**
 * DMN files under {@code rules/} (the default location), in the application or a dependency, become a
 * {@code Decisions} bean and one engine bean per decision.
 */
class DecisionBeansTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root
                    .addAsResource("tables/discount.dmn", "rules/discount.dmn")
                    // outside the default location: not loaded
                    .addAsResource("tables/overlapping.dmn", "other/overlapping.dmn"))
            // the default location covers every class path root, dependencies included
            .withAdditionalDependency(jar -> jar.add(new FileAsset(new File("src/test/resources/tables/shipping.dmn")),
                    "rules/shipping/shipping.dmn"));

    @Inject
    Decisions decisions;

    @Inject
    @Identifier("Discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Inject
    @Named("Carrier")
    PraeceptaEngine<Map<String, String>, Map<String, String>> carrier;

    @Inject
    @Any
    Instance<PraeceptaEngine<Map<String, String>, Map<String, String>>> engines;

    @Test
    void every_decision_table_under_rules_is_loaded_by_its_decision_name() {
        Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");
        Assertions.assertThat(decisions.get("Carrier").location()).endsWith("rules/shipping/shipping.dmn");
        Assertions.assertThat(decisions.evaluate("Fee", Map.of("zone", "islands"))).containsExactly(Map.of("fee", "17.5"));
    }

    @Test
    void each_decision_is_an_engine_bean_qualified_by_its_name() {
        Assertions.assertThat(discount.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")))
                .containsExactly(Map.of("discount", "0.15", "tier", "gold"), Map.of("discount", "0.05", "tier", "silver"));
        Assertions.assertThat(carrier.evaluate(Map.of("weight", "1.5"))).containsExactly(Map.of("Carrier", "post"));
        // the beans are the engines of the Decisions bean, built once
        Assertions.assertThat(discount).isSameAs(decisions.engine("Discount"));
        Assertions.assertThat(engines.select(Identifier.Literal.of("Fee")).get()).isSameAs(decisions.engine("Fee"));
        Assertions.assertThat(engines.stream().toList()).hasSize(3);
    }

    @Test
    void an_engine_is_not_injected_without_its_name() {
        Assertions.assertThat(engines.select(Default.Literal.INSTANCE).isUnsatisfied()).isTrue();
        Assertions.assertThat(engines.select(Identifier.Literal.of("Nope")).isUnsatisfied()).isTrue();
    }

    @Test
    void the_reports_are_available_at_runtime() {
        @SuppressWarnings("unchecked")
        var described = (Map<String, Map<String, Object>>) decisions.describe().get("decisions");
        Assertions.assertThat(described).containsOnlyKeys("Carrier", "Discount", "Fee");
        var discountReport = described.get("Discount");
        Assertions.assertThat(discountReport)
                .containsEntry("name", "Discount")
                .containsEntry("hitPolicy", "RULE_ORDER")
                .containsEntry("rules", 2);
        Assertions.assertThat((String) discountReport.get("location")).endsWith("rules/discount.dmn");
        Assertions.assertThat((List<?>) discountReport.get("issues")).isEqualTo(decisions.get("Discount").describe().get("issues"));
    }
}
