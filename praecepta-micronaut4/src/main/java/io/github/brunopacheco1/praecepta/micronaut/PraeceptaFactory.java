/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.DecisionsLoader;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
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
 * <li>for each decision table, its engine, a singleton of type
 * {@code PraeceptaEngine<Map<String, String>, Map<String, String>>} ({@link #ENGINE}) named after the
 * decision, injected with {@code @Named("Discount")}.</li>
 * </ul>
 *
 * The DMN files are those {@code praecepta.locations} finds with the application's class loader; every
 * decision table in them becomes one decision, and in a file whose decisions require other decisions, every
 * decision, evaluated through the file's decision requirements graph with {@link Decisions#evaluate} (a literal
 * expression has no engine bean; a table's engine evaluates it alone). A file that cannot be read, a table the engine cannot build,
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
     * Loads the decisions and registers an engine bean per decision table. Created first among the context-scoped
     * beans, so that every bean of the application can inject the engines.
     *
     * @throws DecisionsException listing every problem with the settings, the locations or the tables
     */
    @Context
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public Decisions decisions(PraeceptaConfiguration configuration, BeanContext context) {
        var decisions = load(configuration, context.getClassLoader());
        for (var decision : decisions.all()) {
            if (decision.engine() == null) {
                continue; // a literal expression of a decision model: evaluated through Decisions
            }
            context.registerBeanDefinition(RuntimeBeanDefinition.builder(ENGINE, decision::engine)
                    .named(decision.name())
                    .singleton(true)
                    .build());
        }
        return decisions;
    }

    /**
     * Finds and loads the configured tables with the application's class loader, collecting every problem before
     * failing and logging the validation issues below {@code praecepta.fail-on}, as every integration does.
     */
    static Decisions load(PraeceptaConfiguration configuration, ClassLoader loader) {
        return new DecisionsLoader(PraeceptaConfiguration.PREFIX, configuration.getLocations(), configuration.getFailOn())
                .load(location -> TableLocations.find(location, loader), new DecisionsLoader.Log() {
                    @Override
                    public void info(String message) {
                        LOG.info(message);
                    }

                    @Override
                    public void warn(String message) {
                        LOG.warn(message);
                    }
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<PraeceptaEngine<Map<String, String>, Map<String, String>>> engineType() {
        Argument text = Argument.mapOf(String.class, String.class);
        return (Argument) Argument.of(PraeceptaEngine.class, text, text);
    }
}
