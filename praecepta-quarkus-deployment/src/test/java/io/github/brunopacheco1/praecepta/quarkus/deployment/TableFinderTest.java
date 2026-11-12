/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.integration.TableLocations.Location;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaTablesBuildItem.Table;
import io.quarkus.paths.PathTree;

/** How the build step finds DMN files on the application's class path (directories and jars) and the file system. */
class TableFinderTest {

    @TempDir
    Path temp;

    @Test
    void class_path_patterns_search_directories_and_jars() throws IOException {
        var classes = temp.resolve("classes");
        write(classes.resolve("rules/discount.dmn"), "discount");
        write(classes.resolve("rules/nested/fees.dmn"), "fees");
        write(classes.resolve("rules/notes.txt"), "not a table");
        write(classes.resolve("other/elsewhere.dmn"), "elsewhere");
        var jar = jar(temp.resolve("library.jar"), "rules/shipping.dmn", "shipping", "META-INF/rules/hidden.dmn", "hidden");
        var finder = new TableFinder(List.of(PathTree.ofDirectoryOrArchive(classes), PathTree.ofDirectoryOrArchive(jar)));

        var found = finder.find(Location.parse("classpath*:rules/**/*.dmn"));

        Assertions.assertThat(found).extracting(Table::location).hasSize(3)
                .anySatisfy(location -> Assertions.assertThat(location).startsWith("file:").endsWith("/classes/rules/discount.dmn"))
                .anySatisfy(location -> Assertions.assertThat(location).endsWith("/classes/rules/nested/fees.dmn"))
                .anySatisfy(location -> Assertions.assertThat(location).startsWith("jar:file:").endsWith("library.jar!/rules/shipping.dmn"));
        Assertions.assertThat(found).extracting(table -> new String(table.dmn(), StandardCharsets.UTF_8))
                .containsExactlyInAnyOrder("discount", "fees", "shipping");
        // ordered by location
        Assertions.assertThat(found).extracting(Table::location).isSorted();
    }

    @Test
    void names_without_wildcards_name_one_file_even_at_a_roots_top() throws IOException {
        var classes = temp.resolve("classes");
        write(classes.resolve("top.dmn"), "top");
        write(classes.resolve("rules/discount.dmn"), "discount");
        var finder = new TableFinder(List.of(PathTree.ofDirectoryOrArchive(classes)));

        Assertions.assertThat(finder.find(Location.parse("classpath:top.dmn")))
                .extracting(Table::location).singleElement().asString().endsWith("/classes/top.dmn");
        // as in every integration, a pattern with wildcards must start with a directory
        Assertions.assertThatThrownBy(() -> Location.parse("classpath:*.dmn")).isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThat(finder.find(Location.parse("classpath:rules/discount.dmn")))
                .extracting(Table::location).singleElement().asString().endsWith("/classes/rules/discount.dmn");
        Assertions.assertThat(finder.find(Location.parse("classpath:rules/missing.dmn"))).isEmpty();
    }

    @Test
    void a_resource_on_two_roots_is_found_on_both_but_the_same_root_twice_counts_once() throws IOException {
        var first = temp.resolve("first");
        var second = temp.resolve("second");
        write(first.resolve("rules/discount.dmn"), "one");
        write(second.resolve("rules/discount.dmn"), "two");
        var finder = new TableFinder(List.of(PathTree.ofDirectoryOrArchive(first), PathTree.ofDirectoryOrArchive(second),
                PathTree.ofDirectoryOrArchive(first)));

        Assertions.assertThat(finder.find(Location.parse("classpath:rules/*.dmn"))).hasSize(2);
    }

    @Test
    void file_patterns_search_the_file_system() throws IOException {
        write(temp.resolve("tables/discount.dmn"), "discount");
        write(temp.resolve("tables/readme.md"), "not a table");
        var pattern = "file:" + temp.toString().replace('\\', '/') + "/tables/*.dmn";
        var finder = new TableFinder(List.of());

        var found = finder.find(Location.parse(pattern));

        Assertions.assertThat(found).singleElement().satisfies(table -> {
            Assertions.assertThat(Path.of(table.location())).isEqualTo(temp.resolve("tables/discount.dmn"));
            Assertions.assertThat(table.dmn()).asString(StandardCharsets.UTF_8).isEqualTo("discount");
        });
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path jar(Path file, String... namesAndContents) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                jar.putNextEntry(new JarEntry(namesAndContents[i]));
                jar.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }
}
