/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.github.brunopacheco1.praecepta.compiler.ColumnDefinition;
import io.github.brunopacheco1.praecepta.compiler.CompilerOptions;
import io.github.brunopacheco1.praecepta.compiler.TableCompiler;
import io.github.brunopacheco1.praecepta.compiler.TableDefinition;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaBuildTimeConfig.ColumnConfig;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaBuildTimeConfig.TableConfig;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaTablesBuildItem.Table;

/**
 * Compiles the tables declared under {@code quarkus.praecepta.tables} with {@code praecepta-compiler}, as the
 * Maven and Gradle plugins do, and keeps the DMN each one compiles to. No classes are generated: the
 * application gets the same map-based engines as for DMN files.
 */
final class DeclaredTables {

    /** The tool named in the compiler's output. */
    static final String GENERATOR = "praecepta-quarkus";

    /** The configuration's spelling of the compiler's settings (the Maven plugin's names, in kebab-case). */
    private static final Map<String, String> SETTINGS = Map.of(
            "table", "table",
            "hitPolicy", "hit-policy",
            "allowedValues", "allowed-values",
            "fileHeader", "file-header");

    private static final UnaryOperator<String> KEBAB_CASE = setting -> "'" + SETTINGS.getOrDefault(setting, setting) + "'";

    private DeclaredTables() {
    }

    /** Where a configured source is: absolute, or relative to the project's directory. */
    static Path resolve(String source, Path projectDirectory) {
        var path = Path.of(source);
        return path.isAbsolute() ? path : projectDirectory.resolve(path).normalize();
    }

    /**
     * Compiles one declared table. Validation issues never fail here: the build step judges them on the
     * engine the application gets, as for DMN files.
     *
     * @param key              the table's key under {@code quarkus.praecepta.tables}
     * @param projectDirectory what relative sources are resolved against
     * @throws IllegalArgumentException naming the table's key, for any configuration or table mistake
     */
    static Table compile(String key, TableConfig config, Path projectDirectory) {
        var source = config.source().map(path -> resolve(path, projectDirectory)).orElse(null);
        var definition = new TableDefinition(source, null, config.decision().orElse(null), config.hitPolicy().orElse(null),
                config.aggregation().orElse(null), columns(config.inputs().orElse(List.of())),
                columns(config.outputs().orElse(List.of())));
        var options = CompilerOptions.of("", GENERATOR).withFailOn(null).withSettingNames(KEBAB_CASE);
        try {
            var compiled = TableCompiler.compile(definition, options);
            return new Table(source.toString(), compiled.dmn().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("quarkus.praecepta.tables.\"%s\": %s".formatted(key, e.getMessage()), e);
        }
    }

    private static List<ColumnDefinition> columns(List<ColumnConfig> columns) {
        return columns.stream()
                .map(column -> new ColumnDefinition(column.name(), column.type(), column.allowedValues().orElse(null),
                        column.priority().orElse(List.of()), column.list()))
                .toList();
    }
}
