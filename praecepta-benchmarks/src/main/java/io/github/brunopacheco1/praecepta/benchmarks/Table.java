/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.excel.ExcelLoader;

/**
 * A decision table shared by every engine under test. Every engine reads the same DMN, rendered from
 * {@link #decisionTable()} by the engine's {@code DmnWriter}, so all of them evaluate identical logic.
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

    /** Output of PRIORITY tables, declared before {@link #ID_OUTPUT} so that it decides the winner. */
    public static final String PRIORITY_OUTPUT = "decision";

    /** The values of {@link #PRIORITY_OUTPUT}, highest priority first. */
    public static final List<String> PRIORITY_VALUES = List.of("decline", "refer", "review", "accept");

    /** Name of the decision in the DMN given to every engine. */
    public static final String DECISION = "decision_table";

    /** First day of the date domain used by mixed tables. */
    static final LocalDate DAY0 = LocalDate.of(2025, 1, 1);

    private static final String REAL_TABLE = "tables/decision_table.xlsx";
    private static final Pattern GENERATED =
            Pattern.compile("(synthetic|mixed|unique)-(\\d+)(?:x(\\d+))?(?:-(collect|first|priority|ruleorder|unique))?");
    private static final double WILDCARDS = 0.4;
    private static final int STRING_DOMAIN = 8;
    private static final long SEED = 42;

    /**
     * Tables by name:
     * <ul>
     *   <li>{@code real}: the bundled 119-rule, 13-column string table (FIRST);</li>
     *   <li>{@code synthetic-<rules>[x<columns>]}: random string columns (default 10), where rules
     *       overlap (COLLECT);</li>
     *   <li>{@code mixed-<rules>[x<columns>]}: random string, number and date columns, interleaved
     *       (default 9, a multiple of 3), using every FEEL cell form Praecepta supports (COLLECT);</li>
     *   <li>{@code unique-<rules>[x<columns>]}: random string columns (default 10) whose rules never
     *       overlap, as in lookup tables (UNIQUE).</li>
     * </ul>
     * A suffix {@code -collect}, {@code -first}, {@code -priority}, {@code -ruleorder} or
     * {@code -unique} changes the hit policy and nothing else: {@code mixed-1000-first} has the same
     * rules as {@code mixed-1000}. PRIORITY tables add a {@link #PRIORITY_OUTPUT} output column.
     */
    public static Table named(String name) {
        if (name.equals("real")) {
            return real();
        }
        var matcher = GENERATED.matcher(name);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unknown table: " + name);
        }
        var kind = matcher.group(1);
        int rules = Integer.parseInt(matcher.group(2));
        var columns = matcher.group(3);
        var hitPolicySuffix = matcher.group(4);
        return switch (kind) {
            case "synthetic" -> synthetic(name, rules, columnsOr(columns, 10), hitPolicyOr(hitPolicySuffix, HitPolicy.COLLECT));
            case "mixed" -> mixed(name, rules, columnsOr(columns, 9), hitPolicyOr(hitPolicySuffix, HitPolicy.COLLECT));
            default -> unique(name, rules, columnsOr(columns, 10), hitPolicyOr(hitPolicySuffix, HitPolicy.UNIQUE));
        };
    }

    /** The table as a decision table, for {@code DmnWriter}; outputs are strings. */
    public DecisionTable decisionTable() {
        var columns = new ArrayList<DecisionTable.InputColumn>();
        for (int c = 0; c < inputs.size(); c++) {
            columns.add(new DecisionTable.InputColumn(inputs.get(c), types.get(c)));
        }
        var results = outputs.stream()
                .map(output -> output.equals(PRIORITY_OUTPUT)
                        ? new DecisionTable.OutputColumn(output, ColumnType.STRING, PRIORITY_VALUES)
                        : new DecisionTable.OutputColumn(output, ColumnType.STRING))
                .toList();
        return new DecisionTable(DECISION, hitPolicy, null, columns, results, rules);
    }

    /** True when every column is a string column. */
    public boolean stringsOnly() {
        return types.stream().allMatch(type -> type == ColumnType.STRING);
    }

    /** The {@link #PRIORITY_OUTPUT} of the rule with this id, for PRIORITY tables. */
    public String priorityOf(String ruleId) {
        return rules.get(Integer.parseInt(ruleId) - 1).output().values().get(PRIORITY_OUTPUT);
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
     * Random string table. Later columns are wildcards more often, as in typical hand-written tables,
     * so rules overlap and an input usually matches several of them.
     */
    static Table synthetic(String name, int ruleCount, int columns, HitPolicy hitPolicy) {
        var random = new Random(SEED);
        var inputs = columnNames("c", columns);
        var cells = new ArrayList<Map<String, String>>();
        for (int r = 1; r <= ruleCount; r++) {
            var values = new HashMap<String, String>();
            for (int c = 0; c < columns; c++) {
                values.put(inputs.get(c), randomStringCell(random, c, columns));
            }
            cells.add(values);
        }
        return build(name, inputs, strings(columns), cells, hitPolicy);
    }

    /**
     * Random table of string ({@code s*}), number ({@code n*}, values 0..100) and date ({@code d*}, days
     * of 2025) columns, interleaved. Cells use every FEEL form Praecepta supports: literal lists,
     * {@code not(...)}, comparisons and intervals with open and closed ends. Wildcards grow more
     * frequent to the right, as in {@link #synthetic}.
     */
    static Table mixed(String name, int ruleCount, int columns, HitPolicy hitPolicy) {
        if (columns % 3 != 0) {
            throw new IllegalArgumentException("Mixed tables have a multiple of 3 columns: " + name);
        }
        var random = new Random(SEED);
        var inputs = new ArrayList<String>();
        var types = new ArrayList<ColumnType>();
        for (int i = 0; i < columns / 3; i++) {
            inputs.addAll(List.of("s" + i, "n" + i, "d" + i));
            types.addAll(List.of(ColumnType.STRING, ColumnType.NUMBER, ColumnType.DATE));
        }
        var cells = new ArrayList<Map<String, String>>();
        for (int r = 1; r <= ruleCount; r++) {
            var values = new HashMap<String, String>();
            for (int c = 0; c < columns; c++) {
                values.put(inputs.get(c), wildcard(random, c, columns) ? "-" : feelCell(types.get(c), random));
            }
            cells.add(values);
        }
        return build(name, inputs, types, cells, hitPolicy);
    }

    /**
     * Random string table whose rules never overlap: the first columns hold a distinct combination of
     * values in every rule (enough columns for four times as many combinations as rules), the others
     * are random with wildcards. An input built from a rule matches that rule only.
     */
    static Table unique(String name, int ruleCount, int columns, HitPolicy hitPolicy) {
        int keys = keyColumns(ruleCount);
        if (keys > columns) {
            throw new IllegalArgumentException("Too few columns for %d distinct rules: %s".formatted(ruleCount, name));
        }
        var random = new Random(SEED);
        var inputs = columnNames("c", columns);
        var combinations = new LinkedHashSet<List<Integer>>();
        while (combinations.size() < ruleCount) {
            var combination = new ArrayList<Integer>(keys);
            for (int k = 0; k < keys; k++) {
                combination.add(random.nextInt(STRING_DOMAIN));
            }
            combinations.add(List.copyOf(combination));
        }
        var cells = new ArrayList<Map<String, String>>();
        for (var combination : combinations) {
            var values = new HashMap<String, String>();
            for (int c = 0; c < columns; c++) {
                var cell = c < keys ? "v" + combination.get(c) : randomStringCell(random, c, columns);
                values.put(inputs.get(c), cell);
            }
            cells.add(values);
        }
        return build(name, inputs, strings(columns), cells, hitPolicy);
    }

    /** The number of key columns of a unique table: enough for four times as many value combinations as rules. */
    private static int keyColumns(int ruleCount) {
        int keys = 1;
        for (long combinations = STRING_DOMAIN; combinations < 4L * ruleCount; combinations *= STRING_DOMAIN) {
            keys++;
        }
        return keys;
    }

    private static Table build(String name, List<String> inputs, List<ColumnType> types, List<Map<String, String>> cells,
                               HitPolicy hitPolicy) {
        boolean priority = hitPolicy == HitPolicy.PRIORITY;
        // a separate generator, so that the rules are the same under every hit policy
        var random = new Random(SEED + 1);
        var rules = new ArrayList<Praeceptum>(cells.size());
        for (int r = 0; r < cells.size(); r++) {
            var outputs = new HashMap<String, String>();
            outputs.put(ID_OUTPUT, String.valueOf(r + 1));
            if (priority) {
                outputs.put(PRIORITY_OUTPUT, PRIORITY_VALUES.get(random.nextInt(PRIORITY_VALUES.size())));
            }
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells.get(r)), new PraeceptaOutput(outputs)));
        }
        var outputs = priority ? List.of(PRIORITY_OUTPUT, ID_OUTPUT) : List.of(ID_OUTPUT);
        return new Table(name, List.copyOf(inputs), List.copyOf(types), outputs, List.copyOf(rules), hitPolicy);
    }

    /** Whether a cell is a wildcard. Columns further right are wildcards more often. */
    private static boolean wildcard(Random random, int column, int columns) {
        return random.nextDouble() < WILDCARDS * (0.5 + column / (double) columns);
    }

    /** A cell of a string table: a wildcard (null) or one unquoted value. */
    private static String randomStringCell(Random random, int column, int columns) {
        return wildcard(random, column, columns) ? null : "v" + random.nextInt(STRING_DOMAIN);
    }

    /** A FEEL cell of a mixed table; {@code form} picks the shape of the cell, from 0 to 9. */
    private static String feelCell(ColumnType type, Random random) {
        int form = random.nextInt(10);
        return switch (type) {
            case STRING -> stringCell(form, random);
            case NUMBER -> numberCell(form, random);
            case DATE -> dateCell(form, random);
            case BOOLEAN, TIME, DATE_TIME -> throw new IllegalArgumentException("generated tables use STRING, NUMBER and DATE columns");
        };
    }

    private static String stringCell(int form, Random random) {
        if (form < 6) {
            return quotedString(random);
        }
        if (form < 8) {
            return quotedString(random) + ", " + quotedString(random);
        }
        return "not(" + quotedString(random) + ")";
    }

    /** Numbers range over 0..100; {@code low} and {@code high} are at least two apart. */
    private static String numberCell(int form, Random random) {
        int low = random.nextInt(90);
        int high = low + 2 + random.nextInt(100 - low - 1);
        return switch (form) {
            case 0, 1 -> "< " + low;
            case 2, 3 -> ">= " + low;
            case 4, 5 -> "[" + low + ".." + high + ")";
            case 6, 7 -> "(" + low + ".." + high + "]";
            default -> low + ", " + high;
        };
    }

    /** Dates range over the days of 2025; {@code low} and {@code high} are at least two days apart. */
    private static String dateCell(int form, Random random) {
        int low = random.nextInt(330);
        int high = low + 2 + random.nextInt(364 - low - 1);
        return switch (form) {
            case 0, 1, 2 -> "< " + dateLiteral(low);
            case 3, 4, 5 -> ">= " + dateLiteral(low);
            default -> "[" + dateLiteral(low) + ".." + dateLiteral(high) + "]";
        };
    }

    private static String quotedString(Random random) {
        return "\"v" + random.nextInt(STRING_DOMAIN) + "\"";
    }

    private static String dateLiteral(int day) {
        return "date(\"" + DAY0.plusDays(day) + "\")";
    }

    /** The column count of a table name ({@code x<columns>}), or the default when the name has none. */
    private static int columnsOr(String columns, int defaultColumns) {
        return columns == null ? defaultColumns : Integer.parseInt(columns);
    }

    /** The hit policy of a table name's suffix ({@code -first}...), or the default when the name has none. */
    private static HitPolicy hitPolicyOr(String suffix, HitPolicy defaultPolicy) {
        if (suffix == null) {
            return defaultPolicy;
        }
        return switch (suffix) {
            case "collect" -> HitPolicy.COLLECT;
            case "first" -> HitPolicy.FIRST;
            case "priority" -> HitPolicy.PRIORITY;
            case "ruleorder" -> HitPolicy.RULE_ORDER;
            default -> HitPolicy.UNIQUE;
        };
    }

    private static List<String> columnNames(String prefix, int columns) {
        var names = new ArrayList<String>(columns);
        for (int c = 0; c < columns; c++) {
            names.add(prefix + c);
        }
        return names;
    }

    private static List<ColumnType> strings(int columns) {
        return Collections.nCopies(columns, ColumnType.STRING);
    }
}
