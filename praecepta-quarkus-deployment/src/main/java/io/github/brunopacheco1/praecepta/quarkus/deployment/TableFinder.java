/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.integration.TableLocations.Location;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaTablesBuildItem.Table;
import io.quarkus.paths.PathTree;
import io.quarkus.paths.PathVisit;

/**
 * Finds the DMN files a location pattern names, reading them while Quarkus builds the application: class path
 * patterns in the application's own resources and in every runtime dependency (the class path the
 * application will have, which the build's own class loader is not), file patterns on the file system.
 */
final class TableFinder {

    private final List<PathTree> classPath;

    /** @param classPath the application's class path roots: its own resources first, then its dependencies */
    TableFinder(List<PathTree> classPath) {
        this.classPath = List.copyOf(classPath);
    }

    /**
     * The tables one pattern finds, each read once, ordered by location.
     *
     * @throws IllegalArgumentException if the pattern is malformed
     * @throws UncheckedIOException     if a file or directory cannot be read
     */
    List<Table> find(Location location) {
        var found = new LinkedHashMap<String, Table>();
        if (location.classpath()) {
            for (var tree : classPath) {
                if (location.root().isEmpty()) {
                    tree.walk(visit -> add(location, visit, found));
                } else {
                    tree.walkIfContains(location.root(), visit -> add(location, visit, found));
                }
            }
        } else {
            for (var source : TableLocations.find("file:" + location.pattern(), TableFinder.class.getClassLoader())) {
                try (var stream = source.opener().open()) {
                    found.putIfAbsent(source.location(), new Table(source.location(), stream.readAllBytes()));
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + source.location() + ": " + e.getMessage(), e);
                }
            }
        }
        var tables = new ArrayList<>(found.values());
        tables.sort((a, b) -> a.location().compareTo(b.location()));
        return tables;
    }

    private static void add(Location location, PathVisit visit, LinkedHashMap<String, Table> found) {
        if (!location.matches(visit.getResourceName()) || !Files.isRegularFile(visit.getPath())) {
            return;
        }
        var url = visit.getUrl().toString();
        if (found.containsKey(url)) {
            return;
        }
        try {
            found.put(url, new Table(url, Files.readAllBytes(visit.getPath())));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + url + ": " + e.getMessage(), e);
        }
    }
}
