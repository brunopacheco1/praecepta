/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.HashSet;
import java.util.Locale;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * The kind of value a {@code typeRef} names in a model: one of FEEL's simple types, or a structure, a collection, a
 * function, any value, or a type the model does not define. Item definitions that only rename a type
 * ({@code tName} of {@code string}) are followed to it.
 */
sealed interface ItemType {

    /** A FEEL simple type Praecepta has. */
    record Simple(ColumnType type) implements ItemType {
    }

    /** An item definition with components: a context. */
    record Structure() implements ItemType {
    }

    /** An item definition that is a collection, or FEEL's {@code list}. */
    record Collection() implements ItemType {
    }

    /** An item definition that is a function, or FEEL's {@code function}. */
    record Function() implements ItemType {
    }

    /** No type ({@code Any}, or none given): the value's own type decides. */
    record Any() implements ItemType {
    }

    /** A name the model does not define, such as a type of an imported model. */
    record Unknown(String name) implements ItemType {
    }

    /** How deep item definitions may rename one another before the runner gives up (a cycle, say). */
    int MAX_RENAMES = 16;

    /** The type a {@code typeRef} names (without a prefix, as {@link DmnModel#typeRef} gives it). */
    static ItemType of(DmnModel model, String typeRef) {
        var name = typeRef;
        var seen = new HashSet<String>();
        while (seen.size() < MAX_RENAMES && seen.add(name)) {
            var builtIn = builtIn(name);
            if (builtIn != null) {
                return builtIn;
            }
            var item = model.itemDefinition(name);
            if (item == null) {
                return new Unknown(name);
            }
            var defined = defined(item);
            if (defined != null) {
                return defined;
            }
            name = renamed(item);
        }
        return new Unknown(typeRef);
    }

    /** What an item definition is, unless it renames another type. */
    private static ItemType defined(Element item) {
        if ("true".equals(item.getAttribute("isCollection"))) {
            return new Collection();
        }
        if (!TckXml.children(item, "itemComponent").isEmpty()) {
            return new Structure();
        }
        if (!TckXml.children(item, "functionItem").isEmpty()) {
            return new Function();
        }
        return renamed(item).isEmpty() ? new Any() : null;
    }

    /** The type an item definition renames, from its {@code typeRef} element or attribute, without a prefix. */
    private static String renamed(Element item) {
        var elements = TckXml.children(item, "typeRef");
        var type = elements.isEmpty() ? item.getAttribute("typeRef") : elements.getFirst().getTextContent().strip();
        return type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
    }

    /** FEEL's and XML Schema's names of the types DMN models use ({@code dateTime}, {@code date and time}), or null for another name. */
    static ItemType builtIn(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "", "any" -> new Any();
            case "string" -> new Simple(ColumnType.STRING);
            case "number", "decimal", "integer", "int", "long", "double", "float" -> new Simple(ColumnType.NUMBER);
            case "boolean" -> new Simple(ColumnType.BOOLEAN);
            case "date" -> new Simple(ColumnType.DATE);
            case "time" -> new Simple(ColumnType.TIME);
            case "datetime", "date and time" -> new Simple(ColumnType.DATE_TIME);
            case "daytimeduration", "days and time duration" -> new Simple(ColumnType.DAY_TIME_DURATION);
            case "yearmonthduration", "years and months duration" -> new Simple(ColumnType.YEAR_MONTH_DURATION);
            case "context" -> new Structure();
            case "list" -> new Collection();
            case "function" -> new Function();
            default -> null;
        };
    }
}
