/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

/**
 * FEEL's ternary logic (DMN 1.5, Tables 50 and 51): what a test is for a value, where {@code UNKNOWN} is FEEL's
 * null, as a comparison with a missing value or of a time with a zone id and one it does not compare with gives.
 */
enum Truth {
    TRUE, FALSE, UNKNOWN;

    static Truth of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** FEEL's {@code not}: unknown stays unknown. */
    Truth not() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** FEEL's {@code and}: false if either is false, else unknown if either is unknown, else true. */
    Truth and(Truth other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
    }
}
