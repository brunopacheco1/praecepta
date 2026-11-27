/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ComputedOutputs.Computed;
import io.github.brunopacheco1.praecepta.engine.ComputedOutputs.OutputBinding;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * How the value of an output that reads inputs is given to its output: as it is, as text, or bound to the output's
 * type; a number an expression computes without trailing zeros, the number of an input an output names as it is.
 */
class ComputedOutputsTest {

    private static final BigDecimal SCALED = new BigDecimal("1.50");

    private static Computed expression(OutputBinding binding, ColumnType type) {
        return new Computed("total", FeelExpression.parse("1"), -1, binding, type == null ? FeelType.ANY : FeelType.of(type));
    }

    private static Computed reference(OutputBinding binding, ColumnType type) {
        return new Computed("total", null, 0, binding, type == null ? FeelType.ANY : FeelType.of(type));
    }

    @Test
    void an_output_of_no_declared_type_takes_a_computed_number_without_trailing_zeros_and_an_inputs_number_as_it_is() {
        assertThat(ComputedOutputs.bound(SCALED, expression(OutputBinding.AS_IS, null), null)).isEqualTo(new BigDecimal("1.5"));
        assertThat(ComputedOutputs.bound(SCALED, reference(OutputBinding.AS_IS, null), null)).isSameAs(SCALED);
        assertThat(ComputedOutputs.bound(List.of(SCALED), expression(OutputBinding.AS_IS, null), null)).as("a list, as it is")
                .isEqualTo(List.of(SCALED));
        assertThat(ComputedOutputs.bound(null, expression(OutputBinding.AS_IS, null), null)).isNull();
    }

    @Test
    void a_string_output_takes_a_string_as_it_is_and_any_other_value_as_its_text() {
        assertThat(ComputedOutputs.bound("1.50", expression(OutputBinding.TEXT, ColumnType.STRING), null)).isEqualTo("1.50");
        assertThat(ComputedOutputs.bound(SCALED, expression(OutputBinding.TEXT, ColumnType.STRING), null)).isEqualTo("1.5");
        assertThat(ComputedOutputs.bound(SCALED, reference(OutputBinding.TEXT, ColumnType.STRING), null))
                .as("the number of an input the output names keeps its scale").isEqualTo("1.50");
        assertThat(ComputedOutputs.bound(new BigDecimal("0.00"), expression(OutputBinding.TEXT, ColumnType.STRING), null)).isEqualTo("0");
        assertThat(ComputedOutputs.bound(List.of("PT", SCALED), expression(OutputBinding.TEXT, ColumnType.STRING), null))
                .isEqualTo("[\"PT\", 1.50]");
        assertThat(ComputedOutputs.bound(LocalDate.of(2025, 1, 31), reference(OutputBinding.TEXT, ColumnType.STRING), null))
                .isEqualTo("2025-01-31");
        assertThat(ComputedOutputs.bound(null, expression(OutputBinding.TEXT, ColumnType.STRING), null)).isNull();
    }

    @Test
    void a_typed_output_takes_a_value_that_conforms_to_its_type_and_is_null_with_an_error_for_any_other() {
        var errors = new ArrayList<String>();
        var date = LocalDate.of(2025, 1, 31);

        assertThat(ComputedOutputs.bound(SCALED, expression(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add)).isEqualTo(new BigDecimal("1.5"));
        assertThat(ComputedOutputs.bound(SCALED, reference(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add)).isSameAs(SCALED);
        assertThat(ComputedOutputs.bound(List.of(SCALED), expression(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add))
                .as("a list of one is its element").isEqualTo(SCALED);
        assertThat(ComputedOutputs.bound(date, expression(OutputBinding.CONFORM, ColumnType.DATE_TIME), errors::add))
                .isEqualTo(ZonedDateTime.of(2025, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(ComputedOutputs.bound(null, expression(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add)).isNull();
        assertThat(errors).as("what conforms, and null, are no errors").isEmpty();

        assertThat(ComputedOutputs.bound("high", expression(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add)).isNull();
        assertThat(ComputedOutputs.bound(List.of(SCALED, SCALED), reference(OutputBinding.CONFORM, ColumnType.NUMBER), errors::add)).isNull();
        assertThat(errors).containsExactly("output 'total': high is not a number, the output's type (DMN 1.5, 10.3.2.9.4)",
                "output 'total': [1.50, 1.50] is not a number, the output's type (DMN 1.5, 10.3.2.9.4)");
        assertThat(ComputedOutputs.bound("high", expression(OutputBinding.CONFORM, ColumnType.NUMBER), null)).as("with no one to tell").isNull();
    }
}
