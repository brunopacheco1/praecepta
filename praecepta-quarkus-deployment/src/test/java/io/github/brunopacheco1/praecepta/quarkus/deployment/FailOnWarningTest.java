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

/** With {@code fail-on=warning}, a warning of the validation report fails the build. */
class FailOnWarningTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/unreachable.dmn", "rules/unreachable.dmn"))
            .overrideConfigKey("quarkus.praecepta.fail-on", "warning")
            .assertException(failure -> {
                Assertions.assertThat(failure.getClass().getName()).isEqualTo(DecisionsException.class.getName());
                Assertions.assertThat(failure.getMessage())
                        .startsWith("Cannot load decision tables: ")
                        .containsPattern("rules/unreachable.dmn: decision 'Grade' has validation issues at WARNING or above:\\n  WARNING ");
            });

    @Test
    @SuppressWarnings("java:S2699") // assertException above is the assertion: Quarkus runs it on the build's failure
    void a_warning_fails_the_build() {
        // the extension checks the failure; a build that succeeds fails this test
    }
}
