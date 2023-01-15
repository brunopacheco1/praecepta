package io.com.github.brunopacheco1.praecepta.camunda.benchmark.service;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;

import static org.assertj.core.api.Assertions.assertThat;

import io.com.github.brunopacheco1.praecepta.camunda.benchmark.dto.BenchmarkInput;

public class BenchmarkEngineTest {

    private static final Arbitrary<String> INPUT_A = Arbitraries.of("A", "B", null);
    private static final Arbitrary<String> INPUT_B = Arbitraries.of("A", "B", null);
    private static final Arbitrary<String> INPUT_C = Arbitraries.of("A", "B", null);
    private static final Arbitrary<String> INPUT_D = Arbitraries.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "AA", "AB", "AC", "AD",
            "AE", "AF", "AG", "AH", "AI", null);
    private static final Arbitrary<String> INPUT_E = Arbitraries.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            null);
    private static final Arbitrary<String> INPUT_F = Arbitraries.of("YES", "NO", null);
    private static final Arbitrary<String> INPUT_G = Arbitraries.of("A", "B", null);
    private static final Arbitrary<String> INPUT_H = Arbitraries.of("A", "B", null);
    private static final Arbitrary<String> INPUT_I = Arbitraries.of("YES", "NO", null);
    private static final Arbitrary<String> INPUT_J = Arbitraries.of("YES", "NO", null);
    private static final Arbitrary<String> INPUT_K = Arbitraries.of("A", "B", "C", "D", "E", "F", "G", null);
    private static final Arbitrary<String> INPUT_L = Arbitraries.of("YES", "NO", null);
    private static final Arbitrary<String> INPUT_M = Arbitraries.of("A", null);

    @Provide
    private static Arbitrary<BenchmarkInput> inputs() {
        return Builders.withBuilder(BenchmarkInput::builder)
                .use(INPUT_A).in((builder, inputA) -> builder.inputA(inputA))
                .use(INPUT_B).in((builder, inputB) -> builder.inputB(inputB))
                .use(INPUT_C).in((builder, inputC) -> builder.inputC(inputC))
                .use(INPUT_D).in((builder, inputD) -> builder.inputD(inputD))
                .use(INPUT_E).in((builder, inputE) -> builder.inputE(inputE))
                .use(INPUT_F).in((builder, inputF) -> builder.inputF(inputF))
                .use(INPUT_G).in((builder, inputG) -> builder.inputG(inputG))
                .use(INPUT_H).in((builder, inputH) -> builder.inputH(inputH))
                .use(INPUT_I).in((builder, inputI) -> builder.inputI(inputI))
                .use(INPUT_J).in((builder, inputJ) -> builder.inputJ(inputJ))
                .use(INPUT_K).in((builder, inputK) -> builder.inputK(inputK))
                .use(INPUT_L).in((builder, inputL) -> builder.inputL(inputL))
                .use(INPUT_M).in((builder, inputM) -> builder.inputM(inputM))
                .build((builder) -> builder.build());
    }

    private BenchmarkEngine benchmarkEngine;

    @BeforeProperty
    void beforeTry() {
        benchmarkEngine = new BenchmarkEngine("/decision_table.dmn");
    }

    @Property(generation = GenerationMode.RANDOMIZED, tries = 1000)
    void test(@ForAll("inputs") BenchmarkInput input) {
        var ouput = benchmarkEngine.evaluate(input);
        assertThat(ouput).isNotNull();
    }
}
