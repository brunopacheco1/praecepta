/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.AnnotationMetadata;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.integration.TableSource;

/**
 * Loads the decision tables while the context registers its beans, so that each decision can become an engine
 * bean of its own, and fails the startup with every problem at once.
 */
final class DecisionsRegistrar implements ImportBeanDefinitionRegistrar {

    private static final Log LOG = LogFactory.getLog(PraeceptaAutoConfiguration.class);

    private static final ResolvableType MAP_OF_TEXT = ResolvableType.forClassWithGenerics(Map.class, String.class, String.class);

    /** {@code PraeceptaEngine<Map<String, String>, Map<String, String>>}, the type of every engine bean. */
    static final ResolvableType ENGINE_TYPE = ResolvableType.forClassWithGenerics(PraeceptaEngine.class, MAP_OF_TEXT, MAP_OF_TEXT);

    /** JSR-330's {@code @Named}, by name, so that the starter does not need {@code jakarta.inject}. */
    private static final String NAMED = "jakarta.inject.Named";

    private final Environment environment;
    private final ResourceLoader resourceLoader;

    DecisionsRegistrar(Environment environment, ResourceLoader resourceLoader) {
        this.environment = environment;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        var decisions = load(environment, ResourcePatternUtils.getResourcePatternResolver(resourceLoader));

        var decisionsBean = new RootBeanDefinition(Decisions.class, () -> decisions);
        decisionsBean.setLazyInit(false);
        registry.registerBeanDefinition(PraeceptaAutoConfiguration.DECISIONS_BEAN, decisionsBean);

        for (var decision : decisions.all()) {
            var engineBean = new RootBeanDefinition(PraeceptaEngine.class);
            engineBean.setTargetType(ENGINE_TYPE);
            engineBean.setInstanceSupplier(decision::engine);
            engineBean.addQualifier(new AutowireCandidateQualifier(Qualifier.class, decision.name()));
            // @Named("Discount") too, as in the Micronaut and Quarkus integrations (when jakarta.inject is present)
            engineBean.addQualifier(new AutowireCandidateQualifier(NAMED, decision.name()));
            engineBean.setLazyInit(false);
            engineBean.setDependsOn(PraeceptaAutoConfiguration.DECISIONS_BEAN);
            registry.registerBeanDefinition(PraeceptaAutoConfiguration.ENGINE_BEAN_PREFIX + decision.name(), engineBean);
        }
    }

    /**
     * Finds, reads, builds and validates the tables the {@code praecepta.*} settings name, and logs the validation
     * issues that do not stop the startup.
     *
     * @throws DecisionsException listing every problem: a malformed setting, a location set explicitly that
     *                            finds nothing, and whatever {@link Decisions#load} rejects
     */
    static Decisions load(Environment environment, ResourcePatternResolver resolver) {
        var binder = Binder.get(environment);
        var properties = binder.bind(PraeceptaProperties.PREFIX, PraeceptaProperties.class).orElseGet(PraeceptaProperties::new);
        boolean explicit = binder.bind(PraeceptaProperties.PREFIX + ".locations", Bindable.listOf(String.class)).isBound();

        var problems = new ArrayList<String>();
        var failOn = Severity.ERROR;
        try {
            failOn = Decisions.failOn(properties.getFailOn());
        } catch (IllegalArgumentException e) {
            problems.add(PraeceptaProperties.PREFIX + "." + e.getMessage());
        }
        var locations = properties.getLocations().stream().map(String::strip).filter(location -> !location.isEmpty()).toList();
        if (explicit && locations.isEmpty()) {
            problems.add(PraeceptaProperties.PREFIX + ".locations is set but names no location");
        }
        var sources = new TreeMap<String, TableSource>();
        for (var location : locations) {
            try {
                var found = find(location, resolver);
                if (found.isEmpty() && explicit) {
                    problems.add(location + ": no DMN file matches this location");
                }
                found.forEach(source -> sources.putIfAbsent(source.location(), source));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            } catch (IOException e) {
                problems.add("%s: cannot be searched (%s)".formatted(location, e.getMessage()));
            }
        }

        var decisions = Decisions.empty();
        try {
            decisions = Decisions.load(sources.values(), failOn);
        } catch (DecisionsException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }

        if (decisions.isEmpty()) {
            LOG.info("No decision tables found at " + String.join(", ", locations));
        } else {
            LOG.info("Loaded %d decision(s) from %d file(s): %s".formatted(decisions.names().size(), sources.size(), decisions.names()));
        }
        for (var decision : decisions.all()) {
            decision.report().issues().forEach(issue -> LOG.warn(decision.name() + ": " + issue));
        }
        return decisions;
    }

    /**
     * The DMN files one pattern finds, through Spring's resolver so that the nested jars of an executable jar are
     * searched too. The pattern is parsed as every integration parses it: {@code classpath:} becomes
     * {@code classpath*:}, as does a pattern without a prefix, and other prefixes are rejected.
     *
     * @throws IllegalArgumentException if the pattern is malformed
     */
    static List<TableSource> find(String pattern, ResourcePatternResolver resolver) throws IOException {
        var location = TableLocations.Location.parse(pattern);
        var resolvable = (location.classpath() ? ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX : "file:") + location.pattern();
        var found = new ArrayList<TableSource>();
        for (var resource : resolver.getResources(resolvable)) {
            if (resource.isReadable()) {
                found.add(new TableSource(location(resource), resource::getInputStream));
            }
        }
        return found;
    }

    private static String location(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }
}
