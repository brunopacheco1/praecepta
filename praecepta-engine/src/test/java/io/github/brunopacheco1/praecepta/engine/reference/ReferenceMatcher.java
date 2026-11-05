package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Executable specification of decision-table semantics: a deliberately naive matcher that scans
 * every rule, plus strict DMN 1.3 hit-policy rules (§8.2.12). It is the source of truth for the
 * differential tests; performance is irrelevant.
 *
 * <p>Cell semantics follow Praecepta's table format: a null or blank cell matches anything; a cell
 * {@code a,b} matches inputs equal to {@code a} or {@code b}. A null or blank input value only
 * matches wildcard cells.
 *
 * @param rules           rules in table order (DMN "rule order")
 * @param hitPolicy       DMN hit policy
 * @param outputPriority  for PRIORITY / OUTPUT_ORDER: per output column, its allowed values from
 *                        highest to lowest priority (DMN output value lists). Columns are compared
 *                        in map iteration order, so pass an ordered map.
 */
public record ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy, Map<String, List<String>> outputPriority) {

    public ReferenceMatcher(List<Praeceptum> rules, HitPolicy hitPolicy) {
        this(rules, hitPolicy, Map.of());
    }

    /** Result of evaluating a decision table under strict DMN semantics. */
    public sealed interface Outcome {
        /** Hits in the order mandated by the hit policy (single-hit policies yield at most one). */
        record Hits(List<PraeceptaOutput> outputs) implements Outcome {
        }

        /** The table violates its hit policy for this input (DMN: evaluation error / null result). */
        record Violation(String reason) implements Outcome {
        }
    }

    /** All rules whose every cell accepts the input, in table order. */
    public List<Praeceptum> matches(Map<String, String> input) {
        var matches = new ArrayList<Praeceptum>();
        for (var rule : rules) {
            if (accepts(rule, input)) {
                matches.add(rule);
            }
        }
        return matches;
    }

    public Outcome evaluate(Map<String, String> input) {
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
    public BigDecimal aggregate(Map<String, String> input, String output, Aggregator aggregator) {
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

    private static boolean accepts(Praeceptum rule, Map<String, String> input) {
        for (var cell : rule.input().values().entrySet()) {
            if (!accepts(cell.getValue(), input.get(cell.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean accepts(String cell, String value) {
        if (cell == null || cell.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        for (var option : cell.split(",")) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
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
