/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Cells of a strict FEEL (DMN) table that compare with another input, as in {@code > limit}: the
 * index sees "any value" for them, and the engine checks them on the rules it has left.
 */
class VariableCellsTest {

    record Order(BigDecimal amount, BigDecimal limit, BigDecimal floor, String country, String home) {
    }

    //   amount              country       → decision
    // 1 > limit             -                review
    // 2 [floor..limit]      home          local
    // 3 [floor..limit]      not(home)     abroad
    private static final List<Praeceptum> RULES = List.of(
            rule(1, "> limit", "-", "review"),
            rule(2, "[floor..limit]", "home", "local"),
            rule(3, "[floor..limit]", "not(home)", "abroad"));

    private static final DecisionTable TABLE = new DecisionTable("orders", HitPolicy.FIRST, null,
            List.of(new DecisionTable.InputColumn("amount", ColumnType.NUMBER), new DecisionTable.InputColumn("country", ColumnType.STRING)),
            List.of(new DecisionTable.OutputColumn("decision", ColumnType.STRING)), RULES, true);

    @Test
    void cells_compare_with_the_named_inputs_values() {
        var engine = PraeceptaEngine.<Order, String>builder()
                .table(TABLE)
                .numberInput("amount", Order::amount)
                .input("country", Order::country)
                .numberInput("limit", Order::limit)
                .numberInput("floor", Order::floor)
                .input("home", Order::home)
                .output(output -> output.values().get("decision"))
                .build();
        Assertions.assertThat(engine.evaluate(order(120, 100, 10, "PT", "PT"))).containsExactly("review");
        Assertions.assertThat(engine.evaluate(order(50, 100, 10, "PT", "PT"))).containsExactly("local");
        Assertions.assertThat(engine.evaluate(order(50, 100, 10, "ES", "PT"))).containsExactly("abroad");
        Assertions.assertThat(engine.evaluate(order(5, 100, 10, "PT", "PT"))).isEmpty();
        // a missing bound makes the comparison unknown, so the rule does not match
        Assertions.assertThat(engine.evaluate(new Order(new BigDecimal(50), null, BigDecimal.TEN, "PT", "PT"))).isEmpty();
        // the named inputs are inputs of the engine
        Assertions.assertThat(engine.inputs()).contains("limit", "floor", "home");
    }

    @Test
    void map_based_inputs_read_a_named_input_with_the_type_of_the_column_naming_it() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(TABLE)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("decision"))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("amount", "100.0", "limit", "100", "floor", "1", "country", "PT", "home", "PT")))
                .as("numbers compare by value").containsExactly("local");
        // FEEL: a missing value equals a missing value, so not(home) fails when both are missing
        var missing = new HashMap<String, String>();
        missing.put("amount", "5");
        missing.put("limit", "9");
        missing.put("floor", "1");
        Assertions.assertThat(engine.evaluate(missing)).containsExactly("local");
    }

    @Test
    void a_named_input_needs_a_binding_and_the_type_of_the_column_naming_it() {
        var unbound = PraeceptaEngine.<Order, String>builder()
                .table(TABLE)
                .numberInput("amount", Order::amount)
                .input("country", Order::country)
                .output(output -> "x");
        Assertions.assertThatThrownBy(unbound::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, column 'amount': compares with 'limit', which has no input binding");
        var mistyped = PraeceptaEngine.<Order, String>builder()
                .table(TABLE)
                .numberInput("amount", Order::amount)
                .input("country", Order::country)
                .input("limit", order -> "100")
                .numberInput("floor", Order::floor)
                .input("home", Order::home)
                .output(output -> "x");
        Assertions.assertThatThrownBy(mistyped::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, column 'amount': compares a number with 'limit', a string");
    }

    @Test
    void the_analysis_reports_nothing_it_cannot_know() {
        // under UNIQUE, rules 2 and 3 might overlap or not depending on home; under FIRST, rule 1 does not hide them
        var unique = mapEngine(HitPolicy.UNIQUE);
        Assertions.assertThat(unique.validationReport().issues(Kind.OVERLAPPING_RULES)).isEmpty();
        var first = mapEngine(HitPolicy.FIRST);
        Assertions.assertThat(first.validationReport().issues(Kind.UNREACHABLE_RULE)).isEmpty();
        // a rule its other cells leave unreachable is still found: rule 2 tests a value no input has
        var rules = List.of(rule(1, "-", "\"PT\"", "a"), rule(2, "> limit", "\"PT\"", "b"));
        var hidden = PraeceptaEngine.<Map<String, String>, String>builder()
                .table(new DecisionTable("t", HitPolicy.FIRST, null, TABLE.inputs(), TABLE.outputs(), rules, true))
                .inputs(PraeceptaInput::new)
                .output(output -> "x")
                .build();
        Assertions.assertThat(hidden.validationReport().issues(Kind.UNREACHABLE_RULE))
                .extracting(issue -> issue.ruleIds().getFirst()).containsExactly(2);
    }

    @Test
    void dmn_reads_and_writes_cells_that_name_inputs() {
        var xml = DmnWriter.write(TABLE);
        Assertions.assertThat(xml).contains("<text>&gt; limit</text>", "<text>[floor..limit]</text>", "<text>not(home)</text>");
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThat(read.feelCells()).isTrue();
        Assertions.assertThat(read.namedInputs()).containsExactly(
                Map.entry("limit", ColumnType.NUMBER), Map.entry("floor", ColumnType.NUMBER), Map.entry("home", ColumnType.STRING));
        Assertions.assertThat(DmnWriter.write(read)).isEqualTo(xml);
    }

    private static PraeceptaEngine<Map<String, String>, String> mapEngine(HitPolicy policy) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .table(new DecisionTable("t", policy, null, TABLE.inputs(), TABLE.outputs(), RULES, true))
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("decision"))
                .build();
    }

    private static Order order(int amount, int limit, int floor, String country, String home) {
        return new Order(new BigDecimal(amount), new BigDecimal(limit), new BigDecimal(floor), country, home);
    }

    private static Praeceptum rule(int id, String amount, String country, String decision) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("amount", amount, "country", country)),
                new PraeceptaOutput(Map.of("decision", decision)));
    }
}
