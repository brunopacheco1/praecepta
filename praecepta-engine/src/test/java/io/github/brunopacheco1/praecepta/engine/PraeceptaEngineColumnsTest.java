/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/** How the engine works out its columns, their types and their input value lists when it is built. */
class PraeceptaEngineColumnsTest {

    record Order(String country, String segment) {
    }

    //   country   segment   → discount
    // 1 PT        -           10
    // 2 -         "SMB"       5
    private static final List<Praeceptum> RULES = List.of(
            rule(1, Map.of("country", "PT", "segment", "-"), "10"),
            rule(2, Map.of("country", "-", "segment", "\"SMB\""), "5"));

    @Test
    void rules_may_test_only_bound_columns() {
        var builder = byCountry().rules(RULES);
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 2 tests column 'segment', which has no declared input");
    }

    @Test
    void a_wildcard_on_an_unbound_column_is_not_a_test() {
        var engine = byCountry().rules(List.of(RULES.getFirst())).build();
        Assertions.assertThat(engine.inputs()).containsExactly("country");
        Assertions.assertThat(engine.evaluate(new Order("PT", "SMB"))).containsExactly("10");
    }

    @Test
    void input_values_need_a_bound_column() {
        var builder = byCountry().rules(List.of(RULES.getFirst())).allowedValues("region", List.of("EU"));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Input values declared for unknown column 'region'");
    }

    @Test
    void unbound_columns_in_rules_are_reported_before_unknown_input_value_columns() {
        var builder = byCountry().rules(RULES).allowedValues("region", List.of("EU"));
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Rule 2 tests column 'segment', which has no declared input");
    }

    @Test
    void map_inputs_take_the_rules_columns_then_those_with_input_values() {
        var engine = byMap()
                .allowedValues("region", List.of("EU"))
                .columnType("age", ColumnType.NUMBER)
                .build();
        Assertions.assertThat(engine.inputs()).containsExactly("country", "segment", "region", "age");
        Assertions.assertThat(engine.evaluate(Map.of("country", "PT", "region", "EU", "age", "40"))).containsExactly("10");
    }

    @Test
    void string_input_values_must_be_a_list_of_values() {
        var builder = byMap().allowedValues("country", "not(\"FR\")");
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessage("Input values of string column 'country' must be a list of values");
    }

    @Test
    void invalid_input_values_name_the_column() {
        var builder = byMap().columnType("age", ColumnType.NUMBER).allowedValues("age", "[120..0]");
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageStartingWith("Input values of column 'age': ")
                .hasMessageContaining("interval starts after it ends");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static PraeceptaEngine.Builder<Order, String> byCountry() {
        return PraeceptaEngine.<Order, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .input("country", Order::country)
                .output(output -> output.values().get("discount"));
    }

    private static PraeceptaEngine.Builder<Map<String, String>, String> byMap() {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("discount"))
                .rules(RULES);
    }

    private static Praeceptum rule(int id, Map<String, String> cells, String discount) {
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("discount", discount)));
    }
}
