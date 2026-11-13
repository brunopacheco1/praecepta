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
import java.util.TreeSet;

/**
 * The committed baseline (baseline.txt): the key of every result node that passed when it was last regenerated, one
 * per line, sorted. A node it lists that no longer passes is a regression, which fails the build; regenerating it
 * with {@code -Dtck.update=true} records what passes now, and its diff shows what changed.
 */
final class Baseline {

    static final Path FILE = Path.of("baseline.txt");

    private static final String HEADER = """
            # SPDX-FileCopyrightText: Bruno Pacheco
            #
            # SPDX-License-Identifier: Apache-2.0
            #
            # The DMN TCK result nodes that passed when this file was last regenerated, one per line: the test case
            # directory, the test file, the test case id and the result node's name. TckTest fails the build when one of
            # them no longer passes. Regenerate it, with RESULTS.md, only to record a deliberate change:
            #
            #     mvn -pl praecepta-tck -am test -Dtck.update=true
            """;

    private Baseline() {
    }

    /** The keys the baseline lists; empty if there is no baseline. */
    static Set<String> read(Path file) {
        if (!Files.exists(file)) {
            return Set.of();
        }
        try (var lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank() && !line.startsWith("#")).collect(TreeSet::new, Set::add, Set::addAll);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
