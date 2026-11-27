/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring.app;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.brunopacheco1.praecepta.integration.DecisionsException;

@ExtendWith(OutputCaptureExtension.class)
class PricingApplicationFailureTest {

    @Test
    void an_invalid_table_stops_the_application_and_the_failure_analysis_lists_every_problem(CapturedOutput output) {
        var application = new SpringApplication(PricingApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        Assertions.assertThatThrownBy(() -> application.run("--praecepta.locations=classpath:tables/overlapping.dmn,classpath:nowhere/*.dmn"))
                .isInstanceOf(DecisionsException.class);

        Assertions.assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("Praecepta could not load the decision tables:")
                .contains("- classpath:nowhere/*.dmn: no DMN file matches this location")
                .contains("tables/overlapping.dmn: decision 'Tier' has validation issues at ERROR or above:")
                .contains("    ERROR rules [1, 2] both match some inputs (UNIQUE)")
                .contains("praecepta.fail-on the validation severity that stops startup");
    }
}
