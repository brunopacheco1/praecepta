/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.INPUT_DATA;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.OUTPUT_ENTRY;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.descendants;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.textOf;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.TableReader.TableScope;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads the rules of a decision table: their input entries, kept as written once checked, and their output entries,
 * each a literal value, the name of an input or a FEEL expression; and finds the inputs their cells compare with that
 * are no column of the table.
 */
final class RuleReader {

    private RuleReader() {
    }

    /**
     * The table's columns, as its rules are read.
     *
     * @param lists the inputs that hold lists
     */
    record Columns(List<InputColumn> inputs, List<OutputColumn> outputs, Set<String> lists, TableScope scope) {

        /** Whether an input is one of the table's columns. */
        boolean isInput(String name) {
            return inputs.stream().anyMatch(input -> input.name().equals(name));
        }
    }

    /**
     * The table's rules, numbered from 1 in table order. The inputs their cells compare with that hold lists are added
     * to the columns' lists.
     *
     * @param root the model's {@code definitions}, whose input data type the inputs the cells compare with
     */
    static List<Praeceptum> rules(List<Element> ruleElements, Columns columns, Element root, ItemDefinitions types, String where) {
        var rules = new ArrayList<Praeceptum>();
        var names = new ArrayList<NamedInput>();
        for (int i = 0; i < ruleElements.size(); i++) {
            rules.add(rule(ruleElements.get(i), where + ", rule " + (i + 1), i + 1, columns, names));
        }
        // cells stay strict FEEL, where a name compares with another input
        var named = namedInputs(names, where);
        if (!named.isEmpty()) {
            columns.lists().addAll(namedListInputs(root, named, types, where));
        }
        return checkComputedOutputs(rules, named, columns.lists(), columns.outputs(), where);
    }

    /**
     * A rule, with its input entries kept as written (once checked) and its output entries as plain text.
     *
     * @param where  the rule, as errors name it
     * @param number the rule's number, from 1 in table order
     * @param names  where the inputs the rule's cells compare with, other than columns, are added
     */
    private static Praeceptum rule(Element rule, String where, int number, Columns columns, List<NamedInput> names) {
        var inputEntries = children(rule, "inputEntry");
        var outputEntries = children(rule, OUTPUT_ENTRY);
        if (inputEntries.size() != columns.inputs().size() || outputEntries.size() != columns.outputs().size()) {
            throw new InvalidDecisionTableException("%s: has %d input and %d output entries; the table has %d inputs and %d outputs"
                    .formatted(where, inputEntries.size(), outputEntries.size(), columns.inputs().size(), columns.outputs().size()));
        }
        var input = inputEntries(inputEntries, where, columns, names);
        return new Praeceptum(number, input, outputEntries(outputEntries, where, columns));
    }

    /**
     * A rule's input entries, kept as written once checked; adds the inputs they compare with that are not columns to
     * {@code names}. An entry beyond the simple unary tests the bitset index evaluates is checked to be FEEL unary tests.
     */
    private static PraeceptaInput inputEntries(List<Element> entries, String where, Columns columns, List<NamedInput> names) {
        Map<String, String> cells = new HashMap<>();
        for (int c = 0; c < entries.size(); c++) {
            var column = columns.inputs().get(c);
            var text = textOf(entries.get(c)).strip();
            var cellWhere = where + ", column '" + column.name() + "'";
            var cell = ruleCell(text, column.type(), columns.lists().contains(column.name()), columns.scope(), cellWhere);
            if (cell != null && cell.namesInputs()) { // a list column's simple cells name no input
                UnaryTests.names(cell).stream()
                        .filter(name -> !columns.isInput(name))
                        .forEach(name -> names.add(new NamedInput(name, column.type())));
            }
            cells.put(column.name(), text);
        }
        return new PraeceptaInput(cells);
    }

