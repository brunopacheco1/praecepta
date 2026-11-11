/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.quarkus.test.QuarkusExtensionTest;

/** Every problem with the tables fails the build, all listed in one message. */
class BuildFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root
                    .addAsResource("tables/overlapping.dmn", "rules/overlapping.dmn")
                    .addAsResource("tables/broken.dmn", "rules/broken.dmn")
                    .addAsResource("tables/discount.dmn", "rules/discount.dmn")
                    .addAsResource("tables/discount.dmn", "rules/copy/discount.dmn"))
            .overrideConfigKey("quarkus.praecepta.locations", "classpath:rules/**/*.dmn, classpath:missing/*.dmn")
            .overrideConfigKey("quarkus.praecepta.tables.pricing.source", "src/test/resources/tables/discount.csv")
            .overrideConfigKey("quarkus.praecepta.tables.nowhere.source", "src/test/resources/tables/nowhere.csv")
            .overrideConfigKey("quarkus.praecepta.tables.nowhere.hit-policy", "FIRST")
            .assertException(failure -> {
                Assertions.assertThat(failure.getClass().getName()).isEqualTo(DecisionsException.class.getName());
                Assertions.assertThat(failure.getMessage())
                        .startsWith("Cannot load decision tables (6 problems):")
                        .contains("\n- classpath:missing/*.dmn: no DMN file matches this location\n")
                        .contains("quarkus.praecepta.tables.\"pricing\": Table source ")
                        .contains("'hit-policy' is required for Excel and CSV tables")
                        .contains("quarkus.praecepta.tables.\"nowhere\": Table source ")
                        .contains("nowhere.csv does not exist")
                        .containsPattern("rules/broken.dmn: .+")
                        .containsPattern("rules/overlapping.dmn: decision 'Tier' has validation issues at ERROR or above:\\n  ERROR ")
                        .containsPattern("rules/discount.dmn: decision 'Discount' is also defined in .*rules/copy/discount.dmn");
            });

    @Test
    void the_build_fails_listing_every_problem() {
        // the extension checks the failure; a build that succeeds fails this test
    }
}
