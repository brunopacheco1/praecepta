/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.assertj.core.api.Assertions;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Gradle builds (TestKit) of small projects that apply the plugin as users do: by id and version, resolved
 * through its marker from a Maven repository holding this build's artifacts (and Maven Central, for the BOMs that
 * third-party POMs import). Run by the {@code gradle-functional}
 * profile, which fills that repository and names the Gradle version ({@code praecepta.gradle.version}); TestKit
 * downloads that distribution.
 */
class PraeceptaPluginFunctionalTest {

    private static final String GRADLE_VERSION = property("praecepta.gradle.version");

    private static final String VERSION = property("praecepta.version");

    private static final Path REPOSITORY = Path.of(property("praecepta.repository")).toAbsolutePath();

    private static final Path GOLDEN = Path.of(property("praecepta.golden"));

    private static final String FEES = """
            praecepta {
                packageName = 'com.acme.rules'
                fileHeader = 'Golden test header\\nsecond line'
                tables {
                    register('fees') {
                        source = file('src/main/rules/fees.csv')
                        className = 'Fees'
                        hitPolicy = 'COLLECT'
                        aggregation = 'SUM'
                        inputs {
                            column('zone') { allowedValues = '"islands","mainland"' }
                        }
                        outputs {
                            column('fee') { type = 'NUMBER' }
                        }
                    }
                }
            }
            """;

    @TempDir
    Path directory;

