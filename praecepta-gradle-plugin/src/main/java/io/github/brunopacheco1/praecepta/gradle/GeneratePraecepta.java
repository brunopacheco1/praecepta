/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.gradle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import io.github.brunopacheco1.praecepta.compiler.ColumnDefinition;
import io.github.brunopacheco1.praecepta.compiler.CompiledTable;
import io.github.brunopacheco1.praecepta.compiler.CompilerOptions;
import io.github.brunopacheco1.praecepta.compiler.TableCompiler;
import io.github.brunopacheco1.praecepta.compiler.TableDefinition;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * Compiles decision tables, as the Maven plugin's {@code generate} goal does. For each table it:
 * <ol>
 * <li>loads it from Excel, CSV or DMN;</li>
 * <li>validates it with the engine (overlaps, unreachable rules, gaps), failing the build at {@code failOn}
 * severity and logging the rest as warnings;</li>
 * <li>writes it as a DMN resource ({@code <package>/<Name>.dmn}) under {@link #getResourceDirectory()};</li>
 * <li>generates {@code <Name>Input} and {@code <Name>Output} records and a {@code <Name>} class that evaluates the
 * DMN resource under {@link #getSourceDirectory()}, so applications need only {@code praecepta-engine} at
 * runtime.</li>
 * </ol>
 * The task is cacheable: its inputs are the table files (by name and content) and the settings, so moving the
 * project does not invalidate it. Each run first empties both output directories, so a table that was removed
 * leaves no class behind.
 */
@CacheableTask
public abstract class GeneratePraecepta extends DefaultTask {

    /** The tool the generated sources name. */
    static final String GENERATOR = "praecepta-gradle-plugin";

    /**
     * How messages name the settings: quoted, with the Maven plugin's {@code <name>} as {@code 'className'}, since
     * {@code name} is the table's name in its container.
     */
    static final UnaryOperator<String> SETTING_NAMES = setting -> switch (setting) {
        case "name" -> "'className'";
        case "table" -> "table";
        default -> "'" + setting + "'";
    };

    /** Creates the task; Gradle does when the plugin registers it. */
    @SuppressWarnings("java:S5993") // Gradle creates its managed types through public constructors only
    public GeneratePraecepta() {
        // Gradle implements the properties
    }

    /**
     * Package of the generated classes and DMN resources; empty for the default package.
     *
     * @return the package name
     */
    @Input
    public abstract Property<String> getPackageName();

    /**
     * The validation severity that fails the build: {@code ERROR}, {@code WARNING} or {@code NONE}, in any case.
     *
     * @return the severity
     */
    @Input
    public abstract Property<String> getFailOn();

    /**
     * Text put first in every generated source, as {@code //} comments; none when absent.
     *
     * @return the header
     */
    @Input
    @Optional
    public abstract Property<String> getFileHeader();

    /**
     * Whether to write nothing.
     *
     * @return whether to skip
     */
    @Input
    public abstract Property<Boolean> getSkip();

    /**
     * The tables to compile.
     *
     * @return the tables
     */
    @Nested
    public abstract ListProperty<TableSpec> getTables();

    /**
     * Where the Java sources go; {@code build/generated/sources/praecepta/java/main} by default.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getSourceDirectory();

    /**
     * Where the DMN resources go; {@code build/generated/resources/praecepta/main} by default.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getResourceDirectory();

    /**
     * Empties the output directories.
     *
     * @return Gradle's file operations
     */
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /** Compiles every table and writes the results; a table that cannot be compiled fails the build. */
    @TaskAction
    public void generate() {
        var sourceRoot = getSourceDirectory().get().getAsFile().toPath();
        var resourceRoot = getResourceDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(sourceRoot.toFile(), resourceRoot.toFile()));
        if (getSkip().get().booleanValue()) {
            getLogger().lifecycle("Skipping Praecepta table generation");
            return;
        }
        var tables = getTables().get();
        if (tables.isEmpty()) {
            throw new GradleException("Register at least one table: praecepta { tables { register(\"discount\") { ... } } }");
        }
        var packageName = getPackageName().get();
        var options = CompilerOptions.of(packageName, GENERATOR)
                .withFailOn(severity(getFailOn().get()))
                .withFileHeader(getFileHeader().getOrNull())
                .withSettingNames(SETTING_NAMES);
        var classNames = new HashSet<String>();
        for (var table : tables) {
            var compiled = compile(table, options);
            if (!classNames.add(compiled.className())) {
                throw new GradleException("Two tables generate the class %s; set %s on one"
                        .formatted(compiled.className(), SETTING_NAMES.apply("name")));
            }
            compiled.report().issues().forEach(issue -> getLogger().warn(compiled.className() + ": " + issue));
            write(compiled, sourceRoot, resourceRoot);
            getLogger().info("Compiled table {} ({} rules, {}) into {}", table.getName(), compiled.table().rules().size(),
                    compiled.table().hitPolicy(), packageName.isEmpty() ? compiled.className() : packageName + "." + compiled.className());
        }
    }

    /** Compiles one table; a configuration or table mistake fails the build with the compiler's message. */
    private static CompiledTable compile(TableSpec table, CompilerOptions options) {
        try {
            return TableCompiler.compile(definition(table), options);
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage(), e);
        }
    }

    private static void write(CompiledTable compiled, Path sourceRoot, Path resourceRoot) {
        try {
            compiled.write(sourceRoot, resourceRoot);
        } catch (IOException e) {
            throw new GradleException("Cannot write generated files: " + e.getMessage(), e);
        }
    }

    /** The table as praecepta-compiler takes it. */
    static TableDefinition definition(TableSpec table) {
        var where = "Table '" + table.getName() + "'";
        var source = table.getSource().isPresent() ? table.getSource().get().getAsFile().toPath() : null;
        return new TableDefinition(source, table.getClassName().getOrNull(), table.getDecision().getOrNull(),
                parse(HitPolicy.class, table.getHitPolicy().getOrNull(), where, "hitPolicy"),
                parse(AggregationOperator.class, table.getAggregation().getOrNull(), where, "aggregation"),
                columns(table.getInputs(), where), columns(table.getOutputs(), where));
    }

    private static List<ColumnDefinition> columns(Columns columns, String where) {
        return columns.getColumns().stream()
                .map(column -> new ColumnDefinition(column.getName(),
                        parse(ColumnType.class, column.getType().get(), where + ", column '" + column.getName() + "'", "type"),
                        column.getAllowedValues().getOrNull(), column.getPriority().get(), column.getList().get()))
                .toList();
    }

    /** A setting's value as one of the engine's constants, in any case; null when it is not set. */
    static <E extends Enum<E>> E parse(Class<E> type, String value, String where, String setting) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            var names = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new GradleException("%s: %s must be one of %s, not %s".formatted(where, SETTING_NAMES.apply(setting), names, value), e);
        }
    }

    /** The severity {@code failOn} names, as every integration reads it ({@link Severity#failOn}); null for NONE. */
    static Severity severity(String value) {
        try {
            return Severity.failOn(value);
        } catch (IllegalArgumentException e) {
            throw new GradleException(SETTING_NAMES.apply("failOn") + " " + e.getMessage(), e);
        }
    }
}
