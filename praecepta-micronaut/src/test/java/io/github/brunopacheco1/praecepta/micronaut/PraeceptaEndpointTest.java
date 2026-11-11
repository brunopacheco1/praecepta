/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.micronaut;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;

class PraeceptaEndpointTest {

    private static final Argument<Map<String, Object>> JSON = Argument.mapOf(String.class, Object.class);

    @Test
    void reports_every_decision_and_each_one_by_name() {
        try (var server = start(Map.of("endpoints.praecepta.sensitive", false));
                var client = HttpClient.create(server.getURL())) {
            var http = client.toBlocking();

            var all = http.retrieve(HttpRequest.GET("/praecepta"), JSON);
            Assertions.assertThat(all).containsOnlyKeys("decisions");
            Assertions.assertThat(all.get("decisions")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsOnlyKeys("Carrier", "Discount", "Fee");

            var fee = http.retrieve(HttpRequest.GET("/praecepta/Fee"), JSON);
            Assertions.assertThat(fee)
                    .containsEntry("name", "Fee")
                    .containsEntry("hitPolicy", "COLLECT")
                    .containsEntry("aggregation", "SUM")
                    .containsEntry("rules", 2)
                    .containsEntry("inputs", List.of(Map.of("name", "zone", "type", "STRING", "list", false)))
                    .containsEntry("outputs", List.of(Map.of("name", "fee", "type", "NUMBER")))
                    .containsEntry("issues", List.of());
            Assertions.assertThat((String) fee.get("location")).endsWith("rules/shipping/shipping.dmn");
            Assertions.assertThat(all.get("decisions")).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                    .containsEntry("Fee", fee);

            Assertions.assertThatThrownBy(() -> http.retrieve(HttpRequest.GET("/praecepta/Nope"), JSON))
                    .isInstanceOfSatisfying(HttpClientResponseException.class,
                            e -> Assertions.assertThat(e.getStatus().getCode()).isEqualTo(HttpStatus.NOT_FOUND.getCode()));
        }
    }

    @Test
    void is_sensitive_unless_configured_otherwise() {
        try (var server = start(Map.of());
                var client = HttpClient.create(server.getURL())) {
            var http = client.toBlocking();

            Assertions.assertThatThrownBy(() -> http.retrieve(HttpRequest.GET("/praecepta"), JSON))
                    .isInstanceOfSatisfying(HttpClientResponseException.class,
                            e -> Assertions.assertThat(e.getStatus().getCode()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode()));
        }
    }

    @Test
    void gives_the_reports_of_the_decisions_bean() {
        try (var context = ApplicationContext.run()) {
            var endpoint = context.getBean(PraeceptaEndpoint.class);
            var decisions = context.getBean(Decisions.class);

            Assertions.assertThat(endpoint.decisions()).isEqualTo(decisions.describe());
            Assertions.assertThat(endpoint.decision("Discount")).isEqualTo(decisions.get("Discount").describe());
            Assertions.assertThat(endpoint.decision("Nope")).isNull();
        }
    }

    private static EmbeddedServer start(Map<String, Object> properties) {
        var all = new java.util.HashMap<String, Object>(properties);
        all.put("micronaut.server.port", -1);
        return ApplicationContext.run(EmbeddedServer.class, all);
    }
}
