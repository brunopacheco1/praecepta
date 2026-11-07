/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

/**
 * An input value cannot be evaluated: it is outside its column's declared input values (DMN input
 * value list), or, with map-based inputs, it cannot be read as the column's type.
 */
public class InvalidInputException extends IllegalArgumentException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String column;
    private final transient Object value;

    public InvalidInputException(String column, Object value) {
        super("Input %s=%s is not one of the column's allowed input values".formatted(column, value));
        this.column = column;
        this.value = value;
    }

    public InvalidInputException(String column, Object value, String problem) {
        super("Input %s=%s %s".formatted(column, value, problem));
        this.column = column;
        this.value = value;
    }

    public String getColumn() {
        return column;
    }

    public Object getValue() {
        return value;
    }
}