    /**
     * A rule's input entry: the simple unary tests {@link CellParser} reads (for a list column, its forms of
     * {@code list contains}), which the bitset index evaluates; else null, once it is checked to be FEEL unary tests
     * ({@link FeelUnaryTests}), which the engine applies to the rules the index matches.
     *
     * @throws UnsupportedFeatureException  if it defines an external function
     * @throws InvalidDecisionTableException if it is neither
     */
    private static UnaryTests ruleCell(String text, ColumnType type, boolean list, TableScope scope, String where) {
        try {
            return list ? CellParser.parseFeelList(text, type) : CellParser.parseFeel(text, type);
        } catch (CellSyntaxException rejected) {
            FeelUnaryTests tests;
            try {
                tests = FeelUnaryTests.parseCell(text, scope.get(), rejected);
            } catch (CellSyntaxException e) {
                throw new InvalidDecisionTableException(where + ": " + e.getMessage());
            }
            TableReader.rejectUnsupported(where, tests.unsupported());
            return null;
        }
    }

    /**
     * A rule's output entries, as plain text: literal values, names of inputs, and FEEL expressions computing a value. A
     * literal of an output that is not {@linkplain OutputColumn#typed() typed} is an expression, so that it keeps its own
     * type.
     */
    private static PraeceptaOutput outputEntries(List<Element> entries, String where, Columns columns) {
        Map<String, String> values = new HashMap<>();
        Map<String, String> references = new HashMap<>();
        Map<String, String> expressions = new HashMap<>();
        for (int c = 0; c < entries.size(); c++) {
            var column = columns.outputs().get(c);
            var text = textOf(entries.get(c)).strip();
            var outputWhere = TableReader.outputWhere(where, column.name());
            var input = namedInput(text, columns.inputs(), column, outputWhere);
            var literal = input == null ? Literals.literalOutput(text, column.type()) : null;
            if (input != null) {
                checkReference(input, columns.lists(), outputWhere);
                references.put(column.name(), input.name());
            } else if (literal != null && (column.typed() || literal.text() == null)) {
                values.put(column.name(), literal.text());
            } else {
                // an entry that names an input the cells compare with is a reference (see checkComputedOutputs)
                parseExpression(text, columns.scope().get(), outputWhere);
                if (column.typed()) {
                    rejectLiteralOfAnotherType(text, column.type(), columns.scope().get(), outputWhere);
                }
                expressions.put(column.name(), text);
            }
        }
        return new PraeceptaOutput(values, references, expressions);
    }

    /** An input a cell compares with that is not a column of the table, with the type of the column whose cell names it. */
    private record NamedInput(String name, ColumnType type) {
    }

    /**
     * The inputs the cells compare with, as {@link DecisionTable#namedInputs} finds them, from the names the
     * cells were found to hold when they were read: each with the type of the column that names it, in the
     * order first named. Reading them here spares parsing every cell again.
     */
    private static Map<String, ColumnType> namedInputs(List<NamedInput> names, String where) {
        var named = new LinkedHashMap<String, ColumnType>();
        for (var input : names) {
            var previous = named.putIfAbsent(input.name(), input.type());
            if (previous != null && previous != input.type()) {
                throw new InvalidDecisionTableException("%s: '%s' is compared with both a %s and a %s column"
                        .formatted(where, input.name(), previous.feelName(), input.type().feelName()));
            }
        }
        return named;
    }

    /**
     * The inputs the cells compare with that hold lists: those whose {@code inputData} has a collection
     * type of the type of the column that names them, as {@code Flu Symptoms} in
     * {@code Flu Symptoms} (a cell that holds when the column's value is in that list).
     */
    private static List<String> namedListInputs(Element root, Map<String, ColumnType> named, ItemDefinitions types, String where) {
        var lists = new ArrayList<String>();
        for (var inputData : descendants(root, INPUT_DATA)) {
            var inputName = inputData.getAttribute("name").strip();
            var typeRef = variableTypeRef(inputData);
            if (!named.containsKey(inputName) || typeRef.isBlank()) {
                continue;
            }
            var type = types.typeIfSupported(typeRef, TableReader.inputWhere(where, inputName));
            if (type.isPresent() && type.get().list()) {
                if (type.get().columnType() != named.get(inputName)) {
                    throw new InvalidDecisionTableException("%s: a %s column compares with '%s', a list of %s".formatted(
                            where, named.get(inputName).feelName(), inputName, type.get().columnType().feelName()));
                }
                lists.add(inputName);
            }
        }
        return lists;
    }

