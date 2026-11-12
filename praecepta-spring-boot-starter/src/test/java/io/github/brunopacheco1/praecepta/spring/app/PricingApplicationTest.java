/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.spring.app;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.jayway.jsonpath.JsonPath;

import io.github.brunopacheco1.praecepta.integration.Decisions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,praecepta")
class PricingApplicationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private PricingApplication.PriceService prices;

    @Autowired
    private Decisions decisions;

    @Test
    void the_application_evaluates_with_the_injected_engine() {
        Assertions.assertThat(prices.discount("PT", 30, "2019-01-01")).isEqualByComparingTo("0.15");
        Assertions.assertThat(prices.discount("ES", 30, "2024-01-01")).isEqualByComparingTo("0.05");
        Assertions.assertThat(prices.discount("FR", 30, "2019-01-01")).isEqualByComparingTo(BigDecimal.ZERO);
        Assertions.assertThat(decisions.names()).containsExactly("Carrier", "Discount", "Fee");
    }

    @Test
    void the_actuator_endpoint_reports_every_decision() throws IOException, InterruptedException {
        var response = get("/actuator/praecepta");
        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> described = JsonPath.read(response.body(), "$.decisions");
        Assertions.assertThat(described).containsOnlyKeys("Carrier", "Discount", "Fee");
        Assertions.assertThat((String) JsonPath.read(response.body(), "$.decisions.Discount.hitPolicy")).isEqualTo("RULE_ORDER");
        Assertions.assertThat((String) JsonPath.read(response.body(), "$.decisions.Fee.aggregation")).isEqualTo("SUM");
        Assertions.assertThat((List<?>) JsonPath.read(response.body(), "$.decisions.Carrier.issues")).isEmpty();
    }

    @Test
    void the_actuator_endpoint_reports_one_decision_by_name() throws IOException, InterruptedException {
        var response = get("/actuator/praecepta/Discount");
        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        Assertions.assertThat((String) JsonPath.read(response.body(), "$.name")).isEqualTo("Discount");
        Assertions.assertThat((String) JsonPath.read(response.body(), "$.location")).endsWith("rules/discount.dmn");
        Assertions.assertThat((Integer) JsonPath.read(response.body(), "$.rules")).isEqualTo(2);
        List<String> inputs = JsonPath.read(response.body(), "$.inputs[*].name");
        Assertions.assertThat(inputs).containsExactly("country", "customer.age", "since");
        List<String> outputTypes = JsonPath.read(response.body(), "$.outputs[*].type");
        Assertions.assertThat(outputTypes).containsExactly("NUMBER", "STRING");

        Assertions.assertThat(get("/actuator/praecepta/Nope").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
