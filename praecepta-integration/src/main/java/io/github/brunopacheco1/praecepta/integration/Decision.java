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
import java.util.NoSuchElementException;
import java.util.Objects;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;

/**
 * One decision loaded by a framework integration, with a map-based engine: inputs and outputs are the
 * columns' values as text, keyed by column name, as {@code PraeceptaEngine.Builder.inputs(...)} reads them.
 *
 * <pre>{@code
 * List<Map<String, String>> outputs = decision.evaluate(Map.of("country", "PT", "age", "42"));
 * }</pre>
 *
 * Values are written as the table's cells write literals: numbers as {@code 42} or {@code 2.5}, dates as
 * {@code 2025-01-31}, times and date-times with or without a time zone, durations as {@code P1DT2H}, a
 * list column's value as a FEEL list ({@code ["PT", "BR"]}); a missing or blank value is a missing input.
 * Each output map holds one matching rule's outputs (an output that is missing, such as an expression with
 * a missing operand, maps to null); under a COLLECT aggregation, the one map holds the aggregate, null for a
 * SUM, MIN or MAX when the output is missing in a matching rule (FEEL's {@code sum([10, null])} is null). When no
 * rule matches, the list holds the table's default output values, if it declares any, or is empty, whatever
 * the hit policy or aggregation (DMN 1.5, 10.3.2.10, step 2): a COUNT is then empty, not zero.
 *
 * <p><b>A decision of a decision requirements graph</b> (#14): when its file holds decisions that require other
 * decisions, or literal expressions, every decision of the file belongs to its {@link GraphModel}, and
 * {@link #evaluate(Map)} evaluates it from the model's <em>input data</em>, by name, through the decisions it requires,
 * each once, giving its value as maps of text ({@link GraphModel#evaluate}). Its {@link #engine()} still evaluates its
 * table alone, from its columns, the values of the decisions it requires included, as in 0.6; a literal expression
 * has neither table nor engine.
 *
 * @param name     the decision's name: the DMN decision's {@code name} (or {@code id}); engines are named and
 *                 injected by it
 * @param location where the decision was read from ({@link TableSource#location()})
 * @param table    the table as read; null for a decision of a model whose logic is a literal expression
 * @param engine   the table's engine, built once, immutable and thread-safe; null where {@code table} is
 * @param report   the table's static analysis (overlaps, unreachable rules, gaps); empty for a literal expression.
 *                 The issues of the graph a decision belongs to are its model's {@link GraphModel#report()}
 * @param model    the decision requirements graph the decision belongs to, which {@link #evaluate} evaluates it
 *                 through; null for a decision table read alone
 */
