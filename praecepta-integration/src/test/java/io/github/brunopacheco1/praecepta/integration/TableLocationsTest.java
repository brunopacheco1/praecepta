/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableLocationsTest {

    private static final ClassLoader LOADER = TableLocationsTest.class.getClassLoader();

    @TempDir
    Path directory;

    @Test
    void patterns_split_into_a_root_and_a_glob() {
        var location = TableLocations.Location.parse("classpath*:/rules/**/*.dmn");
        Assertions.assertThat(location.classpath()).isTrue();
        Assertions.assertThat(location.root()).isEqualTo("rules");
        Assertions.assertThat(location.pattern()).isEqualTo("rules/**/*.dmn");
        Assertions.assertThat(location.isSingleFile()).isFalse();

        Assertions.assertThat(TableLocations.Location.parse("classpath:rules/a?/x*.dmn").root()).isEqualTo("rules");
        Assertions.assertThat(TableLocations.Location.parse("rules/discount.dmn").isSingleFile()).isTrue();
        Assertions.assertThat(TableLocations.Location.parse("rules/discount.dmn").classpath()).isTrue();
        var file = TableLocations.Location.parse("file:/etc/acme/rules/*.dmn");
        Assertions.assertThat(file.classpath()).isFalse();
        Assertions.assertThat(file.root()).isEqualTo("/etc/acme/rules");
    }

    @Test
    void globs_match_within_segments_and_across_them() {
        var deep = TableLocations.Location.parse("rules/**/*.dmn");
        Assertions.assertThat(deep.matches("rules/a.dmn")).isTrue();
        Assertions.assertThat(deep.matches("rules/x/y/a.dmn")).isTrue();
        Assertions.assertThat(deep.matches("rules/a.dmn.bak")).isFalse();
        Assertions.assertThat(deep.matches("other/a.dmn")).isFalse();

        var flat = TableLocations.Location.parse("rules/*.dmn");
        Assertions.assertThat(flat.matches("rules/a.dmn")).isTrue();
        Assertions.assertThat(flat.matches("rules/x/a.dmn")).isFalse();
        Assertions.assertThat(TableLocations.Location.parse("rules/a?.dmn").matches("rules/ab.dmn")).isTrue();
        Assertions.assertThat(TableLocations.Location.parse("rules/a?.dmn").matches("rules/a.dmn")).isFalse();
        // regex characters in names are literal
        Assertions.assertThat(TableLocations.Location.parse("rules/a+b.dmn").matches("rules/aab.dmn")).isFalse();
        Assertions.assertThat(TableLocations.Location.parse("rules/a+b.dmn").matches("rules/a+b.dmn")).isTrue();
    }

    @Test
    void malformed_patterns_are_rejected() {
        Assertions.assertThatThrownBy(() -> TableLocations.Location.parse("http://example.com/rules/*.dmn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected classpath:, classpath*: or file:");
        Assertions.assertThatThrownBy(() -> TableLocations.Location.parse("classpath:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no files");
        // every framework would list the class path's roots differently, and none can inside jars
        for (var rootless : List.of("classpath*:**/*.dmn", "classpath:*.dmn", "rules*/a.dmn")) {
            Assertions.assertThatThrownBy(() -> TableLocations.Location.parse(rootless))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Location '%s': a class path pattern must start with a directory without wildcards, "
                            + "such as classpath*:rules/**/*.dmn", rootless);
        }
        Assertions.assertThat(TableLocations.Location.parse("classpath:top.dmn").isSingleFile()).isTrue();
        Assertions.assertThat(TableLocations.Location.parse("file:*.dmn").root()).isEmpty();
        Assertions.assertThat(TableLocations.split(" classpath:a/*.dmn, ,file:/b/*.dmn ")).containsExactly("classpath:a/*.dmn", "file:/b/*.dmn");
        Assertions.assertThat(TableLocations.split(null)).isEmpty();
    }

    @Test
    void finds_class_path_resources_in_directories() {
        Assertions.assertThat(names(TableLocations.find("classpath:rules/**/*.dmn", LOADER)))
                .containsExactly("rules/discount.dmn", "rules/shipping/shipping.dmn");
        Assertions.assertThat(names(TableLocations.find("classpath*:rules/*.dmn", LOADER))).containsExactly("rules/discount.dmn");
        Assertions.assertThat(names(TableLocations.find("rules/shipping/shipping.dmn", LOADER))).containsExactly("rules/shipping/shipping.dmn");
        Assertions.assertThat(TableLocations.find("classpath:nowhere/**/*.dmn", LOADER)).isEmpty();
    }

    @Test
    void finds_class_path_resources_in_jars() throws IOException {
        var jar = directory.resolve("tables with space.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : List.of("rules/", "rules/in jar.dmn", "rules/deeper/other.dmn", "rules/readme.md", "elsewhere/x.dmn")) {
                out.putNextEntry(new JarEntry(entry));
                if (!entry.endsWith("/")) {
                    out.write("<definitions/>".getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var found = TableLocations.find("classpath:rules/**/*.dmn", loader);
            Assertions.assertThat(found).extracting(TableSource::location)
                    .allSatisfy(location -> Assertions.assertThat(location).startsWith("jar:file:"));
            Assertions.assertThat(names(found)).containsExactly("rules/deeper/other.dmn", "rules/in%20jar.dmn");
            try (InputStream content = found.getLast().opener().open()) {
                Assertions.assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("<definitions/>");
            }
        }
    }

    @Test
    void finds_files_by_a_file_pattern() throws IOException {
        Files.createDirectories(directory.resolve("rules/nested"));
        Files.writeString(directory.resolve("rules/a.dmn"), "a");
        Files.writeString(directory.resolve("rules/nested/b.dmn"), "b");
        Files.writeString(directory.resolve("rules/c.xml"), "c");
        var root = directory.toString().replace('\\', '/');

        Assertions.assertThat(TableLocations.find("file:" + root + "/rules/**/*.dmn", LOADER)).extracting(TableSource::location)
                .containsExactly(directory.resolve("rules/a.dmn").toString(), directory.resolve("rules/nested/b.dmn").toString());
        Assertions.assertThat(TableLocations.find("file:" + root + "/rules/a.dmn", LOADER)).hasSize(1);
        Assertions.assertThat(TableLocations.find("file:" + root + "/missing/*.dmn", LOADER)).isEmpty();
        // several patterns: each file once
        Assertions.assertThat(TableLocations.find(List.of("file:" + root + "/rules/*.dmn", "file:" + root + "/rules/**/*.dmn"), LOADER))
                .hasSize(2);
    }

    /** The resource names at the end of the sources' locations, from their {@code rules/} directory on. */
    private static List<String> names(List<TableSource> sources) {
        return sources.stream().map(TableSource::location).map(location -> location.substring(location.lastIndexOf("rules/"))).toList();
    }
}
