package com.github.brunopacheco1.praecepta.loader.csv;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvLoaderTest {
    
    @Test
    void fails_if_matrix_does_not_exist() {
        var inputs = Set.of("inputOne", "inputTwo");
        var outputs = Set.of("output");
        assertThatThrownBy(() ->
                new CsvLoader("file-doesnt-exist.csv", inputs, outputs)
        ).isInstanceOf(InvalidMatrixException.class);
    }

    @Test
    void fails_if_field_not_found() {
        var inputs = Set.of("notFoundInput");
        var outputs = Set.of("notFoundOutput");
        assertThatThrownBy(() ->
                new CsvLoader("dummy-matrix.csv", inputs, outputs)
        ).isInstanceOf(InvalidMatrixException.class);
    }

    @Test
    void can_load_a_matrix() {
        var inputs = Set.of("inputOne", "inputTwo");
        var outputs = Set.of("output");
        var loader = new CsvLoader("dummy-matrix.csv", inputs, outputs);
        assertThat(loader).isNotNull();
        assertThat(loader.getPraecepta()).isNotEmpty();
    }
}
