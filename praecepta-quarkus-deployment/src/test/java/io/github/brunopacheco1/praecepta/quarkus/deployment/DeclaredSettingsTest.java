/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.Map;

import jakarta.inject.Inject;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Every setting of a declared table reaches the compiled table, spelled in kebab-case: an aggregation, an
 * output's priority list, a list input, a DMN file's decision, and an Excel workbook, whose Apache POI runs in
 * Quarkus' build class loader.
 */
class DeclaredSettingsTest {

    private static final String TABLES = "src/test/resources/tables/";

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.praecepta.tables.fees.source", TABLES + "fees.csv")
            .overrideConfigKey("quarkus.praecepta.tables.fees.hit-policy", "COLLECT")
            .overrideConfigKey("quarkus.praecepta.tables.fees.aggregation", "SUM")
            .overrideConfigKey("quarkus.praecepta.tables.fees.inputs[0].name", "zone")
            .overrideConfigKey("quarkus.praecepta.tables.fees.outputs[0].name", "fee")
            .overrideConfigKey("quarkus.praecepta.tables.fees.outputs[0].type", "NUMBER")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.source", TABLES + "tiers.csv")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.hit-policy", "PRIORITY")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.inputs[0].name", "country")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.outputs[0].name", "tier")
            .overrideConfigKey("quarkus.praecepta.tables.tiers.outputs[0].priority", "silver,gold")
            .overrideConfigKey("quarkus.praecepta.tables.offers.source", TABLES + "nationalities.csv")
            .overrideConfigKey("quarkus.praecepta.tables.offers.hit-policy", "FIRST")
            .overrideConfigKey("quarkus.praecepta.tables.offers.inputs[0].name", "nationalities")
            .overrideConfigKey("quarkus.praecepta.tables.offers.inputs[0].list", "true")
            .overrideConfigKey("quarkus.praecepta.tables.offers.inputs[1].name", "ages")
            .overrideConfigKey("quarkus.praecepta.tables.offers.inputs[1].type", "NUMBER")
            .overrideConfigKey("quarkus.praecepta.tables.offers.inputs[1].list", "true")
            .overrideConfigKey("quarkus.praecepta.tables.offers.outputs[0].name", "offer")
            .overrideConfigKey("quarkus.praecepta.tables.carrier.source", TABLES + "shipping.dmn")
            .overrideConfigKey("quarkus.praecepta.tables.carrier.decision", "Carrier")
            .overrideConfigKey("quarkus.praecepta.tables.matrix.source", TABLES + "matrix.xlsx")
            .overrideConfigKey("quarkus.praecepta.tables.matrix.hit-policy", "FIRST")
            .overrideConfigKey("quarkus.praecepta.tables.matrix.inputs[0].name", "inputOne")
            .overrideConfigKey("quarkus.praecepta.tables.matrix.inputs[1].name", "inputTwo")
            .overrideConfigKey("quarkus.praecepta.tables.matrix.outputs[0].name", "output");

    @Inject
    Decisions decisions;

    @Test
    void an_aggregation_reaches_the_table() {
        Assertions.assertThat(decisions.get("fees").table().aggregation()).isEqualTo(AggregationOperator.SUM);
        Assertions.assertThat(decisions.evaluate("fees", Map.of("zone", "islands"))).containsExactly(Map.of("fee", "17.5"));
    }

    @Test
    void an_outputs_priority_list_orders_the_outputs() {
        Assertions.assertThat(decisions.get("tiers").table().hitPolicy()).isEqualTo(HitPolicy.PRIORITY);
        // both rules match; silver comes first in the priority list
        Assertions.assertThat(decisions.evaluate("tiers", Map.of("country", "PT"))).containsExactly(Map.of("tier", "silver"));
    }

    @Test
    void list_inputs_hold_lists() {
        Assertions.assertThat(decisions.get("nationalities").table().listInputs()).containsExactlyInAnyOrder("nationalities", "ages");
        Assertions.assertThat(decisions.evaluate("nationalities", Map.of("nationalities", "[\"BR\", \"ES\"]")))
                .containsExactly(Map.of("offer", "iberian"));
        Assertions.assertThat(decisions.evaluate("nationalities", Map.of("nationalities", "[\"BR\"]", "ages", "[70]")))
                .containsExactly(Map.of("offer", "senior"));
    }

    @Test
    void a_dmn_files_decision_is_picked_by_name() {
        Assertions.assertThat(decisions.find("Carrier")).hasValueSatisfying(decision ->
                Assertions.assertThat(decision.evaluate(Map.of("weight", "30"))).containsExactly(Map.of("Carrier", "freight")));
    }

    @Test
    void an_excel_workbook_is_compiled_at_build_time() {
        Assertions.assertThat(decisions.evaluate("matrix", Map.of("inputOne", "TEST2", "inputTwo", "TEST")))
                .containsExactly(Map.of("output", "rule2"));
        Assertions.assertThat(decisions.evaluate("matrix", Map.of("inputOne", "X", "inputTwo", "TEST"))).containsExactly(Map.of("output", "rule3"));
    }
}
