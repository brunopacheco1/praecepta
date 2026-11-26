/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;

/**
 * Finds what a {@link GraphAnalysis} cannot evaluate because of what it requires: the other phases note why an element
 * with an issue of its own cannot be evaluated, and this one carries that to the elements that require it, directly or
 * not. It adds no issue to the validation report: it only fills {@code blocked} and {@code knowledgeBlocked}, whose
 * reasons the graph gives when such an element is asked for.
 */
final class GraphBlocking {

    private final GraphAnalysis graph;
    private final DecisionModel model;
    private final GraphSlots slots;
    /**
     * By decision: whether why it cannot be evaluated is only that a decision it requires cannot be, which a decision
     * service that takes that decision as an input decision provides.
     */
    private final boolean[] inherited;

    GraphBlocking(GraphAnalysis graph) {
        this.graph = graph;
        this.model = graph.model;
        this.slots = graph.slots;
        this.inherited = new boolean[slots.decisionCount];
    }

    /** Blocks every business knowledge model that requires an invocable that cannot be evaluated, directly or indirectly. */
    void blockKnowledge() {
        var knowledgeModels = model.knowledgeModels();
        for (boolean changed = true; changed;) {
            changed = false;
            for (int k = 0; k < knowledgeModels.size(); k++) {
                if (graph.knowledgeBlocked[k] != null) {
                    continue;
                }
                for (var required : knowledgeModels.get(k).requiredKnowledge()) {
                    var slot = slots.of(required);
                    if (slots.isInvocable(slot) && graph.knowledgeBlocked[slots.invocableAt(slot)] != null) {
                        graph.knowledgeBlocked[k] = "it requires business knowledge model '%s', which cannot be evaluated".formatted(required);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Blocks every decision that requires one that cannot be evaluated, directly or indirectly, or one in a cycle.
     *
     * @param cyclic by decision, whether it is in a cycle
     */
    void blockDecisions(boolean[] cyclic) {
        var ordered = new boolean[slots.decisionCount];
        for (int d : graph.order) {
            ordered[d] = true;
            inheritBlock(d);
        }
        for (int d = 0; d < ordered.length; d++) {
            if (!ordered[d] && graph.blocked[d] == null) {
                graph.blocked[d] = cyclic[d] ? "it is in a cycle of required decisions" : "it requires a decision in a cycle of required decisions";
            }
        }
    }

    /**
     * Blocks, until nothing changes, a decision service that evaluates a decision that cannot be evaluated, and the
     * decisions and business knowledge models that require an invocable that cannot be.
     */
    void blockThroughInvocables() {
        for (boolean changed = true; changed;) {
            changed = false;
            for (int k = 0; k < slots.serviceCount; k++) {
                changed |= blockService(k);
            }
            for (int k = 0; k < slots.knowledgeCount; k++) {
                if (graph.knowledgeBlocked[k] == null) {
                    graph.knowledgeBlocked[k] = blockedInvocable(model.knowledgeModels().get(k).requiredKnowledge());
                    changed |= graph.knowledgeBlocked[k] != null;
                }
            }
            for (int d = 0; d < slots.decisionCount; d++) {
                if (graph.blocked[d] == null) {
                    graph.blocked[d] = blockedInvocable(model.decisions().get(d).requiredKnowledge());
                    changed |= graph.blocked[d] != null;
                }
                changed |= inheritBlock(d);
            }
        }
    }

    /** Blocks a decision service that evaluates a decision that cannot be evaluated; says whether it did. */
    private boolean blockService(int k) {
        int invocable = slots.knowledgeCount + k;
        if (graph.knowledgeBlocked[invocable] != null) {
            return false;
        }
        for (int d : graph.serviceDecisions[k]) {
            // what a decision only inherits from the decisions it requires is the service's to provide: its input decisions
            if (graph.blocked[d] != null && !inherited[d]) {
                graph.knowledgeBlocked[invocable] = "it evaluates decision '%s', which cannot be evaluated: %s"
                        .formatted(model.decisions().get(d).name(), graph.blocked[d]);
                return true;
            }
        }
        return false;
    }

    /** Why what requires these invocables cannot be evaluated: the first of them that cannot be; null if they all can. */
    private String blockedInvocable(List<String> required) {
        for (var name : required) {
            var slot = slots.of(name);
            if (slots.isInvocable(slot) && graph.knowledgeBlocked[slots.invocableAt(slot)] != null) {
                return "it requires %s '%s', which cannot be evaluated".formatted(slots.kindOf(slot), name);
            }
        }
        return null;
    }

    /** Blocks a decision that can be evaluated but for a decision it requires, which cannot; says whether it did. */
    private boolean inheritBlock(int d) {
        if (graph.blocked[d] != null) {
            return false;
        }
        for (int r : graph.requiredDecisions[d]) {
            if (graph.blocked[r] != null) {
                inherited[d] = true;
                graph.blocked[d] = "it requires decision '%s', which cannot be evaluated: %s".formatted(model.decisions().get(r).name(),
                        graph.blocked[r]);
                return true;
            }
        }
        return false;
    }
}
