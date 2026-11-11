/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * One decision table to compile. A DMN source carries its own hit policy, columns and value lists; an Excel
 * ({@code .xlsx}) or CSV source declares them here.
 *
 * <pre>{@code
 * register("discount") {
 *     source = file("src/main/rules/discount.xlsx")
 *     hitPolicy = "FIRST"
 *     inputs {
 *         column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
 *         column("age") { type = "NUMBER" }
 *     }
 *     outputs {
 *         column("discount") { type = "NUMBER" }
 *     }
 * }
 * register("shipping") {
 *     source = file("src/main/rules/shipping.dmn")
 *     decision = "Carrier"
 * }
 * }</pre>
 *
 * <p>In {@code praecepta-maven-plugin} the generated class is named by {@code <name>}; here {@code name} is the
 * name the table is registered under, as for every Gradle container, so the class is named by
 * {@link #getClassName() className}.
 */
public abstract class TableSpec implements Named {

    /** Creates a table; Gradle does when one is registered. */
    public TableSpec() {
        // Gradle implements the properties
    }

    /**
     * The name the table is registered under, used in Gradle's messages only.
     *
     * @return the name
     */
    @Internal
    @Override
    public abstract String getName();

    /**
     * The {@code .xlsx}, {@code .csv} or {@code .dmn} file (required).
     *
     * @return the file
     */
    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getSource();

    /**
     * Name of the generated decision class; defaults to the DMN decision's name or the file name. This is the
     * Maven plugin's {@code <name>}.
     *
     * @return the class name
     */
    @Input
    @Optional
    public abstract Property<String> getClassName();

    /**
     * DMN only: the decision to read, when the model has several decision tables.
     *
     * @return the decision's name or id
     */
    @Input
    @Optional
    public abstract Property<String> getDecision();

    /**
     * Excel and CSV only (required there): {@code UNIQUE}, {@code ANY}, {@code PRIORITY}, {@code FIRST},
     * {@code OUTPUT_ORDER}, {@code RULE_ORDER} or {@code COLLECT}.
     *
     * @return the hit policy
     */
    @Input
    @Optional
    public abstract Property<String> getHitPolicy();

    /**
     * Excel and CSV only: {@code SUM}, {@code MIN}, {@code MAX} or {@code COUNT} for a {@code COLLECT} table with
     * one {@code NUMBER} output.
     *
     * @return the aggregation
     */
    @Input
    @Optional
    public abstract Property<String> getAggregation();

    /**
     * Excel and CSV only: the input columns, in order.
     *
     * @return the columns
     */
    @Nested
    public abstract Columns getInputs();

    /**
     * Excel and CSV only: the output columns, in order.
     *
     * @return the columns
     */
    @Nested
    public abstract Columns getOutputs();

    /**
     * Declares input columns: {@code inputs { column("country") { ... } }}.
     *
     * @param action what to do with the columns
     */
    public void inputs(Action<? super Columns> action) {
        action.execute(getInputs());
    }

    /**
     * Declares output columns: {@code outputs { column("discount") { type = "NUMBER" } }}.
     *
     * @param action what to do with the columns
     */
    public void outputs(Action<? super Columns> action) {
        action.execute(getOutputs());
    }
}
