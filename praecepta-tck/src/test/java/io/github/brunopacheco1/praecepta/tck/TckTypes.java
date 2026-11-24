/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.LinkedHashMap;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * The FEEL type a {@code typeRef} names in a model the engine does not read as a whole, for {@link LiteralExpressionEvaluator}
 * to bind a decision's value to it as DMN 1.5, 10.3.2.9.4, does: a built-in type, or an item definition, a collection of
 * a type, components (a context), nested; {@link FeelType#ANY} for anything else (a function type, a type of another
 * model) and beyond a few levels.
 */
final class TckTypes {

    private static final int MAX_DEPTH = 16;

    private TckTypes() {
    }

    /** The type a {@code typeRef} (without a prefix) names. */
    static FeelType of(DmnModel model, String typeRef) {
        return of(model, typeRef, 0);
    }

    private static FeelType of(DmnModel model, String typeRef, int depth) {
        if (depth > MAX_DEPTH) {
            return FeelType.ANY;
        }
        if (ItemType.builtIn(typeRef) instanceof ItemType.Simple(var simple)) {
            return FeelType.of(simple);
        }
        var definition = model.itemDefinition(typeRef);
        return definition == null ? FeelType.ANY : definition(model, definition, depth + 1);
    }

    private static FeelType definition(DmnModel model, Element definition, int depth) {
        if (!TckXml.children(definition, "functionItem").isEmpty()) {
            return FeelType.ANY;
        }
        var components = TckXml.children(definition, "itemComponent");
        FeelType type;
        if (!components.isEmpty()) {
            var types = new LinkedHashMap<String, FeelType>();
            for (var component : components) {
                types.put(component.getAttribute("name"), depth > MAX_DEPTH ? FeelType.ANY : definition(model, component, depth + 1));
            }
            type = new FeelType.Context(types);
        } else {
            var typeRefs = TckXml.children(definition, "typeRef");
            var typeRef = typeRefs.isEmpty() ? definition.getAttribute("typeRef") : typeRefs.getFirst().getTextContent().strip();
            type = of(model, typeRef.contains(":") ? typeRef.substring(typeRef.indexOf(':') + 1) : typeRef, depth);
        }
        return "true".equals(definition.getAttribute("isCollection")) ? new FeelType.ListOf(type) : type;
    }
}
