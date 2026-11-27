/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring.app;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;

/** A small application using the starter as users would: tables under {@code rules/}, an engine injected by name. */
@SpringBootApplication
public class PricingApplication {

    /** Prices with the {@code Discount} decision of {@code rules/discount.dmn}. */
    @Service
    public static class PriceService {

        private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

        public PriceService(@Qualifier("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount) {
            this.discount = discount;
        }

        /** The best discount for a customer, or zero. */
        public BigDecimal discount(String country, int age, String since) {
            return discount.evaluate(Map.of("country", country, "customer.age", Integer.toString(age), "since", since)).stream()
                    .map(output -> new BigDecimal(output.get("discount")))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
        }
    }
}
