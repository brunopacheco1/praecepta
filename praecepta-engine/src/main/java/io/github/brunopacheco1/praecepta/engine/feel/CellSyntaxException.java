/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

/** A table cell is not a valid unary test for its column type. */
public class CellSyntaxException extends IllegalArgumentException {

    private final String cell;

    public CellSyntaxException(String cell, String problem) {
        super("Invalid cell '%s': %s".formatted(cell, problem));
        this.cell = cell;
    }

    public String getCell() {
        return cell;
    }
}
