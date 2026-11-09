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
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads decision tables from DMN XML (DMN 1.1 to 1.5, as written by Camunda Modeler, Drools / KIE
 * tools, Trisotech and {@link DmnWriter}). Uses only the JDK's XML parser, with DTDs and external
 * entities disabled.
 *
 * <p>Supported: decision tables whose input expressions name an input variable, with
 * {@code string}, {@code number} (or {@code integer}, {@code long}, {@code double}), {@code date},
 * {@code boolean}, {@code time} and {@code dateTime} (or {@code date and time}) columns, times without
 * a zone; columns typed by an item definition of one of those types, whose allowed values become the
 * input value list unless the input declares its own; input entries in Praecepta's FEEL subset
 * (lists, {@code not(...)}, {@code null}, comparisons, intervals, {@code -}); input and output value
 * lists; literal output entries; every hit policy; SUM, MIN and MAX aggregation. Anything else is rejected with an
 * {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are numbered
 * from 1 in table order.
 */
public final class DmnReader {

    private static final String TYPE_REF = "typeRef";

    private DmnReader() {
    }

    /** Reads the model's only decision table. */
    public static DecisionTable read(InputStream dmn) {
        return read(dmn, null);
    }

    /**
     * @param decisionName the decision whose table to read, or null if the model has exactly one
     */
    public static DecisionTable read(InputStream dmn, String decisionName) {
        var root = parse(dmn);
        var types = new ItemDefinitions(root);
        var tables = new ArrayList<Element>();
        var names = new ArrayList<String>();
        for (var decision : descendants(root, "decision")) {
            var table = child(decision, "decisionTable");
            if (table != null) {
                tables.add(decision);
                names.add(decision.getAttribute("name"));
            }
        }
        if (decisionName != null) {
            int at = names.indexOf(decisionName);
            if (at < 0) {
                throw new InvalidDecisionTableException("DMN model has no decision table named '%s'; it has %s".formatted(decisionName, names));
            }
            return decisionTable(tables.get(at), types);
        }
        if (tables.size() != 1) {
            throw new InvalidDecisionTableException(tables.isEmpty()
                    ? "DMN model has no decision table"
                    : "DMN model has %d decision tables %s; name the one to read".formatted(tables.size(), names));
        }
        return decisionTable(tables.getFirst(), types);
    }

    private static DecisionTable decisionTable(Element decision, ItemDefinitions types) {
        var name = nameOr(decision, "id");
        var table = child(decision, "decisionTable");
        var where = "Decision '" + name + "'";

        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute("aggregation"), where);

        var inputs = children(table, "input").stream().map(input -> inputColumn(input, types, where)).toList();
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
            rules.add(rule(ruleElements.get(i), i + 1, inputs, outputs, where));
        }
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, rules);
    }

    private static InputColumn inputColumn(Element input, ItemDefinitions types, String where) {
        var expression = child(input, "inputExpression");
        var column = expression == null ? "" : textOf(expression).strip();
        if (column.isEmpty()) {
            column = input.getAttribute("label").strip();
        }
        if (!isName(column)) {
            throw new InvalidDecisionTableException("%s: input expression '%s' is not supported; it must name an input variable"
                    .formatted(where, column));
        }
        var type = types.resolve(expression == null ? "" : expression.getAttribute(TYPE_REF), where + ", input '" + column + "'");
        var values = child(input, "inputValues");
        if (values == null || textOf(values).isBlank()) {
            return new InputColumn(column, type.columnType(), type.allowedValues());
        }
        var allowed = textOf(values).strip();
        parse(allowed, type.columnType(), where + ", input values of '" + column + "'");
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
        var type = types.resolve(output.getAttribute(TYPE_REF), outputWhere).columnType();
        var valueList = child(output, "outputValues");
        if (valueList == null || textOf(valueList).isBlank()) {
            return new OutputColumn(column, type, List.of());
        }
        var valueTests = parse(textOf(valueList), type, outputWhere + " value list");
        if (!(valueTests instanceof UnaryTests.Positive(var tests))
                || !tests.stream().allMatch(UnaryTests.Equals.class::isInstance)) {
            throw new InvalidDecisionTableException(outputWhere + ": the output value list must be a list of values");
        }
        var values = tests.stream().map(test -> FeelLiterals.text(((UnaryTests.Equals) test).value())).toList();
        return new OutputColumn(column, type, values);
    }

    /** @param number the rule's number, from 1 in table order */
    private static Praeceptum rule(Element rule, int number, List<InputColumn> inputs, List<OutputColumn> outputs, String where) {
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
            parse(text, column.type(), ruleWhere + ", column '" + column.name() + "'");
            cells.put(column.name(), text);
        }
        Map<String, String> values = new HashMap<>();
        for (int c = 0; c < outputs.size(); c++) {
            var column = outputs.get(c);
            values.put(column.name(), outputValue(textOf(outputEntries.get(c)), column.type(), ruleWhere + ", output '" + column.name() + "'"));
        }
        return new Praeceptum(number, new PraeceptaInput(cells), new PraeceptaOutput(values));
    }

    // ---- values ---------------------------------------------------------------------------------

    private static UnaryTests parse(String text, ColumnType type, String where) {
        try {
            return CellParser.parseFeel(text, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** Literal types an unquoted output entry of an untyped (string) output may hold, kept as their text. */
    private static final List<ColumnType> UNQUOTED_OUTPUT_TYPES =
            List.of(ColumnType.NUMBER, ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.TIME, ColumnType.DATE_TIME);

    /**
     * A literal output entry as plain text ({@link FeelLiterals#text}), or null. An unquoted entry of a
     * string output may still be a number, boolean, date, time or date-time literal.
     */
    private static String outputValue(String text, ColumnType type, String where) {
        var literal = text.strip();
        if (literal.isEmpty() || literal.equals("null")) {
            return null;
        }
        if (type == ColumnType.STRING && !literal.startsWith("\"")) {
            return UNQUOTED_OUTPUT_TYPES.stream()
                    .flatMap(other -> literalValue(literal, other).stream())
                    .findFirst()
                    .map(FeelLiterals::text)
                    .orElseThrow(() -> new InvalidDecisionTableException(
                            "%s: '%s' is not supported; output entries must be literals (quote text)".formatted(where, literal)));
        }
        return literalValue(literal, type)
                .map(FeelLiterals::text)
                .orElseThrow(() -> new InvalidDecisionTableException(
                        "%s: '%s' is not supported; output entries must be literals".formatted(where, literal)));
    }

    /** The value of an entry that is a single literal of the type (no list, comparison or name). */
    private static Optional<Object> literalValue(String literal, ColumnType type) {
        try {
            if (CellParser.parseFeel(literal, type) instanceof UnaryTests.Positive(var tests)
                    && tests.size() == 1 && tests.getFirst() instanceof UnaryTests.Equals(var value)) {
                return Optional.of(value);
            }
        } catch (CellSyntaxException e) {
            // not a literal of this type
        }
        return Optional.empty();
    }

    /**
     * A column's type: a built-in type, and the allowed values of the item definitions it was reached
     * through (the most derived one that declares any), or null.
     */
    private record Type(ColumnType columnType, String allowedValues) {
    }

    /**
     * The model's item definitions, by name. A column may name one whose base type is a built-in type
     * or another such item definition; structured types (with item components), collections and
     * function types are rejected.
     */
    private static final class ItemDefinitions {
        private final Map<String, Element> byName = new HashMap<>();

        ItemDefinitions(Element root) {
            children(root, "itemDefinition").forEach(definition -> byName.putIfAbsent(definition.getAttribute("name"), definition));
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
                default -> null;
            });
        }

        /** A type name without its namespace prefix ({@code feel:string} is {@code string}). */
        private static String localName(String typeRef) {
            return typeRef.substring(typeRef.indexOf(':') + 1).strip();
        }

        /**
         * Follows the chain of item definitions to a built-in type, one step at a time, so a long chain
         * costs no stack. The allowed values are those of the most derived definition that has any.
         */
        Type resolve(String typeRef, String where) {
            String allowedValues = null;
            String allowedBy = null;
            var seen = new HashSet<String>();
            var ref = typeRef;
            var builtIn = builtInType(ref);
            while (builtIn.isEmpty()) {
                var name = byName.containsKey(ref.strip()) ? ref.strip() : localName(ref);
                var definition = itemDefinition(name, ref, where);
                if (!seen.add(name)) {
                    throw new InvalidDecisionTableException("%s: item definition '%s' is defined in terms of itself".formatted(where, name));
                }
                var allowed = child(definition, "allowedValues");
                if (allowedValues == null && allowed != null && !textOf(allowed).isBlank()) {
                    allowedValues = textOf(allowed).strip();
                    allowedBy = name;
                }
                ref = child(definition, TYPE_REF).getTextContent();
                builtIn = builtInType(ref);
            }
            var type = builtIn.get();
            if (allowedValues != null) {
                parse(allowedValues, type, where + ", allowed values of type '" + allowedBy + "'");
            }
            return new Type(type, allowedValues);
        }

        /** The item definition of that name, if it is a simple type: one with a base type and no components. */
        private Element itemDefinition(String name, String typeRef, String where) {
            var definition = byName.get(name);
            if (definition == null) {
                throw new InvalidDecisionTableException(
                        "%s: type '%s' is not supported (string, number, date, boolean, time, dateTime and item definitions of them are)"
                                .formatted(where, typeRef));
            }
            if (Boolean.parseBoolean(definition.getAttribute("isCollection").strip())) {
                throw new InvalidDecisionTableException("%s: type '%s' is a collection, which is not supported".formatted(where, name));
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
            default -> throw new InvalidDecisionTableException(
                    "%s: aggregation '%s' is not supported (SUM, MIN and MAX are)".formatted(where, value));
        };
    }

    /**
     * A FEEL name, or a qualified name such as {@code applicant.age}. Each part starts with a letter,
     * {@code _} or {@code ?}, continues with letters, digits, {@code _} and {@code ?}, and may hold single
     * spaces between words, as FEEL allows: {@code Credit Score}, {@code Applicant data.Monthly income}.
     * Checked one code point at a time, so long input costs linear time and no stack.
     */
    static boolean isName(String text) {
        int previous = '.'; // the text starts a part, as if after a dot
        for (int c : text.codePoints().toArray()) {
            boolean separator = c == '.' || c == ' ';
            boolean afterSeparator = previous == '.' || previous == ' ';
            boolean valid = separator
                    ? !afterSeparator // no empty part, no space next to a dot, no double space
                    : isNameStart(c) || previous != '.' && Character.isDigit(c); // digits never start a part
            if (!valid) {
                return false;
            }
            previous = c;
        }
        return previous != '.' && previous != ' ';
    }

    private static boolean isNameStart(int c) {
        return Character.isLetter(c) || c == '_' || c == '?';
    }

    // ---- XML ---------------------------------------------------------------------------------

    private static Element parse(InputStream dmn) {
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

    /** The element's {@code name} attribute, or its {@code fallback} attribute if it has no name. */
    private static String nameOr(Element element, String fallback) {
        return element.getAttribute(element.getAttribute("name").isBlank() ? fallback : "name");
    }

    private static Element child(Element parent, String localName) {
        var found = children(parent, localName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The text of an element's {@code <text>} child (DMN expressions), or its own text. */
    private static String textOf(Element element) {
        var text = child(element, "text");
        return (text == null ? element : text).getTextContent();
    }
}
