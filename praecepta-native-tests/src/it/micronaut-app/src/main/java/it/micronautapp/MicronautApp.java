/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.micronautapp;

import io.micronaut.context.ApplicationContext;

/** Starts the application context, runs the checks of {@link Pricing} and exits with 1 if any fails. */
public final class MicronautApp {

    private MicronautApp() {
    }

    public static void main(String[] args) {
        int status;
        try (var context = ApplicationContext.run()) {
            var failures = context.getBean(Pricing.class).check();
            failures.forEach(failure -> System.err.println("FAILED " + failure));
            status = failures.isEmpty() ? 0 : 1;
        }
        System.exit(status);
    }
}
