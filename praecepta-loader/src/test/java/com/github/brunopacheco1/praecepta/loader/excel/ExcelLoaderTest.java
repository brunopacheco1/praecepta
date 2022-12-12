package com.github.brunopacheco1.praecepta.loader.excel;

import org.junit.jupiter.api.Test;

import com.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelLoaderTest {

    @Test
    void fails_if_matrix_does_not_exist() {
        var inputs = Set.of("inputOne", "inputTwo");
        var outputs = Set.of("output");

        assertThatThrownBy(() ->
                new ExcelLoader("file-doesnt-exist.xlsx", inputs, outputs)
        ).isInstanceOf(InvalidMatrixException.class);
    }

    @Test
    void fails_if_field_not_found() {
        var inputs = Set.of("notFoundInput");
        var outputs = Set.of("notFoundOutput");
        assertThatThrownBy(() ->
                new ExcelLoader("dummy-matrix.xlsx", inputs, outputs)
        ).isInstanceOf(InvalidMatrixException.class);
    }

    @Test
    void can_load_a_matrix() {
        var inputs = Set.of("inputOne", "inputTwo");
        var outputs = Set.of("output");
        var loader = new ExcelLoader("dummy-matrix.xlsx", inputs, outputs);
        assertThat(loader).isNotNull();
        assertThat(loader.getPraecepta()).isNotEmpty();
    }
}
