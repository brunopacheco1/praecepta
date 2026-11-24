/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's arithmetic as decision tables' output entries compute it (DMN 1.5, 10.3.2.3.1 and Tables 57 to 62): decimals of
 * 34 digits, the precedence of its operators, its null for what it does not define. Output entries were a subset of
 * FEEL in 0.6 and 0.7 ({@code OutputExpression}); since 0.8 they are full FEEL, so these are the same cases, evaluated by
 * {@link FeelExpression}.
 */
class FeelArithmeticTest {

    @Test
    void products_bind_tighter_than_sums_and_operators_of_one_level_associate_left() {
        var values = Map.<String, Object>of("a", new BigDecimal(10), "b", new BigDecimal(3), "c", new BigDecimal(2));
        Assertions.assertThat((BigDecimal) FeelExpression.parse("a - b - 2 * -c / 4", values.keySet()).evaluate(values::get))
                .isEqualByComparingTo("8");
        Assertions.assertThat((BigDecimal) FeelExpression.parse("(a+b)*.5", values.keySet()).evaluate(values::get)).isEqualByComparingTo("6.5");
    }

    @Test
    void names_may_hold_spaces_and_strings_escape_with_a_backslash() {
        var names = List.of("Flu Symptom", "Credit Score");
        var expression = FeelExpression.parse("Flu Symptom + \" is \\\"in\\\" \" + Credit Score", names);
        Assertions.assertThat(expression.names()).containsExactly("Flu Symptom", "Credit Score");
        Assertions.assertThat(expression.evaluate(Map.of("Flu Symptom", "fever", "Credit Score", "high")::get)).isEqualTo("fever is \"in\" high");
    }

    @Test
    void strings_join_and_numbers_compute_as_feel_decimals() {
        Assertions.assertThat(FeelExpression.parse("Symptom + \" is in the list of Flu symptoms\"", List.of("Symptom"))
                .evaluate(Map.of("Symptom", "cough")::get)).isEqualTo("cough is in the list of Flu symptoms");
        var values = Map.<String, Object>of("price", new BigDecimal("10.5"), "discount", new BigDecimal("0.5"));
        Assertions.assertThat(FeelExpression.parse("(price - discount) * 1.2", values.keySet()).evaluate(values::get))
                .isEqualTo(new BigDecimal("12.00"));
        Assertions.assertThat(number("1 / 3")).as("34 significant digits").isEqualTo(new BigDecimal("0." + "3".repeat(34)));
    }

    @Test
    void a_missing_operand_or_a_zero_divisor_gives_null_with_an_error() {
        var inputs = new HashMap<String, Object>();
        inputs.put("a", null);
        for (var text : List.of("a + \"x\"", "-a", "1 / (2 - 2)", "a ** 2", "2 ** a")) {
            var errors = new ArrayList<String>();
            Assertions.assertThat(FeelExpression.parse(text, List.of("a")).evaluate(inputs::get, errors::add)).as(text).isNull();
            Assertions.assertThat(errors).as(text).isNotEmpty();
        }
    }

