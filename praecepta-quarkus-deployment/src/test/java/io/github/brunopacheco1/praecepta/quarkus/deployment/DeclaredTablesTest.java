/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.nio.file.Path;
import java.util.Map;

import jakarta.inject.Inject;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Identifier;

/**
 * A CSV table declared under {@code quarkus.praecepta.tables} is compiled at build time, as the Maven plugin
 * compiles it, and becomes an engine like a DMN file; explicit locations, on the class path or the file system,
 * replace the default one.
 */
class DeclaredTablesTest {

    private static final String TABLES = Path.of("src/test/resources/tables").toAbsolutePath().toString().replace('\\', '/');

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root
                    .addAsResource("tables/shipping.dmn", "decisions/shipping.dmn")
                    // the default location is not searched once locations are set
                    .addAsResource("tables/overlapping.dmn", "rules/overlapping.dmn"))
            .overrideConfigKey("quarkus.praecepta.locations", "classpath:decisions/*.dmn,file:" + TABLES + "/unr*.dmn")
            // relative to the project's directory
            .overrideConfigKey("quarkus.praecepta.tables.pricing.source", "src/test/resources/tables/discount.csv")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.hit-policy", "first")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[0].name", "country")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[0].allowed-values", "\"PT\",\"ES\",\"FR\"")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[1].name", "age")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[1].type", "NUMBER")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[2].name", "since")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.inputs[2].type", "date")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.outputs[0].name", "discount")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.outputs[0].type", "NUMBER")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.outputs[1].name", "tier");

    @Inject
    Decisions decisions;

    @Inject
    @Identifier("discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Test
    void a_declared_spreadsheet_is_named_after_its_file_and_evaluated_like_a_dmn_table() {
        Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Fee", "Grade", "discount");
        var decision = decisions.get("discount");
        Assertions.assertThat(decision.location()).endsWith("src/test/resources/tables/discount.csv");
        Assertions.assertThat(decision.table().hitPolicy()).isEqualTo(HitPolicy.FIRST);
        Assertions.assertThat(discount.evaluate(Map.of("country", "PT", "age", "30", "since", "2019-01-01")))
                .containsExactly(Map.of("discount", "0.15", "tier", "gold"));
        Assertions.assertThat(discount.evaluate(Map.of("country", "FR", "age", "12"))).containsExactly(Map.of("discount", "0", "tier", "junior"));
    }

    @Test
    void file_locations_are_searched_on_the_file_system() {
        Assertions.assertThat(Path.of(decisions.get("Grade").location())).isEqualTo(Path.of(TABLES, "unreachable.dmn"));
    }

    @Test
    void the_declared_value_list_reaches_the_engine() {
        var unlisted = Map.of("country", "DE", "age", "30");
        Assertions.assertThatThrownBy(() -> discount.evaluate(unlisted)).isInstanceOf(InvalidInputException.class);
    }
}
