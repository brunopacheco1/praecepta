/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads decision tables from DMN XML (DMN 1.1 to 1.5, as written by Camunda Modeler, Drools / KIE
 * tools, Trisotech and {@link DmnWriter}). Uses only the JDK's XML parser, with DTDs and external
 * entities disabled.
 *
 * <p>Supported: decision tables whose input expressions name an input variable, with
 * {@code string}, {@code number} (or {@code integer}, {@code long}, {@code double}), {@code date},
 * {@code boolean}, {@code time}, {@code dateTime} (or {@code date and time}), {@code dayTimeDuration} and
 * {@code yearMonthDuration} (or their FEEL names) columns, times and date-times with or without a time zone
 * (but no time with a zone id, which has no fixed offset); columns typed by an item definition of one of
 * those types, whose allowed values become the input value list unless the input declares its own;
 * columns typed by a collection of those ({@code isCollection="true"}), which hold lists and whose
 * entries test the elements ({@code list contains(?, "PT")}, see {@link CellParser#parseFeelList}), and
 * named inputs whose {@code inputData} is such a collection (see {@link DecisionTable#listInputs}); input
 * entries in Praecepta's FEEL subset (lists, {@code not(...)}, {@code null}, comparisons, intervals, {@code -}); input and output value
 * lists; output entries that are literals or name one of the table's inputs (of the output's type, or
 * any input for a string output; an input is a column or an input the cells compare with, and holds single
 * values), or that join string and number literals and the table's inputs with
 * {@code +}, {@code -}, {@code *}, {@code /} and {@code **} ({@link OutputExpression}, kept as written in
 * {@link PraeceptaOutput#expressions()}); every hit policy; SUM, MIN, MAX and COUNT aggregation. Anything else is rejected with an
 * {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are numbered
 * from 1 in table order.
 */
public final class DmnReader {

    private static final String TYPE_REF = "typeRef";

    /** Literal types an unquoted output entry of an untyped (string) output may hold, kept as their text. */
    private static final List<ColumnType> UNQUOTED_OUTPUT_TYPES =
            List.of(ColumnType.NUMBER, ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.TIME, ColumnType.DATE_TIME);

    private DmnReader() {
    }

    /** Reads the model's only decision table. */
    public static DecisionTable read(InputStream dmn) {
        return read(dmn, null);
    }

    /**
     * Reads one decision table of the model.
     *
     * @param decisionName the decision whose table to read, or null if the model has exactly one
     */
    public static DecisionTable read(InputStream dmn, String decisionName) {
        var root = parseModel(dmn);
        var decision = decisionToRead(root, decisionName);
        return decisionTable(decision, new ItemDefinitions(root));
    }

    // ---- the table -------------------------------------------------------------------------------

    /** The named decision, or the model's only decision with a table if no name is given. */
    private static Element decisionToRead(Element root, String decisionName) {
        var decisions = new ArrayList<Element>();
        var names = new ArrayList<String>();
        for (var decision : descendants(root, "decision")) {
            if (child(decision, "decisionTable") != null) {
                decisions.add(decision);
                names.add(decision.getAttribute("name"));
            }
        }
        if (decisionName != null) {
            int at = names.indexOf(decisionName);
            if (at < 0) {
                throw new InvalidDecisionTableException("DMN model has no decision table named '%s'; it has %s".formatted(decisionName, names));
            }
            return decisions.get(at);
        }
        if (decisions.size() != 1) {
            throw new InvalidDecisionTableException(decisions.isEmpty()
                    ? "DMN model has no decision table"
                    : "DMN model has %d decision tables %s; name the one to read".formatted(decisions.size(), names));
        }
        return decisions.getFirst();
    }

    private static DecisionTable decisionTable(Element decision, ItemDefinitions types) {
        var name = nameOr(decision, "id");
        var table = child(decision, "decisionTable");
        var where = "Decision '" + name + "'";

        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute("aggregation"), where);

        var lists = new HashSet<String>();
        var inputs = children(table, "input").stream().map(input -> inputColumn(input, types, lists, where)).toList();
        var outputElements = children(table, "output");
        if (outputElements.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the decision table has no output");
        }
        // a single unnamed output is the decision's result, named after the decision
        var unnamedOutput = outputElements.size() == 1 ? name : "";
        var outputs = outputElements.stream().map(output -> outputColumn(output, unnamedOutput, types, where)).toList();

        var rules = new ArrayList<Praeceptum>();
        var ruleElements = children(table, "rule");
        for (int i = 0; i < ruleElements.size(); i++) {
            rules.add(rule(ruleElements.get(i), i + 1, inputs, lists, outputs, where));
        }
        // cells stay strict FEEL, where a name compares with another input
        var read = new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, rules, true, lists);
        Map<String, ColumnType> named;
        try {
            named = read.namedInputs();
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
        lists.addAll(namedListInputs(decision.getOwnerDocument().getDocumentElement(), named, types, where));
        var checked = checkComputedOutputs(rules, inputs, named, lists, outputs, where);
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, checked, true, lists);
    }

    /**
     * The inputs the cells compare with that hold lists: those whose {@code inputData} has a collection
     * type of the type of the column that names them, as {@code Flu Symptoms} in
     * {@code Flu Symptoms} (a cell that holds when the column's value is in that list).
     */
    private static List<String> namedListInputs(Element root, Map<String, ColumnType> named, ItemDefinitions types, String where) {
        var lists = new ArrayList<String>();
        for (var inputData : descendants(root, "inputData")) {
            var variable = child(inputData, "variable");
            var inputName = inputData.getAttribute("name").strip();
            var typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
            if (!named.containsKey(inputName) || typeRef.isBlank()) {
                continue;
            }
            Type type;
            try {
                type = types.resolve(typeRef, where + ", input '" + inputName + "'");
            } catch (InvalidDecisionTableException e) {
                continue; // a type Praecepta cannot hold, which no input value can be bound to either
            }
            if (type.list()) {
                if (type.columnType() != named.get(inputName)) {
                    throw new InvalidDecisionTableException("%s: a %s column compares with '%s', a list of %s".formatted(
                            where, named.get(inputName).feelName(), inputName, type.columnType().feelName()));
                }
                lists.add(inputName);
            }
        }
        return lists;
    }

    private static HitPolicy hitPolicy(String value, String where) {
        if (value.isBlank()) {
            return HitPolicy.UNIQUE; // the DMN default
        }
        try {
            return HitPolicy.valueOf(value.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("%s: unknown hit policy '%s'".formatted(where, value));
        }
    }

    private static AggregationOperator aggregation(String value, String where) {
        if (value.isBlank()) {
            return null;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "SUM" -> AggregationOperator.SUM;
            case "MIN" -> AggregationOperator.MIN;
            case "MAX" -> AggregationOperator.MAX;
            case "COUNT" -> AggregationOperator.COUNT;
            default -> throw new InvalidDecisionTableException(
                    "%s: aggregation '%s' is not supported (SUM, MIN, MAX and COUNT are)".formatted(where, value));
        };
    }

    // ---- columns and rules -----------------------------------------------------------------------

    /**
     * A column; one whose type is a collection is added to {@code lists}, and its allowed values (those of
     * its item type) apply to the list's elements.
     */
    private static InputColumn inputColumn(Element input, ItemDefinitions types, Set<String> lists, String where) {
        var expression = child(input, "inputExpression");
        var column = expression == null ? "" : textOf(expression).strip();
        if (column.isEmpty()) {
            column = input.getAttribute("label").strip();
        }
        if (!CellParser.isName(column)) {
            throw new InvalidDecisionTableException("%s: input expression '%s' is not supported; it must name an input variable"
                    .formatted(where, column));
        }
        var typeRef = expression == null ? "" : expression.getAttribute(TYPE_REF);
        var type = types.resolve(typeRef, where + ", input '" + column + "'");
        var values = nonBlankText(input, "inputValues");
        if (type.list()) {
            if (values != null) {
                throw new InvalidDecisionTableException(("%s, input '%s': input values of a list are not supported; "
                        + "declare the allowed values of its items in their item definition").formatted(where, column));
            }
            lists.add(column);
        }
        if (values == null) {
            return new InputColumn(column, type.columnType(), type.allowedValues());
        }
        var allowed = values.strip();
        parseCell(allowed, type.columnType(), where + ", input values of '" + column + "'");
        return new InputColumn(column, type.columnType(), allowed);
    }

    private static OutputColumn outputColumn(Element output, String unnamed, ItemDefinitions types, String where) {
        var column = nameOr(output, "label");
        if (column.isBlank()) {
            column = unnamed;
        }
        if (column.isBlank()) {
            throw new InvalidDecisionTableException(where + ": every output of a multi-output table needs a name");
        }
        var outputWhere = where + ", output '" + column + "'";
        var resolved = types.resolve(output.getAttribute(TYPE_REF), outputWhere);
        if (resolved.list()) {
            throw new InvalidDecisionTableException("%s: type '%s' is a collection, which is not supported for outputs"
                    .formatted(outputWhere, output.getAttribute(TYPE_REF)));
        }
        var type = resolved.columnType();
        var valueList = nonBlankText(output, "outputValues");
        if (valueList == null) {
            return new OutputColumn(column, type, List.of());
        }
        var values = parseCell(valueList, type, outputWhere + " value list").values()
                .orElseThrow(() -> new InvalidDecisionTableException(outputWhere + ": the output value list must be a list of values"));
        return new OutputColumn(column, type, values.stream().map(FeelLiterals::text).toList());
    }

    /**
     * A rule, with its input entries kept as written (once checked) and its output entries as plain text.
     *
     * @param number the rule's number, from 1 in table order
     */
    private static Praeceptum rule(Element rule, int number, List<InputColumn> inputs, Set<String> lists, List<OutputColumn> outputs,
            String where) {
        var ruleWhere = where + ", rule " + number;
        var inputEntries = children(rule, "inputEntry");
        var outputEntries = children(rule, "outputEntry");
        if (inputEntries.size() != inputs.size() || outputEntries.size() != outputs.size()) {
            throw new InvalidDecisionTableException("%s: has %d input and %d output entries; the table has %d inputs and %d outputs"
                    .formatted(ruleWhere, inputEntries.size(), outputEntries.size(), inputs.size(), outputs.size()));
        }
        Map<String, String> cells = new HashMap<>();
        for (int c = 0; c < inputs.size(); c++) {
            var column = inputs.get(c);
            var text = textOf(inputEntries.get(c)).strip();
            var cellWhere = ruleWhere + ", column '" + column.name() + "'";
            if (lists.contains(column.name())) {
                parseListCell(text, column.type(), cellWhere);
            } else {
                parseCell(text, column.type(), cellWhere);
            }
            cells.put(column.name(), text);
        }
        Map<String, String> values = new HashMap<>();
        Map<String, String> references = new HashMap<>();
        Map<String, String> expressions = new HashMap<>();
        for (int c = 0; c < outputs.size(); c++) {
            var column = outputs.get(c);
            var text = textOf(outputEntries.get(c)).strip();
            var outputWhere = ruleWhere + ", output '" + column.name() + "'";
            var input = namedInput(text, inputs, column, outputWhere);
            if (input != null) {
                checkReference(input, lists, outputWhere);
                references.put(column.name(), input.name());
                continue;
            }
            var literal = literalOutput(text, column.type());
            if (literal != null) {
                values.put(column.name(), literal.text());
            } else {
                // checked once the inputs the cells compare with are known (see checkComputedOutputs)
                parseExpression(text, outputWhere);
                expressions.put(column.name(), text);
            }
        }
        return new Praeceptum(number, new PraeceptaInput(cells), new PraeceptaOutput(values, references, expressions));
    }

    /** An output entry may name an input that holds single values only: an output holds one value. */
    private static void checkReference(InputColumn input, Set<String> lists, String where) {
        if (lists.contains(input.name())) {
            throw new InvalidDecisionTableException("%s: names input '%s', a list, which outputs cannot hold".formatted(where, input.name()));
        }
    }

    /**
     * Checks the output entries that compute their value, now that the inputs the cells compare with are
     * known: an expression may read the table's columns and those inputs, as the engine evaluates it,
     * and an entry that is just the name of such an input names it, as an entry naming a column does.
     */
    private static List<Praeceptum> checkComputedOutputs(List<Praeceptum> rules, List<InputColumn> inputs, Map<String, ColumnType> named,
            Set<String> lists, List<OutputColumn> outputs, String where) {
        var types = new HashMap<>(named);
        inputs.forEach(input -> types.put(input.name(), input.type()));
        var outputsByName = new HashMap<String, OutputColumn>();
        outputs.forEach(output -> outputsByName.put(output.name(), output));
        var checked = new ArrayList<Praeceptum>();
        for (var rule : rules) {
            var output = rule.output();
            var references = new HashMap<>(output.references());
            var expressions = new HashMap<String, String>();
            output.expressions().forEach((column, text) -> {
                var outputWhere = where + ", rule " + rule.priority() + ", output '" + column + "'";
                var input = named.containsKey(text)
                        ? namedInput(text, List.of(new InputColumn(text, named.get(text))), outputsByName.get(column), outputWhere)
                        : null;
                if (input != null) {
                    checkReference(input, lists, outputWhere);
                    references.put(column, text);
                } else {
                    checkExpression(text, types, lists, outputsByName.get(column), outputWhere);
                    expressions.put(column, text);
                }
            });
            checked.add(new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(output.values(), references, expressions)));
        }
        return checked;
    }

    private static OutputExpression parseExpression(String text, String where) {
        try {
            return OutputExpression.parse(text);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException(("%s: '%s' is not supported; output entries must be literals, name an input, "
                    + "or join literals and inputs with +, -, *, / and ** (%s)").formatted(where, text, e.getMessage()));
        }
    }

    /**
     * Checks an output entry that computes its value ({@link OutputExpression}): it parses, it names
     * only inputs of single values (the table's columns, or inputs its cells compare with: those the
     * engine reads), their types go together, and the value has the output's type, or any type for a
     * string output (which takes a number as its text, as it takes number literals).
     *
     * @param types the type of each input an expression may read
     */
    private static void checkExpression(String text, Map<String, ColumnType> types, Set<String> lists, OutputColumn output, String where) {
        var expression = parseExpression(text, where);
        for (var name : expression.names()) {
            if (!types.containsKey(name) || lists.contains(name)) {
                throw new InvalidDecisionTableException(("%s: '%s' names '%s', which is not one of the table's inputs of single values; "
                        + "output entries must be literals or read the table's inputs").formatted(where, text, name));
            }
        }
        ColumnType type;
        try {
            type = expression.type(types::get);
        } catch (ExpressionTypeException e) {
            throw new InvalidDecisionTableException("%s: '%s' %s".formatted(where, text, e.getMessage()));
        }
        if (type != output.type() && output.type() != ColumnType.STRING) {
            throw new InvalidDecisionTableException("%s: '%s' gives a %s, but the output is a %s"
                    .formatted(where, text, type.feelName(), output.type().feelName()));
        }
    }

    /**
     * The input column an output entry names, as in {@code a}, which returns the input {@code a}; null if
     * the entry names none. The input must be of the output's type, unless the output is a string,
     * which takes any value as its text (as it takes unquoted number or date literals).
     */
    private static InputColumn namedInput(String text, List<InputColumn> inputs, OutputColumn output, String where) {
        for (var input : inputs) {
            if (input.name().equals(text)) {
                if (input.type() != output.type() && output.type() != ColumnType.STRING) {
                    throw new InvalidDecisionTableException("%s: names input '%s', a %s, but the output is a %s"
                            .formatted(where, text, input.type().feelName(), output.type().feelName()));
                }
                return input;
            }
        }
        return null;
    }

    // ---- values ----------------------------------------------------------------------------------

    /** Parses strict FEEL unary tests, naming {@code where} they are if they are invalid. */
    private static UnaryTests parseCell(String text, ColumnType type, String where) {
        try {
            return CellParser.parseFeel(text, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** Parses the cell of a list column ({@code list contains(?, "PT")}), naming {@code where} it is if it is invalid. */
    private static void parseListCell(String text, ColumnType type, String where) {
        try {
            CellParser.parseFeelList(text, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** A literal output entry's value as plain text ({@link FeelLiterals#text}), null for {@code null}. */
    private record LiteralOutput(String text) {
    }

    /**
     * A literal output entry, or null if the entry is not one. An unquoted entry of a string output may
     * still be a number, boolean, date, time or date-time literal.
     */
    private static LiteralOutput literalOutput(String text, ColumnType type) {
        var literal = text.strip();
        if (literal.isEmpty() || literal.equals("null")) {
            return new LiteralOutput(null);
        }
        var types = type == ColumnType.STRING && !literal.startsWith("\"") ? UNQUOTED_OUTPUT_TYPES : List.of(type);
        for (var literalType : types) {
            var value = literalValue(literal, literalType);
            if (value.isPresent()) {
                return new LiteralOutput(FeelLiterals.text(value.get()));
            }
        }
        return null;
    }

    /** The value of an entry that is a single literal of the type (no list, comparison or name). */
    private static Optional<Object> literalValue(String literal, ColumnType type) {
        try {
            var values = CellParser.parseFeel(literal, type).values().orElse(List.of());
            if (values.size() == 1) {
                return Optional.of(values.getFirst());
            }
        } catch (CellSyntaxException e) {
            // not a literal of this type
        }
        return Optional.empty();
    }

    // ---- XML -------------------------------------------------------------------------------------

    /** The model's {@code definitions} element, parsed with DTDs and external entities disabled. */
    private static Element parseModel(InputStream dmn) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var root = factory.newDocumentBuilder().parse(dmn).getDocumentElement();
            if (!root.getLocalName().equals("definitions")) {
                throw new InvalidDecisionTableException("Not a DMN model: root element is <%s>".formatted(root.getTagName()));
            }
            return root;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new InvalidDecisionTableException("Cannot read DMN model: " + e.getMessage());
        }
    }

    private static List<Element> descendants(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        var elements = new ArrayList<Element>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private static List<Element> children(Element parent, String localName) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static Element child(Element parent, String localName) {
        var found = children(parent, localName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The element's {@code name} attribute, or its {@code fallback} attribute if it has no name. */
    private static String nameOr(Element element, String fallback) {
        var name = element.getAttribute("name");
        return name.isBlank() ? element.getAttribute(fallback) : name;
    }

    /** The text of an element's {@code <text>} child (DMN expressions), or its own text. */
    private static String textOf(Element element) {
        var text = child(element, "text");
        return (text == null ? element : text).getTextContent();
    }

    /** The text of a child such as {@code <inputValues>}, or null if there is none or it is blank. */
    private static String nonBlankText(Element parent, String localName) {
        var element = child(parent, localName);
        if (element == null) {
            return null;
        }
        var text = textOf(element);
        return text.isBlank() ? null : text;
    }

    // ---- types -----------------------------------------------------------------------------------

    /**
     * A column's type: a built-in type, the allowed values of the item definitions it was reached
     * through (the most derived one that declares any), or null, and whether it is a list of that type.
     */
    private record Type(ColumnType columnType, String allowedValues, boolean list) {
    }

    /**
     * The model's item definitions, by name. A column may name one whose base type is a built-in type
     * or another such item definition, or a collection of one; structured types (with item components),
     * collections of collections and function types are rejected.
     */
    private static final class ItemDefinitions {
        private final Map<String, Element> byName = new HashMap<>();

        ItemDefinitions(Element root) {
            children(root, "itemDefinition").forEach(definition -> byName.putIfAbsent(definition.getAttribute("name"), definition));
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
                    if (list) {
                        throw new InvalidDecisionTableException("%s: type '%s' is a collection of collections, which is not supported"
                                .formatted(where, name));
                    }
                    if (allowed != null || allowedValues != null) {
                        throw new InvalidDecisionTableException(("%s: type '%s' constrains a collection as a whole, which is not supported; "
                                + "declare the allowed values of its items in their item definition").formatted(where, name));
                    }
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
                parseCell(allowedValues, type, where + ", allowed values of type '" + allowedBy + "'");
            }
            return new Type(type, allowedValues, list);
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
}
