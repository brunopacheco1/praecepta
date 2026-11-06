package io.github.brunopacheco1.praecepta.benchmarks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
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
 * @param types     type of each input column, in the same order
 * @param outputs   output column names; {@link #ID_OUTPUT} identifies the matched rule
 * @param rules     rules in table order (DMN rule order); cells are Praecepta / FEEL unary tests
 * @param hitPolicy hit policy applied by every engine
 */
public record Table(String name, List<String> inputs, List<ColumnType> types, List<String> outputs,
                    List<Praeceptum> rules, HitPolicy hitPolicy) {

    public static final String ID_OUTPUT = "ruleId";

    /** First day of the date domain used by mixed tables. */
    static final LocalDate DAY0 = LocalDate.of(2025, 1, 1);

    private static final String REAL_TABLE = "tables/decision_table.xlsx";
    private static final String SYNTHETIC_PREFIX = "synthetic-";
    private static final String MIXED_PREFIX = "mixed-";

    /**
     * @param name {@code real} for the bundled 119-rule table, {@code synthetic-<rules>} for a
     *             generated string table, or {@code mixed-<rules>} for a generated table mixing
     *             string, number and date columns.
     */
    public static Table named(String name) {
        if (name.equals("real")) {
            return real();
        }
        if (name.startsWith(SYNTHETIC_PREFIX)) {
            return synthetic(Integer.parseInt(name.substring(SYNTHETIC_PREFIX.length())), 10, 8, 0.4, 42);
        }
        if (name.startsWith(MIXED_PREFIX)) {
            return mixed(Integer.parseInt(name.substring(MIXED_PREFIX.length())), 0.4, 42);
        }
        throw new IllegalArgumentException("Unknown table: " + name);
    }

    /** True when every column is a string column. */
    public boolean stringsOnly() {
        return types.stream().allMatch(type -> type == ColumnType.STRING);
    }

    /** The 119-rule, 13-input table previously used by the App Engine benchmarks. FIRST hit policy. */
    public static Table real() {
        var inputs = new ArrayList<String>();
        for (char c = 'A'; c <= 'M'; c++) {
            inputs.add("input" + c);
        }
        var outputs = List.of("outputA", "outputB", ID_OUTPUT);
        var rules = new ExcelLoader(REAL_TABLE, new HashSet<>(inputs), new HashSet<>(outputs)).getPraecepta();
        return new Table("real", List.copyOf(inputs), strings(inputs.size()), outputs, rules, HitPolicy.FIRST);
    }

    /**
     * Random string table. Later columns are wildcards more often, as in typical hand-written tables.
     * COLLECT hit policy (all matches), so every overlapping rule contributes to the result.
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
            rules.add(rule(r, values));
        }
        return new Table(SYNTHETIC_PREFIX + ruleCount, List.copyOf(inputs), strings(columns), List.of(ID_OUTPUT),
                List.copyOf(rules), HitPolicy.COLLECT);
    }

    /**
     * Random table of nine columns: string ({@code s*}), number ({@code n*}, values 0..100) and date
     * ({@code d*}, days of 2025), interleaved. Cells use every FEEL form Praecepta supports: literal
     * lists, {@code not(...)}, comparisons and intervals with open and closed ends. Wildcards grow
     * more frequent to the right, as in {@link #synthetic}. COLLECT hit policy.
     */
    public static Table mixed(int ruleCount, double wildcardRatio, long seed) {
        var random = new Random(seed);
        var inputs = new ArrayList<String>();
        var types = new ArrayList<ColumnType>();
        for (int i = 0; i < 3; i++) {
            inputs.addAll(List.of("s" + i, "n" + i, "d" + i));
            types.addAll(List.of(ColumnType.STRING, ColumnType.NUMBER, ColumnType.DATE));
        }
        int columns = inputs.size();
        var rules = new ArrayList<Praeceptum>();
        for (int r = 1; r <= ruleCount; r++) {
            var values = new HashMap<String, String>();
            for (int c = 0; c < columns; c++) {
                double pWildcard = wildcardRatio * (0.5 + c / (double) columns);
                values.put(inputs.get(c), random.nextDouble() < pWildcard ? "-" : cell(types.get(c), random));
            }
            rules.add(rule(r, values));
        }
        return new Table(MIXED_PREFIX + ruleCount, List.copyOf(inputs), List.copyOf(types), List.of(ID_OUTPUT),
                List.copyOf(rules), HitPolicy.COLLECT);
    }

    private static String cell(ColumnType type, Random random) {
        int pick = random.nextInt(10);
        return switch (type) {
            case STRING -> pick < 6 ? str(random)
                    : pick < 8 ? str(random) + ", " + str(random)
                    : "not(" + str(random) + ")";
            case NUMBER -> {
                int a = random.nextInt(90);
                int b = a + 2 + random.nextInt(100 - a - 1);
                yield switch (pick) {
                    case 0, 1 -> "< " + a;
                    case 2, 3 -> ">= " + a;
                    case 4, 5 -> "[" + a + ".." + b + ")";
                    case 6, 7 -> "(" + a + ".." + b + "]";
                    default -> a + ", " + b;
                };
            }
            case DATE -> {
                int a = random.nextInt(330);
                int b = a + 2 + random.nextInt(364 - a - 1);
                yield switch (pick) {
                    case 0, 1, 2 -> "< " + date(a);
                    case 3, 4, 5 -> ">= " + date(a);
                    default -> "[" + date(a) + ".." + date(b) + "]";
                };
            }
        };
    }

    private static String str(Random random) {
        return "\"v" + random.nextInt(8) + "\"";
    }

    private static String date(int day) {
        return "date(\"" + DAY0.plusDays(day) + "\")";
    }

    private static Praeceptum rule(int id, Map<String, String> cells) {
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of(ID_OUTPUT, String.valueOf(id))));
    }

    private static List<ColumnType> strings(int columns) {
        return Collections.nCopies(columns, ColumnType.STRING);
    }
}
