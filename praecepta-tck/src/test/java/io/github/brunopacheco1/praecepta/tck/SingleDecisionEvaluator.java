/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/**
 * What Praecepta evaluates now: one decision on its own, whose logic is a decision table
 * ({@link DecisionTableEvaluator}) or a literal expression ({@link LiteralExpressionEvaluator}), with the test case's
 * inputs. A decision that needs the value of another decision the test case does not give, a decision service, a
 * business knowledge model, or another boxed expression is not supported; its needs are every feature found on the
 * way to its value: its own logic's, and those of the decisions and knowledge models it requires.
 */
final class SingleDecisionEvaluator implements ModelEvaluator {

    /** The feature each boxed expression other than a decision table and a literal expression is, by DMN element name. */
    private static final Map<String, Feature> BOXED = Map.of(
            "context", Feature.boxed("boxed contexts"),
            "invocation", Feature.INVOCATIONS,
            "relation", Feature.boxed("relations"),
            "list", Feature.boxed("boxed lists"),
            "functionDefinition", Feature.FUNCTION_DEFINITIONS,
            "conditional", Feature.boxed("boxed conditionals"),
            "filter", Feature.boxed("boxed filters"),
            "for", Feature.boxed("boxed iterators (for, some, every)"),
            "some", Feature.boxed("boxed iterators (for, some, every)"),
            "every", Feature.boxed("boxed iterators (for, some, every)"));

    private final DecisionTableEvaluator tables = new DecisionTableEvaluator();
    private final LiteralExpressionEvaluator literals = new LiteralExpressionEvaluator();

    @Override
    public Evaluation evaluate(Check check) {
        var decision = check.kind() == Invocable.DECISION ? check.model().decision(check.decision()) : null;
        if (decision == null) {
            return new Evaluation.Unsupported(needs(check, List.of()));
        }
        var evaluation = switch (String.valueOf(DmnModel.logicKind(decision))) {
            case "decisionTable" -> tables.evaluate(check);
            case "literalExpression" -> literals.evaluate(check, decision);
            default -> new Evaluation.Unsupported(own(check.model(), decision));
        };
        return evaluation instanceof Evaluation.Unsupported unsupported
                ? new Evaluation.Unsupported(needs(check, unsupported.needs())) : evaluation;
    }

    /**
     * Every feature a node needs, {@code first} first: those of what it invokes, and of the decisions, knowledge
     * models and decision services that requires, but for decisions whose value the test case gives. Only what
     * Praecepta cannot read counts: a decision table {@link DecisionTableEvaluator} reads, or a literal expression output
     * expressions read, needs nothing.
     */
    List<Feature> needs(Check check, List<Feature> first) {
        return walk(check, first, this::unread, false);
    }

    /**
     * The needs of a node for an evaluator of decision requirement graphs, which computes the decisions a decision
     * requires: as {@link #needs}, without {@link Feature#REQUIRED_DECISIONS}, but with the needs of those decisions.
     */
    List<Feature> needsBesideGraphs(Check check, List<Feature> first) {
        return walk(check, first, this::unread, true);
    }

    /**
     * Every feature the content of a node uses, whether Praecepta reads it or not: a decision table, or a literal
     * expression of arithmetic, names and literals, uses none. The latest area among them is the node's feature area,
     * which does not change as Praecepta grows.
     */
    static List<Feature> content(Check check) {
        return walk(check, List.of(), SingleDecisionEvaluator::used, false);
    }

    /** What a decision's or knowledge model's logic needs, without its requirements. */
    @FunctionalInterface
    private interface LogicNeeds {
        /**
         * @param decision the decision whose logic it is, or null for a knowledge model's
         */
        List<Feature> of(DmnModel model, Element decision, Element logic);
    }

    /**
     * The features of what a node invokes and of what that requires, {@code first} first: the reasons an evaluator gave
     * for the decision the node names, which stand for that decision's own logic.
     */
    private static List<Feature> walk(Check check, List<Feature> first, LogicNeeds logicNeeds, boolean graphs) {
        var model = check.model();
        var needs = new LinkedHashSet<Feature>(first);
        var todo = new ArrayDeque<Element>();
        var start = start(check, needs);
        if (start != null) {
            todo.add(start);
        }
        var seen = new HashSet<Element>();
        while (!todo.isEmpty()) {
            var element = todo.poll();
            if (!seen.add(element)) {
                continue;
            }
            switch (element.getLocalName()) {
                case "decision" -> {
                    // the evaluator's reasons for the decision a node names are in first, and more precise
                    if (element != start || first.isEmpty()) {
                        var logic = DmnModel.logic(element);
                        needs.addAll(logic == null ? List.of(Feature.NO_LOGIC) : logicNeeds.of(model, element, logic));
                    }
                }
                case "businessKnowledgeModel" -> {
                    needs.add(Feature.KNOWLEDGE_MODELS);
                    for (var function : TckXml.children(element, "encapsulatedLogic")) {
                        var logic = DmnModel.logic(function);
                        if (logic != null) {
                            needs.addAll(logicNeeds.of(model, null, logic));
                        }
                    }
                }
                case "decisionService" -> {
                    needs.add(Feature.DECISION_SERVICES);
                    serviceDecisions(element).forEach(href -> follow(model, href, todo, needs));
                }
                default -> {
                    // nothing else is followed
                }
            }
            for (var href : DmnModel.requirements(element, "requiredDecision")) {
                var required = model.resolve(href);
                if (required == null || !check.inputs().containsKey(required.getAttribute("name"))) {
                    if (!graphs) {
                        needs.add(Feature.REQUIRED_DECISIONS);
                    }
                    follow(model, href, todo, needs);
                }
            }
            DmnModel.requirements(element, "requiredKnowledge").forEach(href -> follow(model, href, todo, needs));
        }
        return new ArrayList<>(needs);
    }

