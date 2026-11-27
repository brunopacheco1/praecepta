/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;

/**
 * Reads decision tables from DMN XML (DMN 1.1 to 1.5, as written by Camunda Modeler, Drools / KIE
 * tools, Trisotech and {@link DmnWriter}). Uses only the JDK's own XML parser, with DTDs and external
 * entities disabled, even when another JAXP implementation (such as Xerces) is on the class path.
 *
 * <p>Supported: decision tables whose input expressions name an input variable or are FEEL expressions
 * ({@code Age * 12}, which the engine computes from the inputs it reads), with
 * {@code string}, {@code number} (or {@code integer}, {@code long}, {@code double}), {@code date},
 * {@code boolean}, {@code time}, {@code dateTime} (or {@code date and time}), {@code dayTimeDuration} and
 * {@code yearMonthDuration} (or their FEEL names) columns, times and date-times with or without a time zone
 * (an offset, or a zone id, with which a time is a {@code ZonedTime}, or an {@code OffsetTime} if the zone has one
 * fixed offset); columns typed by an item definition of one of
 * those types, whose allowed values become the input value list unless the input declares its own;
 * columns typed by a collection of those ({@code isCollection="true"}), which hold lists and whose
 * entries test the elements ({@code list contains(?, "PT")}, see {@link CellParser#parseFeelList}), and
 * named inputs whose {@code inputData} is such a collection (see {@link DecisionTable#listInputs}); input
 * entries in the full FEEL language of unary tests (DMN 1.5, grammar rules 13 to 17): the simple ones as
 * {@link CellParser} reads them (lists, {@code not(...)}, {@code null}, comparisons, {@code =} and {@code !=},
 * intervals, {@code -}, with FEEL's literals and comments), which the bitset index evaluates, and any other
 * ({@link FeelUnaryTests}: {@code > Limit * 2}, {@code ? > 5 and ? < 10}, {@code [1, 2, 3]}), which the engine applies to
 * the rules the index matches; input and output value
 * lists; outputs' default values ({@code defaultOutputEntry}, see {@link DecisionTable#defaultOutput}) that are
 * literals, {@code null} included ({@link DecisionTable.OutputColumn#hasDefault}), or FEEL expressions that read no
 * input, evaluated when the table is read; output entries that are
 * literals or name one of the table's inputs (of the output's type, or
 * any input for a string output; an input is a column or an input the cells compare with, and holds single
 * values), or any other FEEL expression (DMN 1.5, chapter 10), kept as written in
 * {@link PraeceptaOutput#expressions()}, which the engine parses, types and evaluates; every hit policy; SUM, MIN, MAX
 * and COUNT aggregation. An entry or input expression that defines an external function is rejected with an
 * {@link UnsupportedFeatureException}, as Praecepta runs no code from outside the model, by design; anything else
 * Praecepta does not read with an {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are
 * numbered from 1 in table order.
 *
 * <p>{@link #readModel} reads a whole decision model instead, with its input data, its decisions (decision tables, literal
 * expressions and boxed expressions), its business knowledge models and their requirements, for {@code DecisionGraph}.
 */
public final class DmnReader {

    // DmnReader is the entry point. The reading itself is in the package-private classes of this package:
    //   DmnXml            parses the XML, safely, and reads elements, attributes and texts
    //   ItemDefinitions   the model's item definitions, and the types a typeRef names
    //   TableReader       one decision table: its hit policy, its input and output columns, its default outputs
    //   RuleReader        a table's rules: input entries, output entries, the inputs the cells compare with
    //   Literals          value lists and literal output entries
    //   ModelTypes        the types of a model's input data and decisions, for the columns and outputs that declare none
    //   ModelReader       a whole model: its imports, input data, decisions, knowledge models and decision services
    //   ExpressionReader  a decision's literal expression or boxed expression, part by part

