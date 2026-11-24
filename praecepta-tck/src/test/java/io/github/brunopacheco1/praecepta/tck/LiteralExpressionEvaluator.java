/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Evaluates a decision whose logic is a literal expression on its own, in a model {@code DmnReader.readModel} does not
 * read as a whole: the expression ({@link FeelExpression}), parsed with the model's input data and decisions in scope,
 * reads the test case's values of the input data and decisions it names, typed by their {@code typeRef}, through a
 * {@link DecisionGraph} of that one decision, which binds its value to its declared type and reports FEEL's errors. A
 * decision it needs whose value the test case does not give needs decision requirement graphs, and a built-in function,
 * 0.9.
 *
 * <p>Which feature area a node is in is fixed by the kit's content, not by what Praecepta reads: {@link #inTheSubset}
 * tells the arithmetic of names and literals that 0.6's output expressions computed, which counts as no feature.
 */
final class LiteralExpressionEvaluator {

    /** What stops evaluation: the features it needs. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient List<Feature> features;

        Unsupported(List<Feature> features) {
            super(features.getFirst().name(), null, false, false);
            this.features = features;
        }

        Unsupported(Feature feature) {
            this(List.of(feature));
        }
    }

    static final Feature LISTS = Feature.feel("lists");
    static final Feature OTHER_SYNTAX = Feature.feel("other FEEL syntax");
    static final Feature UNTYPED_INPUTS = Feature.feel("inputs of no declared type");
    static final Feature OTHER_NAMES = Feature.feel("names other than the decision's inputs (scopes)");

    Evaluation evaluate(Check check, Element decision) {
        var model = check.model();
        var text = text(DmnModel.logic(decision));
        var scope = new LinkedHashSet<String>();
        scope.addAll(model.inputData());
        scope.addAll(model.decisions());
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            return new Evaluation.Unsupported(features(text, model));
        }
        if (!expression.unsupported().isEmpty()) {
            return new Evaluation.Unsupported(unsupported(text, model));
        }
        try {
            return evaluate(check, decision, text, expression);
        } catch (Unsupported e) {
            return new Evaluation.Unsupported(e.features);
        }
    }

    private static Evaluation evaluate(Check check, Element decision, String text, FeelExpression expression) {
        var model = check.model();
        var builder = DecisionGraph.builder().name(String.valueOf(model.root().getAttribute("name"))).logValidationReport(false);
        var inputs = new HashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var name : expression.names()) {
            Element owner = model.inputData(name);
            if (owner == null && model.decision(name) != null) {
                if (!check.inputs().containsKey(name)) {
                    throw new Unsupported(Feature.REQUIRED_DECISIONS);
                }
                owner = model.decision(name);
            }
            if (owner == null) {
                var scanned = FeelScan.features(text, model);
                throw new Unsupported(scanned.isEmpty() ? List.of(OTHER_NAMES) : scanned);
            }
            var type = inputType(model, owner);
            builder.inputData(name, type);
            required.add(name);
            var given = check.inputs().get(name);
            if (given != null) {
                inputs.put(name, GraphEvaluator.input(given, type));
            }
        }
        var declared = declaredType(model, decision);
        var name = check.decision();
        builder.decision(new DecisionModel.Decision(name, List.of(), required, new DecisionLogic.LiteralExpression(text, declared)));
        var graph = builder.build();
        if (!graph.canEvaluate(name)) {
            return new Evaluation.Unsupported(GraphEvaluator.REPORTED);
        }
        try {
            return GraphEvaluator.evaluation(check, graph.evaluateWithErrors(name, inputs), true);
        } catch (InvalidInputException e) {
            return new Evaluation.Failure("an input is not of its type: " + e.getMessage());
        }
    }

    /** The type of an input data or a decision whose value the test case gives, as its {@code typeRef} names it. */
    private static FeelType inputType(DmnModel model, Element owner) {
        return switch (ItemType.of(model, DmnModel.typeRef(owner))) {
            case ItemType.Simple(var simple) -> FeelType.of(simple);
            case ItemType.Function function -> throw new Unsupported(Feature.FUNCTION_DEFINITIONS);
            case ItemType.Unknown unknown -> throw new Unsupported(model.imports() ? Feature.IMPORTS : UNTYPED_INPUTS);
            default -> TckTypes.of(model, DmnModel.typeRef(owner));
        };
    }

    /** The type a decision declares ({@link TckTypes}). */
    private static FeelType declaredType(DmnModel model, Element decision) {
        return TckTypes.of(model, DmnModel.typeRef(decision));
    }

    /** The text of a literal expression; empty if it has none. */
    static String text(Element literal) {
        var texts = TckXml.children(literal, "text");
        return texts.isEmpty() ? "" : texts.getFirst().getTextContent();
    }

    /**
     * Whether the text is in the subset 0.6's output expressions computed: arithmetic of names and literals. It fixes a
     * node's feature area, which does not change as Praecepta grows; it says nothing of what Praecepta reads now.
     */
    static boolean inTheSubset(String text) {
        try {
            OutputExpression.parse(text);
            return true;
        } catch (ExpressionSyntaxException e) {
            return false;
        }
    }

    /** Whether Praecepta evaluates the text: it is FEEL, and uses nothing Praecepta does not evaluate yet. */
    static boolean evaluates(String text) {
        try {
            return FeelExpression.parse(text).unsupported().isEmpty();
        } catch (ExpressionSyntaxException e) {
            return false;
        }
    }

    /**
     * What the inputs of an expression in the subset need: a structured input, whose components it reads
     * ({@code Loan.amount}), or a list. Part of a node's fixed feature area.
     */
    static List<Feature> inputs(DmnModel model, String text) {
        var features = new LinkedHashSet<Feature>();
        for (var name : OutputExpression.parse(text).names()) {
            var dot = name.indexOf('.');
            var input = model.inputData(dot > 0 && model.inputData(name) == null ? name.substring(0, dot) : name);
            switch (input == null ? new ItemType.Any() : ItemType.of(model, DmnModel.typeRef(input))) {
                case ItemType.Structure structure -> features.add(Feature.STRUCTURED_INPUTS);
                case ItemType.Collection collection -> features.add(LISTS);
                default -> {
                    // a value Praecepta takes
                }
            }
        }
        return List.copyOf(features);
    }

    /** What a text that is not FEEL Praecepta reads needs: what {@link FeelScan} finds, or {@link #OTHER_SYNTAX}. */
    static List<Feature> features(String text, DmnModel model) {
        var features = FeelScan.features(text, model);
        return features.isEmpty() ? List.of(OTHER_SYNTAX) : features;
    }

    /**
     * What a text Praecepta parses but does not evaluate needs: the built-in functions and later features
     * {@link FeelScan} finds in it, the FEEL language itself, function definitions among it, being Praecepta's now.
     */
    static List<Feature> unsupported(String text, DmnModel model) {
        var needs = FeelScan.features(text, model).stream()
                .filter(feature -> feature.area().compareTo(Feature.Area.FEEL) > 0 && !feature.equals(Feature.FUNCTION_DEFINITIONS))
                .toList();
        return needs.isEmpty() ? features(text, model) : needs;
    }
}
