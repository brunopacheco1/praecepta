/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Predicate;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Deterministic request mixes.
 * <ul>
 *   <li>{@code match}: each input starts from a random rule, takes a value satisfying every concrete
 *       cell, and a random known value (or null) for every wildcard cell, so it matches at least that
 *       rule.</li>
 *   <li>{@code nomatch}: the same inputs, with values no rule expects until none matches: an unknown
 *       string first (an unknown code, say), then a missing number or date. This is the path of
 *       requests the table does not cover. Tables with a catch-all rule, such as {@code real}, have
 *       no such inputs.</li>
 * </ul>
 *
 * <p>Values are {@code String}, {@code BigDecimal} or {@code LocalDate} by column type, created
 * fresh per input as if deserialised from a request.
 */
public final class Workload {

    public static final String MATCH = "match";
    public static final String NO_MATCH = "nomatch";

    private Workload() {
    }

    public static List<Map<String, Object>> generate(Table table, int size, long seed) {
        return table.stringsOnly() ? strings(table, size, seed) : typed(table, size, seed);
    }

    public static List<Map<String, Object>> generate(Table table, int size, long seed, String shape) {
        return switch (shape) {
            case MATCH -> generate(table, size, seed);
            case NO_MATCH -> noMatch(table, generate(table, size, seed), seed);
            default -> throw new IllegalArgumentException("Unknown workload: " + shape);
        };
    }

    private static List<Map<String, Object>> noMatch(Table table, List<Map<String, Object>> matching, long seed) {
        var reference = Contender.reference(table);
        var random = new Random(seed + 1);
        var stringColumns = new ArrayList<String>();
        var otherColumns = new ArrayList<String>();
        for (int c = 0; c < table.inputs().size(); c++) {
            (table.types().get(c) == ColumnType.STRING ? stringColumns : otherColumns).add(table.inputs().get(c));
        }
        var inputs = new ArrayList<Map<String, Object>>(matching.size());
        for (var original : matching) {
            // string columns first, then the others, each group in a new random order
            Collections.shuffle(stringColumns, random);
            Collections.shuffle(otherColumns, random);
            var columns = new ArrayList<>(stringColumns);
            columns.addAll(otherColumns);
            inputs.add(unmatched(table, reference, original, columns, stringColumns));
        }
        return List.copyOf(inputs);
    }

    /**
     * The input with the values of {@code columns}, in that order, replaced until no rule matches: an
     * unknown string in a string column, a missing value in any other.
     */
    private static Map<String, Object> unmatched(Table table, Contender reference, Map<String, Object> original,
                                                 List<String> columns, List<String> stringColumns) {
        var input = new HashMap<>(original);
        var next = columns.iterator();
        while (!reference.evaluate(input).isEmpty()) {
            if (!next.hasNext()) {
                throw new IllegalStateException("A rule of " + table.name() + " matches any input");
            }
            var column = next.next();
            input.put(column, stringColumns.contains(column) ? new String("unknown") : null);
        }
        return input;
    }

    /** String tables, with Praecepta's legacy cells ({@code a,b}); unchanged since Phase 0. */
    private static List<Map<String, Object>> strings(Table table, int size, long seed) {
        var domains = new ArrayList<List<String>>();
        for (var column : table.inputs()) {
            domains.add(stringDomain(table, column));
        }

        var random = new Random(seed);
        var inputs = new ArrayList<Map<String, Object>>(size);
        for (int i = 0; i < size; i++) {
            var rule = table.rules().get(random.nextInt(table.rules().size()));
            var input = new HashMap<String, Object>();
            for (int c = 0; c < table.inputs().size(); c++) {
                var column = table.inputs().get(c);
                var cell = rule.input().values().get(column);
                String value;
                if (cell == null || cell.isBlank()) {
                    value = anyValueOrNull(domains.get(c), random);
                } else {
                    var options = cell.split(",");
                    value = options[random.nextInt(options.length)];
                }
                input.put(column, value == null ? null : new String(value));
            }
            inputs.add(input);
        }
        return List.copyOf(inputs);
    }

