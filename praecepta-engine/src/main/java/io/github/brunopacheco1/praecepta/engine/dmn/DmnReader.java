/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads decision tables from DMN XML (DMN 1.1 to 1.5, as written by Camunda Modeler, Drools / KIE
 * tools, Trisotech and {@link DmnWriter}). Uses only the JDK's XML parser, with DTDs and external
 * entities disabled.
 *
 * <p>Supported: decision tables whose input expressions name an input variable, with
 * {@code string}, {@code number} (or {@code integer}, {@code long}, {@code double}), {@code date},
 * {@code boolean}, {@code time} and {@code dateTime} (or {@code date and time}) columns, times without
 * a zone; input entries in Praecepta's FEEL subset (lists, {@code not(...)}, {@code null},
 * comparisons, intervals, {@code -}); input and output value lists; literal output entries; every
 * hit policy; SUM, MIN and MAX aggregation. Anything else is rejected with an
 * {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are numbered
 * from 1 in table order.
 */
public final class DmnReader {

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
            return decisionTable(tables.get(at));
        }
        if (tables.size() != 1) {
            throw new InvalidDecisionTableException(tables.isEmpty()
                    ? "DMN model has no decision table"
                    : "DMN model has %d decision tables %s; name the one to read".formatted(tables.size(), names));
        }
        return decisionTable(tables.getFirst());
    }

    private static DecisionTable decisionTable(Element decision) {
        var name = decision.getAttribute("name").isBlank() ? decision.getAttribute("id") : decision.getAttribute("name");
        var table = child(decision, "decisionTable");
        var where = "Decision '" + name + "'";

        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute("aggregation"), where);

        var inputs = new ArrayList<InputColumn>();
        for (var input : children(table, "input")) {
            var expression = child(input, "inputExpression");
            var column = expression == null ? "" : textOf(expression).strip();
            if (column.isEmpty()) {
                column = input.getAttribute("label").strip();
            }
            if (!isName(column)) {
                throw new InvalidDecisionTableException("%s: input expression '%s' is not supported; it must name an input variable"
                        .formatted(where, column));
            }
            var type = type(expression == null ? "" : expression.getAttribute("typeRef"), where + ", input '" + column + "'");
            var values = child(input, "inputValues");
            var allowed = values == null || textOf(values).isBlank() ? null : textOf(values).strip();
            if (allowed != null) {
                parse(allowed, type, where + ", input values of '" + column + "'");
            }
            inputs.add(new InputColumn(column, type, allowed));
        }

        var outputs = new ArrayList<OutputColumn>();
        var outputElements = children(table, "output");
        for (var output : outputElements) {
            var column = output.getAttribute("name").isBlank() ? output.getAttribute("label") : output.getAttribute("name");
            if (column.isBlank()) {
                // a single unnamed output is the decision's result, named after the decision
                column = outputElements.size() == 1 ? name : "";
            }
            if (column.isBlank()) {
                throw new InvalidDecisionTableException(where + ": every output of a multi-output table needs a name");
            }
            var outputWhere = where + ", output '" + column + "'";
            var type = type(output.getAttribute("typeRef"), outputWhere);
            var values = new ArrayList<String>();
            var valueList = child(output, "outputValues");
            if (valueList != null && !textOf(valueList).isBlank()) {
                var tests = parse(textOf(valueList), type, outputWhere + " value list");
                if (!(tests instanceof UnaryTests.Positive positive)
                        || !positive.tests().stream().allMatch(UnaryTests.Equals.class::isInstance)) {
                    throw new InvalidDecisionTableException(outputWhere + ": the output value list must be a list of values");
                }
                positive.tests().forEach(test -> values.add(plain(((UnaryTests.Equals) test).value())));
            }
            outputs.add(new OutputColumn(column, type, values));
        }
        if (outputs.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the decision table has no output");
        }

        var rules = new ArrayList<Praeceptum>();
        int number = 0;
        for (var rule : children(table, "rule")) {
            number++;
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
            rules.add(new Praeceptum(number, new PraeceptaInput(cells), new PraeceptaOutput(values)));
        }
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, rules);
    }

    // ---- values ---------------------------------------------------------------------------------

    private static UnaryTests parse(String text, ColumnType type, String where) {
        try {
            return CellParser.parse(text, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** A literal output entry as plain text (unquoted string, decimal number, ISO date), or null. */
    private static String outputValue(String text, ColumnType type, String where) {
        var literal = text.strip();
        if (literal.isEmpty() || literal.equals("null")) {
            return null;
        }
        if (type == ColumnType.STRING && !literal.startsWith("\"")) {
            // untyped or string outputs holding numbers or dates are kept as their text
            if (literal.matches("-?\\d+(\\.\\d+)?")) {
                return literal;
            }
            if (literal.equals("true") || literal.equals("false")) {
                return literal;
            }
            if (literal.startsWith("date(")) {
                return plain(single(literal, ColumnType.DATE, where));
            }
            if (literal.startsWith("time(")) {
                return plain(single(literal, ColumnType.TIME, where));
            }
            if (literal.matches("date\\s+and\\s+time\\(.*")) {
                return plain(single(literal, ColumnType.DATE_TIME, where));
            }
            throw new InvalidDecisionTableException("%s: '%s' is not supported; output entries must be literals (quote text)"
                    .formatted(where, literal));
        }
        return plain(single(literal, type, where));
    }

    private static Object single(String literal, ColumnType type, String where) {
        UnaryTests tests;
        try {
            tests = CellParser.parse(literal, type);
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not supported; output entries must be literals".formatted(where, literal));
        }
        if (tests instanceof UnaryTests.Positive positive && positive.tests().size() == 1
                && positive.tests().getFirst() instanceof UnaryTests.Equals equals) {
            return equals.value();
        }
        throw new InvalidDecisionTableException("%s: '%s' is not supported; output entries must be literals".formatted(where, literal));
    }

    private static String plain(Object value) {
        return switch (value) {
            case BigDecimal number -> number.toPlainString();
            case LocalDate date -> date.toString();
            case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
            case LocalDateTime dateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
            default -> value.toString();
        };
    }

    private static ColumnType type(String typeRef, String where) {
        var local = typeRef.contains(":") ? typeRef.substring(typeRef.indexOf(':') + 1) : typeRef;
        return switch (local.strip().toLowerCase(Locale.ROOT)) {
            case "", "string" -> ColumnType.STRING;
            case "number", "integer", "long", "double", "decimal", "int" -> ColumnType.NUMBER;
            case "date" -> ColumnType.DATE;
            case "boolean" -> ColumnType.BOOLEAN;
            case "time" -> ColumnType.TIME;
            case "datetime", "date and time" -> ColumnType.DATE_TIME;
            default -> throw new InvalidDecisionTableException(
                    "%s: type '%s' is not supported (string, number, date, boolean, time and dateTime are)".formatted(where, typeRef));
        };
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

    /** A FEEL name or a qualified name such as {@code applicant.age}. */
    private static boolean isName(String text) {
        return text.matches("[\\p{L}_?][\\p{L}\\p{N}_?]*(\\.[\\p{L}_?][\\p{L}\\p{N}_?]*)*");
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
