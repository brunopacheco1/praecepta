/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import java.util.List;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * The Gradle plugin {@value #ID}: compiles decision tables at build time, as {@code praecepta-maven-plugin} does.
 * It adds the {@value #EXTENSION_NAME} extension ({@link PraeceptaExtension}) and the {@value #TASK_NAME} task
 * ({@link GeneratePraecepta}). With the {@code java} plugin, the task's generated sources and DMN resources join
 * the {@code main} source set, so {@code compileJava} and {@code processResources} run it first.
 *
 * <pre>{@code
 * plugins {
 *     java
 *     id("io.github.brunopacheco1.praecepta") version "0.5.0"
 * }
 *
 * praecepta {
 *     packageName = "com.acme.pricing"
 *     tables {
 *         register("discount") {
 *             source = file("src/main/rules/discount.csv")
 *             hitPolicy = "FIRST"
 *             inputs {
 *                 column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
 *                 column("age") { type = "NUMBER" }
 *             }
 *             outputs {
 *                 column("discount") { type = "NUMBER" }
 *             }
 *         }
 *         register("shipping") { source = file("src/main/rules/shipping.dmn") }
 *     }
 * }
 * }</pre>
 */
public class PraeceptaPlugin implements Plugin<Project> {

    /** The plugin's id. */
    public static final String ID = "io.github.brunopacheco1.praecepta";

    /** The extension's name in build scripts. */
    public static final String EXTENSION_NAME = "praecepta";

    /** The name of the task that compiles the tables. */
    public static final String TASK_NAME = "generatePraecepta";

    /** The Gradle property that skips generation, as {@code -Dpraecepta.skip} does in Maven. */
    public static final String SKIP_PROPERTY = "praecepta.skip";

    /** Creates the plugin; Gradle does when the plugin is applied. */
    public PraeceptaPlugin() {
        // nothing to set up before apply
    }

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create(EXTENSION_NAME, PraeceptaExtension.class);
        var providers = project.getProviders();
        extension.getFailOn().convention("ERROR");
        extension.getSkip().convention(providers.gradleProperty(SKIP_PROPERTY).map(Boolean::parseBoolean).orElse(false));

        var buildDirectory = project.getLayout().getBuildDirectory();
        var task = project.getTasks().register(TASK_NAME, GeneratePraecepta.class, generate -> {
            generate.setGroup("build");
            generate.setDescription("Validates the Praecepta decision tables and generates their DMN resources and Java classes.");
            generate.getPackageName().set(extension.getPackageName());
            generate.getFailOn().set(extension.getFailOn());
            generate.getFileHeader().set(extension.getFileHeader());
            generate.getSkip().set(extension.getSkip());
            // in declaration order, read when the task runs, so tables registered after this are included
            generate.getTables().set(providers.provider(() -> List.copyOf(extension.getTables())));
            generate.getSourceDirectory().convention(buildDirectory.dir("generated/sources/praecepta/java/main"));
            generate.getResourceDirectory().convention(buildDirectory.dir("generated/resources/praecepta/main"));
        });

        project.getPluginManager().withPlugin("java", java -> {
            var main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            // directories given as the task's outputs carry the dependency on it
            main.getJava().srcDir(task.flatMap(GeneratePraecepta::getSourceDirectory));
            main.getResources().srcDir(task.flatMap(GeneratePraecepta::getResourceDirectory));
        });
    }
}
