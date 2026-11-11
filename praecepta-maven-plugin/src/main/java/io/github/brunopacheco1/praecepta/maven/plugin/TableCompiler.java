/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.loader.csv.CsvLoader;
import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;

/**
 * Compiles one table: loads it, validates it with the engine, renders it as DMN and generates the
 * Java sources that load that DMN at runtime. Independent of Maven, so it can be tested directly.
 */
final class TableCompiler {

    /**
     * @param table      the table as loaded
     * @param className  simple name of the generated decision class
     * @param report     the engine's static analysis of the table
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
        return compile(config, packageName, failOn, null);
    }

    /**
     * @param fileHeader text put first in every generated source as comments (e.g. the user's SPDX
     *                   header), or null for none
     */
    static Result compile(TableConfig config, String packageName, Severity failOn, String fileHeader) {
        if (config.source == null) {
            throw new IllegalArgumentException("Every <table> needs a <source>");
        }
        var table = load(config);
        var className = JavaNames.type(config.name != null ? config.name : table.name());
        var report = validate(table, failOn);
        var dmn = DmnWriter.write(table);
        var sources = new SourceGenerator(packageName, className, table, config.source.getName(), fileHeader).generate();
        return new Result(table, className, report, dmn, sources);
    }

    // ---- loading ----------------------------------------------------------------------------------

