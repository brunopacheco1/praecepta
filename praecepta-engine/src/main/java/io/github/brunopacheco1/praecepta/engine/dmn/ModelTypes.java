/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.AGGREGATION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.COUNT;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION_TABLE;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DEFAULT_OUTPUT_ENTRY;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.INPUT_DATA;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.LABEL;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.OUTPUT_ENTRY;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.child;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.descendants;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nameOr;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nonBlankText;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.textOf;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.dmn.ItemDefinitions.Type;
import io.github.brunopacheco1.praecepta.engine.dmn.TableReader.TableScope;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Holds the types of a model's input data and decisions, as declared, by name, and reads from them the types of the
 * input expressions and outputs that declare none, in the decision tables of the model.
 */
final class ModelTypes {

    private final Map<String, FeelType> types = new HashMap<>();

    /** Declares the type of an input data or a decision; the first one declared for a name stays. */
    void declare(String name, FeelType type) {
        types.putIfAbsent(name, type);
    }

    /**
     * The declared types of a file's input data and decisions, those Praecepta can hold: what a table read alone, of
     * a file that need be no model Praecepta reads, types its columns that compute their value with.
     */
    static ModelTypes of(Element root, ItemDefinitions items) {
        var model = new ModelTypes();
        for (var input : descendants(root, INPUT_DATA)) {
            try {
                model.declare(nameOr(input, "id"), items.feelType(variableTypeRef(input), ""));
            } catch (InvalidDecisionTableException e) {
                // a type Praecepta cannot hold: what reads it is of any type
            }
        }
        for (var decision : descendants(root, DECISION)) {
            model.declare(nameOr(decision, "id"), items.declaredTableType(decision, ""));
        }
        return model;
    }

    /** These types, and those of the names a boxed expression binds, which hide the model's elements of their names. */
    ModelTypes with(Map<String, FeelType> locals) {
        if (locals.isEmpty()) {
            return this;
        }
        var inner = new ModelTypes();
        inner.types.putAll(types);
        inner.types.putAll(locals);
        return inner;
    }

    /**
     * The type of a column whose input expression is an expression, as FEEL types it from the types of the input data
     * and decisions it reads, when that is a simple type or a list of one.
     */
    Optional<Type> expressionType(FeelExpression expression) {
        return switch (expression.type(types::get)) {
            case FeelType.Simple(var simple) -> Optional.of(new Type(simple, null, false));
            case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(new Type(simple, null, true));
            default -> Optional.empty();
        };
    }

    /**
     * The type of a column that declares none, from what it reads: the input data or decision of its name, or of its
     * longest part before a dot, at the path the rest names, when that is a simple type or a list of one.
     */
    Optional<Type> columnType(String column) {
        var element = DecisionModel.elementOf(column, types::containsKey);
        if (element == null) {
            return Optional.empty();
        }
        var type = types.get(element);
        if (element.length() < column.length()) {
            for (var component : column.substring(element.length() + 1).split("\\.")) {
                type = type == null ? null : type.component(component);
            }
        }
        return switch (type) {
            case FeelType.Simple(var simple) -> Optional.of(new Type(simple, null, false));
            case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(new Type(simple, null, true));
            case null, default -> Optional.empty();
        };
    }

    /**
     * A table's rules, as written, with its inputs, those that hold lists, and the names in scope, for typing its output
     * entries.
     */
    record Entries(List<Element> rules, List<InputColumn> inputs, Set<String> lists, TableScope scope) {

        /** The type of a name an entry reads: a column's, else an input data's or a decision's; null if unknown. */
        FeelType type(String name, Map<String, FeelType> model) {
            for (var input : inputs) {
                if (input.name().equals(name)) {
                    return lists.contains(name) ? FeelType.listOf(input.type()) : FeelType.of(input.type());
                }
            }
            return model.get(name);
        }
    }

    /**
     * The type of an output that declares none ({@link #declaredOutputType}), else that of its literal entries
     * ({@link #literalType}), else the one type its entries give, as FEEL types them ({@link #entriesType}): empty
     * where they give several, or one Praecepta's columns do not hold (a list, a context), as each entry then gives a
     * value of its own type (DMN 1.5, 10.3.2.10, step 3c). A COUNT's output, which says nothing of its value, is
     * then a string.
     *
     * @param index the output's position among the table's outputs
     */
    Optional<ColumnType> untypedOutputType(Element decision, Element output, int index, Entries entries, ItemDefinitions items,
            String where) {
        var declared = declaredOutputType(decision, output, items, where).or(() -> literalType(entries.rules(), index, output));
        if (declared.isPresent()) {
            return declared;
        }
        var table = child(decision, DECISION_TABLE);
        if (COUNT.equalsIgnoreCase(table.getAttribute(AGGREGATION).strip())) {
            return Optional.of(ColumnType.STRING);
        }
        return entriesType(entries, index, output);
    }

