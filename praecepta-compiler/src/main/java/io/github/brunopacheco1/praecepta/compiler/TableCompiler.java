/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Compiles one table: loads it, validates it with the engine, renders it as DMN and generates the Java
 * sources that load that DMN at runtime. Independent of any build tool: the Maven plugin (and the Gradle
 * plugin and Quarkus extension) turn their configuration into a {@link TableDefinition} and write the
 * {@link CompiledTable} where their build expects it.
 *
 * <pre>{@code
 * var options = CompilerOptions.of("com.acme.pricing", "praecepta-gradle-plugin").withFailOn(Severity.WARNING);
 * var compiled = TableCompiler.compile(TableDefinition.dmn(Path.of("src/main/rules/shipping.dmn")), options);
 * compiled.report().issues().forEach(issue -> log.warn(compiled.className() + ": " + issue));
 * compiled.write(generatedSources, generatedResources);
 * }</pre>
 */
public final class TableCompiler {

    private TableCompiler() {
    }

    /**
     * Compiles one table.
     *
     * @return the loaded table, its validation report, its DMN and the generated sources
     * @throws IllegalArgumentException with a readable message, naming settings as
     *                                  {@link CompilerOptions#settingNames()} does, if the definition or the
     *                                  table is invalid, or if validation finds issues at least as severe as
     *                                  {@link CompilerOptions#failOn()}
     */
    public static CompiledTable compile(TableDefinition definition, CompilerOptions options) {
        if (definition.source() == null) {
            throw new IllegalArgumentException("Every %s needs a %s".formatted(options.setting("table"), options.setting("source")));
        }
        var table = load(definition, options);
        var className = JavaNames.type(definition.name() != null ? definition.name() : table.name());
        var report = validate(table, options.failOn());
        var dmn = DmnWriter.write(table);
        var sources = new SourceGenerator(options, className, table, fileName(definition.source())).generate();
        return new CompiledTable(table, options.packageName(), className, report, dmn, sources);
    }

    // ---- loading ----------------------------------------------------------------------------------

    private static DecisionTable load(TableDefinition definition, CompilerOptions options) {
        var file = definition.source();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Table source " + file + " does not exist");
        }
        try (var stream = Files.newInputStream(file)) {
            return switch (extension(file)) {
                case "dmn" -> dmn(definition, stream, options);
                case "xlsx", "xls" -> spreadsheet(definition, stream, false, options);
                case "csv" -> spreadsheet(definition, stream, true, options);
                default -> throw new IllegalArgumentException("Table source %s: expected a .xlsx, .csv or .dmn file".formatted(file));
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read table source " + file + ": " + e.getMessage(), e);
        }
    }

    private static String fileName(Path file) {
        return file.getFileName().toString();
    }

    private static String extension(Path file) {
        var fileName = fileName(file);
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static DecisionTable dmn(TableDefinition definition, InputStream stream, CompilerOptions options) {
        if (definition.hitPolicy() != null || definition.aggregation() != null || !definition.inputs().isEmpty()
                || !definition.outputs().isEmpty()) {
            throw new IllegalArgumentException(("Table source %s: a DMN model declares its own hit policy and columns; "
                    + "remove %s, %s, %s and %s").formatted(definition.source(), options.setting("hitPolicy"),
                            options.setting("aggregation"), options.setting("inputs"), options.setting("outputs")));
        }
        return DmnReader.read(stream, definition.decision());
    }

    /** An Excel or CSV table: the build declares its hit policy and columns, the file holds its rules. */
    private static DecisionTable spreadsheet(TableDefinition definition, InputStream stream, boolean csv, CompilerOptions options) {
        var where = "Table source " + definition.source();
        checkSpreadsheetDefinition(definition, where, options);
        var inputs = definition.inputs().stream()
                .map(column -> new InputColumn(requiredName(column, where, options), column.type(), column.allowedValues()))
                .toList();
        var outputs = definition.outputs().stream()
                .map(column -> new OutputColumn(requiredName(column, where, options), column.type(), column.priority()))
                .toList();
        var inputNames = new LinkedHashSet<>(inputs.stream().map(InputColumn::name).toList());
        var outputNames = new LinkedHashSet<>(outputs.stream().map(OutputColumn::name).toList());
        var fileName = fileName(definition.source());
        var rules = csv
                ? new CsvLoader(stream, fileName, inputNames, outputNames).getPraecepta()
                : new ExcelLoader(stream, fileName, inputNames, outputNames).getPraecepta();
        var tableName = fileName.substring(0, fileName.lastIndexOf('.'));
        var lists = definition.inputs().stream().filter(ColumnDefinition::list).map(ColumnDefinition::name)
                .collect(Collectors.toSet());
        if (definition.outputs().stream().anyMatch(ColumnDefinition::list)) {
            throw new IllegalArgumentException(where + ": %s only applies to inputs".formatted(options.setting("list")));
        }
        return new DecisionTable(tableName, definition.hitPolicy(), definition.aggregation(), inputs, outputs, rules, false, lists);
    }

    private static void checkSpreadsheetDefinition(TableDefinition definition, String where, CompilerOptions options) {
        if (definition.decision() != null) {
            throw new IllegalArgumentException(where + ": %s only applies to DMN sources".formatted(options.setting("decision")));
        }
        if (definition.hitPolicy() == null) {
            throw new IllegalArgumentException(where + ": %s is required for Excel and CSV tables".formatted(options.setting("hitPolicy")));
        }
        if (definition.inputs().isEmpty() || definition.outputs().isEmpty()) {
            throw new IllegalArgumentException(where + ": declare the %s and %s columns"
                    .formatted(options.setting("inputs"), options.setting("outputs")));
        }
    }

    private static String requiredName(ColumnDefinition column, String where, CompilerOptions options) {
        if (column.name() == null || column.name().isBlank()) {
            throw new IllegalArgumentException(where + ": every column needs a %s".formatted(options.setting("name")));
        }
        return column.name();
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
