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

/** {@code fail-on} takes ERROR, WARNING or NONE only, as in every Praecepta integration. */
class InvalidFailOnTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.praecepta.fail-on", "sometimes")
            .assertException(failure -> {
                // listed with every other problem, as in the Spring Boot and Micronaut integrations
                Assertions.assertThat(failure.getClass().getName()).isEqualTo(DecisionsException.class.getName());
                Assertions.assertThat(failure.getMessage())
                        .isEqualTo("Cannot load decision tables: quarkus.praecepta.fail-on must be ERROR, WARNING or NONE, not sometimes");
            });

    @Test
    void an_unknown_severity_fails_the_build() {
        // the extension checks the failure; a build that succeeds fails this test
    }
}
