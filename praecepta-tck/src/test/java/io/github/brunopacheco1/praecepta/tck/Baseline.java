/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The committed baseline (baseline.txt): the key of every result node that passed when it was last regenerated, one
 * per line, sorted. A node it lists that no longer passes is a regression, which fails the build; regenerating it with
 * {@code -Dtck.update=true} records what passes now, and its diff shows what changed.
 */
final class Baseline {

    /** The module's directory: Maven's {@code basedir}, or {@code praecepta-tck} from the repository's root, as an IDE runs it. */
    static final Path MODULE = module();

    static final Path FILE = MODULE.resolve("baseline.txt");

    private static final String HEADER = """
            # SPDX-FileCopyrightText: Bruno Pacheco
            #
            # SPDX-License-Identifier: Apache-2.0
            #
            # The DMN TCK result nodes that passed when this file was last regenerated, one per line: the test case
            # directory, the test file, the test case id and the result node's name. TckTest fails the build when one
            # of them no longer passes. Regenerate it, with RESULTS.md, only to record a deliberate change:
            #
            #     mvn -pl praecepta-tck -am test -Dtck.update=true
            """;

    private Baseline() {
    }

    private static Path module() {
        var basedir = System.getProperty("basedir");
        if (basedir != null) {
            return Path.of(basedir);
        }
        return Path.of(Files.isDirectory(Path.of("praecepta-tck")) ? "praecepta-tck" : "");
    }

    /**
     * The keys the baseline lists.
     *
     * @throws IllegalStateException if there is no baseline, or it lists no node, unless {@code update} is set: a
     *                               missing baseline would let every regression pass
     */
    static SortedSet<String> read(Path file, boolean update) {
        if (!Files.exists(file)) {
            if (update) {
                return new TreeSet<>();
            }
            throw new IllegalStateException(file.toAbsolutePath() + " does not exist; without it no regression is caught. "
                    + "Run the TCK from praecepta-tck, or regenerate it with -Dtck.update=true");
        }
        var keys = new TreeSet<String>();
        try (var lines = Files.lines(file)) {
            lines.filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(keys::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (keys.isEmpty() && !update) {
            throw new IllegalStateException(file.toAbsolutePath() + " lists no result node; regenerate it with -Dtck.update=true");
        }
        return keys;
    }

    /** The baseline's text for these passing keys: the header, then the keys sorted, each line ending in {@code \n}. */
    static String render(Collection<String> passing) {
        var text = new StringBuilder(HEADER);
        new TreeSet<>(passing).forEach(key -> text.append(key).append('\n'));
        return text.toString();
    }

    /** The keys of the baseline that are not among the passing ones, sorted. */
    static List<String> regressions(Set<String> baseline, Collection<String> passing) {
        var lost = new TreeSet<>(baseline);
        lost.removeAll(Set.copyOf(passing));
        return List.copyOf(lost);
    }
}
