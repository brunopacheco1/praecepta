package io.github.brunopacheco1.praecepta.simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;

import java.io.IOException;

import static io.github.brunopacheco1.praecepta.simulations.SimulationProperties.durationInSecs;
import static io.github.brunopacheco1.praecepta.simulations.SimulationProperties.users;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;

public class BenchmarkSimulation extends BaseSimulation {

    ChainBuilder benchmarkRequest =
            during(10)
                    .on(
                            exec(
                                    http("Benchmark")
                                            .post("/benchmark")
                                            .headers(COMMON_HEADERS)
                                            .body(StringBody(requestBody("request.json")))
                                            .check(errorChecks)
                            )
                    );

    ScenarioBuilder scenarioRequest = scenario("Benchmark")
            .exec(benchmarkRequest);

    {
        setUp(
                scenarioRequest
                        .injectOpen(rampUsers(users())
                                .during(durationInSecs())
                        )
        ).protocols(httpProtocol);
    }

    public BenchmarkSimulation() throws IOException {
    }
}
