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

/** A decision requirements graph whose decisions require one another in a cycle fails the build, naming the cycle. */
class DecisionGraphFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/cycle.dmn", "rules/cycle.dmn"))
            .assertException(failure -> {
                Assertions.assertThat(failure.getClass().getName()).isEqualTo(DecisionsException.class.getName());
                Assertions.assertThat(failure.getMessage())
                        .containsPattern("rules/cycle.dmn: decision model 'Cycle' has validation issues at ERROR or above:\\n  ERROR ")
                        .contains("decisions 'A' -> 'B' -> 'A' require one another in a cycle");
            });

    @Test
    @SuppressWarnings("java:S2699") // assertException above is the assertion: Quarkus runs it on the build's failure
    void the_build_fails_naming_the_cycle() {
        // the extension checks the failure; a build that succeeds fails this test
    }
}
