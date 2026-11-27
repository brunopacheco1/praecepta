/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.util.function.Predicate;

import org.springframework.aot.generate.GenerationContext;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.aot.BeanRegistrationCodeFragments;
import org.springframework.beans.factory.aot.BeanRegistrationCodeFragmentsDecorator;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.InstanceSupplier;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.javapoet.ClassName;
import org.springframework.javapoet.CodeBlock;

/**
 * Lets Spring AOT generate the beans {@link DecisionsRegistrar} registers, which it could not otherwise: their
 * instances come from the tables the registrar loaded, so their definitions have instance suppliers, which AOT
 * cannot write as code. The generated definitions create them with {@link DecisionsRegistrar#decisions} and
 * {@link DecisionsRegistrar#engine} instead, and keep everything else (names, types, both qualifiers, eager creation).
 * Registered in {@code META-INF/spring/aot.factories}.
 */
final class DecisionsAotProcessor implements BeanRegistrationAotProcessor {

    @Override
    public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean bean) {
        if (!(bean.getMergedBeanDefinition().getAttribute(DecisionsRegistrar.DECISION_ATTRIBUTE) instanceof String decision)) {
            return null;
        }
        return BeanRegistrationAotContribution.withCustomCodeFragments(fragments -> new Fragments(fragments, decision));
    }

    /** @param decision the engine bean's decision, or the empty string for the {@code Decisions} bean */
    private static final class Fragments extends BeanRegistrationCodeFragmentsDecorator {

        private final String decision;

        Fragments(BeanRegistrationCodeFragments delegate, String decision) {
            super(delegate);
            this.decision = decision;
        }

        /** The generated code goes beside the registrar, whose methods it calls. */
        @Override
        public ClassName getTarget(RegisteredBean bean) {
            return ClassName.get(DecisionsRegistrar.class);
        }

        /**
         * Spring's code, then the qualifiers the registrar gave an engine written again. Spring writes a definition's
         * qualifiers from {@link RootBeanDefinition#getQualifiers()}, a set in which qualifiers with the same value
         * are one whatever their types; so of an engine's {@code @Qualifier("Carrier")} and {@code @Named("Carrier")}
         * only one reached the generated code, and injecting with the other failed once the application was
         * processed ahead of time. A qualifier added with a type the definition already has replaces it, so the one
         * Spring did write is not doubled.
         */
        @Override
        public CodeBlock generateSetBeanDefinitionPropertiesCode(GenerationContext context, BeanRegistrationCode code,
                RootBeanDefinition definition, Predicate<String> attributeFilter) {
            var properties = CodeBlock.builder().add(super.generateSetBeanDefinitionPropertiesCode(context, code, definition, attributeFilter));
            for (var type : DecisionsRegistrar.QUALIFIER_TYPES) {
                var qualifier = definition.getQualifier(type);
                if (qualifier != null) {
                    properties.addStatement("$L.addQualifier(new $T($S, $S))", BEAN_DEFINITION_VARIABLE, AutowireCandidateQualifier.class,
                            type, qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY));
                }
            }
            return properties.build();
        }

        @Override
        public CodeBlock generateInstanceSupplierCode(GenerationContext context, BeanRegistrationCode code, boolean allowDirectSupplierShortcut) {
            if (decision.isEmpty()) {
                return CodeBlock.of("$T.of($T::decisions)", InstanceSupplier.class, DecisionsRegistrar.class);
            }
            return CodeBlock.of("$T.of(bean -> $T.engine(bean, $S))", InstanceSupplier.class, DecisionsRegistrar.class, decision);
        }
    }
}
