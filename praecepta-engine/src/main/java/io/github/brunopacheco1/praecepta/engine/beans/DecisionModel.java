/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision model: its input data and its decisions, which require one another and the input data through their
 * information requirements, and its business knowledge models, which decisions and other business knowledge models
 * invoke through their knowledge requirements, forming a decision requirements graph (DMN 1.5, chapter 6). This is what
 * a DMN file with several decisions holds; {@code DmnReader#readModel} reads it, {@code DmnWriter#write(DecisionModel)} writes it, and
 * {@code DecisionGraph} evaluates it. It is a plain description: whether its requirements exist, are acyclic and fit
 * the logic that reads them is checked by {@code DecisionGraph}, which reports what is wrong in its validation report.
 *
 * @param name            the model's name (the {@code definitions} element's)
 * @param inputData       the input data, in the model's order
 * @param decisions       the decisions, in the model's order
 * @param knowledgeModels  the business knowledge models, in the model's order
 * @param decisionServices the decision services, in the model's order
 * @param namespace        the model's namespace (the {@code definitions} element's), by which other models import it;
 *                         empty if it has none
 * @param imports          the models it imports (DMN 1.5, 6.3.3), whose elements its own read by qualified
 *                         names ({@code importName.elementName}), or by their own names for an import without a name
 * @param types           the types the model defines (its item definitions, DMN 1.5, 7.3.2), by name, which a literal
 *                  expression's {@code instance of} and function parameters may name (10.3.2.9); empty if it defines
 *                  none
 */
