/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.GraphAnalysis.Resolution;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The typing phase of a {@link GraphAnalysis}: works out the type of every slot ({@link #workOut}), the type of each
 * name a decision reads, at its path in the value of the requirement that provides it, and how each decision's value is
 * bound to the type the decision declares (DMN 1.5, 10.3.2.9.4). It needs the reads resolved and the decisions ordered.
 * It reports an input data that two tables read as two types ({@link Kind#TYPE_MISMATCH}), a path a type does not have,
 * and a decision whose value is null whatever its inputs ({@link Kind#ALWAYS_NULL}).
 */
final class GraphTypes {

    /** The properties FEEL gives dates, times, date-times and durations (DMN 1.5, Table 65), which a path reads. */
    private static final Set<String> TEMPORAL_PROPERTIES = Set.of("year", "month", "day", "weekday", "hour", "minute", "second",
            "time offset", "timezone", "years", "months", "days", "hours", "minutes", "seconds");

    private final GraphAnalysis graph;
    private final DecisionModel model;
    private final GraphSlots slots;
    private final FeelType[] types;

    GraphTypes(GraphAnalysis graph) {
        this.graph = graph;
        this.model = graph.model;
        this.slots = graph.slots;
        this.types = graph.types;
    }

    /** Types every slot: the input data, then the business knowledge models, the decisions and the decision services. */
    void workOut() {
        for (int i = 0; i < slots.inputCount; i++) {
            types[i] = model.inputData().get(i).type();
        }
        inferInputTypes();
        for (int k = 0; k < slots.serviceCount; k++) {
            // until the decisions are typed, what invokes a decision service reads it as its declared type, or any function
            types[slots.ofService(k)] = model.decisionServices().get(k).type();
        }
        typeKnowledge();
        typeDecisions();
        for (int k = 0; k < slots.serviceCount; k++) {
            typeService(k);
        }
    }

    // ---- input data ---------------------------------------------------------------------------------

    /**
     * Gives an input data the model declares no type for the type of the decision table inputs that read it whole, as
     * long as they agree; where they do not, the later reader has a type mismatch.
     */
    private void inferInputTypes() {
        var inferredBy = new HashMap<Integer, String>();
        for (int d = 0; d < slots.decisionCount; d++) {
            var decision = model.decisions().get(d);
            for (var read : graph.tableReads[d].columns()) {
                var resolution = graph.reads.get(d).get(read.name());
                if (resolution == null || resolution.path().length > 0 || !slots.isInputData(resolution.slot())
                        || !(model.inputData().get(resolution.slot()).type() instanceof FeelType.Any)) {
                    continue;
                }
                var type = read.list() ? FeelType.listOf(read.type()) : FeelType.of(read.type());
                int slot = resolution.slot();
                if (types[slot] instanceof FeelType.Any) {
                    types[slot] = type;
                    inferredBy.put(slot, decision.name());
                } else if (!types[slot].equals(type)) {
                    var detail = "input data '%s' declares no type, and decision '%s' reads it as a %s, but decision '%s' as a %s"
                            .formatted(read.name(), inferredBy.get(slot), types[slot].feelName(), decision.name(), type.feelName());
                    graph.report(d, Kind.TYPE_MISMATCH, detail);
                    graph.conflicting.add(d + ":" + read.name());
                }
            }
        }
    }

    // ---- business knowledge models -----------------------------------------------------------------

    /**
     * Types every business knowledge model as the function its logic defines, each from the types of those it invokes,
     * in the order they require one another; one that invokes itself, directly or not, reads its own type as any type.
     * The models being typed are on a stack of its own, not the thread's, so a chain of any length is typed: a model
     * that reads one not typed yet waits on the stack while that one is typed, and is then typed again from the start.
     */
    private void typeKnowledge() {
        var state = new int[slots.knowledgeCount]; // 0: not typed, 1: being typed, 2: typed
        var typing = new ArrayDeque<Integer>();
        for (int first = 0; first < state.length; first++) {
            if (state[first] == 0) {
                state[first] = 1;
                typing.push(first);
            }
            while (!typing.isEmpty()) {
                int k = typing.peek();
                int untyped = typeKnowledge(k, state);
                if (untyped < 0) {
                    state[k] = 2;
                    typing.pop();
                } else {
                    state[untyped] = 1;
                    typing.push(untyped);
                }
            }
        }
    }

    /**
     * Types a business knowledge model from the types of those it reads, unless it reads one that is not typed yet.
     *
     * @return the first business knowledge model it reads that is not typed yet, by index, which is to be typed before
     *         it; -1 if it read none, and so is typed
     */
    private int typeKnowledge(int k, int[] state) {
        var expression = graph.knowledgeExpressions[k];
        var declared = model.knowledgeModels().get(k).logic().type();
        FeelType type = declared;
        if (expression != null && graph.knowledgeBlocked[k] == null) {
            var reads = graph.knowledgeReads.get(k);
            var untyped = new int[] {-1};
            var computed = expression.type(name -> {
                var read = reads.get(name);
                if (read == null) {
                    return null;
                }
                if (slots.isService(read)) {
                    return FeelType.ANY; // typed after the decisions, so a business knowledge model reads it as any function
                }
                int invoked = slots.invocableAt(read);
                if (state[invoked] == 2) {
                    return types[slots.ofKnowledge(invoked)];
                }
                if (state[invoked] == 0 && untyped[0] < 0) {
                    untyped[0] = invoked;
                }
                return FeelType.ANY; // itself, or one that waits for it: a cycle; or one not typed yet, and this type is dropped
            });
            if (untyped[0] >= 0) {
                return untyped[0];
            }
            type = declared instanceof FeelType.Any ? computed : declared;
        }
        types[slots.ofKnowledge(k)] = type;
        return -1;
    }

    // ---- decisions ----------------------------------------------------------------------------------

    /**
     * Works out each decision's type, required decisions first, and resolves the type of every name each decision
     * reads; a decision in a cycle reads values whose types may not be known yet, which do not matter, as it is never
     * evaluated.
     */
    private void typeDecisions() {
        var typed = new boolean[slots.decisionCount];
        for (int d : typingOrder()) {
            typeDecision(d);
            typed[d] = true;
        }
        for (int d = 0; d < typed.length; d++) {
            if (!typed[d]) {
                types[slots.ofDecision(d)] = declaredType(model.decisions().get(d));
            }
        }
        for (int d = 0; d < typed.length; d++) {
            if (!typed[d]) {
                locateReads(d);
            }
        }
    }

    /**
     * The order the decisions in no cycle are typed in: the graph's order, but each decision that invokes a decision
     * service after the decisions the service evaluates, so that it reads the service's type, unless they invoke it in
     * turn. Worked out with a stack of its own.
     */
    private int[] typingOrder() {
        var inOrder = new boolean[slots.decisionCount];
        for (int d : graph.order) {
            inOrder[d] = true;
        }
        var state = new int[slots.decisionCount]; // 0: not visited, 1: being visited, 2: placed
        var result = new ArrayList<Integer>();
        for (int start : graph.order) {
            var stack = new ArrayDeque<int[]>(); // decision, and whether its predecessors were pushed
            stack.push(new int[] {start, 0});
            while (!stack.isEmpty()) {
                var top = stack.peek();
                int d = top[0];
                if (state[d] == 2) {
                    stack.pop();
                } else if (top[1] == 1) {
                    stack.pop();
                    state[d] = 2;
                    result.add(d);
                } else {
                    top[1] = 1;
                    state[d] = 1;
                    for (int before : typedBefore(d)) {
                        if (state[before] == 0 && inOrder[before]) {
                            stack.push(new int[] {before, 0});
                        }
                    }
                }
            }
        }
        return GraphAnalysis.ints(result);
    }

    /** The decisions typed before a decision: those it requires, then those the decision services it requires evaluate. */
    private List<Integer> typedBefore(int d) {
        var before = new ArrayList<Integer>();
        for (int required : graph.requiredDecisions[d]) {
            before.add(required);
        }
        for (int service : requiredServices(d)) {
            for (int evaluated : graph.serviceDecisions[service]) {
                before.add(evaluated);
            }
        }
        return before;
    }

    /** The decision services a decision requires, by index. */
    private List<Integer> requiredServices(int d) {
        var services = new ArrayList<Integer>();
        for (var name : model.decisions().get(d).requiredKnowledge()) {
            var slot = slots.of(name);
            if (slots.isService(slot)) {
                services.add(slots.serviceAt(slot));
            }
        }
        return services;
    }

    private void typeDecision(int d) {
        requiredServices(d).forEach(this::typeService); // their outputs, which do not require this decision, are typed already
        locateReads(d);
        var decision = model.decisions().get(d);
        types[slots.ofDecision(d)] = switch (decision.logic()) {
            case DecisionLogic.Table(var table, var declared) -> bind(d, declared, valueType(table, declared), "decision table");
            default -> expressionType(d, decision.logic());
        };
    }

    /** The type of each name the decision reads, at its path in the value of the requirement that provides it. */
    private void locateReads(int d) {
        graph.reads.get(d).replaceAll((name, resolution) -> {
            var path = resolution.path();
            // a decision service not typed yet reads as any type
            var type = types[resolution.slot()] == null ? FeelType.ANY : types[resolution.slot()];
            var at = new StringBuilder(resolution.root(name));
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

    /** The type of a decision that cannot be ordered, so is not typed from what it reads: the type it declares, or its table's. */
    private static FeelType declaredType(Decision decision) {
        return switch (decision.logic()) {
            case DecisionLogic.Table(var table, var declared) -> declared instanceof FeelType.Any ? tableType(table) : declared;
            default -> decision.logic().type();
        };
    }

    /**
     * The type of a literal expression's value, or a boxed expression's, bound to the type its decision declares
     * ({@link #bind}): its expression's, as FEEL types it from the types of the names it reads (DMN 1.5, 10.3.2.9),
     * {@link FeelType#ANY} where only the values tell. An expression whose value is null whatever its inputs (operands
     * FEEL does not combine, such as a string and a number, a path to a component its type does not have) is reported
     * ({@link Kind#ALWAYS_NULL}), and evaluated: FEEL gives null there rather than fail.
     */
    private FeelType expressionType(int d, DecisionLogic decisionLogic) {
        var expression = graph.expressions[d];
        checkPaths(d);
        if (graph.blocked[d] != null || expression == null) {
            return decisionLogic.type(); // reported already: a name none of its requirements provides, or what Praecepta does not evaluate
        }
        var logic = decisionLogic instanceof DecisionLogic.LiteralExpression ? "literal expression '%s'".formatted(expression.text().strip())
                : expression.text();
        var reasons = new ArrayList<String>();
        var resolved = graph.reads.get(d);
        var type = expression.type(name -> resolved.containsKey(name) ? resolved.get(name).type() : null, reasons::add);
        if (type instanceof FeelType.Null) {
            graph.bindings[d] = GraphValues.Binding.NULL;
            var reason = reasons.isEmpty() ? "it is null" : reasons.getFirst();
            graph.issue(Kind.ALWAYS_NULL, "decision '%s': in %s, %s; its value is null (DMN 1.5, 10.3.2.15)"
                    .formatted(model.decisions().get(d).name(), logic, reason));
            return decisionLogic.type();
        }
        return bind(d, decisionLogic.type(), type, logic);
    }

    /** Reports each name a literal expression reads by a path into a requirement's value that its type does not have. */
    private void checkPaths(int d) {
        var decision = model.decisions().get(d).name();
        for (var read : graph.reads.get(d).entrySet()) {
            var resolution = read.getValue();
            if (resolution.error() != null) {
                graph.report(d, resolution.unsupported() ? Kind.UNSUPPORTED_FEATURE : Kind.TYPE_MISMATCH,
                        "decision '%s' reads '%s', but %s".formatted(decision, read.getKey(), resolution.error()));
            }
        }
    }

    /**
     * The type of a decision whose logic gives values of type {@code actual}, and how they are bound to it (DMN 1.5,
     * 10.3.2.9.4, binding contexts): as they are if the decision declares no type, or one they conform to (10.3.2.9.2,
     * {@link FeelType#conformsTo}), keeping their own; a date converted to a date and time at UTC midnight, a value to a
     * list of one, or a list of one to its value, where the decision declares that; checked when the decision is
     * evaluated where the values' type is known only then; and otherwise as null, the values not conforming to the
     * declared type, which is reported ({@link Kind#ALWAYS_NULL}), though the decision is evaluated.
     *
     * @param logic the decision's logic, as the report names it
     */
    private FeelType bind(int d, FeelType declared, FeelType actual, String logic) {
        if (actual.conformsTo(declared)) {
            return actual;
        }
        if (actual.equals(FeelType.of(ColumnType.DATE)) && declared.equals(FeelType.of(ColumnType.DATE_TIME))) {
            graph.bindings[d] = GraphValues.Binding.DATE_TO_DATE_TIME;
            return declared;
        }
        if (declared instanceof FeelType.ListOf(var element) && actual.conformsTo(element)) {
            graph.bindings[d] = GraphValues.Binding.SINGLETON_LIST;
            return new FeelType.ListOf(actual);
        }
        if (actual instanceof FeelType.ListOf(var element) && element.conformsTo(declared)) {
            graph.bindings[d] = GraphValues.Binding.FROM_SINGLETON_LIST;
            return element;
        }
        if (holdsAny(actual)) {
            graph.bindings[d] = GraphValues.Binding.CHECKED;
            return declared;
        }
        graph.bindings[d] = GraphValues.Binding.NULL;
        graph.issue(Kind.ALWAYS_NULL, "decision '%s' declares a %s, but its %s gives a %s, so its value is null (DMN 1.5, 10.3.2.9.4)"
                .formatted(model.decisions().get(d).name(), declared.feelName(), logic, actual.feelName()));
        return declared;
    }

    /** Whether a type is, or holds, any type, so that whether its values conform is known only from them. */
    private static boolean holdsAny(FeelType type) {
        return switch (type) {
            case FeelType.Any any -> true;
            case FeelType.ListOf(var element) -> holdsAny(element);
            case FeelType.RangeOf(var element) -> holdsAny(element);
            case FeelType.Context(var components) -> components.values().stream().anyMatch(GraphTypes::holdsAny);
            default -> false;
        };
    }

    // ---- decision services --------------------------------------------------------------------------

    /**
     * Types a decision service as a function of its parameters' types, giving its one output decision's type, or a
     * context of its output decisions' types, once those are typed; one whose variable declares a function type has that
     * type. A decision that invokes it, typed before, reads it as its declared type.
     */
    private void typeService(int k) {
        var service = model.decisionServices().get(k);
        int slot = slots.ofService(k);
        if (service.type() instanceof FeelType.FunctionOf) {
            types[slot] = service.type();
            return;
        }
        var outputs = graph.serviceOutputs[k];
        for (int d : outputs) {
            if (types[slots.ofDecision(d)] == null) {
                return;
            }
        }
        var parameters = new ArrayList<FeelType>();
        for (int parameter : graph.serviceParameters[k]) {
            parameters.add(parameter < 0 || types[parameter] == null ? FeelType.ANY : types[parameter]);
        }
        FeelType result;
        if (outputs.length == 1) {
            result = types[slots.ofDecision(outputs[0])];
        } else {
            var components = new LinkedHashMap<String, FeelType>();
            for (int d : outputs) {
                components.put(model.decisions().get(d).name(), types[slots.ofDecision(d)]);
            }
            result = new FeelType.Context(components);
        }
        types[slot] = new FeelType.FunctionOf(parameters, result);
    }
}
