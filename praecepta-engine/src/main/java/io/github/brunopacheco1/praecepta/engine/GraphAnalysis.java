/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The static analysis of a decision model, done once when a {@link DecisionGraph} is built: each element gets a slot
 * ({@link GraphSlots}), each name a decision's logic reads is resolved to the slot of one of its information
 * requirements and a path into its value, the types of the elements are worked out, and what is wrong is reported
 * (missing requirements, cycles, type mismatches), together with the decisions that therefore cannot be evaluated.
 *
 * <p>This class holds what the analysis finds, in its fields, and runs the analysis, in its constructor: a numbered
 * sequence of phases, each a class of its own that reads and fills these fields. {@link GraphRequirements} checks what
 * each element requires and resolves the names its logic reads, {@link GraphCycles} finds the cycles and orders the
 * decisions, {@link GraphTypes} works out the types, {@link TableReadChecks} checks that the values a decision table
 * reads fit its columns, and {@link GraphBlocking} finds what cannot be evaluated because of what it requires. The
 * issues are reported in the order of the phases, so the order of the phases is part of the validation report.
 *
 * <p>The fields are indexed as their comments say: by slot, by decision (its index in the model), by business knowledge
 * model or decision service (its index in the model), or by invocable (a business knowledge model's index, or the number
 * of business knowledge models plus a decision service's index).
 */
final class GraphAnalysis {

    /**
     * A name a decision's logic reads, resolved: the slot of the requirement that provides it, the path into that
     * requirement's value, and the type of the value there ({@link FeelType#ANY} when unknown); {@code error} says why
     * the path does not exist in the requirement's type, else it is null, and {@code unsupported} whether the path
     * exists in FEEL but a decision table's column does not read it (a property of a date, {@code Birth.year}).
     */
    record Resolution(int slot, String[] path, FeelType type, String error, boolean unsupported) {

        Resolution(int slot, String[] path, FeelType type) {
            this(slot, path, type, null, false);
        }

        /** The path a name reads into the element it names: what follows the element's name, none for the name itself. */
        static String[] pathOf(String name, String element) {
            return element.length() == name.length() ? new String[0] : name.substring(element.length() + 1).split("\\.");
        }

        /** The requirement's name in a name that reads this path into it. */
        String root(String name) {
            int length = name.length();
            for (var component : path) {
                length -= component.length() + 1;
            }
            return name.substring(0, length);
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

    /** A column that reads a property of a date, time or duration: the FEEL expression of its name, and the requirement it reads. */
    record PropertyColumn(FeelExpression expression, String root, int slot) {
    }

    final DecisionModel model;
    final GraphSlots slots;
    /** Each slot's type: an input data's, declared or worked out from the tables that read it, or a decision's value's. */
    final FeelType[] types;

    // ---- the decisions --------------------------------------------------------------------------------

    /** By decision: the decisions it requires that the model has, as their indexes. */
    final int[][] requiredDecisions;
    /** By decision: the names its logic reads, resolved; a name none of its requirements provides is left out. */
    final List<Map<String, Resolution>> reads;
    /** By decision: its literal expression, parsed once with its requirements' names in scope; null for a decision table. */
    final FeelExpression[] expressions;
    /** By decision: what its table reads (nothing for a literal expression), read once, as it parses every cell. */
    final TableReads[] tableReads;
    /** By decision: the decision tables within its boxed expression, each with its validation report. */
    final List<List<GraphNodes.EmbeddedTable>> embeddedTables;
    /** By decision: the inputs its table's cells compare with whose values are lists, though the table reads single values. */
    final List<Set<String>> listedNamedInputs;
    /**
     * By decision: its table's columns that read a property of a date, time or duration ({@code Birth.year}), which the
     * table computes as FEEL reads them.
     */
    final List<Map<String, PropertyColumn>> propertyColumns;
    /** By decision: how its logic's value is bound to it (DMN 1.5, 10.3.2.9.4). */
    final GraphValues.Binding[] bindings;
    /** By decision: why it cannot be evaluated, or null if it can. */
    final String[] blocked;
    /** The decisions that can be ordered, required ones first, as their indexes: those in no cycle and requiring none in one. */
    final int[] order;

    // ---- the invocables: business knowledge models and decision services ------------------------------

    /**
     * By business knowledge model: its function definition, composed with the names of the business knowledge models it
     * requires in scope; null where it is not FEEL.
     */
    final FeelExpression[] knowledgeExpressions;
    /** By business knowledge model: the slot of each business knowledge model its logic reads, by name. */
    final List<Map<String, Integer>> knowledgeReads;
    /** By business knowledge model: the decision tables within its logic, each with its validation report. */
    final List<List<GraphNodes.EmbeddedTable>> knowledgeTables;
    /** By invocable: why it cannot be evaluated, or null if it can. */
    final String[] knowledgeBlocked;
    /** By decision service: the slots of its parameters, its input data then its input decisions; -1 for one the model has not. */
    final int[][] serviceParameters;
    /** By decision service: the decisions it evaluates, by index, required ones first; its input decisions are not among them. */
    final int[][] serviceDecisions;
    /** By decision service: its output decisions, by index. */
    final int[][] serviceOutputs;

    // ---- what the phases report -----------------------------------------------------------------------

    private final List<Issue> issues = new ArrayList<>();
    /**
     * The reads, as {@code decision:name}, of an input data without a declared type that disagree with an earlier one:
     * {@link GraphTypes} reports each once, so {@link TableReadChecks} does not report it again.
     */
    final Set<String> conflicting = new HashSet<>();

    /**
     * @param log     whether the engines of the decision tables within boxed expressions log their validation reports
     * @param aliases by element, the names its own model reads, each to its element's name in the graph, where the model
     *                is one with the models it imports ({@link ImportedModels}); none for an element whose names are those
     */
    GraphAnalysis(DecisionModel model, boolean log, Map<String, Map<String, String>> aliases) {
        this.model = model;
        slots = new GraphSlots(model);
        int decisions = slots.decisionCount;
        types = new FeelType[slots.count()];
        requiredDecisions = new int[decisions][];
        reads = each(decisions, LinkedHashMap::new);
        expressions = new FeelExpression[decisions];
        tableReads = new TableReads[decisions];
        embeddedTables = each(decisions, ArrayList::new);
        listedNamedInputs = each(decisions, LinkedHashSet::new);
        propertyColumns = each(decisions, LinkedHashMap::new);
        bindings = new GraphValues.Binding[decisions];
        Arrays.fill(bindings, GraphValues.Binding.AS_IS);
        blocked = new String[decisions];
        knowledgeExpressions = new FeelExpression[slots.knowledgeCount];
        knowledgeReads = each(slots.knowledgeCount, LinkedHashMap::new);
        knowledgeTables = each(slots.knowledgeCount, ArrayList::new);
        knowledgeBlocked = new String[slots.knowledgeCount + slots.serviceCount];
        serviceParameters = new int[slots.serviceCount][];
        serviceDecisions = new int[slots.serviceCount][];
        serviceOutputs = new int[slots.serviceCount][];

        // 1. what the business knowledge models and decision services require, read and evaluate; a decision's issue
        // says why an invocable it requires cannot be evaluated, so those are known before the decisions are checked
        var requirements = new GraphRequirements(this, log, aliases);
        requirements.checkInvocables();
        var blocking = new GraphBlocking(this);
        blocking.blockKnowledge();

        // 2. what each decision requires, and where each name its logic reads is
        requirements.checkDecisions();

        // 3. the cycles of required decisions, and the order of the other decisions
        var cycles = new GraphCycles(requiredDecisions, model.decisions());
        cycles.found.forEach(this::reportCycle);
        order = cycles.order;

        // 4. the types of the elements, and how each decision's value is bound to the type it declares
        new GraphTypes(this).workOut();

        // 5. whether the values each decision table reads fit its columns
        new TableReadChecks(this).check();

        // 6. what cannot be evaluated because of what it requires
        blocking.blockDecisions(cycles.cyclic);
        blocking.blockThroughInvocables();
    }

    /** A list of {@code count} empty collections, one per element, which the phases fill. */
    private static <T> List<T> each(int count, Supplier<T> empty) {
        var list = new ArrayList<T>(count);
        for (int i = 0; i < count; i++) {
            list.add(empty.get());
        }
        return list;
    }

    /** The issues found, in the order of the phases, each phase in the model's order of elements. */
    List<Issue> issues() {
        return List.copyOf(issues);
    }

    // ---- reporting, for the phases ----------------------------------------------------------------------

    /** Adds an issue to the validation report. */
    void issue(Kind kind, String detail) {
        issues.add(new Issue(kind, List.of(), detail, Map.of()));
    }

    /** Reports an issue of a decision, which then cannot be evaluated. */
    void report(int d, Kind kind, String detail) {
        issue(kind, detail);
        block(d, detail);
    }

    /** Notes why a decision cannot be evaluated, unless a reason is noted already. */
    void block(int d, String reason) {
        if (blocked[d] == null) {
            blocked[d] = reason;
        }
    }

    /** Reports an issue of an invocable, which then cannot be evaluated, nor what requires it. */
    void reportKnowledge(int invocable, Kind kind, String detail) {
        issue(kind, detail);
        if (knowledgeBlocked[invocable] == null) {
            knowledgeBlocked[invocable] = detail;
        }
    }

    private void reportCycle(GraphCycles.Cycle cycle) {
        issue(Kind.DECISION_CYCLE, cycle.detail());
        cycle.members().forEach(member -> block(member, cycle.detail()));
    }

    /** The values as an array, in their order. */
    static int[] ints(Collection<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
