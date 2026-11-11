/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;

/** A value in a TCK test file: an input node, an expected result, or a result computed by Praecepta. */
sealed interface TckValue {

    String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    /** No value: {@code xsi:nil}, a missing input, or no matching rule. */
    record Nil() implements TckValue {
        @Override
        public String toString() {
            return "null";
        }
    }

    /**
     * @param type the XML Schema type without prefix ({@code string}, {@code decimal}, {@code date}...),
     *             or empty if not given
     */
    record Simple(String type, String text) implements TckValue {
        @Override
        public String toString() {
            return type.equals("string") ? '"' + text + '"' : text;
        }
    }

    /** A context: the outputs of a multi-output table, or a structured input. */
    record Components(Map<String, TckValue> values) implements TckValue {
        @Override
        public String toString() {
            return values.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /** A list: the outputs of a multi-hit table, or a list input. */
    record Items(List<TckValue> values) implements TckValue {
        @Override
        public String toString() {
            return values.toString();
        }
    }

    TckValue NIL = new Nil();

    /** Reads the value held by an {@code inputNode}, {@code expected}, {@code component} or {@code item}. */
    static TckValue read(Element holder) {
        var components = new LinkedHashMap<String, TckValue>();
        for (var child : TckXml.children(holder)) {
            switch (child.getLocalName()) {
                case "value" -> {
                    return "true".equals(child.getAttributeNS(XSI, "nil")) ? NIL : new Simple(xsiType(child), child.getTextContent());
                }
                case "list" -> {
                    var items = new ArrayList<TckValue>();
                    TckXml.children(child, "item").forEach(item -> items.add(read(item)));
                    return new Items(items);
                }
                case "component" -> components.put(child.getAttribute("name"), read(child));
                default -> {
                    // documentation and other annotations
                }
            }
        }
        return components.isEmpty() ? NIL : new Components(components);
    }

    private static String xsiType(Element value) {
        var type = value.getAttributeNS(XSI, "type");
        return type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
    }

    /**
     * Whether {@code actual} is the value this expected value describes. Numbers compare as decimals,
     * dates, times and date-times by value, and booleans ignoring case; a list compares in order unless
     * {@code ordered} is false. Praecepta has no null list, so an empty list stands for null.
     */
    default boolean matches(TckValue actual, boolean ordered) {
        return switch (this) {
            case Nil nil -> actual instanceof Nil || actual instanceof Items items && items.values().isEmpty();
            case Simple expected -> actual instanceof Simple simple && sameSimple(expected, simple.text());
            case Components expected -> actual instanceof Components components
                    && expected.values().keySet().equals(components.values().keySet())
                    && expected.values().entrySet().stream()
                            .allMatch(e -> e.getValue().matches(components.values().get(e.getKey()), ordered));
            case Items expected -> actual instanceof Items items ? sameItems(expected.values(), items.values(), ordered)
                    : expected.values().isEmpty() && actual instanceof Nil;
        };
    }

    private static boolean sameSimple(Simple expected, String actual) {
        try {
            return switch (expected.type().toLowerCase(Locale.ROOT)) {
                case "decimal", "double", "integer", "int", "long", "float" ->
                        new BigDecimal(expected.text().strip()).compareTo(new BigDecimal(actual.strip())) == 0;
                case "date" -> LocalDate.parse(expected.text().strip()).equals(LocalDate.parse(actual.strip()));
                case "time" -> sameTemporal(FeelTemporals.parseTime(expected.text().strip()), FeelTemporals.parseTime(actual.strip()));
                case "datetime" -> sameTemporal(FeelTemporals.parseDateTime(expected.text().strip()),
                        FeelTemporals.parseDateTime(actual.strip()));
                case "boolean" -> expected.text().strip().equalsIgnoreCase(actual.strip());
                default -> expected.text().equals(actual);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return false;
        }
    }

    /** Equal as FEEL compares times and date-times: with a zone by instant; never one with a zone and one without. */
    private static boolean sameTemporal(Object expected, Object actual) {
        return FeelTemporals.comparable(expected, actual) && FeelTemporals.compare(expected, actual) == 0;
    }

    private static boolean sameItems(List<TckValue> expected, List<TckValue> actual, boolean ordered) {
        if (expected.size() != actual.size()) {
            return false;
        }
        if (ordered) {
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).matches(actual.get(i), true)) {
                    return false;
                }
            }
            return true;
        }
        var left = new ArrayList<>(actual);
        for (var item : expected) {
            var match = left.stream().filter(candidate -> item.matches(candidate, false)).findFirst();
            if (match.isEmpty()) {
                return false;
            }
            left.remove(match.get());
        }
        return true;
    }
}
