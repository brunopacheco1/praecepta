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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel.Decision;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

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
     * the path does not exist in the requirement's type, else it is null.
     */
    record Resolution(int slot, String[] path, FeelType type, String error) {
    }

    /** How a decision table reads a name: as a column of {@code type}, or of lists of it, or as an input its cells compare with. */
    record ColumnRead(String name, ColumnType type, boolean list) {
    }

    final DecisionModel model;
    /** Each element's slot, by name: the input data, then the decisions. */
    final Map<String, Integer> slots = new HashMap<>();
    final int inputCount;
    /** Each slot's type: an input data's, declared or worked out from the tables that read it, or a decision's value's. */
    final FeelType[] types;
    /** By decision (index in the model): the names its logic reads, resolved; a name none of its requirements provides is left out. */
    final List<Map<String, Resolution>> reads = new ArrayList<>();
    /** By decision: the inputs its table's cells compare with whose values are lists, though the table reads single values. */
    final List<Set<String>> listedNamedInputs = new ArrayList<>();
    /** By decision: what its table reads (empty for a literal expression), read once, as it parses every cell. */
    private final List<List<ColumnRead>> tableReads = new ArrayList<>();
    /** By decision: the decisions it requires that the model has, as their indexes. */
    private final List<int[]> requiredDecisions = new ArrayList<>();
    /** By decision: why it cannot be evaluated, or null if it can. */
    final String[] blocked;
    /** By decision: how its literal expression's value is bound to it; as it is for a decision table. */
    final GraphValues.Binding[] bindings;
    /** The decisions that can be ordered, required ones first, as their indexes: those in no cycle and requiring none in one. */
    final int[] order;
    private final List<Issue> issues = new ArrayList<>();
    /** The reads, as {@code decision:name}, of an input data without a declared type that disagree with an earlier one, reported once. */
    private final Set<String> conflicting = new HashSet<>();

    GraphAnalysis(DecisionModel model) {
        this.model = model;
        var decisions = model.decisions();
        inputCount = model.inputData().size();
        types = new FeelType[inputCount + decisions.size()];
        blocked = new String[decisions.size()];
        bindings = new GraphValues.Binding[decisions.size()];
        Arrays.fill(bindings, GraphValues.Binding.AS_IS);
        for (int i = 0; i < inputCount; i++) {
            var input = model.inputData().get(i);
            slots.put(input.name(), i);
            types[i] = input.type();
        }
        for (int d = 0; d < decisions.size(); d++) {
            slots.put(decisions.get(d).name(), inputCount + d);
        }
        for (int d = 0; d < decisions.size(); d++) {
            checkRequirements(d);
            resolveReads(d);
        }
        var cyclic = findCycles();
        order = topologicalOrder(cyclic);
        inferInputTypes();
        typeDecisions();
        checkTableReads();
        block(cyclic);
    }

    /** The issues found: of requirements and names, then cycles, then types, each in the model's order of decisions. */
    List<Issue> issues() {
        return List.copyOf(issues);
    }

    /** The indexes of the decisions a decision requires that the model has. */
    int[] requiredDecisions(int d) {
        return requiredDecisions.get(d).clone();
    }

    /** What the decision's table reads: its columns, then the inputs its cells compare with; empty for a literal expression. */
    List<ColumnRead> tableReads(int d) {
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
            } else if (slot < inputCount) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires decision '%s', which is input data, not a decision"
                        .formatted(decision.name(), name));
            } else {
                required.add(slot - inputCount);
            }
        }
        for (var name : decision.requiredInputs()) {
            var slot = slots.get(name);
            if (slot == null) {
                report(d, Kind.MISSING_REQUIREMENT,
                        "decision '%s' requires input data '%s', which is not in the model".formatted(decision.name(), name));
            } else if (slot >= inputCount) {
                report(d, Kind.MISSING_REQUIREMENT, "decision '%s' requires input data '%s', which is a decision, not input data"
                        .formatted(decision.name(), name));
            }
        }
        requiredDecisions.add(required.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Resolves each name the decision's logic reads among its requirements: the whole name if one is so named, else the
     * longest part before a dot that is, the rest being a path into its value ({@code Pricing.discount}). A name no
     * requirement provides is reported; one a requirement the model does not have (or of the other kind) provides is
     * left unresolved, that requirement being reported already.
     */
    private void resolveReads(int d) {
        var decision = model.decisions().get(d);
        var scope = new HashSet<>(decision.requirements());
        var valid = new HashSet<String>();
        decision.requiredDecisions().stream().filter(name -> isDecision(slots.get(name))).forEach(valid::add);
        decision.requiredInputs().stream().filter(name -> isInputData(slots.get(name))).forEach(valid::add);
        var resolved = new LinkedHashMap<String, Resolution>();
        var names = new LinkedHashSet<String>();
        switch (decision.logic()) {
            case DecisionLogic.Table(var table) -> {
                var columnReads = columnReads(decision.name(), table);
                tableReads.add(columnReads);
                columnReads.forEach(read -> names.add(read.name()));
            }
            case DecisionLogic.LiteralExpression literal -> {
                tableReads.add(List.of());
                names.addAll(literal.expression().names());
            }
        }
        for (var name : names) {
            var requirement = requirementOf(name, scope);
            if (requirement == null) {
                report(d, Kind.MISSING_REQUIREMENT, ("decision '%s' reads '%s', which none of its information requirements provides "
                        + "(DMN 1.5, 7.3.4)%s").formatted(decision.name(), name, operatorHint(name)));
                continue;
            }
            if (!valid.contains(requirement)) {
                continue; // a requirement the model does not have, or of the other kind, which is reported already
            }
            var path = requirement.length() == name.length() ? new String[0] : name.substring(requirement.length() + 1).split("\\.");
            resolved.put(name, new Resolution(slots.get(requirement), path, null, null));
        }
        reads.add(resolved);
        listedNamedInputs.add(new LinkedHashSet<>());
    }

    /** FEEL's operators that are words, which a name may also hold: {@code A and B} reads as one name in the subset. */
    private static final Pattern FEEL_WORD = Pattern.compile("(?:^|\\s)(and|or|in|between|instance of|satisfies)(?:\\s|$)");

    /**
     * For a name no requirement provides that holds a word FEEL uses as an operator ({@code A and B}), a hint that FEEL's
     * operator is not in Praecepta's expression subset yet; empty otherwise.
     */
    private static String operatorHint(String name) {
        var word = FEEL_WORD.matcher(name);
        return word.find() ? "; if '%s' is meant as FEEL's operator, the full FEEL language is planned for 0.8 (#116)".formatted(word.group(1))
                : "";
    }

    private boolean isDecision(Integer slot) {
        return slot != null && slot >= inputCount;
    }

    private boolean isInputData(Integer slot) {
        return slot != null && slot < inputCount;
    }

    /** The requirement that provides a name: the name itself, or its longest part before a dot; null if none. */
    private static String requirementOf(String name, Set<String> scope) {
        if (scope.contains(name)) {
            return name;
        }
        for (int dot = name.lastIndexOf('.'); dot > 0; dot = name.lastIndexOf('.', dot - 1)) {
            var prefix = name.substring(0, dot);
            if (scope.contains(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /** The names a decision's logic reads: a table's columns and the inputs its cells compare with, or a literal expression's names. */
    static Set<String> namesRead(Decision decision) {
        var names = new LinkedHashSet<String>();
        switch (decision.logic()) {
            case DecisionLogic.Table(var table) -> columnReads(decision.name(), table).forEach(read -> names.add(read.name()));
            case DecisionLogic.LiteralExpression literal -> names.addAll(literal.expression().names());
        }
        return names;
    }

    /** What a table reads: its columns, then the inputs its cells compare with. */
    private static List<ColumnRead> columnReads(String decision, DecisionTable table) {
        var columnReads = new ArrayList<ColumnRead>();
        table.inputs().forEach(column -> columnReads.add(new ColumnRead(column.name(), column.type(), table.listInputs().contains(column.name()))));
        Map<String, ColumnType> named;
        try {
            named = table.namedInputs();
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("Decision '%s': %s".formatted(decision, e.getMessage()));
        }
        named.forEach((name, type) -> columnReads.add(new ColumnRead(name, type, table.listInputs().contains(name))));
        return columnReads;
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
                if (component.contains(r) && !previous.containsKey(r)) {
                    previous.put(r, d);
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
            for (var read : tableReads.get(d)) {
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
        for (int d : order) {
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

    private void typeDecision(int d) {
        locateReads(d);
        var decision = model.decisions().get(d);
        types[inputCount + d] = switch (decision.logic()) {
            case DecisionLogic.Table(var table) -> tableType(table);
            case DecisionLogic.LiteralExpression literal -> literalType(d, literal);
        };
    }

    /** The type of each name the decision reads, at its path in the value of the requirement that provides it. */
    private void locateReads(int d) {
        reads.get(d).replaceAll((name, resolution) -> {
            var path = resolution.path();
            var type = types[resolution.slot()];
            var at = rootName(name, path);
            for (var component : path) {
                var next = component(type, component);
                if (next == null) {
                    var error = "'%s' is a %s, which has no component '%s'".formatted(at, type.feelName(), component);
                    return new Resolution(resolution.slot(), path, FeelType.ANY, error);
                }
                type = next;
                at = at + "." + component;
            }
            return new Resolution(resolution.slot(), path, type, null);
        });
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
     * The type of a component of a value of {@code type}: a context's component, and of a list of contexts the list of
     * their components (DMN 1.5, 10.3.2.5); any type in a value of any type; null if there is no such component.
     */
    private static FeelType component(FeelType type, String name) {
        return switch (type) {
            case FeelType.Context(var components) -> components.get(name);
            case FeelType.ListOf(FeelType.Context(var components)) -> components.containsKey(name) ? new FeelType.ListOf(components.get(name)) : null;
            case FeelType.ListOf(FeelType.Any any) -> new FeelType.ListOf(FeelType.ANY);
            case FeelType.Any any -> FeelType.ANY;
            default -> null;
        };
    }

    /**
     * The type of a decision table's value: its one output's, or a context of its outputs; a list of that under a hit
     * policy with several results and no aggregation; a number for COUNT (DMN 1.5, 10.3.2.10).
     */
    static FeelType tableType(DecisionTable table) {
        if (table.aggregation() == AggregationOperator.COUNT) {
            return FeelType.of(ColumnType.NUMBER);
        }
        FeelType output;
        if (table.outputs().size() == 1) {
            output = FeelType.of(table.outputs().getFirst().type());
        } else {
            var components = new LinkedHashMap<String, FeelType>();
            table.outputs().forEach(column -> components.put(column.name(), FeelType.of(column.type())));
            output = new FeelType.Context(components);
        }
        return table.hitPolicy().isSingleHit() || table.aggregation() != null ? output : new FeelType.ListOf(output);
    }

    private static FeelType declaredType(Decision decision) {
        return switch (decision.logic()) {
            case DecisionLogic.Table(var table) -> tableType(table);
            case DecisionLogic.LiteralExpression literal -> literal.type();
        };
    }

    /**
     * The type of a literal expression's value, from the types of the names it reads, each of which must be a single
     * string, number, date, time or duration, checked against the type its decision declares; the declared type, or
     * any type, where it cannot be worked out.
     */
    private FeelType literalType(int d, DecisionLogic.LiteralExpression literal) {
        var decision = model.decisions().get(d);
        var operands = new HashMap<String, ColumnType>();
        boolean known = true;
        for (var read : reads.get(d).entrySet()) {
            var resolution = read.getValue();
            if (resolution.error() != null) {
                report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', but %s".formatted(decision.name(), read.getKey(), resolution.error()));
                known = false;
            } else if (resolution.type() instanceof FeelType.Simple(var type)) {
                operands.put(read.getKey(), type);
            } else {
                report(d, Kind.TYPE_MISMATCH, (resolution.type() instanceof FeelType.Any
                        ? "decision '%s' reads '%s', whose type is not declared; its literal expression needs the type of every name it reads"
                        : "decision '%s' reads '%s', a %s; its literal expression computes with single strings, numbers, dates, times and durations")
                        .formatted(decision.name(), read.getKey(), resolution.type().feelName()));
                known = false;
            }
        }
        known &= operands.keySet().containsAll(literal.expression().names());
        if (!known) {
            return literal.type();
        }
        ColumnType type;
        try {
            type = literal.expression().type(operands::get);
        } catch (ExpressionTypeException e) {
            report(d, Kind.TYPE_MISMATCH,
                    "decision '%s': literal expression '%s' %s".formatted(decision.name(), literal.text().strip(), e.getMessage()));
            return literal.type();
        }
        return checkDeclared(d, literal, type);
    }

    /**
     * The type of the decision whose literal expression gives values of {@code type}, and how they are bound to it (DMN
     * 1.5, 10.3.2.9.4, binding contexts): as they are, to the type the decision declares or to their own if it declares
     * none; a date converted to a date and time at UTC midnight, or a value to a list of one, where the decision declares
     * that; and otherwise as null, the value not conforming to the declared type, which is also reported, as a type
     * mismatch, though the decision is evaluated.
     */
    private FeelType checkDeclared(int d, DecisionLogic.LiteralExpression literal, ColumnType type) {
        var declared = literal.type();
        if (declared instanceof FeelType.Any) {
            return FeelType.of(type);
        }
        if (declared.equals(FeelType.of(type))) {
            return declared;
        }
        if (type == ColumnType.DATE && declared.equals(FeelType.of(ColumnType.DATE_TIME))) {
            bindings[d] = GraphValues.Binding.DATE_TO_DATE_TIME;
        } else if (declared.equals(FeelType.listOf(type))) {
            bindings[d] = GraphValues.Binding.SINGLETON_LIST;
        } else {
            bindings[d] = GraphValues.Binding.NULL;
            issues.add(new Issue(Kind.TYPE_MISMATCH, List.of(), ("decision '%s' declares a %s, but its literal expression '%s' gives a %s, "
                    + "so its value is null (DMN 1.5, 10.3.2.9.4)").formatted(model.decisions().get(d).name(), declared.feelName(),
                    literal.text().strip(), type.feelName()), Map.of()));
        }
        return declared;
    }

    /** Checks that every value a decision table reads fits the column, or the input its cells compare with, that reads it. */
    private void checkTableReads() {
        for (int d = 0; d < model.decisions().size(); d++) {
            var decision = model.decisions().get(d);
            if (!(decision.logic() instanceof DecisionLogic.Table(var table))) {
                continue;
            }
            var columns = table.inputs().stream().map(DecisionTable.InputColumn::name).collect(Collectors.toSet());
            for (var read : tableReads.get(d)) {
                var resolution = reads.get(d).get(read.name());
                if (resolution != null && !conflicting.contains(d + ":" + read.name())) {
                    checkTableRead(d, read, resolution, columns.contains(read.name()));
                }
            }
        }
    }

    private void checkTableRead(int d, ColumnRead read, Resolution resolution, boolean column) {
        var decision = model.decisions().get(d).name();
        if (resolution.error() != null) {
            report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', but %s".formatted(decision, read.name(), resolution.error()));
            return;
        }
        var source = resolution.type();
        boolean single = source instanceof FeelType.Simple(var type) && type == read.type() || source instanceof FeelType.Any;
        boolean list = source.equals(FeelType.listOf(read.type())) || source.equals(new FeelType.ListOf(FeelType.ANY));
        if (single || read.list() && list) {
            return;
        }
        if (list && !column) {
            listedNamedInputs.get(d).add(read.name()); // a cell names a list: the value is in it, as with an input data of lists
            return;
        }
        var expected = read.list() ? FeelType.listOf(read.type()) : FeelType.of(read.type());
        report(d, Kind.TYPE_MISMATCH, "decision '%s' reads '%s', a %s, into its %s '%s', a %s".formatted(decision, read.name(), source.feelName(),
                column ? "input" : "cells' comparisons with", read.name(), expected.feelName()));
    }

    // ---- what cannot be evaluated ------------------------------------------------------------------

    private void report(int d, Kind kind, String detail) {
        issues.add(new Issue(kind, List.of(), detail, Map.of()));
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
