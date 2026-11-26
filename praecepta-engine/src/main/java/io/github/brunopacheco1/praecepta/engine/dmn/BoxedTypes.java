/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.typeName;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.attribute;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Declares the types of the parts of boxed expressions, before the decisions are written: a simple type is named by its
 * {@code typeRef}, any other type by an item definition, written once, and any type by nothing. It also gives each
 * decision table within a boxed expression the prefix of its ids.
 */
final class BoxedTypes {

    private final StringBuilder xml;
    private final Map<String, FeelType> named;
    private final Map<FeelType, String> written = new HashMap<>();
    /** The prefix of the ids of each decision table within a boxed expression, and of the item definitions of its list columns. */
    private final Map<DecisionTable, String> tables = new IdentityHashMap<>();
    /** The prefix of the ids of the element whose logic is being declared ({@code decision_2_}). */
    private String element = "";

    BoxedTypes(StringBuilder xml, Map<String, FeelType> named) {
        this.xml = xml;
        this.named = named;
    }

    /** Declares the parts of an element's logic, whose ids start with {@code prefix}. */
    void declare(String prefix, DecisionLogic logic, boolean root, String where) {
        element = prefix;
        declare(logic, root, where);
    }

    /** Writes the item definitions of every part's type; the root's, a decision's, is its variable's. */
    private void declare(DecisionLogic logic, boolean root, String where) {
        if (!root) {
            declare(logic.type(), where);
        }
        switch (logic) {
            case DecisionLogic.Context(var entries, var result, var type) -> {
                entries.forEach(entry -> declare(entry.value(), false, where));
                if (result != null) {
                    declare(result, false, where);
                }
            }
            case DecisionLogic.Invocation(var function, var bindings, var type) ->
                    bindings.stream().filter(binding -> binding.value() != null).forEach(binding -> declare(binding.value(), false, where));
            case DecisionLogic.FunctionDefinition(var parameters, var body, var type) -> {
                parameters.forEach(parameter -> declare(parameter.type(), where));
                declare(body, false, where);
            }
            case DecisionLogic.Relation(var columns, var rows, var type) -> {
                columns.forEach(column -> declare(column.type(), where));
                rows.forEach(row -> row.forEach(cell -> declare(cell, false, where)));
            }
            case DecisionLogic.BoxedList(var elements, var type) -> elements.forEach(element -> declare(element, false, where));
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var type) ->
                    List.of(condition, then, otherwise).forEach(part -> declare(part, false, where));
            case DecisionLogic.Iteration(var kind, var variable, var domain, var body, var type) ->
                    List.of(domain, body).forEach(part -> declare(part, false, where));
            case DecisionLogic.Filter(var list, var match, var type) -> List.of(list, match).forEach(part -> declare(part, false, where));
            case DecisionLogic.Table(var table, var type) -> {
                var prefix = TableWriter.freePrefix(element + "table_" + tables.size(), named);
                tables.put(table, prefix);
                TableWriter.listItemDefinitions(xml, table, prefix);
            }
            default -> {
                // a literal expression has no parts
            }
        }
    }

    /**
     * Declares a type: a simple type, or any type, needs nothing; another one is named by the item definition the model
     * has of it, else by one written here, once ({@code boxed_type_0}).
     */
    void declare(FeelType type, String where) {
        if (type instanceof FeelType.Simple || type instanceof FeelType.Any || written.containsKey(type)) {
            return;
        }
        var name = named.entrySet().stream().filter(entry -> entry.getValue().equals(type)).map(Map.Entry::getKey).findFirst()
                .orElse(null);
        if (name == null) {
            name = TypeWriter.freeName("boxed_type_" + written.size(), named);
            if (type instanceof FeelType.FunctionOf) {
                TypeWriter.namedType(xml, name, type);
            } else {
                TypeWriter.typeRef(xml, type, name, where);
            }
        }
        written.put(type, name);
    }

    /** The prefix of the ids of a decision table within a boxed expression. */
    String prefix(DecisionTable table) {
        return tables.get(table);
    }

    /** The attribute {@code typeRef="..."} of a type, or nothing for any type. */
    String attributeOf(FeelType type) {
        return switch (type) {
            case FeelType.Simple(var simple) -> attribute(TYPE_REF, typeName(simple));
            case FeelType.Any any -> "";
            default -> attribute(TYPE_REF, written.get(type));
        };
    }
}
