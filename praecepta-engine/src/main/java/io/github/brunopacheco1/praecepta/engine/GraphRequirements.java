/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.Resolution;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.BusinessKnowledgeModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.DecisionService;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;

/**
 * The first phase of a {@link GraphAnalysis}: checks that each element requires what the model has, parses its logic
 * with the names of its requirements in scope, and resolves each name the logic reads to the slot of the requirement
 * that provides it. It does so for the business knowledge models, then the decision services (which have no logic: it
 * works out what each evaluates), then the decisions. What is wrong is a {@link Kind#MISSING_REQUIREMENT}, or a
 * {@link Kind#INVALID_EXPRESSION} or {@link Kind#UNSUPPORTED_FEATURE} of the logic.
 */
final class GraphRequirements {

    private final GraphAnalysis graph;
    private final DecisionModel model;
    private final GraphSlots slots;
    /** Whether the engines of the tables within boxed expressions log their validation reports. */
    private final boolean log;
    /** By element, the names its own model reads, each to its element's name in the graph ({@link ImportedModels}). */
    private final Map<String, Map<String, String>> aliases;

    GraphRequirements(GraphAnalysis graph, boolean log, Map<String, Map<String, String>> aliases) {
        this.graph = graph;
        this.model = graph.model;
        this.slots = graph.slots;
        this.log = log;
        this.aliases = aliases;
    }

    /** Checks every business knowledge model, then every decision service. */
    void checkInvocables() {
        for (int k = 0; k < slots.knowledgeCount; k++) {
            resolveKnowledge(k);
        }
        for (int k = 0; k < slots.serviceCount; k++) {
            checkService(k);
        }
    }

    /** Checks every decision: its requirements, then the names its logic reads. */
    void checkDecisions() {
        for (int d = 0; d < slots.decisionCount; d++) {
            checkRequirements(d);
            resolveReads(d);
        }
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
        var valid = requiredKnowledge(k, knowledgeModel);
        // a business knowledge model may invoke itself, as a function a context defines may
        var scope = localScope(name, knowledgeModel.requiredKnowledge());
        scope.addAll(localScope(name, List.of(name)));
        valid.add(name);
        FeelExpression expression;
        try {
            var tables = graph.knowledgeTables.get(k);
            expression = FeelExpression.compose(knowledgeModel.logic(), FeelExpression.closedScope(scope, model.types()),
                    site -> embed(site, tables));
        } catch (ExpressionSyntaxException e) {
            graph.reportKnowledge(k, Kind.INVALID_EXPRESSION, "business knowledge model '%s': %s".formatted(name, e.getMessage()));
            return;
        }
        graph.knowledgeExpressions[k] = expression;
        reportUses(expression, "business knowledge model", name, "6.3.10", (kind, detail) -> graph.reportKnowledge(k, kind, detail));
        var reads = graph.knowledgeReads.get(k);
        for (var read : expression.names()) {
            var required = DecisionModel.elementOf(read, scope::contains);
            if (required == null) {
                graph.reportKnowledge(k, Kind.MISSING_REQUIREMENT, ("business knowledge model '%s' reads '%s', which is neither one of its "
                        + "parameters nor a business knowledge model it requires (DMN 1.5, 6.3.10)").formatted(name, read));
            } else if (valid.contains(global(name, required))) {
                reads.put(read, slots.of(global(name, required)));
            }
        }
    }

