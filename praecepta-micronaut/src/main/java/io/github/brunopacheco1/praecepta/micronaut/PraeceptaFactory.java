/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decision;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.integration.TableSource;
import io.micronaut.context.BeanContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;

/**
 * Loads the application's decision tables at startup and provides them as beans:
 *
 * <ul>
 * <li>one {@link Decisions}, created with the application context ({@code @Context}), before the
 * application's own context-scoped beans;</li>
 * <li>for each decision, its engine, a singleton of type
 * {@code PraeceptaEngine<Map<String, String>, Map<String, String>>} ({@link #ENGINE}) named after the
 * decision, injected with {@code @Named("Discount")}.</li>
 * </ul>
 *
 * The DMN files are those {@code praecepta.locations} finds with the application's class loader; every
 * decision table in them becomes one decision. A file that cannot be read, a table the engine cannot build,
 * two decisions with one name, a location set explicitly that finds no file, a {@code praecepta.fail-on}
 * that is not a severity, and validation issues at {@code praecepta.fail-on} or above stop the application
 * from starting, with every problem listed in one {@link DecisionsException}. Issues below it are logged as
 * warnings, one line per issue, prefixed with the decision's name.
 */
@Factory
@Requires(property = PraeceptaConfiguration.ENABLED, notEquals = StringUtils.FALSE)
public class PraeceptaFactory {

    /** The type of the engine beans: {@code PraeceptaEngine<Map<String, String>, Map<String, String>>}. */
    public static final Argument<PraeceptaEngine<Map<String, String>, Map<String, String>>> ENGINE = engineType();

    private static final Logger LOG = LoggerFactory.getLogger(PraeceptaFactory.class);

    /**
     * Loads the decisions and registers an engine bean per decision. Created first among the context-scoped
     * beans, so that every bean of the application can inject the engines.
     *
     * @throws DecisionsException listing every problem with the settings, the locations or the tables
     */
    @Context
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public Decisions decisions(PraeceptaConfiguration configuration, BeanContext context) {
        var decisions = load(configuration, context.getClassLoader());
        for (var decision : decisions.all()) {
            context.registerBeanDefinition(RuntimeBeanDefinition.builder(ENGINE, decision::engine)
                    .named(decision.name())
                    .singleton(true)
                    .build());
            decision.report().issues().forEach(issue -> LOG.warn("{}: {}", decision.name(), issue));
        }
        return decisions;
    }

    /** Finds and loads the configured tables, collecting every problem before failing. */
    static Decisions load(PraeceptaConfiguration configuration, ClassLoader loader) {
        var problems = new ArrayList<String>();
        var failOn = Severity.ERROR;
        try {
            failOn = Decisions.failOn(configuration.getFailOn());
        } catch (IllegalArgumentException e) {
            problems.add(PraeceptaConfiguration.PREFIX + "." + e.getMessage());
        }
        boolean explicit = configuration.getLocations() != null;
        var patterns = explicit
                ? configuration.getLocations().stream().flatMap(location -> TableLocations.split(location).stream()).toList()
                : List.of(TableLocations.DEFAULT);
        if (patterns.isEmpty()) {
            problems.add(PraeceptaConfiguration.PREFIX + ".locations is set but names no location");
        }
        var sources = new TreeMap<String, TableSource>();
        for (var pattern : patterns) {
            try {
                var found = TableLocations.find(pattern, loader);
                if (found.isEmpty() && explicit) {
                    problems.add(pattern + ": no DMN file matches this location");
                }
                found.forEach(source -> sources.putIfAbsent(source.location(), source));
            } catch (IllegalArgumentException | UncheckedIOException e) {
                problems.add(e.getMessage());
            }
        }
        Decisions decisions = null;
        try {
            decisions = Decisions.load(sources.values(), failOn);
        } catch (DecisionsException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }
        if (decisions.isEmpty()) {
            LOG.info("No decision tables found at {}", String.join(", ", patterns));
        } else {
            LOG.info("Loaded {} decision(s) from {} DMN file(s): {}", decisions.names().size(), sources.size(),
                    String.join(", ", decisions.all().stream().map(Decision::name).toList()));
        }
        return decisions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<PraeceptaEngine<Map<String, String>, Map<String, String>>> engineType() {
        Argument text = Argument.mapOf(String.class, String.class);
        return (Argument) Argument.of(PraeceptaEngine.class, text, text);
    }
}