    /**
     * The one simple type an output's entries and default give, each as FEEL types it from the types of the table's
     * inputs and of the model's input data and decisions ({@code a + 1} is a number where {@code a} is one); empty
     * where they give several, or another type, or one that only their values tell.
     */
    private Optional<ColumnType> entriesType(Entries entries, int index, Element output) {
        var found = new HashSet<FeelType>();
        for (var text : entryTexts(entries.rules(), index, output)) {
            var type = text == null ? null : entryType(text.strip(), entries);
            if (type != null) {
                found.add(type);
            }
        }
        return found.size() == 1 && found.iterator().next() instanceof FeelType.Simple(var simple) ? Optional.of(simple) : Optional.empty();
    }

    /**
     * The texts of an output's entries, in rule order, then of its default, if it has one; null for a rule that lacks
     * the entry, which is reported when the rule is read.
     *
     * @param index the output's position among the table's outputs
     */
    private static List<String> entryTexts(List<Element> rules, int index, Element output) {
        var texts = new ArrayList<String>();
        for (var rule : rules) {
            var entries = children(rule, OUTPUT_ENTRY);
            texts.add(index < entries.size() ? textOf(entries.get(index)) : null);
        }
        var defaultEntry = nonBlankText(output, DEFAULT_OUTPUT_ENTRY);
        if (defaultEntry != null) {
            texts.add(defaultEntry);
        }
        return texts;
    }

    /** An entry's type: a literal's, else its expression's; null for a {@code null} or blank entry, and one that is no FEEL. */
    private FeelType entryType(String text, Entries entries) {
        if (Literals.isNullEntry(text, ColumnType.STRING)) {
            return null;
        }
        var literal = Literals.typeOf(text);
        if (literal.isPresent()) {
            return FeelType.of(literal.get());
        }
        try {
            var type = FeelExpression.parse(text, entries.scope().get()).type(name -> entries.type(name, types));
            return type instanceof FeelType.Null ? null : type;
        } catch (ExpressionSyntaxException e) {
            return null; // reported when the rule is read
        }
    }

    /**
     * The type of an output that declares none, as the decision's variable declares it: for a single output, the
     * variable's type, or the type of its elements (whatever the hit policy: the table's value is bound to the
     * variable, a single result to a list as a list of one); for several, the type of the variable's component of the
     * output's name. Empty where it declares none Praecepta can hold, and for COUNT, whose outputs say nothing of its
     * value.
     */
    private Optional<ColumnType> declaredOutputType(Element decision, Element output, ItemDefinitions items, String where) {
        var table = child(decision, DECISION_TABLE);
        boolean single = children(table, "output").size() == 1;
        if (!output.getAttribute(TYPE_REF).isBlank() || single && COUNT.equalsIgnoreCase(table.getAttribute(AGGREGATION).strip())) {
            return Optional.empty();
        }
        var typeRef = table.getAttribute(TYPE_REF);
        FeelType declared;
        try {
            declared = typeRef.isBlank() ? items.declaredTableType(decision, where) : items.feelType(typeRef, where);
        } catch (InvalidDecisionTableException e) {
            return Optional.empty();
        }
        var type = single ? declared : declared.component(nameOr(output, LABEL));
        return switch (type) {
            case FeelType.Simple(var simple) -> Optional.of(simple);
            case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(simple);
            case null, default -> Optional.empty();
        };
    }

    /**
     * The type of an output from its entries, for a table of a model whose output declares no type: the type of its
     * literal output entries and default, when all of them that are not {@code null} are literals of one type (a quoted
     * string, or an unquoted number, boolean, date, time or date and time, as FEEL reads {@code 10} as the number 10);
     * empty when one names an input or computes a value, or they differ.
     *
     * @param index the output's position among the table's outputs
     */
    private static Optional<ColumnType> literalType(List<Element> rules, int index, Element output) {
        var texts = entryTexts(rules, index, output);
        if (texts.contains(null)) {
            return Optional.empty();
        }
        var found = new HashSet<ColumnType>();
        for (var text : texts) {
            var literal = text.strip();
            if (Literals.isNullEntry(literal, ColumnType.STRING)) {
                continue;
            }
            var type = Literals.typeOf(literal);
            if (type.isEmpty()) {
                return Optional.empty();
            }
            found.add(type.get());
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }
}
