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
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;

/**
 * A compiled table: what a build tool writes and reports.
 *
 * @param table       the table as loaded
 * @param packageName package of the generated classes and DMN resource
 * @param className   simple name of the generated decision class
 * @param report      the engine's static analysis of the table, to report as the tool's warnings
 * @param dmn         the table as DMN, to ship as {@link #resourcePath()} next to the classes
 * @param sources     generated Java sources by simple class name
 */
public record CompiledTable(DecisionTable table, String packageName, String className, ValidationReport report, String dmn,
        Map<String, String> sources) {

    public CompiledTable {
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
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
