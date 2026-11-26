/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.HashMap;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;

/**
 * Gives each element of a decision model its slot in the array of a graph's values: the input data first, then the
 * decisions, then the business knowledge models, then the decision services, each in the model's order. The last two
 * are the model's invocables, which share one numbering: a business knowledge model's index, or the number of business
 * knowledge models plus a decision service's index.
 */
final class GraphSlots {

    final int inputCount;
    final int decisionCount;
    final int knowledgeCount;
    final int serviceCount;
    private final Map<String, Integer> byName = new HashMap<>();

    GraphSlots(DecisionModel model) {
        inputCount = model.inputData().size();
        decisionCount = model.decisions().size();
        knowledgeCount = model.knowledgeModels().size();
        serviceCount = model.decisionServices().size();
        for (int i = 0; i < inputCount; i++) {
            byName.put(model.inputData().get(i).name(), i);
        }
        for (int d = 0; d < decisionCount; d++) {
            byName.put(model.decisions().get(d).name(), ofDecision(d));
        }
        for (int k = 0; k < knowledgeCount; k++) {
            byName.put(model.knowledgeModels().get(k).name(), ofKnowledge(k));
        }
        for (int k = 0; k < serviceCount; k++) {
            byName.put(model.decisionServices().get(k).name(), ofService(k));
        }
    }

    /** The number of slots: one per element. */
    int count() {
        return inputCount + decisionCount + knowledgeCount + serviceCount;
    }

    /** The slot of the element of that name; null if the model has none. */
    Integer of(String name) {
        return byName.get(name);
    }

    int ofDecision(int d) {
        return inputCount + d;
    }

    int ofKnowledge(int k) {
        return inputCount + decisionCount + k;
    }

    int ofService(int k) {
        return inputCount + decisionCount + knowledgeCount + k;
    }

    boolean isInputData(Integer slot) {
        return slot != null && slot < inputCount;
    }

    boolean isDecision(Integer slot) {
        return slot != null && slot >= inputCount && slot < inputCount + decisionCount;
    }

    /** Whether the slot is a business knowledge model's or a decision service's. */
    boolean isInvocable(Integer slot) {
        return slot != null && slot >= inputCount + decisionCount;
    }

    boolean isService(Integer slot) {
        return slot != null && slot >= inputCount + decisionCount + knowledgeCount;
    }

    /** The index of the decision at a slot. */
    int decisionAt(int slot) {
        return slot - inputCount;
    }

    /** The index, among the invocables, of the business knowledge model or decision service at a slot. */
    int invocableAt(int slot) {
        return slot - inputCount - decisionCount;
    }

    /** The index of the decision service at a slot. */
    int serviceAt(int slot) {
        return slot - inputCount - decisionCount - knowledgeCount;
    }

    /** What kind of element is at a slot, as messages name it. */
    String kindOf(int slot) {
        if (isInputData(slot)) {
            return "input data";
        }
        if (isDecision(slot)) {
            return "a decision";
        }
        return isService(slot) ? "a decision service" : "a business knowledge model";
    }
}
