/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;

/**
 * The decision tables an application loaded at startup, by name: what the Spring Boot starter, the Micronaut
 * module and the Quarkus extension register as a bean, and what their management endpoints report.
 * Immutable and thread-safe.
 *
 * <pre>{@code
 * var decisions = Decisions.load(TableLocations.find(List.of("classpath:rules/*.dmn"), loader), Severity.ERROR);
 * List<Map<String, String>> discount = decisions.evaluate("Discount", Map.of("country", "PT", "age", "42"));
 * }</pre>
 */
public final class Decisions {

    private final Map<String, Decision> byName;

    private Decisions(Map<String, Decision> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /** No decisions. */
    public static Decisions empty() {
        return new Decisions(new TreeMap<>());
    }

    /**
     * Reads every decision table of every source (a DMN file may hold several, each its own decision) and
     * builds their engines.
     *
     * @param failOn the validation severity that fails loading: ERROR, WARNING, or null for none (unreadable
     *               or invalid tables always fail)
     * @throws DecisionsException listing every problem: a source that cannot be read or holds no decision
     *                            table, a table that is not valid DMN or cannot be built, two decisions with
     *                            the same name, and validation issues at least as severe as {@code failOn}
     */
    public static Decisions load(Collection<TableSource> sources, Severity failOn) {
        var problems = new ArrayList<String>();
        var byName = new TreeMap<String, Decision>();
        for (var source : sources) {
            for (var decision : read(source, problems)) {
                var other = byName.putIfAbsent(decision.name(), decision);
                if (other != null) {
                    problems.add("%s: decision '%s' is also defined in %s".formatted(source.location(), decision.name(), other.location()));
                } else if (failOn != null && decision.report().hasIssuesAtLeast(failOn)) {
                    problems.add("%s: decision '%s' has validation issues at %s or above:%n  %s".formatted(source.location(),
                            decision.name(), failOn, decision.report().toString().replace("\n", "\n  ")));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }
        return new Decisions(byName);
    }

    private static List<Decision> read(TableSource source, List<String> problems) {
        try (var dmn = source.opener().open()) {
            var tables = DmnReader.readAll(dmn);
            if (tables.isEmpty()) {
                problems.add(source.location() + ": no decision table");
                return List.of();
            }
            var decisions = new ArrayList<Decision>();
            for (var table : tables) {
                try {
                    decisions.add(Decision.of(source.location(), table));
                } catch (IllegalArgumentException e) {
                    problems.add(source.location() + ": " + e.getMessage());
                }
            }
            return decisions;
        } catch (IOException e) {
            problems.add("%s: cannot be read (%s)".formatted(source.location(), e.getMessage()));
        } catch (RuntimeException e) {
            problems.add(source.location() + ": " + e.getMessage());
        }
        return List.of();
    }

    /** The decision with this name, if loaded. */
    public Optional<Decision> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The decision with this name.
     *
     * @throws NoSuchElementException naming the decisions there are, if there is none by this name
     */
    public Decision get(String name) {
        var decision = byName.get(name);
        if (decision == null) {
            throw new NoSuchElementException("No decision named '%s'; there are %s".formatted(name, byName.keySet()));
        }
        return decision;
    }

    /** The engine of the decision with this name; see {@link #get(String)}. */
    public PraeceptaEngine<Map<String, String>, Map<String, String>> engine(String name) {
        return get(name).engine();
    }

    /** Evaluates the decision with this name; see {@link Decision#evaluate(Map)} and {@link #get(String)}. */
    public List<Map<String, String>> evaluate(String name, Map<String, String> input) {
        return get(name).evaluate(input);
    }

    /** The decisions' names, in order. */
    public Set<String> names() {
        return byName.keySet();
    }

    /** The decisions, ordered by name. */
    public Collection<Decision> all() {
        return byName.values();
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Every decision as {@link Decision#describe()} gives it, under {@code decisions} by name, for a
     * management endpoint ({@code /actuator/praecepta} and its Micronaut and Quarkus counterparts).
     */
    public Map<String, Object> describe() {
        var decisions = new LinkedHashMap<String, Object>();
        byName.forEach((name, decision) -> decisions.put(name, decision.describe()));
        return Map.of("decisions", Collections.unmodifiableMap(decisions));
    }

    /**
     * Parses a {@code fail-on} setting: {@code ERROR}, {@code WARNING}, or {@code NONE} (null), in any case.
     *
     * @throws IllegalArgumentException for any other value, null included
     */
    public static Severity failOn(String value) {
        var text = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "NONE" -> null;
            case "ERROR" -> Severity.ERROR;
            case "WARNING" -> Severity.WARNING;
            default -> throw new IllegalArgumentException("fail-on must be ERROR, WARNING or NONE, not " + value);
        };
    }

    @Override
    public String toString() {
        return "Decisions" + byName.keySet();
    }
}
