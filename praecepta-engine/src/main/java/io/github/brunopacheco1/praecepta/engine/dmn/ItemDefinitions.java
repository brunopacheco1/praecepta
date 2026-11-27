/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.INPUT_DATA;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.child;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.descendants;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nonBlankText;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Reads a model's item definitions, by name, and resolves the types that name them: the type of a column
 * ({@link #resolve}), and the type of an input data, a decision or a part of a boxed expression ({@link #feelType}). A
 * column may name an item definition whose base type is a built-in type or another such item definition, or a
 * collection of one; for a column, structured types (with item components), collections of collections and function
 * types are rejected.
 */
final class ItemDefinitions {

    /**
     * A column's type: a built-in type, the allowed values of the item definitions it was reached
     * through (the most derived one that declares any), or null, and whether it is a list of that type.
     */
    record Type(ColumnType columnType, String allowedValues, boolean list) {
    }

    private final Map<String, Element> byName = new LinkedHashMap<>();
    private final Element root;
    /** The names of the model's input data and decisions, found once for all its tables; null until asked for. */
    private Set<String> elementNames;
    private FeelExpression.Scope expressionScope;
    private ModelTypes modelTypes;
    /** Every type the item definitions define ({@link #all}), read once. */
    private Map<String, FeelType> all;

    /** The item definitions of the models the model imports, by import name. */
    private final Map<String, ItemDefinitions> imported = new LinkedHashMap<>();

    ItemDefinitions(Element root) {
        this.root = root;
        children(root, "itemDefinition").forEach(definition -> byName.putIfAbsent(definition.getAttribute("name"), definition));
    }

    /** Adds the item definitions of the models the model imports, by import name, which a {@code typeRef} may then name. */
    void importTypes(Map<String, ItemDefinitions> byImportName) {
        imported.putAll(byImportName);
    }

    /** The names of the model's input data and decisions, which its expressions may read, found once. */
    Set<String> elementNames() {
        if (elementNames == null) {
            var names = new LinkedHashSet<String>();
            for (var kind : List.of(INPUT_DATA, DECISION)) {
                descendants(root, kind).forEach(element -> {
                    var name = element.getAttribute("name").strip();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                });
            }
            elementNames = names;
        }
        return elementNames;
    }

    /** Those names in scope, for an input expression, indexed once. */
    FeelExpression.Scope expressionScope() {
        if (expressionScope == null) {
            expressionScope = FeelExpression.scope(elementNames(), Map.of());
        }
        return expressionScope;
    }

    /** The declared types of the model's input data and decisions ({@link ModelTypes#of}), read once. */
    ModelTypes modelTypes() {
        if (modelTypes == null) {
            modelTypes = ModelTypes.of(root, this);
        }
        return modelTypes;
    }

    /**
     * Follows the chain of item definitions to a built-in type, one step at a time, so a long chain
     * costs no stack. The allowed values are those of the most derived definition that has any; one
     * link of the chain may be a collection, whose items are of the type it names, and the allowed
     * values then apply to the items (they must come after the collection).
     */
    Type resolve(String typeRef, String where) {
        String allowedValues = null;
        String allowedBy = null;
        boolean list = false;
        var seen = new HashSet<String>();
        var ref = typeRef;
        var builtIn = builtInType(ref);
        while (builtIn.isEmpty()) {
            // the definition's name as written, or without a namespace prefix (tns:tAge)
            var name = byName.containsKey(ref.strip()) ? ref.strip() : localName(ref);
            var definition = itemDefinition(name, ref, where);
            if (!seen.add(name)) {
                throw new InvalidDecisionTableException("%s: item definition '%s' is defined in terms of itself".formatted(where, name));
            }
            var allowed = nonBlankText(definition, "allowedValues");
            if (Boolean.parseBoolean(definition.getAttribute("isCollection").strip())) {
                checkCollection(name, list, allowed != null || allowedValues != null, where);
                list = true;
            }
            if (allowedValues == null && allowed != null) {
                allowedValues = allowed.strip();
                allowedBy = name;
            }
            ref = child(definition, TYPE_REF).getTextContent();
            builtIn = builtInType(ref);
        }
        var type = builtIn.get();
        if (allowedValues != null) {
            Literals.parseCell(allowedValues, type, where + ", allowed values of type '" + allowedBy + "'");
        }
        return new Type(type, allowedValues, list);
    }

    /**
     * Checks a collection in a chain of item definitions: it is the only one, and neither it nor a
     * definition derived from it declares allowed values, which would constrain the collection as a whole.
     *
     * @param inList      whether an earlier link of the chain is a collection already
     * @param constrained whether it, or a definition derived from it, declares allowed values
     */
    private static void checkCollection(String name, boolean inList, boolean constrained, String where) {
        if (inList) {
            throw new InvalidDecisionTableException("%s: type '%s' is a collection of collections, which is not supported"
                    .formatted(where, name));
        }
        if (constrained) {
            throw new InvalidDecisionTableException(("%s: type '%s' constrains a collection as a whole, which is not supported; "
                    + "declare the allowed values of its items in their item definition").formatted(where, name));
        }
    }

    /** A column's type ({@link #resolve}), or none if Praecepta cannot hold it, in which case no input value can be bound to it either. */
    Optional<Type> typeIfSupported(String typeRef, String where) {
        try {
            return Optional.of(resolve(typeRef, where));
        } catch (InvalidDecisionTableException e) {
            return Optional.empty();
        }
    }

    /**
     * The type of an input data or a decision ({@link FeelType}): a built-in type, {@code Any}, or an item definition,
     * which may be a collection, have components, or both, nested up to {@value DmnReader#MAX_TYPE_DEPTH} levels; any type if
     * {@code typeRef} is blank. An item definition's allowed values are not part of the type.
     */
    FeelType feelType(String typeRef, String where) {
        return typeRef.isBlank() ? FeelType.ANY : typeOf(typeRef, where, 0, new HashSet<>());
    }

    /**
     * The type a decision's variable declares, for its decision table's value to be bound to; any type if it declares
     * none, or one Praecepta cannot hold, in which case the table's value is passed on as its outputs give it.
     */
    FeelType declaredTableType(Element decision, String where) {
        try {
            return feelType(variableTypeRef(decision), where);
        } catch (InvalidDecisionTableException e) {
            return FeelType.ANY;
        }
    }

    /** @param enclosing the item definitions being read, which a type within them may not name */
    private FeelType typeOf(String typeRef, String where, int depth, Set<String> enclosing) {
        var builtIn = builtInType(typeRef);
        if (builtIn.isPresent()) {
            return FeelType.of(builtIn.get());
        }
        if (localName(typeRef).equals("Any")) {
            return FeelType.ANY;
        }
        var name = byName.containsKey(typeRef.strip()) ? typeRef.strip() : localName(typeRef);
        var definition = byName.get(name);
        if (definition == null) {
            for (var entry : imported.entrySet()) {
                var prefix = entry.getKey().isEmpty() ? "" : entry.getKey() + ".";
                var rest = typeRef.strip();
                if (rest.startsWith(prefix) && entry.getValue().byName.containsKey(rest.substring(prefix.length()))) {
                    return entry.getValue().feelType(rest.substring(prefix.length()), where);
                }
            }
            throw new InvalidDecisionTableException("%s: type '%s' is not a built-in type or an item definition of the model"
                    .formatted(where, typeRef));
        }
        if (!enclosing.add(name)) {
            throw new InvalidDecisionTableException("%s: item definition '%s' is defined in terms of itself".formatted(where, name));
        }
        var type = definitionType(definition, name, where, depth + 1, enclosing);
        enclosing.remove(name);
        return type;
    }

    /**
     * Every type the model's item definitions define, by name, for FEEL's {@code instance of} and function parameters
     * (DMN 1.5, 10.3.2.9): function types (a {@code functionItem}) among them; a definition Praecepta cannot read is
     * left out.
     */
    Map<String, FeelType> all() {
        if (all == null) {
            all = Collections.unmodifiableMap(allTypes());
        }
        return all;
    }

    private Map<String, FeelType> allTypes() {
        var read = new LinkedHashMap<String, FeelType>();
        for (var name : byName.keySet()) {
            try {
                read.put(name, typeOf(name, "Item definition '" + name + "'", 0, new HashSet<>()));
            } catch (InvalidDecisionTableException e) {
                // a type Praecepta cannot read: an expression that names it is reported
            }
        }
        return read;
    }

    /** A function item's type (DMN 1.5, 7.3.4): its parameters' types and its output's, {@code Any} where it names none. */
    private FeelType functionType(Element function, String where, int depth, Set<String> enclosing) {
        var parameters = new ArrayList<FeelType>();
        for (var parameter : children(function, "parameters")) {
            var typeRef = parameter.getAttribute(TYPE_REF);
            parameters.add(typeRef.isBlank() ? FeelType.ANY : typeOf(typeRef, where, depth + 1, enclosing));
        }
        var output = function.getAttribute("outputTypeRef");
        return new FeelType.FunctionOf(parameters, output.isBlank() ? FeelType.ANY : typeOf(output, where, depth + 1, enclosing));
    }

    /** The type an item definition, or one of its components, defines. */
    private FeelType definitionType(Element definition, String name, String where, int depth, Set<String> enclosing) {
        if (depth > DmnReader.MAX_TYPE_DEPTH) {
            throw new InvalidDecisionTableException("%s: type '%s' nests more than %d levels of item definitions and components"
                    .formatted(where, name, DmnReader.MAX_TYPE_DEPTH));
        }
        var function = child(definition, "functionItem");
        if (function != null) {
            return functionType(function, where, depth, enclosing);
        }
        var components = children(definition, "itemComponent");
        FeelType type;
        if (!components.isEmpty()) {
            var types = new LinkedHashMap<String, FeelType>();
            for (var component : components) {
                var componentName = component.getAttribute("name");
                types.put(componentName, definitionType(component, name + "." + componentName, where, depth + 1, enclosing));
            }
            type = new FeelType.Context(types);
        } else {
            var base = child(definition, TYPE_REF);
            // an item definition, or a component, with neither a base type nor components may hold any value (the DMN
            // TCK's 0031-user-defined-functions and 1120-feel-duration-function)
            type = base == null || base.getTextContent().isBlank() ? FeelType.ANY : typeOf(base.getTextContent(), where, depth, enclosing);
        }
        // a collection of a collection is a list of lists (the DMN TCK's 0009-append-flatten)
        if (Boolean.parseBoolean(definition.getAttribute("isCollection").strip())) {
            type = new FeelType.ListOf(type);
        }
        return type;
    }

    /** The item definition of that name, if it is a simple type or a collection: one with a base type and no components. */
    private Element itemDefinition(String name, String typeRef, String where) {
        var definition = byName.get(name);
        if (definition == null) {
            throw new InvalidDecisionTableException(
                    "%s: type '%s' is not supported (string, number, date, boolean, time, dateTime, dayTimeDuration, yearMonthDuration and item definitions of them are)"
                            .formatted(where, typeRef));
        }
        if (!children(definition, "itemComponent").isEmpty()) {
            throw new InvalidDecisionTableException(
                    "%s: type '%s' is a structured item definition (with components), which is not supported".formatted(where, name));
        }
        var base = child(definition, TYPE_REF);
        if (base == null || base.getTextContent().isBlank()) {
            throw new InvalidDecisionTableException(
                    "%s: type '%s' is not supported; its item definition has no base type".formatted(where, name));
        }
        return definition;
    }

    /** A built-in type, by its FEEL or XML Schema name, with or without a namespace prefix. */
    private static Optional<ColumnType> builtInType(String typeRef) {
        return Optional.ofNullable(switch (localName(typeRef).toLowerCase(Locale.ROOT)) {
            case "", "string" -> ColumnType.STRING;
            case "number", "integer", "long", "double", "decimal", "int" -> ColumnType.NUMBER;
            case "date" -> ColumnType.DATE;
            case "boolean" -> ColumnType.BOOLEAN;
            case "time" -> ColumnType.TIME;
            case "datetime", "date and time" -> ColumnType.DATE_TIME;
            case "daytimeduration", "days and time duration" -> ColumnType.DAY_TIME_DURATION;
            case "yearmonthduration", "years and months duration" -> ColumnType.YEAR_MONTH_DURATION;
            default -> null;
        });
    }

    /** A type name without its namespace prefix ({@code feel:string} is {@code string}). */
    private static String localName(String typeRef) {
        return typeRef.substring(typeRef.indexOf(':') + 1).strip();
    }
}