    /** The invocables a business knowledge model requires that the model has, by name; the others are reported. */
    private Set<String> requiredKnowledge(int k, BusinessKnowledgeModel knowledgeModel) {
        var valid = new HashSet<String>();
        for (var required : knowledgeModel.requiredKnowledge()) {
            var slot = slots.of(required);
            if (slots.isInvocable(slot)) {
                valid.add(required);
            } else {
                graph.reportKnowledge(k, Kind.MISSING_REQUIREMENT, "business knowledge model '%s' requires business knowledge model '%s', which is %s"
                        .formatted(knowledgeModel.name(), required, fault(slot, "a business knowledge model")));
            }
        }
        return valid;
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
        int invocable = slots.knowledgeCount + k;
        var where = "decision service '%s'".formatted(service.name());
        graph.serviceParameters[k] = parameterSlots(service, invocable, where);
        var named = new ArrayList<>(service.outputDecisions());
        named.addAll(service.encapsulatedDecisions());
        for (var name : named) {
            var slot = slots.of(name);
            if (!slots.isDecision(slot)) {
                graph.reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s evaluates decision '%s', which is %s"
                        .formatted(where, name, fault(slot, "a decision")));
            }
        }
        var outputs = decisionsAmong(service.outputDecisions());
        graph.serviceOutputs[k] = GraphAnalysis.ints(outputs);
        graph.serviceDecisions[k] = evaluated(service, invocable, where, outputs);
    }

    /**
     * The slots of a decision service's parameters, its input data then its input decisions; -1 for one the model does
     * not have. One that is not what the service takes it for is reported.
     */
    private int[] parameterSlots(DecisionService service, int invocable, String where) {
        var parameters = new ArrayList<Integer>();
        for (var name : service.inputData()) {
            var slot = slots.of(name);
            if (!slots.isInputData(slot)) {
                graph.reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s takes input data '%s', which is %s"
                        .formatted(where, name, fault(slot, "input data")));
            }
            parameters.add(slot == null ? -1 : slot);
        }
        for (var name : service.inputDecisions()) {
            var slot = slots.of(name);
            if (!slots.isDecision(slot)) {
                graph.reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, "%s takes decision '%s', which is %s"
                        .formatted(where, name, fault(slot, "a decision")));
            }
            parameters.add(slot == null ? -1 : slot);
        }
        return GraphAnalysis.ints(parameters);
    }

    /**
     * The decisions a decision service evaluates, by index: its output decisions and those they require, directly or not,
     * down to its input decisions, found with a stack of its own. One that requires input data the service does not take
     * is reported.
     */
    private int[] evaluated(DecisionService service, int invocable, String where, List<Integer> outputs) {
        var inputs = new HashSet<>(decisionsAmong(service.inputDecisions()));
        var evaluated = new LinkedHashSet<Integer>();
        var pending = new ArrayDeque<>(outputs);
        while (!pending.isEmpty()) {
            int d = pending.pop();
            if (inputs.contains(d) || !evaluated.add(d)) {
                continue;
            }
            var decision = model.decisions().get(d);
            decisionsAmong(decision.requiredDecisions()).forEach(pending::push);
            for (var name : decision.requiredInputs()) {
                if (!service.inputData().contains(name) && slots.isInputData(slots.of(name))) {
                    graph.reportKnowledge(invocable, Kind.MISSING_REQUIREMENT, ("%s evaluates decision '%s', which requires input data '%s', "
                            + "which the service does not take (DMN 1.5, 6.3.9)").formatted(where, decision.name(), name));
                }
            }
        }
        return GraphAnalysis.ints(evaluated);
    }

    /** The indexes of the decisions among some names, in the names' order. */
    private List<Integer> decisionsAmong(List<String> names) {
        var decisions = new ArrayList<Integer>();
        for (var name : names) {
            var slot = slots.of(name);
            if (slots.isDecision(slot)) {
                decisions.add(slots.decisionAt(slot));
            }
        }
        return decisions;
    }

    // ---- decisions ----------------------------------------------------------------------------------

    /**
     * Checks that a decision requires decisions, input data and invocables the model has, each of its kind, and notes the
     * decisions it requires. One that requires an invocable that cannot be evaluated cannot be either, which is no new
     * issue: the invocable's is reported already.
     */
    private void checkRequirements(int d) {
        var decision = model.decisions().get(d);
        var who = "decision '%s'".formatted(decision.name());
        for (var name : decision.requiredDecisions()) {
            var slot = slots.of(name);
            if (!slots.isDecision(slot)) {
                graph.report(d, Kind.MISSING_REQUIREMENT, "%s requires decision '%s', which is %s".formatted(who, name, fault(slot, "a decision")));
            }
        }
        for (var name : decision.requiredInputs()) {
            var slot = slots.of(name);
            if (!slots.isInputData(slot)) {
                graph.report(d, Kind.MISSING_REQUIREMENT, "%s requires input data '%s', which is %s".formatted(who, name, fault(slot, "input data")));
            }
        }
        for (var name : decision.requiredKnowledge()) {
            var slot = slots.of(name);
            if (!slots.isInvocable(slot)) {
                graph.report(d, Kind.MISSING_REQUIREMENT, "%s requires business knowledge model '%s', which is %s"
                        .formatted(who, name, fault(slot, "a business knowledge model")));
            } else if (graph.knowledgeBlocked[slots.invocableAt(slot)] != null) {
                graph.block(d, "%s requires %s '%s', which cannot be evaluated: %s"
                        .formatted(who, slots.kindOf(slot), name, graph.knowledgeBlocked[slots.invocableAt(slot)]));
            }
        }
        graph.requiredDecisions[d] = GraphAnalysis.ints(decisionsAmong(decision.requiredDecisions()));
    }

    /**
     * Resolves each name the decision's logic reads among its requirements: the whole name if one is so named, else the
     * longest part before a dot that is, the rest being a path into its value ({@code Pricing.discount}). A name no
     * requirement provides is reported; one a requirement the model does not have (or of the other kind) provides is
     * left unresolved, that requirement being reported already.
     */
    private void resolveReads(int d) {
        var decision = model.decisions().get(d);
        var scope = localScope(decision.name(), decision.requirements());
        var valid = new HashSet<String>();
        decision.requiredDecisions().stream().filter(name -> slots.isDecision(slots.of(name))).forEach(valid::add);
        decision.requiredInputs().stream().filter(name -> slots.isInputData(slots.of(name))).forEach(valid::add);
        decision.requiredKnowledge().stream().filter(name -> slots.isInvocable(slots.of(name))).forEach(valid::add);
        var resolved = graph.reads.get(d);
        for (var name : namesRead(d, decision, scope)) {
            var requirement = DecisionModel.elementOf(name, scope::contains);
            if (requirement == null) {
                graph.report(d, Kind.MISSING_REQUIREMENT, ("decision '%s' reads '%s', which none of its information requirements provides "
                        + "(DMN 1.5, 7.3.4)").formatted(decision.name(), name));
            } else if (valid.contains(global(decision.name(), requirement))) {
                resolved.put(name, new Resolution(slots.of(global(decision.name(), requirement)), Resolution.pathOf(name, requirement), null));
            }
            // else a requirement the model does not have, or of the other kind, which is reported already
        }
    }

    /**
     * The names a decision's logic reads, each once, with {@code scope} in scope: a table's columns and what its cells
     * and expressions read, or the names an expression reads. It keeps the logic as it parses it, for the graph to
     * evaluate.
     *
     * @throws InvalidDecisionTableException if a cell or an expression of a decision table is invalid
     */
    private Set<String> namesRead(int d, Decision decision, Set<String> scope) {
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var type) -> {
                graph.tableReads[d] = TableReads.of(decision.name(), table, TableReads.scope(scope, table, model.types()));
                return graph.tableReads[d].names();
            }
            case DecisionLogic.NoLogic none -> {
                // a decision a person makes: no issue of the model, but nothing the graph evaluates (DMN 1.5, 6.3.7)
                graph.tableReads[d] = TableReads.NONE;
                graph.blocked[d] = "it has no decision logic; its value is given only to a decision service that takes it as an input decision";
                return Set.of();
            }
            default -> {
                graph.tableReads[d] = TableReads.NONE;
                parseExpression(d, decision.logic(), scope);
                return graph.expressions[d] == null ? Set.of() : new LinkedHashSet<>(graph.expressions[d].names());
            }
        }
    }

    /**
     * Parses a decision's literal expression, or composes its boxed expression ({@link FeelExpression#compose}), with the
     * names of its requirements in scope (DMN 1.5, 7.3.4 and 10.3.2.11), and the types the model defines; reports what it
     * uses that it cannot ({@link #reportUses}), and an expression that is not FEEL, or names a type that neither FEEL nor
     * the model defines ({@link Kind#INVALID_EXPRESSION}).
     */
    private void parseExpression(int d, DecisionLogic logic, Set<String> scope) {
        var decision = model.decisions().get(d).name();
        try {
            var tables = graph.embeddedTables.get(d);
            graph.expressions[d] = FeelExpression.compose(logic, FeelExpression.closedScope(scope, model.types()), site -> embed(site, tables));
        } catch (ExpressionSyntaxException e) {
            graph.report(d, Kind.INVALID_EXPRESSION, "decision '%s': %s".formatted(decision, e.getMessage()));
            return;
        }
        reportUses(graph.expressions[d], "decision", decision, "7.3.4", (kind, detail) -> graph.report(d, kind, detail));
    }

    // ---- what decisions and business knowledge models share -----------------------------------------

    /**
     * Reports what an element's logic uses that it cannot: an element of the model it invokes but does not require, a
     * requirement missing (a function nothing in the model names is no issue: it is null when invoked, with an error,
     * and its type always null), and what Praecepta does not evaluate (an external function).
     *
     * @param kind    the kind of element, as messages name it
     * @param section the section of DMN 1.5 that says what the element may read
     * @param report  reports an issue of the element
     */
    private void reportUses(FeelExpression expression, String kind, String element, String section, BiConsumer<Kind, String> report) {
        for (var invoked : expression.uninvocable()) {
            var slot = slots.of(global(element, invoked));
            if (slot != null) {
                report.accept(Kind.MISSING_REQUIREMENT, "%s '%s' invokes '%s', which is %s it does not require (DMN 1.5, %s)"
                        .formatted(kind, element, invoked, slots.kindOf(slot), section));
            }
        }
        for (var what : expression.unsupported()) {
            report.accept(Kind.UNSUPPORTED_FEATURE, "%s '%s' uses %s".formatted(kind, element, FeelExpression.Unsupported.describe(List.of(what))));
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

    /** What is wrong with a requirement that is not of the kind required, as messages end: {@code which is ...}. */
    private String fault(Integer slot, String kind) {
        return slot == null ? "not in the model" : slots.kindOf(slot) + ", not " + kind;
    }

    /**
     * The names an element's logic reads its requirements by: their names in its own model, which may be several (an
     * element of a model imported twice, with and without a name), or the requirements' own names.
     */
    private Set<String> localScope(String element, Collection<String> requirements) {
        var alias = aliases.get(element);
        if (alias == null) {
            return new LinkedHashSet<>(requirements);
        }
        var required = new HashSet<>(requirements);
        var scope = new LinkedHashSet<String>();
        alias.forEach((local, global) -> {
            if (required.contains(global)) {
                scope.add(local);
            }
        });
        requirements.stream().filter(requirement -> !alias.containsValue(requirement)).forEach(scope::add);
        return scope;
    }

    /** The name in the graph of what an element reads by {@code local}. */
    private String global(String element, String local) {
        var alias = aliases.get(element);
        return alias == null ? local : alias.getOrDefault(local, local);
    }
}
