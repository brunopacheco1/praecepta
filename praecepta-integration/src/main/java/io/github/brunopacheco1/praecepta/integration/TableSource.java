/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A DMN file to load: where it is, for messages and reports, and how to read it. A framework turns what its
 * own resource lookup finds into these (a Spring {@code Resource}, a Quarkus build item), or lets
 * {@link TableLocations} find them on the class path or the file system.
 *
 * @param location where the file is, as users would recognise it (a URL, a path); shown in reports and
 *                 error messages
 * @param opener   opens the file; called once per load
 */
public record TableSource(String location, Opener opener) {

    /** Opens a table's content. */
    @FunctionalInterface
    public interface Opener {

        /** A new stream over the DMN document; the caller closes it. */
        InputStream open() throws IOException;
    }

    public TableSource {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(opener, "opener");
    }

    /** A file on the file system, located by its path. */
    public static TableSource of(Path file) {
        return new TableSource(file.toString(), () -> Files.newInputStream(file));
    }

    /** A resource at a URL (a class path resource, say), located by the URL. */
    public static TableSource of(URL url) {
        return new TableSource(url.toString(), url::openStream);
    }

    /** A DMN document held in memory, such as one a build step recorded for the runtime. */
    public static TableSource of(String location, byte[] dmn) {
        var copy = dmn.clone();
        return new TableSource(location, () -> new ByteArrayInputStream(copy));
    }
}
