/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.List;
import java.util.Objects;

/**
 * A complete decision table: columns with their types and value lists, hit policy and rules. This is
 * what a DMN decision table holds; {@code DmnReader} and {@code DmnWriter} convert between the two,
 * and {@code PraeceptaEngine.Builder#table} configures an engine from it.
 *
 * @param name        the decision's name
 * @param hitPolicy   the hit policy
 * @param aggregation COLLECT aggregation, or null
 * @param inputs      input columns, in table order
 * @param outputs     output columns, in table order
 * @param rules       rules in rule order; input cells are unary tests of their column's type, outputs
 *                    are plain values (text, a decimal number or an ISO date), null when empty
 */
public record DecisionTable(
        String name,
        HitPolicy hitPolicy,
        AggregationOperator aggregation,
        List<InputColumn> inputs,
        List<OutputColumn> outputs,
        List<Praeceptum> rules) {

    public DecisionTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hitPolicy, "hitPolicy");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rules = List.copyOf(rules);
    }

    /**
     * @param name          the column name, which is also the input variable it reads
     * @param type          the column type
     * @param allowedValues DMN input value list as unary tests (e.g. {@code "PT","ES"} or {@code [0..120]}), or null
     */
    public record InputColumn(String name, ColumnType type, String allowedValues) {
        public InputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        public InputColumn(String name, ColumnType type) {
            this(name, type, null);
        }
    }

    /**
     * @param name   the column name
     * @param type   the type of its values
     * @param values DMN output value list, highest priority first (used by PRIORITY and OUTPUT_ORDER); may be empty
     */
    public record OutputColumn(String name, ColumnType type, List<String> values) {
        public OutputColumn {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            values = List.copyOf(values);
        }

        public OutputColumn(String name, ColumnType type) {
            this(name, type, List.of());
        }
    }
}
