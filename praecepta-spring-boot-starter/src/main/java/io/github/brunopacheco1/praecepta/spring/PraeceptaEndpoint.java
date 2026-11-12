/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.util.Map;
import java.util.Objects;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import io.github.brunopacheco1.praecepta.integration.Decision;
import io.github.brunopacheco1.praecepta.integration.Decisions;

/**
 * The Actuator endpoint {@code praecepta}: the loaded decisions with their columns and validation reports
 * ({@code /actuator/praecepta}), or one of them ({@code /actuator/praecepta/{name}}, 404 for an unknown name).
 * It only reads; nothing is evaluated through it. Exposed under Actuator's usual rules, such as
 * {@code management.endpoints.web.exposure.include=praecepta}.
 */
@Endpoint(id = "praecepta")
public class PraeceptaEndpoint {

    private final Decisions decisions;

    public PraeceptaEndpoint(Decisions decisions) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /** Every decision, as {@link Decisions#describe()} gives it: {@code {"decisions": {"Discount": {...}}}}. */
    @ReadOperation
    public Map<String, Object> decisions() {
        return decisions.describe();
    }

    /** The decision with this name, as {@link Decision#describe()} gives it, or null (404) if there is none. */
    @ReadOperation
    public Map<String, Object> decision(@Selector String name) {
        return decisions.find(name).map(Decision::describe).orElse(null);
    }
}