    /** An output entry may name an input that holds single values only: an output holds one value. */
    private static void checkReference(InputColumn input, Set<String> lists, String where) {
        if (lists.contains(input.name())) {
            throw new InvalidDecisionTableException("%s: names input '%s', a list, which outputs cannot hold".formatted(where, input.name()));
        }
    }

    /**
     * Once the inputs the cells compare with are known: an output entry that is just the name of such an input names
     * it, as an entry naming a column does.
     */
    private static List<Praeceptum> checkComputedOutputs(List<Praeceptum> rules, Map<String, ColumnType> named, Set<String> lists,
            List<OutputColumn> outputs, String where) {
        var outputsByName = new HashMap<String, OutputColumn>();
        outputs.forEach(output -> outputsByName.put(output.name(), output));
        var checked = new ArrayList<Praeceptum>();
        for (var rule : rules) {
            var output = rule.output();
            var references = new HashMap<>(output.references());
            var expressions = new HashMap<String, String>();
            output.expressions().forEach((column, text) -> {
                var outputWhere = TableReader.outputWhere(where + ", rule " + rule.priority(), column);
                var input = named.containsKey(text)
                        ? namedInput(text, List.of(new InputColumn(text, named.get(text))), outputsByName.get(column), outputWhere)
                        : null;
                if (input != null) {
                    checkReference(input, lists, outputWhere);
                    references.put(column, text);
                } else {
                    expressions.put(column, text);
                }
            });
            checked.add(new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(output.values(), references, expressions)));
        }
        return checked;
    }

    /**
     * Rejects an output entry that is a single literal, as written, but not one of its output's type: a date and time
     * literal that gives no value ({@code time("08:00:00+14:01")}), or, but for a string output, which takes any value as
     * its text, a literal of another type ({@code "x"} for a number). Such an entry is a mistake in a literal, as a cell's
     * literal of another type is, not an expression (one that computes a value is checked when the engine is built).
     *
     * @throws InvalidDecisionTableException if it is such a literal
     */
    private static void rejectLiteralOfAnotherType(String text, ColumnType type, FeelExpression.Scope scope, String where) {
        var problem = literalProblem(text, type, scope);
        if (problem != null) {
            throw new InvalidDecisionTableException("%s: '%s' is not a literal %s%s".formatted(where, text, type.feelName(), problem));
        }
    }

    /**
     * What is wrong with an entry that is a single literal, a name of none, but not one of the type: {@code ""} for a
     * literal of another type (none for a string), the error FEEL reports for a literal that gives no value; null for an
     * entry that is no single literal, or is fine.
     */
    private static String literalProblem(String text, ColumnType type, FeelExpression.Scope scope) {
        FeelUnaryTests tests;
        try {
            tests = FeelUnaryTests.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            return null;
        }
        if (tests.isAny() || !tests.isSimple() || !tests.names().isEmpty()) {
            return null;
        }
        var errors = new ArrayList<String>();
        FeelExpression.parse(text, scope).evaluate(name -> null, errors::add);
        if (!errors.isEmpty()) {
            return ": " + errors.getFirst();
        }
        return type == ColumnType.STRING ? null : "";
    }

    /**
     * Checks an output entry that computes its value: a FEEL expression (DMN 1.5, chapter 10), which the engine parses,
     * types and evaluates; one whose value is null whatever its inputs is a WARNING of its validation report.
     *
     * @throws UnsupportedFeatureException  if it defines an external function
     * @throws InvalidDecisionTableException if it is not FEEL
     */
    private static void parseExpression(String text, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not a FEEL expression (%s)".formatted(where, text, e.getMessage()));
        }
        TableReader.rejectUnsupported(where, expression.unsupported());
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
}
