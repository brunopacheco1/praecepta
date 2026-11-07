/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.exceptions;

public class MissingFileException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
    public MissingFileException(String path, Throwable cause) {
        super(String.format("The file not found: %s", path), cause);
    }
}
