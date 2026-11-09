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

/**
 * Reads rules from the first sheet of an Excel workbook: a header row naming the columns, then one
 * rule per row, numbered by its row in the sheet (the first rule is 1). Missing and empty cells are
 * null; rows without a value are skipped.
 */
public final class ExcelLoader extends BaseLoader {

    private static final int HEADER_ROW = 0;

    private final List<Praeceptum> praecepta;

    /** Reads the first sheet of an Excel file from the classpath. */
    public ExcelLoader(String excelPath, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        praecepta = read(excelPath, null);
    }

    /**
     * Reads the first sheet of an Excel workbook from a stream, which is closed afterwards.
     *
     * @param source names the data in error messages, e.g. its file name
     */
    public ExcelLoader(InputStream excel, String source, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        praecepta = read(source, excel);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Praeceptum> getPraecepta() {
        return praecepta;
    }

    /**
     * The text of a cell as the engine's cell parser expects it. Numbers keep their exact value
     * ({@code 18.5}, not rounded; {@code 18}, not {@code 18.0}); date-formatted cells become ISO dates
     * ({@code 2025-01-31}), which DATE columns accept; formulas use their cached result.
     */
    static String cellText(Cell cell) {
        var type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? isoDate(cell) : exactNumber(cell);
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * @param source the classpath resource to read if {@code stream} is null; else names the stream in error messages
     */
    private List<Praeceptum> read(String source, InputStream stream) {
        try (var workbook = openWorkbook(source, stream)) {
            var sheet = workbook.getSheetAt(0);
            var headers = readHeaders(sheet.getRow(HEADER_ROW));
            validateFields(headers);
            return parseRowsToPraecepta(readRows(headers, sheet));
        } catch (Exception e) {
            throw new InvalidMatrixException(source, e);
        }
    }

    private Workbook openWorkbook(String source, InputStream stream) {
        try (var inputStream = stream != null ? stream : getClass().getClassLoader().getResourceAsStream(source)) {
            return WorkbookFactory.create(inputStream);
        } catch (Exception e) {
            throw new MissingFileException(source, e);
        }
    }

    /** Each column's name by its position. */
    private static Map<Integer, String> readHeaders(Row headers) {
        var positioning = new HashMap<Integer, String>();
        headers.forEach(cell -> positioning.put(cell.getColumnIndex(), cell.getStringCellValue()));
        return positioning;
    }

    /** The rows below the header that have a value. */
    private static List<FileRow> readRows(Map<Integer, String> headers, Sheet sheet) {
        var rows = new ArrayList<FileRow>();
        for (var sheetRow : sheet) {
            if (sheetRow.getRowNum() == HEADER_ROW) {
                continue;
            }
            var row = readRow(headers, sheetRow);
            if (row.isNotEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static FileRow readRow(Map<Integer, String> headers, Row sheetRow) {
        var row = new FileRow(sheetRow.getRowNum());
        for (var header : headers.entrySet()) {
            var cell = sheetRow.getCell(header.getKey());
            row.set(header.getValue(), cell == null ? null : cellText(cell));
        }
        return row;
    }

    private static String isoDate(Cell cell) {
        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
    }

    /**
     * {@code BigDecimal.valueOf} takes the shortest decimal that reads back as the same double, so
     * {@code 18.5} stays {@code 18.5}; trailing zeros are stripped, and plain notation avoids {@code 1E+21}.
     */
    private static String exactNumber(Cell cell) {
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }
}
