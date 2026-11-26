/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A decision model and the models it imports (DMN 1.5, 6.3.3), as one: every element of every model, each model once
 * (by namespace, so a model two others import is one), under its name in the graph: the root model's elements under
 * their own names, an imported model's under the names the path of imports that first reaches it gives them
 * ({@code Model B.modelA.Person name}). Each element keeps the names its own model reads, in FEEL and in its requirements:
 * its model's own elements by their names, and those of the models it imports by the names each import gives them
 * ({@code modelA.Greet the Person}), which {@link #aliases} maps to the elements' names in the graph.
 */
final class ImportedModels {

    /**
     * The model as one.
     *
     * @param model   every element of every model, under its name in the graph, requirements by those names too
     * @param aliases by element (its name in the graph), the names its own model reads, each to its element's name in the
     *                graph; none for an element of the root model of a model that imports none
     * @param byModel by imported model's namespace, the prefix of its elements' names in the graph
     */
    record Flat(DecisionModel model, Map<String, Map<String, String>> aliases, Map<String, String> byModel) {
    }

    private ImportedModels() {
    }

    /** The model and those it imports, as one; the model itself where it imports none. */
    static Flat flatten(DecisionModel root) {
        if (root.imports().isEmpty()) {
            return new Flat(root, Map.of(), Map.of());
        }
        var prefixes = new IdentityHashMap<DecisionModel, String>();
        var byNamespace = new LinkedHashMap<String, DecisionModel>();
        var order = new ArrayList<DecisionModel>();
        var pending = new ArrayDeque<DecisionModel>();
        prefixes.put(root, "");
        order.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            var model = pending.poll();
            for (var imported : model.imports()) {
                var child = canonical(imported.model(), byNamespace);
                if (!prefixes.containsKey(child)) {
                    prefixes.put(child, prefixes.get(model) + (imported.name().isEmpty() ? "" : imported.name() + "."));
                    order.add(child);
                    pending.add(child);
                }
            }
        }
        var inputData = new ArrayList<DecisionModel.InputData>();
        var decisions = new ArrayList<DecisionModel.Decision>();
        var knowledgeModels = new ArrayList<DecisionModel.BusinessKnowledgeModel>();
        var services = new ArrayList<DecisionModel.DecisionService>();
        var types = new LinkedHashMap<String, FeelType>();
        var aliases = new LinkedHashMap<String, Map<String, String>>();
        var byModel = new LinkedHashMap<String, String>();
        for (var model : order) {
            var prefix = prefixes.get(model);
            byModel.putIfAbsent(model.namespace(), prefix);
            var local = names(model, prefix, prefixes, byNamespace);
            model.types().forEach(types::putIfAbsent);
            for (var input : model.inputData()) {
                inputData.add(new DecisionModel.InputData(prefix + input.name(), input.type()));
                aliases.put(prefix + input.name(), local);
            }
            for (var decision : model.decisions()) {
                var name = prefix + decision.name();
                var logic = decision.logic() instanceof DecisionLogic.Table(var table, var type) && !prefix.isEmpty()
                        ? new DecisionLogic.Table(renamed(table, name), type) : decision.logic();
                decisions.add(new DecisionModel.Decision(name, global(decision.requiredDecisions(), local),
                        global(decision.requiredInputs(), local), global(decision.requiredKnowledge(), local), logic));
                aliases.put(name, local);
            }
            for (var knowledgeModel : model.knowledgeModels()) {
                var name = prefix + knowledgeModel.name();
                knowledgeModels.add(new DecisionModel.BusinessKnowledgeModel(name, global(knowledgeModel.requiredKnowledge(), local),
                        knowledgeModel.logic()));
                aliases.put(name, local);
            }
            for (var service : model.decisionServices()) {
                var name = prefix + service.name();
                services.add(new DecisionModel.DecisionService(name, global(service.outputDecisions(), local),
                        global(service.encapsulatedDecisions(), local), global(service.inputDecisions(), local),
                        global(service.inputData(), local), service.type()));
                aliases.put(name, local);
            }
        }
        var flat = new DecisionModel(root.name(), inputData, decisions, knowledgeModels, services, types, root.namespace(), List.of());
        return new Flat(flat, aliases, byModel);
    }

    /** The one instance of a model of that namespace, so a model two others import is one. */
    private static DecisionModel canonical(DecisionModel model, Map<String, DecisionModel> byNamespace) {
        if (model.namespace().isEmpty()) {
            return model;
        }
        return byNamespace.computeIfAbsent(model.namespace(), namespace -> model);
    }

    /**
     * The names a model reads, each to its element's name in the graph: its own elements' names, and the names each of its
     * imports gives the imported model's elements.
     */
    private static Map<String, String> names(DecisionModel model, String prefix, Map<DecisionModel, String> prefixes,
            Map<String, DecisionModel> byNamespace) {
        var names = new LinkedHashMap<String, String>();
        for (var imported : model.imports()) {
            var child = canonical(imported.model(), byNamespace);
            var childPrefix = prefixes.get(child);
            for (var element : elements(child)) {
                names.putIfAbsent(imported.qualified(element), childPrefix + element);
            }
        }
        for (var element : elements(model)) {
            names.put(element, prefix + element); // its own elements hide what an import without a name brings
        }
        return Map.copyOf(names);
    }

    private static List<String> elements(DecisionModel model) {
        var names = new ArrayList<String>();
        model.inputData().forEach(input -> names.add(input.name()));
        model.decisions().forEach(decision -> names.add(decision.name()));
        model.knowledgeModels().forEach(knowledgeModel -> names.add(knowledgeModel.name()));
        model.decisionServices().forEach(service -> names.add(service.name()));
        return names;
    }

    private static List<String> global(List<String> names, Map<String, String> local) {
        return names.stream().map(name -> local.getOrDefault(name, name)).toList();
    }

    private static DecisionTable renamed(DecisionTable table, String name) {
        return new DecisionTable(name, table.hitPolicy(), table.aggregation(), table.inputs(), table.outputs(), table.rules(),
                table.feelCells(), table.listInputs());
    }
}
