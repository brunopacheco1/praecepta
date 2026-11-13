/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.InputData;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision requirements graph, evaluated: the decisions of a decision model (DMN 1.5, chapter 6), each a
 * {@linkplain DecisionLogic.Table decision table} or a {@linkplain DecisionLogic.LiteralExpression literal expression},
 * evaluated in dependency order from the model's input data, each decision's value given to the decisions that require
 * it under the decision's name.
 *
 * <pre>{@code
 * DecisionGraph graph;
 * try (var dmn = Files.newInputStream(Path.of("pricing.dmn"))) {
 *     graph = DecisionGraph.read(dmn);
 * }
 * Object discount = graph.evaluate("Discount", Map.of("Customer Age", 42, "Order Total", new BigDecimal("120.00")));
 * }</pre>
 *
 * <p><b>Values.</b> A decision table with one output gives that output's value; with several outputs, a context of them
 * by name ({@code Map<String, Object>}, in output order), whose components a decision reads by path
 * ({@code Pricing.discount}); under a hit policy with several results (RULE ORDER, OUTPUT ORDER, COLLECT without an
 * aggregation), a list of those, which a decision table input of lists takes; null when no rule matches and the table
 * declares no default output (DMN 1.5, 10.3.2.10). A literal expression gives the value of its expression. Values are
 * FEEL's, as {@link FeelType} lists them: {@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate},
 * times, date-times, durations, {@code List} and {@code Map}, all immutable. Input data are given as such values, or
 * in any form a table's typed input bindings take ({@code Integer}, {@code Period}, {@code OffsetDateTime}...), or, for
 * a simple type, as text ({@code "42"}, {@code "2025-01-31"}); an input data the map does not hold is missing (null).
 *
 * <p><b>Building.</b> {@link #read} reads a DMN file ({@link DmnReader#readModel}); {@link #builder()} assembles a graph
 * from tables and literal expressions in code. Building checks the model, as DMN 1.5 requires it to be well formed:
 * a decision that requires a decision or input data the model does not have, or whose logic reads a name none of its
 * information requirements provides ({@link ValidationReport.Kind#MISSING_REQUIREMENT}), decisions that require one
 * another in a cycle ({@link ValidationReport.Kind#DECISION_CYCLE}), and a value that cannot fit where it goes, such as
 * a string into a number column ({@link ValidationReport.Kind#TYPE_MISMATCH}), are ERRORs of
 * {@link #validationReport()}. Such a decision, and every decision that requires it, cannot be evaluated; the others
 * can. A literal expression whose value does not conform to the type its decision declares is reported too, but DMN
 * 1.5 defines its value, null (10.3.2.9.4), so it is evaluated. Each decision table keeps its own analysis
 * ({@link #validationReport(String)}), exactly as a {@link PraeceptaEngine} built from it alone, with map-based inputs,
 * reports it.
 *
 * <p><b>Evaluating.</b> {@link #evaluate(String, Map)} evaluates a decision and the decisions it requires, directly or
 * indirectly, each once, in dependency order, and nothing else; every table, expression and name was parsed and
 * resolved when the graph was built. A graph is immutable and thread-safe.
 *
 * <p>Business knowledge models, invocations and boxed expressions are planned for 0.10
 * (<a href="https://github.com/brunopacheco1/praecepta/issues/118">#118</a>), the full FEEL language for 0.8
 * (<a href="https://github.com/brunopacheco1/praecepta/issues/116">#116</a>) and its built-in functions for 0.9
 * (<a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>), decision services and imports for 0.11
 * (<a href="https://github.com/brunopacheco1/praecepta/issues/119">#119</a>); a model that uses them is rejected when it
 * is read.
 */
public final class DecisionGraph {

    private static final System.Logger LOG = System.getLogger(DecisionGraph.class.getName());

    private final DecisionModel model;
    /** The number of slots: one per input data, then one per decision. */
    private final int slotCount;
    /** Each input data's name and type (declared, or worked out from the tables that read it), by slot. */
    private final String[] inputNames;
    private final FeelType[] inputTypes;
    /** Each decision's logic, ready to evaluate, by index in the model; null if it cannot be evaluated. */
    private final GraphNodes.Node[] nodes;
    /** The type of each input data and decision, by name. */
    private final Map<String, FeelType> types;
    /** Each decision's index in the model, by name. */
    private final Map<String, Integer> decisionIndexes = new HashMap<>();
    /** By decision: the indexes of the decisions it requires, and the slots of the input data it requires. */
    private final int[][] requiredDecisions;
    private final int[][] requiredInputs;
    /** By decision: why it cannot be evaluated, or null if it can. */
    private final String[] blocked;
    /** Each decision's position in an order where every decision comes after those it requires. */
    private final int[] rank;
    /** The plans worked out so far, by decision index: each the first time its decision is evaluated. */
    private final Map<Integer, Plan> plans = new ConcurrentHashMap<>();
    private final ValidationReport report;
    private final Map<String, ValidationReport> tableReports;

    /**
     * How to evaluate a decision.
     *
     * @param decision  its index in the model
     * @param inputs    the slots of the input data it requires, directly or indirectly, in the model's order
     * @param decisions the indexes of the decisions to evaluate, itself last, each after those it requires
     */
    private record Plan(int decision, int[] inputs, int[] decisions) {
    }

    private DecisionGraph(DecisionModel model, Severity failOn, boolean log) {
        this.model = model;
        var analysis = new GraphAnalysis(model);
        int inputCount = analysis.inputCount;
        int decisionCount = model.decisions().size();
        slotCount = inputCount + decisionCount;
        inputNames = new String[inputCount];
        inputTypes = new FeelType[inputCount];
        var allTypes = new LinkedHashMap<String, FeelType>();
        for (int i = 0; i < inputCount; i++) {
            inputNames[i] = model.inputData().get(i).name();
            inputTypes[i] = analysis.types[i];
            allTypes.put(inputNames[i], inputTypes[i]);
        }
        nodes = new GraphNodes.Node[decisionCount];
        requiredDecisions = new int[decisionCount][];
        requiredInputs = new int[decisionCount][];
        blocked = analysis.blocked.clone();
        var reports = new LinkedHashMap<String, ValidationReport>();
        for (int d = 0; d < decisionCount; d++) {
            var decision = model.decisions().get(d);
            decisionIndexes.put(decision.name(), d);
            allTypes.put(decision.name(), analysis.types[inputCount + d]);
            nodes[d] = node(analysis, d, log);
            reports.put(decision.name(), nodes[d].report());
            requiredDecisions[d] = analysis.requiredDecisions(d);
            requiredInputs[d] = decision.requiredInputs().stream().map(analysis.slots::get)
                    .filter(slot -> slot != null && slot < inputCount).mapToInt(Integer::intValue).toArray();
        }
        types = Collections.unmodifiableMap(allTypes);
        tableReports = Collections.unmodifiableMap(reports);
        rank = new int[decisionCount];
        for (int i = 0; i < analysis.order.length; i++) {
            rank[analysis.order[i]] = i;
        }
        report = new ValidationReport(analysis.issues());
        if (log && !report.isEmpty()) {
            LOG.log(Level.WARNING, "Decision model ''{0}'' has {1} validation issue(s):\n{2}", model.name(), report.issues().size(), report);
        }
        enforce(failOn);
    }

    private static GraphNodes.Node node(GraphAnalysis analysis, int d, boolean log) {
        var decision = analysis.model.decisions().get(d);
        var reads = analysis.reads.get(d);
        return switch (decision.logic()) {
            case DecisionLogic.Table(var table) ->
                    new GraphNodes.TableNode(table, analysis.tableReads(d), reads, analysis.listedNamedInputs.get(d), log);
            case DecisionLogic.LiteralExpression literal -> new GraphNodes.LiteralNode(literal.expression(), reads, analysis.bindings[d]);
        };
    }

    /**
     * The decisions and input data a decision that can be evaluated needs, directly or indirectly, required decisions
     * first, found with a stack of its own, so a long chain takes no more of the thread's stack than a short one.
     */
    private Plan plan(int target) {
        var needed = new TreeSet<Integer>((a, b) -> Integer.compare(rank[a], rank[b]));
        var inputs = new TreeSet<Integer>();
        var pending = new ArrayDeque<Integer>();
        pending.push(target);
        while (!pending.isEmpty()) {
            int d = pending.pop();
            if (needed.add(d)) {
                IntStream.of(requiredInputs[d]).forEach(inputs::add);
                IntStream.of(requiredDecisions[d]).forEach(pending::push);
            }
        }
        return new Plan(target, inputs.stream().mapToInt(Integer::intValue).toArray(), needed.stream().mapToInt(Integer::intValue).toArray());
    }

    private void enforce(Severity failOn) {
        if (failOn == null) {
            return;
        }
        var failing = new ArrayList<String>();
        report.issues().stream().filter(issue -> issue.severity().compareTo(failOn) >= 0).forEach(issue -> failing.add(issue.toString()));
        tableReports.forEach((decision, tableReport) -> tableReport.issues().stream()
                .filter(issue -> issue.severity().compareTo(failOn) >= 0)
                .forEach(issue -> failing.add("decision '%s': %s".formatted(decision, issue))));
        if (!failing.isEmpty()) {
            throw new InvalidDecisionTableException("Decision model '%s' has %d validation issue(s) at %s or above:\n%s"
                    .formatted(model.name(), failing.size(), failOn, String.join("\n", failing)));
        }
    }

    /**
     * Reads a decision model from DMN ({@link DmnReader#readModel}) and builds its graph, which logs its validation
     * report, if it has issues, as one warning (logger {@code io.github.brunopacheco1.praecepta.engine.DecisionGraph}),
     * and each decision table its own, as {@link PraeceptaEngine} does.
     *
     * @throws InvalidDecisionTableException if the model cannot be read, uses what Praecepta does not support yet, or
     *                                       has a decision table that cannot be built into an engine
     */
    public static DecisionGraph read(InputStream dmn) {
        return builder().model(DmnReader.readModel(dmn)).build();
    }

    /** Starts assembling a graph in code, or from a {@link DecisionModel}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Evaluates a decision, and the decisions it requires, directly or indirectly, each once, from the input data they
     * require.
     *
     * @param decision  the decision's name
     * @param inputData the input data's values by name; one the map does not hold, or holds as null, is missing
     * @return the decision's value (see the class description), null for FEEL's null
     * @throws NoSuchElementException        if the graph has no decision of that name
     * @throws InvalidDecisionTableException if the decision cannot be evaluated: its validation report, or that of a
     *                                       decision it requires, has a {@link ValidationReport.Kind#MISSING_REQUIREMENT},
     *                                       {@link ValidationReport.Kind#DECISION_CYCLE} or
     *                                       {@link ValidationReport.Kind#TYPE_MISMATCH} issue, which the message names
     * @throws InvalidInputException         if an input data's value is not one of its type, or a decision table
     *                                       rejects a value (one outside its column's input values)
     * @throws HitPolicyViolationException   if a decision table's hit policy is violated (UNIQUE, ANY)
     */
    public Object evaluate(String decision, Map<String, ?> inputData) {
        var plan = plan(decision);
        var values = new Object[slotCount];
        assign(plan.inputs(), values, inputData);
        for (int d : plan.decisions()) {
            values[inputNames.length + d] = nodes[d].evaluate(values);
        }
        return values[inputNames.length + plan.decision()];
    }

    /**
     * Evaluates several decisions at once, each decision they require being evaluated once for all of them.
     *
     * @return each decision's value, by name, in the order given (nulls included)
     * @throws NoSuchElementException        if the graph has no decision of one of the names
     * @throws InvalidDecisionTableException if one of the decisions cannot be evaluated (see {@link #evaluate(String, Map)})
     */
    public Map<String, Object> evaluate(Collection<String> decisions, Map<String, ?> inputData) {
        var selected = decisions.stream().map(this::plan).toList();
        var inputs = new TreeSet<Integer>();
        var needed = new TreeSet<Integer>((a, b) -> Integer.compare(rank[a], rank[b]));
        for (var plan : selected) {
            IntStream.of(plan.inputs()).forEach(inputs::add);
            IntStream.of(plan.decisions()).forEach(needed::add);
        }
        var values = new Object[slotCount];
        assign(inputs.stream().mapToInt(Integer::intValue).toArray(), values, inputData);
        for (int d : needed) {
            values[inputNames.length + d] = nodes[d].evaluate(values);
        }
        var results = new LinkedHashMap<String, Object>();
        selected.forEach(plan -> results.put(model.decisions().get(plan.decision()).name(), values[inputNames.length + plan.decision()]));
        return Collections.unmodifiableMap(results);
    }

    private Plan plan(String decision) {
        int d = index(decision);
        if (blocked[d] != null) {
            throw new InvalidDecisionTableException("Decision '%s' cannot be evaluated: %s".formatted(decision, blocked[d]));
        }
        return plans.computeIfAbsent(d, this::plan);
    }

    private int index(String decision) {
        var d = decisionIndexes.get(Objects.requireNonNull(decision, "decision"));
        if (d == null) {
            throw new NoSuchElementException(
                    "Decision model '%s' has no decision named '%s'; it has %s".formatted(model.name(), decision, decisions()));
        }
        return d;
    }

    private void assign(int[] inputs, Object[] values, Map<String, ?> inputData) {
        for (int slot : inputs) {
            values[slot] = GraphValues.normalise(inputData.get(inputNames[slot]), inputTypes[slot], inputNames[slot]);
        }
    }

    /** The model the graph evaluates; for a graph assembled in code, with the requirements its builder worked out. */
    public DecisionModel model() {
        return model;
    }

    /** The decisions' names, in the model's order. */
    public List<String> decisions() {
        return model.decisions().stream().map(Decision::name).toList();
    }

    /**
     * The type of each input data, as declared or, for one the model declares no type for, as the decision tables that
     * read it read it; {@link FeelType#ANY} if none does.
     */
    public Map<String, FeelType> inputData() {
        var inputData = new LinkedHashMap<String, FeelType>();
        for (int i = 0; i < inputNames.length; i++) {
            inputData.put(inputNames[i], inputTypes[i]);
        }
        return Collections.unmodifiableMap(inputData);
    }

    /**
     * The type of a decision's value: a decision table's as the class description gives it, a literal expression's as
     * its decision declares it, or as its expression computes it.
     *
     * @throws NoSuchElementException if the graph has no decision of that name
     */
    public FeelType type(String decision) {
        index(decision);
        return types.get(decision);
    }

    /**
     * The input data a decision needs, directly or through the decisions it requires, in the model's order: those
     * {@link #evaluate(String, Map)} reads.
     *
     * @throws NoSuchElementException        if the graph has no decision of that name
     * @throws InvalidDecisionTableException if the decision cannot be evaluated
     */
    public List<String> requiredInputData(String decision) {
        return IntStream.of(plan(decision).inputs()).mapToObj(slot -> inputNames[slot]).toList();
    }

    /** Whether a decision can be evaluated: no issue of the graph's report concerns it or a decision it requires. */
    public boolean canEvaluate(String decision) {
        var d = decisionIndexes.get(decision);
        return d != null && blocked[d] == null;
    }

    /**
     * The graph's validation report: missing requirements, cycles and type mismatches, all ERRORs, each naming its
     * decision. Each decision table's own analysis is in {@link #validationReport(String)}.
     */
    public ValidationReport validationReport() {
        return report;
    }

    /**
     * The validation report of a decision's table, as {@link PraeceptaEngine#validationReport()} gives it; empty for a
     * literal expression.
     *
     * @throws NoSuchElementException if the graph has no decision of that name
     */
    public ValidationReport validationReport(String decision) {
        index(decision);
        return tableReports.get(decision);
    }

    // ---- builder -------------------------------------------------------------------------------

    /**
     * Assembles a {@link DecisionGraph}: input data, and decisions whose information requirements are given, as a
     * {@link DecisionModel} gives them, or worked out from what their logic reads ({@link #decision(DecisionTable)},
     * {@link #literalDecision(String, String)}): each name a decision reads requires the decision or input data of that
     * name, or of its longest part before a dot ({@code Pricing.discount} requires {@code Pricing}); a name that is
     * neither requires an input data of that name that the graph does not have, a missing requirement.
     */
    public static final class Builder {

        private String name = "decisions";
        private final List<InputData> inputData = new ArrayList<>();
        /** The decisions, in order; those whose requirements are to be worked out have none yet. */
        private final List<Decision> decisions = new ArrayList<>();
        private final Set<String> inferred = new LinkedHashSet<>();
        private Severity failOn;
        private boolean log = true;

        private Builder() {
        }

        /** The model's name, which messages name; {@code decisions} unless set. */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Adds every input data and decision of a model, with the requirements it gives, and takes its name. */
        public Builder model(DecisionModel model) {
            name(model.name());
            inputData.addAll(model.inputData());
            decisions.addAll(model.decisions());
            return this;
        }

        /** Adds an input data of a declared type ({@link FeelType#ANY} for none). */
        public Builder inputData(String name, FeelType type) {
            inputData.add(new InputData(name, type));
            return this;
        }

        /** Adds an input data of a simple type. */
        public Builder inputData(String name, ColumnType type) {
            return inputData(name, FeelType.of(type));
        }

        /** Adds a decision with the information requirements it gives. */
        public Builder decision(Decision decision) {
            decisions.add(Objects.requireNonNull(decision, "decision"));
            return this;
        }

        /**
         * Adds a decision whose logic is a decision table, named after the table, requiring what its columns and the
         * inputs its cells compare with read ({@code Pricing.discount} reads {@code Pricing}).
         */
        public Builder decision(DecisionTable table) {
            return inferred(new Decision(table.name(), List.of(), List.of(), new DecisionLogic.Table(table)));
        }

        /**
         * Adds a decision whose logic is a literal expression, requiring what its names read, of no declared type.
         *
         * @throws IllegalArgumentException if the expression is not in Praecepta's expression subset
         */
        public Builder literalDecision(String name, String expression) {
            return literalDecision(name, FeelType.ANY, expression);
        }

        /**
         * Adds a decision whose logic is a literal expression, requiring what its names read, of a declared type.
         *
         * @throws IllegalArgumentException if the expression is not in Praecepta's expression subset
         */
        public Builder literalDecision(String name, FeelType type, String expression) {
            return inferred(new Decision(name, List.of(), List.of(), new DecisionLogic.LiteralExpression(expression, type)));
        }

        private Builder inferred(Decision decision) {
            decisions.add(decision);
            inferred.add(decision.name());
            return this;
        }

        /**
         * Fails {@link #build()} if the graph's validation report, or that of one of its decision tables, has issues this
         * severe.
         */
        public Builder failOn(Severity severity) {
            this.failOn = Objects.requireNonNull(severity, "severity");
            return this;
        }

        /**
         * Whether {@link #build()} logs the graph's validation report, and each table its own, as {@link PraeceptaEngine}
         * does ({@link PraeceptaEngine.Builder#logValidationReport}); true by default.
         */
        public Builder logValidationReport(boolean log) {
            this.log = log;
            return this;
        }

        /**
         * Works out the requirements left to work out, checks the model and builds each decision's logic.
         *
         * @throws IllegalArgumentException      if two input data or decisions have the same name
         * @throws InvalidDecisionTableException if a decision table cannot be built into an engine, or the validation
         *                                       report has issues at the {@link #failOn} severity
         */
        public DecisionGraph build() {
            var inputNames = inputData.stream().map(InputData::name).collect(Collectors.toSet());
            var decisionNames = decisions.stream().map(Decision::name).collect(Collectors.toSet());
            var resolved = decisions.stream()
                    .map(decision -> inferred.contains(decision.name()) ? withRequirements(decision, inputNames, decisionNames) : decision)
                    .toList();
            return new DecisionGraph(new DecisionModel(name, inputData, resolved), failOn, log);
        }

        /** The decision, requiring what its logic reads. */
        private static Decision withRequirements(Decision decision, Set<String> inputNames, Set<String> decisionNames) {
            var requiredDecisions = new LinkedHashSet<String>();
            var requiredInputs = new LinkedHashSet<String>();
            for (var read : GraphAnalysis.namesRead(decision)) {
                var root = root(read, inputNames, decisionNames);
                if (decisionNames.contains(root)) {
                    requiredDecisions.add(root);
                } else {
                    requiredInputs.add(root);
                }
            }
            return new Decision(decision.name(), List.copyOf(requiredDecisions), List.copyOf(requiredInputs), decision.logic());
        }

        /** The element a name reads: the one so named, or named by its longest part before a dot; the name itself if none. */
        private static String root(String name, Set<String> inputNames, Set<String> decisionNames) {
            for (int end = name.length(); end > 0; end = name.lastIndexOf('.', end - 1)) {
                var prefix = name.substring(0, end);
                if (inputNames.contains(prefix) || decisionNames.contains(prefix)) {
                    return prefix;
                }
            }
            return name;
        }
    }
}
