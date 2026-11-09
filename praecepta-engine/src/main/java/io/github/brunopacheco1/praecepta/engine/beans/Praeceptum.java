/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

/**
 * One rule (row) of a decision table.
 *
 * <p>Input cells are DMN FEEL unary tests of their column's type, such as {@code "PT","ES"} or
 * {@code >= 18} (see {@code CellParser}). A null, blank or {@code -} cell matches any value, including
 * a missing one.
 *
 * @param priority the rule's identifier, used in validation reports and violation messages
 *                 (loaders set it to the source row number). It does not affect evaluation:
 *                 DMN rule order is the order in which rules are given to the engine.
 * @param input    input cells by column name
 * @param output   output values by column name
 */
public record Praeceptum(
        Integer priority,
        PraeceptaInput input,
        PraeceptaOutput output) {
}
