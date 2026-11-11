/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.nio.file.Path;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * One decision table to compile, as a build tool's configuration describes it. A DMN source carries its
 * own hit policy, columns and value lists, so only {@code source}, {@code name} and {@code decision}
 * apply to it; an Excel ({@code .xlsx}) or CSV source declares them here. {@link TableCompiler} checks
 * which settings apply and names a misplaced one in its message.
 *
 * @param source      the {@code .xlsx}, {@code .csv} or {@code .dmn} file; null is reported as a missing
 *                    setting
 * @param name        name of the generated decision class; null for the DMN decision's name or the file
 *                    name
 * @param decision    DMN only: the decision to read, when the model has several decision tables; else null
 * @param hitPolicy   Excel and CSV only (required there)
 * @param aggregation Excel and CSV only: SUM, MIN, MAX or COUNT for a COLLECT table with one NUMBER
 *                    output; else null
 * @param inputs      Excel and CSV only: the input columns, in order
 * @param outputs     Excel and CSV only: the output columns, in order
 */
public record TableDefinition(Path source, String name, String decision, HitPolicy hitPolicy, AggregationOperator aggregation,
        List<ColumnDefinition> inputs, List<ColumnDefinition> outputs) {

    /** Copies the column lists; null lists are empty. */
    public TableDefinition {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    /** A DMN model's only decision table, named after its decision. */
    public static TableDefinition dmn(Path source) {
        return new TableDefinition(source, null, null, null, null, List.of(), List.of());
    }
}
