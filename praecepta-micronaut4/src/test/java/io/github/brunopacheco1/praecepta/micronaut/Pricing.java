/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** An application bean that injects a decision's engine by name, as the README shows. */
@Singleton
@Requires(property = "praecepta-test.pricing")
class Pricing {

    private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    Pricing(@Named("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount) {
        this.discount = discount;
    }

    List<String> tiers(String country, int age, LocalDate since) {
        return discount.evaluate(Map.of("country", country, "customer.age", Integer.toString(age), "since", since.toString())).stream()
                .map(output -> output.get("tier"))
                .toList();
    }
}