public record DecisionModel(String name, List<InputData> inputData, List<Decision> decisions, List<BusinessKnowledgeModel> knowledgeModels,
        List<DecisionService> decisionServices, Map<String, FeelType> types, String namespace, List<Import> imports) {

    /**
     * A model with the given input data and decisions, and no business knowledge models or types of its own.
     *
     * @throws IllegalArgumentException if two of them have the same name (see the canonical constructor)
     */
    public DecisionModel(String name, List<InputData> inputData, List<Decision> decisions) {
        this(name, inputData, decisions, List.of(), List.of(), Map.of());
    }

    /**
     * A model with the given input data, decisions and types, and no business knowledge models.
     *
     * @throws IllegalArgumentException if two of them have the same name (see the canonical constructor)
     */
    public DecisionModel(String name, List<InputData> inputData, List<Decision> decisions, Map<String, FeelType> types) {
        this(name, inputData, decisions, List.of(), List.of(), types);
    }

    /**
     * A model with the given input data, decisions, business knowledge models and types, and no decision services.
     *
     * @throws IllegalArgumentException if two of them have the same name (see the canonical constructor)
     */
    public DecisionModel(String name, List<InputData> inputData, List<Decision> decisions, List<BusinessKnowledgeModel> knowledgeModels,
            Map<String, FeelType> types) {
        this(name, inputData, decisions, knowledgeModels, List.of(), types);
    }

    /**
     * A model with the given input data, decisions, business knowledge models, decision services and types, of no
     * namespace, importing no other model.
     *
     * @throws IllegalArgumentException if two of them have the same name (see the canonical constructor)
     */
    public DecisionModel(String name, List<InputData> inputData, List<Decision> decisions, List<BusinessKnowledgeModel> knowledgeModels,
            List<DecisionService> decisionServices, Map<String, FeelType> types) {
        this(name, inputData, decisions, knowledgeModels, decisionServices, types, "", List.of());
    }

    /**
     * A model with the given elements, types, namespace and imports.
     *
     * @throws IllegalArgumentException if two of them have the same name: DMN 1.5, 6.3.7 and 6.3.11, "the name of a
     *                                  Decision must be different from the name of any other invocable, input data,
     *                                  decision, or import in the decision model"
     */
    public DecisionModel {
        Objects.requireNonNull(name, "name");
        inputData = List.copyOf(inputData);
        decisions = List.copyOf(decisions);
        knowledgeModels = List.copyOf(knowledgeModels);
        decisionServices = List.copyOf(decisionServices);
        Objects.requireNonNull(namespace, "namespace");
        imports = List.copyOf(imports);
        types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        var names = new HashSet<String>();
        var all = new ArrayList<String>();
        inputData.forEach(input -> all.add(input.name()));
        decisions.forEach(decision -> all.add(decision.name()));
        knowledgeModels.forEach(knowledgeModel -> all.add(knowledgeModel.name()));
        decisionServices.forEach(service -> all.add(service.name()));
        for (var element : all) {
            if (!names.add(element)) {
                throw new IllegalArgumentException(("'%s' names two elements of the decision model; the names of its decisions, "
                        + "input data, business knowledge models and decision services must differ (DMN 1.5, 6.3.7 and 6.3.11)")
                        .formatted(element));
            }
        }
    }

    /**
     * The element a name that a decision reads refers to, among the names {@code inScope} accepts: the one of that whole
     * name, else the one named by its longest part before a dot, the rest of the name being a path into its value
     * ({@code Pricing.discount} reads the component {@code discount} of {@code Pricing}; DMN 1.5, 10.3.2.11, and
     * docs/DMN-CONFORMANCE.md, case 15). Every part of Praecepta that reads a model resolves names with it.
     *
     * @return the element's name, or null if no part of the name is in scope
     */
    public static String elementOf(String name, Predicate<String> inScope) {
        for (int end = name.length(); end > 0; end = name.lastIndexOf('.', end - 1)) {
            var prefix = name.substring(0, end);
            if (inScope.test(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /** The decision of that name, if the model has one. */
    public Optional<Decision> decision(String name) {
        return decisions.stream().filter(decision -> decision.name().equals(name)).findFirst();
    }

    /**
     * Whether the model needs its decision requirements graph to be evaluated: one of its decisions does
     * ({@link Decision#needsGraph()}). The decision tables of any other model may each be evaluated alone, from their
     * columns, as the build plugins and framework integrations evaluate them.
     */
    public boolean needsGraph() {
        return decisions.stream().anyMatch(Decision::needsGraph);
    }

    /** The decision service of that name, if the model has one. */
    public Optional<DecisionService> decisionService(String name) {
        return decisionServices.stream().filter(service -> service.name().equals(name)).findFirst();
    }

    /** The business knowledge model of that name, if the model has one. */
    public Optional<BusinessKnowledgeModel> knowledgeModel(String name) {
        return knowledgeModels.stream().filter(knowledgeModel -> knowledgeModel.name().equals(name)).findFirst();
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
     * @param requiredKnowledge the names of the business knowledge models it requires (its knowledge requirements, DMN
     *                          1.5, 6.3.14), which its logic invokes by name
     * @param logic             its logic
     */
    public record Decision(String name, List<String> requiredDecisions, List<String> requiredInputs, List<String> requiredKnowledge,
            DecisionLogic logic) {

        /**
         * A decision that requires no business knowledge.
         *
         * @throws IllegalArgumentException if its logic is a decision table named otherwise
         */
        public Decision(String name, List<String> requiredDecisions, List<String> requiredInputs, DecisionLogic logic) {
            this(name, requiredDecisions, requiredInputs, List.of(), logic);
        }

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
            requiredKnowledge = List.copyOf(requiredKnowledge);
            if (logic instanceof DecisionLogic.Table(var table, var type) && !table.name().equals(name)) {
                throw new IllegalArgumentException("Decision '%s' has a decision table named '%s'; a decision's table takes its name"
                        .formatted(name, table.name()));
            }
        }

        /**
         * Whether it needs the decision requirements graph to be evaluated: it requires another decision or business
         * knowledge, or its logic is not a decision table, which only a graph evaluates.
         */
        public boolean needsGraph() {
            return !requiredDecisions.isEmpty() || !requiredKnowledge.isEmpty() || !(logic instanceof DecisionLogic.Table);
        }

        /** Its requirements: the decisions it requires, then the input data, then the business knowledge models. */
        public List<String> requirements() {
            var requirements = new ArrayList<>(requiredDecisions);
            requirements.addAll(requiredInputs);
            requirements.addAll(requiredKnowledge);
            return List.copyOf(requirements);
        }
    }

    /**
     * A business knowledge model (DMN 1.5, 6.3.10): a function, named by its variable, that decisions and other business
     * knowledge models invoke, its logic a function definition whose body is a decision table, a literal expression or
     * a boxed expression. It reads its parameters and the business knowledge models it requires (its knowledge
     * requirements, 6.3.14) only, not input data or decisions.
     *
     * @param name              its name, which is also the name of its variable
     * @param requiredKnowledge the names of the business knowledge models it requires, in the model's order
     * @param logic             its logic (its {@code encapsulatedLogic}), whose type is the function type its variable
     *                          declares, or any type
     */
    public record BusinessKnowledgeModel(String name, List<String> requiredKnowledge, DecisionLogic.FunctionDefinition logic) {
        public BusinessKnowledgeModel {
            Objects.requireNonNull(name, "name");
            requiredKnowledge = List.copyOf(requiredKnowledge);
            Objects.requireNonNull(logic, "logic");
        }
    }

    /**
     * A decision service (DMN 1.5, 6.3.9): a function, named by its variable, that evaluates its output decisions, and the
     * encapsulated decisions they require, from its parameters, which are its input data, then its input decisions, in
     * order: the values of the input decisions are the arguments, and those decisions are not evaluated. Its value is its
     * one output decision's, or a context of its output decisions' by name. Decisions and business knowledge models invoke
     * it by name, once they require it through a knowledge requirement, as they invoke a business knowledge model.
     *
     * @param name                  its name, which is also the name of its variable
     * @param outputDecisions       the decisions whose values it gives, in order
     * @param encapsulatedDecisions the other decisions it evaluates, which its output decisions require
     * @param inputDecisions        the decisions whose values it takes as parameters
     * @param inputData             the input data whose values it takes as parameters
     * @param type                  the type its variable declares (a function type), or any type
     */
    public record DecisionService(String name, List<String> outputDecisions, List<String> encapsulatedDecisions, List<String> inputDecisions,
            List<String> inputData, FeelType type) {
        public DecisionService {
            Objects.requireNonNull(name, "name");
            outputDecisions = List.copyOf(outputDecisions);
            encapsulatedDecisions = List.copyOf(encapsulatedDecisions);
            inputDecisions = List.copyOf(inputDecisions);
            inputData = List.copyOf(inputData);
            Objects.requireNonNull(type, "type");
        }

        /** Its parameters' names: its input data, then its input decisions (the DMN TCK's {@code 0085-decision-services}). */
        public List<String> parameters() {
            var parameters = new ArrayList<>(inputData);
            parameters.addAll(inputDecisions);
            return List.copyOf(parameters);
        }
    }

    /**
     * An import (DMN 1.5, 6.3.3): another model, whose elements the importing model's decisions, business knowledge
     * models and decision services read and require by {@code name.elementName}, or by their own names where the import
     * has no name.
     *
     * @param name  the import's name, the prefix of the names it gives the imported elements; empty for none
     * @param model the imported model, which may import others in turn
     */
    public record Import(String name, DecisionModel model) {
        public Import {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
        }

        /** The name an element of the imported model takes in the importing one. */
        public String qualified(String element) {
            return name.isEmpty() ? element : name + "." + element;
        }
    }
}
