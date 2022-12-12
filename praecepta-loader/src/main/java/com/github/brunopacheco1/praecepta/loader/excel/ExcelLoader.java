package com.github.brunopacheco1.praecepta.loader.excel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import com.github.brunopacheco1.praecepta.loader.BaseLoader;
import com.github.brunopacheco1.praecepta.loader.beans.FileRow;
import com.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;
import com.github.brunopacheco1.praecepta.loader.exceptions.MissingFileException;

public class ExcelLoader extends BaseLoader {

    private final List<Praeceptum> praecepta = new ArrayList<>();

    public ExcelLoader(String excelPath, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        readExcel(excelPath);
    }

    /**
     * Translate the excel file into a collection of praecepta.
     *
     * @param excelPath the path to look after the Excel file
     */
    private void readExcel(String excelPath) {
        try (
            var workbook = readFile(excelPath);
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
    private Workbook readFile(String excelPath) {
        ClassLoader classLoader = getClass().getClassLoader();
        try {
            var inputStream = classLoader.getResourceAsStream(excelPath);
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

        headers.forEach((var colIndex,var header) ->  {
            var cell = row.getCell(colIndex);
            if(cell != null) {
                fileRow.set(header, getCellValue(cell));
            } else {
                fileRow.set(header, null);
            }
        });

        return fileRow;
    }

    private String getCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case NUMERIC:
                return String.valueOf(Math.round(cell.getNumericCellValue()));
            case STRING:
                return cell.getStringCellValue();
            default:
                return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Praeceptum> getPraecepta() {
        return praecepta;
    }
}
