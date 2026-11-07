/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/** The int-coded input path: STRING columns bound to enums. */
class EnumInputTest {

    enum Country { PT, ES, FR, DE }

    enum Segment {
        SMALL_BUSINESS("small business"), CONSUMER("consumer");

        private final String value;

        Segment(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    record Order(Country country, Segment segment) {
    }

    //   country     segment             → discount
    // 1 PT,ES       "small business"      10
    // 2 not("FR")   -                     5
    // 3 null        -                     0
    private static final List<Praeceptum> RULES = List.of(
            rule(1, "PT,ES", "\"small business\"", "10"),
            rule(2, "not(\"FR\")", "-", "5"),
            rule(3, "null", "", "0"));

    @Test
    void enum_bindings_match_like_string_bindings() {
        var byEnum = PraeceptaEngine.<Order, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .enumInput("country", Country.class, Order::country)
                .enumInput("segment", Segment.class, Segment::value, Order::segment)
                .output(output -> output.values().get("discount"))
                .rules(RULES)
                .build();
        var byString = PraeceptaEngine.<Order, String>builder()
                .hitPolicy(HitPolicy.COLLECT)
                .input("country", order -> order.country() == null ? null : order.country().name())
                .input("segment", order -> order.segment() == null ? null : order.segment().value())
                .output(output -> output.values().get("discount"))
                .rules(RULES)
                .build();
        for (var country : withNull(Country.values())) {
            for (var segment : withNull(Segment.values())) {
                var order = new Order(country, segment);
                Assertions.assertThat(byEnum.evaluate(order)).as("%s", order).isEqualTo(byString.evaluate(order));
            }
        }
        Assertions.assertThat(byEnum.evaluate(new Order(Country.PT, Segment.SMALL_BUSINESS))).containsExactly("10", "5");
        Assertions.assertThat(byEnum.evaluate(new Order(null, Segment.CONSUMER))).containsExactly("5", "0");
    }

    @Test
    void the_enum_is_the_columns_value_list_so_gaps_are_reported() {
        var engine = PraeceptaEngine.<Order, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .enumInput("country", Country.class, Order::country)
                .enumInput("segment", Segment.class, Segment::value, Order::segment)
                .output(output -> output.values().get("discount"))
                .rules(RULES)
                .build();
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP))
                .singleElement()
                .satisfies(gap -> Assertions.assertThat(gap.detail()).startsWith("country = \"FR\""));
    }

    @Test
    void enum_values_must_be_within_declared_input_values() {
        Assertions.assertThatThrownBy(() -> PraeceptaEngine.<Order, String>builder()
                        .hitPolicy(HitPolicy.FIRST)
                        .enumInput("country", Country.class, Order::country)
                        .allowedValues("country", List.of("PT", "ES", "FR"))
                        .enumInput("segment", Segment.class, Segment::value, Order::segment)
                        .output(output -> output.values().get("discount"))
                        .rules(RULES)
                        .build())
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Column 'country': enum value 'DE' is not one of the column's input values");
    }

    @SafeVarargs
    private static <E> List<E> withNull(E... values) {
        var list = new java.util.ArrayList<E>();
        for (var value : values) {
            list.add(value);
        }
        list.add(null);
        return list;
    }

    private static Praeceptum rule(int id, String country, String segment, String discount) {
        var cells = new HashMap<String, String>();
        cells.put("country", country);
        cells.put("segment", segment);
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("discount", discount)));
    }
}