    private static DecisionTable load(TableConfig config) {
        var file = config.source;
        if (!file.isFile()) {
            throw new IllegalArgumentException("Table source " + file + " does not exist");
        }
        var fileName = file.getName();
        var extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try (var stream = Files.newInputStream(file.toPath())) {
            return switch (extension) {
                case "dmn" -> dmn(config, stream);
                case "xlsx", "xls" -> spreadsheet(config, stream, false);
                case "csv" -> spreadsheet(config, stream, true);
                default -> throw new IllegalArgumentException("Table source %s: expected a .xlsx, .csv or .dmn file".formatted(file));
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read table source " + file + ": " + e.getMessage(), e);
        }
    }

    private static DecisionTable dmn(TableConfig config, InputStream stream) {
        if (config.hitPolicy != null || config.aggregation != null || !config.inputs.isEmpty() || !config.outputs.isEmpty()) {
            throw new IllegalArgumentException(("Table source %s: a DMN model declares its own hit policy and columns; "
                    + "remove <hitPolicy>, <aggregation>, <inputs> and <outputs>").formatted(config.source));
        }
        return DmnReader.read(stream, config.decision);
    }

    /** An Excel or CSV table: the pom declares its hit policy and columns, the file holds its rules. */
    private static DecisionTable spreadsheet(TableConfig config, InputStream stream, boolean csv) {
        var where = "Table source " + config.source;
        checkSpreadsheetConfig(config, where);
        var inputs = config.inputs.stream()
                .map(column -> new InputColumn(requiredName(column, where), column.type, column.allowedValues))
                .toList();
        var outputs = config.outputs.stream()
                .map(column -> new OutputColumn(requiredName(column, where), column.type, column.priority))
                .toList();
        var inputNames = new LinkedHashSet<>(inputs.stream().map(InputColumn::name).toList());
        var outputNames = new LinkedHashSet<>(outputs.stream().map(OutputColumn::name).toList());
        var fileName = config.source.getName();
        var rules = csv
                ? new CsvLoader(stream, fileName, inputNames, outputNames).getPraecepta()
                : new ExcelLoader(stream, fileName, inputNames, outputNames).getPraecepta();
        var tableName = fileName.substring(0, fileName.lastIndexOf('.'));
        var lists = config.inputs.stream().filter(column -> column.list).map(column -> column.name).collect(Collectors.toSet());
        if (config.outputs.stream().anyMatch(column -> column.list)) {
            throw new IllegalArgumentException(where + ": <list> only applies to inputs");
        }
        return new DecisionTable(tableName, config.hitPolicy, config.aggregation, inputs, outputs, rules, false, lists);
    }

    private static void checkSpreadsheetConfig(TableConfig config, String where) {
        if (config.decision != null) {
            throw new IllegalArgumentException(where + ": <decision> only applies to DMN sources");
        }
        if (config.hitPolicy == null) {
            throw new IllegalArgumentException(where + ": <hitPolicy> is required for Excel and CSV tables");
        }
        if (config.inputs.isEmpty() || config.outputs.isEmpty()) {
            throw new IllegalArgumentException(where + ": declare the <inputs> and <outputs> columns");
        }
    }

    private static String requiredName(ColumnConfig column, String where) {
        if (column.name == null || column.name.isBlank()) {
            throw new IllegalArgumentException(where + ": every column needs a <name>");
        }
        return column.name;
    }

    // ---- validation ---------------------------------------------------------------------------

    /**
     * Builds the engine as the generated code will: each input bound with the getter of its generated
     * Java type, so that times and date-times with a time zone, or without, are analysed as the record
     * gives them, and inputs that hold lists as lists. The getters are never called, so no classes are
     * needed yet. A string column with a value list is bound as a string: the table's value list is the
     * generated enum's constants.
     */
    private static ValidationReport validate(DecisionTable table, Severity failOn) {
        if (table.aggregation() != null
                && (table.outputs().size() != 1 || table.outputs().getFirst().type() != ColumnType.NUMBER)) {
            throw new IllegalArgumentException("Table '%s': %s aggregation needs exactly one NUMBER output"
                    .formatted(table.name(), table.aggregation()));
        }
        var builder = PraeceptaEngine.<Object, PraeceptaOutput>builder()
                .table(table)
                .output(new OutputStrategy<>() {
                    @Override
                    public PraeceptaOutput transform(PraeceptaOutput output) {
                        return output;
                    }

                    // a COUNT engine builds its no-match output up front; the generated strategy counts too
                    @Override
                    public PraeceptaOutput count(int matches) {
                        return new PraeceptaOutput(Map.of());
                    }
                });
        try {
            var zoned = TimeZones.of(table).inputs();
            var lists = table.listInputs();
            table.inputs().forEach(column -> bind(builder, column.name(), column.type(), zoned.contains(column.name()),
                    lists.contains(column.name())));
            table.namedInputs().forEach((name, type) -> bind(builder, name, type, zoned.contains(name), lists.contains(name)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage().startsWith("Table '") ? e.getMessage()
                    : "Table '%s': %s".formatted(table.name(), e.getMessage()), e);
        }
        if (failOn != null) {
            builder.failOn(failOn);
        }
        try {
            return builder.build().validationReport();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Table '%s': %s".formatted(table.name(), e.getMessage()), e);
        }
    }

    /**
     * Binds an input with a getter of the Java type generated code gives it, a {@code java.util.List} of
     * that type for an input that holds lists; validation never calls it.
     */
    private static void bind(PraeceptaEngine.Builder<Object, PraeceptaOutput> builder, String name, ColumnType type, boolean zoned,
            boolean list) {
        if (list) {
            if (type == ColumnType.STRING) {
                builder.listInput(name, input -> List.<String>of());
            } else {
                builder.listInput(name, type, input -> List.of());
            }
            return;
        }
        switch (type) {
            case STRING -> builder.input(name, input -> null);
            case NUMBER -> builder.numberInput(name, input -> null);
            case DATE -> builder.dateInput(name, input -> null);
            case BOOLEAN -> builder.booleanInput(name, input -> null);
            case TIME -> {
                if (zoned) {
                    builder.offsetTimeInput(name, input -> null);
                } else {
                    builder.timeInput(name, input -> null);
                }
            }
            case DATE_TIME -> {
                if (zoned) {
                    builder.zonedDateTimeInput(name, input -> null);
                } else {
                    builder.dateTimeInput(name, input -> null);
                }
            }
            case DAY_TIME_DURATION -> builder.durationInput(name, input -> null);
            case YEAR_MONTH_DURATION -> builder.periodInput(name, input -> null);
        }
    }
}
