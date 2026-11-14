/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Evaluates a decision whose logic is a literal expression as an output expression ({@link OutputExpression}), which
 * output entries are, as {@code OutputExpressionsTckTest} does: the names it reads are the decision's input data,
 * typed by their {@code typeRef}, with the test case's values. Praecepta has no literal-expression decisions; what its
 * output entries compute is what a decision table would give, so the kit's literal expressions check that arithmetic.
 *
 * <p>Output expressions reject operands of types FEEL does not combine ({@code "a" + 1}) when the engine is built,
 * where FEEL gives null: such a node gets no value at all, and is counted as not supported where the kit expects null
 * or an error, and as failed otherwise.
 */
final class LiteralExpressionEvaluator {

    /** What stops evaluation: the features it needs. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Feature feature;

        Unsupported(Feature feature) {
            super(feature.name(), null, false, false);
            this.feature = feature;
        }
    }

    static final Feature REJECTED_WHERE_FEEL_GIVES_NULL = Feature.feel(
            "null for operands of types FEEL does not combine (Praecepta rejects them when the engine is built)");
    static final Feature BOOLEANS = Feature.feel("boolean values");
    static final Feature LISTS = Feature.feel("lists");
    static final Feature OTHER_SYNTAX = Feature.feel("other FEEL syntax");
    static final Feature UNTYPED_INPUTS = Feature.feel("inputs of no declared type");
    static final Feature HOUR_24 = Feature.feel("hour 24 (24:00:00) in date and time literals");
    static final Feature INVALID_LITERALS = Feature.feel(
            "null from invalid date and time literals (Praecepta rejects them when the engine is built)");
    static final Feature EXPRESSION_ERRORS = Feature.feel("errors reported with a null result (errorResult)");
    static final Feature TYPE_CONVERSIONS = Feature.feel("a decision's type: conformance and implicit conversions (10.3.2.9.4)");
    static final Feature OTHER_NAMES = Feature.feel("names other than the decision's inputs (scopes)");

    Evaluation evaluate(Check check, Element decision) {
        var text = text(DmnModel.logic(decision));
        OutputExpression expression;
        try {
            expression = OutputExpression.parse(text);
        } catch (ExpressionSyntaxException e) {
            return new Evaluation.Unsupported(unread(check, text, e.getMessage()));
        }
        var types = new HashMap<String, ColumnType>();
        var values = new HashMap<String, Object>();
        try {
            for (var name : new LinkedHashSet<>(expression.names())) {
                bind(check, name, types, values);
            }
        } catch (Unsupported e) {
            var scanned = FeelScan.features(text, check.model());
            return new Evaluation.Unsupported(e.feature == OTHER_NAMES && !scanned.isEmpty() ? scanned : List.of(e.feature));
        } catch (IllegalArgumentException e) {
            return new Evaluation.Failure("an input is not of its type: " + e.getMessage());
        }
        ColumnType type;
        try {
            type = expression.type(types::get);
        } catch (ExpressionTypeException e) {
            if (check.expected() instanceof TckValue.Nil || check.expectsError()) {
                return new Evaluation.Unsupported(REJECTED_WHERE_FEEL_GIVES_NULL);
            }
            return new Evaluation.Failure("rejected when the engine is built: " + e.getMessage());
        }
        var value = expression.evaluate(values::get);
        if (value == null) {
            return check.expectsError() ? new Evaluation.Unsupported(EXPRESSION_ERRORS) : new Evaluation.Value(TckValue.NIL, true);
        }
        if (!conforms(check.model(), decision, type)) {
            return new Evaluation.Unsupported(TYPE_CONVERSIONS);
        }
        var valueText = value instanceof BigDecimal number ? number.toPlainString() : FeelLiterals.text(value);
        return new Evaluation.Value(new TckValue.Simple(type.feelName(), valueText), true);
    }

    /**
     * What a text output expressions do not read needs. Where {@link FeelScan} finds nothing, the text has a date and
     * time literal Praecepta rejects: hour 24, or one FEEL gives null for (an error case of the kit).
     */
    private static List<Feature> unread(Check check, String text, String message) {
        var features = FeelScan.features(text, check.model());
        if (!features.isEmpty()) {
            return features;
        }
        if (message.contains("24:00")) {
            return List.of(HOUR_24);
        }
        return List.of(check.expectsError() || check.expected() instanceof TckValue.Nil ? INVALID_LITERALS : OTHER_SYNTAX);
    }

