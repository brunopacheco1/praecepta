/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.beans.FileRow;
import io.github.brunopacheco1.praecepta.loader.exceptions.MissingFieldException;

/**
 * A loader of a file with one header row naming the columns, then one rule per row. A subclass reads
 * its file in the constructor: it checks the header with {@link #validateFields} and turns the rows
 * into rules with {@link #parseRowsToPraecepta}.
 */
public abstract class BaseLoader implements PraeceptaLoader {

    private final Set<String> inputs;
    private final Set<String> outputs;

    /**
     * @param inputs  the names of the input columns
     * @param outputs the names of the output columns
     */
    protected BaseLoader(Set<String> inputs, Set<String> outputs) {
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /**
     * Checks that the header names every input and output column.
     *
     * @param positioning the header: each column's name by its position
     * @throws MissingFieldException naming the columns that are missing
     */
    protected final void validateFields(Map<Integer, String> positioning) {
        var missingFields = Stream.concat(inputs.stream(), outputs.stream())
                .filter(Predicate.not(positioning::containsValue))
                .collect(Collectors.toSet());

        if (!missingFields.isEmpty()) {
            throw new MissingFieldException(missingFields);
        }
    }

    /**
     * Turns each row into a rule, numbered by its row number. Every row must have a cell, possibly
     * empty, for every input and output column.
     *
     * @param records the rows read from the file, without the header
     */
    protected final List<Praeceptum> parseRowsToPraecepta(Iterable<FileRow> records) {
        var praecepta = new ArrayList<Praeceptum>();
        for (var row : records) {
            praecepta.add(new Praeceptum(row.getRowNumber(), new PraeceptaInput(row.get(inputs)), new PraeceptaOutput(row.get(outputs))));
        }
        return praecepta;
    }
}
