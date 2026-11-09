/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader.csv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

/**
 * Reads rules from semicolon-separated CSV: a header row naming the columns, then one rule per row,
 * numbered from 0. Cells are trimmed, and empty cells are null; blank rows are skipped.
 */
public final class CsvLoader extends BaseLoader {

    private static final String SEPARATOR = ";";

    private final List<Praeceptum> praecepta;

    /** Reads a CSV file from the classpath. */
    public CsvLoader(String csvPath, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        praecepta = read(csvPath, null);
    }

    /**
     * Reads CSV (UTF-8) from a stream, which is closed afterwards.
     *
     * @param source names the data in error messages, e.g. its file name
     */
    public CsvLoader(InputStream csv, String source, Set<String> inputs, Set<String> outputs) {
        super(inputs, outputs);
        praecepta = read(source, csv);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Praeceptum> getPraecepta() {
        return praecepta;
    }

    /**
     * @param source the classpath resource to read if {@code stream} is null; else names the stream in error messages
     */
    private List<Praeceptum> read(String source, InputStream stream) {
        try {
            var lines = readLines(source, stream);
            var headers = readHeaders(lines.get(0));
            validateFields(headers);
            return parseRowsToPraecepta(readRows(headers, lines.subList(1, lines.size())));
        } catch (Exception e) {
            throw new InvalidMatrixException(source, e);
        }
    }

    private List<String> readLines(String source, InputStream stream) {
        var lines = new ArrayList<String>();
        try (
            var inputStream = stream != null ? stream : getClass().getClassLoader().getResourceAsStream(source);
            var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (Exception e) {
            throw new MissingFileException(source, e);
        }
    }

    /** Each column's name by its position. */
    private static Map<Integer, String> readHeaders(String headerLine) {
        var headers = new HashMap<Integer, String>();
        var position = 0;
        for (var header : headerLine.split(SEPARATOR)) {
            headers.put(position++, header.trim());
        }
        return headers;
    }

    /** The rows that have a value, numbered from 0 after the header. */
    private static List<FileRow> readRows(Map<Integer, String> headers, List<String> lines) {
        var rows = new ArrayList<FileRow>();
        for (int rowNumber = 0; rowNumber < lines.size(); rowNumber++) {
            var row = readRow(headers, lines.get(rowNumber), rowNumber);
            if (row.isNotEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static FileRow readRow(Map<Integer, String> headers, String line, int rowNumber) {
        var row = new FileRow(rowNumber);
        var position = 0;
        // split drops trailing empty cells, so a row ending in empty cells lacks those columns and is rejected
        for (var cell : line.split(SEPARATOR)) {
            row.set(headers.get(position++), cell.trim());
        }
        return row;
    }
}