    /**
     * Publishes the plugin marker next to the artifacts the invoker installed, as the release publishes it to Maven
     * Central. The marker module is built after this one, and its POM is all there is to it.
     */
    @BeforeAll
    static void publish_the_plugin_marker() throws IOException {
        var marker = "io.github.brunopacheco1.praecepta.gradle.plugin";
        var target = REPOSITORY.resolve("io/github/brunopacheco1/praecepta/" + marker + "/" + VERSION + "/" + marker + "-" + VERSION + ".pom");
        Files.createDirectories(target.getParent());
        Files.copy(Path.of(property("praecepta.marker")), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void a_project_applying_the_plugin_by_id_compiles_and_runs_the_generated_classes() throws IOException {
        var project = project("app", """
                plugins {
                    id 'application'
                    id 'io.github.brunopacheco1.praecepta' version '%s'
                }
                dependencies {
                    implementation 'io.github.brunopacheco1:praecepta-engine:%s'
                }
                application {
                    mainClass = 'app.Main'
                }
                """.formatted(VERSION, VERSION) + FEES);
        write(project.resolve("src/main/java/app/Main.java"), """
                package app;

                import com.acme.rules.Fees;
                import com.acme.rules.FeesInput;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("fee: " + Fees.evaluate(new FeesInput(FeesInput.Zone.ISLANDS)).orElseThrow().fee());
                    }
                }
                """);

        var result = gradle(project, "run").build();

        Assertions.assertThat(result.task(":generatePraecepta").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Assertions.assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Assertions.assertThat(result.getOutput()).contains("fee: 17.5");
        Assertions.assertThat(project.resolve("build/resources/main/com/acme/rules/Fees.dmn")).exists();
        // the Maven plugin's golden files, but for the tool they name
        var generated = new TreeMap<String, String>();
        var sources = project.resolve("build/generated/sources/praecepta/java/main/com/acme/rules");
        for (var name : List.of("Fees", "FeesInput", "FeesOutput")) {
            generated.put(name + ".java.txt", Files.readString(sources.resolve(name + ".java"))
                    .replace("praecepta-gradle-plugin", "praecepta-maven-plugin"));
        }
        generated.put("Fees.dmn.txt", Files.readString(project.resolve("build/generated/resources/praecepta/main/com/acme/rules/Fees.dmn")));
        Assertions.assertThat(generated).isEqualTo(read(GOLDEN.resolve("fees")));
    }

    @Test
    void the_task_is_up_to_date_and_comes_from_the_build_cache_in_another_directory() throws IOException {
        var cache = directory.resolve("build-cache");
        var first = project("first", javaProject() + FEES);
        cacheIn(first, cache);

        Assertions.assertThat(outcome(gradle(first, "generatePraecepta", "--build-cache").build())).isEqualTo(TaskOutcome.SUCCESS);
        Assertions.assertThat(outcome(gradle(first, "generatePraecepta", "--build-cache").build())).isEqualTo(TaskOutcome.UP_TO_DATE);

        // the same tables elsewhere: the cache key holds the files' names and contents, not their paths
        var second = project("second", javaProject() + FEES);
        cacheIn(second, cache);
        Assertions.assertThat(outcome(gradle(second, "generatePraecepta", "--build-cache").build())).isEqualTo(TaskOutcome.FROM_CACHE);
        Assertions.assertThat(second.resolve("build/generated/sources/praecepta/java/main/com/acme/rules/Fees.java")).exists();

        // a changed table runs it again
        Files.writeString(second.resolve("src/main/rules/fees.csv"), "zone;fee\n;5\nislands;12.5\nmainland;1\n");
        Assertions.assertThat(outcome(gradle(second, "generatePraecepta", "--build-cache").build())).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void the_configuration_cache_is_stored_and_reused() throws IOException {
        var project = project("cc", javaProject() + FEES);

        var first = gradle(project, "compileJava", "--configuration-cache").build();
        Assertions.assertThat(first.getOutput()).contains("Configuration cache entry stored");
        Assertions.assertThat(outcome(first)).isEqualTo(TaskOutcome.SUCCESS);

        Files.delete(project.resolve("build/generated/sources/praecepta/java/main/com/acme/rules/Fees.java"));
        var second = gradle(project, "compileJava", "--configuration-cache").build();
        Assertions.assertThat(second.getOutput()).contains("Reusing configuration cache");
        Assertions.assertThat(outcome(second)).isEqualTo(TaskOutcome.SUCCESS);
        Assertions.assertThat(project.resolve("build/generated/sources/praecepta/java/main/com/acme/rules/Fees.java")).exists();
    }

    @Test
    void the_kotlin_dsl_takes_the_same_settings() throws IOException {
        var project = directory.resolve("kotlin");
        copyTables(project);
        write(project.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        maven { url = uri("%s") }
                        mavenCentral()
                    }
                }
                rootProject.name = "kotlin"
                """.formatted(REPOSITORY.toUri()));
        write(project.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.github.brunopacheco1.praecepta") version "%s"
                }
                praecepta {
                    packageName = "com.acme.pricing"
                    failOn = "error"
                    tables {
                        register("discount") {
                            source = file("src/main/rules/discount.csv")
                            hitPolicy = "FIRST"
                            inputs {
                                column("country") { allowedValues = "\\"PT\\",\\"ES\\",\\"FR\\"" }
                                column("age") { type = "NUMBER"; allowedValues = "[0..120]" }
                                column("since") { type = "DATE" }
                            }
                            outputs {
                                column("discount") { type = "NUMBER" }
                                column("tier")
                            }
                        }
                        register("offers") {
                            source = file("src/main/rules/nationalities.csv")
                            className = "Offer"
                            hitPolicy = "PRIORITY"
                            inputs {
                                column("nationalities") { list = true }
                                column("ages") { type = "NUMBER"; list = true }
                            }
                            outputs {
                                column("offer") { priority = listOf("iberian", "senior", "standard") }
                            }
                        }
                        // an Excel workbook: Apache POI runs in the plugin's class loader
                        register("matrix") {
                            source = file("src/main/rules/matrix.xlsx")
                            hitPolicy = "FIRST"
                            inputs {
                                column("inputOne")
                                column("inputTwo")
                            }
                            outputs {
                                column("output")
                            }
                        }
                    }
                }
                """.formatted(VERSION));

        var result = gradle(project, "generatePraecepta").build();

        Assertions.assertThat(outcome(result)).isEqualTo(TaskOutcome.SUCCESS);
        var sources = project.resolve("build/generated/sources/praecepta/java/main/com/acme/pricing");
        Assertions.assertThat(sources.resolve("DiscountInput.java")).content()
                .contains("public record DiscountInput(Country country, java.math.BigDecimal age, java.time.LocalDate since)");
        Assertions.assertThat(sources.resolve("OfferInput.java")).content()
                .contains("java.util.List<String> nationalities, java.util.List<java.math.BigDecimal> ages");
        Assertions.assertThat(project.resolve("build/generated/resources/praecepta/main/com/acme/pricing/Offer.dmn")).content()
                .contains("hitPolicy=\"PRIORITY\"");
        Assertions.assertThat(project.resolve("build/generated/resources/praecepta/main/com/acme/pricing/Matrix.dmn")).content()
                .contains("<text>\"TEST2\"</text>", "<text>\"rule3\"</text>");
    }

    @Test
    void validation_issues_fail_the_build_at_fail_on_and_are_logged_below_it() throws IOException {
        var tiers = """
                praecepta {
                    packageName = 'com.acme.rules'
                    failOn = '%s'
                    tables {
                        register('tiers') {
                            source = file('src/main/rules/overlapping.csv')
                            className = 'Tier'
                            hitPolicy = 'UNIQUE'
                            inputs { column('country') }
                            outputs { column('tier') }
                        }
                    }
                }
                """;
        var failing = project("failing", javaProject() + tiers.formatted("ERROR"));
        var failure = gradle(failing, "generatePraecepta").buildAndFail();
        Assertions.assertThat(failure.task(":generatePraecepta").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        Assertions.assertThat(failure.getOutput()).contains("Table 'overlapping'", "UNIQUE");

        var warning = project("warning", javaProject() + tiers.formatted("NONE"));
        var success = gradle(warning, "generatePraecepta").build();
        Assertions.assertThat(success.getOutput()).contains("Tier: ERROR rules [0, 1] both match some inputs (UNIQUE)");
    }

    @Test
    void the_skip_property_writes_nothing() throws IOException {
        var project = project("skip", javaProject() + FEES);

        var result = gradle(project, "compileJava", "-Ppraecepta.skip=true").build();

        Assertions.assertThat(result.getOutput()).contains("Skipping Praecepta table generation");
        Assertions.assertThat(project.resolve("build/generated/sources/praecepta/java/main/com/acme/rules/Fees.java")).doesNotExist();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String javaProject() {
        return """
                plugins {
                    id 'java'
                    id 'io.github.brunopacheco1.praecepta' version '%s'
                }
                dependencies {
                    implementation 'io.github.brunopacheco1:praecepta-engine:%s'
                }
                """.formatted(VERSION, VERSION);
    }

    /** A Groovy DSL project with the test tables under src/main/rules, resolving from the test repository. */
    private Path project(String name, String buildScript) throws IOException {
        var project = directory.resolve(name);
        copyTables(project);
        write(project.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        maven { url = uri('%1$s') }
                        mavenCentral()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri('%1$s') }
                        mavenCentral()
                    }
                }
                rootProject.name = '%2$s'
                """.formatted(REPOSITORY.toUri(), name));
        write(project.resolve("build.gradle"), buildScript);
        return project;
    }

    private static void cacheIn(Path project, Path cache) throws IOException {
        Files.writeString(project.resolve("settings.gradle"), """
                buildCache {
                    local { directory = file('%s') }
                }
                """.formatted(cache.toUri().getPath()), java.nio.file.StandardOpenOption.APPEND);
    }

    private static void copyTables(Path project) throws IOException {
        var rules = Files.createDirectories(project.resolve("src/main/rules"));
        for (var name : List.of("fees.csv", "overlapping.csv", "discount.csv", "nationalities.csv", "matrix.xlsx")) {
            try {
                Files.copy(Path.of(PraeceptaPluginFunctionalTest.class.getResource("/tables/" + name).toURI()), rules.resolve(name));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static GradleRunner gradle(Path project, String... arguments) {
        var args = new ArrayList<>(List.of(arguments));
        args.add("--stacktrace");
        // a deprecation the plugin causes would break it in the next major Gradle release
        args.add("--warning-mode=fail");
        return GradleRunner.create()
                .withGradleVersion(GRADLE_VERSION)
                .withProjectDir(project.toFile())
                .withArguments(args);
    }

    private static TaskOutcome outcome(BuildResult result) {
        return result.task(":generatePraecepta").getOutcome();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Map<String, String> read(Path directory) throws IOException {
        var files = new TreeMap<String, String>();
        try (var stream = Files.list(directory)) {
            stream.forEach(file -> {
                try {
                    files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return files;
    }

    private static String property(String name) {
        var value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException("Run the functional tests with -P gradle-functional, which sets " + name);
        }
        return value;
    }
}
