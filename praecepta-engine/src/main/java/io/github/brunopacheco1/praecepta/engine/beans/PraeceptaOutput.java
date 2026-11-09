/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Map;

/**
 * The output values of a rule, keyed by output column name. An {@code OutputStrategy} converts them
 * to the engine's output type.
 *
 * @param values the output values by column name
 */
public record PraeceptaOutput(Map<String, String> values) {
}
