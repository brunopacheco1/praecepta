/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.exceptions;

/**
 * Exception raised if a matrix file cannot be loaded.
 */
public class InvalidMatrixException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
    /**
     * Exception raised if a matrix file cannot be loaded.
     *
     * @param resourcePath The path to the invalid resource.
     * @param cause        The cause.
     */
    public InvalidMatrixException(String resourcePath, Throwable cause) {
        super("Error loading " + resourcePath + ": " + cause.getMessage(), cause);
    }
}
