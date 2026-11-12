/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.springapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the application, runs the checks of {@link Pricing} and exits with 1 if any fails. */
@SpringBootApplication
public class SpringApp {

    public static void main(String[] args) {
        int status;
        try (var context = SpringApplication.run(SpringApp.class, args)) {
            var failures = context.getBean(Pricing.class).check();
            failures.forEach(failure -> System.err.println("FAILED " + failure));
            status = failures.isEmpty() ? 0 : 1;
        }
        System.exit(status);
    }
}
