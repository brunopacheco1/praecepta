/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.brunopacheco1.praecepta.integration.TableLocations;

/**
 * The settings under {@code praecepta}, the same keys, defaults and values as in every Praecepta framework
 * integration.
 *
 * <pre>
 * praecepta.locations=classpath*:rules/**&#47;*.dmn,file:/etc/acme/rules/*.dmn
 * praecepta.fail-on=WARNING
 * </pre>
 */
@ConfigurationProperties(PraeceptaProperties.PREFIX)
public class PraeceptaProperties {

    /** The prefix of every key. */
    public static final String PREFIX = "praecepta";

    /** Whether to load the decision tables and register their beans and the Actuator endpoint. */
    private boolean enabled = true;

    /**
     * The DMN files to load, as location patterns. "classpath:" and "classpath*:" both search every class path
     * root, "file:" searches the file system; "*" matches within a path segment and "**" across segments.
     * A pattern set here that finds no file stops startup.
     */
    private List<String> locations = List.of(TableLocations.DEFAULT);

    /**
     * The validation severity that stops startup: ERROR, WARNING or NONE, in any case. Tables that cannot be
     * read or built stop it whatever this says.
     */
    private String failOn = "ERROR";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public String getFailOn() {
        return failOn;
    }

    public void setFailOn(String failOn) {
        this.failOn = failOn;
    }
}
