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

import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * A decision table in the full FEEL language, validated while Quarkus builds the application: a column
 * computed by its input expression, cells beyond the simple unary tests and output entries of several types, read by a
 * literal expression.
 */
class FeelGraphBeansTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addAsResource("tables/feel.dmn", "rules/feel.dmn"))
            .overrideConfigKey("quarkus.praecepta.fail-on", "ERROR");

    @Inject
    Decisions decisions;

    @Test
    void a_decision_table_in_the_full_feel_language_is_evaluated_from_the_input_data() {
        Assertions.assertThat(decisions.names()).containsExactly("Band", "Summary");
        Assertions.assertThat(decisions.evaluate("Band", Map.of("Age", "30", "Limit", "100"))).containsExactly(Map.of("Band", "360"));
        Assertions.assertThat(decisions.evaluate("Band", Map.of("Age", "30", "Limit", "20"))).containsExactly(Map.of("Band", "over the limit"));
        Assertions.assertThat(decisions.evaluate("Summary", Map.of("Age", "30", "Limit", "100"))).containsExactly(Map.of("Summary", "true"));
        Assertions.assertThat(decisions.models()).singleElement().satisfies(model -> Assertions.assertThat(model.report().issues()).isEmpty());
    }
}
