/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * SUM, MIN and MAX over a missing output (#90). DMN 1.5 aggregates with FEEL's built-ins (8.2.11; 10.3.2.10, step
 * 3b, "DTI=FEEL(aggregation(o))"), and {@code sum}, {@code min} and {@code max} take lists of numbers, or of
 * comparable items (Table 75): null is neither, and "whenever a parameter is outside its domain, the result of the
 * built-in is null" (10.3.4, 10.3.2.16). So {@code sum([10, null])} is null, and a table whose matching rules
 * output 10 and null gives null, not 10. COUNT is {@code count}, which counts any item, null included.
 */
class MissingAggregatesTest {

    /**
     * Fees: rule 1 always gives 10, rule 2 gives {@code price / discount} when the input says so (null without a
     * discount, or with a zero one), rule 3 gives a literal null for a {@code "free"} order.
     */
    private static DecisionTable fees(AggregationOperator aggregation) {
        var rules = List.of(
                new Praeceptum(1, kind("-"), new PraeceptaOutput(Map.of("fee", "10"))),
                new Praeceptum(2, kind("\"ratio\""), new PraeceptaOutput(Map.of(), Map.of(), Map.of("fee", "price / discount"))),
                new Praeceptum(3, kind("\"free\""), new PraeceptaOutput(nullValue("fee"))));
        return new DecisionTable("Fees", HitPolicy.COLLECT, aggregation,
                List.of(new DecisionTable.InputColumn("kind", ColumnType.STRING), new DecisionTable.InputColumn("price", ColumnType.NUMBER),
                        new DecisionTable.InputColumn("discount", ColumnType.NUMBER)),
                List.of(new DecisionTable.OutputColumn("fee", ColumnType.NUMBER)), rules, true);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // kind, discount: the rules' fees  -> SUM, MIN, MAX, COUNT
            "other, 2,    10            , 10,   10,   10,   1",
            "ratio, 2,    10 and 15     , 25,   10,   15,   2",
            "ratio, null, 10 and null   , null, null, null, 2",
            "ratio, 0,    10 and null   , null, null, null, 2",
            "free,  2,    10 and null   , null, null, null, 2",
    })
    void a_missing_output_makes_sum_min_and_max_missing_but_is_counted(String kind, String discount, String fees, String sum, String min,
            String max, String count) {
        var input = new HashMap<String, String>();
        input.put("kind", kind);
        input.put("price", "30");
        input.put("discount", discount);
        var expected = Map.of(AggregationOperator.SUM, Optional.ofNullable(sum), AggregationOperator.MIN, Optional.ofNullable(min),
                AggregationOperator.MAX, Optional.ofNullable(max), AggregationOperator.COUNT, Optional.of(count));
        expected.forEach((aggregation, total) -> Assertions.assertThat(engine(fees(aggregation)).evaluate(input))
                .as("%s of %s", aggregation, fees)
                .containsExactly(total.map(BigDecimal::new)));
    }

    /** The strategy never sees a missing value: it may unwrap the outputs it combines. */
    @ParameterizedTest
    @EnumSource(names = {"SUM", "MIN", "MAX"})
    void the_strategy_combines_only_outputs_that_are_not_missing(AggregationOperator aggregation) {
        var combined = new ArrayList<BigDecimal>();
        var engine = PraeceptaEngine.<Map<String, String>, Optional<BigDecimal>>builder()
                .table(fees(aggregation))
                .inputs(PraeceptaInput::new)
                .output(new OutputStrategy<>() {
                    @Override
                    public Optional<BigDecimal> transform(PraeceptaOutput output) {
                        return Optional.ofNullable(output.values().get("fee")).map(BigDecimal::new);
                    }

                    @Override
                    public Optional<BigDecimal> sum(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        combined.add(a.orElseThrow());
                        combined.add(b.orElseThrow());
                        return Optional.of(a.get().add(b.get()));
                    }

                    @Override
                    public Optional<BigDecimal> min(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return sum(a, b).map(any -> a.get().min(b.get()));
                    }

                    @Override
                    public Optional<BigDecimal> max(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return sum(a, b).map(any -> a.get().max(b.get()));
                    }
                })
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("kind", "ratio", "price", "30"))).containsExactly(Optional.empty());
        Assertions.assertThat(engine.evaluate(Map.of("kind", "free"))).containsExactly(Optional.empty());
        Assertions.assertThat(combined).isEmpty();
        Assertions.assertThat(engine.evaluate(Map.of("kind", "ratio", "price", "30", "discount", "3"))).hasSize(1);
        Assertions.assertThat(combined).containsExactly(new BigDecimal("10"), new BigDecimal("10"));
    }

    /** The result is the matching rule's output whose value is missing, as the strategy converted it. */
    @Test
    void the_result_is_the_output_that_is_missing() {
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(fees(AggregationOperator.SUM))
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("kind", "free"))).containsExactly(nullValue("fee"));
        Assertions.assertThat(engine.evaluate(Map.of("kind", "ratio", "price", "30"))).containsExactly(nullValue("fee"));
    }

    /** A table built from rules alone has the outputs its rules give: one here, so a missing one counts as above. */
    @Test
    void a_table_built_from_rules_aggregates_its_one_output_alike() {
        var engine = PraeceptaEngine.<Map<String, String>, Optional<BigDecimal>>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .aggregation(AggregationOperator.MAX)
                .input("kind", input -> input.get("kind"))
                .output(numbers("fee"))
                .rules(List.of(new Praeceptum(1, input("kind", ""), new PraeceptaOutput(Map.of("fee", "10"))),
                        new Praeceptum(2, input("kind", "free"), new PraeceptaOutput(Map.of()))))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("kind", "other"))).containsExactly(Optional.of(BigDecimal.TEN));
        Assertions.assertThat(engine.evaluate(Map.of("kind", "free"))).as("a rule without the output: missing").containsExactly(Optional.empty());
    }

    /**
     * DMN does not aggregate several outputs (8.2.11), so with several the engine leaves what a missing one adds up
     * to to the strategy, as before.
     */
    @Test
    void with_several_outputs_the_strategy_decides_what_a_missing_one_adds_up_to() {
        var engine = PraeceptaEngine.<Map<String, String>, Optional<BigDecimal>>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .aggregation(AggregationOperator.SUM)
                .input("kind", input -> input.get("kind"))
                .output(numbers("fee"))
                .rules(List.of(new Praeceptum(1, input("kind", ""), new PraeceptaOutput(Map.of("fee", "10", "label", "base"))),
                        new Praeceptum(2, input("kind", "free"), new PraeceptaOutput(nullValue("fee")))))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("kind", "free"))).containsExactly(Optional.of(BigDecimal.TEN));
    }

    /** Numbers of one output, combined with the missing ones skipped, as a strategy may still do with several outputs. */
    private static OutputStrategy<Optional<BigDecimal>> numbers(String output) {
        return new OutputStrategy<>() {
            @Override
            public Optional<BigDecimal> transform(PraeceptaOutput values) {
                return Optional.ofNullable(values.values().get(output)).map(BigDecimal::new);
            }

            @Override
            public Optional<BigDecimal> sum(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                return a.isEmpty() ? b : b.isEmpty() ? a : Optional.of(a.get().add(b.get()));
            }

            @Override
            public Optional<BigDecimal> max(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                return a.isEmpty() ? b : b.isEmpty() ? a : Optional.of(a.get().max(b.get()));
            }
        };
    }

    /** A map-based engine whose one number output is summed, minimised, maximised or counted exactly. */
    private static PraeceptaEngine<Map<String, String>, Optional<BigDecimal>> engine(DecisionTable table) {
        return PraeceptaEngine.<Map<String, String>, Optional<BigDecimal>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(new OutputStrategy<>() {
                    @Override
                    public Optional<BigDecimal> transform(PraeceptaOutput output) {
                        return Optional.ofNullable(output.values().get("fee")).map(BigDecimal::new);
                    }

                    @Override
                    public Optional<BigDecimal> sum(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return Optional.of(a.orElseThrow().add(b.orElseThrow()));
                    }

                    @Override
                    public Optional<BigDecimal> min(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return Optional.of(a.orElseThrow().min(b.orElseThrow()));
                    }

                    @Override
                    public Optional<BigDecimal> max(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return Optional.of(a.orElseThrow().max(b.orElseThrow()));
                    }

                    @Override
                    public Optional<BigDecimal> count(int matches) {
                        return Optional.of(BigDecimal.valueOf(matches));
                    }
                })
                .build();
    }

    private static PraeceptaInput input(String column, String cell) {
        return new PraeceptaInput(Map.of(column, cell));
    }

    /** A rule's cells in {@link #fees}: one on {@code kind}, and any price and discount. */
    private static PraeceptaInput kind(String cell) {
        return new PraeceptaInput(Map.of("kind", cell, "price", "-", "discount", "-"));
    }

    private static Map<String, String> nullValue(String output) {
        var values = new HashMap<String, String>();
        values.put(output, null);
        return values;
    }
}