    /** Every value the rules list in a string column, sorted. */
    private static List<String> stringDomain(Table table, String column) {
        var values = new TreeSet<String>();
        for (var rule : table.rules()) {
            var cell = rule.input().values().get(column);
            if (cell != null && !cell.isBlank()) {
                Collections.addAll(values, cell.split(","));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Typed tables with FEEL cells. Each column's candidate values are the literals in its cells and,
     * for numbers and dates, their neighbours (±1, ±1 day), which reach every interval between
     * boundaries. A wildcard takes a random candidate or null; {@code not(...)} a candidate none of
     * its tests accepts; any other cell one of its literals or a candidate inside one of its tests.
     */
    private static List<Map<String, Object>> typed(Table table, int size, long seed) {
        int columns = table.inputs().size();
        var cells = new UnaryTests[table.rules().size()][columns];
        var domains = new ArrayList<List<Comparable<?>>>();
        for (int c = 0; c < columns; c++) {
            var type = table.types().get(c);
            var values = new TreeSet<Comparable<?>>();
            for (int r = 0; r < cells.length; r++) {
                cells[r][c] = CellParser.parse(table.rules().get(r).input().values().get(table.inputs().get(c)), type);
                addLiteralsWithNeighbours(values, cells[r][c]);
            }
            domains.add(List.copyOf(values));
        }

        var random = new Random(seed);
        var inputs = new ArrayList<Map<String, Object>>(size);
        for (int i = 0; i < size; i++) {
            int rule = random.nextInt(cells.length);
            var input = new HashMap<String, Object>();
            for (int c = 0; c < columns; c++) {
                input.put(table.inputs().get(c), fresh(valueSatisfying(cells[rule][c], domains.get(c), random)));
            }
            inputs.add(input);
        }
        return List.copyOf(inputs);
    }

    /** A value the cell accepts, taken from the column's candidates; for a wildcard, possibly null. */
    private static Object valueSatisfying(UnaryTests cell, List<Comparable<?>> domain, Random random) {
        return switch (cell) {
            case UnaryTests.Any any -> anyValueOrNull(domain, random);
            case UnaryTests.Negated negated -> sample(domain, random, value -> negated.tests().stream().noneMatch(t -> accepts(t, value)));
            case UnaryTests.Positive positive -> {
                var test = positive.tests().get(random.nextInt(positive.tests().size()));
                yield switch (test) {
                    case NullTest n -> null;
                    case UnaryTests.NullElement n -> null;
                    case Equals e -> e.value();
                    default -> sample(domain, random, value -> accepts(test, value));
                };
            }
        };
    }

    /** The value for a wildcard: null one time in three, else a random value of the column (null if it has none). */
    private static <T> T anyValueOrNull(List<T> domain, Random random) {
        return domain.isEmpty() || random.nextInt(3) == 0 ? null : domain.get(random.nextInt(domain.size()));
    }

    private static Comparable<?> sample(List<Comparable<?>> domain, Random random, Predicate<Comparable<?>> accepted) {
        for (int attempt = 0; attempt < 100_000; attempt++) {
            var value = domain.get(random.nextInt(domain.size()));
            if (accepted.test(value)) {
                return value;
            }
        }
        throw new IllegalStateException("No candidate value satisfies the cell");
    }

    private static boolean accepts(Test test, Comparable<?> value) {
        return switch (test) {
            case NullTest n -> false;
            case UnaryTests.NullElement n -> false;
            case Equals equals -> compare(value, equals.value()) == 0;
            case UnaryTests.NotEqual(var negated) -> !accepts(negated, value);
            case Compare comparison -> {
                int cmp = compare(value, comparison.value());
                yield switch (comparison.operator()) {
                    case LT -> cmp < 0;
                    case LE -> cmp <= 0;
                    case GT -> cmp > 0;
                    case GE -> cmp >= 0;
                };
            }
            case Range range -> {
                int low = compare(value, range.low());
                int high = compare(value, range.high());
                yield (range.lowClosed() ? low >= 0 : low > 0) && (range.highClosed() ? high <= 0 : high < 0);
            }
        };
    }

    private static List<Test> tests(UnaryTests cell) {
        return switch (cell) {
            case UnaryTests.Any any -> List.of();
            case UnaryTests.Positive positive -> positive.tests();
            case UnaryTests.Negated negated -> negated.tests();
        };
    }

    private static void addLiteralsWithNeighbours(TreeSet<Comparable<?>> values, UnaryTests cell) {
        for (var test : tests(cell)) {
            switch (test) {
                case Equals equals -> addWithNeighbours(values, equals.value());
                case Compare comparison -> addWithNeighbours(values, comparison.value());
                case Range range -> {
                    addWithNeighbours(values, range.low());
                    addWithNeighbours(values, range.high());
                }
                case UnaryTests.NotEqual notEqual -> UnaryTests.values(notEqual).forEach(value -> addWithNeighbours(values, value));
                case NullTest nullTest -> { }
                case UnaryTests.NullElement nullElement -> { }
            }
        }
    }

    private static void addWithNeighbours(TreeSet<Comparable<?>> values, Comparable<?> value) {
        values.add(value);
        switch (value) {
            case BigDecimal number -> {
                values.add(number.subtract(BigDecimal.ONE));
                values.add(number.add(BigDecimal.ONE));
            }
            case LocalDate date -> {
                values.add(date.minusDays(1));
                values.add(date.plusDays(1));
            }
            default -> { }
        }
    }

    /** A new instance of the value, as if deserialised from a request. */
    private static Object fresh(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> new String(text);
            case BigDecimal number -> new BigDecimal(number.unscaledValue(), number.scale());
            case LocalDate date -> LocalDate.of(date.getYear(), date.getMonth(), date.getDayOfMonth());
            default -> value;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable<?> a, Comparable<?> b) {
        return ((Comparable) a).compareTo(b);
    }
}
