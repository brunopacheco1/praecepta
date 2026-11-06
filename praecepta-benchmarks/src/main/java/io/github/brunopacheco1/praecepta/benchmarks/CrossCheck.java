package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/** Verifies that two engines return the same rule ids for every input of a workload. */
public final class CrossCheck {

    private CrossCheck() {
    }

    public static void assertAgreement(Table table, List<Map<String, String>> workload, Contender actual, Contender expected) {
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

    /** DMN leaves the order of COLLECT results unspecified, so compare them as sorted lists. */
    private static List<String> normalise(Table table, List<String> ids) {
        return table.hitPolicy() == HitPolicy.COLLECT ? ids.stream().sorted().toList() : ids;
    }
}
