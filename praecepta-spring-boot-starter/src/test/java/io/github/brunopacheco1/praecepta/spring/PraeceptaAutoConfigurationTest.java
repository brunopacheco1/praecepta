/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.jayway.jsonpath.JsonPath;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import jakarta.inject.Named;

@ExtendWith(OutputCaptureExtension.class)
class PraeceptaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PraeceptaAutoConfiguration.class));

    @Test
    void every_decision_found_at_the_default_locations_gets_an_engine_bean_named_and_qualified_by_its_name() {
        // as spring.main.lazy-initialization=true does: the beans stay eager all the same
        runner.withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor())).run(context -> {
            Assertions.assertThat(context).hasNotFailed().hasSingleBean(Decisions.class);
            var decisions = context.getBean(PraeceptaAutoConfiguration.DECISIONS_BEAN, Decisions.class);
            Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");

            Assertions.assertThat(context.getBeanNamesForType(DecisionsRegistrar.ENGINE_TYPE))
                    .containsExactlyInAnyOrder("praecepta.Carrier", "praecepta.Discount", "praecepta.Fee");
            Assertions.assertThat(context.getBean("praecepta.Discount")).isSameAs(decisions.engine("Discount"));
            Assertions.assertThat(BeanFactoryAnnotationUtils.qualifiedBeanOfType(context.getBeanFactory(), PraeceptaEngine.class, "Carrier"))
                    .isSameAs(decisions.engine("Carrier"));
            for (var name : List.of(PraeceptaAutoConfiguration.DECISIONS_BEAN, "praecepta.Carrier", "praecepta.Discount", "praecepta.Fee")) {
                Assertions.assertThat(context.getBeanFactory().getBeanDefinition(name).isLazyInit()).as(name).isFalse();
            }
        });
    }

    @Test
    void engines_are_injected_with_the_decision_name_as_qualifier_and_their_generic_type() {
        runner.withUserConfiguration(PricingConfiguration.class).run(context -> {
            Assertions.assertThat(context).hasNotFailed();
            var pricing = context.getBean(Pricing.class);
            Assertions.assertThat(pricing.discount().evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")))
                    .containsExactly(Map.of("discount", "0.15", "tier", "gold"), Map.of("discount", "0.05", "tier", "silver"));
            Assertions.assertThat(pricing.carrier().evaluate(Map.of("weight", "30"))).containsExactly(Map.of("Carrier", "freight"));
            // an engine of other types matches none of them
            Assertions.assertThat(pricing.typed().getIfAvailable()).isNull();
        });
    }

    @Test
    void praecepta_enabled_false_registers_nothing() {
        runner.withPropertyValues("praecepta.enabled=false", "praecepta.locations=classpath:nowhere/*.dmn").run(context -> {
            Assertions.assertThat(context).hasNotFailed().doesNotHaveBean(Decisions.class).doesNotHaveBean(PraeceptaProperties.class);
            Assertions.assertThat(context.getBeanNamesForType(PraeceptaEngine.class)).isEmpty();
        });
    }

    @Test
    void locations_are_a_list_of_class_path_and_file_patterns(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("grade.dmn"), read("tables/unreachable.dmn"));
        var file = "file:" + directory.toString().replace('\\', '/') + "/*.dmn";
        runner.withPropertyValues("praecepta.locations=classpath:rules/shipping/*.dmn, " + file).run(context -> {
            Assertions.assertThat(context).hasNotFailed();
            var decisions = context.getBean(Decisions.class);
            Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Fee", "Grade");
            Assertions.assertThat(decisions.get("Grade").location()).startsWith("file:").endsWith("/grade.dmn");
            Assertions.assertThat(decisions.get("Fee").location()).endsWith("rules/shipping/shipping.dmn");
        });
    }

    @Test
    void a_location_without_prefix_or_wildcards_names_one_class_path_file() {
        runner.withPropertyValues("praecepta.locations=rules/discount.dmn").run(context -> {
            Assertions.assertThat(context).hasNotFailed();
            Assertions.assertThat(context.getBean(Decisions.class).names()).containsExactly("Discount");
        });
    }

    @Test
    void tables_are_found_inside_jars(@TempDir Path directory) throws IOException {
        var jar = directory.resolve("tables.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            // directory entries, as build tools write them: class loaders find directories in jars only through these
            out.putNextEntry(new JarEntry("jarred/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("jarred/grades/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("jarred/grades/grade table.dmn"));
            out.write(read("tables/unreachable.dmn").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, getClass().getClassLoader())) {
            runner.withClassLoader(loader).withPropertyValues("praecepta.locations=classpath:jarred/**/*.dmn").run(context -> {
                Assertions.assertThat(context).hasNotFailed();
                var decisions = context.getBean(Decisions.class);
                Assertions.assertThat(decisions.names()).containsExactly("Grade");
                Assertions.assertThat(decisions.get("Grade").location()).startsWith("jar:file:").endsWith("!/jarred/grades/grade table.dmn");
                Assertions.assertThat(decisions.evaluate("Grade", Map.of("score", "7"))).containsExactly(Map.of("grade", "A"));
            });
        }
    }

    @Test
    void default_locations_that_find_nothing_give_no_decisions(CapturedOutput output) {
        runner.withClassLoader(new FilteredClassLoader(new ClassPathResource("rules/"), new ClassPathResource("rules")))
                .run(context -> {
                    Assertions.assertThat(context).hasNotFailed();
                    Assertions.assertThat(context.getBean(Decisions.class).isEmpty()).isTrue();
                    Assertions.assertThat(context.getBeanNamesForType(PraeceptaEngine.class)).isEmpty();
                });
        Assertions.assertThat(output).contains("No decision tables found at classpath*:rules/**/*.dmn");
    }

    @Test
    void a_location_set_explicitly_that_finds_nothing_stops_startup_naming_it() {
        runner.withPropertyValues("praecepta.locations=classpath:rules/*.dmn,classpath:nowhere/*.dmn,file:/nowhere/rules.dmn")
                .run(context -> Assertions.assertThat(problems(context))
                        .containsExactly("classpath:nowhere/*.dmn: no DMN file matches this location",
                                "file:/nowhere/rules.dmn: no DMN file matches this location"));
    }

    @Test
    void locations_set_to_nothing_stop_startup() {
        runner.withPropertyValues("praecepta.locations= , ")
                .run(context -> Assertions.assertThat(problems(context))
                        .containsExactly("praecepta.locations is set but names no location"));
    }

    @Test
    void engines_are_injected_with_named_as_in_the_other_integrations() {
        runner.withUserConfiguration(NamedPricingConfiguration.class).run(context -> {
            Assertions.assertThat(context).hasNotFailed();
            var pricing = context.getBean(Pricing.class);
            var decisions = context.getBean(Decisions.class);
            Assertions.assertThat(pricing.discount()).isSameAs(decisions.engine("Discount"));
            Assertions.assertThat(pricing.carrier()).isSameAs(decisions.engine("Carrier"));
        });
    }

    @Test
    void every_problem_is_listed_at_once() {
        runner.withPropertyValues("praecepta.locations=classpath:tables/*.dmn,classpath:rules/discount.dmn,http://example.com/rules.dmn",
                "praecepta.fail-on=sometimes").run(context -> {
                    var problems = problems(context);
                    Assertions.assertThat(problems).hasSize(4);
                    Assertions.assertThat(problems.get(0)).isEqualTo("praecepta.fail-on must be ERROR, WARNING or NONE, not sometimes");
                    Assertions.assertThat(problems.get(1)).isEqualTo("Location 'http://example.com/rules.dmn': expected classpath:, classpath*: or file:");
                    Assertions.assertThat(problems.get(2)).endsWith("tables/no-table.dmn: no decision table");
                    Assertions.assertThat(problems.get(3)).contains("tables/overlapping.dmn: decision 'Tier' has validation issues at ERROR or above")
                            .contains("ERROR rules [1, 2] both match some inputs (UNIQUE)");
                });
    }

    @Test
    void validation_issues_below_fail_on_are_logged_as_warnings_prefixed_with_the_decision_name(CapturedOutput output) {
        runner.withPropertyValues("praecepta.locations=classpath:tables/overlapping.dmn,classpath:tables/unreachable.dmn",
                "praecepta.fail-on=none").run(context -> {
                    Assertions.assertThat(context).hasNotFailed();
                    Assertions.assertThat(context.getBean(Decisions.class).names()).containsExactly("Grade", "Tier");
                });
        Assertions.assertThat(output)
                .contains("WARN  PraeceptaAutoConfiguration - Tier: ERROR rules [1, 2] both match some inputs (UNIQUE)")
                .contains("WARN  PraeceptaAutoConfiguration - Grade: WARNING rule 2 can never be selected")
                .contains("Loaded 2 decision(s) from 2 DMN file(s): Grade, Tier");
    }

    @Test
    void fail_on_sets_the_severity_that_stops_startup() {
        var unreachable = "praecepta.locations=classpath:tables/unreachable.dmn";
        runner.withPropertyValues(unreachable).run(context -> Assertions.assertThat(context).hasNotFailed());
        runner.withPropertyValues(unreachable, "praecepta.fail-on=Warning").run(context -> Assertions.assertThat(problems(context))
                .singleElement().asString().contains("decision 'Grade' has validation issues at WARNING or above"));
    }

    /**
     * A file whose decisions require other decisions (#14): every decision evaluated from the model's input data
     * through the Decisions bean, an engine bean per decision table, and the graph described by the endpoint.
     */
    @Test
    void a_decision_requirements_graph_is_evaluated_through_the_decisions_bean_and_described_by_the_endpoint() {
        runner.withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class))
                .withPropertyValues("praecepta.locations=classpath:graphs/*.dmn", "management.endpoints.web.exposure.include=praecepta")
                .run(context -> {
                    Assertions.assertThat(context).hasNotFailed();
                    var decisions = context.getBean(Decisions.class);
                    Assertions.assertThat(decisions.names()).containsExactly("Credit Limit", "Monthly Limit", "Risk Category");
                    Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "30", "Income", "1200")))
                            .containsExactly(Map.of("Monthly Limit", "200"));
                    // a table's engine evaluates it alone; the literal expression has no engine
                    Assertions.assertThat(context.getBeanNamesForType(DecisionsRegistrar.ENGINE_TYPE))
                            .containsExactlyInAnyOrder("praecepta.Credit Limit", "praecepta.Risk Category");
                    var limit = BeanFactoryAnnotationUtils.qualifiedBeanOfType(context.getBeanFactory(), PraeceptaEngine.class, "Credit Limit");
                    Assertions.assertThat(limit).isSameAs(decisions.engine("Credit Limit"));

                    var endpoint = context.getBean(PraeceptaEndpoint.class);
                    Assertions.assertThat(endpoint.decisions()).containsKeys("decisions", "models");
                    Assertions.assertThat(endpoint.decision("Monthly Limit"))
                            .containsEntry("model", "Credit")
                            .containsEntry("logic", "literalExpression")
                            .containsEntry("inputData", List.of("Applicant Age", "Income"));
                });
        runner.withPropertyValues("praecepta.locations=classpath:graphs/*.dmn", "praecepta.fail-on=WARNING").run(context ->
                Assertions.assertThat(context).hasNotFailed());
    }

    @Test
    void the_actuator_endpoint_reports_the_decisions_when_exposed() {
        var withActuator = runner.withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class));
        withActuator.withPropertyValues("management.endpoints.web.exposure.include=praecepta").run(context -> {
            Assertions.assertThat(context).hasNotFailed().hasSingleBean(PraeceptaEndpoint.class);
            var endpoint = context.getBean(PraeceptaEndpoint.class);
            @SuppressWarnings("unchecked")
            var described = (Map<String, Object>) endpoint.decisions().get("decisions");
            Assertions.assertThat(described).containsOnlyKeys("Carrier", "Discount", "Fee");
            Assertions.assertThat(endpoint.decision("Fee")).containsEntry("hitPolicy", "COLLECT").containsEntry("aggregation", "SUM");
            Assertions.assertThat(endpoint.decision("Nope")).isNull();
        });
        // not exposed: the endpoint is not available, so no bean
        withActuator.run(context -> Assertions.assertThat(context).hasNotFailed().doesNotHaveBean(PraeceptaEndpoint.class));
        // no Actuator: the decisions alone
        runner.withPropertyValues("management.endpoints.web.exposure.include=*")
                .withClassLoader(new FilteredClassLoader(Endpoint.class))
                .run(context -> Assertions.assertThat(context).hasNotFailed()
                        .hasSingleBean(Decisions.class).doesNotHaveBean(PraeceptaEndpoint.class));
    }

    @Test
    void the_configuration_metadata_documents_every_key() throws IOException {
        // written by spring-boot-configuration-processor, with additional-spring-configuration-metadata.json merged in
        var metadata = Files.readString(Path.of("target/classes/META-INF/spring-configuration-metadata.json"));
        List<Object> defaults = JsonPath.read(metadata, "$.properties[?(@.name =~ /praecepta\\..*/)].defaultValue");
        Assertions.assertThat(defaults).containsExactlyInAnyOrder(true, List.of(TableLocations.DEFAULT), "ERROR");
        List<String> failOn = JsonPath.read(metadata, "$.hints[?(@.name == 'praecepta.fail-on')].values[*].value");
        Assertions.assertThat(failOn).containsExactly("ERROR", "WARNING", "NONE");
    }

    private static List<String> problems(AssertableApplicationContext context) {
        Assertions.assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        while (failure != null && !(failure instanceof DecisionsException)) {
            failure = failure.getCause();
        }
        Assertions.assertThat(failure).as("a DecisionsException in %s", context.getStartupFailure()).isNotNull();
        return ((DecisionsException) failure).problems();
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** An application's bean that has engines injected. */
    record Pricing(PraeceptaEngine<Map<String, String>, Map<String, String>> discount,
            PraeceptaEngine<Map<String, String>, Map<String, String>> carrier, ObjectProvider<PraeceptaEngine<String, String>> typed) {
    }

    @Configuration(proxyBeanMethods = false)
    static class PricingConfiguration {

        @Bean
        Pricing pricing(@Qualifier("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount,
                @Qualifier("Carrier") PraeceptaEngine<Map<String, String>, Map<String, String>> carrier,
                ObjectProvider<PraeceptaEngine<String, String>> typed) {
            return new Pricing(discount, carrier, typed);
        }
    }

    /** The same beans injected with JSR-330's {@code @Named}, as the Micronaut and Quarkus integrations inject them. */
    @Configuration(proxyBeanMethods = false)
    static class NamedPricingConfiguration {

        @Bean
        Pricing pricing(@Named("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount,
                @Named("Carrier") PraeceptaEngine<Map<String, String>, Map<String, String>> carrier,
                ObjectProvider<PraeceptaEngine<String, String>> typed) {
            return new Pricing(discount, carrier, typed);
        }
    }
}
