/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.Serial;
import java.util.List;

/**
 * Tables that could not be loaded: unreadable or invalid DMN, two decisions with the same name, or
 * validation issues at the configured severity. Lists every problem found, not only the first, so that one
 * failed startup shows them all.
 */
public final class DecisionsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Each problem, as {@code <location>: <what is wrong>}; the message holds them too, so it is not serialised. */
    private final transient List<String> problems;

    public DecisionsException(List<String> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    /** Each problem, as {@code <location>: <what is wrong>}, in the order the sources were read. */
    public List<String> problems() {
        return problems;
    }

    private static String message(List<String> problems) {
        return problems.size() == 1
                ? "Cannot load decision tables: " + problems.getFirst()
                : "Cannot load decision tables (%d problems):%n- %s".formatted(problems.size(), String.join("\n- ", problems));
    }
}
