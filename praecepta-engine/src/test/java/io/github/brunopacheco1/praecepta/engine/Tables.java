/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/** Small decision tables in strict FEEL, as a DMN file holds them, for tests of decision graphs. */
final class Tables {

    private Tables() {
    }

    /**
     * A table whose cells are strict FEEL.
     *
     * @param inputs  the columns, as {@code name:type}, the type a FEEL type name ({@code number}) or {@code list<string>}
     * @param outputs the outputs, as {@code name:type}
     * @param rows    each rule's input entries, then its output entries: FEEL literals ({@code "adult"}, {@code 0.1}), or
     *                expressions, kept as written, when they are not literals
     */
    static DecisionTable table(String name, String hitPolicy, List<String> inputs, List<String> outputs, List<List<String>> rows) {
        var columns = new ArrayList<DecisionTable.InputColumn>();
        var lists = new HashSet<String>();
        for (var input : inputs) {
            var parts = input.split(":");
            var type = parts[1];
            if (type.startsWith("list<")) {
                type = type.substring(5, type.length() - 1);
                lists.add(parts[0]);
            }
            columns.add(new DecisionTable.InputColumn(parts[0], type(type)));
        }
        var outputColumns = outputs.stream().map(output -> output.split(":"))
                .map(parts -> new DecisionTable.OutputColumn(parts[0], type(parts[1]))).toList();
        var rules = new ArrayList<Praeceptum>();
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                cells.put(columns.get(c).name(), row.get(c));
            }
            var values = new HashMap<String, String>();
            var expressions = new HashMap<String, String>();
            for (int o = 0; o < outputColumns.size(); o++) {
                var entry = row.get(columns.size() + o);
                var column = outputColumns.get(o);
                if (entry.startsWith("\"") && entry.endsWith("\"")) {
                    values.put(column.name(), entry.substring(1, entry.length() - 1));
                } else if (entry.matches("-?[0-9.]+|true|false")) {
                    values.put(column.name(), entry);
                } else if (entry.equals("null")) {
                    values.put(column.name(), null);
                } else {
                    expressions.put(column.name(), entry);
                }
            }
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(values, Map.of(), expressions)));
        }
        return new DecisionTable(name, HitPolicy.valueOf(hitPolicy.replace(' ', '_')), null, columns, outputColumns, rules, true, lists);
    }

    /** The table, whose one output declares a default value. */
    static DecisionTable withDefault(DecisionTable table, String value) {
        var output = table.outputs().getFirst();
        var outputs = List.of(new DecisionTable.OutputColumn(output.name(), output.type(), output.values(), value));
        return new DecisionTable(table.name(), table.hitPolicy(), table.aggregation(), table.inputs(), outputs, table.rules(), table.feelCells(),
                table.listInputs());
    }

    static ColumnType type(String feelName) {
        return switch (feelName.toLowerCase(Locale.ROOT)) {
            case "string" -> ColumnType.STRING;
            case "number" -> ColumnType.NUMBER;
            case "boolean" -> ColumnType.BOOLEAN;
            case "date" -> ColumnType.DATE;
            case "time" -> ColumnType.TIME;
            case "datetime" -> ColumnType.DATE_TIME;
            case "duration" -> ColumnType.DAY_TIME_DURATION;
            default -> throw new IllegalArgumentException(feelName);
        };
    }
}
