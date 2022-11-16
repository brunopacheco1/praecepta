package com.github.brunopacheco1.praecepta;

import java.util.HashMap;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PraeceptaEngineTest {

    private static final Integer OUTPUT_1_INDEX = 1;
    private static final Integer OUTPUT_2_INDEX = 2;
    private static final String INPUT_1 = "INPUT_1";
    private static final String INPUT_2 = "INPUT_2";
    private static final String OUTPUT_1 = "OUTPUT_1";
    private static final String OUTPUT_2 = "OUTPUT_2";

    private final InputStrategy<DummyInput> inputStrategy = (DummyInput input) -> {
        var values = new HashMap<String, String>();
        values.put("inputOne", input.inputOne());
        values.put("inputTwo", input.inputTwo());
        return new Input(values);
    };

    private final OutputStrategy<DummyOutput> outputStrategy = (Output output) -> {
        return new DummyOutput(output.values().get("output"));
    };

    @Test
    void throws_an_exception_if_no_output_is_returned() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(INPUT_1, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.UNIQUE, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var input = new DummyInput(null, null);
        Assertions.assertThatThrownBy(() -> evaluator.evaluate(input)).isInstanceOf(MissingOutputException.class);
    }

    @Test
    void fails_when_there_are_praecepta_that_will_never_be_reached() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(null, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.UNIQUE, inputStrategy, outputStrategy);

        var praecepta = List.of(rule1, rule2);
        Assertions.assertThatThrownBy(() -> evaluator.register(praecepta)).isInstanceOf(InvalidPraeceptaException.class);
    }

    @Test
    void does_not_fail_when_there_are_overlapping_praecepta_if_hit_policy_is_collect() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(null, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.COLLECT, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var actual = evaluator.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_1),
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_rule1_as_it_has_bigger_priority() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(INPUT_1, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.UNIQUE, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var actual = evaluator.evaluate(new DummyInput(INPUT_1, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_1));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_rule1_and_rule2() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(INPUT_1, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.COLLECT, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var actual = evaluator.evaluate(new DummyInput(INPUT_1, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_1),
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_rule2_as_it_is_the_match() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(INPUT_1, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.UNIQUE, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var actual = evaluator.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_rule2_as_it_is_the_match_also_when_evaluating_all_praecepta() {
        var rule1 = new Praeceptum(OUTPUT_1_INDEX, ruleInput(INPUT_1, null), ruleOutput(OUTPUT_1));

        var rule2 = new Praeceptum(OUTPUT_2_INDEX, ruleInput(null, INPUT_2), ruleOutput(OUTPUT_2));

        var evaluator = new PraeceptaEngine<>(HitPolicy.UNIQUE, inputStrategy, outputStrategy);
        evaluator.register(List.of(rule1, rule2));

        var actual = evaluator.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private Input ruleInput(String inputOne, String inputTwo) {
        var values = new HashMap<String, String>();
        values.put("inputOne", inputOne);
        values.put("inputTwo", inputTwo);
        return new Input(values);
    }

    private Output ruleOutput(String output) {
        var values = new HashMap<String, String>();
        values.put("output", output);
        return new Output(values);
    }

    private static record DummyInput(String inputOne, String inputTwo) {
    }

    private static record DummyOutput(String output) {
    }
}
