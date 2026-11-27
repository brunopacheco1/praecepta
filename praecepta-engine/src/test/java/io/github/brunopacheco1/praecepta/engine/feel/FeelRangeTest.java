/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FEEL's ranges (DMN 1.5, 10.3.2.7): between two endpoints, or of one, which its comparison tells. */
class FeelRangeTest {

    @ParameterizedTest
    @EnumSource(FeelRange.Comparison.class)
    void a_range_of_one_endpoint_has_its_comparisons_endpoints(FeelRange.Comparison comparison) {
        var range = FeelRange.comparison(comparison, BigDecimal.TEN);
        assertThat(range.comparison()).isEqualTo(comparison);
        assertThat(range).hasToString(comparison.symbol() + " 10");
        assertThat(FeelRange.Comparison.of(comparison.symbol())).isEqualTo(comparison);
    }

    @Test
    void a_range_of_one_endpoint_takes_no_other_endpoints() {
        assertThatThrownBy(() -> new FeelRange(1, true, 5, true, FeelRange.Comparison.LESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(null, false, 5, true, FeelRange.Comparison.LESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, null, false, FeelRange.Comparison.GREATER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, 6, true, FeelRange.Comparison.EQUAL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeelRange.Comparison.of("<>")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FeelRange(null, false, 5, true, FeelRange.Comparison.LESS_OR_EQUAL)).isEqualTo(
                FeelRange.comparison(FeelRange.Comparison.LESS_OR_EQUAL, 5));
    }

    @Test
    void a_range_between_two_endpoints_is_written_as_feel_writes_it() {
        assertThat(FeelRange.between(BigDecimal.ONE, true, BigDecimal.TEN, false)).hasToString("[1..10)");
        assertThat(FeelRange.between(null, false, "z", true).comparison()).isNull();
    }

    @Test
    void each_comparison_takes_its_own_endpoint_and_inclusions_only() {
        var less = FeelRange.Comparison.LESS;
        var lessOrEqual = FeelRange.Comparison.LESS_OR_EQUAL;
        var greater = FeelRange.Comparison.GREATER;
        var greaterOrEqual = FeelRange.Comparison.GREATER_OR_EQUAL;
        var equal = FeelRange.Comparison.EQUAL;
        var notEqual = FeelRange.Comparison.NOT_EQUAL;

        assertThat(new FeelRange(null, false, 5, false, less)).isEqualTo(FeelRange.comparison(less, 5));
        assertThat(new FeelRange(5, false, null, false, greater)).isEqualTo(FeelRange.comparison(greater, 5));
        assertThat(new FeelRange(5, true, null, false, greaterOrEqual)).isEqualTo(FeelRange.comparison(greaterOrEqual, 5));
        assertThat(new FeelRange(5, true, 5, true, equal)).isEqualTo(FeelRange.comparison(equal, 5));
        assertThat(new FeelRange(5, false, 5, false, notEqual)).isEqualTo(FeelRange.comparison(notEqual, 5));
        assertThat(new FeelRange(null, true, null, true, equal)).as("= null").isEqualTo(FeelRange.comparison(equal, null));
        assertThatThrownBy(() -> new FeelRange(1, true, 5, true, less)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a range '< e' has no start 1 included and end 5 included");
        assertThatThrownBy(() -> new FeelRange(null, true, 5, false, less)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a range '< e' has no start null included and end 5 excluded");
        assertThatThrownBy(() -> new FeelRange(null, false, 5, false, lessOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(null, true, 5, true, lessOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(1, false, 5, true, lessOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, 9, false, greater)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, null, true, greater)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, null, false, greaterOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, null, true, greaterOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, 9, false, greaterOrEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, 5, true, equal)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, 5, false, equal)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, true, 5, false, notEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, 5, true, notEqual)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeelRange(5, false, 6, false, notEqual)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a range '!= e' has no start 5 excluded and end 6 excluded");
    }

    @Test
    void an_endpoint_is_written_as_its_text_if_it_is_a_simple_value_and_else_as_its_kind() {
        assertThat(FeelRange.between(null, false, "z", true)).hasToString("(null..\"z\"]");
        assertThat(FeelRange.between(LocalDate.of(2025, 1, 31), true, null, false)).hasToString("[2025-01-31..null)");
        assertThat(FeelRange.between(List.of(1), true, Map.of(), true)).hasToString("[list..context]");
        assertThat(FeelRange.comparison(FeelRange.Comparison.GREATER, List.of(1))).hasToString("> list");
        assertThat(FeelRange.comparison(FeelRange.Comparison.NOT_EQUAL, null)).hasToString("!= null");
    }
}
