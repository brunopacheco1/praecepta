/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.util.Map;

import jakarta.inject.Named;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.mock.env.MockEnvironment;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;

/**
 * Spring AOT, which a native image and the JVM's AOT mode need: the auto-configuration is processed ahead of time,
 * the generated code is compiled, and an application started from it has the same beans.
 */
@CompileWithForkedClassLoader
class PraeceptaAotTest {

    @Test
    void the_beans_are_generated_ahead_of_time_and_load_the_tables_when_the_application_starts() {
        var generationContext = new TestGenerationContext();
        var initializer = new ApplicationContextAotGenerator().processAheadOfTime(context(new MockEnvironment()), generationContext);
        generationContext.writeGeneratedContent();

        TestCompiler.forSystem().with(generationContext).compile(compiled -> {
            var application = start(compiled.getInstance(ApplicationContextInitializer.class, initializer.toString()), new MockEnvironment());
            var decisions = application.getBean(PraeceptaAutoConfiguration.DECISIONS_BEAN, Decisions.class);
            Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");
            Assertions.assertThat(application.getBeanNamesForType(DecisionsRegistrar.ENGINE_TYPE))
                    .containsExactlyInAnyOrder("praecepta.Carrier", "praecepta.Discount", "praecepta.Fee");
            var carrier = BeanFactoryAnnotationUtils.qualifiedBeanOfType(application.getBeanFactory(), PraeceptaEngine.class, "Carrier");
            Assertions.assertThat(carrier).isSameAs(decisions.engine("Carrier"));
            @SuppressWarnings("unchecked")
            PraeceptaEngine<Map<String, String>, Map<String, String>> engine = carrier;
            Assertions.assertThat(engine.evaluate(Map.of("weight", "30"))).containsExactly(Map.of("Carrier", "freight"));
            Assertions.assertThat(application.getBeanFactory().getBeanDefinition("praecepta.Fee").isLazyInit()).isFalse();
            application.close();

            // the tables are read again when the application starts, with its settings then
            var broken = new MockEnvironment().withProperty("praecepta.fail-on", "sometimes");
            Assertions.assertThatThrownBy(() -> start(compiled.getInstance(ApplicationContextInitializer.class, initializer.toString()), broken))
                    .hasRootCauseInstanceOf(DecisionsException.class)
                    .rootCause().hasMessageContaining("praecepta.fail-on must be ERROR, WARNING or NONE, not sometimes");
        });
    }

    @Test
    void engines_processed_ahead_of_time_are_injected_with_qualifier_and_with_named() {
        var generationContext = new TestGenerationContext();
        var context = context(new MockEnvironment());
        context.registerBean(Shipping.class);
        var initializer = new ApplicationContextAotGenerator().processAheadOfTime(context, generationContext);
        generationContext.writeGeneratedContent();

        TestCompiler.forSystem().with(generationContext).compile(compiled -> {
            var application = start(compiled.getInstance(ApplicationContextInitializer.class, initializer.toString()), new MockEnvironment());
            var decisions = application.getBean(PraeceptaAutoConfiguration.DECISIONS_BEAN, Decisions.class);
            var shipping = application.getBean(Shipping.class);
            Assertions.assertThat(shipping.carrier).isSameAs(decisions.engine("Carrier"));
            Assertions.assertThat(shipping.fee).isSameAs(decisions.engine("Fee"));
            var definition = (AbstractBeanDefinition) application.getBeanFactory().getBeanDefinition("praecepta.Discount");
            Assertions.assertThat(DecisionsRegistrar.QUALIFIER_TYPES)
                    .allSatisfy(type -> Assertions.assertThat(definition.getQualifier(type).getAttribute("value")).isEqualTo("Discount"));
            application.close();
        });
    }

    /** An application bean injecting engines the two ways the starter supports. */
    static class Shipping {

        final PraeceptaEngine<Map<String, String>, Map<String, String>> carrier;
        final PraeceptaEngine<Map<String, String>, Map<String, String>> fee;

        Shipping(@Named("Carrier") PraeceptaEngine<Map<String, String>, Map<String, String>> carrier,
                @Qualifier("Fee") PraeceptaEngine<Map<String, String>, Map<String, String>> fee) {
            this.carrier = carrier;
            this.fee = fee;
        }
    }

    private static GenericApplicationContext context(MockEnvironment environment) {
        var context = new GenericApplicationContext();
        context.setEnvironment(environment);
        AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
        context.registerBean(PraeceptaAutoConfiguration.class);
        return context;
    }

    @SuppressWarnings("unchecked")
    private static GenericApplicationContext start(ApplicationContextInitializer<?> initializer, MockEnvironment environment) {
        var application = new GenericApplicationContext();
        application.setEnvironment(environment);
        ((ApplicationContextInitializer<GenericApplicationContext>) initializer).initialize(application);
        application.refresh();
        return application;
    }
}
