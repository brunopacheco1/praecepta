/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.camunda.dmn.DmnEngine;
import org.camunda.dmn.parser.ParsedDmn;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;

/**
 * A decision engine that evaluates one decision of a decision requirement graph: {@value #DECISION} of the model
 * {@value #MODEL}, which requires a decision table that requires another decision table. Every engine reads the
 * same DMN document and gets the same input data.
 */
public sealed interface GraphContender permits GraphContender.Praecepta, GraphContender.Camunda, GraphContender.Drools {

    /** The model, on the class path. */
    String MODEL = "/graphs/credit.dmn";
    /** The decision the benchmark evaluates: a literal expression over a table that reads another table. */
    String DECISION = "MonthlyLimit";
    String AGE = "Age";
    String INCOME = "Income";

    /** The value of {@link #DECISION} for input data {@link #AGE} and {@link #INCOME}. */
    BigDecimal evaluate(Map<String, Object> inputData);

    static GraphContender create(String engine) {
        var dmn = model();
        return switch (engine) {
            case "praecepta" -> new Praecepta(dmn);
            case "camunda" -> new Camunda(dmn);
            case "drools" -> new Drools(dmn);
            default -> throw new IllegalArgumentException("Unknown engine: " + engine);
        };
    }

    /** The DMN document every engine reads. */
    static String model() {
        try (var stream = GraphContender.class.getResourceAsStream(MODEL)) {
            if (stream == null) {
                throw new IllegalStateException("No " + MODEL + " on the class path");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Input data that reach every rule of both tables: ages from 18 to 80, incomes from 0 to 200,000. */
    static List<Map<String, Object>> workload(int size, long seed) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        var inputs = new ArrayList<Map<String, Object>>(size);
        for (int i = 0; i < size; i++) {
            inputs.add(Map.of(AGE, BigDecimal.valueOf(random.nextInt(18, 81)), INCOME, BigDecimal.valueOf(random.nextInt(0, 200_001))));
        }
        return inputs;
    }

    /**
     * The model's answer, computed in plain Java: what every engine is checked against.
     *
     * @return the monthly limit, with FEEL's 34 digits
     */
    static BigDecimal expected(Map<String, Object> inputData) {
        int age = ((BigDecimal) inputData.get(AGE)).intValueExact();
        var income = (BigDecimal) inputData.get(INCOME);
        String risk = age < 25 ? "High" : age < 40 ? "Medium" : age < 65 ? "Low" : "Medium";
        BigDecimal limit;
        if (income.compareTo(BigDecimal.valueOf(12_000)) < 0) {
            limit = BigDecimal.ZERO;
        } else if (risk.equals("High")) {
            limit = income.multiply(new BigDecimal(income.compareTo(BigDecimal.valueOf(60_000)) < 0 ? "0.25" : "0.5"));
        } else if (risk.equals("Medium")) {
            limit = income.compareTo(BigDecimal.valueOf(100_000)) <= 0 ? income : income.multiply(new BigDecimal("1.5"));
        } else {
            limit = income.multiply(BigDecimal.valueOf(2));
        }
        return limit.divide(BigDecimal.valueOf(12), MathContext.DECIMAL128);
    }

    /**
     * Fails unless the engine gives {@link #expected} for every input of the workload. Engines round a quotient that
     * does not end to different numbers of digits, so values are compared to 15 significant digits.
     */
    static void assertAgreement(List<Map<String, Object>> workload, GraphContender contender) {
        var precision = new MathContext(15);
        for (var input : workload) {
            var actual = contender.evaluate(input);
            var expected = expected(input);
            if (actual == null || actual.round(precision).compareTo(expected.round(precision)) != 0) {
                throw new IllegalStateException("%s gives %s for %s, not %s"
                        .formatted(contender.getClass().getSimpleName(), actual, input, expected));
            }
        }
    }

    /** A number as any of the engines returns it. */
    private static BigDecimal number(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal decimal -> decimal;
            default -> new BigDecimal(value.toString());
        };
    }

    /** Praecepta through its public API. */
    final class Praecepta implements GraphContender {
        private final DecisionGraph graph;

        Praecepta(String dmn) {
            graph = DecisionGraph.read(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public BigDecimal evaluate(Map<String, Object> inputData) {
            return number(graph.evaluate(DECISION, inputData));
        }
    }

    /** Camunda 8's DMN engine (dmn-scala with feel-scala). */
    final class Camunda implements GraphContender {
        private final DmnEngine engine;
        private final ParsedDmn dmn;

        Camunda(String document) {
            engine = new DmnEngine.Builder().build();
            var parsed = engine.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
            if (parsed.isLeft()) {
                throw new IllegalStateException("Camunda could not parse the DMN model: " + parsed.swap().toOption().get());
            }
            dmn = parsed.toOption().get();
        }

        @Override
        public BigDecimal evaluate(Map<String, Object> inputData) {
            var result = engine.evalByName(dmn, DECISION, inputData);
            if (result.isLeft()) {
                throw new IllegalStateException("Camunda failed to evaluate " + inputData + ": " + result.swap().toOption().get());
            }
            return number(result.toOption().get().value());
        }
    }

    /** Drools (Apache KIE) DMN, with defaults. */
    final class Drools implements GraphContender {
        private final DMNRuntime runtime;
        private final DMNModel model;

        Drools(String dmn) {
            var resource = ResourceFactory.newReaderResource(new StringReader(dmn), "UTF-8");
            resource.setSourcePath("credit.dmn");
            runtime = DMNRuntimeBuilder.fromDefaults()
                    .buildConfiguration()
                    .fromResources(List.of(resource))
                    .getOrElseThrow(cause -> new IllegalStateException("Drools could not compile the DMN model", cause));
            model = runtime.getModels().getFirst();
            if (model.hasErrors()) {
                throw new IllegalStateException("Drools DMN model has errors: " + model.getMessages());
            }
        }

        @Override
        public BigDecimal evaluate(Map<String, Object> inputData) {
            var context = runtime.newContext();
            inputData.forEach(context::set);
            return number(runtime.evaluateByName(model, context, DECISION).getDecisionResultByName(DECISION).getResult());
        }
    }
}
