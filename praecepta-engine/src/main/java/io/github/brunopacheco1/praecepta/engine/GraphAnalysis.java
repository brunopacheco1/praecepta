/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * The static analysis of a decision model, done once when a {@link DecisionGraph} is built: each element gets a slot
 * (input data first, then decisions, in the model's order), each name a decision's logic reads is resolved to the slot
 * of one of its information requirements and a path into its value, the types of input data and decisions are worked
 * out, and what is wrong is reported (missing requirements, cycles, type mismatches), together with the decisions that
 * therefore cannot be evaluated. Every walk over the graph is a loop with a stack of its own, so a long chain of
 * decisions takes no more of the thread's stack than a short one.
 */
final class GraphAnalysis {

    /**
     * A name a decision's logic reads, resolved: the slot of the requirement that provides it, the path into that
     * requirement's value, and the type of the value there ({@link FeelType#ANY} when unknown); {@code error} says why
     * the path does not exist in the requirement's type, else it is null, and {@code unsupported} whether the path
     * exists in FEEL but a decision table's column does not read it yet (a property of a date, {@code Birth.year}).
     */
    record Resolution(int slot, String[] path, FeelType type, String error, boolean unsupported) {

        Resolution(int slot, String[] path, FeelType type) {
            this(slot, path, type, null, false);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resolution(int otherSlot, String[] otherPath, var otherType, var otherError, boolean otherUnsupported)
                    && slot == otherSlot && Arrays.equals(path, otherPath) && Objects.equals(type, otherType)
                    && Objects.equals(error, otherError) && unsupported == otherUnsupported;
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, Arrays.hashCode(path), type, error, unsupported);
        }

        @Override
        public String toString() {
            return "Resolution[" + slot + ", " + Arrays.toString(path) + ", " + type + ", " + error + ", " + unsupported + "]";
        }
    }

    /** How a decision table reads a name: as a column of {@code type}, or of lists of it, or as an input its cells compare with. */
    record ColumnRead(String name, ColumnType type, boolean list) {
    }

    /**
     * What a decision table reads: its columns that name what they read, and the inputs its simple cells compare with
     * ({@link ColumnRead}s, each read as a value of its column's type); its columns whose input expressions are
     * expressions ({@code Age * 12}), parsed; and the names its FEEL (cells beyond the simple ones, input expressions,
     * output entries) reads beside its columns, whose values it reads as they are; and that FEEL, parsed, which the
     * table's engine takes rather than parse it again.
     */
    record TableReads(List<ColumnRead> columns, Map<String, FeelExpression> computed, Set<String> expressions,
            TableExpressions.Parsed parsed) {

        static final TableReads NONE = new TableReads(List.of(), Map.of(), Set.of(), TableExpressions.Parsed.NONE);

        /** Every name read, columns first. */
        Set<String> names() {
            var names = new LinkedHashSet<String>();
            columns.forEach(read -> names.add(read.name()));
            names.addAll(expressions);
            return names;
        }
    }

    final DecisionModel model;
    /** Each element's slot, by name: the input data, then the decisions, then the business knowledge models. */
    final Map<String, Integer> slots = new HashMap<>();
    final int inputCount;
    final int decisionCount;
    /**
     * By business knowledge model (index in the model): its function definition, composed with the names of the business
     * knowledge models it requires in scope; null where it is not FEEL.
     */
    final FeelExpression[] knowledgeExpressions;
    /** By business knowledge model: the slot of each business knowledge model its logic reads, by name. */
    final List<Map<String, Integer>> knowledgeReads = new ArrayList<>();
    /**
     * By invocable, the business knowledge models then the decision services: why it cannot be evaluated, or null if it
     * can.
     */
    final String[] knowledgeBlocked;
    /** By decision service: the slots of its parameters, its input data then its input decisions. */
    final List<int[]> serviceParameters = new ArrayList<>();
    /** By decision service: the decisions it evaluates, by index, required ones first; its input decisions are not among them. */
    final List<int[]> serviceDecisions = new ArrayList<>();
    /** By decision service: its output decisions, by index. */
    final List<int[]> serviceOutputs = new ArrayList<>();
    /** By business knowledge model: the decision tables within its logic, each with its validation report. */
    final List<List<GraphNodes.EmbeddedTable>> knowledgeTables = new ArrayList<>();
    /** By decision: the decision tables within its boxed expression, each with its validation report. */
    final List<List<GraphNodes.EmbeddedTable>> embeddedTables = new ArrayList<>();
    /** Whether the engines of the tables within boxed expressions log their validation reports. */
    private final boolean log;
    /** Each slot's type: an input data's, declared or worked out from the tables that read it, or a decision's value's. */
    final FeelType[] types;
    /** By decision (index in the model): the names its logic reads, resolved; a name none of its requirements provides is left out. */
    final List<Map<String, Resolution>> reads = new ArrayList<>();
    /** By decision: the inputs its table's cells compare with whose values are lists, though the table reads single values. */
    final List<Set<String>> listedNamedInputs = new ArrayList<>();
    /**
     * By decision: its table's columns that read a property of a date, time or duration ({@code Birth.year}), which the
     * table computes as FEEL reads them.
     */
    final List<Map<String, PropertyColumn>> propertyColumns = new ArrayList<>();
    /** By decision: what its table reads (nothing for a literal expression), read once, as it parses every cell. */
    private final List<TableReads> tableReads = new ArrayList<>();
    /** By decision: the decisions it requires that the model has, as their indexes. */
    private final List<int[]> requiredDecisions = new ArrayList<>();
    /** By decision: why it cannot be evaluated, or null if it can. */
    final String[] blocked;
    /** By decision: how its logic's value is bound to it (DMN 1.5, 10.3.2.9.4). */
    final GraphValues.Binding[] bindings;
    /** By decision: its literal expression, parsed once with its requirements' names in scope; null for a decision table. */
    final FeelExpression[] expressions;
    /** The decisions that can be ordered, required ones first, as their indexes: those in no cycle and requiring none in one. */
    final int[] order;
    private final List<Issue> issues = new ArrayList<>();
    /** The reads, as {@code decision:name}, of an input data without a declared type that disagree with an earlier one, reported once. */
    private final Set<String> conflicting = new HashSet<>();

    GraphAnalysis(DecisionModel model) {
        this(model, false);
    }

    /** @param log whether the engines of the decision tables within boxed expressions log their validation reports */
    GraphAnalysis(DecisionModel model, boolean log) {
        this.model = model;
        this.log = log;
        var decisions = model.decisions();
        inputCount = model.inputData().size();
        decisionCount = decisions.size();
        var knowledgeModels = model.knowledgeModels();
        var services = model.decisionServices();
        types = new FeelType[inputCount + decisions.size() + knowledgeModels.size() + services.size()];
        knowledgeExpressions = new FeelExpression[knowledgeModels.size()];
        knowledgeBlocked = new String[knowledgeModels.size() + services.size()];
        blocked = new String[decisions.size()];
        bindings = new GraphValues.Binding[decisions.size()];
        Arrays.fill(bindings, GraphValues.Binding.AS_IS);
        expressions = new FeelExpression[decisions.size()];
        for (int i = 0; i < inputCount; i++) {
            var input = model.inputData().get(i);
            slots.put(input.name(), i);
            types[i] = input.type();
        }
        for (int d = 0; d < decisions.size(); d++) {
            slots.put(decisions.get(d).name(), inputCount + d);
        }
        for (int k = 0; k < knowledgeModels.size(); k++) {
            slots.put(knowledgeModels.get(k).name(), inputCount + decisionCount + k);
        }
        for (int k = 0; k < services.size(); k++) {
            slots.put(services.get(k).name(), inputCount + decisionCount + knowledgeModels.size() + k);
        }
        for (int k = 0; k < knowledgeModels.size(); k++) {
            resolveKnowledge(k);
        }
        for (int k = 0; k < services.size(); k++) {
            checkService(k);
        }
        blockKnowledge();
        for (int d = 0; d < decisions.size(); d++) {
            checkRequirements(d);
            resolveReads(d);
        }
        var cyclic = findCycles();
        order = topologicalOrder(cyclic);
        inferInputTypes();
        for (int k = 0; k < services.size(); k++) {
            // until the decisions are typed, what invokes a decision service reads it as its declared type, or any function
            types[inputCount + decisionCount + knowledgeModels.size() + k] = services.get(k).type();
        }
        typeKnowledge();
        typeDecisions();
        typeServices();
        checkTableReads();
        block(cyclic);
        blockThroughInvocables();
    }

    /** The issues found: of requirements and names, then cycles, then types, each in the model's order of decisions. */
    List<Issue> issues() {
        return List.copyOf(issues);
    }

    /** The indexes of the decisions a decision requires that the model has. */
    int[] requiredDecisions(int d) {
        return requiredDecisions.get(d).clone();
    }

    /** What the decision's table reads; nothing for a literal expression. */
    TableReads tableReads(int d) {
        return tableReads.get(d);
    }

    // ---- requirements ------------------------------------------------------------------------------

    private void checkRequirements(int d) {
        var decision = model.decisions().get(d);
        var required = new ArrayList<Integer>();
        for (var name : decision.requiredDecisions()) {
            var slot = slots.get(name);
            if (slot == null) {
                report(d, Kind.MISSING_REQUIREMENT,
                        "decision '%s' requires decision '%s', which is not in the model".formatted(decision.name(), name));
            } else if (!isDecision(slot)) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires decision '%s', which is %s, not a decision"
                        .formatted(decision.name(), name, kindOf(slot)));
            } else {
                required.add(slot - inputCount);
            }
        }
        for (var name : decision.requiredInputs()) {
            var slot = slots.get(name);
            if (slot == null) {
                report(d, Kind.MISSING_REQUIREMENT,
                        "decision '%s' requires input data '%s', which is not in the model".formatted(decision.name(), name));
            } else if (!isInputData(slot)) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires input data '%s', which is %s, not input data"
                        .formatted(decision.name(), name, kindOf(slot)));
            }
        }
        for (var name : decision.requiredKnowledge()) {
            var slot = slots.get(name);
            if (slot == null) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires business knowledge model '%s', which is not in the model"
                        .formatted(decision.name(), name));
            } else if (!isKnowledge(slot)) {
                report(d, Kind.MISSING_REQUIREMENT, ("decision '%s' requires business knowledge model '%s', which is %s, not a business "
                        + "knowledge model").formatted(decision.name(), name, kindOf(slot)));
            } else if (knowledgeBlocked[slot - inputCount - decisionCount] != null) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires %s '%s', which cannot be evaluated: %s"
                        .formatted(decision.name(), kindOf(slot), name, knowledgeBlocked[slot - inputCount - decisionCount]), false);
            }
        }
        requiredDecisions.add(required.stream().mapToInt(Integer::intValue).toArray());
    }

    /** What kind of element is at a slot, as messages name it. */
    private String kindOf(int slot) {
        if (isInputData(slot)) {
            return "input data";
        }
        if (isDecision(slot)) {
            return "a decision";
        }
        return slot < inputCount + decisionCount + model.knowledgeModels().size() ? "a business knowledge model" : "a decision service";
    }

    // ---- business knowledge models -----------------------------------------------------------------

    /**
     * Checks a business knowledge model's knowledge requirements, composes its logic with their names in scope (its
     * parameters are its own), and resolves each name it reads among them: a name none of them provides is a missing
     * requirement, as a decision's is (DMN 1.5, 6.3.10: it reads its parameters and the knowledge it requires only).
     */
    private void resolveKnowledge(int k) {
        var knowledgeModel = model.knowledgeModels().get(k);
        var name = knowledgeModel.name();
        var reads = new LinkedHashMap<String, Integer>();
        knowledgeReads.add(reads);
        knowledgeTables.add(new ArrayList<>());
        var valid = new HashSet<String>();
        for (var required : knowledgeModel.requiredKnowledge()) {
            var slot = slots.get(required);
            if (slot == null) {
                reportKnowledge(k, Kind.MISSING_REQUIREMENT, ("business knowledge model '%s' requires business knowledge model '%s', which is "
                        + "not in the model").formatted(name, required));
            } else if (!isKnowledge(slot)) {
                reportKnowledge(k, Kind.MISSING_REQUIREMENT, ("business knowledge model '%s' requires business knowledge model '%s', which is "
                        + "%s, not a business knowledge model").formatted(name, required, kindOf(slot)));
            } else {
                valid.add(required);
            }
        }
        // a business knowledge model may invoke itself, as a function a context defines may
        var scope = new LinkedHashSet<>(knowledgeModel.requiredKnowledge());
        scope.add(name);
        valid.add(name);
        try {
            var tables = knowledgeTables.get(k);
            knowledgeExpressions[k] = FeelExpression.compose(knowledgeModel.logic(), FeelExpression.scope(scope, model.types()),
                    site -> embed(site, tables));
        } catch (ExpressionSyntaxException e) {
            reportKnowledge(k, Kind.INVALID_EXPRESSION, "business knowledge model '%s': %s".formatted(name, e.getMessage()));
            return;
        }
        for (var what : knowledgeExpressions[k].unsupported()) {
            reportKnowledge(k, Kind.UNSUPPORTED_FEATURE, "business knowledge model '%s' uses %s, which Praecepta does not evaluate yet"
                    .formatted(name, what));
        }
        for (var read : knowledgeExpressions[k].names()) {
            var required = DecisionModel.elementOf(read, scope::contains);
            if (required == null) {
                reportKnowledge(k, Kind.MISSING_REQUIREMENT, ("business knowledge model '%s' reads '%s', which is neither one of its parameters "
                        + "nor a business knowledge model it requires (DMN 1.5, 6.3.10)").formatted(name, read));
            } else if (valid.contains(required)) {
                reads.put(read, slots.get(required));
            }
        }
    }

    /**
     * A decision table within a boxed expression, built into an engine of its own, which keeps its validation report.
     *
     * @throws InvalidDecisionTableException if it cannot be built, as a decision's table that cannot be built
     */
    private GraphNodes.EmbeddedTable embed(FeelExpression.TableSite site, List<GraphNodes.EmbeddedTable> tables) {
        var table = new GraphNodes.EmbeddedTable(site, log);
        tables.add(table);
        return table;
    }

    // ---- decision services --------------------------------------------------------------------------

    /**
     * Checks a decision service's decisions and input data, and works out what it evaluates: its output decisions and the
     * decisions they require, directly or not, but its input decisions, whose values are its arguments (DMN 1.5, 6.3.9),
     * whether the service lists them as encapsulated or not. One of those that requires input data the service does not
     * take could not be evaluated, and is reported.
     */
    private void checkService(int k) {
        var service = model.decisionServices().get(k);
        int invocable = model.knowledgeModels().size() + k;
        var where = "decision service '%s'".formatted(service.name());
        var parameters = new ArrayList<Integer>();
        for (var name : service.inputData()) {
            var slot = slots.get(name);
            if (!isInputData(slot)) {
                reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s takes input data '%s', which is %s".formatted(where, name,
                        slot == null ? "not in the model" : kindOf(slot) + ", not input data"));
            }
            parameters.add(slot == null ? -1 : slot);
        }
        var inputs = new HashSet<Integer>();
        for (var name : service.inputDecisions()) {
            var slot = slots.get(name);
            if (!isDecision(slot)) {
                reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s takes decision '%s', which is %s".formatted(where, name,
                        slot == null ? "not in the model" : kindOf(slot) + ", not a decision"));
            } else {
                inputs.add(slot - inputCount);
            }
            parameters.add(slot == null ? -1 : slot);
        }
        var outputs = new ArrayList<Integer>();
        var named = new ArrayList<>(service.outputDecisions());
        named.addAll(service.encapsulatedDecisions());
        for (int i = 0; i < named.size(); i++) {
            var slot = slots.get(named.get(i));
            if (!isDecision(slot)) {
                reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s evaluates decision '%s', which is %s".formatted(where, named.get(i),
                        slot == null ? "not in the model" : kindOf(slot) + ", not a decision"));
            } else if (i < service.outputDecisions().size()) {
                outputs.add(slot - inputCount);
            }
        }
        var evaluated = new LinkedHashSet<Integer>();
        var pending = new ArrayDeque<>(outputs);
        while (!pending.isEmpty()) {
            int d = pending.pop();
            if (inputs.contains(d) || !evaluated.add(d)) {
                continue;
            }
            var decision = model.decisions().get(d);
            for (var name : decision.requiredDecisions()) {
                var slot = slots.get(name);
                if (isDecision(slot)) {
                    pending.push(slot - inputCount);
                }
            }
            for (var name : decision.requiredInputs()) {
                if (!service.inputData().contains(name) && isInputData(slots.get(name))) {
                    reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, ("%s evaluates decision '%s', which requires input data '%s', "
                            + "which the service does not take (DMN 1.5, 6.3.9)").formatted(where, decision.name(), name));
                }
            }
        }
        serviceParameters.add(parameters.stream().mapToInt(Integer::intValue).toArray());
        serviceOutputs.add(outputs.stream().mapToInt(Integer::intValue).toArray());
        serviceDecisions.add(evaluated.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Types every decision service as a function of its parameters' types, giving its one output decision's type, or a
     * context of its output decisions' types; one whose variable declares a function type has that type.
     */
    private void typeServices() {
        for (int k = 0; k < model.decisionServices().size(); k++) {
            typeService(k);
        }
    }

    /**
     * Types a decision service, once its output decisions are typed; a decision that invokes it, typed before, reads it
     * as its declared type.
     */
    private void typeService(int k) {
        var service = model.decisionServices().get(k);
        int slot = inputCount + decisionCount + model.knowledgeModels().size() + k;
        if (service.type() instanceof FeelType.FunctionOf) {
            types[slot] = service.type();
            return;
        }
        var outputs = serviceOutputs.get(k);
        for (int d : outputs) {
            if (types[inputCount + d] == null) {
                return;
            }
        }
        var parameters = new ArrayList<FeelType>();
        for (int parameter : serviceParameters.get(k)) {
            parameters.add(parameter < 0 || types[parameter] == null ? FeelType.ANY : types[parameter]);
        }
        FeelType result;
        if (outputs.length == 1) {
            result = types[inputCount + outputs[0]];
        } else {
            var components = new LinkedHashMap<String, FeelType>();
            for (int d : outputs) {
                components.put(model.decisions().get(d).name(), types[inputCount + d]);
            }
            result = new FeelType.Context(components);
        }
        types[slot] = new FeelType.FunctionOf(parameters, result);
    }

    /**
     * Blocks, until nothing changes, a decision service that evaluates a decision that cannot be evaluated, and the
     * decisions and business knowledge models that require an invocable that cannot be.
     */
    private void blockThroughInvocables() {
        int knowledgeCount = model.knowledgeModels().size();
        int base = inputCount + decisionCount;
        for (boolean changed = true; changed;) {
            changed = false;
            for (int k = 0; k < model.decisionServices().size(); k++) {
                if (knowledgeBlocked[knowledgeCount + k] != null) {
                    continue;
                }
                for (int d : serviceDecisions.get(k)) {
                    if (blocked[d] != null) {
                        knowledgeBlocked[knowledgeCount + k] = "it evaluates decision '%s', which cannot be evaluated: %s"
                                .formatted(model.decisions().get(d).name(), blocked[d]);
                        changed = true;
                        break;
                    }
                }
            }
            for (int k = 0; k < knowledgeCount; k++) {
                changed |= blockOnInvocable(model.knowledgeModels().get(k).requiredKnowledge(), base, k, -1);
            }
            for (int d = 0; d < decisionCount; d++) {
                changed |= blockOnInvocable(model.decisions().get(d).requiredKnowledge(), base, -1, d);
                for (int r : requiredDecisions.get(d)) {
                    if (blocked[d] == null && blocked[r] != null) {
                        blocked[d] = "it requires decision '%s', which cannot be evaluated: %s".formatted(model.decisions().get(r).name(),
                                blocked[r]);
                        changed = true;
                    }
                }
            }
        }
    }

    /** Blocks business knowledge model {@code k}, or decision {@code d}, if one of the invocables it requires cannot be evaluated. */
    private boolean blockOnInvocable(List<String> required, int base, int k, int d) {
        if (k >= 0 ? knowledgeBlocked[k] != null : blocked[d] != null) {
            return false;
        }
        for (var name : required) {
            var slot = slots.get(name);
            if (slot != null && isKnowledge(slot) && knowledgeBlocked[slot - base] != null) {
                var reason = "it requires %s '%s', which cannot be evaluated".formatted(kindOf(slot), name);
                if (k >= 0) {
                    knowledgeBlocked[k] = reason;
                } else {
                    blocked[d] = reason;
                }
                return true;
            }
        }
        return false;
    }

    /** Reports an issue of a business knowledge model, which then cannot be evaluated, nor what requires it. */
    private void reportKnowledge(int k, Kind kind, String detail) {
        issues.add(new Issue(kind, List.of(), detail, Map.of()));
        if (knowledgeBlocked[k] == null) {
            knowledgeBlocked[k] = detail;
        }
    }

    /** Blocks every business knowledge model that requires one that cannot be evaluated, directly or indirectly. */
    private void blockKnowledge() {
        var knowledgeModels = model.knowledgeModels();
        for (boolean changed = true; changed;) {
            changed = false;
            for (int k = 0; k < knowledgeModels.size(); k++) {
                if (knowledgeBlocked[k] != null) {
                    continue;
                }
                for (var required : knowledgeModels.get(k).requiredKnowledge()) {
                    var slot = slots.get(required);
                    if (slot != null && isKnowledge(slot) && knowledgeBlocked[slot - inputCount - decisionCount] != null) {
                        knowledgeBlocked[k] = "it requires business knowledge model '%s', which cannot be evaluated".formatted(required);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Types every business knowledge model as the function its logic defines, each from the types of those it invokes,
     * in the order they require one another; one that invokes itself, directly or not, reads its own type as any type.
     */
    private void typeKnowledge() {
        int count = model.knowledgeModels().size();
        var state = new int[count]; // 0: not typed, 1: being typed, 2: typed
        for (int k = 0; k < count; k++) {
            typeKnowledge(k, state);
        }
    }

    private FeelType typeKnowledge(int k, int[] state) {
        int slot = inputCount + decisionCount + k;
        if (state[k] == 2) {
            return types[slot];
        }
        if (state[k] == 1) {
            return FeelType.ANY;
        }
        state[k] = 1;
        var expression = knowledgeExpressions[k];
        var declared = model.knowledgeModels().get(k).logic().type();
        FeelType type = declared;
        if (expression != null && knowledgeBlocked[k] == null) {
            var reads = knowledgeReads.get(k);
            var computed = expression.type(name -> {
                var read = reads.get(name);
                if (read == null) {
                    return null;
                }
                int index = read - inputCount - decisionCount;
                // a decision service is typed after the decisions, so a business knowledge model reads it as any function
                return index < state.length ? typeKnowledge(index, state) : FeelType.ANY;
            });
            type = declared instanceof FeelType.Any ? computed : declared;
        }
        types[slot] = type;
        state[k] = 2;
        return type;
    }

    /**
     * Resolves each name the decision's logic reads among its requirements: the whole name if one is so named, else the
     * longest part before a dot that is, the rest being a path into its value ({@code Pricing.discount}). A name no
     * requirement provides is reported; one a requirement the model does not have (or of the other kind) provides is
     * left unresolved, that requirement being reported already.
     */
    private void resolveReads(int d) {
        embeddedTables.add(List.of());
        var decision = model.decisions().get(d);
        var scope = new HashSet<>(decision.requirements());
        var valid = new HashSet<String>();
        decision.requiredDecisions().stream().filter(name -> isDecision(slots.get(name))).forEach(valid::add);
        decision.requiredInputs().stream().filter(name -> isInputData(slots.get(name))).forEach(valid::add);
        decision.requiredKnowledge().stream().filter(name -> isKnowledge(slots.get(name))).forEach(valid::add);
        var resolved = new LinkedHashMap<String, Resolution>();
        var names = new LinkedHashSet<String>();
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var type) -> {
                var read = tableReads(decision.name(), table, tableScope(scope, table, model.types()));
                tableReads.add(read);
                names.addAll(read.names());
            }
            default -> {
                tableReads.add(TableReads.NONE);
                parseExpression(d, decision.logic(), scope);
                if (expressions[d] != null) {
                    names.addAll(expressions[d].names());
                }
            }
        }
        for (var name : names) {
            var requirement = DecisionModel.elementOf(name, scope::contains);
            if (requirement == null) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' reads '%s', which none of its information requirements provides (DMN 1.5, 7.3.4)"
                        .formatted(decision.name(), name));
            } else if (valid.contains(requirement)) {
                var path = requirement.length() == name.length() ? new String[0] : name.substring(requirement.length() + 1).split("\\.");
                resolved.put(name, new Resolution(slots.get(requirement), path, null));
            }
            // else a requirement the model does not have, or of the other kind, which is reported already
        }
        reads.add(resolved);
        listedNamedInputs.add(new LinkedHashSet<>());
        propertyColumns.add(new LinkedHashMap<>());
    }

    /**
     * Parses a decision's literal expression, or composes its boxed expression ({@link FeelExpression#compose}), with the
     * names of its requirements in scope (DMN 1.5, 7.3.4 and 10.3.2.11), and the types the model defines; reports what it
     * uses that Praecepta does not evaluate yet (an external function, planned for 0.11), and an expression that is not
     * FEEL, or names a type that neither FEEL nor the model defines ({@link Kind#INVALID_EXPRESSION}).
     */
    private void parseExpression(int d, DecisionLogic logic, Set<String> scope) {
        var decision = model.decisions().get(d).name();
        try {
            var tables = new ArrayList<GraphNodes.EmbeddedTable>();
            embeddedTables.set(d, tables);
            expressions[d] = FeelExpression.compose(logic, FeelExpression.scope(scope, model.types()), site -> embed(site, tables));
        } catch (ExpressionSyntaxException e) {
            report(d, Kind.INVALID_EXPRESSION, "decision '%s': %s".formatted(decision, e.getMessage()));
            return;
        }
        for (var what : expressions[d].unsupported()) {
            report(d, Kind.UNSUPPORTED_FEATURE, "decision '%s' uses %s, which Praecepta does not evaluate yet".formatted(decision, what));
        }
    }

    private boolean isDecision(Integer slot) {
        return slot != null && slot >= inputCount && slot < inputCount + decisionCount;
    }

    private boolean isKnowledge(Integer slot) {
        return slot != null && slot >= inputCount + decisionCount;
    }

    private boolean isInputData(Integer slot) {
        return slot != null && slot < inputCount;
    }

    /**
     * The names a decision's logic reads: a table's columns and the inputs its cells compare with, or the names a literal
     * expression reads, parsed with {@code scope} in scope (none if it does not parse so), and the model's {@code types}.
     */
    static Set<String> namesRead(Decision decision, Set<String> names, FeelExpression.Scope scope, Map<String, FeelType> types) {
        var read = new LinkedHashSet<String>();
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var type) ->
                    read.addAll(tableReads(decision.name(), table, tableScope(names, table, types)).names());
            default -> {
                try {
                    read.addAll(FeelExpression.compose(decision.logic(), scope).names());
                } catch (ExpressionSyntaxException e) {
                    // reported when the graph is built
                }
            }
        }
        return read;
    }

    /**
     * The names in scope for a decision table's FEEL: its decision's requirements (DMN 1.5, 7.3.4) and its own columns,
     * with the model's types.
     */
    static FeelExpression.Scope tableScope(Set<String> requirements, DecisionTable table, Map<String, FeelType> types) {
        var names = new LinkedHashSet<>(requirements);
        // a column that is an expression is no name: its text would read as itself
        table.inputs().stream().filter(input -> !TableExpressions.isExpression(input.name())).forEach(input -> names.add(input.name()));
        return FeelExpression.scope(names, types);
    }

    /**
     * What a table reads ({@link TableReads}): its columns that name what they read, and the inputs its simple cells
     * compare with, each of the type of the column that compares with it; its columns that are expressions; and the names
     * its FEEL reads beside its columns, parsed with {@code scope} in scope.
     *
     * @throws InvalidDecisionTableException if a cell or an expression is invalid, or columns of different types
     *                                       compare with one input
     */
    static TableReads tableReads(String decision, DecisionTable table, FeelExpression.Scope scope) {
        var columns = new ArrayList<ColumnRead>();
        var computed = new LinkedHashMap<String, FeelExpression>();
        var expressions = new LinkedHashSet<String>();
        var columnNames = new HashSet<String>();
        var parsed = new TableExpressions.Parsed(scope, new HashMap<>(), new HashMap<>());
        for (var input : table.inputs()) {
            if (TableExpressions.isExpression(input.name())) {
                // no name in the table: its text, read as a name, is what the decision requires of that name
                var expression = parse(decision, input.name(), parsed);
                computed.put(input.name(), expression);
                expressions.addAll(expression.names());
            } else {
                columnNames.add(input.name());
                columns.add(new ColumnRead(input.name(), input.type(), table.listInputs().contains(input.name())));
            }
        }
        var named = new LinkedHashMap<String, ColumnType>();
        for (var rule : table.rules()) {
            for (var input : table.inputs()) {
                cellReads(decision, table, rule, input, parsed, named, expressions);
            }
            rule.output().expressions().values().forEach(text -> expressions.addAll(parse(decision, text, parsed).names()));
        }
        named.keySet().removeAll(columnNames);
        named.forEach((name, type) -> columns.add(new ColumnRead(name, type, table.listInputs().contains(name))));
        expressions.removeAll(columnNames);
        expressions.removeAll(named.keySet());
        return new TableReads(List.copyOf(columns), Collections.unmodifiableMap(computed), Collections.unmodifiableSet(expressions),
                new TableExpressions.Parsed(scope, Map.copyOf(parsed.cells()), Map.copyOf(parsed.expressions())));
    }

    /**
     * Adds what a cell reads: to {@code named} the inputs a simple cell compares with, of its column's type; to
     * {@code expressions} the names a cell in the full FEEL language reads, which it parses into {@code parsed}.
     */
    private static void cellReads(String decision, DecisionTable table, Praeceptum rule, DecisionTable.InputColumn input,
            TableExpressions.Parsed parsed, Map<String, ColumnType> named, Set<String> expressions) {
        UnaryTests cell;
        try {
            cell = table.cell(rule, input);
        } catch (CellSyntaxException rejected) {
            if (!table.feelCells()) {
                throw invalid(decision, rejected);
            }
            expressions.addAll(parseCell(decision, rule.input().values().get(input.name()), rejected, parsed).names());
            return;
        }
        for (var name : UnaryTests.names(cell)) {
            if (table.inputs().stream().anyMatch(column -> column.name().equals(name))) {
                continue;
            }
            var previous = named.putIfAbsent(name, input.type());
            if (previous != null && previous != input.type()) {
                throw new InvalidDecisionTableException("Decision '%s': '%s' is compared with both a %s and a %s column"
                        .formatted(decision, name, previous.feelName(), input.type().feelName()));
            }
        }
    }

    /** A cell of a table beyond the simple unary tests, parsed once whatever the rules that hold it, into {@code parsed}. */
    private static FeelUnaryTests parseCell(String decision, String text, CellSyntaxException rejected, TableExpressions.Parsed parsed) {
        var tests = parsed.cells().get(text);
        if (tests == null) {
            try {
                tests = FeelUnaryTests.parseCell(text, parsed.scope(), rejected);
            } catch (CellSyntaxException e) {
                throw invalid(decision, e);
            }
            parsed.cells().put(text, tests);
        }
        return tests;
    }

    /** An expression of a table, parsed once whatever the rules that hold it, into {@code parsed}. */
    private static FeelExpression parse(String decision, String text, TableExpressions.Parsed parsed) {
        var expression = parsed.expressions().get(text);
        if (expression == null) {
            try {
                expression = FeelExpression.parse(text, parsed.scope());
            } catch (ExpressionSyntaxException e) {
                throw invalid(decision, e);
            }
            parsed.expressions().put(text, expression);
        }
        return expression;
    }

    private static InvalidDecisionTableException invalid(String decision, IllegalArgumentException e) {
        return new InvalidDecisionTableException("Decision '%s': %s".formatted(decision, e.getMessage()));
    }

    // ---- cycles and order --------------------------------------------------------------------------

    /**
     * Reports each cycle of required decisions: every strongly connected component of more than one decision, or of one
     * that requires itself (Tarjan's algorithm, with an explicit stack).
     *
     * @return whether each decision is in a cycle
     */
    private boolean[] findCycles() {
        var components = new StronglyConnected(requiredDecisions);
        var cyclic = new boolean[requiredDecisions.size()];
        for (int root = 0; root < requiredDecisions.size(); root++) {
            components.visit(root).forEach(component -> reportCycle(component, cyclic));
        }
        return cyclic;
    }

    /**
     * Tarjan's algorithm for the strongly connected components of the graph of required decisions, with a stack of
     * frames of its own instead of recursion.
     */
    private static final class StronglyConnected {
        private final List<int[]> edges;
        private final int[] index;
        private final int[] low;
        private final boolean[] onStack;
        private final ArrayDeque<Integer> stack = new ArrayDeque<>();
        private int next;

        StronglyConnected(List<int[]> edges) {
            this.edges = edges;
            index = new int[edges.size()];
            low = new int[edges.size()];
            onStack = new boolean[edges.size()];
            Arrays.fill(index, -1);
        }

        /** The components found from {@code root}, if it was not visited yet; each is complete when it is listed. */
        List<List<Integer>> visit(int root) {
            var components = new ArrayList<List<Integer>>();
            if (index[root] >= 0) {
                return components;
            }
            // each frame: a decision and how many of the decisions it requires it has visited
            var frames = new ArrayDeque<int[]>();
            enter(root, frames);
            while (!frames.isEmpty()) {
                var frame = frames.peek();
                int d = frame[0];
                var required = edges.get(d);
                if (frame[1] < required.length) {
                    follow(d, required[frame[1]++], frames);
                } else {
                    frames.pop();
                    if (!frames.isEmpty()) {
                        int parent = frames.peek()[0];
                        low[parent] = Math.min(low[parent], low[d]);
                    }
                    if (low[d] == index[d]) {
                        components.add(component(d));
                    }
                }
            }
            return components;
        }

        private void enter(int d, ArrayDeque<int[]> frames) {
            index[d] = next;
            low[d] = next;
            next++;
            stack.push(d);
            onStack[d] = true;
            frames.push(new int[] {d, 0});
        }

        private void follow(int d, int required, ArrayDeque<int[]> frames) {
            if (index[required] < 0) {
                enter(required, frames);
            } else if (onStack[required]) {
                low[d] = Math.min(low[d], index[required]);
            }
        }

        /** The component whose root is {@code d}: the decisions on the stack down to it. */
        private List<Integer> component(int d) {
            var component = new ArrayList<Integer>();
            int member;
            do {
                member = stack.pop();
                onStack[member] = false;
                component.add(member);
            } while (member != d);
            return component;
        }
    }

    private void reportCycle(List<Integer> component, boolean[] cyclic) {
        int first = component.stream().mapToInt(Integer::intValue).min().orElseThrow();
        boolean selfRequiring = Arrays.stream(requiredDecisions.get(first)).anyMatch(r -> r == first);
        if (component.size() == 1 && !selfRequiring) {
            return;
        }
        component.forEach(member -> cyclic[member] = true);
        String detail;
        if (component.size() == 1) {
            detail = "decision '%s' requires itself (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)"
                    .formatted(model.decisions().get(first).name());
        } else {
            var names = cycleThrough(first, Set.copyOf(component)).stream()
                    .map(d -> "'" + model.decisions().get(d).name() + "'")
                    .collect(Collectors.joining(" -> "));
            detail = "decisions %s require one another in a cycle (DMN 1.5, 6.3.7: a decision SHALL not require itself, directly or indirectly)"
                    .formatted(names);
        }
        issues.add(new Issue(Kind.DECISION_CYCLE, List.of(), detail, Map.of()));
        component.forEach(member -> {
            if (blocked[member] == null) {
                blocked[member] = detail;
            }
        });
    }

    /** A cycle from {@code start} back to it within a strongly connected component, found breadth first: start, ..., start. */
    private List<Integer> cycleThrough(int start, Set<Integer> component) {
        var previous = new HashMap<Integer, Integer>();
        var queue = new ArrayDeque<Integer>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int d = queue.poll();
            for (int r : requiredDecisions.get(d)) {
                if (r == start) {
                    var cycle = new ArrayList<Integer>();
                    for (int at = d; at != start; at = previous.get(at)) {
                        cycle.add(at);
                    }
                    cycle.add(start);
                    Collections.reverse(cycle); // start, ..., d, each requiring the next
                    cycle.add(start);
                    return cycle;
                }
                if (component.contains(r) && previous.putIfAbsent(r, d) == null) {
                    queue.add(r);
                }
            }
        }
        throw new IllegalStateException("no cycle through a decision of a strongly connected component");
    }

    /** The decisions in no cycle and requiring none in one, each after the decisions it requires (Kahn's algorithm). */
    private int[] topologicalOrder(boolean[] cyclic) {
        int count = model.decisions().size();
        var pending = new int[count];
        var dependents = new ArrayList<List<Integer>>();
        for (int d = 0; d < count; d++) {
            dependents.add(new ArrayList<>());
        }
        for (int d = 0; d < count; d++) {
            pending[d] = requiredDecisions.get(d).length;
            for (int r : requiredDecisions.get(d)) {
                dependents.get(r).add(d);
            }
        }
        var ready = new ArrayDeque<Integer>();
        for (int d = 0; d < count; d++) {
            if (pending[d] == 0 && !cyclic[d]) {
                ready.add(d);
            }
        }
        var ordered = new ArrayList<Integer>();
        while (!ready.isEmpty()) {
            int d = ready.poll();
            ordered.add(d);
            for (int dependent : dependents.get(d)) {
                if (--pending[dependent] == 0 && !cyclic[dependent]) {
                    ready.add(dependent);
                }
            }
        }
        return ordered.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- types -------------------------------------------------------------------------------------

    /**
     * Gives an input data the model declares no type for the type of the decision table inputs that read it whole, as
     * long as they agree; where they do not, the later reader has a type mismatch.
     */
    private void inferInputTypes() {
        var inferredBy = new HashMap<Integer, String>();
        for (int d = 0; d < model.decisions().size(); d++) {
            var decision = model.decisions().get(d);
            for (var read : tableReads.get(d).columns()) {
                var resolution = reads.get(d).get(read.name());
                if (resolution == null || resolution.path().length > 0 || resolution.slot() >= inputCount
                        || !(model.inputData().get(resolution.slot()).type() instanceof FeelType.Any)) {
                    continue;
                }
                var type = read.list() ? FeelType.listOf(read.type()) : FeelType.of(read.type());
                int slot = resolution.slot();
                if (types[slot] instanceof FeelType.Any) {
                    types[slot] = type;
                    inferredBy.put(slot, decision.name());
                } else if (!types[slot].equals(type)) {
                    report(d, Kind.TYPE_MISMATCH, "input data '%s' declares no type, and decision '%s' reads it as a %s, but decision '%s' as a %s"
                            .formatted(read.name(), inferredBy.get(slot), types[slot].feelName(), decision.name(), type.feelName()));
                    conflicting.add(d + ":" + read.name());
                }
            }
        }
    }

    /**
     * Works out each decision's type, required decisions first, and resolves the type of every name each decision
     * reads; a decision in a cycle reads values whose types may not be known yet, which do not matter, as it is never
     * evaluated.
     */
    private void typeDecisions() {
        var typed = new boolean[model.decisions().size()];
        for (int d : typingOrder()) {
            typeDecision(d);
            typed[d] = true;
        }
        for (int d = 0; d < typed.length; d++) {
            if (!typed[d]) {
                types[inputCount + d] = declaredType(model.decisions().get(d));
            }
        }
        for (int d = 0; d < typed.length; d++) {
            if (!typed[d]) {
                locateReads(d);
            }
        }
    }

    /**
     * The order the decisions in no cycle are typed in: {@link #order}, but each decision that invokes a decision service
     * after the decisions the service evaluates, so that it reads the service's type, unless they invoke it in turn.
     * Worked out with a stack of its own.
     */
    private int[] typingOrder() {
        var inOrder = new boolean[decisionCount];
        for (int d : order) {
            inOrder[d] = true;
        }
        int services = inputCount + decisionCount + model.knowledgeModels().size();
        var state = new int[decisionCount]; // 0: not visited, 1: being visited, 2: placed
        var result = new ArrayList<Integer>();
        for (int start : order) {
            var stack = new ArrayDeque<int[]>(); // decision, and whether its predecessors were pushed
            stack.push(new int[] {start, 0});
            while (!stack.isEmpty()) {
                var top = stack.peek();
                int d = top[0];
                if (state[d] == 2) {
                    stack.pop();
                    continue;
                }
                if (top[1] == 1) {
                    stack.pop();
                    state[d] = 2;
                    result.add(d);
                    continue;
                }
                top[1] = 1;
                state[d] = 1;
                for (int r : requiredDecisions.get(d)) {
                    if (state[r] == 0 && inOrder[r]) {
                        stack.push(new int[] {r, 0});
                    }
                }
                for (var name : model.decisions().get(d).requiredKnowledge()) {
                    var slot = slots.get(name);
                    if (slot != null && slot >= services) {
                        for (int e : serviceDecisions.get(slot - services)) {
                            if (state[e] == 0 && inOrder[e]) {
                                stack.push(new int[] {e, 0});
                            }
                        }
                    }
                }
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private void typeDecision(int d) {
        int services = inputCount + decisionCount + model.knowledgeModels().size();
        for (var name : model.decisions().get(d).requiredKnowledge()) {
            var slot = slots.get(name);
            if (slot != null && slot >= services) {
                typeService(slot - services); // its outputs, which do not require this decision, are typed already
            }
        }
        locateReads(d);
        var decision = model.decisions().get(d);
        types[inputCount + d] = switch (decision.logic()) {
            case DecisionLogic.Table(var table, var declared) -> bind(d, declared, valueType(table, declared), "decision table");
            default -> expressionType(d, decision.logic());
        };
    }

    /**
     * The type of a decision table's value ({@link #tableType}), but for a table with several results and a default
     * output whose decision declares no type: any type, as its value is a list, or the default output, a single value
     * (DMN 1.5, 10.3.2.10, step 2; docs/DMN-CONFORMANCE.md, case 14).
     */
    private static FeelType valueType(DecisionTable table, FeelType declared) {
        boolean severalResults = !table.hitPolicy().isSingleHit() && table.aggregation() == null;
        if (severalResults && declared instanceof FeelType.Any && table.defaultOutput().isPresent()) {
            return FeelType.ANY;
        }
        return tableType(table);
    }

    /** The type of each name the decision reads, at its path in the value of the requirement that provides it. */
    private void locateReads(int d) {
        reads.get(d).replaceAll((name, resolution) -> {
            var path = resolution.path();
            // a decision service not typed yet reads as any type
            var type = types[resolution.slot()] == null ? FeelType.ANY : types[resolution.slot()];
            var at = new StringBuilder(rootName(name, path));
            for (var component : path) {
                var next = type.component(component);
                if (next == null) {
                    return missingComponent(resolution, at.toString(), type, component);
                }
                type = next;
                at.append('.').append(component);
            }
            return new Resolution(resolution.slot(), path, type);
        });
    }

    /** The properties FEEL gives dates, times, date-times and durations (DMN 1.5, Table 65), which a path reads. */
    private static final Set<String> TEMPORAL_PROPERTIES = Set.of("year", "month", "day", "weekday", "hour", "minute", "second",
            "time offset", "timezone", "years", "months", "days", "hours", "minutes", "seconds");

    /**
     * A path that reads a component {@code at}'s type does not have: a property of a date, time or duration (DMN 1.5,
     * Table 65), which a decision table's column reads as FEEL reads it ({@code Birth.year}), or else a component that
     * does not exist.
     */
    private static Resolution missingComponent(Resolution resolution, String at, FeelType type, String component) {
        if (type instanceof FeelType.Simple(var simple) && simple != ColumnType.STRING && simple != ColumnType.NUMBER
                && simple != ColumnType.BOOLEAN && TEMPORAL_PROPERTIES.contains(component)) {
            var error = "'%s' is a %s, whose property '%s' is read as FEEL reads it (DMN 1.5, Table 65)".formatted(at, type.feelName(),
                    component);
            return new Resolution(resolution.slot(), resolution.path(), FeelType.ANY, error, true);
        }
        var error = "'%s' is a %s, which has no component '%s'".formatted(at, type.feelName(), component);
        return new Resolution(resolution.slot(), resolution.path(), FeelType.ANY, error, false);
    }

    /** The requirement's name in a name that reads a path into it. */
    private static String rootName(String name, String[] path) {
        int length = name.length();
        for (var component : path) {
            length -= component.length() + 1;
        }
        return name.substring(0, length);
    }

    /**
     * The type of a decision table's value: its one output's, or a context of its outputs; a list of that under a hit
     * policy with several results and no aggregation; a number for COUNT (DMN 1.5, 10.3.2.10). An output that is not
     * {@linkplain DecisionTable.OutputColumn#typed() typed} is of any type, each of its entries giving a value of its own
     * (step 3c).
     */
    static FeelType tableType(DecisionTable table) {
        if (table.aggregation() == AggregationOperator.COUNT) {
            return FeelType.of(ColumnType.NUMBER);
        }
        FeelType output;
        if (table.outputs().size() == 1) {
            output = outputType(table.outputs().getFirst());
        } else {
            var components = new LinkedHashMap<String, FeelType>();
            table.outputs().forEach(column -> components.put(column.name(), outputType(column)));
            output = new FeelType.Context(components);
        }
        return table.hitPolicy().isSingleHit() || table.aggregation() != null ? output : new FeelType.ListOf(output);
    }

    private static FeelType outputType(DecisionTable.OutputColumn column) {
        return column.typed() ? FeelType.of(column.type()) : FeelType.ANY;
    }

    private static FeelType declaredType(Decision decision) {
        return switch (decision.logic()) {
            case DecisionLogic.Table(var table, var declared) -> declared instanceof FeelType.Any ? tableType(table) : declared;
            default -> decision.logic().type();
        };
    }

    /**
     * The type of a literal expression's value, or a boxed expression's, bound to the type its decision declares ({@link #bind}): its
     * expression's, as FEEL types it from the types of the names it reads (DMN 1.5, 10.3.2.9), {@link FeelType#ANY}
     * where only the values tell. An expression whose value is null whatever its inputs (operands FEEL does not combine,
     * such as a string and a number, a path to a component its type does not have) is reported
     * ({@link Kind#ALWAYS_NULL}), and evaluated: FEEL gives null there rather than fail.
     */
    private FeelType expressionType(int d, DecisionLogic decisionLogic) {
        var expression = expressions[d];
        checkPaths(d);
        if (blocked[d] != null || expression == null) {
            return decisionLogic.type(); // reported already: a name none of its requirements provides, or what Praecepta does not evaluate
        }
        var logic = decisionLogic instanceof DecisionLogic.LiteralExpression ? "literal expression '%s'".formatted(expression.text().strip())
                : expression.text();
        var reasons = new ArrayList<String>();
        var resolved = reads.get(d);
        var type = expression.type(name -> resolved.containsKey(name) ? resolved.get(name).type() : null, reasons::add);
        if (type instanceof FeelType.Null) {
            bindings[d] = GraphValues.Binding.NULL;
            var reason = reasons.isEmpty() ? "it is null" : reasons.getFirst();
            issues.add(new Issue(Kind.ALWAYS_NULL, List.of(), "decision '%s': in %s, %s; its value is null (DMN 1.5, 10.3.2.15)"
                    .formatted(model.decisions().get(d).name(), logic, reason), Map.of()));
            return decisionLogic.type();
        }
        return bind(d, decisionLogic.type(), type, logic);
    }

    /** Reports each name a literal expression reads by a path into a requirement's value that its type does not have. */
    private void checkPaths(int d) {
        var decision = model.decisions().get(d).name();
        for (var read : reads.get(d).entrySet()) {
            var resolution = read.getValue();
            if (resolution.error() != null) {
                report(d, resolution.unsupported() ? Kind.UNSUPPORTED_FEATURE : Kind.TYPE_MISMATCH,
                        "decision '%s' reads '%s', but %s".formatted(decision, read.getKey(), resolution.error()));
            }
        }
    }

    /**
     * The type of a decision whose logic gives values of type {@code actual}, and how they are bound to it (DMN 1.5,
     * 10.3.2.9.4, binding contexts): as they are if the decision declares no type, or one they conform to (10.3.2.9.2),
     * keeping their own; a date converted to a date and time at UTC midnight, a value to a list of one, or a list of one
     * to its value, where the decision declares that; checked when the decision is evaluated where the values' type is
     * known only then; and otherwise as null, the values not conforming to the declared type, which is reported
     * ({@link Kind#ALWAYS_NULL}), though the decision is evaluated.
     *
     * @param logic the decision's logic, as the report names it
     */
    private FeelType bind(int d, FeelType declared, FeelType actual, String logic) {
        if (conforms(actual, declared)) {
            return actual;
        }
        if (actual.equals(FeelType.of(ColumnType.DATE)) && declared.equals(FeelType.of(ColumnType.DATE_TIME))) {
            bindings[d] = GraphValues.Binding.DATE_TO_DATE_TIME;
            return declared;
        }
        if (declared instanceof FeelType.ListOf(var element) && conforms(actual, element)) {
            bindings[d] = GraphValues.Binding.SINGLETON_LIST;
            return new FeelType.ListOf(actual);
        }
        if (actual instanceof FeelType.ListOf(var element) && conforms(element, declared)) {
            bindings[d] = GraphValues.Binding.FROM_SINGLETON_LIST;
            return element;
        }
        if (holdsAny(actual)) {
            bindings[d] = GraphValues.Binding.CHECKED;
            return declared;
        }
        bindings[d] = GraphValues.Binding.NULL;
        issues.add(new Issue(Kind.ALWAYS_NULL, List.of(),
                "decision '%s' declares a %s, but its %s gives a %s, so its value is null (DMN 1.5, 10.3.2.9.4)".formatted(model.decisions().get(d).name(), declared.feelName(), logic, actual.feelName()), Map.of()));
        return declared;
    }

    /**
     * Whether every value of type {@code type} conforms to {@code target} (DMN 1.5, 10.3.2.9.2): any type does to any
     * type; a type does to an equivalent one; a list does to a list whose elements' type its elements' conforms to; a
     * context does to a context each of whose components it has, of a type that conforms. A value of any type may or may
     * not conform to another, which only the value tells.
     */
    static boolean conforms(FeelType type, FeelType target) {
        return type.conformsTo(target);
    }

    /** Whether a type is, or holds, any type, so that whether its values conform is known only from them. */
    private static boolean holdsAny(FeelType type) {
        return switch (type) {
            case FeelType.Any any -> true;
            case FeelType.ListOf(var element) -> holdsAny(element);
            case FeelType.RangeOf(var element) -> holdsAny(element);
            case FeelType.Context(var components) -> components.values().stream().anyMatch(GraphAnalysis::holdsAny);
            default -> false;
        };
    }

    /** Checks that every value a decision table reads fits the column, or the input its cells compare with, that reads it. */
    private void checkTableReads() {
        for (int d = 0; d < model.decisions().size(); d++) {
            var decision = model.decisions().get(d);
            if (!(decision.logic() instanceof DecisionLogic.Table(var table, var type))) {
                continue;
            }
            var columns = table.inputs().stream().map(DecisionTable.InputColumn::name).collect(Collectors.toSet());
            for (var read : tableReads.get(d).columns()) {
                var resolution = reads.get(d).get(read.name());
                if (resolution != null && !conflicting.contains(d + ":" + read.name())) {
                    checkTableRead(d, read, resolution, columns.contains(read.name()));
                }
            }
            for (var computed : tableReads.get(d).computed().entrySet()) {
                checkComputedColumn(d, table, computed.getKey(), computed.getValue());
            }
        }
    }

    private void checkTableRead(int d, ColumnRead read, Resolution resolution, boolean column) {
        var source = resolution.error() == null ? resolution.type() : pathType(d, read, resolution, column);
        if (source == null) {
            return; // reported
        }
        boolean single = source instanceof FeelType.Simple(var type) && type == read.type() || source instanceof FeelType.Any;
        boolean list = source.equals(FeelType.listOf(read.type())) || source.equals(new FeelType.ListOf(FeelType.ANY));
        if (single || read.list() && list) {
            return;
        }
        if (list && !column) {
            listedNamedInputs.get(d).add(read.name()); // a cell names a list: the value is in it, as with an input data of lists
            return;
        }
        reportMismatch(d, read, source, column ? "input" : "cells' comparisons with");
    }

    /**
     * The type of a column's path that its requirement's type does not have: a property of a date, time or duration,
     * which the column reads as FEEL does ({@link #propertyType}); else null, the path reported as a type mismatch.
     */
    private FeelType pathType(int d, ColumnRead read, Resolution resolution, boolean column) {
        var property = resolution.unsupported() && column ? propertyType(d, read, resolution) : null;
        if (property == null) {
            report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', but %s".formatted(model.decisions().get(d).name(), read.name(),
                    resolution.error()));
        }
        return property;
    }

    private void reportMismatch(int d, ColumnRead read, FeelType source, String what) {
        var expected = read.list() ? FeelType.listOf(read.type()) : FeelType.of(read.type());
        report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', a %s, into its %s '%s', a %s".formatted(model.decisions().get(d).name(),
                read.name(), source.feelName(), what, read.name(), expected.feelName()));
    }

    /**
     * The type of a column that reads a property of a date, time or duration ({@code Birth.year}, DMN 1.5, Table 65),
     * which the table reads as FEEL does, computing the column from its requirement's value; null if FEEL has no such
     * property there.
     */
    private FeelType propertyType(int d, ColumnRead read, Resolution resolution) {
        var root = rootName(read.name(), resolution.path());
        var reasons = new ArrayList<String>();
        var expression = FeelExpression.parse(read.name(), FeelExpression.scope(List.of(root), Map.of()));
        var type = expression.type(name -> name.equals(root) ? types[resolution.slot()] : null, reasons::add);
        if (type instanceof FeelType.Null) {
            return null;
        }
        propertyColumns.get(d).put(read.name(), new PropertyColumn(expression, root, resolution.slot()));
        return type;
    }

    /**
     * Checks a column whose input expression is an expression ({@code Age * 12}): its value, as FEEL types it from the
     * types of what it reads, fits the column, a value of its type (or of a list of it) or of a type only its values tell.
     */
    private void checkComputedColumn(int d, DecisionTable table, String column, FeelExpression expression) {
        var resolved = reads.get(d);
        var type = expression.type(name -> resolved.containsKey(name) ? resolved.get(name).type() : null);
        var read = new ColumnRead(column, table.inputs().stream().filter(input -> input.name().equals(column)).findFirst()
                .orElseThrow().type(), table.listInputs().contains(column));
        boolean single = type instanceof FeelType.Simple(var simple) && simple == read.type();
        boolean list = type instanceof FeelType.ListOf(var element) && (element.equals(FeelType.of(read.type())) || element instanceof FeelType.Any);
        boolean fits = type instanceof FeelType.Any || type instanceof FeelType.Null || single || read.list() && list;
        if (!fits) {
            reportMismatch(d, read, type, "input");
        }
    }

    /** A column that reads a property of a date, time or duration: the FEEL expression of its name, and the requirement it reads. */
    record PropertyColumn(FeelExpression expression, String root, int slot) {
    }

    // ---- what cannot be evaluated ------------------------------------------------------------------

    private void report(int d, Kind kind, String detail) {
        report(d, kind, detail, true);
    }

    /** Blocks a decision for an issue, which is reported unless it is reported already, as a business knowledge model's. */
    private void report(int d, Kind kind, String detail, boolean issue) {
        if (issue) {
            issues.add(new Issue(kind, List.of(), detail, Map.of()));
        }
        if (blocked[d] == null) {
            blocked[d] = detail;
        }
    }

    /** Blocks every decision that requires one that cannot be evaluated, directly or indirectly, or one in a cycle. */
    private void block(boolean[] cyclic) {
        var ordered = new boolean[blocked.length];
        for (int d : order) {
            ordered[d] = true;
            for (int r : requiredDecisions.get(d)) {
                if (blocked[d] == null && blocked[r] != null) {
                    blocked[d] = "it requires decision '%s', which cannot be evaluated: %s".formatted(model.decisions().get(r).name(), blocked[r]);
                }
            }
        }
        for (int d = 0; d < blocked.length; d++) {
            if (!ordered[d] && blocked[d] == null) {
                blocked[d] = cyclic[d] ? "it is in a cycle of required decisions" : "it requires a decision in a cycle of required decisions";
            }
        }
    }
}
