/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.web.Router;

/**
 * The dev-mode test's application: {@code /discount} gives the discounts of the Discount decision (an engine
 * bean) for a Portuguese customer, {@code /decisions/<name>?column=value} the discounts of any decision, and
 * {@code /decisions} their names.
 */
@ApplicationScoped
public class DiscountRoute {

    @Inject
    @Identifier("Discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Inject
    Decisions decisions;

    void register(@Observes Router router) {
        router.get("/discount").handler(context -> context.response()
                .end(discount.evaluate(Map.of("country", "PT", "customer.age", "30", "since", "2019-01-01")).stream()
                        .map(output -> output.get("discount"))
                        .toList()
                        .toString()));
        router.get("/decisions").handler(context -> context.response().end(decisions.names().toString()));
        router.get("/decisions/:name").handler(context -> {
            var input = new HashMap<String, String>();
            context.queryParams().forEach(input::put);
            context.response().end(decisions.evaluate(context.pathParam("name"), input).stream()
                    .map(output -> output.get("discount"))
                    .toList()
                    .toString());
        });
    }
}