    /** At most this many levels of item definitions and components in the type of an input data or a decision. */
    public static final int MAX_TYPE_DEPTH = 32;

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
        var root = DmnXml.parseModel(dmn);
        var decision = TableReader.decisionToRead(root, decisionName);
        return TableReader.decisionTable(decision, new ItemDefinitions(root), null);
    }

    /**
     * Reads every decision table of the model, in document order, as a framework integration does when it
     * gives each decision of a DMN file its own engine. Decisions whose logic is not a decision table are
     * skipped.
     *
     * @return the tables; empty if the model has none
     * @throws InvalidDecisionTableException if the model, or any of its decision tables, cannot be read
     */
    public static List<DecisionTable> readAll(InputStream dmn) {
        var root = DmnXml.parseModel(dmn);
        var types = new ItemDefinitions(root);
        return TableReader.decisionsWithTables(root).stream().map(decision -> TableReader.decisionTable(decision, types, null)).toList();
    }

    /**
     * Reads a whole decision model, for {@code DecisionGraph}: its input data, with their types, its decisions, with
     * their information and knowledge requirements and their logic, and its business knowledge models, with their
     * knowledge requirements and their logic, a function definition. Logic is a decision table (read as
     * {@link #read(InputStream, String)} reads it), a literal expression in FEEL ({@link DecisionLogic.LiteralExpression}),
     * or a boxed expression (DMN 1.5, 10.2: a context, an invocation, a function definition in FEEL, a relation, a list, a
     * conditional, an iterator {@code for}, {@code some} or {@code every}, a filter), whose parts are in turn any of these,
     * a decision table among them (named after the context entry, or the business knowledge model, that holds it, else
     * its decision). Each part is of the type it, or the variable of the context entry or binding that holds it,
     * declares. Every literal expression is checked to be FEEL with the names it may read in scope: its decision's
     * requirements, or its business knowledge model's knowledge requirements and own name, and those its enclosing
     * constructs bind ({@code decision A 2.1} reads as one name where it is one). An input
     * data's type is its variable's {@code typeRef}: a built-in type, or an item definition, which may be a collection or
     * have components (a context), nested up to {@value #MAX_TYPE_DEPTH} levels; none if it has no {@code typeRef}. A
     * literal expression's type is the decision variable's {@code typeRef} (or the literal expression's own, where the
     * variable has none), and so is a decision table's, which its value is bound to. Where a decision table's input
     * expression or output declares no {@code typeRef}, the model gives it one: an input is of the type of the input data
     * or decision it reads (or of the component of it a path reads), and an output of the type the decision's variable
     * declares for it (the variable's for a single output, its component's of the output's name for several), else of
     * the type of its output entries and default, when they are all literals of one type ({@code 10} is a number, as
     * FEEL reads it); else they are strings, as {@link #read(InputStream, String)} reads them. A
     * requirement names a decision, an input data, a business knowledge model or a decision service of the model by its
     * {@code href}
     * ({@code #id}); one that refers to
     * no element of the model is kept as the {@code id} it refers to, which {@code DecisionGraph} reports as a missing
     * requirement. Knowledge sources and authority requirements, which do not take part in evaluation, text annotations,
     * groups and diagrams are ignored, and so is a decision without logic that no decision requires, which changes no
     * value Praecepta evaluates. Decision services are read with their output, encapsulated and input decisions and input
     * data, by reference, and the function type their variable declares. The model's item definitions are
     * its {@linkplain DecisionModel#types() types}, by name, which a literal expression's {@code instance of} may name; a
     * function item among them is a function type, and one with neither a base type nor components is any type.
     *
     * <p>What Praecepta does not evaluate or read is rejected with an {@link UnsupportedFeatureException} that names the
     * element: an external function (in FEEL, or a boxed function definition in Java or PMML), by design, as Praecepta
     * runs no code from outside the model; the values a literal expression imports ({@code importedValues}); and an
     * import, as this method reads a model alone ({@link #readModel(InputStream, ImportResolver)} reads it with the
     * models it imports).
     *
     * @throws InvalidDecisionTableException if the model cannot be read (a literal expression that is not FEEL among
     *                                       other things), uses what Praecepta does not evaluate or read, or names two
     *                                       of its decisions, input data and business knowledge models alike
     */
    public static DecisionModel readModel(InputStream dmn) {
        return readModel(dmn, null);
    }

    /**
     * Finds the DMN of a model another model imports (DMN 1.5, 6.3.3), for {@link #readModel(InputStream, ImportResolver)}.
     */
    @FunctionalInterface
    public interface ImportResolver {

        /**
         * Opens the DMN of the model of that namespace, which the importing model may say where to find.
         *
         * @param namespace   the imported model's namespace
         * @param locationUri the import's {@code locationURI}, relative to the importing model's file; empty if it gives
         *                    none
         * @return the model's DMN, which the reader closes; null if there is none
         * @throws IOException if it cannot be opened
         */
        InputStream open(String namespace, String locationUri) throws IOException;
    }

    /**
     * Reads a whole decision model, as {@link #readModel(InputStream)} does, and the models it imports (DMN 1.5, 6.3.3),
     * each found by its namespace with {@code imports}, read in turn with the models they import, each once
     * ({@link DecisionModel#imports()}). The importing model reads an imported element by {@code importName.elementName}
     * (by its own name for an import without a name), in FEEL, in a {@code typeRef}, and in an {@code href} that names
     * the imported model's namespace ({@code namespace#id}).
     *
     * @param imports finds the models imported; null to reject a model that imports another, as
     *                {@link #readModel(InputStream)} does
     * @throws InvalidDecisionTableException if a model cannot be read, imports one {@code imports} does not find or one
     *                                       that imports it in turn, or imports anything but a DMN model
     */
    public static DecisionModel readModel(InputStream dmn, ImportResolver imports) {
        return ModelReader.read(DmnXml.parseModel(dmn), imports);
    }

    /**
     * The namespace of a DMN model (its {@code definitions} element's), by which other models import it: for finding
     * the models a model imports among a set of files ({@link ImportResolver}).
     *
     * @return the namespace; empty if the model has none
     * @throws InvalidDecisionTableException if it is not DMN XML
     */
    public static String namespace(InputStream dmn) {
        return DmnXml.namespaceOf(DmnXml.parseModel(dmn));
    }
}
