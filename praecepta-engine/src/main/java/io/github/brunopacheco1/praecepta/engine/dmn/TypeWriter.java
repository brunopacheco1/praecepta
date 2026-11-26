/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.attribute;

import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Writes types: the name of a simple type, and the item definition of any other type (a list, a context, a function
 * type), which a {@code typeRef} then names.
 */
final class TypeWriter {

    static final String TYPE_REF = "typeRef";
    static final String TYPE_REF_END = "</typeRef>\n";
    static final String ITEM_DEFINITION = "  <itemDefinition";
    static final String ITEM_DEFINITION_END = "  </itemDefinition>\n";

    private TypeWriter() {
    }

    /**
     * An item definition for a type the model names ({@link DecisionModel#types()}): a function type as a
     * {@code functionItem}, whose parameters and output are of simple types or {@code Any}; any other type as
     * {@link #itemDefinition} writes it. A range, or the type of null, has no item definition, and is not written.
     */
    static void namedType(StringBuilder xml, String name, FeelType type) {
        switch (type) {
            case FeelType.RangeOf range -> {
                // no item definition holds a range type
            }
            case FeelType.Null nothing -> {
                // nor the type of null
            }
            case FeelType.FunctionOf(var parameters, var result) -> {
                xml.append(ITEM_DEFINITION).append(attribute("name", name)).append(">\n    <functionItem")
                   .append(attribute("outputTypeRef", simpleTypeRef(result))).append(">\n");
                for (int i = 0; i < parameters.size(); i++) {
                    xml.append("      <parameters").append(attribute("name", "p" + i)).append(attribute(TYPE_REF, simpleTypeRef(parameters.get(i))))
                       .append("/>\n");
                }
                xml.append("    </functionItem>\n  </itemDefinition>\n");
            }
            case FeelType.Simple(var simple) -> xml.append(ITEM_DEFINITION).append(attribute("name", name)).append(">\n    <typeRef>")
                    .append(typeName(simple)).append(TYPE_REF_END).append(ITEM_DEFINITION_END);
            default -> itemDefinition(xml, "itemDefinition", name, type, "  ", "type '" + name + "'");
        }
    }

    /** A simple type's name, or {@code Any} for any other type. */
    private static String simpleTypeRef(FeelType type) {
        return type instanceof FeelType.Simple(var simple) ? typeName(simple) : "Any";
    }

    /** The attribute {@code typeRef="..."}, or nothing for a value of any type. */
    static String optionalType(String typeRef) {
        return typeRef == null ? "" : attribute(TYPE_REF, typeRef);
    }

    /**
     * The {@code typeRef} of an element's type, as {@link #typeRef} gives it, where the model's own types are written
     * already: a type of the model under the name an item definition would be given is that one, not written twice, and
     * a type the model names otherwise takes the next name that is free ({@code input_data_0_type_1}).
     */
    static String elementTypeRef(StringBuilder xml, FeelType type, String name, Map<String, FeelType> named, String where) {
        if (type instanceof FeelType.Simple || type instanceof FeelType.Any) {
            return typeRef(xml, type, name, where);
        }
        if (type.equals(named.get(name))) {
            return name;
        }
        return typeRef(xml, type, freeName(name, named), where);
    }

    /**
     * The {@code typeRef} of a type: a built-in type's name, or the name of an item definition written for a list or a
     * context; null for any type.
     *
     * @param name the item definition's name, if it needs one
     */
    static String typeRef(StringBuilder xml, FeelType type, String name, String where) {
        return switch (type) {
            case FeelType.Simple(var simple) -> typeName(simple);
            case FeelType.Any any -> null;
            default -> {
                itemDefinition(xml, "itemDefinition", name, type, "  ", where);
                yield name;
            }
        };
    }

    /** An item definition, or a component of one, of a type: a collection of its element type, components for a context. */
    private static void itemDefinition(StringBuilder xml, String tag, String name, FeelType type, String indent, String where) {
        var body = type instanceof FeelType.ListOf(var element) ? element : type;
        if (body instanceof FeelType.ListOf) {
            throw new InvalidDecisionTableException("The type of %s is a list of lists, which an item definition cannot hold".formatted(where));
        }
        xml.append(indent).append('<').append(tag).append(attribute("name", name));
        if (body != type) {
            xml.append(attribute("isCollection", "true"));
        }
        xml.append(">\n");
        switch (body) {
            case FeelType.Simple(var simple) -> xml.append(indent).append("  <typeRef>").append(typeName(simple)).append(TYPE_REF_END);
            case FeelType.Context(var components) -> components.forEach((component, componentType) ->
                    itemDefinition(xml, "itemComponent", component, componentType, indent + "  ", where));
            default -> xml.append(indent).append("  <typeRef>Any</typeRef>\n");
        }
        xml.append(indent).append("</").append(tag).append(">\n");
    }

    /**
     * A name for an item definition: {@code name}, or the first of {@code name_1}, {@code name_2}... that no type the
     * model names has.
     */
    static String freeName(String name, Map<String, FeelType> named) {
        var free = name;
        for (int k = 1; named.containsKey(free); k++) {
            free = name + "_" + k;
        }
        return free;
    }

    /** A column type's name in DMN. */
    static String typeName(ColumnType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMBER -> "number";
            case DATE -> "date";
            case BOOLEAN -> "boolean";
            case TIME -> "time";
            case DATE_TIME -> "dateTime";
            case DAY_TIME_DURATION -> "dayTimeDuration";
            case YEAR_MONTH_DURATION -> "yearMonthDuration";
        };
    }
}
