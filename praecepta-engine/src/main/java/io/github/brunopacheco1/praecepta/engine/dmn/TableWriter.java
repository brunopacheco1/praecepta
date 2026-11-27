/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.ITEM_DEFINITION;
import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.ITEM_DEFINITION_END;
import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.TYPE_REF_END;
import static io.github.brunopacheco1.praecepta.engine.dmn.TypeWriter.typeName;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.attribute;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.escapeText;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.id;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.idAndName;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Writes a decision table: its inputs, its outputs and its rules, with ids that tell the tables of a model apart, and the
 * item definitions of its inputs that hold lists. {@link CellWriter} writes its cells.
 */
final class TableWriter {

    /** The prefix of the ids and item definition names of the table's inputs, followed by their number. */
    private static final String INPUT = "input_";

    private TableWriter() {
    }

    /** @param prefix the prefix of the ids of the table's elements, which tell the tables of a model apart */
    static void decisionTable(StringBuilder xml, DecisionTable table, String prefix) {
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
            var values = CellWriter.standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
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
                    .map(value -> CellWriter.outputLiteral(value, output.type(), "output values of '" + output.name() + "'"))
                    .collect(Collectors.joining(", "));
            expression(xml, "outputValues", id(prefix + "output_values_" + c), values);
        }
        if (output.hasDefault()) {
            var value = CellWriter.outputLiteral(output.defaultValue(), output.type(), "default output of '" + output.name() + "'");
            expression(xml, "defaultOutputEntry", id(prefix + "output_default_" + c), value);
        }
        xml.append("      </output>\n");
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
            var cell = CellWriter.standardFeel(text, input.type(), table.feelCells(), table.listInputs().contains(input.name()),
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
                value = CellWriter.outputLiteral(rule.output().values().get(output.name()), output.type(), outputWhere);
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

    /**
     * The inputs an output entry may name: the table's columns, and the inputs its cells compare with
     * (as {@code DmnReader} reads them, and the engine evaluates them).
     */
    private static Set<String> readableInputs(DecisionTable table) {
        var names = new HashSet<String>();
        table.inputs().forEach(input -> names.add(input.name()));
        names.addAll(namedInputs(table).keySet());
        return names;
    }

    /**
     * The inputs the table's cells compare with, or none if a cell is invalid: writing the rules then
     * reports that cell, naming its rule and column.
     */
    static Map<String, ColumnType> namedInputs(DecisionTable table) {
        try {
            return table.namedInputs();
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    /** The item definitions of a table's columns of lists. */
    static void listItemDefinitions(StringBuilder xml, DecisionTable table, String prefix) {
        var columns = table.inputs();
        for (int c = 0; c < columns.size(); c++) {
            if (table.listInputs().contains(columns.get(c).name())) {
                listItemDefinitions(xml, c, columns.get(c), prefix);
            }
        }
    }

    /**
     * The item definitions of a list input: a collection of its type or, if it declares input values,
     * of an item definition that allows only those (DMN constrains a list's items through their type).
     */
    static void listItemDefinitions(StringBuilder xml, int v, InputColumn input, String prefix) {
        var items = typeName(input.type());
        if (input.allowedValues() != null) {
            items = prefix + INPUT + v + "_item";
            var values = CellWriter.standardFeel(input.allowedValues(), input.type(), false, false, "input values of column '" + input.name() + "'");
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

    /**
     * The prefix of the ids of a table, and of the item definitions of its list columns: {@code base_}
     * ({@code decision_3_}); or, where the model names a type it would start ({@code decision_3_input_0_list}), the first
     * of {@code decision_3_1_}, {@code decision_3_2_}... that no type's name starts, so that no item definition is
     * written twice.
     */
    static String freePrefix(String base, Map<String, FeelType> named) {
        var prefix = base + "_";
        for (int k = 1; startsAName(named, prefix + INPUT); k++) {
            prefix = base + "_" + k + "_";
        }
        return prefix;
    }

    private static boolean startsAName(Map<String, FeelType> named, String start) {
        return named.keySet().stream().anyMatch(name -> name.startsWith(start));
    }

    /** The attribute {@code typeRef="..."} of a column type. */
    private static String typed(ColumnType type) {
        return attribute(TYPE_REF, typeName(type));
    }

    /** The attribute {@code typeRef="..."} of the input at {@code v}: its list type if it holds lists. */
    static String typed(InputColumn input, int v, DecisionTable table, String prefix) {
        return table.listInputs().contains(input.name()) ? attribute(TYPE_REF, listTypeName(v, prefix)) : typed(input.type());
    }
}
