/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.excel;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.BaseLoader;
import io.github.brunopacheco1.praecepta.loader.beans.FileRow;
import io.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFileException;

public final class ExcelLoader extends BaseLoader {

    private final List<Praeceptum> praecepta = new ArrayList<>();

    /** Reads the first sheet of an Excel file from the classpath. */
    public ExcelLoader(String excelPath, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        readExcel(excelPath, null);
    }

    /**
     * Reads the first sheet of an Excel workbook from a stream, which is closed afterwards.
     *
     * @param source names the data in error messages, e.g. its file name
     */
    public ExcelLoader(InputStream excel, String source, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        readExcel(source, excel);
    }

    /**
     * Translate the excel file into a collection of praecepta.
     *
     * @param excelPath the path to look after the Excel file
     */
    private void readExcel(String excelPath, InputStream stream) {
        try (
            var workbook = readFile(excelPath, stream);
        ) {
            var sheet = workbook.getSheetAt(0);
            var headers = readHeadersPositioning(sheet);
            var fileRows = readRows(headers, sheet);
            praecepta.addAll(parseRowsToPraecepta(fileRows));
        } catch (Exception e) {
            throw new InvalidMatrixException(excelPath, e);
        }
    }

    /**
     * Opens the Excel file.
     *
     * @param excelPath the file path
     * @return the Excel POI object
     */
    private Workbook readFile(String excelPath, InputStream stream) {
        try (var inputStream = stream != null ? stream : getClass().getClassLoader().getResourceAsStream(excelPath)) {
            return WorkbookFactory.create(inputStream);
        } catch (Exception e) {
            throw new MissingFileException(excelPath, e);
        }
    }

    /**
     * Takes a look into the headers and validates the INPUT and OUTPUT classes.
     *
     * @param sheet the workbook sheet to retrieve the header positioning
     * @return the mapping the headers (fields) and their position in the Excel file.
     */
    private Map<Integer, String> readHeadersPositioning(Sheet sheet) {
        var headers = sheet.getRow(0);
        var positioning = new HashMap<Integer, String>();
        headers.forEach(cell -> positioning.put(cell.getColumnIndex(), cell.getStringCellValue()));
        validateFields(positioning);
        sheet.removeRow(headers);
        return positioning;
    }

    /**
     * Translates the Excel rows into instances of Recards, for easier further conversion.
     *
     * @param headers the header positioning
     * @param sheet   the working Excel sheet
     * @return collection of fileRows
     */
    private Iterable<FileRow> readRows(Map<Integer, String> headers, Sheet sheet) {
        var fileRows = new ArrayList<FileRow>();
        var rowIterator = sheet.rowIterator();
        while (rowIterator.hasNext()) {
            var fileRow = readCells(headers, rowIterator.next());
            if (fileRow.isNotEmpty()) {
                fileRows.add(fileRow);
            }
        }
        return fileRows;
    }

    private FileRow readCells(Map<Integer, String> headers, Row row) {
        var fileRow = new FileRow(row.getRowNum());

        headers.forEach((var colIndex, var header) -> {
            var cell = row.getCell(colIndex);
            if (cell != null) {
                fileRow.set(header, getCellValue(cell));
            } else {
                fileRow.set(header, null);
            }
        });

        return fileRow;
    }

    private String getCellValue(Cell cell) {
        return cellText(cell);
    }

    /**
     * The text of a cell as the engine's cell parser expects it. Numbers keep their exact value
     * ({@code 18.5}, not rounded; {@code 18}, not {@code 18.0}); date-formatted cells become ISO dates
     * ({@code 2025-01-31}), which DATE columns accept; formulas use their cached result.
     */
    static String cellText(Cell cell) {
        var type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Praeceptum> getPraecepta() {
        return praecepta;
    }
}
