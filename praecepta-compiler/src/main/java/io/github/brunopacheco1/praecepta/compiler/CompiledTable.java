/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;

/**
 * A compiled table, or a compiled decision model: what a build tool writes and reports.
 *
 * @param table       the table as loaded; null for a decision model compiled as a decision requirements graph
 * @param packageName package of the generated classes and DMN resource
 * @param className   simple name of the generated decision class; of a decision model, of its model class
 *                    ({@code CreditModel}), which loads the DMN resource
 * @param report      the engine's static analysis of the table, to report as the tool's warnings; of a decision model,
 *                    its graph's validation report (cycles, missing requirements, type mismatches)
 * @param dmn         the table, or the decision model, as DMN, to ship as {@link #resourcePath()} next to the classes
 * @param sources     generated Java sources by simple class name
 * @param model       the decision model compiled as a graph; null for a table
 * @param reports     every report to give as the tool's warnings, by the generated class it concerns: the table's by
 *                    {@code className}; of a decision model, its graph's by the model class and each decision table's
 *                    by its decision's class
 */
public record CompiledTable(DecisionTable table, String packageName, String className, ValidationReport report, String dmn,
        Map<String, String> sources, DecisionModel model, Map<String, ValidationReport> reports) {

    public CompiledTable {
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(reports));
    }

    /** A compiled table: its report is the only one, by its class. */
    public CompiledTable(DecisionTable table, String packageName, String className, ValidationReport report, String dmn,
            Map<String, String> sources) {
        this(table, packageName, className, report, dmn, sources, null, Map.of(className, report));
    }

    /**
     * The classes generated: {@link #className()} first, then the others, as a build tool checks that no two tables
     * generate one class.
     */
    public List<String> classNames() {
        var names = new ArrayList<String>();
        names.add(className);
        sources.keySet().stream().filter(name -> !name.equals(className)).forEach(names::add);
        return List.copyOf(names);
    }

    /**
     * What was compiled, for a build tool's log: {@code 3 rules, FIRST} for a table, {@code 3 decisions} for a decision
     * model (those of its model, of which the generated classes evaluate the ones compiled).
     */
    public String summary() {
        return table != null ? "%d rules, %s".formatted(table.rules().size(), table.hitPolicy())
                : "%d decisions".formatted(model.decisions().size());
    }

    /**
     * Where the generated decision class reads the DMN from: {@code com/acme/rules/Discount.dmn}, a class path
     * resource (and so the file to list in a native image's resource configuration).
     */
    public String resourcePath() {
        return packagePath() + className + ".dmn";
    }

    /**
     * Writes the DMN resource under {@code resourceRoot} and the Java sources under {@code sourceRoot}, each
     * in the package's directory.
     *
     * @return the files written: the DMN resource, then the sources
     */
    public List<Path> write(Path sourceRoot, Path resourceRoot) throws IOException {
        var written = new ArrayList<Path>();
        written.add(write(resourceRoot.resolve(resourcePath()), dmn));
        for (var source : sources.entrySet()) {
            written.add(write(sourceRoot.resolve(packagePath() + source.getKey() + ".java"), source.getValue()));
        }
        return written;
    }

    private String packagePath() {
        return packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
