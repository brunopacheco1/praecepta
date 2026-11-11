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
    void types_must_go_together() {
        Map<String, ColumnType> types = Map.of("s", ColumnType.STRING, "n", ColumnType.NUMBER, "d", ColumnType.DATE);
        Assertions.assertThat(OutputExpression.parse("s + \"!\"").type(types::get)).isEqualTo(ColumnType.STRING);
        Assertions.assertThat(OutputExpression.parse("-n * 2").type(types::get)).isEqualTo(ColumnType.NUMBER);
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("s + n").type(types::get))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessage("+ combines a string and a number, which gives null in FEEL");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("s * 2").type(types::get))
                .hasMessage("* combines a string and a number, which gives null in FEEL");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("s - s").type(types::get))
                .hasMessage("- applies to numbers, not strings");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("-s").type(types::get))
                .hasMessage("- applies to numbers, not strings");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse("d + 1").type(types::get))
                .hasMessage("'d' is a date; output expressions combine strings and numbers");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "a function call      | string length(a)     | function calls such as string length(...) are not supported",
            "a comparison         | a > 1                | unexpected '> 1'",
            "a conditional        | if a then 1 else 2   | 'if a then' is not supported",
            "a boolean            | true                 | 'true' is not supported",
            "exponentiation       | a ** 2               | ** is not supported",
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
