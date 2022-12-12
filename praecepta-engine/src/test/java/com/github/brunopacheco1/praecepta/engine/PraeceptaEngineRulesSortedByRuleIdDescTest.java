package com.github.brunopacheco1.praecepta.engine;

import java.util.HashMap;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import com.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import com.github.brunopacheco1.praecepta.engine.exceptions.InvalidPraeceptaException;
import com.github.brunopacheco1.praecepta.engine.exceptions.MissingOutputException;

class PraeceptaEngineRulesSortedByRuleIdDescTest {

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
        return new PraeceptaInput(values);
    };

    private final OutputStrategy<DummyOutput> outputStrategy = (PraeceptaOutput output) -> {
        return new DummyOutput(output.values().get("output"));
    };

    @Test
    void throws_an_exception_if_no_output_is_returned() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(INPUT_1, null), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.PRIORITY, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var input = new DummyInput(null, null);
        Assertions.assertThatThrownBy(() -> engine.evaluate(input)).isInstanceOf(MissingOutputException.class);
    }

    @Test
    void fails_when_there_are_praecepta_that_will_never_be_reached() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, null), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.PRIORITY, inputStrategy, outputStrategy);

        var praecepta = List.of(praeceptum2, praeceptum1);
        Assertions.assertThatThrownBy(() -> engine.register(praecepta)).isInstanceOf(InvalidPraeceptaException.class);
    }

    @Test
    void does_not_fail_when_there_are_overlapping_praecepta_if_hit_policy_is_OUTPUT_ORDER() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(null, null), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.OUTPUT_ORDER, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var actual = engine.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_1),
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_praeceptum1_as_it_has_bigger_priority() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(INPUT_1, null), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.PRIORITY, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var actual = engine.evaluate(new DummyInput(INPUT_1, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_praeceptum1_and_praeceptum2() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(INPUT_1, null), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.OUTPUT_ORDER, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var actual = engine.evaluate(new DummyInput(INPUT_1, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_1),
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_praeceptum2_as_it_is_the_match() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(INPUT_1, null), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.PRIORITY, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var actual = engine.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void should_return_praeceptum2_as_it_is_the_match_also_when_evaluating_all_praecepta() {
        var praeceptum1 = new Praeceptum(OUTPUT_1_INDEX, praeceptumInput(INPUT_1, null), praeceptumOutput(OUTPUT_1));

        var praeceptum2 = new Praeceptum(OUTPUT_2_INDEX, praeceptumInput(null, INPUT_2), praeceptumOutput(OUTPUT_2));

        var engine = new PraeceptaEngine<>(HitPolicy.PRIORITY, inputStrategy, outputStrategy);
        engine.register(List.of(praeceptum1, praeceptum2));

        var actual = engine.evaluate(new DummyInput(null, INPUT_2));

        var expected = List.of(
                new DummyOutput(OUTPUT_2));

        Assertions.assertThat(actual)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private PraeceptaInput praeceptumInput(String inputOne, String inputTwo) {
        var values = new HashMap<String, String>();
        values.put("inputOne", inputOne);
        values.put("inputTwo", inputTwo);
        return new PraeceptaInput(values);
    }

    private PraeceptaOutput praeceptumOutput(String output) {
        var values = new HashMap<String, String>();
        values.put("output", output);
        return new PraeceptaOutput(values);
    }

    private static record DummyInput(String inputOne, String inputTwo) {
    }

    private static record DummyOutput(String output) {
    }
}
