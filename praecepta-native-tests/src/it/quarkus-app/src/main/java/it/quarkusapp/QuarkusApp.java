/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.quarkusapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.smallrye.common.annotation.Identifier;

/**
 * A command-mode application: the engines of {@code rules/discount.dmn} and of the CSV table declared in
 * {@code application.properties} injected by their decisions' names, and the {@code Decisions} bean. It
 * compares their answers with those of the DMN semantics and exits with 1 if any differs.
 */
@QuarkusMain
public class QuarkusApp implements QuarkusApplication {

    @Inject
    @Identifier("Discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Inject
    @Named("fees")
    PraeceptaEngine<Map<String, String>, Map<String, String>> fees;

    @Inject
    Decisions decisions;

    private final List<String> failures = new ArrayList<>();

    @Override
    public int run(String... args) {
        // RULE ORDER: both rules match a loyal Portuguese adult
        check("Discount, both rules", () -> discount.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")),
                List.of(Map.of("discount", "0.15", "tier", "gold"), Map.of("discount", "0.05", "tier", "silver")));
        check("Discount, no rule", () -> discount.evaluate(Map.of("country", "FR", "customer.age", "30")), List.of());
        // the declared CSV table, COLLECT SUM: 5 + 12.5
        check("Fees, islands", () -> fees.evaluate(Map.of("zone", "islands")), List.of(Map.of("fee", "17.5")));
        check("Fees, by name", () -> decisions.evaluate("fees", Map.of("zone", "mainland")), List.of(Map.of("fee", "5")));
        // rules/graph/credit.dmn, a decision requirements graph (#14): Monthly Limit = Credit Limit / 12, from the input data
        check("Monthly Limit, through the graph", () -> decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "30", "Income", "1200")),
                List.of(Map.of("Monthly Limit", "200")));
        check("Decisions found", decisions::names, Set.of("Credit Limit", "Discount", "Monthly Limit", "Risk Category", "fees"));
        System.out.printf("%d checks failed%n", failures.size());
        failures.forEach(failure -> System.err.println("FAILED " + failure));
        return failures.isEmpty() ? 0 : 1;
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
