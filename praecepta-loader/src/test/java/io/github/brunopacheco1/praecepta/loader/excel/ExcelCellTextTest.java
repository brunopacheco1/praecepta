package io.github.brunopacheco1.praecepta.loader.excel;

import java.time.LocalDate;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** Excel cells must reach the engine exactly: no rounding, dates as ISO text. */
class ExcelCellTextTest {

    @Test
    void numbers_keep_their_exact_value() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue(18.5);
            row.createCell(1).setCellValue(18.0);
            row.createCell(2).setCellValue(-0.25);
            Assertions.assertThat(ExcelLoader.cellText(row.getCell(0))).isEqualTo("18.5"); // was rounded to 19
            Assertions.assertThat(ExcelLoader.cellText(row.getCell(1))).isEqualTo("18");
            Assertions.assertThat(ExcelLoader.cellText(row.getCell(2))).isEqualTo("-0.25");
        }
    }

    @Test
    void date_formatted_cells_become_iso_dates() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            var cell = workbook.createSheet().createRow(0).createCell(0);
            cell.setCellValue(LocalDate.of(2025, 1, 31));
            cell.setCellStyle(style);
            Assertions.assertThat(ExcelLoader.cellText(cell)).isEqualTo("2025-01-31"); // was a day-serial number
        }
    }

    @Test
    void text_booleans_and_formulas() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("[18..65)");
            row.createCell(1).setCellValue(true);
            var formula = row.createCell(2);
            formula.setCellFormula("2*3");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            Assertions.assertThat(ExcelLoader.cellText(row.getCell(0))).isEqualTo("[18..65)");
            Assertions.assertThat(ExcelLoader.cellText(row.getCell(1))).isEqualTo("true");
            Assertions.assertThat(ExcelLoader.cellText(formula)).isEqualTo("6");
        }
    }
}
