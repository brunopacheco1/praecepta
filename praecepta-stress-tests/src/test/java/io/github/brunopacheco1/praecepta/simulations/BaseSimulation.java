package io.github.brunopacheco1.praecepta.simulations;

import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.pictet.dxb.tax.simulations.SimulationProperties.baseUrl;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.substring;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static java.nio.charset.Charset.defaultCharset;
import static org.apache.commons.io.IOUtils.resourceToString;

public abstract class BaseSimulation extends Simulation {

    static final Map<String, String> COMMON_HEADERS = Map.of("Content-Type", "application/json");

    HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl());

    String requestBody(String fileName) throws IOException {
        return resourceToString("/" + fileName, defaultCharset());
    }

    List<CheckBuilder> errorChecks = List.of(
            status().is(200),
            substring("exception")
                    .notExists());
}
