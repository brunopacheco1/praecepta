/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Arithmetic;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Literal;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Name;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Negation;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

/** Output entries that compute their value: parsing, type checking and evaluation. */
class OutputExpressionTest {

    @Test
    void products_bind_tighter_than_sums_and_operators_of_one_level_associate_left() {
        Assertions.assertThat(OutputExpression.parse("a - b - 2 * -c / 4")).isEqualTo(new Arithmetic(Operator.SUBTRACT,
                new Arithmetic(Operator.SUBTRACT, new Name("a"), new Name("b")),
                new Arithmetic(Operator.DIVIDE, new Arithmetic(Operator.MULTIPLY, new Literal(new BigDecimal(2)), new Negation(new Name("c"))),
                        new Literal(new BigDecimal(4)))));
        Assertions.assertThat(OutputExpression.parse("(a+b)*.5")).isEqualTo(new Arithmetic(Operator.MULTIPLY,
                new Arithmetic(Operator.ADD, new Name("a"), new Name("b")), new Literal(new BigDecimal("0.5"))));
    }

    @Test
    void names_may_hold_spaces_and_dots_and_strings_escape_with_a_backslash() {
        var expression = OutputExpression.parse("Flu Symptoms.first + \" is \\\"in\\\" \" + Credit Score");
        Assertions.assertThat(expression.names()).containsExactly("Flu Symptoms.first", "Credit Score");
        Assertions.assertThat(expression.evaluate(Map.of("Flu Symptoms.first", "fever", "Credit Score", "high")::get))
                .isEqualTo("fever is \"in\" high");
    }

    @Test
    void strings_join_and_numbers_compute_as_feel_decimals() {
        Assertions.assertThat(OutputExpression.parse("Symptom + \" is in the list of Flu symptoms\"").evaluate(Map.of("Symptom", "cough")::get))
                .isEqualTo("cough is in the list of Flu symptoms");
        Assertions.assertThat(OutputExpression.parse("(price - discount) * 1.2").evaluate(
                Map.<String, Object>of("price", new BigDecimal("10.5"), "discount", new BigDecimal("0.5"))::get))
                .isEqualTo(new BigDecimal("12.00"));
        Assertions.assertThat(OutputExpression.parse("1 / 3").evaluate(name -> null))
                .as("34 significant digits").isEqualTo(new BigDecimal("0." + "3".repeat(34)));
    }

    @Test
    void a_missing_operand_or_a_zero_divisor_gives_null() {
        var inputs = new HashMap<String, Object>();
        inputs.put("a", null);
        Assertions.assertThat(OutputExpression.parse("a + \"x\"").evaluate(inputs::get)).isNull();
        Assertions.assertThat(OutputExpression.parse("-a").evaluate(inputs::get)).isNull();
        Assertions.assertThat(OutputExpression.parse("1 / (2 - 2)").evaluate(inputs::get)).isNull();
    }