    @Test
    void powers_bind_tighter_than_products_and_looser_than_unary_minus_and_associate_left() {
        // DMN 1.5, 10.3.1.2: "-4 ** 2 is interpreted as (-4)*(-4)", and the TCK's 3 ** 4 ** 5 is (3 ** 4) ** 5
        Assertions.assertThat(number("-4 ** 2")).isEqualByComparingTo("16");
        Assertions.assertThat(number("-(4 ** 2)")).isEqualByComparingTo("-16");
        Assertions.assertThat(number("3 ** 4 ** 5")).isEqualByComparingTo("3486784401");
        Assertions.assertThat(number("5+2**5+3")).isEqualByComparingTo("40");
        Assertions.assertThat(number("1.2*10**3")).isEqualByComparingTo("1200");
        Assertions.assertThat(number("2 ** -1 * 4")).isEqualByComparingTo("2");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "5 ** 2                  | 25",
            "5 ** -2                 | 0.04",
            "5.5 ** 2                | 30.25",
            "0 ** 0                  | 1",
            "-8 ** 3                 | -512",
            "2 ** 100                | 1267650600228229401496703205376",
            "2 ** 200                | 1.606938044258990275541962092341163E+60",
            "4 ** 0.5                | 2",
            "2 ** 0.5                | 1.414213562373095048801688724209698",
            "5 ** 2.55               | 60.58617166606633673745724928244262",
            "10 ** 6144              | 1E+6144",
    })
    void powers_are_feel_decimals_of_34_digits(String expression, BigDecimal expected) {
        Assertions.assertThat(number(expression)).isEqualByComparingTo(expected);
    }

    @Test
    void a_power_feel_does_not_define_gives_null() {
        // DMN 1.5, Table 60: the exponent is a number from -999,999,999 to 999,999,999, and the result must be in range
        Assertions.assertThat(number("0 ** -1")).as("a division by zero").isNull();
        Assertions.assertThat(number("-4 ** 0.5")).as("no real root").isNull();
        Assertions.assertThat(number("2 ** 1000000000")).isNull();
        Assertions.assertThat(number("10 ** 6145")).as("beyond the largest decimal128").isNull();
    }

    @Test
    void every_result_is_rounded_to_34_digits_ties_to_even() {
        // DMN 1.5, 10.3.2.3.1 and Tables 57 and 59: "p has 34 digits or less"
        Assertions.assertThat(number("1234567890123456789 * 1234567890123456789"))
                .isEqualByComparingTo("1.524157875323883675019051998750191E+36");
        Assertions.assertThat(number("9999999999999999999999999999999999 + 0.5")).as("a tie goes to the even neighbour")
                .isEqualByComparingTo("1E+34");
        Assertions.assertThat(number(".10 * 30.00")).as("the scale of exact results is kept (Table 40)").isEqualTo(new BigDecimal("3.0000"));
        Assertions.assertThat(number("1e6144 * 10")).as("beyond the largest decimal128").isNull();
    }

    @Test
    void numbers_may_have_an_exponent() {
        // DMN 1.5, grammar rule 35: digits with an optional fraction, or a fraction alone, and an optional exponent
        Assertions.assertThat(number("1.2e3")).isEqualByComparingTo("1200");
        Assertions.assertThat(number("1.2E+3 + .5e-1")).isEqualByComparingTo("1200.05");
        Assertions.assertThat(number("5e-1 * 4")).isEqualByComparingTo("2");
    }

    @Test
    void strings_take_feels_escape_sequences() {
        // DMN 1.5, grammar rule 64
        Assertions.assertThat(FeelExpression.parse("\"a\\tb\\nc\\rd\" + \"\\'\\\"\\\\\"").evaluate(name -> null)).isEqualTo("a\tb\nc\rd'\"\\");
        Assertions.assertThat(FeelExpression.parse("\"caf\\u00e9 \\U01F40E\"").evaluate(name -> null))
                .isEqualTo("caf\u00e9 " + Character.toString(0x1F40E));
    }

    @Test
    void operands_feel_does_not_combine_are_always_null() {
        Map<String, FeelType> types = Map.of("s", FeelType.of(ColumnType.STRING), "n", FeelType.of(ColumnType.NUMBER), "d",
                FeelType.of(ColumnType.DATE));
        Assertions.assertThat(FeelExpression.parse("s + \"!\"", types.keySet()).type(types::get)).isEqualTo(FeelType.of(ColumnType.STRING));
        Assertions.assertThat(FeelExpression.parse("-n * 2", types.keySet()).type(types::get)).isEqualTo(FeelType.of(ColumnType.NUMBER));
        for (var text : List.of("s + n", "s * 2", "s - s", "-s", "d + 1")) {
            var reasons = new ArrayList<String>();
            Assertions.assertThat(FeelExpression.parse(text, types.keySet()).type(types::get, reasons::add)).as(text).isEqualTo(FeelType.NULL);
            Assertions.assertThat(reasons).as(text).isNotEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "-(1 + 2) ** 2 * 3     | 27",
        "((2)) ** -(1) ** 2    | 0.25",
        "- - 3 - -(-(4))       | -1",
        "(1 - (2 - (3 - 4)))   | -2",
        "2 * (3 + 4) / (1 + 1) | 7",
    })
    void parentheses_and_unary_minus_nest_in_any_order(String expression, BigDecimal value) {
        Assertions.assertThat(number(expression)).isEqualByComparingTo(value);
    }

    private static BigDecimal number(String expression) {
        return (BigDecimal) FeelExpression.parse(expression).evaluate(name -> null);
    }
}
