/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

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

    private static final String NAMESPACE = "https://www.omg.org/spec/DMN/20191111/MODEL/";
    /** DMN 1.5's namespace, for a model with boxed expressions DMN 1.3 does not have: conditionals, iterators and filters. */
    private static final String NAMESPACE_15 = "https://www.omg.org/spec/DMN/20230324/MODEL/";
    /** The prefix of the ids and item definition names of the table's inputs, followed by their number. */
    private static final String INPUT = "input_";
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
    private static final String TYPE_REF = "typeRef";
    private static final String TYPE_REF_END = "</typeRef>\n";
    private static final String ITEM_DEFINITION = "  <itemDefinition";
    private static final String ITEM_DEFINITION_END = "  </itemDefinition>\n";

    private DmnWriter() {
    }

    /**
     * The DMN XML of a table.
     *
     * @throws InvalidDecisionTableException if a cell is not valid for its column type
     */
    public static String write(DecisionTable table) {
        var id = identifier(table.name());
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions").append(attribute("xmlns", NAMESPACE)).append(idAndName("definitions_" + id, table.name()))
           .append(attribute("namespace", "https://github.com/brunopacheco1/praecepta/" + id)).append(">\n");
        // the table's columns, then the inputs its cells compare with, each with its inputData
        var variables = new ArrayList<>(table.inputs());
        namedInputs(table).forEach((name, type) -> variables.add(new InputColumn(name, type)));
        for (int v = 0; v < variables.size(); v++) {
            if (table.listInputs().contains(variables.get(v).name())) {
                listItemDefinitions(xml, v, variables.get(v), "");
            }
        }
        for (int v = 0; v < variables.size(); v++) {
            inputData(xml, v, variables.get(v), table);
        }
        decision(xml, id, table, variables.size());
        xml.append("</definitions>\n");
        return xml.toString();
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
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        var namespace = model.decisions().stream().anyMatch(decision -> needsDmn15(decision.logic()))
                || model.knowledgeModels().stream().anyMatch(knowledgeModel -> needsDmn15(knowledgeModel.logic())) ? NAMESPACE_15 : NAMESPACE;
        xml.append("<definitions").append(attribute("xmlns", namespace)).append(idAndName("definitions_" + id, model.name()))
           .append(attribute("namespace", model.namespace().isEmpty() ? "https://github.com/brunopacheco1/praecepta/" + id : model.namespace())).append(">\n");
        var ids = new HashMap<String, String>();
        for (var imported : model.imports()) {
            imports(xml, imported, ids);
        }
        model.types().forEach((name, type) -> namedType(xml, name, type));
        var boxedTypes = new BoxedTypes(xml, model.types());
        var inputData = model.inputData();
        var decisions = model.decisions();
        var inputTypes = new String[inputData.size()];
        for (int i = 0; i < inputData.size(); i++) {
            ids.put(inputData.get(i).name(), "#" + INPUT_DATA + i);
            inputTypes[i] = elementTypeRef(xml, inputData.get(i).type(), INPUT_DATA + i + TYPE_SUFFIX, model.types(),
                    "input data '" + inputData.get(i).name() + "'");
        }
        var decisionTypes = new String[decisions.size()];
        var prefixes = new String[decisions.size()];
        for (int d = 0; d < decisions.size(); d++) {
            prefixes[d] = tablePrefix(d, model.types());
            var decision = decisions.get(d);
            ids.put(decision.name(), "#" + DECISION + d);
            switch (decision.logic()) {
                case DecisionLogic.Table(var table, var type) -> {
                    listItemDefinitions(xml, table, prefixes[d]);
                    decisionTypes[d] = elementTypeRef(xml, type, DECISION + d + TYPE_SUFFIX, model.types(), "decision '" + decision.name() + "'");
                }
                default -> {
                    decisionTypes[d] = elementTypeRef(xml, decision.logic().type(), DECISION + d + TYPE_SUFFIX, model.types(),
                            "decision '" + decision.name() + "'");
                    boxedTypes.declare(DECISION + d + "_", decision.logic(), true, "decision '" + decision.name() + "'");
                }
            }
        }
        var services = model.decisionServices();
        for (int k = 0; k < services.size(); k++) {
            ids.put(services.get(k).name(), "#" + DECISION_SERVICE + k);
            boxedTypes.declare(services.get(k).type(), "decision service '" + services.get(k).name() + "'");
        }
        var knowledgeModels = model.knowledgeModels();
        for (int k = 0; k < knowledgeModels.size(); k++) {
            var knowledgeModel = knowledgeModels.get(k);
            ids.put(knowledgeModel.name(), "#" + KNOWLEDGE_MODEL + k);
            boxedTypes.declare(KNOWLEDGE_MODEL + k + "_", knowledgeModel.logic(), false, "business knowledge model '" + knowledgeModel.name() + "'");
        }
        for (int i = 0; i < inputData.size(); i++) {
            var name = inputData.get(i).name();
            xml.append("  <inputData").append(idAndName(INPUT_DATA + i, name)).append(">\n")
               .append(VARIABLE).append(idAndName(INPUT_DATA + i + "_variable", name)).append(optionalType(inputTypes[i])).append("/>\n")
               .append("  </inputData>\n");
        }
        for (int d = 0; d < decisions.size(); d++) {
            decision(xml, d, decisions.get(d), decisionTypes[d], prefixes[d], ids, boxedTypes);
        }
        for (int k = 0; k < knowledgeModels.size(); k++) {
            knowledgeModel(xml, k, knowledgeModels.get(k), ids, boxedTypes);
        }
        for (int k = 0; k < services.size(); k++) {
            decisionService(xml, k, services.get(k), ids, boxedTypes);
        }
        xml.append("</definitions>\n");
        return xml.toString();
    }

    /**
     * An item definition for a type the model names ({@link DecisionModel#types()}): a function type as a
     * {@code functionItem}, whose parameters and output are of simple types or {@code Any}; any other type as
     * {@link #itemDefinition} writes it. A range, or the type of null, has no item definition, and is not written.
     */
    private static void namedType(StringBuilder xml, String name, FeelType type) {
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

    /** The item definitions of a table's columns of lists. */
    private static void listItemDefinitions(StringBuilder xml, DecisionTable table, String prefix) {
        var columns = table.inputs();
        for (int c = 0; c < columns.size(); c++) {
            if (table.listInputs().contains(columns.get(c).name())) {
                listItemDefinitions(xml, c, columns.get(c), prefix);
            }
        }
    }

    /**
     * The prefix of the ids of the table of the decision at {@code d}, and of the item definitions of its list columns:
     * {@code decision_3_}; or, where the model names a type it would start ({@code decision_3_input_0_list}), the first
     * of {@code decision_3_1_}, {@code decision_3_2_}... that no type's name starts, so that no item definition is
     * written twice.
     */
    private static String tablePrefix(int d, Map<String, FeelType> named) {
        var prefix = DECISION + d + "_";
        for (int k = 1; startsAName(named, prefix + INPUT); k++) {
            prefix = DECISION + d + "_" + k + "_";
        }
        return prefix;
    }

    private static boolean startsAName(Map<String, FeelType> named, String start) {
        return named.keySet().stream().anyMatch(name -> name.startsWith(start));
    }

    /** A decision of a model, at position {@code d}: its variable, its information requirements and its logic. */
    private static void decision(StringBuilder xml, int d, DecisionModel.Decision decision, String type, String prefix,
            Map<String, String> ids, BoxedTypes boxedTypes) {
        var where = "Decision '" + decision.name() + "'";
        xml.append("  <decision").append(idAndName(DECISION + d, decision.name())).append(">\n")
           .append(VARIABLE).append(idAndName(DECISION + d + "_variable", decision.name())).append(optionalType(type)).append("/>\n");
        var requirements = decision.requirements();
        int information = decision.requiredDecisions().size() + decision.requiredInputs().size();
        for (int r = 0; r < requirements.size(); r++) {
            var required = requirements.get(r);
            var href = ids.get(required);
            if (href == null) {
                throw new InvalidDecisionTableException("%s requires '%s', which is not in the model, so DMN cannot refer to it"
                        .formatted(where, required));
            }
            if (r >= information) {
                xml.append("    <knowledgeRequirement").append(id(DECISION + d + "_requirement_" + r)).append("><requiredKnowledge")
                   .append(attribute("href", href)).append("/></knowledgeRequirement>\n");
                continue;
            }
            var kind = r < decision.requiredDecisions().size() ? "requiredDecision" : "requiredInput";
            xml.append("    <informationRequirement").append(id(DECISION + d + "_requirement_" + r)).append("><").append(kind)
               .append(attribute("href", href)).append("/></informationRequirement>\n");
        }
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var tableType) -> decisionTable(xml, table, prefix);
            case DecisionLogic.LiteralExpression literal -> xml.append("    <literalExpression").append(id(DECISION + d + "_literal"))
                    .append("><text>").append(escapeText(literal.text())).append("</text></literalExpression>\n");
            default -> boxed(xml, decision.logic(), null, "    ", boxedTypes);
        }
        xml.append("  </decision>\n");
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

    /** Whether a decision's logic holds a boxed expression that DMN 1.3 does not have: a conditional, an iterator or a filter. */
    private static boolean needsDmn15(DecisionLogic logic) {
        return switch (logic) {
            case DecisionLogic.Conditional c -> true;
            case DecisionLogic.Iteration i -> true;
            case DecisionLogic.Filter f -> true;
            case DecisionLogic.Context(var entries, var result, var type) ->
                    entries.stream().anyMatch(entry -> needsDmn15(entry.value())) || result != null && needsDmn15(result);
            case DecisionLogic.Invocation(var function, var bindings, var type) ->
                    bindings.stream().anyMatch(binding -> binding.value() != null && needsDmn15(binding.value()));
            case DecisionLogic.FunctionDefinition(var parameters, var body, var type) -> needsDmn15(body);
            case DecisionLogic.Relation(var columns, var rows, var type) -> rows.stream().flatMap(List::stream).anyMatch(DmnWriter::needsDmn15);
            case DecisionLogic.BoxedList(var elements, var type) -> elements.stream().anyMatch(DmnWriter::needsDmn15);
            default -> false;
        };
    }

    /**
     * The {@code typeRef}s of the parts of boxed expressions: a simple type's name, or an item definition, written once
     * for each other type, before the decisions; none for any type.
     */
    private static final class BoxedTypes {
        private final StringBuilder xml;
        private final Map<String, FeelType> named;
        private final Map<FeelType, String> written = new HashMap<>();
        /** The prefix of the ids of each decision table within a boxed expression, and of the item definitions of its list columns. */
        private final Map<DecisionTable, String> tables = new IdentityHashMap<>();
        /** The prefix of the ids of the element whose logic is being declared ({@code decision_2_}). */
        private String element = "";

        /** Declares the parts of an element's logic, whose ids start with {@code prefix}. */
        void declare(String prefix, DecisionLogic logic, boolean root, String where) {
            element = prefix;
            declare(logic, root, where);
        }

        /** The prefix of the ids of a decision table within a boxed expression. */
        String prefix(DecisionTable table) {
            return tables.get(table);
        }

        BoxedTypes(StringBuilder xml, Map<String, FeelType> named) {
            this.xml = xml;
            this.named = named;
        }

        /** Writes the item definitions of every part's type; the root's, a decision's, is its variable's. */
        void declare(DecisionLogic logic, boolean root, String where) {
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
                    var prefix = element + "table_" + tables.size() + "_";
                    for (int k = 1; startsAName(named, prefix + INPUT); k++) {
                        prefix = element + "table_" + tables.size() + "_" + k + "_";
                    }
                    tables.put(table, prefix);
                    listItemDefinitions(xml, table, prefix);
                }
                default -> {
                    // a literal expression has no parts
                }
            }
        }

        void declare(FeelType type, String where) {
            if (type instanceof FeelType.Simple || type instanceof FeelType.Any || written.containsKey(type)) {
                return;
            }
            var name = named.entrySet().stream().filter(entry -> entry.getValue().equals(type)).map(Map.Entry::getKey).findFirst()
                    .orElse(null);
            if (name == null) {
                name = "boxed_type_" + written.size();
                for (int k = 1; named.containsKey(name); k++) {
                    name = "boxed_type_" + written.size() + "_" + k;
                }
                if (type instanceof FeelType.FunctionOf) {
                    namedType(xml, name, type);
                } else {
                    typeRef(xml, type, name, where);
                }
            }
            written.put(type, name);
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

    /**
     * A boxed expression, or a part of one, at {@code indent}, with its type as {@code typeRef} unless {@code typeRef}
     * is given in its place (null: none, a decision's or a context entry's type being its variable's).
     */
    private static void boxed(StringBuilder xml, DecisionLogic logic, String typeRef, String indent, BoxedTypes types) {
        var type = typeRef == null ? "" : typeRef;
        var inner = indent + "  ";
        switch (logic) {
            case DecisionLogic.LiteralExpression literal -> xml.append(indent).append("<literalExpression").append(type).append("><text>")
                    .append(escapeText(literal.text())).append("</text></literalExpression>\n");
            case DecisionLogic.Table(var table, var tableType) -> decisionTable(xml, table, types.prefix(table));
            case DecisionLogic.Context(var entries, var result, var contextType) -> {
                xml.append(indent).append("<context").append(type).append(">\n");
                for (var entry : entries) {
                    xml.append(inner).append("<contextEntry>\n").append(inner).append("  <variable").append(attribute("name", entry.name()))
                       .append(types.attributeOf(entry.value().type())).append("/>\n");
                    boxed(xml, entry.value(), "", inner + "  ", types);
                    xml.append(inner).append("</contextEntry>\n");
                }
                if (result != null) {
                    xml.append(inner).append("<contextEntry>\n");
                    boxed(xml, result, types.attributeOf(result.type()), inner + "  ", types);
                    xml.append(inner).append("</contextEntry>\n");
                }
                xml.append(indent).append("</context>\n");
            }
            case DecisionLogic.Invocation(var function, var bindings, var invocationType) -> {
                xml.append(indent).append("<invocation").append(type).append(">\n").append(inner).append("<literalExpression><text>")
                   .append(escapeText(function)).append("</text></literalExpression>\n");
                for (var binding : bindings) {
                    xml.append(inner).append("<binding>\n").append(inner).append("  <parameter").append(attribute("name", binding.parameter()))
                       .append(binding.value() == null ? "" : types.attributeOf(binding.value().type())).append("/>\n");
                    if (binding.value() != null) {
                        boxed(xml, binding.value(), "", inner + "  ", types);
                    }
                    xml.append(inner).append("</binding>\n");
                }
                xml.append(indent).append("</invocation>\n");
            }
            case DecisionLogic.FunctionDefinition function -> functionDefinition(xml, "functionDefinition", function, type, indent, types);
            case DecisionLogic.Relation(var columns, var rows, var relationType) -> {
                xml.append(indent).append("<relation").append(type).append(">\n");
                for (var column : columns) {
                    xml.append(inner).append("<column").append(attribute("name", column.name())).append(types.attributeOf(column.type()))
                       .append("/>\n");
                }
                for (var row : rows) {
                    xml.append(inner).append("<row>\n");
                    row.forEach(cell -> boxed(xml, cell, types.attributeOf(cell.type()), inner + "  ", types));
                    xml.append(inner).append("</row>\n");
                }
                xml.append(indent).append("</relation>\n");
            }
            case DecisionLogic.BoxedList(var elements, var listType) -> {
                xml.append(indent).append("<list").append(type).append(">\n");
                elements.forEach(element -> boxed(xml, element, types.attributeOf(element.type()), inner, types));
                xml.append(indent).append("</list>\n");
            }
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var conditionalType) -> {
                xml.append(indent).append("<conditional").append(type).append(">\n");
                part(xml, "if", condition, inner, types);
                part(xml, "then", then, inner, types);
                part(xml, "else", otherwise, inner, types);
                xml.append(indent).append("</conditional>\n");
            }
            case DecisionLogic.Iteration(var kind, var variable, var domain, var body, var iterationType) -> {
                var tag = kind.name().toLowerCase(Locale.ROOT);
                xml.append(indent).append('<').append(tag).append(type).append(attribute("iteratorVariable", variable)).append(">\n");
                part(xml, "in", domain, inner, types);
                part(xml, kind == DecisionLogic.Iteration.Kind.FOR ? "return" : "satisfies", body, inner, types);
                xml.append(indent).append("</").append(tag).append(">\n");
            }
            case DecisionLogic.Filter(var list, var match, var filterType) -> {
                xml.append(indent).append("<filter").append(type).append(">\n");
                part(xml, "in", list, inner, types);
                part(xml, "match", match, inner, types);
                xml.append(indent).append("</filter>\n");
            }
        }
    }

    /** A function definition, as {@code tag}: its formal parameters and its body. */
    private static void functionDefinition(StringBuilder xml, String tag, DecisionLogic.FunctionDefinition function, String type,
            String indent, BoxedTypes types) {
        var inner = indent + "  ";
        xml.append(indent).append('<').append(tag).append(type).append(">\n");
        for (var parameter : function.parameters()) {
            xml.append(inner).append("<formalParameter").append(attribute("name", parameter.name())).append(types.attributeOf(parameter.type()))
               .append("/>\n");
        }
        boxed(xml, function.body(), types.attributeOf(function.body().type()), inner, types);
        xml.append(indent).append("</").append(tag).append(">\n");
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
        functionDefinition(xml, "encapsulatedLogic", knowledgeModel.logic(), "", "    ", types);
        var required = knowledgeModel.requiredKnowledge();
        for (int r = 0; r < required.size(); r++) {
            var href = ids.get(required.get(r));
            if (href == null) {
                throw new InvalidDecisionTableException("%s requires '%s', which is not in the model, so DMN cannot refer to it"
                        .formatted(where, required.get(r)));
            }
            xml.append("    <knowledgeRequirement").append(id(KNOWLEDGE_MODEL + k + "_requirement_" + r)).append("><requiredKnowledge")
               .append(attribute("href", href)).append("/></knowledgeRequirement>\n");
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
                var href = ids.get(name);
                if (href == null) {
                    throw new InvalidDecisionTableException("%s refers to '%s', which is not in the model, so DMN cannot refer to it"
                            .formatted(where, name));
                }
                xml.append("    <").append(kinds.get(l)).append(attribute("href", href)).append("/>\n");
            }
        }
        xml.append("  </decisionService>\n");
    }

    /** A child element of a boxed expression holding one expression: {@code <if>}, {@code <in>}, {@code <return>}... */
    private static void part(StringBuilder xml, String tag, DecisionLogic logic, String indent, BoxedTypes types) {
        xml.append(indent).append('<').append(tag).append(">\n");
        boxed(xml, logic, types.attributeOf(logic.type()), indent + "  ", types);
        xml.append(indent).append("</").append(tag).append(">\n");
    }

    /** The attribute {@code typeRef="..."}, or nothing for a value of any type. */
    private static String optionalType(String typeRef) {
        return typeRef == null ? "" : attribute(TYPE_REF, typeRef);
    }

    /**
     * The {@code typeRef} of an element's type, as {@link #typeRef} gives it, where the model's own types are written
     * already: a type of the model under the name an item definition would be given is that one, not written twice, and
     * a type the model names otherwise takes the next name that is free ({@code input_data_0_type_1}).
     */
    private static String elementTypeRef(StringBuilder xml, FeelType type, String name, Map<String, FeelType> named, String where) {
        if (type instanceof FeelType.Simple || type instanceof FeelType.Any) {
            return typeRef(xml, type, name, where);
        }
        if (type.equals(named.get(name))) {
            return name;
        }
        var free = name;
        for (int k = 1; named.containsKey(free); k++) {
            free = name + "_" + k;
        }
        return typeRef(xml, type, free, where);
    }

    /**
     * The {@code typeRef} of a type: a built-in type's name, or the name of an item definition written for a list or a
     * context; null for any type.
     *
     * @param name the item definition's name, if it needs one
     */
    private static String typeRef(StringBuilder xml, FeelType type, String name, String where) {
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
     * Standard FEEL text for parsed unary tests.
     *
     * @throws IllegalArgumentException if a test is a {@link UnaryTests.NullElement}, which only a list column's
     *                                  cell holds ({@link #renderList})
     */
    public static String render(UnaryTests tests) {
        return switch (tests) {
            case UnaryTests.Any() -> "-";
            case UnaryTests.Positive(var positive) -> join(positive);
            case UnaryTests.Negated(var negated) -> "not(" + join(negated) + ")";
        };
    }

    /**
     * Standard FEEL text for the cell of a column that holds lists, which tests the list's elements:
     * {@code "PT", >= 18, null} becomes
     * {@code list contains(?, "PT") or some x in ? satisfies x >= 18 or ? = null}, read back by
     * {@code CellParser#parseFeelList}; a {@link UnaryTests.NullElement} is written {@code list contains(?, null)}.
     */
    public static String renderList(UnaryTests tests) {
        return switch (tests) {
            case UnaryTests.Any() -> "-";
            case UnaryTests.Positive(var positive) -> joinList(positive);
            case UnaryTests.Negated(var negated) -> "not(" + joinList(negated) + ")";
        };
    }

    /**
     * The inputs the table's cells compare with, or none if a cell is invalid: writing the rules then
     * reports that cell, naming its rule and column.
     */
    private static Map<String, ColumnType> namedInputs(DecisionTable table) {
        try {
            return table.namedInputs();
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    // ---- elements, one method per DMN element ----------------------------------------------------

    /**
     * The item definitions of a list input: a collection of its type or, if it declares input values,
     * of an item definition that allows only those (DMN constrains a list's items through their type).
     */
    private static void listItemDefinitions(StringBuilder xml, int v, InputColumn input, String prefix) {
        var items = typeName(input.type());
        if (input.allowedValues() != null) {
            items = prefix + INPUT + v + "_item";
            var values = standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
            xml.append(ITEM_DEFINITION).append(attribute("name", items)).append(">\n")
               .append("    <typeRef>").append(typeName(input.type())).append(TYPE_REF_END)
               .append("    <allowedValues><text>").append(escapeText(values)).append("</text></allowedValues>\n")
               .append(ITEM_DEFINITION_END);
        }
        xml.append(ITEM_DEFINITION).append(attribute("name", listTypeName(v, prefix))).append(attribute("isCollection", "true")).append(">\n")
           .append("    <typeRef>").append(escapeText(items)).append(TYPE_REF_END)
           .append(ITEM_DEFINITION_END);
    }

    /** The name of the item definition of the list input at {@code v}. */
    private static String listTypeName(int v, String prefix) {
        return prefix + INPUT + v + "_list";
    }

    /** The {@code inputData} of an input, which Drools needs to bind its value. */
    private static void inputData(StringBuilder xml, int v, InputColumn input, DecisionTable table) {
        xml.append("  <inputData").append(idAndName(INPUT_DATA + v, input.name())).append(">\n")
           .append(VARIABLE).append(idAndName("input_variable_" + v, input.name())).append(typed(input, v, table, "")).append("/>\n")
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
        decisionTable(xml, table, "");
        xml.append("  </decision>\n");
    }

    /** @param prefix the prefix of the ids of the table's elements, which tell the tables of a model apart */
    private static void decisionTable(StringBuilder xml, DecisionTable table, String prefix) {
        // DMN spells the hit policies with spaces: RULE ORDER, OUTPUT ORDER
        var hitPolicy = table.hitPolicy().name().replace('_', ' ');
        xml.append("    <decisionTable").append(id(prefix + "table")).append(attribute("hitPolicy", hitPolicy));
        if (table.aggregation() != null) {
            xml.append(attribute("aggregation", table.aggregation().name()));
        }
        xml.append(">\n");
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            input(xml, c, inputs.get(c), table, prefix);
        }
        var outputs = table.outputs();
        for (int c = 0; c < outputs.size(); c++) {
            output(xml, c, outputs.get(c), prefix);
        }
        var rules = table.rules();
        var readable = readableInputs(table);
        for (int r = 0; r < rules.size(); r++) {
            rule(xml, r, rules.get(r), table, readable, prefix);
        }
        xml.append("    </decisionTable>\n");
    }

    /** A column; a list column's input values are those of its item type instead (see {@link #listItemDefinitions}). */
    private static void input(StringBuilder xml, int c, InputColumn input, DecisionTable table, String prefix) {
        xml.append("      <input").append(id(prefix + INPUT + c)).append(attribute("label", input.name())).append(">\n");
        expression(xml, "inputExpression", id(prefix + "input_expression_" + c) + typed(input, c, table, prefix), input.name());
        if (input.allowedValues() != null && !table.listInputs().contains(input.name())) {
            var values = standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
            expression(xml, "inputValues", id(prefix + "input_values_" + c), values);
        }
        xml.append("      </input>\n");
    }

    /** An output, with its value list and its default value ({@code defaultOutputEntry}), if it has them, in that order. */
    private static void output(StringBuilder xml, int c, OutputColumn output, String prefix) {
        xml.append("      <output").append(idAndName(prefix + "output_" + c, output.name())).append(output.typed() ? typed(output.type()) : "");
        if (output.values().isEmpty() && !output.hasDefault()) {
            xml.append("/>\n");
            return;
        }
        xml.append(">\n");
        if (!output.values().isEmpty()) {
            var values = output.values().stream()
                    .map(value -> outputLiteral(value, output.type(), "output values of '" + output.name() + "'"))
                    .collect(Collectors.joining(", "));
            expression(xml, "outputValues", id(prefix + "output_values_" + c), values);
        }
        if (output.hasDefault()) {
            var value = outputLiteral(output.defaultValue(), output.type(), "default output of '" + output.name() + "'");
            expression(xml, "defaultOutputEntry", id(prefix + "output_default_" + c), value);
        }
        xml.append("      </output>\n");
    }

    /**
     * The inputs an output entry may name: the table's columns, and the inputs its cells compare with
     * (as {@code DmnReader} reads them, and the engine evaluates them).
     */
    private static Set<String> readableInputs(DecisionTable table) {
        var names = new HashSet<String>();
        table.inputs().forEach(input -> names.add(input.name()));
        try {
            names.addAll(table.namedInputs().keySet());
        } catch (IllegalArgumentException e) {
            // an invalid cell, which writing the cells reports with its location
        }
        return names;
    }

    /**
     * @param r        the rule's index, from 0
     * @param readable the inputs an output entry may name (see {@link #readableInputs})
     */
    private static void rule(StringBuilder xml, int r, Praeceptum rule, DecisionTable table, Set<String> readable, String prefix) {
        var where = "Rule " + rule.priority();
        xml.append("      <rule").append(id(prefix + "rule_" + r)).append(">\n");
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            var text = rule.input().values().get(input.name());
            var cell = standardFeel(text, input.type(), table.feelCells(), table.listInputs().contains(input.name()),
                    where + ", column '" + input.name() + "'");
            expression(xml, "inputEntry", id(prefix + "input_entry_" + r + "_" + c), cell);
        }
        var outputs = table.outputs();
        for (int c = 0; c < outputs.size(); c++) {
            var output = outputs.get(c);
            var outputWhere = where + ", output '" + output.name() + "'";
            var input = rule.output().references().get(output.name());
            var expression = rule.output().expressions().get(output.name());
            String value;
            if (input != null) {
                value = namedInput(input, readable, outputWhere);
            } else if (expression != null) {
                value = expression; // as written: FEEL already
            } else {
                value = outputLiteral(rule.output().values().get(output.name()), output.type(), outputWhere);
            }
            expression(xml, "outputEntry", id(prefix + "output_entry_" + r + "_" + c), value);
        }
        xml.append("      </rule>\n");
    }

    /** An output entry that names an input: the input's name, which must be a column or an input the cells compare with. */
    private static String namedInput(String input, Set<String> readable, String where) {
        if (!readable.contains(input)) {
            throw new InvalidDecisionTableException("%s: names '%s', which is not an input of the table".formatted(where, input));
        }
        return input;
    }

    /** A line holding an element with a FEEL expression: {@code <tag attributes><text>expression</text></tag>}. */
    private static void expression(StringBuilder xml, String tag, String attributes, String expression) {
        xml.append("        <").append(tag).append(attributes).append("><text>").append(escapeText(expression))
           .append("</text></").append(tag).append(">\n");
    }

    // ---- cells and values as FEEL ----------------------------------------------------------------

    /**
     * A cell in Praecepta's syntax, or already strict FEEL (a DMN table's), as standard FEEL.
     *
     * @param list whether the column holds lists, whose cells test the elements
     */
    private static String standardFeel(String cell, ColumnType type, boolean feel, boolean list, String where) {
        try {
            if (list) {
                return renderList(feel ? CellParser.parseFeelList(cell, type) : CellParser.parse(cell, type));
            }
            return render(feel ? CellParser.parseFeel(cell, type) : CellParser.parse(cell, type));
        } catch (CellSyntaxException e) {
            if (!feel) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
            return beyondSimple(cell, e, where);
        }
    }

    /** A strict FEEL cell beyond the simple unary tests ({@link FeelUnaryTests}): as written, once checked. */
    private static String beyondSimple(String cell, CellSyntaxException rejected, String where) {
        try {
            // no names in scope, nor types: its names are read as words and its types as any, which is all checking it needs
            FeelUnaryTests.parseCell(cell, FeelExpression.namesScope(List.of()), rejected);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
        return cell.strip();
    }

    private static String join(List<Test> tests) {
        return tests.stream().map(DmnWriter::test).collect(Collectors.joining(", "));
    }

    private static String joinList(List<Test> tests) {
        return tests.stream().map(DmnWriter::listTest).collect(Collectors.joining(" or "));
    }

    /** A test of a list column's cell, applied to the list's elements. */
    private static String listTest(Test test) {
        return switch (test) {
            case NullTest() -> "? = null";
            case UnaryTests.NullElement() -> "list contains(?, null)";
            case Equals(var value, var explicit) -> "list contains(?, " + literal(value) + ")";
            case Compare c -> "some x in ? satisfies x " + test(c);
            case NotEqual n -> "some x in ? satisfies x " + test(n);
            case Range r -> "some x in ? satisfies x in " + test(r);
        };
    }

    private static String test(Test test) {
        return switch (test) {
            case NullTest() -> "null";
            // only CellParser.parseFeelList reads it, for a list column, whose cells renderList writes
            case UnaryTests.NullElement() -> throw new IllegalArgumentException("list contains(?, null) only tests a list's elements");
            case Equals(var value, var explicit) -> (explicit ? "= " : "") + literal(value); // = home is not home of a list
            case NotEqual(var negated) -> "!= " + test(negated);
            case Compare(var operator, var value) -> operator.symbol() + " " + literal(value);
            case Range(var low, var lowClosed, var high, var highClosed) -> (lowClosed ? "[" : "(") + literal(low) + ".."
                    + literal(high) + (highClosed ? "]" : ")");
        };
    }

    /** A value as FEEL writes it: a literal, or the bare name of another input. */
    private static String literal(Comparable<?> value) {
        return value instanceof UnaryTests.Name(var name) ? name : FeelLiterals.literal(value);
    }

    /** An output value (plain text) as a FEEL literal of its column type. */
    private static String outputLiteral(String value, ColumnType type, String where) {
        if (value == null) {
            return "null";
        }
        var text = value.strip();
        try {
            return FeelLiterals.literal(switch (type) {
                case STRING -> value;
                case NUMBER -> new BigDecimal(text);
                case DATE -> FeelTemporals.parseDate(text);
                case TIME -> FeelTemporals.parseTime(text);
                case DATE_TIME -> FeelTemporals.parseDateTime(text);
                case DAY_TIME_DURATION -> Duration.parse(text);
                case YEAR_MONTH_DURATION -> YearsAndMonths.parse(text);
                case BOOLEAN -> bool(text);
            });
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not a %s".formatted(where, value, type.feelName()));
        }
    }

    /** Parses {@code true} or {@code false}; like the parsers of the other types, it throws an {@link IllegalArgumentException} otherwise. */
    private static Boolean bool(String text) {
        if (!text.equals("true") && !text.equals("false")) {
            throw new IllegalArgumentException("not a boolean: " + text);
        }
        return Boolean.valueOf(text);
    }

    // ---- ids and attributes ----------------------------------------------------------------------

    /**
     * A DMN id from a name: characters other than ASCII letters, digits and {@code _} become {@code _},
     * and an id that does not start with a letter gets a {@code d_} prefix.
     */
    private static String identifier(String name) {
        var id = name.replaceAll("\\W", "_");
        return id.isEmpty() || !Character.isLetter(id.charAt(0)) ? "d_" + id : id;
    }

    /** The attributes {@code id="..." name="..."}. */
    private static String idAndName(String id, String name) {
        return id(id) + attribute("name", name);
    }

    private static String id(String id) {
        return attribute("id", id);
    }

    /** The attribute {@code typeRef="..."} of a column type. */
    private static String typed(ColumnType type) {
        return attribute(TYPE_REF, typeName(type));
    }

    /** The attribute {@code typeRef="..."} of the input at {@code v}: its list type if it holds lists. */
    private static String typed(InputColumn input, int v, DecisionTable table, String prefix) {
        return table.listInputs().contains(input.name()) ? attribute(TYPE_REF, listTypeName(v, prefix)) : typed(input.type());
    }

    /** A column type's name in DMN. */
    private static String typeName(ColumnType type) {
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

    /** An attribute, with a leading space and its value escaped: {@code  name="value"}. */
    private static String attribute(String name, String value) {
        return " " + name + "=\"" + escapeText(value).replace("\"", "&quot;") + "\"";
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
