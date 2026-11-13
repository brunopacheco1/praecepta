/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision model: its input data and its decisions, which require one another and the input data through their
 * information requirements, forming a decision requirements graph (DMN 1.5, chapter 6). This is what a DMN file with
 * several decisions holds; {@code DmnReader#readModel} reads it, {@code DmnWriter#write(DecisionModel)} writes it, and
 * {@code DecisionGraph} evaluates it. It is a plain description: whether its requirements exist, are acyclic and fit
 * the logic that reads them is checked by {@code DecisionGraph}, which reports what is wrong in its validation report.
 *
 * @param name      the model's name (the {@code definitions} element's)
 * @param inputData the input data, in the model's order
 * @param decisions the decisions, in the model's order
 */
public record DecisionModel(String name, List<InputData> inputData, List<Decision> decisions) {

    /**
     * A model with the given input data and decisions.
     *
     * @throws IllegalArgumentException if two of them have the same name: DMN 1.5, 6.3.7 and 6.3.11, "the name of a
     *                                  Decision must be different from the name of any other invocable, input data,
     *                                  decision, or import in the decision model"
     */
    public DecisionModel {
        Objects.requireNonNull(name, "name");
        inputData = List.copyOf(inputData);
        decisions = List.copyOf(decisions);
        var names = new HashSet<String>();
        var all = new ArrayList<String>();
        inputData.forEach(input -> all.add(input.name()));
        decisions.forEach(decision -> all.add(decision.name()));
        for (var element : all) {
            if (!names.add(element)) {
                throw new IllegalArgumentException(("'%s' names two elements of the decision model; the names of its decisions and "
                        + "input data must differ (DMN 1.5, 6.3.7 and 6.3.11)").formatted(element));
            }
        }
    }

    /** The decision of that name, if the model has one. */
    public Optional<Decision> decision(String name) {
        return decisions.stream().filter(decision -> decision.name().equals(name)).findFirst();
    }

    /** The input data of that name, if the model has one. */
    public Optional<InputData> inputData(String name) {
        return inputData.stream().filter(input -> input.name().equals(name)).findFirst();
    }

    /**
     * An input data (DMN 1.5, 6.3.11): a value given from outside the model when it is evaluated.
     *
     * @param name the input data's name, which is also the name of its variable
     * @param type the type of its value (its variable's {@code typeRef}), or {@link FeelType#ANY} if the model declares
     *             none
     */
    public record InputData(String name, FeelType type) {
        public InputData {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * A decision (DMN 1.5, 6.3.7): logic that gives a value, named by the decision's variable, from the values of its
     * information requirements (6.3.13), the decisions and input data it requires. Its logic may read only those
     * (7.3.4: "each variable that is referenced by a decision's expression must be associated with a required
     * decision, required input data, or required knowledge").
     *
     * @param name              the decision's name, which is also the name of its variable
     * @param requiredDecisions the names of the decisions it requires, in the model's order
     * @param requiredInputs    the names of the input data it requires, in the model's order
     * @param logic             its logic
     */
    public record Decision(String name, List<String> requiredDecisions, List<String> requiredInputs, DecisionLogic logic) {

        /**
         * A decision.
         *
         * @throws IllegalArgumentException if its logic is a decision table named otherwise
         */
        public Decision {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(logic, "logic");
            requiredDecisions = List.copyOf(requiredDecisions);
            requiredInputs = List.copyOf(requiredInputs);
            if (logic instanceof DecisionLogic.Table(var table) && !table.name().equals(name)) {
                throw new IllegalArgumentException("Decision '%s' has a decision table named '%s'; a decision's table takes its name"
                        .formatted(name, table.name()));
            }
        }

        /** Its information requirements: the decisions it requires, then the input data. */
        public List<String> requirements() {
            var requirements = new ArrayList<>(requiredDecisions);
            requirements.addAll(requiredInputs);
            return List.copyOf(requirements);
        }
    }
}
