/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Parses DMN XML, safely, and reads its elements: the DOM helpers the readers of this package share, and the names of
 * the elements and attributes they read.
 */
final class DmnXml {

    static final String TYPE_REF = "typeRef";
    static final String INPUT_DATA = "inputData";
    static final String DECISION = "decision";
    static final String DECISION_TABLE = "decisionTable";
    static final String VARIABLE = "variable";
    static final String LABEL = "label";
    static final String OUTPUT_ENTRY = "outputEntry";
    static final String DEFAULT_OUTPUT_ENTRY = "defaultOutputEntry";
    static final String AGGREGATION = "aggregation";
    /** The aggregation that counts the outputs, which says nothing of their type. */
    static final String COUNT = "COUNT";

    private DmnXml() {
    }

    /**
     * The model's {@code definitions} element, parsed with DTDs and external entities disabled. The parser is
     * the JDK's own ({@code newDefaultInstance}), which these settings are written for, never one JAXP would
     * look up on the class path or through the {@code javax.xml.parsers.DocumentBuilderFactory} property.
     */
    static Element parseModel(InputStream dmn) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
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

    /** A model's namespace, by which other models import it; empty if it has none. */
    static String namespaceOf(Element root) {
        return root.getAttribute("namespace").strip();
    }

    /** The elements of that local name within {@code parent}, at any depth, in document order. */
    static List<Element> descendants(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        var elements = new ArrayList<Element>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    /** The child elements of that local name, in document order. */
    static List<Element> children(Element parent, String localName) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    /** The first child element of that local name, or null if there is none. */
    static Element child(Element parent, String localName) {
        var found = children(parent, localName);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The element's {@code name} attribute, or its {@code fallback} attribute if it has no name. */
    static String nameOr(Element element, String fallback) {
        var name = element.getAttribute("name");
        return name.isBlank() ? element.getAttribute(fallback) : name;
    }

    /** The text of an element's {@code <text>} child (DMN expressions), or its own text. */
    static String textOf(Element element) {
        var text = child(element, "text");
        return (text == null ? element : text).getTextContent();
    }

    /** The text of a child such as {@code <inputValues>}, or null if there is none or it is blank. */
    static String nonBlankText(Element parent, String localName) {
        var element = child(parent, localName);
        if (element == null) {
            return null;
        }
        var text = textOf(element);
        return text.isBlank() ? null : text;
    }

    /** The {@code typeRef} of an element's variable (a decision's, an input data's), blank if it has none. */
    static String variableTypeRef(Element element) {
        var variable = child(element, VARIABLE);
        return variable == null ? "" : variable.getAttribute(TYPE_REF);
    }
}
