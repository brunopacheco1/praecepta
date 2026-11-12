/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Output entries that compute their value (#71), as {@code Symptom + " is listed"}: parsed and type
 * checked when the engine is built, and evaluated for the rules that match.
 */
class OutputExpressionsTest {

    record Order(String country, BigDecimal price, BigDecimal discount, LocalDate day) {
    }

    @Test
    void expressions_join_strings_and_compute_numbers_from_the_inputs() {
        var engine = byColumn(HitPolicy.COLLECT, List.of(
                rule(1, Map.of("country", "PT"), Map.of(), Map.of("text", "country + \" is home\"")),
                rule(2, Map.of(), Map.of("text", "net"), Map.of("total", "(price - discount) * 1.2")),
                rule(3, Map.of(), Map.of("text", "share"), Map.of("total", "price / 4"))));
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal("10.50"), new BigDecimal("0.50"), null))).containsExactly(
                values("text", "PT is home"),
                values("text", "net", "total", "12"),
                values("text", "share", "total", "2.625"));
        // a missing input makes the output missing
        Assertions.assertThat(engine.evaluate(new Order("ES", new BigDecimal(8), null, null))).containsExactly(
                values("text", "net", "total", null),
                values("text", "share", "total", "2"));
    }

    @Test
    void expressions_are_evaluated_only_for_the_rules_that_match() {
        var reads = new AtomicInteger();
        var engine = PraeceptaEngine.<Order, Map<String, String>>builder()
                .hitPolicy(HitPolicy.FIRST)
                .input("country", Order::country)
                .numberInput("price", order -> {
                    reads.incrementAndGet();
                    return order.price();
                })
                .output(PraeceptaOutput::values)
                .rules(List.of(rule(1, Map.of("country", "PT"), Map.of(), Map.of("total", "price * 2")),
                        rule(2, Map.of(), Map.of("total", "0"), Map.of())))
                .build();
        reads.set(0);
        Assertions.assertThat(engine.evaluate(new Order("ES", BigDecimal.ONE, null, null))).containsExactly(values("total", "0"));
        Assertions.assertThat(reads).as("the index reads the column once; the expression of rule 1 is not evaluated").hasValue(1);
        Assertions.assertThat(engine.evaluate(new Order("PT", BigDecimal.ONE, null, null))).containsExactly(values("total", "2"));
    }

    @Test
    void map_based_inputs_feed_expressions_the_values_their_columns_read() {
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("price", ColumnType.NUMBER)
                .columnType("country", ColumnType.STRING)
                .output(PraeceptaOutput::values)
                .rules(List.of(rule(1, Map.of(), Map.of(), Map.of("total", "price * 3", "text", "country + \"!\""))))
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("price", "0.50", "country", "PT"))).containsExactly(values("total", "1.5", "text", "PT!"));
    }

    @Test
    void any_compares_the_evaluated_outputs() {
        var engine = byColumn(HitPolicy.ANY, List.of(
                rule(1, Map.of(), Map.of(), Map.of("total", "price * 2")),
                rule(2, Map.of("country", "PT"), Map.of(), Map.of("total", "price + price"))));
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal(3), null, null))).containsExactly(values("total", "6"));
        var other = byColumn(HitPolicy.ANY, List.of(
                rule(1, Map.of(), Map.of(), Map.of("total", "price * 2")),
                rule(2, Map.of("country", "PT"), Map.of(), Map.of("total", "price * 3"))));
        var three = new Order("PT", new BigDecimal(3), null, null);
        Assertions.assertThatThrownBy(() -> other.evaluate(three))
                .isInstanceOf(HitPolicyViolationException.class);
        Assertions.assertThat(other.evaluate(new Order("PT", BigDecimal.ZERO, null, null))).as("both give 0").containsExactly(values("total", "0"));
    }

    @Test
    void any_compares_numbers_by_value_however_the_rules_write_them() {
        // a literal keeps its scale, as does an output naming an input; a computed number has no trailing zeros
        var engine = byColumn(HitPolicy.ANY, List.of(
                rule(1, Map.of(), Map.of("total", "1.50"), Map.of()),
                rule(2, Map.of(), Map.of(), Map.of("total", "price * 1")),
                new Praeceptum(3, new PraeceptaInput(Map.of()), new PraeceptaOutput(Map.of(), Map.of("total", "price")))));
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal("1.5"), null, null))).containsExactly(values("total", "1.50"));
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal("1.500"), null, null))).containsExactly(values("total", "1.50"));
        var otherPrice = new Order("PT", new BigDecimal("1.6"), null, null);
        Assertions.assertThatThrownBy(() -> engine.evaluate(otherPrice))
                .isInstanceOf(HitPolicyViolationException.class);

        // outputs that read no input: a table's number output is compared by value, an untyped output as text
        var rules = List.of(rule(1, Map.of(), Map.of("total", "1.50"), Map.of()), rule(2, Map.of(), Map.of("total", "1.5"), Map.of()));
        var table = new DecisionTable("t", HitPolicy.ANY, null, List.of(new DecisionTable.InputColumn("country", ColumnType.STRING)),
                List.of(new DecisionTable.OutputColumn("total", ColumnType.NUMBER)), rules);
        var typed = PraeceptaEngine.<Order, Map<String, String>>builder()
                .table(table)
                .input("country", Order::country)
                .output(PraeceptaOutput::values)
                .build();
        Assertions.assertThat(typed.validationReport().isEmpty()).isTrue();
        var noPrice = new Order("PT", null, null, null);
        Assertions.assertThat(typed.evaluate(noPrice)).containsExactly(values("total", "1.50"));
        var untyped = byColumn(HitPolicy.ANY, rules);
        Assertions.assertThat(untyped.validationReport().issues(ValidationReport.Kind.CONFLICTING_OUTPUTS)).hasSize(1);
        Assertions.assertThatThrownBy(() -> untyped.evaluate(noPrice))
                .isInstanceOf(HitPolicyViolationException.class);
    }

    @Test
    void an_aggregation_gets_a_computed_output_that_is_missing_as_null() {
        // FEEL's sum is null for a list that holds null (DMN 1.5, 10.3.4.4): the engine gives the missing output itself
        var engine = PraeceptaEngine.<Order, Optional<BigDecimal>>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .aggregation(AggregationOperator.SUM)
                .numberInput("price", Order::price)
                .numberInput("discount", Order::discount)
                .output(new OutputStrategy<>() {
                    @Override
                    public Optional<BigDecimal> transform(PraeceptaOutput output) {
                        return Optional.ofNullable(output.values().get("fee")).map(BigDecimal::new);
                    }

                    @Override
                    public Optional<BigDecimal> sum(Optional<BigDecimal> a, Optional<BigDecimal> b) {
                        return Optional.of(a.orElseThrow().add(b.orElseThrow()));
                    }
                })
                .rules(List.of(rule(1, Map.of(), Map.of("fee", "5"), Map.of()), rule(2, Map.of(), Map.of(), Map.of("fee", "price * 0.1")),
                        rule(3, Map.of(), Map.of(), Map.of("fee", "price / discount"))))
                .build();
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal(30), new BigDecimal(2), null)))
                .containsExactly(Optional.of(new BigDecimal("23")));
        // no discount: price / discount is missing, and so is the sum
        Assertions.assertThat(engine.evaluate(new Order("PT", new BigDecimal(30), null, null))).containsExactly(Optional.empty());
        // no price: two outputs are missing
        Assertions.assertThat(engine.evaluate(new Order("PT", null, null, null))).containsExactly(Optional.empty());
    }

    @Test
    void priority_lists_find_number_outputs_by_value() {
        var rules = List.of(rule(1, Map.of(), Map.of("rate", "1.50"), Map.of()), rule(2, Map.of(), Map.of("rate", "10"), Map.of()));
        var table = new DecisionTable("t", HitPolicy.PRIORITY, null, List.of(new DecisionTable.InputColumn("country", ColumnType.STRING)),
                List.of(new DecisionTable.OutputColumn("rate", ColumnType.NUMBER, List.of("10.0", "1.5"))), rules);
        var engine = PraeceptaEngine.<Order, Map<String, String>>builder()
                .table(table)
                .input("country", Order::country)
                .output(PraeceptaOutput::values)
                .build();
        // the output keeps its text; 1.50 is the list's 1.5, and 10 its 10.0, which ranks first
        Assertions.assertThat(engine.evaluate(new Order("PT", null, null, null))).containsExactly(values("rate", "10"));
    }

    @Test
    void expressions_are_checked_when_the_engine_is_built() {
        var mixed = List.of(rule(1, Map.of(), Map.of(), Map.of("x", "country + price")));
        Assertions.assertThatThrownBy(() -> byColumn(HitPolicy.FIRST, mixed))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1, output 'x': + combines a string and a number, which gives null in FEEL");
        var date = List.of(rule(1, Map.of(), Map.of(), Map.of("x", "day + 1")));
        Assertions.assertThatThrownBy(() -> byColumn(HitPolicy.FIRST, date))
                .hasMessage("Rule 1, output 'x': + combines a date and a number, which gives null in FEEL");
        var unknown = List.of(rule(1, Map.of(), Map.of(), Map.of("x", "tax * 2")));
        Assertions.assertThatThrownBy(() -> byColumn(HitPolicy.FIRST, unknown))
                .hasMessageStartingWith("Rule 1: output 'x' names 'tax', which is not an input column");
        var call = List.of(rule(1, Map.of(), Map.of(), Map.of("x", "abs(price)")));
        Assertions.assertThatThrownBy(() -> byColumn(HitPolicy.FIRST, call))
                .hasMessage("Rule 1, output 'x': 'abs(price)' is not a supported output expression: "
                        + "function calls such as abs(...) are not supported");
    }

    @Test
    void an_output_with_a_priority_list_cannot_be_an_expression() {
        var builder = PraeceptaEngine.<Order, Map<String, String>>builder()
                .hitPolicy(HitPolicy.PRIORITY)
                .input("country", Order::country)
                .outputPriority("tier", List.of("gold", "silver"))
                .output(PraeceptaOutput::values)
                .rules(List.of(rule(1, Map.of(), Map.of(), Map.of("tier", "country + \"-gold\""))));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 1: output 'tier' is the expression country + \"-gold\", so it has no place in its priority list");
    }

    @Test
    void dmn_reads_expressions_checks_their_types_and_writes_them_back_unchanged() {
        var xml = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="n">
                  <decision id="p" name="p"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="string"><text>Symptom</text></inputExpression></input>
                    <input><inputExpression typeRef="number"><text>Days</text></inputExpression></input>
                    <output name="text" typeRef="string"/>
                    <output name="hours" typeRef="number"/>
                    <rule><inputEntry><text>"cough"</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>Symptom + " is in the list"</text></outputEntry><outputEntry><text>Days*24</text></outputEntry></rule>
                    <rule><inputEntry><text>-</text></inputEntry><inputEntry><text>-</text></inputEntry>
                      <outputEntry><text>-Days / 2</text></outputEntry><outputEntry><text>0</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""";
        var table = read(xml);
        Assertions.assertThat(table.rules().getFirst().output())
                .isEqualTo(new PraeceptaOutput(Map.of(), Map.of(), Map.of("text", "Symptom + \" is in the list\"", "hours", "Days*24")));
        var written = DmnWriter.write(table);
        Assertions.assertThat(written).contains("<text>Symptom + \" is in the list\"</text>", "<text>Days*24</text>", "<text>-Days / 2</text>");
        Assertions.assertThat(DmnWriter.write(read(written))).isEqualTo(written);

        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("Symptom", "cough", "Days", "2")))
                .containsExactly(values("text", "cough is in the list", "hours", "48"));
        Assertions.assertThat(engine.evaluate(Map.of("Symptom", "fever", "Days", "3"))).containsExactly(values("text", "-1.5", "hours", "0"));

        var stringIntoNumber = xml.replace("Days*24", "Symptom + \"h\"");
        Assertions.assertThatThrownBy(() -> read(stringIntoNumber))
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Decision 'p', rule 1, output 'hours': 'Symptom + \"h\"' gives a string, but the output is a number");
        var mixed = xml.replace("Days*24", "Days + Symptom");
        Assertions.assertThatThrownBy(() -> read(mixed))
                .hasMessage("Decision 'p', rule 1, output 'hours': 'Days + Symptom' + combines a number and a string, which gives null in FEEL");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static PraeceptaEngine<Order, Map<String, String>> byColumn(HitPolicy policy, List<Praeceptum> rules) {
        return PraeceptaEngine.<Order, Map<String, String>>builder()
                .hitPolicy(policy)
                .input("country", Order::country)
                .numberInput("price", Order::price)
                .numberInput("discount", Order::discount)
                .dateInput("day", Order::day)
                .output(PraeceptaOutput::values)
                .rules(rules)
                .build();
    }

    private static Praeceptum rule(int id, Map<String, String> cells, Map<String, String> values, Map<String, String> expressions) {
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(values, Map.of(), expressions));
    }

    /** Outputs as a map that may hold nulls: key, value, key, value... */
    private static Map<String, String> values(String... keysAndValues) {
        var values = new HashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return values;
    }

    private static DecisionTable read(String xml) {
        return DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
