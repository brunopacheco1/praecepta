/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

/**
 * An input value cannot be evaluated: it is outside its column's declared input values (DMN input
 * value list), or it is not a valid value of the column's type (text that does not parse, or a number
 * that is not finite).
 */
public class InvalidInputException extends IllegalArgumentException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String column;
    private final transient Object value;

    /** The value is outside the column's declared input values. */
    public InvalidInputException(String column, Object value) {
        super("Input %s=%s is not one of the column's allowed input values".formatted(column, value));
        this.column = column;
        this.value = value;
    }

    /**
     * The value is not a valid value of the column's type.
     *
     * @param problem the end of the message, after "Input column=value", e.g. {@code "is not a valid number"}
     */
    public InvalidInputException(String column, Object value, String problem) {
        super("Input %s=%s %s".formatted(column, value, problem));
        this.column = column;
        this.value = value;
    }

    /** The column the value was given for. */
    public String getColumn() {
        return column;
    }

    /** The rejected value, converted to the column's type when it could be read (e.g. a BigDecimal for a number column). */
    public Object getValue() {
        return value;
    }
}
