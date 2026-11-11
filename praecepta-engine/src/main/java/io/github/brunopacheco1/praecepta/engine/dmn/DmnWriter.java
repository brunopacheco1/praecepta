/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;

/**
 * Writes a {@link DecisionTable} as a DMN 1.3 model with one decision. Cells are parsed and written
 * back as standard FEEL (quoted strings, plain numbers, {@code date("...")}, {@code time("...")},
 * {@code date and time("...")}, {@code duration("...")}, {@code true} / {@code false}), so tables written in
 * Praecepta's shorter syntax ({@code PT,ES}) become portable. Each input, column or input the cells
 * compare with, also gets an {@code inputData} element, which Drools needs to bind input values. An
 * input that holds lists is typed by a collection item definition (of an item type with its input
 * values, if it declares any), and a list column's cells are written with FEEL's list functions
 * ({@link #renderList}).
 *
 * <p>The result is read back unchanged by {@link DmnReader}, and is evaluated identically by
 * Camunda 8 and Drools (checked by the benchmark module).
 */
public final class DmnWriter {

    private static final String NAMESPACE = "https://www.omg.org/spec/DMN/20191111/MODEL/";

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
                listItemDefinitions(xml, v, variables.get(v));
            }
        }
        for (int v = 0; v < variables.size(); v++) {
            inputData(xml, v, variables.get(v), table);
        }
        decision(xml, id, table, variables.size());
        xml.append("</definitions>\n");
        return xml.toString();
    }

    /** Standard FEEL text for parsed unary tests. */
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
     * {@code CellParser#parseFeelList}.
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
    private static void listItemDefinitions(StringBuilder xml, int v, InputColumn input) {
        var items = typeName(input.type());
        if (input.allowedValues() != null) {
            items = "input_" + v + "_item";
            var values = standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
            xml.append("  <itemDefinition").append(attribute("name", items)).append(">\n")
               .append("    <typeRef>").append(typeName(input.type())).append("</typeRef>\n")
               .append("    <allowedValues><text>").append(escapeText(values)).append("</text></allowedValues>\n")
               .append("  </itemDefinition>\n");
        }
        xml.append("  <itemDefinition").append(attribute("name", listTypeName(v))).append(attribute("isCollection", "true")).append(">\n")
           .append("    <typeRef>").append(escapeText(items)).append("</typeRef>\n")
           .append("  </itemDefinition>\n");
    }

    /** The name of the item definition of the list input at {@code v}. */
    private static String listTypeName(int v) {
        return "input_" + v + "_list";
    }

    /** The {@code inputData} of an input, which Drools needs to bind its value. */
    private static void inputData(StringBuilder xml, int v, InputColumn input, DecisionTable table) {
        xml.append("  <inputData").append(idAndName("input_data_" + v, input.name())).append(">\n")
           .append("    <variable").append(idAndName("input_variable_" + v, input.name())).append(typed(input, v, table)).append("/>\n")
           .append("  </inputData>\n");
    }

    /** @param variables the number of inputData elements, which the decision requires */
    private static void decision(StringBuilder xml, String id, DecisionTable table, int variables) {
        xml.append("  <decision").append(idAndName("decision_" + id, table.name())).append(">\n")
           .append("    <variable").append(idAndName("decision_variable", table.name())).append("/>\n");
        for (int v = 0; v < variables; v++) {
            xml.append("    <informationRequirement").append(id("requirement_" + v)).append(">")
               .append("<requiredInput").append(attribute("href", "#input_data_" + v)).append("/></informationRequirement>\n");
        }
        decisionTable(xml, table);
        xml.append("  </decision>\n");
    }

    private static void decisionTable(StringBuilder xml, DecisionTable table) {
        // DMN spells the hit policies with spaces: RULE ORDER, OUTPUT ORDER
        var hitPolicy = table.hitPolicy().name().replace('_', ' ');
        xml.append("    <decisionTable").append(id("table")).append(attribute("hitPolicy", hitPolicy));
        if (table.aggregation() != null) {
            xml.append(attribute("aggregation", table.aggregation().name()));
        }
        xml.append(">\n");
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            input(xml, c, inputs.get(c), table);
        }
        var outputs = table.outputs();
        for (int c = 0; c < outputs.size(); c++) {
            output(xml, c, outputs.get(c));
        }
        var rules = table.rules();
        for (int r = 0; r < rules.size(); r++) {
            rule(xml, r, rules.get(r), table);
        }
        xml.append("    </decisionTable>\n");
    }

    /** A column; a list column's input values are those of its item type instead (see {@link #listItemDefinitions}). */
    private static void input(StringBuilder xml, int c, InputColumn input, DecisionTable table) {
        xml.append("      <input").append(id("input_" + c)).append(attribute("label", input.name())).append(">\n");
        expression(xml, "inputExpression", id("input_expression_" + c) + typed(input, c, table), input.name());
        if (input.allowedValues() != null && !table.listInputs().contains(input.name())) {
            var values = standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
            expression(xml, "inputValues", id("input_values_" + c), values);
        }
        xml.append("      </input>\n");
    }

    private static void output(StringBuilder xml, int c, OutputColumn output) {
        xml.append("      <output").append(idAndName("output_" + c, output.name())).append(typed(output.type()));
        if (output.values().isEmpty()) {
            xml.append("/>\n");
            return;
        }
        var values = output.values().stream()
                .map(value -> outputLiteral(value, output.type(), "output values of '" + output.name() + "'"))
                .collect(Collectors.joining(", "));
        xml.append(">\n");
        expression(xml, "outputValues", id("output_values_" + c), values);
        xml.append("      </output>\n");
    }

    /** @param r the rule's index, from 0 */
    private static void rule(StringBuilder xml, int r, Praeceptum rule, DecisionTable table) {
        var where = "Rule " + rule.priority();
        xml.append("      <rule").append(id("rule_" + r)).append(">\n");
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            var text = rule.input().values().get(input.name());
            var cell = standardFeel(text, input.type(), table.feelCells(), table.listInputs().contains(input.name()),
                    where + ", column '" + input.name() + "'");
            expression(xml, "inputEntry", id("input_entry_" + r + "_" + c), cell);
        }
        var outputs = table.outputs();
        for (int c = 0; c < outputs.size(); c++) {
            var output = outputs.get(c);
            var outputWhere = where + ", output '" + output.name() + "'";
            var input = rule.output().references().get(output.name());
            var value = input != null
                    ? namedInput(input, inputs, outputWhere)
                    : outputLiteral(rule.output().values().get(output.name()), output.type(), outputWhere);
            expression(xml, "outputEntry", id("output_entry_" + r + "_" + c), value);
        }
        xml.append("      </rule>\n");
    }

    /** An output entry that names an input: the input's name, which must be one of the table's columns. */
    private static String namedInput(String input, List<InputColumn> inputs, String where) {
        if (inputs.stream().noneMatch(column -> column.name().equals(input))) {
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
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
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
            case Equals(var value) -> "list contains(?, " + literal(value) + ")";
            case Compare c -> "some x in ? satisfies x " + test(c);
            case Range r -> "some x in ? satisfies x in " + test(r);
        };
    }

    private static String test(Test test) {
        return switch (test) {
            case NullTest() -> "null";
            case Equals(var value) -> literal(value);
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
                case DATE -> LocalDate.parse(text);
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
        return attribute("typeRef", typeName(type));
    }

    /** The attribute {@code typeRef="..."} of the input at {@code v}: its list type if it holds lists. */
    private static String typed(InputColumn input, int v, DecisionTable table) {
        return table.listInputs().contains(input.name()) ? attribute("typeRef", listTypeName(v)) : typed(input.type());
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
