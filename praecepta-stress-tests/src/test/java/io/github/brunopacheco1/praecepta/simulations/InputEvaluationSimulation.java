package io.github.brunopacheco1.praecepta.simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;

import java.io.IOException;

import static com.pictet.dxb.tax.simulations.SimulationProperties.durationInSecs;
import static com.pictet.dxb.tax.simulations.SimulationProperties.users;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;

public class InputEvaluationSimulation extends BaseSimulation {

    ChainBuilder taxRatingSmallRequest =
            during(10)
                    .on(
                            exec(
                                    http("Tax Rating")
                                            .post("/api/v3/tax")
                                            .headers(COMMON_HEADERS)
                                            .body(StringBody(requestBody("small-request.json")))
                                            .check(errorChecks)
                            )
                    );

    ChainBuilder taxRatingBigRequest =
            during(10)
                    .on(
                            exec(
                                    http("Tax Rating")
                                            .post("/api/v3/tax")
                                            .headers(COMMON_HEADERS)
                                            .body(StringBody(requestBody("big-request.json")))
                                            .check(errorChecks)
                            )
                    );

    ScenarioBuilder scenarioSmallRequest = scenario("Tax Rating - Small Request")
            .exec(taxRatingSmallRequest);

    ScenarioBuilder scenarioBigRequest = scenario("Tax Rating - Big Request")
            .exec(taxRatingBigRequest);

    {
        setUp(
                scenarioSmallRequest
                        .injectOpen(rampUsers(users())
                                .during(durationInSecs())
                        ),
                scenarioBigRequest
                        .injectOpen(rampUsers(users())
                                .during(durationInSecs())
                        )
        ).protocols(httpProtocol);
    }

    public TaxRatingSimulation() throws IOException {
    }
}
