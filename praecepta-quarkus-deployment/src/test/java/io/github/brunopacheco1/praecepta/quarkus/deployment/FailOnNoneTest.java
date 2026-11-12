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

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code fail-on=none}, even an error of the validation report lets the build pass: it is logged, one
 * line per issue prefixed with the decision's name, and stays in the report at runtime.
 */
class FailOnNoneTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/overlapping.dmn", "rules/overlapping.dmn"))
            .overrideConfigKey("quarkus.praecepta.fail-on", "NONE")
            .setLogRecordPredicate(record -> record.getLoggerName().equals(PraeceptaProcessor.class.getName()))
            .assertLogRecords(records -> Assertions.assertThat(records)
                    .anySatisfy(record -> {
                        Assertions.assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                        Assertions.assertThat(record.getMessage()).startsWith("Tier: ERROR ");
                    }));

    @Inject
    Decisions decisions;

    @Test
    void the_issue_is_in_the_report_at_runtime() {
        Assertions.assertThat(decisions.get("Tier").report().hasIssuesAtLeast(Severity.ERROR)).isTrue();
        // the engine works for inputs only one rule matches
        Assertions.assertThat(decisions.evaluate("Tier", Map.of("country", "ES"))).containsExactly(Map.of("tier", "silver"));
    }
}
