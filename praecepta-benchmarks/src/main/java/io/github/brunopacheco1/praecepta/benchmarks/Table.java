package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;

/**
 * A decision table shared by every engine under test. Praecepta consumes {@link #rules()} directly;
 * the DMN engines consume the same rules rendered by {@link DmnWriter}, so all engines evaluate
 * identical logic.
 *
 * @param name      identifier used as JMH parameter value
 * @param inputs    input column names, in table order
 * @param outputs   output column names; {@link #ID_OUTPUT} identifies the matched rule
 * @param rules     rules in table order (DMN rule order)
 * @param hitPolicy hit policy applied by every engine
 */
public record Table(String name, List<String> inputs, List<String> outputs, List<Praeceptum> rules, HitPolicy hitPolicy) {

    public static final String ID_OUTPUT = "ruleId";

    private static final String REAL_TABLE = "tables/decision_table.xlsx";
    private static final String SYNTHETIC_PREFIX = "synthetic-";

    /**
     * @param name {@code real} for the bundled 119-rule table, or {@code synthetic-<rules>} for a
     *             generated table with the given number of rules.
     */
    public static Table named(String name) {
        if (name.equals("real")) {
            return real();
        }
        if (name.startsWith(SYNTHETIC_PREFIX)) {
            return synthetic(Integer.parseInt(name.substring(SYNTHETIC_PREFIX.length())), 10, 8, 0.4, 42);
        }
        throw new IllegalArgumentException("Unknown table: " + name);
    }

    /** The 119-rule, 13-input table previously used by the App Engine benchmarks. FIRST hit policy. */
    public static Table real() {
        var inputs = new ArrayList<String>();
        for (char c = 'A'; c <= 'M'; c++) {
            inputs.add("input" + c);
        }
        var outputs = List.of("outputA", "outputB", ID_OUTPUT);
        var rules = new ExcelLoader(REAL_TABLE, new HashSet<>(inputs), new HashSet<>(outputs)).getPraecepta();
        return new Table("real", List.copyOf(inputs), outputs, rules, HitPolicy.FIRST);
    }

    /**
     * Random table. Later columns are wildcards more often, as in typical hand-written tables.
     * COLLECT hit policy, because random rules shadow each other and the current engine rejects
     * shadowed rules under single-hit policies.
     */
    public static Table synthetic(int ruleCount, int columns, int domain, double wildcardRatio, long seed) {
        var random = new Random(seed);
        var inputs = new ArrayList<String>();
        for (int c = 0; c < columns; c++) {
            inputs.add("c" + c);
        }
        var rules = new ArrayList<Praeceptum>();
        for (int r = 1; r <= ruleCount; r++) {
            var values = new HashMap<String, String>();
            for (int c = 0; c < columns; c++) {
                double pWildcard = wildcardRatio * (0.5 + c / (double) columns);
                values.put(inputs.get(c), random.nextDouble() < pWildcard ? null : "v" + random.nextInt(domain));
            }
            rules.add(new Praeceptum(r, new PraeceptaInput(values), new PraeceptaOutput(Map.of(ID_OUTPUT, String.valueOf(r)))));
        }
        return new Table(SYNTHETIC_PREFIX + ruleCount, List.copyOf(inputs), List.of(ID_OUTPUT), List.copyOf(rules), HitPolicy.COLLECT);
    }
}
