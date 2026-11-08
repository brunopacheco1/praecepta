/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Executable specification of decision-table semantics: a deliberately naive matcher that evaluates
 * every cell of every rule against concrete values, plus strict DMN 1.3 hit-policy rules (§8.2.12).
 * It is the source of truth for the differential tests; performance is irrelevant.
 *
 * <p>Cells are parsed with the engine's {@link CellParser} (checked by its own example tests), but
 * evaluated here, directly on values, with FEEL's three-valued logic: a missing value fails
 * comparisons and intervals (unknown), equals only {@code null}, and so satisfies {@code not("A")}.
 * Blank strings count as missing. The engine instead precomputes value classes and bitmasks.
 *
 * @param rules          rules in table order (DMN "rule order")
 * @param hitPolicy      DMN hit policy
 * @param outputPriority for PRIORITY / OUTPUT_ORDER: per output column, its values from highest to
 *                       lowest priority (DMN output value lists), compared in map iteration order
 * @param types          column types; columns not listed are STRING
 * @param allowedValues  declared input values per column, as unary tests of the column's type
 */
public record ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority,
        Map<String, ColumnType> types, Map<String, String> allowedValues) {

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy) {
        this(rules, hitPolicy, Map.of());
    }

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority) {
        this(rules, hitPolicy, outputPriority, Map.of(), Map.of());
    }

    /** Result of evaluating a decision table under strict DMN semantics. */
    public sealed interface Outcome {
        /** Hits in the order mandated by the hit policy (single-hit policies yield at most one). */
        record Hits(List<PraeceptaOutput> outputs) implements Outcome {
        }

        /** The table violates its hit policy for this input (DMN: evaluation error / null result). */
        record Violation(String reason) implements Outcome {
        }

        /** A value is outside its column's declared input values. */
        record InvalidInput(String column) implements Outcome {
        }
    }

    // ---- evaluation ---------------------------------------------------------------------------

    /** All rules whose every cell accepts the input, in table order. */
    public List<Praeceptum> matches(Map<String, ?> input) {
        var matches = new ArrayList<Praeceptum>();
        for (var rule : rules) {
            boolean accepted = true;
            for (var cell : rule.input().values().entrySet()) {
                if (!accepts(parse(cell.getValue(), cell.getKey()), value(input, cell.getKey()))) {
                    accepted = false;
                    break;
                }
            }
            if (accepted) {
                matches.add(rule);
            }
        }
        return matches;
    }

    /** The first column whose (non-missing) value is outside its declared input values, or null. */
    public String invalidColumn(Map<String, ?> input) {
        for (var declared : allowedValues.entrySet()) {
            var value = value(input, declared.getKey());
            if (value != null && !accepts(parse(declared.getValue(), declared.getKey()), value)) {
                return declared.getKey();
            }
        }
        return null;
    }

    public Outcome evaluate(Map<String, ?> input) {
        var invalid = invalidColumn(input);
        if (invalid != null) {
            return new Outcome.InvalidInput(invalid);
        }
        var matches = matches(input);
        var outputs = matches.stream().map(Praeceptum::output).toList();
        return switch (hitPolicy) {
            case UNIQUE -> matches.size() > 1
                    ? new Outcome.Violation("UNIQUE: %d rules match".formatted(matches.size()))
                    : new Outcome.Hits(outputs);
            case ANY -> Set.copyOf(outputs).size() > 1
                    ? new Outcome.Violation("ANY: matching rules disagree on outputs")
                    : new Outcome.Hits(outputs.isEmpty() ? List.of() : List.of(outputs.getFirst()));
            case FIRST -> new Outcome.Hits(outputs.isEmpty() ? List.of() : List.of(outputs.getFirst()));
            case PRIORITY -> new Outcome.Hits(outputs.stream().sorted(byPriority()).limit(1).toList());
            case RULE_ORDER, COLLECT -> new Outcome.Hits(outputs);
            case OUTPUT_ORDER -> new Outcome.Hits(outputs.stream().sorted(byPriority()).toList());
        };
    }

    /** DMN COLLECT with an aggregator over a single numeric output column. Empty input → null (COUNT → 0). */
    public BigDecimal aggregate(Map<String, ?> input, String output, Aggregator aggregator) {
        if (hitPolicy != HitPolicy.COLLECT) {
            throw new IllegalStateException("DMN only allows aggregation with COLLECT");
        }
        var values = matches(input).stream()
                .map(rule -> rule.output().values().get(output))
                .filter(Objects::nonNull)
                .map(BigDecimal::new)
                .toList();
        return switch (aggregator) {
            case COUNT -> BigDecimal.valueOf(values.size());
            case SUM -> values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElse(null);
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElse(null);
        };
    }

    public enum Aggregator { COUNT, SUM, MIN, MAX }

    // ---- static analysis, by brute force --------------------------------------------------------

    /** An expected validation finding: the kind name and the rule ids involved, in rule order. */
    public record Finding(String kind, List<Integer> ruleIds) {
    }

    /**
     * Expected overlap and reachability findings. Every input region is visited by evaluating every
     * combination of {@link #representativeValues representative values}; invalid inputs are skipped,
     * since evaluation rejects them before matching.
     */
    public Set<Finding> expectedFindings(List<String> columns) {
        var findings = new LinkedHashSet<Finding>();
        var selected = new HashSet<Praeceptum>();
        for (var input : representativeInputs(columns, true)) {
            if (invalidColumn(input) != null) {
                continue;
            }
            var matches = matches(input);
            if (matches.isEmpty()) {
                continue;
            }
            switch (hitPolicy) {
                case UNIQUE -> {
                    for (int a = 0; a < matches.size(); a++) {
                        for (int b = a + 1; b < matches.size(); b++) {
                            findings.add(new Finding("OVERLAPPING_RULES", ids(List.of(matches.get(a), matches.get(b)))));
                        }
                    }
                }
                case ANY -> {
                    for (int a = 0; a < matches.size(); a++) {
                        for (int b = a + 1; b < matches.size(); b++) {
                            if (!matches.get(a).output().equals(matches.get(b).output())) {
                                findings.add(new Finding("CONFLICTING_OUTPUTS", ids(List.of(matches.get(a), matches.get(b)))));
                            }
                        }
                    }
                }
                case FIRST -> selected.add(matches.getFirst());
                case PRIORITY -> {
                    var comparator = Comparator.comparing(Praeceptum::output, byPriority());
                    selected.add(matches.stream().sorted(comparator).findFirst().orElseThrow());
                }
                default -> {
                    // multi-hit policies have no static findings
                }
            }
        }
        if (hitPolicy == HitPolicy.FIRST || hitPolicy == HitPolicy.PRIORITY) {
            for (var rule : rules) {
                if (!selected.contains(rule)) {
                    findings.add(new Finding("UNREACHABLE_RULE", List.of(rule.priority())));
                }
            }
        }
        return findings;
    }

    /** Gaps are only analysed when every string column the rules constrain declares its input values. */
    public boolean gapAnalysisApplies(List<String> columns) {
        for (var column : columns) {
            boolean constrained = rules.stream().anyMatch(rule -> !(parse(rule.input().values().get(column), column) instanceof UnaryTests.Any));
            if (type(column) == ColumnType.STRING && constrained && !allowedValues.containsKey(column)) {
                return false;
            }
        }
        return true;
    }

    /** Valid, non-missing representative inputs that no rule matches. */
    public List<Map<String, Object>> uncoveredInputs(List<String> columns) {
        return representativeInputs(columns, false).stream()
                .filter(input -> invalidColumn(input) == null && matches(input).isEmpty())
                .toList();
    }

    /**
     * Values that visit every region the rules (and declared input values) distinguish: every value
     * written, the points between and around them (for dates, the neighbouring days, since there is
     * no date between consecutive days), both booleans, a string no rule mentions, and optionally a
     * missing value.
     */
    public List<Object> representativeValues(String column, boolean withMissing) {
        var values = new ArrayList<Object>();
        var type = type(column);
        var cells = new ArrayList<UnaryTests>();
        rules.forEach(rule -> cells.add(parse(rule.input().values().get(column), column)));
        if (allowedValues.containsKey(column)) {
            cells.add(parse(allowedValues.get(column), column));
        }
        if (type == ColumnType.BOOLEAN) {
            values.addAll(List.of(false, true)); // the whole domain
        } else if (type == ColumnType.STRING) {
            var written = new TreeSet<String>();
            cells.forEach(cell -> literals(cell).forEach(v -> written.add((String) v)));
            values.addAll(written);
            values.add("other-value");
        } else {
            var points = new TreeSet<Object>(ReferenceMatcher::compare);
            cells.forEach(cell -> points.addAll(literals(cell)));
            var sorted = new ArrayList<>(points);
            var around = new TreeSet<Object>(ReferenceMatcher::compare);
            around.addAll(sorted);
            if (sorted.isEmpty()) {
                around.add(switch (type) {
                    case NUMBER -> BigDecimal.ZERO;
                    case TIME -> LocalTime.NOON;
                    case DATE_TIME -> LocalDateTime.of(2025, 1, 1, 0, 0);
                    default -> LocalDate.of(2025, 1, 1);
                });
            }
            for (int i = 0; i < sorted.size(); i++) {
                var point = sorted.get(i);
                var next = i + 1 < sorted.size() ? sorted.get(i + 1) : null;
                switch (point) {
                    case BigDecimal number -> {
                        around.add(number.subtract(BigDecimal.ONE));
                        around.add(number.add(BigDecimal.ONE));
                        if (next != null) {
                            around.add(number.add((BigDecimal) next).divide(BigDecimal.TWO));
                        }
                    }
                    case LocalDate date -> {
                        // no date lies between consecutive days
                        around.add(date.minusDays(1));
                        around.add(date.plusDays(1));
                    }
                    case LocalTime time -> {
                        around.add(time.minusMinutes(1));
                        around.add(time.plusMinutes(1));
                        if (next != null) {
                            around.add(LocalTime.ofNanoOfDay((time.toNanoOfDay() + ((LocalTime) next).toNanoOfDay()) / 2));
                        }
                    }
                    case LocalDateTime moment -> {
                        around.add(moment.minusHours(1));
                        around.add(moment.plusHours(1));
                        if (next != null) {
                            // no zone: measure on the UTC timeline
                            var start = moment.toInstant(ZoneOffset.UTC);
                            var end = ((LocalDateTime) next).toInstant(ZoneOffset.UTC);
                            around.add(LocalDateTime.ofInstant(start.plus(Duration.between(start, end).dividedBy(2)), ZoneOffset.UTC));
                        }
                    }
                    default -> throw new IllegalStateException("not an ordered value: " + point);
                }
            }
            values.addAll(around);
        }
        if (withMissing) {
            values.add(null);
        }
        return values;
    }

    private List<Map<String, Object>> representativeInputs(List<String> columns, boolean withMissing) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(new HashMap<>());
        for (var column : columns) {
            var next = new ArrayList<Map<String, Object>>();
            for (var partial : inputs) {
                for (var option : representativeValues(column, withMissing)) {
                    var input = new HashMap<>(partial);
                    input.put(column, option);
                    next.add(input);
                }
            }
            inputs = next;
        }
        return inputs;
    }

    // ---- FEEL semantics on concrete values ------------------------------------------------------

    private static boolean accepts(UnaryTests cell, Object value) {
        return switch (cell) {
            case UnaryTests.Any any -> true;
            case UnaryTests.Positive positive -> any(positive.tests(), value) == Boolean.TRUE;
            case UnaryTests.Negated negated -> any(negated.tests(), value) == Boolean.FALSE;
        };
    }

    /** True if any test is true; otherwise unknown (null) if any is unknown; otherwise false. */
    private static Boolean any(List<Test> tests, Object value) {
        boolean unknown = false;
        for (var test : tests) {
            var outcome = test(test, value);
            if (outcome == null) {
                unknown = true;
            } else if (outcome) {
                return true;
            }
        }
        return unknown ? null : false;
    }

    private static Boolean test(Test test, Object value) {
        if (value == null) {
            return switch (test) {
                case NullTest n -> true;
                case Equals e -> false;
                case Compare c -> null;
                case Range r -> null;
            };
        }
        return switch (test) {
            case NullTest n -> false;
            case Equals e -> compare(value, e.value()) == 0;
            case Compare c -> switch (c.operator()) {
                case LT -> compare(value, c.value()) < 0;
                case LE -> compare(value, c.value()) <= 0;
                case GT -> compare(value, c.value()) > 0;
                case GE -> compare(value, c.value()) >= 0;
            };
            case Range r -> {
                int low = compare(value, r.low());
                int high = compare(value, r.high());
                yield (r.lowClosed() ? low >= 0 : low > 0) && (r.highClosed() ? high <= 0 : high < 0);
            }
        };
    }

    private static List<Object> literals(UnaryTests cell) {
        var tests = switch (cell) {
            case UnaryTests.Any any -> List.<Test>of();
            case UnaryTests.Positive positive -> positive.tests();
            case UnaryTests.Negated negated -> negated.tests();
        };
        var values = new ArrayList<Object>();
        for (var test : tests) {
            switch (test) {
                case Equals e -> values.add(e.value());
                case Compare c -> values.add(c.value());
                case Range r -> {
                    values.add(r.low());
                    values.add(r.high());
                }
                case NullTest n -> {
                    // no value
                }
            }
        }
        return values;
    }

    private UnaryTests parse(String cell, String column) {
        return CellParser.parse(cell, type(column));
    }

    private ColumnType type(String column) {
        return types.getOrDefault(column, ColumnType.STRING);
    }

    private static Object value(Map<String, ?> input, String column) {
        var value = input.get(column);
        return value instanceof String s && s.isBlank() ? null : value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }

    private static List<Integer> ids(List<Praeceptum> rules) {
        return rules.stream().map(Praeceptum::priority).toList();
    }

    /** Highest priority first; stable, so ties keep rule order. */
    private Comparator<PraeceptaOutput> byPriority() {
        if (outputPriority.isEmpty()) {
            throw new IllegalStateException(hitPolicy + " requires output value lists (outputPriority)");
        }
        Comparator<PraeceptaOutput> comparator = (a, b) -> 0;
        for (var column : outputPriority.entrySet()) {
            var order = column.getValue();
            comparator = comparator.thenComparingInt(output -> rank(order, output.values().get(column.getKey())));
        }
        return comparator;
    }

    private static int rank(List<String> order, String value) {
        int index = order.indexOf(value);
        if (index < 0) {
            throw new IllegalArgumentException("Output value '%s' is not in its output value list %s".formatted(value, order));
        }
        return index;
    }
}
