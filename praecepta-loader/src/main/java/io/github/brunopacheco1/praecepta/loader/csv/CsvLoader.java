package io.github.brunopacheco1.praecepta.loader.csv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.BaseLoader;
import io.github.brunopacheco1.praecepta.loader.beans.FileRow;
import io.github.brunopacheco1.praecepta.loader.exceptions.InvalidMatrixException;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFileException;

public class CsvLoader extends BaseLoader {

    private final List<Praeceptum> praecepta = new ArrayList<>();

    public CsvLoader(String csvPath, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        readCsv(csvPath);
    }

    /**
     * Translate the CSV file into a collection of praecepta.
     *
     * @param csvPath the path to look after the CSV file
     */
    private void readCsv(String csvPath) {
        try {
            var lines = readLines(csvPath);
            var headers = readHeadersPositioning(lines);
            var fileRows = readRows(headers, lines);
            praecepta.addAll(parseRowsToPraecepta(fileRows));
        } catch (Exception e) {
            throw new InvalidMatrixException(csvPath, e);
        }
    }

    /**
     * Opens the CSV file.
     *
     * @param csvPath the file path
     * @return the list of lines
     */
    private List<String> readLines(String csvPath) {
        try {
            var lines = new ArrayList<String>();
            var classLoader = getClass().getClassLoader();
            try (
                var inputStream = classLoader.getResourceAsStream(csvPath);
                var br = new BufferedReader(new InputStreamReader(inputStream))
            ) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (Exception e) {
            throw new MissingFileException(csvPath, e);
        }
    }

    /**
     * Takes a look into the headers and validates the INPUT and OUTPUT classes.
     *
     * @param lines the list of lines to retrieve the header positioning
     * @return the mapping the headers (fields) and their position in the Excel file.
     */
    private Map<Integer, String> readHeadersPositioning(List<String> lines) {
        var headers = lines.get(0);
        var positioning = new HashMap<Integer, String>();
        var index = 0;
        for (String header : headers.split(";")) {
            positioning.put(index++, header.trim());
        }
        validateFields(positioning);
        lines.remove(0);
        return positioning;
    }

    /**
     * Translates the Excel rows into instances of Recards, for easier further conversion.
     *
     * @param headers the header positioning
     * @param rows    the list of lines or rows
     * @return collection of fileRows
     */
    private Iterable<FileRow> readRows(Map<Integer, String> headers, List<String> rows) {
        var fileRows = new ArrayList<FileRow>();
        var index = 0;
        for (String row : rows) {
            var fileRow = readCells(headers, row, index++);
            if (fileRow.isNotEmpty()) {
                fileRows.add(fileRow);
            }
        }
        return fileRows;
    }

    private FileRow readCells(Map<Integer, String> headers, String rowStr, int rowIndex) {
        var row = new FileRow(rowIndex);

        var colIndex = 0;
        for (String cell : rowStr.split(";")) {
            row.set(headers.get(colIndex++), cell.trim());
        }
        
        return row;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Praeceptum> getPraecepta() {
        return praecepta;
    }
}
