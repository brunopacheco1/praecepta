/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFieldException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFileException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the first sheet of a workbook becomes rules: numbering, cell text, empty rows and errors. */
class ExcelLoaderBehaviourTest {

    @Test
    void rows_keep_their_sheet_number_and_empty_rows_are_skipped() throws IOException {
        var rules = load(workbook());

        assertThat(rules).extracting(Praeceptum::priority).containsExactly(1, 4);
        assertThat(rules.get(0).input().values())
                .containsEntry("number", "18.5")
                .containsEntry("date", "2025-01-31")
                .containsEntry("text", "  kept as is ")
                .containsEntry("formula", "6");
        assertThat(rules.get(0).output().values()).containsEntry("out", "first");
        // missing cells and empty text are null
        assertThat(rules.get(1).input().values())
                .containsEntry("number", "-0.25")
                .containsEntry("date", null)
                .containsEntry("text", null)
                .containsEntry("formula", "ab");
        assertThat(rules.get(1).output().values()).containsEntry("out", "18");
    }

    @Test
    void a_declared_column_missing_from_the_header_is_named() throws IOException {
        var stream = new ByteArrayInputStream(workbook());
        var inputs = Set.of("missing");
        var outputs = Set.of("out");
        assertThatThrownBy(() -> new ExcelLoader(stream, "rules.xlsx", inputs, outputs))
                .isInstanceOf(InvalidMatrixException.class)
                .hasMessage("Error loading rules.xlsx: Fields not found in file: missing")
                .hasCauseInstanceOf(MissingFieldException.class);
    }

    @Test
    void a_file_that_is_not_a_workbook_is_reported() {
        var stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
        var inputs = Set.of("a");
        var outputs = Set.of("b");
        assertThatThrownBy(() -> new ExcelLoader(stream, "rules.xlsx", inputs, outputs))
                .isInstanceOf(InvalidMatrixException.class)
                .hasMessage("Error loading rules.xlsx: The file not found: rules.xlsx")
                .hasCauseInstanceOf(MissingFileException.class);
    }

    @Test
    void the_stream_is_closed() throws IOException {
        var closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(workbook()) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        new ExcelLoader(stream, "rules.xlsx", Set.of("number"), Set.of("out"));
        assertThat(closed).isTrue();
    }

    private static List<Praeceptum> load(byte[] workbook) {
        return new ExcelLoader(new ByteArrayInputStream(workbook), "rules.xlsx", Set.of("number", "date", "text", "formula"), Set.of("out"))
                .getPraecepta();
    }

    /** A header, a full row, an empty row, a missing row and a sparse row. */
    private static byte[] workbook() throws IOException {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            var names = List.of("number", "date", "text", "formula", "out");
            for (int c = 0; c < names.size(); c++) {
                header.createCell(c).setCellValue(names.get(c));
            }
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));

            var full = sheet.createRow(1);
            full.createCell(0).setCellValue(18.5);
            var date = full.createCell(1);
            date.setCellValue(LocalDate.of(2025, 1, 31));
            date.setCellStyle(dateStyle);
            full.createCell(2).setCellValue("  kept as is ");
            full.createCell(3).setCellFormula("2*3");
            full.createCell(4).setCellValue("first");

            sheet.createRow(2).createCell(2).setCellValue("");

            var sparse = sheet.createRow(4);
            sparse.createCell(0).setCellValue(-0.25);
            sparse.createCell(3).setCellFormula("\"a\"&\"b\"");
            sparse.createCell(4).setCellValue(18.0);

            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }
}
