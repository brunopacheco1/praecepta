/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

class UnaryTestsTest {

    @Test
    void a_list_of_literals_has_its_values_in_the_order_written() {
        Assertions.assertThat(CellParser.parseFeel("\"PT\",\"ES\"", ColumnType.STRING).values()).contains(List.of("PT", "ES"));
        Assertions.assertThat(CellParser.parseFeel("\"PT\"", ColumnType.STRING).values()).contains(List.of("PT"));
        Assertions.assertThat(CellParser.parseFeel("2, 1, 2", ColumnType.NUMBER).values())
                .contains(List.of(new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("2")));
        Assertions.assertThat(CellParser.parseFeel("date(\"2025-01-31\")", ColumnType.DATE).values())
                .contains(List.of(LocalDate.of(2025, 1, 31)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-", "", "not(18)", "null", "18, null", "< 18", "[18..65]", "18, > 65"})
    void any_other_cell_has_no_values(String cell) {
        Assertions.assertThat(CellParser.parseFeel(cell, ColumnType.NUMBER).values()).isEmpty();
    }
}
