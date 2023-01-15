package io.com.github.brunopacheco1.praecepta.camunda.benchmark.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.BenchmarkInput;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class BenchmarkControllerTest {

    @LocalServerPort
    private int port;

    @Test
    void run_benchmark() {
        given()
                .port(port)
                .contentType("application/json")
                .body(BenchmarkInput.builder()
                        .inputB("B")
                        .inputF("NO")
                        .inputI("NO")
                        .inputJ("YES")
                        .build())
                .post("/api/v1/benchmark")
                .then()
                .statusCode(200)
                .body(
                        "outputA", equalTo("A"),
                        "outputB", equalTo("NO"),
                        "ruleId", equalTo("1"));
    }
}
