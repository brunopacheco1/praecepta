/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.List;
import java.util.Map;

/** Verifies that two engines return the same rule ids for every input of a workload. */
public final class CrossCheck {

    private CrossCheck() {
    }

    public static void assertAgreement(Table table, List<Map<String, Object>> workload, Contender actual, Contender expected) {
        for (var input : workload) {
            var a = normalise(table, actual.evaluate(input));
            var e = normalise(table, expected.evaluate(input));
            if (!a.equals(e)) {
                throw new IllegalStateException("%s disagrees with %s on table %s for input %s: %s vs %s"
                        .formatted(actual.getClass().getSimpleName(), expected.getClass().getSimpleName(),
                                table.name(), input, a, e));
            }
        }
    }

    /** Checks {@code actual} against Praecepta built straight from the table's rules. */
    public static void assertAgreement(Table table, List<Map<String, Object>> workload, Contender actual) {
        assertAgreement(table, workload, actual, Contender.reference(table));
    }

    /**
     * DMN leaves the order of COLLECT results unspecified, so they are compared as sorted lists. Under
     * PRIORITY, DMN only defines the winner's priority: when several matches share the highest one,
     * engines may return different rules, so the priorities are compared.
     */
    private static List<String> normalise(Table table, List<String> ids) {
        return switch (table.hitPolicy()) {
            case COLLECT -> ids.stream().sorted().toList();
            case PRIORITY -> ids.stream().map(table::priorityOf).toList();
            default -> ids;
        };
    }
}
