/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.github.brunopacheco1.praecepta.integration.Decisions;

/**
 * Loads the DMN decision tables at {@code praecepta.locations} when the application starts, and registers:
 *
 * <ul>
 * <li>a {@link Decisions} bean named {@value #DECISIONS_BEAN}, created at startup and never lazily;</li>
 * <li>for each decision, its engine, a {@code PraeceptaEngine<Map<String, String>, Map<String, String>>} named
 * {@code praecepta.<decision name>} and injected with {@code @Qualifier("<decision name>")};</li>
 * <li>with Actuator, the endpoint {@code praecepta} ({@link PraeceptaEndpoint}), exposed under Actuator's usual
 * rules.</li>
 * </ul>
 *
 * A table that cannot be read or built, two decisions with the same name, a location set explicitly that finds
 * no file, or validation issues at {@code praecepta.fail-on} or above stop the startup with a
 * {@link io.github.brunopacheco1.praecepta.integration.DecisionsException} listing every problem. Other
 * validation issues are logged as warnings. {@code praecepta.enabled=false} turns all of this off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = PraeceptaProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PraeceptaProperties.class)
@Import(DecisionsRegistrar.class)
public class PraeceptaAutoConfiguration {

    /** The name of the {@link Decisions} bean. */
    public static final String DECISIONS_BEAN = "praeceptaDecisions";

    /** What an engine bean's name starts with; the decision's name follows. */
    public static final String ENGINE_BEAN_PREFIX = "praecepta.";

    /** Spring creates it; it only holds the names above and imports the beans. */
    PraeceptaAutoConfiguration() {
        // the beans come from DecisionsRegistrar and ActuatorConfiguration
    }

    /** The Actuator endpoint, when Actuator is on the class path and the endpoint is available. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ Endpoint.class, ConditionalOnAvailableEndpoint.class })
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint
        PraeceptaEndpoint praeceptaEndpoint(Decisions decisions) {
            return new PraeceptaEndpoint(decisions);
        }
    }
}
