/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;

class PraeceptaFactoryTest {

    /** A UNIQUE table whose two rules overlap on "PT": an ERROR of the validation report. */
    private static final String OVERLAPPING = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="tier" name="Tier"><decisionTable hitPolicy="UNIQUE">
                <input><inputExpression typeRef="string"><text>country</text></inputExpression></input>
                <output name="tier" typeRef="string"/>
                <rule><inputEntry><text>"PT"</text></inputEntry><outputEntry><text>"gold"</text></outputEntry></rule>
                <rule><inputEntry><text>"PT","ES"</text></inputEntry><outputEntry><text>"silver"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /** A FIRST table whose second rule can never be selected: a WARNING. */
    private static final String UNREACHABLE = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
              <decision id="grade" name="Grade"><decisionTable hitPolicy="FIRST">
                <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                <output name="grade" typeRef="string"/>
                <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"A"</text></outputEntry></rule>
                <rule><inputEntry><text>&gt; 5</text></inputEntry><outputEntry><text>"B"</text></outputEntry></rule>
              </decisionTable></decision>
            </definitions>""";

    /** A location like the default one, under a directory the test class path does not have. */
    private static final String ELSEWHERE = "classpath*:no-rules/**/*.dmn";

    @TempDir
    Path directory;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PraeceptaFactory.class);

    @BeforeEach
    void capture_the_log() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void release_the_log() {
        logger.detachAppender(log);
    }

    @Test
    void loads_every_decision_table_the_default_locations_find() {
        try (var context = ApplicationContext.run()) {
            var decisions = context.getBean(Decisions.class);

            Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");
            Assertions.assertThat(decisions.get("Carrier").location()).endsWith("rules/shipping/shipping.dmn");
            Assertions.assertThat(decisions.evaluate("Fee", Map.of("zone", "islands"))).containsExactly(Map.of("fee", "17.5"));
            Assertions.assertThat(messages(Level.INFO)).containsExactly("Loaded 3 decision(s) from 2 DMN file(s): Carrier, Discount, Fee");
        }
    }

    @Test
    void registers_each_decision_as_an_engine_bean_named_after_it() {
        try (var context = ApplicationContext.run()) {
            var decisions = context.getBean(Decisions.class);

            for (var name : decisions.names()) {
                Assertions.assertThat(context.getBean(PraeceptaFactory.ENGINE, Qualifiers.byName(name))).isSameAs(decisions.engine(name));
            }
            Assertions.assertThat(context.getBeansOfType(PraeceptaFactory.ENGINE)).hasSize(3);
            var carrier = context.getBean(PraeceptaFactory.ENGINE, Qualifiers.byName("Carrier"));
            Assertions.assertThat(carrier.evaluate(Map.of("weight", "30"))).containsExactly(Map.of("Carrier", "freight"));
        }
    }

    /**
     * A file whose decisions require other decisions (#14): every decision evaluated from the model's input data
     * through the Decisions bean, and an engine bean per decision table, which evaluates it alone.
     */
    @Test
    void a_decision_requirements_graph_is_evaluated_through_the_decisions_bean() {
        try (var context = ApplicationContext.run(Map.of("praecepta.locations", "classpath:graphs/*.dmn"))) {
            var decisions = context.getBean(Decisions.class);

            Assertions.assertThat(decisions.names()).containsExactly("Credit Limit", "Monthly Limit", "Risk Category");
            Assertions.assertThat(decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "20", "Income", "1200")))
                    .containsExactly(Map.of("Monthly Limit", "50"));
            Assertions.assertThat(context.getBeansOfType(PraeceptaFactory.ENGINE)).hasSize(2);
            var limit = context.getBean(PraeceptaFactory.ENGINE, Qualifiers.byName("Credit Limit"));
            Assertions.assertThat(limit.evaluate(Map.of("Risk Category", "High", "Income", "1200"))).containsExactly(Map.of("Credit Limit", "600"));
            Assertions.assertThat(context.findBean(PraeceptaFactory.ENGINE, Qualifiers.byName("Monthly Limit"))).isEmpty();
            Assertions.assertThat(decisions.describe()).containsKeys("decisions", "models");
        }
    }

    @Test
    void application_beans_inject_the_engines_by_name() {
        try (var context = ApplicationContext.run(Map.of("praecepta-test.pricing", true))) {
            var pricing = context.getBean(Pricing.class);

            Assertions.assertThat(pricing.tiers("PT", 30, LocalDate.of(2019, 1, 1))).containsExactly("gold", "silver");
            Assertions.assertThat(pricing.tiers("PT", 30, LocalDate.of(2024, 1, 1))).containsExactly("silver");
            Assertions.assertThat(pricing.tiers("FR", 30, LocalDate.of(2019, 1, 1))).isEmpty();
        }
    }

    @Test
    void beans_the_application_creates_with_the_context_inject_the_engines_too() {
        try (var context = ApplicationContext.run(Map.of("praecepta-test.eager", true))) {
            Assertions.assertThat(context.getBean(EagerShipping.class).lightCarrier()).isEqualTo("post");
        }
    }

    @Test
    void locations_are_a_comma_separated_list_or_a_list_of_patterns() throws IOException {
        Files.writeString(directory.resolve("grade.dmn"), UNREACHABLE);
        var files = "file:" + slashes(directory) + "/*.dmn";

        try (var context = ApplicationContext.run(Map.of("praecepta.locations", files + ", classpath:rules/shipping/*.dmn"))) {
            Assertions.assertThat(context.getBean(Decisions.class).names()).containsExactly("Carrier", "Fee", "Grade");
        }
        try (var context = ApplicationContext.run(Map.of("praecepta.locations", List.of(files, "classpath:rules/discount.dmn")))) {
            Assertions.assertThat(context.getBean(Decisions.class).names()).containsExactly("Discount", "Grade");
        }
    }

    @Test
    void issues_below_fail_on_are_logged_as_warnings_prefixed_with_the_decision() throws IOException {
        Files.writeString(directory.resolve("grade.dmn"), UNREACHABLE);

        try (var context = ApplicationContext.run(Map.of("praecepta.locations", "file:" + slashes(directory) + "/*.dmn"))) {
            Assertions.assertThat(context.getBean(Decisions.class).get("Grade").report().issues()).hasSize(1);
            Assertions.assertThat(messages(Level.WARN)).containsExactly("Grade: WARNING rule 2 can never be selected");
        }
    }

    @Test
    void validation_issues_at_fail_on_stop_startup() throws IOException {
        Files.writeString(directory.resolve("grade.dmn"), UNREACHABLE);
        var properties = Map.<String, Object>of("praecepta.locations", "file:" + slashes(directory) + "/*.dmn", "praecepta.fail-on", "warning");

        Assertions.assertThat(startupFailure(properties).problems()).singleElement().asString()
                .startsWith(directory.resolve("grade.dmn") + ": decision 'Grade' has validation issues at WARNING or above")
                .contains("WARNING rule 2 can never be selected");
    }

    @Test
    void every_problem_is_reported_at_once() throws IOException {
        Files.writeString(directory.resolve("broken.dmn"), "<html/>");
        Files.writeString(directory.resolve("tier.dmn"), OVERLAPPING);
        Files.writeString(directory.resolve("tier-again.dmn"), OVERLAPPING);
        var properties = Map.<String, Object>of("praecepta.locations", List.of(
                "file:" + slashes(directory) + "/*.dmn", "classpath:rules/missing/*.dmn", "http://example.com/rules.dmn"));

        var problems = startupFailure(properties).problems();

        Assertions.assertThat(problems).hasSize(5);
        Assertions.assertThat(problems.get(0)).isEqualTo("classpath:rules/missing/*.dmn: no DMN file matches this location");
        Assertions.assertThat(problems.get(1)).isEqualTo("Location 'http://example.com/rules.dmn': expected classpath:, classpath*: or file:");
        Assertions.assertThat(problems.get(2)).startsWith(directory.resolve("broken.dmn") + ": ");
        Assertions.assertThat(problems.get(3)).startsWith(directory.resolve("tier-again.dmn") + ": decision 'Tier' has validation issues at ERROR");
        Assertions.assertThat(problems.get(4))
                .isEqualTo("%s: decision 'Tier' is also defined in %s", directory.resolve("tier.dmn"), directory.resolve("tier-again.dmn"));
    }

    @Test
    void fail_on_none_accepts_validation_errors_but_not_unreadable_tables() throws IOException {
        Files.writeString(directory.resolve("tier.dmn"), OVERLAPPING);
        var locations = "file:" + slashes(directory) + "/*.dmn";

        try (var context = ApplicationContext.run(Map.of("praecepta.locations", locations, "praecepta.fail-on", "NONE"))) {
            Assertions.assertThat(context.getBean(Decisions.class).names()).containsExactly("Tier");
            Assertions.assertThat(messages(Level.WARN)).containsExactly("Tier: ERROR rules [1, 2] both match some inputs (UNIQUE)");
        }
        Files.writeString(directory.resolve("broken.dmn"), "<html/>");
        Assertions.assertThat(startupFailure(Map.of("praecepta.locations", locations, "praecepta.fail-on", "NONE")).problems())
                .singleElement().asString().startsWith(directory.resolve("broken.dmn") + ": ");
    }

    @Test
    void a_fail_on_that_is_not_a_severity_stops_startup() {
        var properties = Map.<String, Object>of("praecepta.fail-on", "sometimes");

        Assertions.assertThat(startupFailure(properties).problems())
                .containsExactly("praecepta.fail-on must be ERROR, WARNING or NONE, not sometimes");
    }

    @Test
    void locations_set_explicitly_must_find_files() {
        var properties = Map.<String, Object>of("praecepta.locations", ELSEWHERE);

        Assertions.assertThat(startupFailure(properties).problems())
                .containsExactly(ELSEWHERE + ": no DMN file matches this location");
    }

    @Test
    void the_default_locations_may_find_nothing() {
        var builder = ApplicationContext.builder().classLoader(new WithoutRules(getClass().getClassLoader()));

        try (var context = builder.start()) {
            Assertions.assertThat(context.getBean(Decisions.class).isEmpty()).isTrue();
            Assertions.assertThat(context.getBeansOfType(PraeceptaFactory.ENGINE)).isEmpty();
            Assertions.assertThat(messages(Level.INFO)).containsExactly("No decision tables found at classpath*:rules/**/*.dmn");
        }
        var explicit = ApplicationContext.builder().classLoader(new WithoutRules(getClass().getClassLoader()))
                .properties(Map.of("praecepta.locations", "classpath*:rules/**/*.dmn"));
        Assertions.assertThatThrownBy(() -> explicit.start().close())
                .hasStackTraceContaining("classpath*:rules/**/*.dmn: no DMN file matches this location");
    }

    @Test
    void locations_set_to_nothing_stop_startup() {
        Assertions.assertThat(startupFailure(Map.of("praecepta.locations", " , ")).problems())
                .containsExactly("praecepta.locations is set but names no location");
    }

    @Test
    void enabled_false_turns_the_integration_off() {
        try (var context = ApplicationContext.run(Map.of("praecepta.enabled", false))) {
            Assertions.assertThat(context.containsBean(Decisions.class)).isFalse();
            Assertions.assertThat(context.containsBean(PraeceptaConfiguration.class)).isFalse();
            Assertions.assertThat(context.containsBean(PraeceptaEndpoint.class)).isFalse();
            Assertions.assertThat(context.getBeansOfType(PraeceptaFactory.ENGINE)).isEmpty();
        }
    }

    @Test
    void the_configuration_has_the_documented_defaults() {
        try (var context = ApplicationContext.run()) {
            var configuration = context.getBean(PraeceptaConfiguration.class);

            Assertions.assertThat(configuration.isEnabled()).isTrue();
            Assertions.assertThat(configuration.getLocations()).isNull();
            Assertions.assertThat(configuration.getFailOn()).isEqualTo("ERROR");
        }
    }

    private static DecisionsException startupFailure(Map<String, Object> properties) {
        var failure = Assertions.catchThrowable(() -> ApplicationContext.run(new HashMap<>(properties)).close());
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DecisionsException decisions) {
                return decisions;
            }
        }
        throw new AssertionError("Expected startup to fail with a DecisionsException", failure);
    }

    private List<String> messages(Level level) {
        return log.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** The test class path without its {@code rules/} directory, as an application with no tables there. */
    private static final class WithoutRules extends ClassLoader {

        WithoutRules(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return name.startsWith("rules") ? Collections.emptyEnumeration() : super.getResources(name);
        }
    }
}
