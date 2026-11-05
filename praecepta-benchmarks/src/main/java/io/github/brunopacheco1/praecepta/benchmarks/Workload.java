package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Deterministic request mix: each input starts from a random rule, takes one of its values for
 * every concrete cell, and a random known value (or null) for every wildcard cell. Every input
 * therefore matches at least one rule, so no engine pays for a no-match path.
 */
public final class Workload {

    private Workload() {
    }

    public static List<Map<String, String>> generate(Table table, int size, long seed) {
        var domains = new ArrayList<List<String>>();
        for (var column : table.inputs()) {
            var values = new TreeSet<String>();
            for (var rule : table.rules()) {
                var cell = rule.input().values().get(column);
                if (cell != null && !cell.isBlank()) {
                    Collections.addAll(values, cell.split(","));
                }
            }
            domains.add(List.copyOf(values));
        }

        var random = new Random(seed);
        var inputs = new ArrayList<Map<String, String>>(size);
        for (int i = 0; i < size; i++) {
            var rule = table.rules().get(random.nextInt(table.rules().size()));
            var input = new HashMap<String, String>();
            for (int c = 0; c < table.inputs().size(); c++) {
                var column = table.inputs().get(c);
                var cell = rule.input().values().get(column);
                String value;
                if (cell == null || cell.isBlank()) {
                    var domain = domains.get(c);
                    value = domain.isEmpty() || random.nextInt(3) == 0 ? null : domain.get(random.nextInt(domain.size()));
                } else {
                    var options = cell.split(",");
                    value = options[random.nextInt(options.length)];
                }
                // fresh instances, as if deserialised from a request
                input.put(column, value == null ? null : new String(value));
            }
            inputs.add(input);
        }
        return List.copyOf(inputs);
    }
}