    /** The element a node invokes, noting what invoking it needs; null if the model has none of that name. */
    private static Element start(Check check, SequencedSet<Feature> needs) {
        var model = check.model();
        var name = check.kind() == Invocable.DECISION ? check.decision() : check.invocable();
        var decision = check.kind() == Invocable.DECISION ? model.decision(name) : null;
        if (decision != null) {
            return decision;
        }
        var service = model.service(name);
        if (service != null) {
            needs.add(Feature.DECISION_SERVICES);
            return service;
        }
        var knowledgeModel = model.knowledgeModel(name);
        if (knowledgeModel != null) {
            needs.add(Feature.KNOWLEDGE_MODELS);
            return knowledgeModel;
        }
        needs.add(model.imports() ? Feature.IMPORTS : Feature.NO_LOGIC);
        return null;
    }

    private static void follow(DmnModel model, String href, ArrayDeque<Element> todo, SequencedSet<Feature> needs) {
        var element = model.resolve(href);
        if (element == null) {
            needs.add(Feature.IMPORTS); // an element of another model
        } else {
            todo.add(element);
        }
    }

    /** The hrefs of a decision service's output, encapsulated and input decisions. */
    private static List<String> serviceDecisions(Element service) {
        var hrefs = new ArrayList<String>();
        for (var kind : List.of("outputDecision", "encapsulatedDecision", "inputDecision")) {
            TckXml.children(service, kind).forEach(child -> hrefs.add(child.getAttribute("href")));
        }
        return hrefs;
    }

    /** What a decision's own logic needs: what Praecepta cannot read in it, without its requirements. */
    private List<Feature> own(DmnModel model, Element decision) {
        var logic = DmnModel.logic(decision);
        return logic == null ? List.of(Feature.NO_LOGIC) : unread(model, decision, logic);
    }

    /**
     * What Praecepta cannot read in an expression: in a literal expression, what {@link FeelScan} finds unless output
     * expressions read it; another boxed expression itself, and what the literal expressions inside it need. A decision
     * table needs what {@link DmnReader} rejects in it.
     */
    private List<Feature> unread(DmnModel model, Element decision, Element logic) {
        return switch (logic.getLocalName()) {
            case "decisionTable" -> {
                var feature = decision == null ? null : tables.unsupported(model, decision.getAttribute("name"));
                yield feature == null ? List.of() : List.of(feature);
            }
            case "literalExpression" -> {
                var text = LiteralExpressionEvaluator.text(logic);
                yield LiteralExpressionEvaluator.parses(text) ? List.of() : LiteralExpressionEvaluator.features(text, model);
            }
            default -> boxed(model, logic, true);
        };
    }

    /** What an expression uses, whether Praecepta reads it or not; a decision table's cells are not looked at. */
    private static List<Feature> used(DmnModel model, Element decision, Element logic) {
        return switch (logic.getLocalName()) {
            case "decisionTable" -> List.of();
            case "literalExpression" -> {
                var text = LiteralExpressionEvaluator.text(logic);
                var features = new ArrayList<>(FeelScan.features(text, model));
                if (LiteralExpressionEvaluator.parses(text)) {
                    features.addAll(LiteralExpressionEvaluator.inputs(model, text));
                } else if (features.isEmpty()) {
                    features.add(LiteralExpressionEvaluator.OTHER_SYNTAX);
                }
                yield features;
            }
            default -> boxed(model, logic, false);
        };
    }

    /** A boxed expression other than a decision table or a literal expression, and what the texts inside it use. */
    private static List<Feature> boxed(DmnModel model, Element logic, boolean unreadOnly) {
        var needs = new LinkedHashSet<Feature>();
        needs.add(BOXED.get(logic.getLocalName()));
        for (var text : texts(logic)) {
            if (!unreadOnly || !LiteralExpressionEvaluator.parses(text)) {
                needs.addAll(FeelScan.features(text, model));
            }
        }
        return new ArrayList<>(needs);
    }

    /** The texts of the literal expressions in a boxed expression, but for those in decision tables. */
    static List<String> texts(Element logic) {
        var texts = new ArrayList<String>();
        var todo = new ArrayDeque<Element>();
        todo.add(logic);
        while (!todo.isEmpty()) {
            var element = todo.poll();
            if (element.getLocalName().equals("decisionTable")) {
                continue;
            }
            if (element.getLocalName().equals("text")) {
                texts.add(element.getTextContent());
            }
            todo.addAll(TckXml.children(element));
        }
        return texts;
    }
}