    /**
     * Whether a value of this type is of the decision's type, so that no conversion applies (DMN 1.5, 10.3.2.9.4): the
     * same simple type, or a decision of no type or of a type the model does not define.
     */
    private static boolean conforms(DmnModel model, Element decision, ColumnType type) {
        return switch (ItemType.of(model, DmnModel.typeRef(decision))) {
            case ItemType.Simple(var declared) -> declared == type;
            case ItemType.Any any -> true;
            case ItemType.Unknown unknown -> true;
            default -> false;
        };
    }

    /** The text of a literal expression; empty if it has none. */
    static String text(Element literal) {
        var texts = TckXml.children(literal, "text");
        return texts.isEmpty() ? "" : texts.getFirst().getTextContent();
    }

    /** Whether output expressions read the text. */
    static boolean parses(String text) {
        try {
            OutputExpression.parse(text);
            return true;
        } catch (ExpressionSyntaxException e) {
            return false;
        }
    }

    /**
     * What the inputs an expression output expressions read need: a structured input, whose components it reads
     * ({@code Loan.amount}), or a list.
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

    /** What a text output expressions do not read needs: what {@link FeelScan} finds, or {@link #OTHER_SYNTAX}. */
    static List<Feature> features(String text, DmnModel model) {
        var features = FeelScan.features(text, model);
        return features.isEmpty() ? List.of(OTHER_SYNTAX) : features;
    }

    /** The type and value of a name the expression reads. */
    private static void bind(Check check, String name, Map<String, ColumnType> types, Map<String, Object> values) {
        var model = check.model();
        Element owner = model.inputData(name);
        if (owner == null && model.decision(name) != null) {
            if (!check.inputs().containsKey(name)) {
                throw new Unsupported(Feature.REQUIRED_DECISIONS);
            }
            owner = model.decision(name);
        }
        if (owner == null) {
            throw new Unsupported(unknownName(model, name));
        }
        var given = check.inputs().getOrDefault(name, TckValue.NIL);
        var type = switch (ItemType.of(model, DmnModel.typeRef(owner))) {
            case ItemType.Simple(var simple) -> simple;
            case ItemType.Any any -> givenType(given);
            case ItemType.Structure structure -> throw new Unsupported(Feature.STRUCTURED_INPUTS);
            case ItemType.Collection collection -> throw new Unsupported(LISTS);
            case ItemType.Function function -> throw new Unsupported(Feature.FUNCTION_DEFINITIONS);
            case ItemType.Unknown unknown -> throw new Unsupported(model.imports() ? Feature.IMPORTS : UNTYPED_INPUTS);
        };
        if (type == ColumnType.BOOLEAN) {
            throw new Unsupported(BOOLEANS);
        }
        types.put(name, type);
        values.put(name, switch (given) {
            case TckValue.Nil nil -> null;
            case TckValue.Simple simple -> FeelLiterals.value(simple.text(), type);
            case TckValue.Items items -> throw new Unsupported(LISTS);
            case TckValue.Components components -> throw new Unsupported(Feature.STRUCTURED_INPUTS);
        });
    }

    /** The type of a value given to an input of no type: its {@code xsi:type}. */
    private static ColumnType givenType(TckValue given) {
        return switch (given) {
            case TckValue.Simple simple when ItemType.builtIn(simple.type()) instanceof ItemType.Simple(var type) -> type;
            case TckValue.Items items -> throw new Unsupported(LISTS);
            case TckValue.Components components -> throw new Unsupported(Feature.STRUCTURED_INPUTS);
            default -> throw new Unsupported(UNTYPED_INPUTS);
        };
    }

    /** What a name that is neither an input nor a decision needs: a path into a structured input, or a scope. */
    private static Feature unknownName(DmnModel model, String name) {
        var dot = name.indexOf('.');
        if (dot > 0) {
            var root = name.substring(0, dot);
            if (model.inputData(root) != null) {
                return Feature.STRUCTURED_INPUTS;
            }
            if (model.decision(root) != null) {
                return Feature.REQUIRED_DECISIONS;
            }
            if (model.imports()) {
                return Feature.IMPORTS;
            }
        }
        return OTHER_NAMES;
    }
}
