/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;

/**
 * DMN aggregation of the outputs of a {@link HitPolicy#COLLECT} table into a single value.
 * The combining functions come from the engine's {@code OutputStrategy}: {@code sum}, {@code min}
 * and {@code max} combine the matching rules' outputs, and {@code count} builds an output from the
 * number of matching rules. They apply only when a rule matches: when none does, the result is the
 * table's default output, or none, whatever the aggregation (DMN 1.5, 10.3.2.10, step 2), so COUNT
 * gives no output rather than zero.
 */
public enum AggregationOperator {
    SUM,
    MIN,
    MAX,
    COUNT;

    /**
     * Checks that the aggregation applies to a table's outputs, as DMN 1.5 defines it: through FEEL's
     * built-ins {@code sum}, {@code count}, {@code min} and {@code max} (8.2.11, 10.3.2.10), over a single
     * output, since "decision tables with compound outputs do not support aggregation" (8.2.11). SUM adds
     * numbers, MIN and MAX take values that are ordered (every type but boolean; Table 75's "comparable
     * items"), and COUNT counts outputs of any type. The engine itself leaves aggregation to the
     * {@code OutputStrategy}; integrations that give aggregations their meaning check them with this.
     *
     * @throws IllegalArgumentException if it does not apply, saying why (such as
     *                                  {@code SUM aggregation needs a number output, not a string})
     */
    public void check(List<OutputColumn> outputs) {
        if (outputs.size() != 1) {
            throw new IllegalArgumentException("%s aggregation needs exactly one output; DMN does not aggregate %d outputs"
                    .formatted(this, outputs.size()));
        }
        var type = outputs.getFirst().type();
        switch (this) {
            case SUM -> {
                if (type != ColumnType.NUMBER) {
                    throw new IllegalArgumentException("SUM aggregation needs a number output, not a " + type.feelName());
                }
            }
            case MIN, MAX -> {
                if (!type.supportsComparisons()) {
                    throw new IllegalArgumentException("%s aggregation needs an output whose values are ordered, not a %s"
                            .formatted(this, type.feelName()));
                }
            }
            case COUNT -> {
                // counts outputs of any type
            }
        }
    }
}
