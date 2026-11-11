/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds DMN files by location patterns, the way the framework integrations configure them
 * ({@code praecepta.locations}):
 *
 * <ul>
 * <li>{@code classpath:rules/**}{@code /*.dmn} or {@code classpath*:rules/**}{@code /*.dmn}: resources under
 * {@code rules/} in every class path root (directories and jars), both prefixes meaning the same;</li>
 * <li>{@code file:/etc/acme/rules/*.dmn}: files on the file system;</li>
 * <li>a pattern without a prefix is a class path pattern.</li>
 * </ul>
 *
 * In a pattern, {@code *} matches any characters within a path segment, {@code ?} one character, and
 * {@code **} any number of whole segments, as in Ant and Spring patterns. A class path pattern should start
 * with a directory ({@code rules/...}): the class path's roots themselves cannot be listed inside jars. A
 * location without wildcards names one file. Frameworks with their own resource lookup (Spring's
 * {@code ResourcePatternResolver}, Quarkus's application archives) use that instead, and may still use
 * {@link Location#matches(String)} to filter paths.
 */
public final class TableLocations {

    /** The default of {@code praecepta.locations} in every framework integration. */
    public static final String DEFAULT = "classpath*:rules/**/*.dmn";

    private TableLocations() {
    }

    /**
     * A parsed location pattern.
     *
     * @param classpath whether it names class path resources (else files)
     * @param root      the directory to search: the pattern's leading segments without wildcards, without a
     *                  leading slash for the class path (empty for the class path's roots)
     * @param pattern   the whole pattern, after the prefix
     */
    public record Location(boolean classpath, String root, String pattern) {

        /**
         * Parses {@code classpath:...}, {@code classpath*:...}, {@code file:...} or a class path pattern
         * without a prefix.
         *
         * @throws IllegalArgumentException if the pattern is blank or has another prefix (such as
         *                                  {@code http:})
         */
        public static Location parse(String text) {
            var trimmed = text == null ? "" : text.strip();
            boolean classpath = true;
            String path;
            var lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("classpath*:")) {
                path = trimmed.substring("classpath*:".length());
            } else if (lower.startsWith("classpath:")) {
                path = trimmed.substring("classpath:".length());
            } else if (lower.startsWith("file:")) {
                classpath = false;
                path = trimmed.substring("file:".length());
            } else if (trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]+:.*") && !trimmed.matches("^[a-zA-Z]:[/\\\\].*")) {
                throw new IllegalArgumentException("Location '%s': expected classpath:, classpath*: or file:".formatted(text));
            } else {
                path = trimmed;
            }
            path = path.replace('\\', '/');
            if (classpath) {
                while (path.startsWith("/")) {
                    path = path.substring(1);
                }
            }
            if (path.isBlank()) {
                throw new IllegalArgumentException("Location '%s' names no files".formatted(text));
            }
            return new Location(classpath, root(path), path);
        }

        /** Whether the pattern has no wildcards, and so names a single file. */
        public boolean isSingleFile() {
            return !hasWildcard(pattern);
        }

        /**
         * Whether a path matches the pattern: a class path resource's name ({@code rules/discount.dmn}), or a
         * file's path with {@code /} separators.
         */
        public boolean matches(String path) {
            return regex(pattern).matcher(path.replace('\\', '/')).matches();
        }

        private static String root(String path) {
            var segments = path.split("/", -1);
            var fixed = new ArrayList<String>();
            for (int i = 0; i < segments.length - 1 && !hasWildcard(segments[i]); i++) {
                fixed.add(segments[i]);
            }
            return String.join("/", fixed);
        }

        private static boolean hasWildcard(String text) {
            return text.indexOf('*') >= 0 || text.indexOf('?') >= 0;
        }

        private static Pattern regex(String glob) {
            var segments = glob.split("/", -1);
            var regex = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                boolean last = i == segments.length - 1;
                var segment = segments[i];
                if (segment.equals("**")) {
                    regex.append(last ? ".*" : "(?:[^/]*/)*");
                    continue;
                }
                for (var c : segment.toCharArray()) {
                    switch (c) {
                        case '*' -> regex.append("[^/]*");
                        case '?' -> regex.append("[^/]");
                        default -> regex.append(Pattern.quote(String.valueOf(c)));
                    }
                }
                if (!last) {
                    regex.append('/');
                }
            }
            return Pattern.compile(regex.toString());
        }
    }

    /** The patterns of a comma-separated list, as a property holds them; blank entries are dropped. */
    public static List<String> split(String locations) {
        if (locations == null) {
            return List.of();
        }
        return Arrays.stream(locations.split(",")).map(String::strip).filter(location -> !location.isEmpty()).toList();
    }

    /**
     * The DMN files the patterns find, each once, ordered by location.
     *
     * @throws IllegalArgumentException if a pattern is malformed
     * @throws UncheckedIOException     if a directory or jar cannot be listed
     */
    public static List<TableSource> find(Collection<String> locations, ClassLoader loader) {
        var found = new LinkedHashMap<String, TableSource>();
        for (var location : locations) {
            for (var source : find(location, loader)) {
                found.putIfAbsent(source.location(), source);
            }
        }
        var sources = new ArrayList<>(found.values());
        sources.sort((a, b) -> a.location().compareTo(b.location()));
        return Collections.unmodifiableList(sources);
    }

    /**
     * The DMN files one pattern finds, ordered by location.
     *
     * @throws IllegalArgumentException if the pattern is malformed
     * @throws UncheckedIOException     if a directory or jar cannot be listed
     */
    public static List<TableSource> find(String location, ClassLoader loader) {
        var parsed = Location.parse(location);
        try {
            var found = parsed.classpath() ? onClasspath(parsed, loader) : onFileSystem(parsed);
            found.sort((a, b) -> a.location().compareTo(b.location()));
            return Collections.unmodifiableList(found);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the tables at " + location + ": " + e.getMessage(), e);
        }
    }

    private static List<TableSource> onFileSystem(Location location) throws IOException {
        var found = new ArrayList<TableSource>();
        if (location.isSingleFile()) {
            var file = Path.of(location.pattern());
            if (Files.isRegularFile(file)) {
                found.add(TableSource.of(file));
            }
            return found;
        }
        var root = Path.of(location.root().isEmpty() ? "." : location.root());
        walk(root, location.root(), location, found);
        return found;
    }

    private static List<TableSource> onClasspath(Location location, ClassLoader loader) throws IOException {
        var found = new ArrayList<TableSource>();
        if (location.isSingleFile()) {
            for (var url : Collections.list(loader.getResources(location.pattern()))) {
                found.add(TableSource.of(url));
            }
            return found;
        }
        for (var url : Collections.list(loader.getResources(location.root()))) {
            switch (url.getProtocol()) {
                case "file" -> walk(path(url), location.root(), location, found);
                case "jar" -> jar(url, location, found);
                default -> walk(pathInFileSystem(url), location.root(), location, found);
            }
        }
        return found;
    }

    /** Adds the regular files under {@code directory} whose name (as {@code root/relative}) matches. */
    private static void walk(Path directory, String root, Location location, List<TableSource> found) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = directory.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                var name = root.isEmpty() ? relative : root + "/" + relative;
                if (location.matches(name)) {
                    found.add(location.classpath() ? TableSource.of(file.toUri().toURL()) : TableSource.of(file));
                }
            }
        }
    }

    /** Adds the entries of the jar a {@code jar:} URL points into that match. */
    private static void jar(URL directory, Location location, List<TableSource> found) throws IOException {
        var connection = (JarURLConnection) directory.openConnection();
        connection.setUseCaches(false);
        var jarUrl = connection.getJarFileURL();
        try (var jar = connection.getJarFile()) {
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.isDirectory() && location.matches(entry.getName())) {
                    found.add(TableSource.of(url("jar:" + jarUrl + "!/" + encode(entry.getName()))));
                }
            }
        }
    }

    private static Path path(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot read " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * A URL of another scheme as a path, through the file system its scheme installs (such as a native
     * image's {@code resource:} file system), opening that file system if needed.
     */
    private static Path pathInFileSystem(URL url) throws IOException {
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Cannot read " + url + ": " + e.getMessage(), e);
        }
        try {
            return Path.of(uri);
        } catch (FileSystemNotFoundException e) {
            // stays open: the sources found read through it later, and the class path does not change
            @SuppressWarnings("resource")
            var fileSystem = FileSystems.newFileSystem(uri, Map.of());
            return fileSystem.provider().getPath(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot list class path resources at " + url + ": " + e.getMessage(), e);
        }
    }

    private static URL url(String text) throws IOException {
        try {
            return URI.create(text).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot read " + text + ": " + e.getMessage(), e);
        }
    }

    /** A jar entry's name as a URL path: characters outside RFC 3986's unreserved set and {@code /} percent-encoded. */
    private static String encode(String name) {
        var encoded = new StringBuilder();
        for (var b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~/".indexOf(c) >= 0)) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return encoded.toString();
    }
}
