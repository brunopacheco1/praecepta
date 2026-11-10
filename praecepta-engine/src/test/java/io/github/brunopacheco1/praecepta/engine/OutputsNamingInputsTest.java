/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Outputs that name an input, as DMN's output entry {@code a} returns the input {@code a}: the engine
 * fills them in from each evaluated input, with the text of the input's value.
 */
class OutputsNamingInputsTest {

    enum Plan { BASIC, GOLD }

    record Customer(String country, Integer age, Plan plan, LocalTime since) {
    }

    @Test
    void outputs_take_the_text_of_the_named_inputs_values() {
        //   country  → where     age   plan  since
        // 1 "PT"       "home"    age   plan  since
        // 2 -          country   -     -     -
        var engine = PraeceptaEngine.<Customer, Map<String, String>>builder()
                .hitPolicy(HitPolicy.FIRST)
                .input("country", Customer::country)
                .numberInput("age", Customer::age)
                .enumInput("plan", Plan.class, Plan::name, Customer::plan)
                .timeInput("since", Customer::since)
                .output(PraeceptaOutput::values)
                .rules(List.of(
                        rule(1, Map.of("country", "\"PT\""), Map.of("where", "home"), Map.of("age", "age", "plan", "plan", "since", "since")),
                        rule(2, Map.of(), Map.of(), Map.of("where", "country"))))
                .build();

        Assertions.assertThat(engine.evaluate(new Customer("PT", 30, Plan.GOLD, LocalTime.of(8, 30)))).containsExactly(
                Map.of("where", "home", "age", "30", "plan", "GOLD", "since", "08:30:00"));
        // a missing input gives a missing output
        var missing = new HashMap<String, String>();
        missing.put("where", "home");
        missing.put("age", null);
        missing.put("plan", null);
        missing.put("since", null);
        Assertions.assertThat(engine.evaluate(new Customer("PT", null, null, null))).containsExactly(missing);
        // a rule naming no other output leaves them out, as a rule whose outputs are values would
        var where = new HashMap<String, String>();
        where.put("where", "ES");
        Assertions.assertThat(engine.evaluate(new Customer("ES", 30, Plan.BASIC, null))).containsExactly(where);
    }

    @Test
    void map_based_inputs_are_filled_in_as_the_columns_read_them() {
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .inputs(PraeceptaInput::new)
                .columnType("amount", ColumnType.NUMBER)
                .columnType("at", ColumnType.TIME)
                .output(PraeceptaOutput::values)
                .rules(List.of(
                        rule(1, Map.of("amount", "> 0"), Map.of(), Map.of("echo", "amount")),
                        rule(2, Map.of("at", "-"), Map.of("echo", "fixed"), Map.of()),
                        rule(3, Map.of(), Map.of(), Map.of("echo", "at"))))
                .build();
        // numbers keep the text they parse from; times gain their seconds
        Assertions.assertThat(engine.evaluate(Map.of("amount", "12.50", "at", "08:00")))
                .containsExactly(Map.of("echo", "12.50"), Map.of("echo", "fixed"), Map.of("echo", "08:00:00"));
    }

    @Test
    void any_compares_the_outputs_each_input_gives() {
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .hitPolicy(HitPolicy.ANY)
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .rules(List.of(
                        rule(1, Map.of("country", "\"PT\",\"ES\""), Map.of("country", "PT"), Map.of()),
                        rule(2, Map.of(), Map.of(), Map.of("country", "country"))))
                .build();
        // both rules give "PT" for PT, so they agree; for ES they do not
        Assertions.assertThat(engine.evaluate(Map.of("country", "PT"))).containsExactly(Map.of("country", "PT"));
        Assertions.assertThat(engine.evaluate(Map.of("country", "FR"))).containsExactly(Map.of("country", "FR"));
        var es = Map.of("country", "ES");
        Assertions.assertThatThrownBy(() -> engine.evaluate(es))
                .isInstanceOfSatisfying(HitPolicyViolationException.class,
                        e -> Assertions.assertThat(e.getRuleIds()).containsExactly(1, 2));
    }

    @Test
    void a_named_input_must_be_a_column_and_outside_priority_lists() {
        var unknown = PraeceptaEngine.<Map<String, String>, PraeceptaOutput>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .output(output -> output)
                .rules(List.of(rule(7, Map.of("country", "-"), Map.of(), Map.of("o", "segment"))));
        Assertions.assertThatThrownBy(unknown::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 7: output 'o' names 'segment', which is not an input column [country]");

        var prioritised = PraeceptaEngine.<Map<String, String>, PraeceptaOutput>builder()
                .hitPolicy(HitPolicy.PRIORITY)
                .outputPriority("o", List.of("A", "B"))
                .inputs(PraeceptaInput::new)
                .output(output -> output)
                .rules(List.of(rule(7, Map.of("country", "-"), Map.of(), Map.of("o", "country"))));
        Assertions.assertThatThrownBy(prioritised::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 7: output 'o' names input 'country', so it has no place in its priority list");
    }

    private static Praeceptum rule(int id, Map<String, String> cells, Map<String, String> values, Map<String, String> references) {
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(values, references));
    }
}
