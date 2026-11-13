/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * Pins the generated sources and DMN byte for byte against files under {@code src/test/resources/golden}, so a
 * stray space or blank line in a template fails the build. After an intended change to the generated code,
 * regenerate them with:
 *
 * <pre>
 * mvn -pl praecepta-compiler -am test -Dtest=GeneratedSourcesGoldenTest -Dsurefire.failIfNoSpecifiedTests=false -Dgolden.update=true
 * </pre>
 */
class GeneratedSourcesGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden");

    static List<Arguments> tables() {
        // an aggregating COLLECT table with an enum column and a file header of two lines
        var fees = new TableDefinition(table("fees.csv"), "Fees", null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnDefinition("zone", ColumnType.STRING, "\"islands\",\"mainland\"", List.of())),
                List.of(new ColumnDefinition("fee", ColumnType.NUMBER, null, List.of())));
        // a single-hit table with typed columns and an enum, in the default package, with no header
        var discount = new TableDefinition(table("discount.csv"), "Discount", null, HitPolicy.FIRST, null,
                List.of(new ColumnDefinition("country", ColumnType.STRING, "\"PT\",\"ES\",\"FR\"", List.of()),
                        new ColumnDefinition("age", ColumnType.NUMBER, "[0..120]", List.of()),
                        new ColumnDefinition("since", ColumnType.DATE, null, List.of())),
                List.of(new ColumnDefinition("discount", ColumnType.NUMBER, null, List.of()),
                        new ColumnDefinition("tier", ColumnType.STRING, null, List.of())));
        // decision requirements graphs (#14): a chain of tables and a literal expression, and a table whose two outputs
        // make a context, read by path downstream
        var credit = TableDefinition.dmn(table("graph/chain.dmn"));
        var pricing = TableDefinition.dmn(table("graph/multi-output.dmn"));
        return List.of(
                Arguments.of("fees", fees, "com.acme.rules", "Golden test header\nsecond line"),
                Arguments.of("discount", discount, "", null),
                Arguments.of("credit", credit, "com.acme.credit", "Golden test header"),
                Arguments.of("pricing", pricing, "", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void generated_files_match_the_golden_files(String name, TableDefinition config, String packageName, String header)
            throws IOException {
        var result = TableCompilerTest.compile(config, packageName, Severity.ERROR, header);
        var generated = new TreeMap<String, String>();
        result.sources().forEach((className, source) -> generated.put(className + ".java.txt", source));
        generated.put(result.className() + ".dmn.txt", result.dmn());

        var directory = GOLDEN.resolve(name);
        if (Boolean.getBoolean("golden.update")) {
            write(directory, generated);
        }
        Assertions.assertThat(read(directory)).as("files in %s", directory).isEqualTo(generated);
    }

    private static void write(Path directory, Map<String, String> files) throws IOException {
        Files.createDirectories(directory);
        try (var old = Files.list(directory)) {
            for (var file : old.toList()) {
                Files.delete(file);
            }
        }
        for (var file : files.entrySet()) {
            Files.writeString(directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> read(Path directory) throws IOException {
        var files = new TreeMap<String, String>();
        try (var stream = Files.list(directory)) {
            for (var file : stream.toList()) {
                files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static Path table(String name) {
        try {
            return Path.of(GeneratedSourcesGoldenTest.class.getResource("/tables/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
