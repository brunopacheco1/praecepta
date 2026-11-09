/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.Map;

/**
 * Text keyed by input column name: the input cells of a rule ({@link Praeceptum#input()}), or an input
 * as an {@code InputStrategy} produces it.
 *
 * @param values the cells or input values by column name
 */
public record PraeceptaInput(Map<String, String> values) {
}
