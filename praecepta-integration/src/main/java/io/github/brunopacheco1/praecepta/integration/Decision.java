/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;

/**
 * One decision table loaded by a framework integration, with a map-based engine: inputs and outputs are the
 * columns' values as text, keyed by column name, as {@code PraeceptaEngine.Builder.inputs(...)} reads them.
 *
 * <pre>{@code
 * List<Map<String, String>> outputs = decision.engine().evaluate(Map.of("country", "PT", "age", "42"));
 * }</pre>
 *
 * Values are written as the table's cells write literals: numbers as {@code 42} or {@code 2.5}, dates as
 * {@code 2025-01-31}, times and date-times with or without a time zone, durations as {@code P1DT2H}, a
 * list column's value as a FEEL list ({@code ["PT", "BR"]}); a missing or blank value is a missing input.
 * Each output map holds one matching rule's outputs (an output that is missing, such as an expression with
 * a missing operand, maps to null); under a COLLECT aggregation, the one map holds the aggregate.
 *
 * @param name     the decision's name: the DMN decision's {@code name} (or {@code id}); engines are named and
 *                 injected by it
 * @param location where the table was read from ({@link TableSource#location()})
 * @param table    the table as read
 * @param engine   the engine, built once; immutable and thread-safe
 * @param report   the table's static analysis (overlaps, unreachable rules, gaps)
 */
public record Decision(String name, String location, DecisionTable table, PraeceptaEngine<Map<String, String>, Map<String, String>> engine,
        ValidationReport report) {

    public Decision {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(report, "report");
    }

    /**
     * Builds a table's map-based engine and validates it. Validation never fails here;
     * {@link Decisions#load} decides what fails.
     *
     * @throws IllegalArgumentException if the table cannot be built into an engine, such as an aggregation
     *                                  that DMN does not define for its outputs ({@link AggregationOperator#check})
     */
    public static Decision of(String location, DecisionTable table) {
        PraeceptaEngine<Map<String, String>, Map<String, String>> engine;
        try {
            if (table.aggregation() != null) {
                table.aggregation().check(table.outputs());
            }
            engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                    .table(table)
                    .inputs(PraeceptaInput::new)
                    .output(new MapOutput(table.aggregation() == null ? null : table.outputs().getFirst()))
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Table '%s': %s".formatted(table.name(), e.getMessage()), e);
        }
        return new Decision(table.name(), location, table, engine, engine.validationReport());
    }

    /** Evaluates an input: the column values by column name, as text. */
    public List<Map<String, String>> evaluate(Map<String, String> input) {
        return engine.evaluate(input);
    }

    /**
     * The decision as plain maps, lists, strings and numbers, for a management endpoint to serialise as JSON:
     * {@code name}, {@code location}, {@code hitPolicy}, {@code aggregation} (null for none), {@code rules}
     * (how many), {@code inputs} and {@code outputs} (each {@code name}, {@code type}, and for inputs
     * {@code list}), and {@code issues} (each {@code severity}, {@code kind}, {@code rules}, {@code detail},
     * {@code example} and {@code message}).
     */
    public Map<String, Object> describe() {
        var description = new LinkedHashMap<String, Object>();
        description.put("name", name);
        description.put("location", location);
        description.put("hitPolicy", table.hitPolicy().name());
        description.put("aggregation", table.aggregation() == null ? null : table.aggregation().name());
        description.put("rules", table.rules().size());
        var inputs = new ArrayList<Map<String, Object>>();
        for (var input : table.inputs()) {
            var column = new LinkedHashMap<String, Object>();
            column.put("name", input.name());
            column.put("type", input.type().name());
            column.put("list", table.listInputs().contains(input.name()));
            inputs.add(Collections.unmodifiableMap(column));
        }
        description.put("inputs", List.copyOf(inputs));
        description.put("outputs", table.outputs().stream()
                .map(output -> Map.<String, Object>of("name", output.name(), "type", output.type().name()))
                .toList());
        description.put("issues", report.issues().stream().map(Decision::describe).toList());
        return Collections.unmodifiableMap(description);
    }

    private static Map<String, Object> describe(Issue issue) {
        var description = new LinkedHashMap<String, Object>();
        description.put("severity", issue.severity().name());
        description.put("kind", issue.kind().name());
        description.put("rules", issue.ruleIds());
        description.put("detail", issue.detail());
        var example = new LinkedHashMap<String, Object>();
        issue.example().forEach((column, value) -> example.put(column, value == null ? null : String.valueOf(value)));
        description.put("example", Collections.unmodifiableMap(example));
        description.put("message", issue.toString());
        return Collections.unmodifiableMap(description);
    }

    /**
     * A rule's outputs as a map of text, and the aggregates of a COLLECT table's one output (DMN 1.5,
     * 10.3.2.10): SUM adds numbers with FEEL's precision (34 significant digits, 10.3.2.3.1) and keeps their
     * scale ({@code 10.50} and {@code 1.50} give {@code 12.00}), as the generated code of the build plugins
     * does; MIN and MAX keep the smallest or the largest value as written, ordered as FEEL orders values of the
     * output's type ({@link FeelTemporals#compare}); COUNT gives the number of matches. A missing value is
     * skipped, as the generated code skips it.
     *
     * @param aggregated the output a COLLECT aggregation aggregates, or null for none
     */
    private record MapOutput(OutputColumn aggregated) implements OutputStrategy<Map<String, String>> {

        @Override
        public Map<String, String> transform(PraeceptaOutput output) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(output.values()));
        }

        @Override
        public Map<String, String> sum(Map<String, String> a, Map<String, String> b) {
            var x = value(a);
            var y = value(b);
            if (x == null) {
                return b;
            }
            if (y == null) {
                return a;
            }
            return single(((BigDecimal) x).add((BigDecimal) y, MathContext.DECIMAL128).toPlainString());
        }

        @Override
        public Map<String, String> min(Map<String, String> a, Map<String, String> b) {
            var x = value(a);
            var y = value(b);
            return x == null ? b : y == null || FeelTemporals.compare(x, y) <= 0 ? a : b;
        }

        @Override
        public Map<String, String> max(Map<String, String> a, Map<String, String> b) {
            var x = value(a);
            var y = value(b);
            return x == null ? b : y == null || FeelTemporals.compare(x, y) >= 0 ? a : b;
        }

        @Override
        public Map<String, String> count(int matches) {
            return single(Integer.toString(matches));
        }

        /** The aggregated output's value, of the output's type; null if it is missing. */
        private Object value(Map<String, String> output) {
            var text = output.get(aggregated.name());
            return text == null ? null : FeelLiterals.value(text, aggregated.type());
        }

        private Map<String, String> single(String value) {
            return Map.of(aggregated.name(), value);
        }
    }
}