public record Decision(String name, String location, DecisionTable table, PraeceptaEngine<Map<String, String>, Map<String, String>> engine,
        ValidationReport report, GraphModel model) {

    /**
     * A decision.
     *
     * @throws NullPointerException     if a component that may not be null is
     * @throws IllegalArgumentException if it has a table but no engine, or an engine but no table, or neither and no
     *                                  model
     */
    public Decision {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(report, "report");
        if ((table == null) != (engine == null)) {
            throw new IllegalArgumentException("Decision '%s': a table needs its engine, and an engine its table".formatted(name));
        }
        if (table == null && model == null) {
            throw new IllegalArgumentException("Decision '%s' needs a table, or a model it belongs to".formatted(name));
        }
    }

    /** A decision table read alone, as in 0.6: evaluated by its engine, from its columns. */
    public Decision(String name, String location, DecisionTable table, PraeceptaEngine<Map<String, String>, Map<String, String>> engine,
            ValidationReport report) {
        this(name, location, Objects.requireNonNull(table, "table"), Objects.requireNonNull(engine, "engine"), report, null);
    }

    /**
     * Builds a table's map-based engine and validates it, the engine logging its validation report as
     * {@code PraeceptaEngine} does by default. Validation never fails here; {@link Decisions#load} decides what
     * fails.
     *
     * @throws IllegalArgumentException if the table cannot be built into an engine, such as an aggregation
     *                                  that DMN does not define for its outputs ({@link AggregationOperator#check})
     */
    public static Decision of(String location, DecisionTable table) {
        return of(location, table, true);
    }

    /**
     * Builds a table's map-based engine and validates it. Validation never fails here;
     * {@link Decisions#load} decides what fails.
     *
     * @param logReport whether the engine logs its validation report
     *                  ({@code PraeceptaEngine.Builder.logValidationReport}); false when the caller reports
     *                  {@link #report()} itself, as {@link DecisionsLoader} does
     * @throws IllegalArgumentException if the table cannot be built into an engine, such as an aggregation
     *                                  that DMN does not define for its outputs ({@link AggregationOperator#check})
     */
    public static Decision of(String location, DecisionTable table, boolean logReport) {
        PraeceptaEngine<Map<String, String>, Map<String, String>> engine;
        try {
            if (table.aggregation() != null) {
                table.aggregation().check(table.outputs());
            }
            engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                    .table(table)
                    .inputs(PraeceptaInput::new)
                    .output(new MapOutput(table.aggregation() == null ? null : table.outputs().getFirst()))
                    .logValidationReport(logReport)
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Table '%s': %s".formatted(table.name(), e.getMessage()), e);
        }
        return new Decision(table.name(), location, table, engine, engine.validationReport());
    }

    /**
     * A decision of a decision requirements graph: its table's map-based engine, if its logic is a table, and the model,
     * which evaluates it. Validation never fails here; {@link Decisions#load} decides what fails.
     *
     * @param logReport whether the table's engine logs its validation report; the graph's own tables log theirs as
     *                  {@link GraphModel#of} says
     * @throws NoSuchElementException   if the model has no decision of that name
     * @throws IllegalArgumentException if its table cannot be built into an engine
     */
    public static Decision of(GraphModel model, String name, boolean logReport) {
        var element = model.graph().model().decision(name).orElseThrow(() -> new NoSuchElementException(
                "Decision model '%s' has no decision named '%s'".formatted(model.name(), name)));
        if (element.logic() instanceof DecisionLogic.Table(var table)) {
            var alone = of(model.location(), table, logReport);
            return new Decision(name, model.location(), table, alone.engine(), alone.report(), model);
        }
        return new Decision(name, model.location(), null, null, model.graph().validationReport(name), model);
    }

    /**
     * Evaluates the decision: a table read alone from its column values by column name, as text; a decision of a
     * {@linkplain #model() model} from the model's input data by name, as text, through the decisions it requires
     * ({@link GraphModel#evaluate}).
     */
    public List<Map<String, String>> evaluate(Map<String, String> input) {
        return model == null ? engine.evaluate(input) : model.evaluate(name, input);
    }

    /**
     * The decision as plain maps, lists, strings and numbers, for a management endpoint to serialise as JSON:
     * {@code name}, {@code location}, {@code hitPolicy}, {@code aggregation} (null for none), {@code rules}
     * (how many), {@code inputs} and {@code outputs} (each {@code name}, {@code type}, and for inputs
     * {@code list}), and {@code issues} (each {@code severity}, {@code kind}, {@code rules}, {@code detail},
     * {@code example} and {@code message}). A decision of a model has no table keys for a literal expression, and
     * adds {@code model}, {@code logic}, {@code expression} (for a literal expression), {@code type},
     * {@code requiredDecisions}, {@code inputData} and {@code canEvaluate} ({@link GraphModel}).
     */
    public Map<String, Object> describe() {
        var description = new LinkedHashMap<String, Object>();
        description.put("name", name);
        description.put("location", location);
        if (table != null) {
            describeTable(description);
        }
        description.put("issues", report.issues().stream().map(Decision::describe).toList());
        if (model != null) {
            description.putAll(model.describe(name));
        }
        return Collections.unmodifiableMap(description);
    }

    private void describeTable(Map<String, Object> description) {
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
    }

    /** An issue as plain maps, for a management endpoint. */
    static Map<String, Object> describe(Issue issue) {
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
     * output's type ({@link FeelTemporals#compare}), and give the output mapped to null when two of the values do
     * not compare (times with a zone id in two zones, which DMN 1.5 does not order: FEEL's {@code min} and
     * {@code max} take a list of comparable items, and a built-in function is null outside its domain, 10.3.2.16);
     * COUNT gives the number of matches. None of them sees a missing value: where the output is
     * missing in a matching rule, the engine gives that rule's map, the output mapped to null, as FEEL's
     * {@code sum}, {@code min} and {@code max} are null for a list that holds null (10.3.4.4). None is called when
     * no rule matches: the engine then gives the default output, or none (10.3.2.10, step 2).
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
            return single(((BigDecimal) value(a)).add((BigDecimal) value(b), MathContext.DECIMAL128).toPlainString());
        }

        @Override
        public Map<String, String> min(Map<String, String> a, Map<String, String> b) {
            return extreme(a, b, -1);
        }

        @Override
        public Map<String, String> max(Map<String, String> a, Map<String, String> b) {
            return extreme(a, b, 1);
        }

        /**
         * The smaller ({@code sign} -1) or the larger (1) of two outputs; the output mapped to null if they do not
         * compare, and then for every output after, as the result is null once two values are outside FEEL's order.
         */
        private Map<String, String> extreme(Map<String, String> a, Map<String, String> b, int sign) {
            if (a.get(aggregated.name()) == null) {
                return a; // two values before did not compare
            }
            var x = value(a);
            var y = value(b);
            if (!FeelTemporals.comparable(x, y)) {
                return Collections.singletonMap(aggregated.name(), null);
            }
            return Integer.signum(FeelTemporals.compare(x, y)) == -sign ? b : a;
        }

        @Override
        public Map<String, String> count(int matches) {
            return single(Integer.toString(matches));
        }

        /** The aggregated output's value, of the output's type; the engine never aggregates a missing one. */
        private Object value(Map<String, String> output) {
            return FeelLiterals.value(output.get(aggregated.name()), aggregated.type());
        }

        private Map<String, String> single(String value) {
            return Map.of(aggregated.name(), value);
        }
    }
}