    @Test
    void powers_bind_tighter_than_products_and_looser_than_unary_minus_and_associate_left() {
        // DMN 1.5, 10.3.1.2: "-4 ** 2 is interpreted as (-4)*(-4)", and the TCK's 3 ** 4 ** 5 is (3 ** 4) ** 5
        Assertions.assertThat(OutputExpression.parse("-a ** 2 * b")).isEqualTo(new Arithmetic(Operator.MULTIPLY,
                new Arithmetic(Operator.POWER, new Negation(new Name("a")), new Literal(new BigDecimal(2))), new Name("b")));
        Assertions.assertThat(OutputExpression.parse("3 ** 4 ** 5")).isEqualTo(new Arithmetic(Operator.POWER,
                new Arithmetic(Operator.POWER, new Literal(new BigDecimal(3)), new Literal(new BigDecimal(4))), new Literal(new BigDecimal(5))));
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
            "5 ** 2.0                | 25",
            "0 ** 0                  | 1",
            "0 ** 3                  | 0",
            "0 ** 0.5                | 0",
            "-8 ** 3                 | -512",
            "2 ** 100                | 1267650600228229401496703205376",
            "2 ** 200                | 1.606938044258990275541962092341163E+60",
            "3 ** -1                 | 0.3333333333333333333333333333333333",
            "4 ** 0.5                | 2",
            "2 ** 0.5                | 1.414213562373095048801688724209698",
            "8 ** (1 / 3)            | 2",
            "5 ** 2.55               | 60.58617166606633673745724928244262",
            "10 ** 0.5               | 3.162277660168379331998893544432719",
            "0.5 ** 10.5             | 0.0006905339660024878167976995723680166",
            "2.718281828 ** -3.5     | 0.03019738344016686250336724819474934",
            "1.0000001 ** 999999999  | 2.688103432454580565047543796723124E+43",
            "10 ** 6144              | 1E+6144",
    })
    void powers_are_feel_decimals_of_34_digits(String expression, BigDecimal expected) {
        Assertions.assertThat(number(expression)).isEqualByComparingTo(expected);
    }

    @Test
    void a_power_feel_does_not_define_gives_null() {
        // DMN 1.5, Table 60: the exponent is a number from -999,999,999 to 999,999,999, and the result must be in range
        Assertions.assertThat(number("0 ** -1")).as("a division by zero").isNull();
        Assertions.assertThat(number("0 ** -0.5")).isNull();
        Assertions.assertThat(number("-4 ** 0.5")).as("no real root").isNull();
        Assertions.assertThat(number("2 ** 1000000000")).isNull();
        Assertions.assertThat(number("2 ** -1000000000")).isNull();
        Assertions.assertThat(number("10 ** 6145")).as("beyond the largest decimal128").isNull();
        Assertions.assertThat(number("10 ** 999999999")).isNull();
        Assertions.assertThat(number("10 ** -999999999")).isNull();
        Assertions.assertThat(number("1.5 ** 99999.5")).isNull();
        var inputs = new HashMap<String, Object>();
        inputs.put("a", null);
        Assertions.assertThat(OutputExpression.parse("a ** 2").evaluate(inputs::get)).isNull();
        Assertions.assertThat(OutputExpression.parse("2 ** a").evaluate(inputs::get)).isNull();
    }

    @Test
    void every_result_is_rounded_to_34_digits_ties_to_even() {
        // DMN 1.5, 10.3.2.3.1 and Tables 57 and 59: "p has 34 digits or less"
        Assertions.assertThat(number("1234567890123456789 * 1234567890123456789"))
                .isEqualByComparingTo("1.524157875323883675019051998750191E+36");
        Assertions.assertThat(number("0.1234567890123456789 * 0.1234567890123456789"))
                .isEqualTo(new BigDecimal("0.01524157875323883675019051998750191"));
        Assertions.assertThat(number("9999999999999999999999999999999999 + 0.5")).as("a tie goes to the even neighbour")
                .isEqualByComparingTo("1E+34");
        Assertions.assertThat(number("9999999999999999999999999999999998 + 0.5")).isEqualTo(new BigDecimal("9999999999999999999999999999999998"));
        Assertions.assertThat(number("1 - 0.00000000000000000000000000000000001")).isEqualTo(new BigDecimal("1.000000000000000000000000000000000"));
        Assertions.assertThat(number(".10 * 30.00")).as("the scale of exact results is kept (Table 40)").isEqualTo(new BigDecimal("3.0000"));
        Assertions.assertThat(number("1e6144 * 10")).as("beyond the largest decimal128").isNull();
    }

    @Test
    void numbers_may_have_an_exponent() {
        // DMN 1.5, grammar rule 35: digits with an optional fraction, or a fraction alone, and an optional exponent
        Assertions.assertThat(number("1.2e3")).isEqualByComparingTo("1200");
        Assertions.assertThat(number("1.2E+3 + .5e-1")).isEqualByComparingTo("1200.05");
        Assertions.assertThat(number("5e-1 * 4")).isEqualByComparingTo("2");
        // not an exponent: a name cannot follow a number
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("2e")).isInstanceOf(ExpressionSyntaxException.class);
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("2e+")).isInstanceOf(ExpressionSyntaxException.class);
    }

    @Test
    void strings_take_feels_escape_sequences() {
        // DMN 1.5, grammar rule 64
        Assertions.assertThat(OutputExpression.parse("\"a\\tb\\nc\\rd\" + \"\\'\\\"\\\\\"").evaluate(name -> null)).isEqualTo("a\tb\nc\rd'\"\\");
        Assertions.assertThat(OutputExpression.parse("\"caf\\u00e9 \\U01F40E\"").evaluate(name -> null))
                .isEqualTo("caf\u00e9 " + Character.toString(0x1F40E));
        Assertions.assertThat(OutputExpression.parse("\"\\u0041\\u0042\"").evaluate(name -> null)).isEqualTo("AB");
        for (var invalid : new String[] {"\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\U110000\"", "\"\\u+041\"", "\"\\"}) {
            Assertions.assertThatThrownBy(() -> OutputExpression.parse(invalid)).as(invalid).isInstanceOf(ExpressionSyntaxException.class);
        }
    }

    private static BigDecimal number(String expression) {
        return (BigDecimal) OutputExpression.parse(expression).evaluate(name -> null);
    }

    @Test
    void types_must_go_together() {
        Map<String, ColumnType> types = Map.of("s", ColumnType.STRING, "n", ColumnType.NUMBER, "d", ColumnType.DATE);
        Assertions.assertThat(OutputExpression.parse("s + \"!\"").type(types::get)).isEqualTo(ColumnType.STRING);
        Assertions.assertThat(OutputExpression.parse("-n * 2").type(types::get)).isEqualTo(ColumnType.NUMBER);
        var stringPlusNumber = OutputExpression.parse("s + n");
        Assertions.assertThatThrownBy(() -> stringPlusNumber.type(types::get))
                .isInstanceOf(ExpressionTypeException.class)
                .hasMessage("+ combines a string and a number, which gives null in FEEL");
        var stringTimesNumber = OutputExpression.parse("s * 2");
        Assertions.assertThatThrownBy(() -> stringTimesNumber.type(types::get))
                .hasMessage("* combines a string and a number, which gives null in FEEL");
        var stringMinusString = OutputExpression.parse("s - s");
        Assertions.assertThatThrownBy(() -> stringMinusString.type(types::get))
                .hasMessage("- combines a string and a string, which gives null in FEEL");
        var negatedString = OutputExpression.parse("-s");
        Assertions.assertThatThrownBy(() -> negatedString.type(types::get))
                .hasMessage("- applies to numbers and durations, not to a string");
        // DMN 1.5, Table 57: a date takes durations, not numbers
        var datePlusNumber = OutputExpression.parse("d + 1");
        Assertions.assertThatThrownBy(() -> datePlusNumber.type(types::get))
                .hasMessage("+ combines a date and a number, which gives null in FEEL");
    }

    @Test
    void a_name_that_is_not_an_input_is_reported_by_name() {
        Map<String, ColumnType> types = Map.of("n", ColumnType.NUMBER);
        var unknownName = OutputExpression.parse("n + limit");
        Assertions.assertThatThrownBy(() -> unknownName.type(types::get))
                .isInstanceOf(ExpressionTypeException.class)
                .hasMessage("'limit' is not an input");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "a function call      | string length(a)     | function calls such as string length(...) are not supported",
            "a comparison         | a > 1                | unexpected '> 1'",
            "a conditional        | if a then 1 else 2   | 'if a then' is not supported",
            "a boolean            | true                 | 'true' is not supported",
            "a lone star          | a * * 2              | unexpected '*'",
            "a list               | [1, 2]               | unexpected '['",
            "an open parenthesis  | (a + 1               | a parenthesis is not closed",
            "a dangling operator  | a +                  | a value is missing at the end",
            "an open string       | \"abc                | a string is not terminated",
            "two dots             | 1.2.3                | '1.2.3' is not a number",
            "nothing              | `   `                | it is empty",
    })
    void other_feel_is_not_supported(String description, String text, String problem) {
        Assertions.assertThatThrownBy(() -> OutputExpression.parse(text))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("is not a supported output expression: " + problem);
    }

    @Test
    void deep_nesting_is_rejected_before_it_can_exhaust_the_stack() {
        var deep = "(".repeat(5_000) + "1" + ")".repeat(5_000);
        Assertions.assertThatThrownBy(() -> OutputExpression.parse(deep))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("more than 1000 operators and parentheses");
        var longSum = "1" + " + 1".repeat(1000);
        Assertions.assertThat(OutputExpression.parse(longSum).evaluate(name -> null)).isEqualTo(new BigDecimal(1001));
    }
}
