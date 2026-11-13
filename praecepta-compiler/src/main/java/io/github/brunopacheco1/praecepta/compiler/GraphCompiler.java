/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;

/**
 * Compiles a DMN source whose decisions require other decisions, or are literal expressions, as a decision requirements
 * graph (#14): validates the graph as {@link DecisionGraph} does, writes the model as DMN, and generates its classes
 * ({@link GraphSourceGenerator}). A DMN source compiles as a graph when the decision it names requires another decision
 * or is a literal expression, or when it names none and one of its decisions does; any other compiles as a table, as
 * before.
 */
final class GraphCompiler {

    private GraphCompiler() {
    }

    /**
     * The model to compile as a graph, or null to compile the source as a table: one whose decisions do not need a
     * graph, or that Praecepta does not evaluate as a graph (it uses what a later release plans, or is not a
     * well-formed decision model, as a DMN file of tables never had to be) but whose table reads alone, as before. A
     * table compiled alone because the model uses what a later release plans is logged ({@link CompilerOptions#log()},
     * {@link #fallbackMessage}).
     *
     * @param source the source's path, as the line logged names it
     * @throws InvalidDecisionTableException if the model uses what a later release plans, naming it, and its table
     *                                       cannot be read alone either
     */
    static DecisionModel model(byte[] dmn, String decision, String source, CompilerOptions options) {
        DecisionModel model;
        try {
            model = DmnReader.readModel(new ByteArrayInputStream(dmn));
        } catch (UnsupportedFeatureException e) {
            if (!readsAlone(dmn, decision)) {
                throw e;
            }
            options.log().accept(fallbackMessage(source, e));
            return null;
        } catch (InvalidDecisionTableException e) {
            return null;
        }
        if (decision == null) {
            return model.needsGraph() ? model : null;
        }
        return model.decision(decision).filter(DecisionModel.Decision::needsGraph).isPresent() ? model : null;
    }

    /**
     * The line logged of a DMN source whose decision table is compiled alone because {@code DmnReader.readModel}
     * rejects its model with what a later release plans: {@code src/main/rules/fees.dmn: its decision table is
     * compiled alone, without its decision requirements graph, which needs Praecepta 0.10 (#118): DMN model has the
     * business knowledge model 'Rate', ...}.
     */
    static String fallbackMessage(String source, UnsupportedFeatureException rejected) {
        return "%s: its decision table is compiled alone, without its decision requirements graph, which needs Praecepta %s (#%d): %s"
                .formatted(source, rejected.release(), rejected.issue(), rejected.getMessage());
    }

    /** Whether the source has the decision table to compile, as {@code DmnReader.read} reads it alone. */
    private static boolean readsAlone(byte[] dmn, String decision) {
        try {
            DmnReader.read(new ByteArrayInputStream(dmn), decision);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Compiles a model as a graph: one decision's classes, if the definition names one, else every decision's that can
     * be evaluated, and the model class, named after that decision, or the definition's name, or the model's.
     *
     * @throws IllegalArgumentException if the graph has validation issues at the options' {@code failOn} severity, a
     *                                  decision to compile cannot be evaluated, or two generated classes have one name
     */
    static CompiledTable compile(TableDefinition definition, DecisionModel model, CompilerOptions options) {
        var builder = DecisionGraph.builder().model(model).logValidationReport(false);
        if (options.failOn() != null) {
            builder.failOn(options.failOn());
        }
        DecisionGraph graph;
        try {
            graph = builder.build();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        var decisionClasses = new LinkedHashMap<String, String>();
        String modelClass;
        if (definition.decision() != null) {
            var decision = definition.decision();
            requireEvaluable(graph, decision);
            var className = JavaNames.type(definition.name() != null ? definition.name() : decision);
            decisionClasses.put(decision, className);
            modelClass = className + "Model";
        } else {
            var evaluable = graph.decisions().stream().filter(graph::canEvaluate).toList();
            if (evaluable.isEmpty()) {
                throw new IllegalArgumentException("Decision model '%s': none of its decisions can be evaluated:%n%s"
                        .formatted(model.name(), graph.validationReport()));
            }
            var names = JavaNames.distinct(evaluable, JavaNames::type, "Decision model '" + model.name() + "' decisions");
            for (int i = 0; i < evaluable.size(); i++) {
                decisionClasses.put(evaluable.get(i), names.get(i));
            }
            modelClass = JavaNames.type(definition.name() != null ? definition.name() : model.name()) + "Model";
        }
        checkClassNames(model, modelClass, decisionClasses);
        var sources = new GraphSourceGenerator(options, definition.source().getFileName().toString(), graph, modelClass, decisionClasses)
                .generate();
        var reports = new LinkedHashMap<String, ValidationReport>();
        reports.put(modelClass, graph.validationReport());
        decisionClasses.forEach((decision, className) -> reports.put(className, graph.validationReport(decision)));
        return new CompiledTable(null, options.packageName(), modelClass, graph.validationReport(), DmnWriter.write(graph.model()), sources,
                graph.model(), reports);
    }

    /** Fails if a decision to compile does not exist or cannot be evaluated, saying why. */
    private static void requireEvaluable(DecisionGraph graph, String decision) {
        try {
            graph.requiredInputData(decision);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Decision model '%s': %s".formatted(graph.model().name(), e.getMessage()), e);
        }
    }

    /** Fails if two generated classes would have one name. */
    private static void checkClassNames(DecisionModel model, String modelClass, Map<String, String> decisionClasses) {
        var owners = new HashMap<String, String>();
        owners.put(modelClass, "the model class");
        decisionClasses.forEach((decision, className) -> {
            for (var name : List.of(className, className + "Input", className + "Output")) {
                var previous = owners.putIfAbsent(name, "decision '" + decision + "'");
                if (previous != null) {
                    throw new IllegalArgumentException("Decision model '%s': %s and decision '%s' both generate the class %s; rename one"
                            .formatted(model.name(), previous, decision, name));
                }
            }
        });
    }
}
