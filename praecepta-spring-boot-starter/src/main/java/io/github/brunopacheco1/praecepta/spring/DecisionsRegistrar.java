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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.AnnotationMetadata;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.DecisionsLoader;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.integration.TableSource;

/**
 * Loads the decision tables while the context registers its beans, so that each decision can become an engine
 * bean of its own, and fails the startup with every problem at once.
 *
 * <p>Processed ahead of time (Spring AOT, for a native image or the JVM's AOT mode), the registrar runs while the
 * application is built, which validates the tables then and fixes the engine beans to the decisions found then.
 * The generated code does not run it again; it creates the beans with {@link #decisions(RegisteredBean)} and
 * {@link #engine(RegisteredBean, String)} instead ({@link DecisionsAotProcessor}), which load the tables when the
 * application starts.
 */
final class DecisionsRegistrar implements ImportBeanDefinitionRegistrar {

    // logs as the public auto-configuration, whose name users set log levels by; this class is internal
    @SuppressWarnings("java:S3416")
    private static final Log LOG = LogFactory.getLog(PraeceptaAutoConfiguration.class);

    private static final ResolvableType MAP_OF_TEXT = ResolvableType.forClassWithGenerics(Map.class, String.class, String.class);

    /** {@code PraeceptaEngine<Map<String, String>, Map<String, String>>}, the type of every engine bean. */
    static final ResolvableType ENGINE_TYPE = ResolvableType.forClassWithGenerics(PraeceptaEngine.class, MAP_OF_TEXT, MAP_OF_TEXT);

    /**
     * The attribute that marks the bean definitions the registrar adds, for {@link DecisionsAotProcessor}: the
     * decision's name on an engine bean, and the empty string on the {@code Decisions} bean.
     */
    static final String DECISION_ATTRIBUTE = DecisionsRegistrar.class.getName() + ".decision";

    /** JSR-330's {@code @Named}, by name, so that the starter does not need {@code jakarta.inject}. */
    private static final String NAMED = "jakarta.inject.Named";

    /** The types of the qualifiers every engine bean has, each with the decision's name as its value. */
    static final List<String> QUALIFIER_TYPES = List.of(Qualifier.class.getName(), NAMED);

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
        decisionsBean.setAttribute(DECISION_ATTRIBUTE, "");
        registry.registerBeanDefinition(PraeceptaAutoConfiguration.DECISIONS_BEAN, decisionsBean);

        for (var decision : decisions.all()) {
            var engineBean = new RootBeanDefinition(PraeceptaEngine.class);
            engineBean.setTargetType(ENGINE_TYPE);
            engineBean.setInstanceSupplier(decision::engine);
            // @Qualifier("Discount"), and @Named("Discount") as in the Micronaut and Quarkus integrations (when
            // jakarta.inject is present)
            QUALIFIER_TYPES.forEach(type -> engineBean.addQualifier(new AutowireCandidateQualifier(type, decision.name())));
            engineBean.setLazyInit(false);
            engineBean.setDependsOn(PraeceptaAutoConfiguration.DECISIONS_BEAN);
            engineBean.setAttribute(DECISION_ATTRIBUTE, decision.name());
            registry.registerBeanDefinition(PraeceptaAutoConfiguration.ENGINE_BEAN_PREFIX + decision.name(), engineBean);
        }
    }

    /**
     * The {@code Decisions} bean of an application processed ahead of time: the tables, loaded when it starts with
     * the application's settings, as {@link #load} loads them.
     */
    static Decisions decisions(RegisteredBean bean) {
        var factory = bean.getBeanFactory();
        var environment = factory.getBean(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, Environment.class);
        return load(environment, new PathMatchingResourcePatternResolver(factory.getBeanClassLoader()));
    }

    /**
     * An engine bean of an application processed ahead of time: the engine of the decision of that name.
     *
     * @throws IllegalStateException if the decision was found while the application was built, but not when it
     *                               started
     */
    static PraeceptaEngine<Map<String, String>, Map<String, String>> engine(RegisteredBean bean, String name) {
        var decisions = bean.getBeanFactory().getBean(PraeceptaAutoConfiguration.DECISIONS_BEAN, Decisions.class);
        return decisions.find(name).orElseThrow(() -> new IllegalStateException(
                "Decision '%s' was found when the application was processed ahead of time, but not when it started; there are %s"
                        .formatted(name, decisions.names()))).engine();
    }

    /**
     * Finds, reads, builds and validates the tables the {@code praecepta.*} settings name, and logs the validation
     * issues that do not stop the startup, as every integration does ({@link DecisionsLoader}).
     *
     * @throws DecisionsException listing every problem: a malformed setting, a location set explicitly that
     *                            finds nothing, and whatever {@link Decisions#load} rejects
     */
    static Decisions load(Environment environment, ResourcePatternResolver resolver) {
        var binder = Binder.get(environment);
        var properties = binder.bind(PraeceptaProperties.PREFIX, PraeceptaProperties.class).orElseGet(PraeceptaProperties::new);
        boolean explicit = binder.bind(PraeceptaProperties.PREFIX + ".locations", Bindable.listOf(String.class)).isBound();
        return new DecisionsLoader(PraeceptaProperties.PREFIX, explicit ? properties.getLocations() : null, properties.getFailOn())
                .load(location -> find(location, resolver), new DecisionsLoader.Log() {
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

    /**
     * The DMN files one pattern finds, through Spring's resolver so that the nested jars of an executable jar are
     * searched too. The pattern is parsed as every integration parses it: {@code classpath:} becomes
     * {@code classpath*:}, as does a pattern without a prefix, and other prefixes are rejected.
     */
    static List<TableSource> find(TableLocations.Location location, ResourcePatternResolver resolver) throws IOException {
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
