/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.Map;
import java.util.logging.Level;

import jakarta.inject.Inject;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A DMN file whose model uses what a later release plans (here a business knowledge model, 0.10) still loads its
 * decision tables alone, as in 0.6, whether found at a location or declared under {@code quarkus.praecepta.tables},
 * and the build logs one line at INFO per such file, naming it, the feature and the release and issue that plan it,
 * so that the graph left out is seen.
 */
class FallbackLoggingTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/later.dmn", "rules/later.dmn"))
            .overrideConfigKey("quarkus.praecepta.tables.rated.source", "src/test/resources/tables/rated.dmn")
            .setLogRecordPredicate(logRecord -> logRecord.getLoggerName().equals(PraeceptaProcessor.class.getName()))
            .assertLogRecords(records -> {
                var fallbacks = records.stream().filter(logRecord -> logRecord.getMessage().contains(" alone, ")).toList();
                Assertions.assertThat(fallbacks).hasSize(2).allSatisfy(logRecord -> {
                    Assertions.assertThat(logRecord.getLevel()).isEqualTo(Level.INFO);
                    Assertions.assertThat(logRecord.getMessage())
                            .contains(", without its decision requirements graph, which needs Praecepta 0.10 (#118): ",
                                    "business knowledge model 'Rate'");
                });
                // the file found at the default location, loaded by the build step
                Assertions.assertThat(fallbacks).anySatisfy(logRecord -> Assertions.assertThat(logRecord.getMessage())
                        .contains("rules/later.dmn: its decision tables are loaded alone"));
                // the declared one, compiled by praecepta-compiler
                Assertions.assertThat(fallbacks).anySatisfy(logRecord -> Assertions.assertThat(logRecord.getMessage())
                        .contains("rated.dmn: its decision table is compiled alone"));
            });

    @Inject
    Decisions decisions;

    @Test
    void the_tables_load_alone() {
        Assertions.assertThat(decisions.names()).containsExactly("Rated", "Tier");
        Assertions.assertThat(decisions.models()).isEmpty();
        Assertions.assertThat(decisions.evaluate("Tier", Map.of("age", "30"))).containsExactly(Map.of("tier", "adult"));
        Assertions.assertThat(decisions.evaluate("Rated", Map.of("age", "12"))).containsExactly(Map.of("tier", "junior"));
    }
}
