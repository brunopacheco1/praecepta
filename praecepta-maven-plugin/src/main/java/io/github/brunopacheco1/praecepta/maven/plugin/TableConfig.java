/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.brunopacheco1.praecepta.compiler.TableDefinition;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * One decision table to compile. A DMN source carries its own hit policy, columns and value lists;
 * an Excel ({@code .xlsx}) or CSV source declares them here.
 *
 * <pre>{@code
 * <table>
 *   <source>src/main/rules/discount.xlsx</source>
 *   <hitPolicy>FIRST</hitPolicy>
 *   <inputs>...</inputs>
 *   <outputs>...</outputs>
 * </table>
 * <table>
 *   <source>src/main/rules/shipping.dmn</source>
 *   <decision>Carrier</decision>
 * </table>
 * }</pre>
 */
public class TableConfig {

    /** The {@code .xlsx}, {@code .csv} or {@code .dmn} file. */
    File source;

    /** Name of the generated decision class; defaults to the DMN decision's name or the file name. */
    String name;

    /** DMN only: the decision to read, when the model has several decision tables. */
    String decision;

    /** Excel and CSV only (required there). */
    HitPolicy hitPolicy;

    /** Excel and CSV only: SUM, MIN, MAX or COUNT for a COLLECT table with one NUMBER output. */
    AggregationOperator aggregation;

    /** Excel and CSV only: the input columns, in order. */
    List<ColumnConfig> inputs = new ArrayList<>();

    /** Excel and CSV only: the output columns, in order. */
    List<ColumnConfig> outputs = new ArrayList<>();

    public TableConfig() {
    }

    TableConfig(File source, String name, String decision, HitPolicy hitPolicy, AggregationOperator aggregation,
                List<ColumnConfig> inputs, List<ColumnConfig> outputs) {
        this.source = source;
        this.name = name;
        this.decision = decision;
        this.hitPolicy = hitPolicy;
        this.aggregation = aggregation;
        this.inputs = new ArrayList<>(inputs);
        this.outputs = new ArrayList<>(outputs);
    }

    /** The table as praecepta-compiler takes it. */
    TableDefinition definition() {
        return new TableDefinition(source == null ? null : source.toPath(), name, decision, hitPolicy, aggregation,
                inputs == null ? List.of() : inputs.stream().map(ColumnConfig::definition).toList(),
                outputs == null ? List.of() : outputs.stream().map(ColumnConfig::definition).toList());
    }
}
