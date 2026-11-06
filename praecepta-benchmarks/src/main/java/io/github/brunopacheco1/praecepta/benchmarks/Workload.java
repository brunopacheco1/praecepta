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

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Deterministic request mix: each input starts from a random rule, takes a value satisfying every
 * concrete cell, and a random known value (or null) for every wildcard cell. Every input therefore
 * matches at least one rule, so no engine pays for a no-match path.
 *
 * <p>Values are {@code String}, {@code BigDecimal} or {@code LocalDate} by column type, created
 * fresh per input as if deserialised from a request.
 */
public final class Workload {

    private Workload() {
    }

    public static List<Map<String, Object>> generate(Table table, int size, long seed) {
        return table.stringsOnly() ? strings(table, size, seed) : typed(table, size, seed);
    }

    /** String tables, with Praecepta's legacy cells ({@code a,b}); unchanged since Phase 0. */
    private static List<Map<String, Object>> strings(Table table, int size, long seed) {
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
        var inputs = new ArrayList<Map<String, Object>>(size);
        for (int i = 0; i < size; i++) {
            var rule = table.rules().get(random.nextInt(table.rules().size()));
            var input = new HashMap<String, Object>();
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
                input.put(column, value == null ? null : new String(value));
            }
            inputs.add(input);
        }
        return List.copyOf(inputs);
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
                for (var test : tests(cells[r][c])) {
                    switch (test) {
                        case Equals e -> addWithNeighbours(values, e.value());
                        case Compare k -> addWithNeighbours(values, k.value());
                        case Range g -> {
                            addWithNeighbours(values, g.low());
                            addWithNeighbours(values, g.high());
                        }
                        case NullTest n -> { }
                    }
                }
            }
            domains.add(List.copyOf(values));
        }

        var random = new Random(seed);
        var inputs = new ArrayList<Map<String, Object>>(size);
        for (int i = 0; i < size; i++) {
            int r = random.nextInt(cells.length);
            var input = new HashMap<String, Object>();
            for (int c = 0; c < columns; c++) {
                input.put(table.inputs().get(c), fresh(pick(cells[r][c], domains.get(c), random)));
            }
            inputs.add(input);
        }
        return List.copyOf(inputs);
    }

    private static Object pick(UnaryTests cell, List<Comparable<?>> domain, Random random) {
        return switch (cell) {
            case UnaryTests.Any any -> domain.isEmpty() || random.nextInt(3) == 0 ? null : domain.get(random.nextInt(domain.size()));
            case UnaryTests.Negated negated -> sample(domain, random, value -> negated.tests().stream().noneMatch(t -> accepts(t, value)));
            case UnaryTests.Positive positive -> {
                var test = positive.tests().get(random.nextInt(positive.tests().size()));
                yield switch (test) {
                    case NullTest n -> null;
                    case Equals e -> e.value();
                    default -> sample(domain, random, value -> accepts(test, value));
                };
            }
        };
    }

    private static Comparable<?> sample(List<Comparable<?>> domain, Random random, java.util.function.Predicate<Comparable<?>> accepted) {
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
            case Equals e -> compare(value, e.value()) == 0;
            case Compare k -> {
                int cmp = compare(value, k.value());
                yield switch (k.operator()) {
                    case LT -> cmp < 0;
                    case LE -> cmp <= 0;
                    case GT -> cmp > 0;
                    case GE -> cmp >= 0;
                };
            }
            case Range g -> {
                int low = compare(value, g.low());
                int high = compare(value, g.high());
                yield (g.lowClosed() ? low >= 0 : low > 0) && (g.highClosed() ? high <= 0 : high < 0);
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
