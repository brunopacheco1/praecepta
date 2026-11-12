/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.util.List;

import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.util.Toggleable;

/**
 * The {@code praecepta.*} settings, with the keys and defaults every Praecepta framework integration uses:
 *
 * <ul>
 * <li>{@code praecepta.enabled}: {@code false} turns the integration off (no beans, no endpoint);</li>
 * <li>{@code praecepta.locations}: the DMN files to load, as a comma-separated list or a list of location
 * patterns ({@link TableLocations#DEFAULT} when not set);</li>
 * <li>{@code praecepta.fail-on}: the validation severity that fails startup, {@code ERROR},
 * {@code WARNING} or {@code NONE}.</li>
 * </ul>
 *
 * Bound by Micronaut's compile-time introspection, without reflection.
 */
@ConfigurationProperties(PraeceptaConfiguration.PREFIX)
@Requires(property = PraeceptaConfiguration.ENABLED, notEquals = StringUtils.FALSE)
public class PraeceptaConfiguration implements Toggleable {

    /** The prefix of every setting. */
    public static final String PREFIX = "praecepta";

    /** The setting that turns the integration off when {@code false}. */
    public static final String ENABLED = PREFIX + ".enabled";

    private boolean active = true;
    private List<String> locations;
    private String failOn = "ERROR";

    /** Whether the integration is on ({@code praecepta.enabled}, default {@code true}). */
    @Override
    public boolean isEnabled() {
        return active;
    }

    public void setEnabled(boolean enabled) {
        this.active = enabled;
    }

    /**
     * The location patterns of the DMN files to load ({@code praecepta.locations}), or null when not set,
     * which means {@link TableLocations#DEFAULT}. When set, a pattern that finds no file fails startup.
     */
    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations == null ? null : List.copyOf(locations);
    }

    /**
     * The validation severity that fails startup ({@code praecepta.fail-on}): {@code ERROR} (the default),
     * {@code WARNING} or {@code NONE}, in any case.
     */
    public String getFailOn() {
        return failOn;
    }

    public void setFailOn(String failOn) {
        this.failOn = failOn;
    }
}
