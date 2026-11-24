/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

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
}
