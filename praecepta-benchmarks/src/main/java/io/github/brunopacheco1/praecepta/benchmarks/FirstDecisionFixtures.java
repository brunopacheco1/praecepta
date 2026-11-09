/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;

/**
 * Writes the inputs of {@link FirstDecisionBenchmark}: for each table, its DMN document
 * ({@code <table>.dmn}) and the first input of its workload ({@code <table>.input}, one
 * {@code column, type, value} line per column, tab-separated). Arguments: the directory, then the
 * tables (by default {@code real}, {@code mixed-100} and {@code mixed-1000}).
 */
public final class FirstDecisionFixtures {

    private FirstDecisionFixtures() {
    }

    public static void main(String[] args) {
        var directory = Path.of(args.length > 0 ? args[0] : "benchmark-fixtures");
        var tables = args.length > 1 ? List.of(args).subList(1, args.length) : List.of("real", "mixed-100", "mixed-1000");
        try {
            Files.createDirectories(directory);
            for (var name : tables) {
                var table = Table.named(name);
                Files.writeString(directory.resolve(name + ".dmn"), DmnWriter.write(table.decisionTable()));
                Files.write(directory.resolve(name + ".input"), firstInputLines(table));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The first input of the table's workload, one {@code column, type, value} line per column. */
    private static List<String> firstInputLines(Table table) {
        var first = Workload.generate(table, 1, 7).getFirst();
        var lines = new ArrayList<String>();
        for (int c = 0; c < table.inputs().size(); c++) {
            var column = table.inputs().get(c);
            var value = first.get(column);
            lines.add(column + "\t" + table.types().get(c) + "\t" + (value == null ? FirstDecisionBenchmark.NULL : value));
        }
        return lines;
    }
}
