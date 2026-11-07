/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.exceptions;

import java.util.Set;

public class MissingFieldException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
    public MissingFieldException(Set<String> fieldName) {
        super(String.format("Fields not found in file: %s", String.join(", ", fieldName)));
    }
}
