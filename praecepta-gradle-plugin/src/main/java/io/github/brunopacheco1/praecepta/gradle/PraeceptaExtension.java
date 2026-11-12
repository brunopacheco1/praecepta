/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.provider.Property;

/**
 * The {@code praecepta} extension: the settings of {@code praecepta-maven-plugin}, with the same names and values.
 *
 * <pre>{@code
 * praecepta {
 *     packageName = "com.acme.pricing"
 *     failOn = "WARNING"
 *     fileHeader = "SPDX-FileCopyrightText: Acme"
 *     tables {
 *         register("shipping") { source = file("src/main/rules/shipping.dmn") }
 *     }
 * }
 * }</pre>
 */
public abstract class PraeceptaExtension {

    /** Creates the extension; Gradle does when the plugin is applied. */
    @SuppressWarnings("java:S5993") // Gradle creates its managed types through public constructors only
    public PraeceptaExtension() {
        // Gradle implements the properties
    }

    /**
     * Package of the generated classes and DMN resources (required); empty for the default package.
     *
     * @return the package name
     */
    public abstract Property<String> getPackageName();

    /**
     * Fail the build if validation finds issues at least this severe: {@code ERROR} (default), {@code WARNING} or
     * {@code NONE}, in any case. Unreadable or invalid tables fail whatever this says.
     *
     * @return the severity
     */
    public abstract Property<String> getFailOn();

    /**
     * Text put first in every generated source, as {@code //} comments: typically the project's own SPDX licence
     * header. Generated code belongs to the project, so there is none by default.
     *
     * @return the header
     */
    public abstract Property<String> getFileHeader();

    /**
     * Whether to skip generation: the task then writes nothing. Defaults to the Gradle property
     * {@code praecepta.skip} ({@code -Ppraecepta.skip=true}), or false.
     *
     * @return whether to skip
     */
    public abstract Property<Boolean> getSkip();

    /**
     * The tables to compile, each registered under a name of its own, used in Gradle's messages. The name of the
     * generated class is {@link TableSpec#getClassName() className}, by default the DMN decision's name or the
     * file name.
     *
     * @return the tables
     */
    public abstract NamedDomainObjectContainer<TableSpec> getTables();

    /**
     * Configures the tables: {@code tables { register("discount") { ... } }}.
     *
     * @param action what to do with them
     */
    public void tables(Action<? super NamedDomainObjectContainer<TableSpec>> action) {
        action.execute(getTables());
    }
}
