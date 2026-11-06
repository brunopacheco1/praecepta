/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

/**
 * DMN aggregation of the outputs of a {@link HitPolicy#COLLECT} table into a single value.
 * The combining functions come from the engine's {@code OutputStrategy}.
 */
public enum AggregationOperator {
    SUM,
    MIN,
    MAX,
}
