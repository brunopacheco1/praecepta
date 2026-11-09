/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.csv;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFieldException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFileException;
import io.github.brunopacheco1.praecepta.loader.exceptions.ParsingInputOutputException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a CSV file becomes rules: numbering, trimming, blank rows and the errors it reports. */
class CsvLoaderBehaviourTest {

    @Test
    void rows_are_numbered_from_zero_after_the_header_and_blank_rows_are_skipped_but_counted() {
        var rules = load(" a ; b ;c\n x ; ;1\n\n;;\n y;z;2\n");

        assertThat(rules).extracting(Praeceptum::priority).containsExactly(0, 3);
        assertThat(rules.get(0).input().values()).containsEntry("a", "x").containsEntry("b", null);
        assertThat(rules.get(0).output().values()).containsEntry("c", "1");
        assertThat(rules.get(1).input().values()).containsEntry("a", "y").containsEntry("b", "z");
    }

    @Test
    void a_header_without_rows_gives_no_rules() {
        assertThat(load("a;b;c\n")).isEmpty();
    }

    @Test
    void a_row_whose_trailing_cells_are_missing_is_rejected() {
        assertThatThrownBy(() -> load("a;b;c\nx;;\n"))
                .isInstanceOf(InvalidMatrixException.class)
                .hasMessage("Error loading rules.csv: Missing input/output value")
                .hasCauseInstanceOf(ParsingInputOutputException.class);
    }

    @Test
    void a_declared_column_missing_from_the_header_is_named() {
        assertThatThrownBy(() -> load("a;c\nx;1\n"))
                .isInstanceOf(InvalidMatrixException.class)
                .hasMessage("Error loading rules.csv: Fields not found in file: b")
                .hasCauseInstanceOf(MissingFieldException.class);
    }

    @Test
    void a_missing_classpath_resource_is_reported() {
        var inputs = new LinkedHashSet<>(List.of("a"));
        var outputs = new LinkedHashSet<>(List.of("c"));
        assertThatThrownBy(() -> new CsvLoader("missing.csv", inputs, outputs))
                .isInstanceOf(InvalidMatrixException.class)
                .hasMessage("Error loading missing.csv: The file not found: missing.csv")
                .hasCauseInstanceOf(MissingFileException.class);
    }

    @Test
    void the_stream_is_closed() {
        var closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream("a;b;c\nx;y;1\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        new CsvLoader(stream, "rules.csv", new LinkedHashSet<>(List.of("a", "b")), new LinkedHashSet<>(List.of("c")));
        assertThat(closed).isTrue();
    }

    private static List<Praeceptum> load(String csv) {
        var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return new CsvLoader(stream, "rules.csv", new LinkedHashSet<>(List.of("a", "b")), new LinkedHashSet<>(List.of("c")))
                .getPraecepta();
    }
}
