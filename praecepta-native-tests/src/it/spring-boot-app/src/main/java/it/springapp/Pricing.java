/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.springapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.inject.Named;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;

/**
 * A service as users write one: the engines of {@code rules/discount.dmn} and {@code rules/shipping/shipping.dmn}
 * injected by their decisions' names, and the {@code Decisions} bean. {@link #check()} compares their answers
 * with those of the DMN semantics.
 */
@Service
public class Pricing {

    private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;
    private final PraeceptaEngine<Map<String, String>, Map<String, String>> carrier;
    private final Decisions decisions;
    private final List<String> failures = new ArrayList<>();

    public Pricing(@Qualifier("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount,
            @Named("Carrier") PraeceptaEngine<Map<String, String>, Map<String, String>> carrier, Decisions decisions) {
        this.discount = discount;
        this.carrier = carrier;
        this.decisions = decisions;
    }

    /** Runs every check, printing each, and returns the failures. */
    public List<String> check() {
        // RULE ORDER: both rules match a loyal Portuguese adult
        check("Discount, both rules", () -> discount.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")),
                List.of(Map.of("discount", "0.15", "tier", "gold"), Map.of("discount", "0.05", "tier", "silver")));
        check("Discount, no rule", () -> discount.evaluate(Map.of("country", "FR", "customer.age", "30")), List.of());
        // FIRST, an unnamed output named after its decision
        check("Carrier", () -> carrier.evaluate(Map.of("weight", "1.5")), List.of(Map.of("Carrier", "post")));
        // rules/graph/credit.dmn, a decision requirements graph (#14): Monthly Limit = Credit Limit / 12, from the input data
        check("Monthly Limit, through the graph", () -> decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "30", "Income", "1200")),
                List.of(Map.of("Monthly Limit", "200")));
        check("Decisions found", decisions::names, Set.of("Carrier", "Credit Limit", "Discount", "Fee", "Monthly Limit", "Risk Category"));
        // COLLECT SUM: 5 + 12.5
        check("Fee, by name", () -> decisions.evaluate("Fee", Map.of("zone", "islands")), List.of(Map.of("fee", "17.5")));
        System.out.printf("%d checks failed%n", failures.size());
        return List.copyOf(failures);
    }

    private void check(String name, Supplier<?> actual, Object expected) {
        Object value;
        try {
            value = actual.get();
        } catch (RuntimeException e) {
            failures.add(name + ": threw " + e);
            return;
        }
        if (Objects.equals(value, expected)) {
            System.out.println("ok " + name);
        } else {
            failures.add(name + ": expected " + expected + " but got " + value);
        }
    }
}
