/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.attribute;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.escapeText;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.id;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.idAndName;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Writes a {@link DecisionTable} as a DMN 1.3 model with one decision, or a whole decision model
 * ({@link #write(DecisionModel)}). Cells are parsed and written
 * back as standard FEEL (quoted strings, plain numbers, {@code date("...")}, {@code time("...")},
 * {@code date and time("...")}, {@code duration("...")}, {@code true} / {@code false}), so tables written in
 * Praecepta's shorter syntax ({@code PT,ES}) become portable. Each input, column or input the cells
 * compare with, also gets an {@code inputData} element, which Drools needs to bind input values. An
 * input that holds lists is typed by a collection item definition (of an item type with its input
 * values, if it declares any), and a list column's cells are written with FEEL's list functions
 * ({@link #renderList}). An output's default value is written as its {@code defaultOutputEntry}, {@code null}
 * for an output whose default is {@code null} ({@link OutputColumn#hasDefault}).
 *
 * <p>The result is read back unchanged by {@link DmnReader}, and is evaluated identically by
 * Camunda 8 and Drools (checked by the benchmark module).
 */
public final class DmnWriter {

    // DmnWriter is the entry point, and writes a model's elements. The rest is in the package-private classes of this package:
    //   TableWriter       a decision table: its inputs, outputs and rules, and the item definitions of its list inputs
    //   CellWriter        a table's cells and values, as standard FEEL
    //   ExpressionWriter  a boxed expression, part by part
    //   BoxedTypes        the types of the parts of boxed expressions, declared before the decisions
    //   TypeWriter        typeRefs and item definitions
    //   XmlText           attributes, ids and escaped text

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String NAMESPACE = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    /** DMN 1.5's namespace, for a model with boxed expressions DMN 1.3 does not have: conditionals, iterators and filters. */
    private static final String NAMESPACE_15 = "https://www.omg.org/spec/DMN/20230324/MODEL/";
    /** The namespace of a table's model, or of a model that names none, followed by its id. */
    private static final String OWN_NAMESPACE = "https://github.com/brunopacheco1/praecepta/";
    /** The prefix of the ids of a model's input data, followed by their position. */
    private static final String INPUT_DATA = "input_data_";
    /** The prefix of the ids of a model's decisions, followed by their position. */
    private static final String DECISION = "decision_";
    /** The prefix of a business knowledge model's id. */
    private static final String KNOWLEDGE_MODEL = "knowledge_model_";
    /** The prefix of a decision service's id. */
    private static final String DECISION_SERVICE = "decision_service_";
    /** The suffix of the name of the item definition of an input data's or a decision's type. */
    private static final String TYPE_SUFFIX = "_type";
    private static final String VARIABLE = "    <variable";

    private DmnWriter() {
    }

    /**
     * The DMN XML of a table.
     *
     * @throws InvalidDecisionTableException if a cell is not valid for its column type
     */
    public static String write(DecisionTable table) {
        var id = identifier(table.name());
        var xml = new StringBuilder(XML_DECLARATION);
        xml.append("<definitions").append(attribute("xmlns", NAMESPACE)).append(idAndName("definitions_" + id, table.name()))
           .append(attribute("namespace", OWN_NAMESPACE + id)).append(">\n");
        // the table's columns, then the inputs its cells compare with, each with its inputData
        var variables = new ArrayList<>(table.inputs());
        TableWriter.namedInputs(table).forEach((name, type) -> variables.add(new InputColumn(name, type)));
        for (int v = 0; v < variables.size(); v++) {
            if (table.listInputs().contains(variables.get(v).name())) {
                TableWriter.listItemDefinitions(xml, v, variables.get(v), "");
            }
        }
        for (int v = 0; v < variables.size(); v++) {
            inputData(xml, v, variables.get(v), table);
        }
        decision(xml, id, table, variables.size());
        xml.append("</definitions>\n");
        return xml.toString();
    }

    /** The {@code inputData} of an input, which Drools needs to bind its value. */
    private static void inputData(StringBuilder xml, int v, InputColumn input, DecisionTable table) {
        xml.append("  <inputData").append(idAndName(INPUT_DATA + v, input.name())).append(">\n")
           .append(VARIABLE).append(idAndName("input_variable_" + v, input.name())).append(TableWriter.typed(input, v, table, "")).append("/>\n")
           .append("  </inputData>\n");
    }

    /** @param variables the number of inputData elements, which the decision requires */
    private static void decision(StringBuilder xml, String id, DecisionTable table, int variables) {
        xml.append("  <decision").append(idAndName(DECISION + id, table.name())).append(">\n")
           .append(VARIABLE).append(idAndName("decision_variable", table.name())).append("/>\n");
        for (int v = 0; v < variables; v++) {
            xml.append("    <informationRequirement").append(id("requirement_" + v)).append(">")
               .append("<requiredInput").append(attribute("href", "#input_data_" + v)).append("/></informationRequirement>\n");
        }
        TableWriter.decisionTable(xml, table, "");
        xml.append("  </decision>\n");
    }

    /**
     * The DMN XML of a decision model, as DMN 1.3, which {@link DmnReader#readModel} reads back as the same model: an
     * {@code inputData} per input data, typed by its type (an item definition for a list or a context, and no
     * {@code typeRef} for {@link FeelType#ANY any type}), a {@code decision} per decision, with an
     * {@code informationRequirement} per information requirement, a {@code knowledgeRequirement} per business knowledge
     * model it requires, and its logic: a decision table, written as {@link #write(DecisionTable)} writes it, a
     * {@code literalExpression}, as written, or a boxed expression, part by part, each typed by its type (a context
     * entry's, or a binding's, on its variable or parameter); the type the decision declares is its variable's; a
     * {@code businessKnowledgeModel} per business knowledge model, its logic its {@code encapsulatedLogic}; and an item
     * definition per type the model names ({@link DecisionModel#types()}), under its name, and per other type of a part
     * that is no simple type ({@code boxed_type_0}). A model with a conditional, an iterator or a filter, which DMN 1.3
     * does not have, is written in DMN 1.5's namespace. Elements are identified by their position
     * ({@code input_data_0}, {@code decision_0}, {@code knowledge_model_0}), so that any names may be told apart.
     *
     * @throws InvalidDecisionTableException if an element requires a decision, an input data or a business knowledge
     *                                       model the model does not have,
     *                                       which DMN cannot refer to; if a type is a list of lists, which an item
     *                                       definition cannot hold; or if a table's cell is not valid for its column type
     */
    public static String write(DecisionModel model) {
        var id = identifier(model.name());
        var xml = new StringBuilder(XML_DECLARATION);
        xml.append("<definitions").append(attribute("xmlns", xmlNamespace(model))).append(idAndName("definitions_" + id, model.name()))
           .append(attribute("namespace", model.namespace().isEmpty() ? OWN_NAMESPACE + id : model.namespace())).append(">\n");
        var ids = new HashMap<String, String>();
        for (var imported : model.imports()) {
            imports(xml, imported, ids);
        }
        // the item definitions come first: those of the model's own types, then those the elements need, each given its id
        model.types().forEach((name, type) -> TypeWriter.namedType(xml, name, type));
        var boxedTypes = new BoxedTypes(xml, model.types());
        var inputTypes = declareInputData(xml, model, ids);
        var decisionTypes = declareDecisions(xml, model, ids, boxedTypes);
        declareDecisionServices(model, ids, boxedTypes);
        declareKnowledgeModels(model, ids, boxedTypes);
        // then the elements
        var inputData = model.inputData();
        for (int i = 0; i < inputData.size(); i++) {
            inputData(xml, i, inputData.get(i).name(), inputTypes[i]);
        }
        var decisions = model.decisions();
        for (int d = 0; d < decisions.size(); d++) {
            decision(xml, d, decisions.get(d), decisionTypes[d], tablePrefix(d, model.types()), ids, boxedTypes);
        }
        var knowledgeModels = model.knowledgeModels();
        for (int k = 0; k < knowledgeModels.size(); k++) {
            knowledgeModel(xml, k, knowledgeModels.get(k), ids, boxedTypes);
        }
        var services = model.decisionServices();
        for (int k = 0; k < services.size(); k++) {
            decisionService(xml, k, services.get(k), ids, boxedTypes);
        }
        xml.append("</definitions>\n");
        return xml.toString();
    }

    /** DMN 1.5's namespace for a model with a conditional, an iterator or a filter, which DMN 1.3 does not have; else DMN 1.3's. */
    private static String xmlNamespace(DecisionModel model) {
        boolean dmn15 = model.decisions().stream().anyMatch(decision -> ExpressionWriter.needsDmn15(decision.logic()))
                || model.knowledgeModels().stream().anyMatch(knowledgeModel -> ExpressionWriter.needsDmn15(knowledgeModel.logic()));
        return dmn15 ? NAMESPACE_15 : NAMESPACE;
    }

    /**
     * Gives each input data its id, and writes the item definition of its type, where it needs one.
     *
     * @return the {@code typeRef} of each input data, null for any type
     */
    private static String[] declareInputData(StringBuilder xml, DecisionModel model, Map<String, String> ids) {
        var inputData = model.inputData();
        var typeRefs = new String[inputData.size()];
        for (int i = 0; i < inputData.size(); i++) {
            ids.put(inputData.get(i).name(), "#" + INPUT_DATA + i);
            typeRefs[i] = TypeWriter.elementTypeRef(xml, inputData.get(i).type(), INPUT_DATA + i + TYPE_SUFFIX, model.types(),
                    "input data '" + inputData.get(i).name() + "'");
        }
        return typeRefs;
    }

    /**
     * Gives each decision its id, and writes the item definitions it needs: those of its table's list columns, that of its
     * type, and those of the types of the parts of its boxed expression.
     *
     * @return the {@code typeRef} of each decision, null for any type
     */
    private static String[] declareDecisions(StringBuilder xml, DecisionModel model, Map<String, String> ids, BoxedTypes boxedTypes) {
        var decisions = model.decisions();
        var typeRefs = new String[decisions.size()];
        for (int d = 0; d < decisions.size(); d++) {
            var decision = decisions.get(d);
            var where = "decision '" + decision.name() + "'";
            ids.put(decision.name(), "#" + DECISION + d);
            switch (decision.logic()) {
                case DecisionLogic.Table(var table, var type) -> {
                    TableWriter.listItemDefinitions(xml, table, tablePrefix(d, model.types()));
                    typeRefs[d] = TypeWriter.elementTypeRef(xml, type, DECISION + d + TYPE_SUFFIX, model.types(), where);
                }
                default -> {
                    typeRefs[d] = TypeWriter.elementTypeRef(xml, decision.logic().type(), DECISION + d + TYPE_SUFFIX, model.types(), where);
                    boxedTypes.declare(DECISION + d + "_", decision.logic(), true, where);
                }
            }
        }
        return typeRefs;
    }

    /** Gives each decision service its id, and writes the item definition of its type, where it needs one. */
    private static void declareDecisionServices(DecisionModel model, Map<String, String> ids, BoxedTypes boxedTypes) {
        var services = model.decisionServices();
        for (int k = 0; k < services.size(); k++) {
            ids.put(services.get(k).name(), "#" + DECISION_SERVICE + k);
            boxedTypes.declare(services.get(k).type(), "decision service '" + services.get(k).name() + "'");
        }
    }

    /** Gives each business knowledge model its id, and writes the item definitions of the types of the parts of its logic. */
    private static void declareKnowledgeModels(DecisionModel model, Map<String, String> ids, BoxedTypes boxedTypes) {
        var knowledgeModels = model.knowledgeModels();
        for (int k = 0; k < knowledgeModels.size(); k++) {
            var knowledgeModel = knowledgeModels.get(k);
            ids.put(knowledgeModel.name(), "#" + KNOWLEDGE_MODEL + k);
            boxedTypes.declare(KNOWLEDGE_MODEL + k + "_", knowledgeModel.logic(), false, "business knowledge model '" + knowledgeModel.name() + "'");
        }
    }

    /**
     * The prefix of the ids of the table of the decision at {@code d}, and of the item definitions of its list columns
     * ({@link TableWriter#freePrefix}): {@code decision_3_}, unless the model names a type that it would start.
     */
    private static String tablePrefix(int d, Map<String, FeelType> named) {
        return TableWriter.freePrefix(DECISION + d, named);
    }

    /**
     * An import (DMN 1.5, 6.3.3) of a model, and the hrefs of the elements the importing model reads of it: its namespace
     * and the id {@link #write(DecisionModel)} gives each in that model's own file ({@code namespace#decision_0}).
     *
     * @throws InvalidDecisionTableException if the imported model has no namespace, by which the import names it
     */
    private static void imports(StringBuilder xml, DecisionModel.Import imported, Map<String, String> ids) {
        var model = imported.model();
        if (model.namespace().isEmpty()) {
            throw new InvalidDecisionTableException("The model imports model '%s', which has no namespace to import it by".formatted(model.name()));
        }
        xml.append("  <import").append(attribute("name", imported.name())).append(attribute("namespace", model.namespace()))
           .append(attribute("importType", NAMESPACE_15)).append("/>\n");
        var prefix = model.namespace() + "#";
        for (int i = 0; i < model.inputData().size(); i++) {
            ids.putIfAbsent(imported.qualified(model.inputData().get(i).name()), prefix + INPUT_DATA + i);
        }
        for (int d = 0; d < model.decisions().size(); d++) {
            ids.putIfAbsent(imported.qualified(model.decisions().get(d).name()), prefix + DECISION + d);
        }
        for (int k = 0; k < model.knowledgeModels().size(); k++) {
            ids.putIfAbsent(imported.qualified(model.knowledgeModels().get(k).name()), prefix + KNOWLEDGE_MODEL + k);
        }
        for (int k = 0; k < model.decisionServices().size(); k++) {
            ids.putIfAbsent(imported.qualified(model.decisionServices().get(k).name()), prefix + DECISION_SERVICE + k);
        }
    }

    /** An input data of a model, at position {@code i}: its variable, of its type. */
    private static void inputData(StringBuilder xml, int i, String name, String typeRef) {
        xml.append("  <inputData").append(idAndName(INPUT_DATA + i, name)).append(">\n")
           .append(VARIABLE).append(idAndName(INPUT_DATA + i + "_variable", name)).append(TypeWriter.optionalType(typeRef)).append("/>\n")
           .append("  </inputData>\n");
    }

    /** A decision of a model, at position {@code d}: its variable, its requirements and its logic. */
    private static void decision(StringBuilder xml, int d, DecisionModel.Decision decision, String type, String prefix,
            Map<String, String> ids, BoxedTypes boxedTypes) {
        var where = "Decision '" + decision.name() + "'";
        xml.append("  <decision").append(idAndName(DECISION + d, decision.name())).append(">\n")
           .append(VARIABLE).append(idAndName(DECISION + d + "_variable", decision.name())).append(TypeWriter.optionalType(type)).append("/>\n");
        // the required decisions, then the required inputs, then the required knowledge
        var requirements = decision.requirements();
        int information = decision.requiredDecisions().size() + decision.requiredInputs().size();
        for (int r = 0; r < requirements.size(); r++) {
            var href = href(ids, requirements.get(r), where, "requires");
            var requirementId = DECISION + d + "_requirement_" + r;
            if (r < information) {
                var kind = r < decision.requiredDecisions().size() ? "requiredDecision" : "requiredInput";
                xml.append("    <informationRequirement").append(id(requirementId)).append("><").append(kind)
                   .append(attribute("href", href)).append("/></informationRequirement>\n");
            } else {
                knowledgeRequirement(xml, requirementId, href);
            }
        }
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var tableType) -> TableWriter.decisionTable(xml, table, prefix);
            case DecisionLogic.LiteralExpression literal -> xml.append("    <literalExpression").append(id(DECISION + d + "_literal"))
                    .append("><text>").append(escapeText(literal.text())).append("</text></literalExpression>\n");
            default -> ExpressionWriter.boxed(xml, decision.logic(), null, "    ", boxedTypes);
        }
        xml.append("  </decision>\n");
    }

    /**
     * A business knowledge model at position {@code k}: its variable, of its logic's type, its logic as
     * {@code encapsulatedLogic}, and its knowledge requirements.
     */
    private static void knowledgeModel(StringBuilder xml, int k, DecisionModel.BusinessKnowledgeModel knowledgeModel, Map<String, String> ids,
            BoxedTypes types) {
        var where = "Business knowledge model '" + knowledgeModel.name() + "'";
        xml.append("  <businessKnowledgeModel").append(idAndName(KNOWLEDGE_MODEL + k, knowledgeModel.name())).append(">\n")
           .append(VARIABLE).append(idAndName(KNOWLEDGE_MODEL + k + "_variable", knowledgeModel.name()))
           .append(types.attributeOf(knowledgeModel.logic().type())).append("/>\n");
        ExpressionWriter.functionDefinition(xml, "encapsulatedLogic", knowledgeModel.logic(), "", "    ", types);
        var required = knowledgeModel.requiredKnowledge();
        for (int r = 0; r < required.size(); r++) {
            knowledgeRequirement(xml, KNOWLEDGE_MODEL + k + "_requirement_" + r, href(ids, required.get(r), where, "requires"));
        }
        xml.append("  </businessKnowledgeModel>\n");
    }

    /** A decision service at position {@code k}: its variable, of its type, and its decisions and input data, by reference. */
    private static void decisionService(StringBuilder xml, int k, DecisionModel.DecisionService service, Map<String, String> ids,
            BoxedTypes types) {
        var where = "Decision service '" + service.name() + "'";
        xml.append("  <decisionService").append(idAndName(DECISION_SERVICE + k, service.name())).append(">\n")
           .append(VARIABLE).append(idAndName(DECISION_SERVICE + k + "_variable", service.name())).append(types.attributeOf(service.type()))
           .append("/>\n");
        var kinds = List.of("outputDecision", "encapsulatedDecision", "inputDecision", "inputData");
        var lists = List.of(service.outputDecisions(), service.encapsulatedDecisions(), service.inputDecisions(), service.inputData());
        for (int l = 0; l < kinds.size(); l++) {
            for (var name : lists.get(l)) {
                var href = href(ids, name, where, "refers to");
                xml.append("    <").append(kinds.get(l)).append(attribute("href", href)).append("/>\n");
            }
        }
        xml.append("  </decisionService>\n");
    }

    /** A knowledge requirement, which names the business knowledge model required by its href. */
    private static void knowledgeRequirement(StringBuilder xml, String requirementId, String href) {
        xml.append("    <knowledgeRequirement").append(id(requirementId)).append("><requiredKnowledge")
           .append(attribute("href", href)).append("/></knowledgeRequirement>\n");
    }

    /**
     * The href of the element of that name, which {@code where} requires or refers to.
     *
     * @param verb what {@code where} does of the element, as the error says it: {@code requires}, {@code refers to}
     * @throws InvalidDecisionTableException if the model has no such element, which DMN cannot refer to
     */
    private static String href(Map<String, String> ids, String name, String where, String verb) {
        var href = ids.get(name);
        if (href == null) {
            throw new InvalidDecisionTableException("%s %s '%s', which is not in the model, so DMN cannot refer to it"
                    .formatted(where, verb, name));
        }
        return href;
    }

    /**
     * Standard FEEL text for parsed unary tests.
     *
     * @throws IllegalArgumentException if a test is a {@link UnaryTests.NullElement}, which only a list column's
     *                                  cell holds ({@link #renderList})
     */
    public static String render(UnaryTests tests) {
        return CellWriter.render(tests);
    }

    /**
     * Standard FEEL text for the cell of a column that holds lists, which tests the list's elements:
     * {@code "PT", >= 18, null} becomes
     * {@code list contains(?, "PT") or some x in ? satisfies x >= 18 or ? = null}, read back by
     * {@code CellParser#parseFeelList}; a {@link UnaryTests.NullElement} is written {@code list contains(?, null)}.
     */
    public static String renderList(UnaryTests tests) {
        return CellWriter.renderList(tests);
    }
}
