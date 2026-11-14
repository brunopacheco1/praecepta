/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.w3c.dom.Element;

/**
 * A TCK model as the runner reads it, by DMN element name: its decisions, business knowledge models, decision
 * services, input data and item definitions. Names and ids are the model's own; an element of an imported model
 * (whose href names another namespace) is not found here.
 */
final class DmnModel {

    /** The elements of a decision's logic that are boxed expressions, by their DMN element name. */
    static final Set<String> EXPRESSIONS = Set.of("decisionTable", "literalExpression", "context", "invocation", "relation", "list",
            "functionDefinition", "conditional", "filter", "for", "some", "every");

    private final Path file;
    private final Element root;
    private final Map<String, Element> decisions = new LinkedHashMap<>();
    private final Map<String, Element> knowledgeModels = new LinkedHashMap<>();
    private final Map<String, Element> services = new LinkedHashMap<>();
    private final Map<String, Element> inputData = new LinkedHashMap<>();
    private final Map<String, Element> itemDefinitions = new HashMap<>();
    private final Map<String, Element> byId = new HashMap<>();
    private final boolean imports;
    private final Set<String> tables;

    DmnModel(Path file) {
        this.file = file;
        this.root = TckXml.parse(file);
        index("decision", decisions);
        index("businessKnowledgeModel", knowledgeModels);
        index("decisionService", services);
        index("inputData", inputData);
        TckXml.children(root, "itemDefinition").forEach(item -> itemDefinitions.put(item.getAttribute("name"), item));
        this.imports = !TckXml.children(root, "import").isEmpty();
        this.tables = decisions.entrySet().stream()
                .filter(entry -> "decisionTable".equals(logicKind(entry.getValue())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void index(String localName, Map<String, Element> byName) {
        for (var element : TckXml.children(root, localName)) {
            byName.put(element.getAttribute("name"), element);
            byId.put(element.getAttribute("id"), element);
        }
    }

    Path file() {
        return file;
    }

    Element root() {
        return root;
    }

    /** The decisions whose logic is a decision table. */
    Set<String> tables() {
        return tables;
    }

    /** The names of every decision. */
    Set<String> decisions() {
        return decisions.keySet();
    }

    Element decision(String name) {
        return decisions.get(name);
    }

    Element knowledgeModel(String name) {
        return knowledgeModels.get(name);
    }

    Element service(String name) {
        return services.get(name);
    }

    Element inputData(String name) {
        return inputData.get(name);
    }

    Element itemDefinition(String name) {
        return itemDefinitions.get(name);
    }

    /** Whether the model imports others. */
    boolean imports() {
        return imports;
    }

    /** The decision, knowledge model or decision service an href ({@code #id}) names, or null (another model's). */
    Element resolve(String href) {
        return href.startsWith("#") ? byId.get(href.substring(1)) : null;
    }

    /** The element holding a decision's logic (a {@code decisionTable}, a {@code literalExpression}...), or null. */
    static Element logic(Element owner) {
        for (var child : TckXml.children(owner)) {
            if (EXPRESSIONS.contains(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    /** The DMN element name of a decision's logic, or null if it has none. */
    static String logicKind(Element owner) {
        var logic = logic(owner);
        return logic == null ? null : logic.getLocalName();
    }

    /** The hrefs of a decision's or service's requirements of one kind ({@code requiredDecision}, {@code requiredKnowledge}). */
    static List<String> requirements(Element owner, String kind) {
        var hrefs = new ArrayList<String>();
        for (var requirement : TckXml.children(owner)) {
            for (var required : TckXml.children(requirement, kind)) {
                hrefs.add(required.getAttribute("href"));
            }
        }
        return hrefs;
    }

    /** The {@code typeRef} of a decision's or an input's variable, without a prefix; empty if none. */
    static String typeRef(Element owner) {
        var variables = TckXml.children(owner, "variable");
        var type = variables.isEmpty() ? "" : variables.getFirst().getAttribute("typeRef");
        return type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
    }
}
