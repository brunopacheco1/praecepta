package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.loader.csv.CsvLoader;
import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;

/**
 * Compiles one table: loads it, validates it with the engine, renders it as DMN and generates the
 * Java sources that load that DMN at runtime. Independent of Maven, so it can be tested directly.
 */
final class TableCompiler {

    /**
     * @param className  simple name of the generated decision class
     * @param dmn        the table as DMN, to ship as {@code <className>.dmn} next to the classes
     * @param sources    generated Java sources by simple class name
     */
    record Result(DecisionTable table, String className, ValidationReport report, String dmn, Map<String, String> sources) {
    }

    private TableCompiler() {
    }

    /**
     * @throws IllegalArgumentException with a readable message if the configuration or the table is invalid,
     *                                  or if validation finds issues at least as severe as {@code failOn}
     */
    static Result compile(TableConfig config, String packageName, Severity failOn) {
        if (config.source == null) {
            throw new IllegalArgumentException("Every <table> needs a <source>");
        }
        var table = load(config);
        var className = JavaNames.type(config.name != null ? config.name : table.name());
        var report = validate(table, failOn);
        var dmn = DmnWriter.write(table);
        var sources = new SourceGenerator(packageName, className, table, config.source.getName()).generate();
        return new Result(table, className, report, dmn, sources);
    }

    // ---- loading ----------------------------------------------------------------------------------

    static DecisionTable load(TableConfig config) {
        var file = config.source;
        if (!file.isFile()) {
            throw new IllegalArgumentException("Table source " + file + " does not exist");
        }
        var fileName = file.getName();
        var extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try (var stream = Files.newInputStream(file.toPath())) {
            if (extension.equals("dmn")) {
                rejectForDmn(config);
                return DmnReader.read(stream, config.decision);
            }
            if (!extension.equals("xlsx") && !extension.equals("xls") && !extension.equals("csv")) {
                throw new IllegalArgumentException("Table source %s: expected a .xlsx, .csv or .dmn file".formatted(file));
            }
            return spreadsheet(config, stream, extension.equals("csv"));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read table source " + file + ": " + e.getMessage(), e);
        }
    }

    private static void rejectForDmn(TableConfig config) {
        if (config.hitPolicy != null || config.aggregation != null || !config.inputs.isEmpty() || !config.outputs.isEmpty()) {
            throw new IllegalArgumentException("Table source %s: a DMN model declares its own hit policy and columns; remove "
                    .formatted(config.source) + "<hitPolicy>, <aggregation>, <inputs> and <outputs>");
        }
    }

    private static DecisionTable spreadsheet(TableConfig config, InputStream stream, boolean csv) {
        var where = "Table source " + config.source;
        if (config.decision != null) {
            throw new IllegalArgumentException(where + ": <decision> only applies to DMN sources");
        }
        if (config.hitPolicy == null) {
            throw new IllegalArgumentException(where + ": <hitPolicy> is required for Excel and CSV tables");
        }
        if (config.inputs.isEmpty() || config.outputs.isEmpty()) {
            throw new IllegalArgumentException(where + ": declare the <inputs> and <outputs> columns");
        }
        var inputs = config.inputs.stream()
                .map(column -> new InputColumn(required(column, where), column.type, column.allowedValues))
                .toList();
        var outputs = config.outputs.stream()
                .map(column -> new OutputColumn(required(column, where), column.type, column.priority))
                .toList();
        var inputNames = new LinkedHashSet<>(inputs.stream().map(InputColumn::name).toList());
        var outputNames = new LinkedHashSet<>(outputs.stream().map(OutputColumn::name).toList());
        var source = config.source.getName();
        var rules = csv
                ? new CsvLoader(stream, source, inputNames, outputNames).getPraecepta()
                : new ExcelLoader(stream, source, inputNames, outputNames).getPraecepta();
        var name = source.substring(0, source.lastIndexOf('.'));
        return new DecisionTable(name, config.hitPolicy, config.aggregation, inputs, outputs, rules);
    }

    private static String required(ColumnConfig column, String where) {
        if (column.name == null || column.name.isBlank()) {
            throw new IllegalArgumentException(where + ": every column needs a <name>");
        }
        return column.name;
    }

    // ---- validation ---------------------------------------------------------------------------

    /** Builds the engine exactly as the generated code will (map-based, so no classes are needed yet). */
    private static ValidationReport validate(DecisionTable table, Severity failOn) {
        if (table.aggregation() != null
                && (table.outputs().size() != 1 || table.outputs().getFirst().type() != ColumnType.NUMBER)) {
            throw new IllegalArgumentException("Table '%s': %s aggregation needs exactly one NUMBER output"
                    .formatted(table.name(), table.aggregation()));
        }
        var builder = PraeceptaEngine.<java.util.Map<String, String>, PraeceptaOutput>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output);
        if (failOn != null) {
            builder.failOn(failOn);
        }
        try {
            return builder.build().validationReport();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Table '%s': %s".formatted(table.name(), e.getMessage()), e);
        }
    }

    // ---- shared helpers ---------------------------------------------------------------------------

    /** The values of a string column's value list, or null if it has none (it then stays a String). */
    static List<String> enumValues(InputColumn column) {
        if (column.type() != ColumnType.STRING || column.allowedValues() == null) {
            return null;
        }
        var tests = (UnaryTests.Positive) CellParser.parse(column.allowedValues(), ColumnType.STRING);
        var values = new LinkedHashMap<String, Boolean>();
        tests.tests().forEach(test -> values.put((String) ((UnaryTests.Equals) test).value(), true));
        return List.copyOf(values.keySet());
    }

    static boolean singleResult(DecisionTable table) {
        return table.hitPolicy().isSingleHit() || table.aggregation() != null;
    }
}
