/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.TableLocations.Location;

/**
 * What every framework integration does with its settings: reads {@code locations} and {@code fail-on}, finds
 * the DMN files through the framework's own resource lookup, loads them, fails with every problem at once, and
 * logs what it loaded. So the Spring Boot starter, the Micronaut module and the Quarkus extension take the
 * same settings and word their problems alike; each supplies only its lookup and its logger.
 *
 * <pre>{@code
 * var decisions = new DecisionsLoader("praecepta", locations, "ERROR")   // locations: null when not set
 *         .load(location -> TableLocations.find(location, classLoader), log);
 * }</pre>
 *
 * The problems, all listed in one {@link DecisionsException}, are worded:
 * <ul>
 * <li>{@code <prefix>.fail-on must be ERROR, WARNING or NONE, not <value>}; the tables are then still loaded
 * and checked at ERROR, so their problems are listed too;</li>
 * <li>{@code <prefix>.locations is set but names no location};</li>
 * <li>{@code Location '<pattern>': ...}, for a malformed pattern ({@link Location#parse});</li>
 * <li>{@code <pattern>: no DMN file matches this location}, for a location set explicitly that finds nothing
 * (the default location may find nothing);</li>
 * <li>{@code <pattern>: cannot be searched (<why>)};</li>
 * <li>and what {@link Decisions#load} reports of the tables, as {@code <file>: <what>}.</li>
 * </ul>
 */
public final class DecisionsLoader {

    /** Finds the DMN files of one parsed location pattern, through the framework's resource lookup. */
    @FunctionalInterface
    public interface Finder {

        /**
         * @return the files found, each once
         * @throws IOException          if the location cannot be searched
         * @throws UncheckedIOException if a file found cannot be read; its message names the file
         */
        List<TableSource> find(Location location) throws IOException;
    }

    /** Where the loader reports what it loaded, through the framework's logging. */
    public interface Log {

        /** One line: {@code Loaded 2 decision(s) from 1 DMN file(s): Discount, Fee}, or that none were found. */
        void info(String message);

        /** One line per validation issue below {@code fail-on}: {@code Discount: WARNING rule 3 can never be selected}. */
        void warn(String message);
    }

    private final String prefix;
    private final List<String> locations;
    private final String failOn;
    private final List<TableSource> declared = new ArrayList<>();
    private final List<String> declaredProblems = new ArrayList<>();

    /**
     * @param prefix    the settings' prefix, as messages name them: {@code praecepta}, {@code quarkus.praecepta}
     * @param locations the {@code locations} setting: its patterns, each of which may itself be a comma-separated
     *                  list; null if it is not set, which is {@link TableLocations#DEFAULT}
     * @param failOn    the {@code fail-on} setting, as {@link Severity#failOn} reads it
     */
    public DecisionsLoader(String prefix, List<String> locations, String failOn) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.locations = locations == null ? null : List.copyOf(locations);
        this.failOn = failOn;
    }

    /**
     * Adds DMN found elsewhere, such as the tables the Quarkus extension compiles from spreadsheets, and the
     * problems met while producing them, which are listed with the others.
     */
    public DecisionsLoader declared(List<TableSource> sources, List<String> problems) {
        declared.addAll(sources);
        declaredProblems.addAll(problems);
        return this;
    }

    /** The location patterns the settings name, in order: the default one when {@code locations} is not set. */
    public List<String> patterns() {
        if (locations == null) {
            return List.of(TableLocations.DEFAULT);
        }
        return locations.stream().flatMap(location -> TableLocations.split(location).stream()).toList();
    }

    /**
     * Finds and loads the tables, then logs how many decisions were loaded and every validation issue below
     * {@code fail-on} as a warning.
     *
     * @throws DecisionsException listing every problem with the settings, the locations or the tables
     */
    public Decisions load(Finder finder, Log log) {
        var problems = new ArrayList<String>();
        var severity = Severity.ERROR;
        try {
            severity = Severity.failOn(failOn);
        } catch (IllegalArgumentException e) {
            problems.add(prefix + ".fail-on " + e.getMessage());
        }
        boolean explicit = locations != null;
        var patterns = patterns();
        if (explicit && patterns.isEmpty()) {
            problems.add(prefix + ".locations is set but names no location");
        }
        var sources = new TreeMap<String, TableSource>();
        for (var pattern : patterns) {
            find(finder, pattern, explicit, problems)
                    .forEach(source -> sources.putIfAbsent(source.location(), source));
        }
        declared.forEach(source -> sources.putIfAbsent(source.location(), source));
        problems.addAll(declaredProblems);

        var decisions = Decisions.empty();
        try {
            decisions = Decisions.load(sources.values(), severity);
        } catch (DecisionsException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }
        if (decisions.isEmpty()) {
            log.info("No decision tables found at " + String.join(", ", patterns));
        } else {
            log.info("Loaded %d decision(s) from %d DMN file(s): %s".formatted(decisions.names().size(), sources.size(),
                    String.join(", ", decisions.names())));
        }
        for (var decision : decisions.all()) {
            decision.report().issues().forEach(issue -> log.warn(decision.name() + ": " + issue));
        }
        return decisions;
    }

    /** The files one location finds, or none, adding to problems what goes wrong. */
    private static List<TableSource> find(Finder finder, String pattern, boolean explicit, List<String> problems) {
        try {
            var found = finder.find(Location.parse(pattern));
            if (found.isEmpty() && explicit) {
                problems.add(pattern + ": no DMN file matches this location");
            }
            return found;
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        } catch (IOException | UncheckedIOException e) {
            problems.add("%s: cannot be searched (%s)".formatted(pattern, e.getMessage()));
        }
        return List.of();
    }
}
