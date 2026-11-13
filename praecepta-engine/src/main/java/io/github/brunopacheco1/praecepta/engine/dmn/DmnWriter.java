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
import java.util.List;
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
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
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
    /** The prefix of the ids and item definition names of the table's inputs, followed by their number. */
    private static final String INPUT = "input_";
    /** The prefix of the ids of a model's input data, followed by their position. */
    private static final String INPUT_DATA = "input_data_";
    /** The prefix of the ids of a model's decisions, followed by their position. */
    private static final String DECISION = "decision_";

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
     * {@code typeRef} for {@link FeelType#ANY any type}), and a {@code decision} per decision, with an
     * {@code informationRequirement} per requirement and its logic: a decision table, written as {@link #write(DecisionTable)}
     * writes it, or a {@code literalExpression}, as written; the type the decision declares is its variable's. Elements are
     * identified by their position ({@code input_data_0}, {@code decision_0}), so that any names may be told apart.
     *
     * @throws InvalidDecisionTableException if a decision requires a decision or an input data the model does not have,
     *                                       which DMN cannot refer to; if a type is a list of lists, which an item
     *                                       definition cannot hold; or if a table's cell is not valid for its column type
     */
    public static String write(DecisionModel model) {
        var id = identifier(model.name());
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions").append(attribute("xmlns", NAMESPACE)).append(idAndName("definitions_" + id, model.name()))
           .append(attribute("namespace", "https://github.com/brunopacheco1/praecepta/" + id)).append(">\n");
        var ids = new HashMap<String, String>();
        var inputData = model.inputData();
        var decisions = model.decisions();
        var inputTypes = new String[inputData.size()];
        for (int i = 0; i < inputData.size(); i++) {
            ids.put(inputData.get(i).name(), INPUT_DATA + i);
            inputTypes[i] = typeRef(xml, inputData.get(i).type(), INPUT_DATA + i + "_type", "input data '" + inputData.get(i).name() + "'");
        }
        var decisionTypes = new String[decisions.size()];
        for (int d = 0; d < decisions.size(); d++) {
            var decision = decisions.get(d);
            ids.put(decision.name(), DECISION + d);
            switch (decision.logic()) {
                case DecisionLogic.Table(var table, var type) -> {
                    listItemDefinitions(xml, table, DECISION + d + "_");
                    decisionTypes[d] = typeRef(xml, type, DECISION + d + "_type", "decision '" + decision.name() + "'");
                }
                case DecisionLogic.LiteralExpression literal -> decisionTypes[d] = typeRef(xml, literal.type(), DECISION + d + "_type",
                        "decision '" + decision.name() + "'");
            }
        }
        for (int i = 0; i < inputData.size(); i++) {
            var name = inputData.get(i).name();
            xml.append("  <inputData").append(idAndName(INPUT_DATA + i, name)).append(">\n")
               .append("    <variable").append(idAndName(INPUT_DATA + i + "_variable", name)).append(optionalType(inputTypes[i])).append("/>\n")
               .append("  </inputData>\n");
        }
        for (int d = 0; d < decisions.size(); d++) {
            decision(xml, d, decisions.get(d), decisionTypes[d], ids);
        }
        xml.append("</definitions>\n");
        return xml.toString();
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

    /** A decision of a model, at position {@code d}: its variable, its information requirements and its logic. */
    private static void decision(StringBuilder xml, int d, DecisionModel.Decision decision, String type, Map<String, String> ids) {
        var where = "Decision '" + decision.name() + "'";
        xml.append("  <decision").append(idAndName(DECISION + d, decision.name())).append(">\n")
           .append("    <variable").append(idAndName(DECISION + d + "_variable", decision.name())).append(optionalType(type)).append("/>\n");
        var requirements = decision.requirements();
        for (int r = 0; r < requirements.size(); r++) {
            var required = requirements.get(r);
            var href = ids.get(required);
            if (href == null) {
                throw new InvalidDecisionTableException("%s requires '%s', which is not in the model, so DMN cannot refer to it"
                        .formatted(where, required));
            }
            var kind = r < decision.requiredDecisions().size() ? "requiredDecision" : "requiredInput";
            xml.append("    <informationRequirement").append(id(DECISION + d + "_requirement_" + r)).append("><").append(kind)
               .append(attribute("href", "#" + href)).append("/></informationRequirement>\n");
        }
        switch (decision.logic()) {
            case DecisionLogic.Table(var table, var tableType) -> decisionTable(xml, table, DECISION + d + "_");
            case DecisionLogic.LiteralExpression literal -> xml.append("    <literalExpression").append(id(DECISION + d + "_literal"))
                    .append("><text>").append(escapeText(literal.text())).append("</text></literalExpression>\n");
        }
        xml.append("  </decision>\n");
    }

    /** The attribute {@code typeRef="..."}, or nothing for a value of any type. */
    private static String optionalType(String typeRef) {
        return typeRef == null ? "" : attribute("typeRef", typeRef);
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
            case FeelType.Simple(var simple) -> xml.append(indent).append("  <typeRef>").append(typeName(simple)).append("</typeRef>\n");
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
            xml.append("  <itemDefinition").append(attribute("name", items)).append(">\n")
               .append("    <typeRef>").append(typeName(input.type())).append("</typeRef>\n")
               .append("    <allowedValues><text>").append(escapeText(values)).append("</text></allowedValues>\n")
               .append("  </itemDefinition>\n");
        }
        xml.append("  <itemDefinition").append(attribute("name", listTypeName(v, prefix))).append(attribute("isCollection", "true")).append(">\n")
           .append("    <typeRef>").append(escapeText(items)).append("</typeRef>\n")
           .append("  </itemDefinition>\n");
    }

    /** The name of the item definition of the list input at {@code v}. */
    private static String listTypeName(int v, String prefix) {
        return prefix + INPUT + v + "_list";
    }

    /** The {@code inputData} of an input, which Drools needs to bind its value. */
    private static void inputData(StringBuilder xml, int v, InputColumn input, DecisionTable table) {
        xml.append("  <inputData").append(idAndName("input_data_" + v, input.name())).append(">\n")
           .append("    <variable").append(idAndName("input_variable_" + v, input.name())).append(typed(input, v, table, "")).append("/>\n")
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
        xml.append("      <output").append(idAndName(prefix + "output_" + c, output.name())).append(typed(output.type()));
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
        return attribute("typeRef", typeName(type));
    }

    /** The attribute {@code typeRef="..."} of the input at {@code v}: its list type if it holds lists. */
    private static String typed(InputColumn input, int v, DecisionTable table, String prefix) {
        return table.listInputs().contains(input.name()) ? attribute("typeRef", listTypeName(v, prefix)) : typed(input.type());
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
