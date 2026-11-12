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

/** Finding nothing at the default location is fine: the application gets an empty {@code Decisions}. */
class NoTablesTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .setLogRecordPredicate(logRecord -> logRecord.getLoggerName().equals(PraeceptaProcessor.class.getName()))
            .assertLogRecords(records -> Assertions.assertThat(records)
                    .anySatisfy(logRecord -> {
                        Assertions.assertThat(logRecord.getLevel()).isEqualTo(Level.INFO);
                        Assertions.assertThat(logRecord.getMessage()).isEqualTo("No decision tables found at classpath*:rules/**/*.dmn");
                    }));

    @Inject
    Decisions decisions;

    @Test
    void the_decisions_are_empty() {
        Assertions.assertThat(decisions.isEmpty()).isTrue();
        Assertions.assertThat(decisions.describe()).containsEntry("decisions", Map.of());
    }
}
