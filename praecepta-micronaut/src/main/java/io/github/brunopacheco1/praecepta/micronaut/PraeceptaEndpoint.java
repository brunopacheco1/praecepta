/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.util.Map;

import io.github.brunopacheco1.praecepta.integration.Decision;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;

/**
 * The management endpoint {@code praecepta}, present when the application has {@code micronaut-management}:
 * {@code GET /praecepta} lists every decision with its columns and validation issues
 * ({@link Decisions#describe()}), and {@code GET /praecepta/{name}} describes one ({@link Decision#describe()}),
 * or answers 404 for a name with no decision. It only reads; nothing is evaluated through it.
 *
 * <p>Like other Micronaut endpoints, it is configured under {@code endpoints.praecepta}. It is sensitive by
 * default, since the reports show the rules' conditions: open it with
 * {@code endpoints.praecepta.sensitive: false}, or secure it with Micronaut Security.
 */
@Endpoint(id = PraeceptaEndpoint.ID)
@Requires(classes = Endpoint.class)
@Requires(property = PraeceptaConfiguration.ENABLED, notEquals = StringUtils.FALSE)
public class PraeceptaEndpoint {

    /** The endpoint's id, its path and configuration prefix ({@code endpoints.praecepta}). */
    public static final String ID = "praecepta";

    private final Decisions decisions;

    public PraeceptaEndpoint(Decisions decisions) {
        this.decisions = decisions;
    }

    /** Every decision, under {@code decisions} by name. */
    @Read
    public Map<String, Object> decisions() {
        return decisions.describe();
    }

    /** One decision, or null (404) if there is none by this name. */
    @Read
    @Nullable
    public Map<String, Object> decision(@Selector String name) {
        return decisions.find(name).map(Decision::describe).orElse(null);
    }
}
