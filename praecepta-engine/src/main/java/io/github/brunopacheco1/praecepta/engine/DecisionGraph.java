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
import java.util.Arrays;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelFunction;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision requirements graph, evaluated: the decisions of a decision model (DMN 1.5, chapter 6), each a
 * {@linkplain DecisionLogic.Table decision table}, a {@linkplain DecisionLogic.LiteralExpression literal expression} or
 * a boxed expression (a context, an invocation, a relation...; {@link DecisionLogic}), evaluated in dependency order
 * from the model's input data, each decision's value given to the decisions that require it under the decision's name,
 * and its business knowledge models, functions that the decisions requiring them invoke by name.
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
 * declares no default output (DMN 1.5, 10.3.2.10). A literal expression gives the value of its expression, and a boxed
 * expression that of the FEEL it boxes ({@link FeelExpression#compose}), its parts bound to the types the model declares
 * for them. Each is bound to the type its decision's variable declares, as DMN 1.5 binds it (10.3.2.9.4): a single value to a list as a
 * list of one, a list of one to a single value as its element, a date to a date and time at UTC midnight, and a value
 * that does not conform to the declared type as null. Values are
 * FEEL's, as {@link FeelType} lists them: {@code String}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate},
 * times, date-times, durations, {@code List} and {@code Map}, all immutable. Input data are given as such values, or
 * in any form a table's typed input bindings take ({@code Integer}, {@code Period}, {@code OffsetDateTime}...), or, for
 * a simple type, as text ({@code "42"}, {@code "2025-01-31"}); an input data the map does not hold is missing (null). A
 * value of an input data whose type the model does not declare is taken as it is (its lists and maps copied), and a
 * decision table column that reads it, or a path into it, takes it as an input data of the column's type would.
 *
 * <p><b>Building.</b> {@link #read} reads a DMN file ({@link DmnReader#readModel}); {@link #builder()} assembles a graph
 * from tables and literal expressions in code. Building checks the model, as DMN 1.5 requires it to be well formed:
 * a decision that requires a decision or input data the model does not have, or whose logic reads a name none of its
 * information requirements provides ({@link ValidationReport.Kind#MISSING_REQUIREMENT}), decisions that require one
 * another in a cycle ({@link ValidationReport.Kind#DECISION_CYCLE}), and a value that cannot fit where it goes, such as
 * a string into a number column ({@link ValidationReport.Kind#TYPE_MISMATCH}), and a business knowledge model that reads
 * a name that is neither its parameter nor one it requires, are ERRORs of
 * {@link #validationReport()}, and so is logic Praecepta does not evaluate
 * ({@link ValidationReport.Kind#UNSUPPORTED_FEATURE}). Such a decision, and every decision that requires it, cannot be
 * evaluated; the others can. A decision whose value DMN 1.5 defines as null whatever its inputs (operands FEEL's
 * arithmetic does not combine, a value that does not conform to the declared type) is a WARNING
 * ({@link ValidationReport.Kind#ALWAYS_NULL}), and is evaluated. Each decision table keeps its own analysis
 * ({@link #validationReport(String)}), exactly as a {@link PraeceptaEngine} built from it alone, with map-based inputs,
 * reports it; so does each decision table within a boxed expression, a business knowledge model's body or a context
 * entry, under its decision's or business knowledge model's name.
 *
 * <p><b>Evaluating.</b> {@link #evaluate(String, Map)} evaluates a decision and the decisions it requires, directly or
 * indirectly, each once, in dependency order, and nothing else; every table, expression and name was parsed and
 * resolved when the graph was built, and only a computed output entry's value is converted from its text when it is
 * evaluated. A literal expression is FEEL (DMN 1.5, chapter 10; {@link io.github.brunopacheco1.praecepta.engine.feel.FeelExpression}):
 * where FEEL defines no value it gives null, and {@link #evaluateWithErrors} gives the errors FEEL reported with the
 * value. A business knowledge model is a function made once, when the graph is built, which may invoke those it
 * requires, and itself. A decision service is a function made once too, of its input data and input
 * decisions, whose invocation evaluates its output decisions from its arguments ({@link #evaluateService} invokes one
 * from outside). A graph is immutable and thread-safe.
 *
 * <p>A model that imports others is read with them ({@link DmnReader#readModel(InputStream, DmnReader.ImportResolver)})
 * and evaluated as one graph; {@link #read} reads a model alone, and rejects one that imports another. Praecepta does
 * not evaluate external functions (Java or PMML), by design, as it runs no code from outside the model: a model that
 * defines one is rejected when it is read.
 */
public final class DecisionGraph {

    private static final System.Logger LOG = System.getLogger(DecisionGraph.class.getName());

    /** The model as one with the models it imports, under the names of the graph ({@link ImportedModels}). */
    private final DecisionModel model;
    /** The model as it was given, with its imports. */
    private final DecisionModel original;
    /** By imported model's namespace, the prefix of its elements' names in the graph. */
    private final Map<String, String> importedPrefixes;
    /** The number of slots: one per input data, then one per decision. */
    private final int slotCount;
    /**
     * The value of each business knowledge model, a function made once when the graph is built, which the last slots of
     * every evaluation hold; null for one that cannot be evaluated.
     */
    private final Object[] knowledge;
    /** Why each business knowledge model, then each decision service, cannot be evaluated; null where it can. */
    private final String[] knowledgeBlocked;
    /** The decision services, by index in the model. */
    private final Service[] services;
    private final Map<String, Integer> serviceIndexes = new HashMap<>();
    /** Each input data's name and type (declared, or worked out from the tables that read it), by slot. */
    private final String[] inputNames;
    private final FeelType[] inputTypes;
    /** Each decision's logic, ready to evaluate, by index in the model; null if it cannot be evaluated. */
    private final GraphNodes.Node[] nodes;
    /** The type of each input data and decision, by name. */
    private final Map<String, FeelType> types;
    /** The type of each input data, by name, in the model's order. */
    private final Map<String, FeelType> inputData;
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
    /** The validation report of the decision tables within each business knowledge model's logic, by its name. */
    private final Map<String, ValidationReport> knowledgeReports;

    /**
     * How to evaluate a decision.
     *
     * @param decision  its index in the model
     * @param inputs    the slots of the input data it requires, directly or indirectly, in the model's order
     * @param decisions the indexes of the decisions to evaluate, itself last, each after those it requires
     */
    private record Plan(int decision, int[] inputs, int[] decisions) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Plan(int otherDecision, int[] otherInputs, int[] otherDecisions)
                    && decision == otherDecision && Arrays.equals(inputs, otherInputs) && Arrays.equals(decisions, otherDecisions);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * decision + Arrays.hashCode(inputs)) + Arrays.hashCode(decisions);
        }

        @Override
        public String toString() {
            return "Plan[" + decision + ", " + Arrays.toString(inputs) + ", " + Arrays.toString(decisions) + "]";
        }
    }

    private DecisionGraph(DecisionModel original, Severity failOn, boolean log) {
        this.original = original;
        var flat = ImportedModels.flatten(original);
        var model = flat.model();
        this.model = model;
        this.importedPrefixes = flat.byModel();
        var analysis = new GraphAnalysis(model, log, flat.aliases());
        int inputCount = analysis.inputCount;
        int decisionCount = model.decisions().size();
        slotCount = inputCount + decisionCount + model.knowledgeModels().size() + model.decisionServices().size();
        knowledge = knowledgeFunctions(analysis);
        knowledgeBlocked = analysis.knowledgeBlocked.clone();
        inputNames = new String[inputCount];
        inputTypes = new FeelType[inputCount];
        var allTypes = new LinkedHashMap<String, FeelType>();
        for (int i = 0; i < inputCount; i++) {
            inputNames[i] = model.inputData().get(i).name();
            inputTypes[i] = analysis.types[i];
            allTypes.put(inputNames[i], inputTypes[i]);
        }
        inputData = Collections.unmodifiableMap(new LinkedHashMap<>(allTypes));
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
            reports.put(decision.name(), merged(nodes[d].report(), analysis.embeddedTables.get(d)));
            requiredDecisions[d] = analysis.requiredDecisions(d);
            requiredInputs[d] = decision.requiredInputs().stream().map(analysis.slots::get)
                    .filter(slot -> slot != null && slot < inputCount).mapToInt(Integer::intValue).toArray();
        }
        types = Collections.unmodifiableMap(allTypes);
        tableReports = Collections.unmodifiableMap(reports);
        var knowledgeReports = new LinkedHashMap<String, ValidationReport>();
        for (int k = 0; k < model.knowledgeModels().size(); k++) {
            knowledgeReports.put(model.knowledgeModels().get(k).name(), merged(new ValidationReport(List.of()), analysis.knowledgeTables.get(k)));
        }
        this.knowledgeReports = Collections.unmodifiableMap(knowledgeReports);
        rank = new int[decisionCount];
        for (int i = 0; i < analysis.order.length; i++) {
            rank[analysis.order[i]] = i;
        }
        services = new Service[model.decisionServices().size()];
        for (int k = 0; k < services.length; k++) {
            services[k] = service(analysis, k);
            serviceIndexes.put(model.decisionServices().get(k).name(), k);
            if (analysis.knowledgeBlocked[model.knowledgeModels().size() + k] == null) {
                knowledge[model.knowledgeModels().size() + k] = services[k].function();
            }
        }
        report = new ValidationReport(analysis.issues());
        if (log && !report.isEmpty()) {
            LOG.log(Level.WARNING, "Decision model ''{0}'' has {1} validation issue(s):\n{2}", model.name(), report.issues().size(), report);
        }
        enforce(failOn);
    }

    /**
     * The functions of the business knowledge models (DMN 1.5, 6.3.10), each its function definition evaluated once,
     * whose body reads the others it invokes, and itself, when it is invoked: a closure over them all.
     */
    private static Object[] knowledgeFunctions(GraphAnalysis analysis) {
        int base = analysis.inputCount + analysis.decisionCount;
        var functions = new Object[analysis.knowledgeBlocked.length];
        for (int k = 0; k < analysis.knowledgeExpressions.length; k++) {
            var expression = analysis.knowledgeExpressions[k];
            if (expression == null || analysis.knowledgeBlocked[k] != null) {
                continue;
            }
            var reads = analysis.knowledgeReads.get(k);
            functions[k] = expression.evaluate(name -> {
                var slot = reads.get(name);
                return slot == null ? null : functions[slot - base];
            });
        }
        return functions;
    }

    /**
     * A decision service, ready to invoke: its parameters' slots and names, the decisions it evaluates in dependency order,
     * and its output decisions.
     */
    private record Service(String name, int[] parameters, List<DecisionLogic.Parameter> declared, int[] decisions, int[] outputs,
            FeelType result, String blocked, Function<Service, Object> functionOf) {

        Object function() {
            return functionOf.apply(this);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Service service && name.equals(service.name) && Arrays.equals(parameters, service.parameters)
                    && declared.equals(service.declared) && Arrays.equals(decisions, service.decisions)
                    && Arrays.equals(outputs, service.outputs) && Objects.equals(result, service.result)
                    && Objects.equals(blocked, service.blocked) && functionOf.equals(service.functionOf);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, Arrays.hashCode(parameters), declared, Arrays.hashCode(decisions), Arrays.hashCode(outputs), result,
                    blocked);
        }

        @Override
        public String toString() {
            return "Service[%s, parameters=%s, decisions=%s, outputs=%s, blocked=%s]".formatted(name, declared,
                    Arrays.toString(decisions), Arrays.toString(outputs), blocked);
        }
    }

    /** A decision service of the model, as its analysis works it out. */
    private Service service(GraphAnalysis analysis, int k) {
        var service = model.decisionServices().get(k);
        var parameterSlots = analysis.serviceParameters.get(k);
        var names = service.parameters();
        var declared = new ArrayList<DecisionLogic.Parameter>();
        for (int i = 0; i < names.size(); i++) {
            int slot = parameterSlots[i];
            declared.add(new DecisionLogic.Parameter(names.get(i), slot < 0 || analysis.types[slot] == null ? FeelType.ANY : analysis.types[slot]));
        }
        var decisions = IntStream.of(analysis.serviceDecisions.get(k)).boxed().sorted((a, b) -> Integer.compare(rank[a], rank[b]))
                .mapToInt(Integer::intValue).toArray();
        var type = analysis.types[analysis.inputCount + analysis.decisionCount + model.knowledgeModels().size() + k];
        var result = type instanceof FeelType.FunctionOf(var parameters, var output) ? output : FeelType.ANY;
        var declaredResult = service.type() instanceof FeelType.FunctionOf(var parameters, var output) ? output : FeelType.ANY;
        var blockedBy = analysis.knowledgeBlocked[model.knowledgeModels().size() + k];
        return new Service(service.name(), parameterSlots, List.copyOf(declared), decisions, analysis.serviceOutputs.get(k), declaredResult,
                blockedBy, self -> FeelExpression.function(self.declared(), new FeelExpression.Embedded() {
                    @Override
                    public List<String> names() {
                        return List.of();
                    }

                    @Override
                    public FeelType type() {
                        return result;
                    }

                    @Override
                    public Object evaluate(Function<String, Object> values, Consumer<String> errors) {
                        var outputs = run(self, values, errors == null ? null : (decision, message) -> errors.accept(message));
                        return result(self, outputs);
                    }
                }, self.result()));
    }

    /**
     * Evaluates a decision service's decisions from its arguments, which take the slots of its input data and input
     * decisions, each decision once, in dependency order; the model's own values are untouched.
     *
     * @return the slots, its output decisions' values in theirs
     */
    private Object[] run(Service service, Function<String, Object> arguments, BiConsumer<String, String> errors) {
        var values = slots();
        for (int i = 0; i < service.parameters().length; i++) {
            int slot = service.parameters()[i];
            if (slot >= 0) {
                values[slot] = arguments.apply(service.declared().get(i).name());
            }
        }
        for (int d : service.decisions()) {
            var name = model.decisions().get(d).name();
            values[inputNames.length + d] = nodes[d].evaluate(values, errors == null ? null : message -> errors.accept(name, message));
        }
        return values;
    }

    /** A decision service's value: its one output decision's, or a context of its output decisions' by name. */
    private Object result(Service service, Object[] values) {
        Object value;
        if (service.outputs().length == 1) {
            value = values[inputNames.length + service.outputs()[0]];
        } else {
            var context = new LinkedHashMap<String, Object>();
            for (int d : service.outputs()) {
                context.put(model.decisions().get(d).name(), values[inputNames.length + d]);
            }
            value = Collections.unmodifiableMap(context);
        }
        return value;
    }

    /** The slots of an evaluation: empty, but for the business knowledge models' functions. */
    private Object[] slots() {
        var values = new Object[slotCount];
        System.arraycopy(knowledge, 0, values, slotCount - knowledge.length, knowledge.length);
        return values;
    }

    private static GraphNodes.Node node(GraphAnalysis analysis, int d, boolean log) {
        var decision = analysis.model.decisions().get(d);
        var reads = analysis.reads.get(d);
        return switch (decision.logic()) {
            case DecisionLogic.Table(var table, var type) -> new GraphNodes.TableNode(table, analysis, d, type, log);
            default -> new GraphNodes.LiteralNode(analysis.expressions[d], reads, analysis.bindings[d], decision.logic().type());
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
        knowledgeReports.forEach((knowledgeModel, tableReport) -> tableReport.issues().stream()
                .filter(issue -> issue.severity().compareTo(failOn) >= 0)
                .forEach(issue -> failing.add("business knowledge model '%s': %s".formatted(knowledgeModel, issue))));
        if (!failing.isEmpty()) {
            throw new InvalidDecisionTableException("Decision model '%s' has %d validation issue(s) at %s or above:%n%s"
                    .formatted(model.name(), failing.size(), failOn, String.join("\n", failing)));
        }
    }

    /**
     * Reads a decision model from DMN ({@link DmnReader#readModel}) and builds its graph, which logs its validation
     * report, if it has issues, as one warning (logger {@code io.github.brunopacheco1.praecepta.engine.DecisionGraph}),
     * and each decision table its own, as {@link PraeceptaEngine} does.
     *
     * @throws InvalidDecisionTableException if the model cannot be read, uses what Praecepta does not evaluate or read, or
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
     *                                       decision it requires, has an ERROR
     *                                       ({@link ValidationReport.Kind#MISSING_REQUIREMENT},
     *                                       {@link ValidationReport.Kind#DECISION_CYCLE},
     *                                       {@link ValidationReport.Kind#TYPE_MISMATCH} or
     *                                       {@link ValidationReport.Kind#UNSUPPORTED_FEATURE}), which the message names
     * @throws InvalidInputException         if an input data's value is not one of its type, or a decision table
     *                                       rejects a value (one outside its column's input values)
     * @throws HitPolicyViolationException   if a decision table's hit policy is violated (UNIQUE, ANY)
     */
    public Object evaluate(String decision, Map<String, ?> inputData) {
        var plan = plan(decision);
        var values = slots();
        assign(plan.inputs(), values, inputData);
        for (int d : plan.decisions()) {
            values[inputNames.length + d] = nodes[d].evaluate(values, null);
        }
        return values[inputNames.length + plan.decision()];
    }

    /**
     * A decision's value, and the errors FEEL reported while evaluating it and the decisions it requires (DMN 1.5,
     * 10.3.2.15): where FEEL defines no value, as for operands it does not combine ({@code "a" + 1}), a division by zero,
     * an index beyond a list or an invalid date literal, the value is null, and an error says why.
     *
     * @param value  the decision's value, as {@link #evaluate(String, Map)} gives it
     * @param errors the errors' messages, by the name of the decision whose logic reported them, in the order the
     *               decisions were evaluated; a decision that reported none is not there
     */
    public record Outcome(Object value, Map<String, List<String>> errors) {
        public Outcome {
            var copy = new LinkedHashMap<String, List<String>>();
            errors.forEach((name, messages) -> copy.put(name, List.copyOf(messages)));
            errors = Collections.unmodifiableMap(copy);
        }

        /** The errors a decision's own logic reported; empty if it reported none. */
        public List<String> errorsOf(String decision) {
            return errors.getOrDefault(decision, List.of());
        }
    }

    /**
     * Evaluates a decision as {@link #evaluate(String, Map)} does, and gives the errors FEEL reported on the way with its
     * value. Evaluation is the same, and as fast but for the errors' messages.
     *
     * @throws NoSuchElementException        if the graph has no decision of that name
     * @throws InvalidDecisionTableException if the decision cannot be evaluated (see {@link #evaluate(String, Map)})
     * @throws InvalidInputException         if an input data's value is not one of its type, or a decision table
     *                                       rejects a value
     * @throws HitPolicyViolationException   if a decision table's hit policy is violated
     */
    public Outcome evaluateWithErrors(String decision, Map<String, ?> inputData) {
        var plan = plan(decision);
        var values = slots();
        assign(plan.inputs(), values, inputData);
        var errors = new LinkedHashMap<String, List<String>>();
        for (int d : plan.decisions()) {
            var messages = new ArrayList<String>();
            values[inputNames.length + d] = nodes[d].evaluate(values, messages::add);
            if (!messages.isEmpty()) {
                errors.put(model.decisions().get(d).name(), messages);
            }
        }
        return new Outcome(values[inputNames.length + plan.decision()], errors);
    }

    /** The business knowledge models' names, in the model's order. */
    public List<String> knowledgeModels() {
        return model.knowledgeModels().stream().map(DecisionModel.BusinessKnowledgeModel::name).toList();
    }

    /**
     * Invokes a business knowledge model (DMN 1.5, 6.3.10) from outside the model, as a decision that requires it
     * invokes it by parameter name: a parameter the map does not hold is null. The values are FEEL's ({@link FeelType}),
     * a {@link Number} taken as the {@code BigDecimal} of its value, each bound to its parameter's type as an invocation
     * binds it (10.3.2.9.4), and the value to the type its function gives.
     *
     * @return its value, and the errors FEEL reported, under its name
     * @throws NoSuchElementException        if the graph has no business knowledge model of that name
     * @throws InvalidDecisionTableException if it cannot be evaluated: its logic is not FEEL Praecepta evaluates, or it
     *                                       invokes what the model does not have
     */
    public Outcome invokeKnowledgeModel(String knowledgeModel, Map<String, ?> arguments) {
        int k = knowledgeModels().indexOf(Objects.requireNonNull(knowledgeModel, "knowledgeModel"));
        if (k < 0) {
            throw new NoSuchElementException("Decision model '%s' has no business knowledge model named '%s'; it has %s".formatted(model.name(),
                    knowledgeModel, knowledgeModels()));
        }
        if (!(knowledge[k] instanceof FeelFunction function)) {
            throw new InvalidDecisionTableException("Business knowledge model '%s' cannot be evaluated: %s".formatted(knowledgeModel,
                    Objects.requireNonNullElse(knowledgeBlocked[k], "its logic is not a function")));
        }
        var values = new ArrayList<>();
        function.parameters().forEach(parameter -> values.add(arguments.get(parameter)));
        var messages = new ArrayList<String>();
        var value = function.invoke(values, messages::add);
        return new Outcome(value, messages.isEmpty() ? Map.of() : Map.of(knowledgeModel, messages));
    }

    /** The decision services' names, in the model's order. */
    public List<String> decisionServices() {
        return model.decisionServices().stream().map(DecisionModel.DecisionService::name).toList();
    }

    /**
     * Invokes a decision service (DMN 1.5, 6.3.9) from outside the model, as a decision that requires it invokes it:
     * evaluates its output decisions, and the decisions they require, from the values of its parameters, its input data
     * and input decisions, by name; a parameter the map does not hold is null. The values are FEEL's ({@link FeelType}),
     * a {@link Number} taken as the {@code BigDecimal} of its value, each bound to its parameter's type as an invocation
     * binds it (10.3.2.9.4): a single value to a list of one, a list of one to its element; one that does not conform
     * makes every output null, with an error. The input decisions are not evaluated, and the model's other decisions
     * take no part.
     *
     * @return each output decision's value, by name, in the service's order, and the errors FEEL reported, under the
     *         service's name
     * @throws NoSuchElementException        if the graph has no decision service of that name
     * @throws InvalidDecisionTableException if the service cannot be evaluated: it evaluates a decision that cannot be,
     *                                       or refers to what the model does not have
     */
    public Outcome evaluateService(String service, Map<String, ?> arguments) {
        var index = serviceIndexes.get(Objects.requireNonNull(service, "service"));
        if (index == null) {
            throw new NoSuchElementException("Decision model '%s' has no decision service named '%s'; it has %s".formatted(model.name(), service,
                    decisionServices()));
        }
        var target = services[index];
        if (target.blocked() != null) {
            throw new InvalidDecisionTableException("Decision service '%s' cannot be evaluated: %s".formatted(service, target.blocked()));
        }
        var values = new ArrayList<>();
        target.declared().forEach(parameter -> values.add(arguments.get(parameter.name())));
        var messages = new ArrayList<String>();
        var value = ((FeelFunction) knowledge[model.knowledgeModels().size() + index]).invoke(values, messages::add);
        var outputs = new LinkedHashMap<String, Object>();
        for (int d : target.outputs()) {
            var name = model.decisions().get(d).name();
            outputs.put(name, target.outputs().length == 1 ? value : value instanceof Map<?, ?> context ? context.get(name) : null);
        }
        return new Outcome(Collections.unmodifiableMap(outputs), messages.isEmpty() ? Map.of() : Map.of(service, messages));
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
        var values = slots();
        assign(inputs.stream().mapToInt(Integer::intValue).toArray(), values, inputData);
        for (int d : needed) {
            values[inputNames.length + d] = nodes[d].evaluate(values, null);
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
        return original;
    }

    /**
     * The name in the graph of an element of the model, or of a model it imports (DMN 1.5, 6.3.3): its own name for an
     * element of the model, else its name qualified by the path of imports that first reaches its model
     * ({@code Model B.modelA.Person name}), under which {@link #evaluate(String, Map)} takes an input data and gives a
     * decision.
     *
     * @param namespace the namespace of the element's model
     * @param name      the element's name in its model
     */
    public String nameOf(String namespace, String name) {
        var prefix = importedPrefixes.get(namespace);
        return prefix == null ? name : prefix + name;
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
        return inputData;
    }

    /**
     * The type of a decision's value: as its decision declares it, or, where it declares none (or one its logic's values
     * conform to, which is then more precise), a decision table's as the class description gives it and a literal
     * expression's as its expression computes it; {@link FeelType#ANY} where that is known only when it is evaluated.
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
     * The graph's validation report: missing requirements, cycles, type mismatches and what Praecepta does not evaluate,
     * all ERRORs, and decisions whose value is always null, WARNINGs, each naming its decision. Each decision table's
     * own analysis is in {@link #validationReport(String)}.
     */
    public ValidationReport validationReport() {
        return report;
    }

    /**
     * The validation report of a decision's table, as {@link PraeceptaEngine#validationReport()} gives it, or of the
     * decision tables within its boxed expression, one after the other; or of those within a business knowledge model's
     * logic, given its name. Empty for logic without a decision table.
     *
     * @throws NoSuchElementException if the graph has no decision or business knowledge model of that name
     */
    public ValidationReport validationReport(String decision) {
        var knowledgeReport = knowledgeReports.get(decision);
        if (knowledgeReport != null && !decisionIndexes.containsKey(decision)) {
            return knowledgeReport;
        }
        index(decision);
        return tableReports.get(decision);
    }

    /** A report of the issues of a decision's own table, if any, then of each table within its boxed expression. */
    private static ValidationReport merged(ValidationReport own, List<GraphNodes.EmbeddedTable> tables) {
        if (tables.isEmpty()) {
            return own;
        }
        var issues = new ArrayList<>(own.issues());
        tables.forEach(table -> issues.addAll(table.report().issues()));
        return new ValidationReport(issues);
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
        private final List<DecisionModel.BusinessKnowledgeModel> knowledgeModels = new ArrayList<>();
        private final List<DecisionModel.DecisionService> decisionServices = new ArrayList<>();
        private final List<DecisionModel.Import> imports = new ArrayList<>();
        private String namespace = "";
        private final Map<String, FeelType> types = new LinkedHashMap<>();
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
            knowledgeModels.addAll(model.knowledgeModels());
            decisionServices.addAll(model.decisionServices());
            imports.addAll(model.imports());
            namespace = model.namespace();
            types.putAll(model.types());
            return this;
        }

        /**
         * Adds a decision service (DMN 1.5, 6.3.9), a function of its input data and input decisions that decisions and
         * business knowledge models requiring it invoke by its name, and that {@link DecisionGraph#evaluateService}
         * invokes from outside.
         */
        public Builder decisionService(DecisionModel.DecisionService service) {
            decisionServices.add(Objects.requireNonNull(service, "service"));
            return this;
        }

        /**
         * Adds a business knowledge model (DMN 1.5, 6.3.10), a function that decisions requiring it, and other business
         * knowledge models, invoke by its name.
         */
        public Builder knowledgeModel(DecisionModel.BusinessKnowledgeModel knowledgeModel) {
            knowledgeModels.add(Objects.requireNonNull(knowledgeModel, "knowledgeModel"));
            return this;
        }

        /**
         * Adds a type the model defines (an item definition), which a literal expression's {@code instance of} and function
         * parameters may name.
         */
        public Builder type(String name, FeelType type) {
            types.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
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
         * Adds a decision whose logic is a decision table, named after the table, of no declared type, requiring what its
         * columns and the inputs its cells compare with read ({@code Pricing.discount} reads {@code Pricing}).
         */
        public Builder decision(DecisionTable table) {
            return decision(table, FeelType.ANY);
        }

        /**
         * Adds a decision whose logic is a decision table, named after the table, of a declared type, to which its value
         * is bound (see the class description), requiring what its columns and the inputs its cells compare with read.
         */
        public Builder decision(DecisionTable table, FeelType type) {
            return inferred(new Decision(table.name(), List.of(), List.of(), new DecisionLogic.Table(table, type)));
        }

        /**
         * Adds a decision whose logic is a literal expression, requiring what its names read, of no declared type.
         *
         * @throws IllegalArgumentException if the expression is not a FEEL expression
         */
        public Builder literalDecision(String name, String expression) {
            return literalDecision(name, FeelType.ANY, expression);
        }

        /**
         * Adds a decision whose logic is a literal expression, requiring what its names read, of a declared type.
         *
         * @throws IllegalArgumentException if the expression is not a FEEL expression
         */
        public Builder literalDecision(String name, FeelType type, String expression) {
            var literal = new DecisionLogic.LiteralExpression(expression, type);
            literal.expression(); // checked here, where the names it may read are not all known yet
            return inferred(new Decision(name, List.of(), List.of(), literal));
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
            var names = new LinkedHashSet<>(inputNames);
            names.addAll(decisionNames);
            var scope = FeelExpression.scope(names, types);
            var resolved = decisions.stream()
                    .map(decision -> inferred.contains(decision.name()) ? withRequirements(decision, names, decisionNames, scope, types) : decision)
                    .toList();
            return new DecisionGraph(new DecisionModel(name, inputData, resolved, knowledgeModels, decisionServices, types, namespace, imports),
                    failOn, log);
        }

        /**
         * The decision, requiring what its logic reads.
         *
         * @param names the names of the model's input data and decisions
         * @param types the types the model defines
         */
        private static Decision withRequirements(Decision decision, Set<String> names, Set<String> decisionNames,
                FeelExpression.Scope scope, Map<String, FeelType> types) {
            var requiredDecisions = new LinkedHashSet<String>();
            var requiredInputs = new LinkedHashSet<String>();
            for (var read : GraphAnalysis.namesRead(decision, names, scope, types)) {
                var element = DecisionModel.elementOf(read, names::contains);
                var root = element == null ? read : element;
                if (decisionNames.contains(root)) {
                    requiredDecisions.add(root);
                } else {
                    requiredInputs.add(root);
                }
            }
            return new Decision(decision.name(), List.copyOf(requiredDecisions), List.copyOf(requiredInputs), decision.logic());
        }
    }
}
