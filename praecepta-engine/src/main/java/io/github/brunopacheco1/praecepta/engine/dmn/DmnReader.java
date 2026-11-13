/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionTypeException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * Reads decision tables from DMN XML (DMN 1.1 to 1.5, as written by Camunda Modeler, Drools / KIE
 * tools, Trisotech and {@link DmnWriter}). Uses only the JDK's own XML parser, with DTDs and external
 * entities disabled, even when another JAXP implementation (such as Xerces) is on the class path.
 *
 * <p>Supported: decision tables whose input expressions name an input variable, with
 * {@code string}, {@code number} (or {@code integer}, {@code long}, {@code double}), {@code date},
 * {@code boolean}, {@code time}, {@code dateTime} (or {@code date and time}), {@code dayTimeDuration} and
 * {@code yearMonthDuration} (or their FEEL names) columns, times and date-times with or without a time zone
 * (an offset, or a zone id, with which a time is a {@code ZonedTime}, or an {@code OffsetTime} if the zone has one
 * fixed offset); columns typed by an item definition of one of
 * those types, whose allowed values become the input value list unless the input declares its own;
 * columns typed by a collection of those ({@code isCollection="true"}), which hold lists and whose
 * entries test the elements ({@code list contains(?, "PT")}, see {@link CellParser#parseFeelList}), and
 * named inputs whose {@code inputData} is such a collection (see {@link DecisionTable#listInputs}); input
 * entries in Praecepta's FEEL subset (lists, {@code not(...)}, {@code null}, comparisons, {@code =} and {@code !=},
 * intervals, {@code -}), with FEEL's literals (numbers such as {@code 1.2e3}, at-literals, every form of the date and
 * time functions) and comments, as {@link CellParser} reads them; input and output value
 * lists; outputs' default values ({@code defaultOutputEntry}, see {@link DecisionTable#defaultOutput}) that are
 * literals, {@code null} included ({@link DecisionTable.OutputColumn#hasDefault}); output entries that are
 * literals or name one of the table's inputs (of the output's type, or
 * any input for a string output; an input is a column or an input the cells compare with, and holds single
 * values), or that combine literals (strings, numbers, dates, times and durations) and the table's inputs with
 * {@code +}, {@code -}, {@code *}, {@code /} and {@code **} ({@link OutputExpression}, kept as written in
 * {@link PraeceptaOutput#expressions()}); every hit policy; SUM, MIN, MAX and COUNT aggregation. Anything else is rejected with an
 * {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are numbered
 * from 1 in table order.
 *
 * <p>{@link #readModel} reads a whole decision model instead, with its input data, its decisions (decision tables and
 * literal expressions) and their information requirements, for {@code DecisionGraph}.
 */
public final class DmnReader {

    private static final String TYPE_REF = "typeRef";
    /** At most this many levels of item definitions and components in the type of an input data or a decision. */
    public static final int MAX_TYPE_DEPTH = 32;

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
        return decisionTable(decision, new ItemDefinitions(root), null);
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
        var root = parseModel(dmn);
        var types = new ItemDefinitions(root);
        return decisionsWithTables(root).stream().map(decision -> decisionTable(decision, types, null)).toList();
    }

    // ---- the decision model ----------------------------------------------------------------------

    /**
     * Reads a whole decision model, for {@code DecisionGraph}: its input data, with their types, and its decisions, with
     * their information requirements and their logic, a decision table (read as {@link #read(InputStream, String)} reads
     * it) or a literal expression in Praecepta's expression subset ({@link DecisionLogic.LiteralExpression}). An input
     * data's type is its variable's {@code typeRef}: a built-in type, or an item definition, which may be a collection or
     * have components (a context), nested up to {@value #MAX_TYPE_DEPTH} levels; none if it has no {@code typeRef}. A
     * literal expression's type is the decision variable's {@code typeRef} (or the literal expression's own, where the
     * variable has none), and so is a decision table's, which its value is bound to. Where a decision table's input
     * expression or output declares no {@code typeRef}, the model gives it one: an input is of the type of the input data
     * or decision it reads (or of the component of it a path reads), and an output of the type the decision's variable
     * declares for it (the variable's for a single output, its component's of the output's name for several), else of
     * the type of its output entries and default, when they are all literals of one type ({@code 10} is a number, as
     * FEEL reads it); else they are strings, as {@link #read(InputStream, String)} reads them. A
     * requirement names a decision or an input data of the model by its {@code href} ({@code #id}); one that refers to
     * no element of the model is kept as the {@code id} it refers to, which {@code DecisionGraph} reports as a missing
     * requirement. Knowledge sources and authority requirements, which do not take part in evaluation, text annotations,
     * groups and diagrams are ignored.
     *
     * <p>What Praecepta does not evaluate yet is rejected, naming the element and the release that plans it: business
     * knowledge models, knowledge requirements and invocations, and decision logic that is a boxed expression (a context,
     * an invocation, a function definition, a relation, a list, a conditional, an iteration, a quantified expression, a
     * filter), planned for 0.10 (#118); literal expressions beyond Praecepta's expression subset, for 0.8 (#116) and,
     * for function calls, 0.9 (#117); decision services and imports, for 0.11 (#119).
     *
     * @throws InvalidDecisionTableException if the model cannot be read, uses what Praecepta does not support yet, or
     *                                       names two of its decisions and input data alike
     */
    public static DecisionModel readModel(InputStream dmn) {
        var root = parseModel(dmn);
        rejectUnsupported(root);
        var types = new ItemDefinitions(root);
        var elements = new HashMap<String, Element>();
        for (var kind : List.of("inputData", "decision")) {
            children(root, kind).forEach(element -> elements.putIfAbsent(element.getAttribute("id"), element));
        }
        var inputData = new ArrayList<DecisionModel.InputData>();
        var model = new ModelTypes();
        for (var input : children(root, "inputData")) {
            var name = elementName(input, "an input data");
            var variable = child(input, "variable");
            var typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
            var type = types.feelType(typeRef, "Input data '" + name + "'");
            inputData.add(new DecisionModel.InputData(name, type));
            model.types.putIfAbsent(name, type);
        }
        for (var decision : children(root, "decision")) {
            var name = nameOr(decision, "id");
            model.types.putIfAbsent(name, declaredTableType(decision, types, "Decision '" + name + "'"));
        }
        var decisions = new ArrayList<DecisionModel.Decision>();
        for (var decision : children(root, "decision")) {
            decisions.add(decision(decision, types, model, elements));
        }
        try {
            return new DecisionModel(root.getAttribute("name"), inputData, decisions);
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("DMN model: " + e.getMessage());
        }
    }

    /** Rejects the elements of a model that Praecepta does not evaluate yet, naming the release that plans them. */
    private static void rejectUnsupported(Element root) {
        rejectAny(root, "import", "namespace", "DMN model imports '%s', which Praecepta does not support yet: imports are planned for 0.11 (#119)",
                IMPORTS);
        rejectAny(root, "businessKnowledgeModel", "id", "DMN model has the business knowledge model '%s', which Praecepta does not support "
                + "yet: business knowledge models and their invocations are planned for 0.10 (#118)", BOXED);
        rejectAny(root, "decisionService", "id", "DMN model has the decision service '%s', which Praecepta does not support yet: "
                + "decision services are planned for 0.11 (#119)", IMPORTS);
    }

    /** The releases that plan what Praecepta does not evaluate yet, as {@link UnsupportedFeatureException} names them. */
    private record Planned(String release, int issue) {

        UnsupportedFeatureException exception(String message) {
            return new UnsupportedFeatureException(message, release, issue);
        }
    }

    private static final Planned FEEL = new Planned("0.8", 116);
    private static final Planned FUNCTIONS = new Planned("0.9", 117);
    private static final Planned BOXED = new Planned("0.10", 118);
    private static final Planned IMPORTS = new Planned("0.11", 119);

    /** Rejects the model if it has an element of that kind, naming the first by its name, or {@code fallback} attribute. */
    private static void rejectAny(Element root, String kind, String fallback, String message, Planned planned) {
        var elements = children(root, kind);
        if (!elements.isEmpty()) {
            throw planned.exception(message.formatted(nameOr(elements.getFirst(), fallback)));
        }
    }

    /** The name of an input data or a decision of a model, or its id if it has none; one with neither is rejected. */
    private static String elementName(Element element, String kind) {
        var name = nameOr(element, "id");
        if (name.isBlank()) {
            throw new InvalidDecisionTableException("DMN model has %s with neither a name nor an id".formatted(kind));
        }
        return name;
    }

    /** A decision of a model: its information requirements and its logic. */
    private static DecisionModel.Decision decision(Element decision, ItemDefinitions types, ModelTypes model, Map<String, Element> elements) {
        var name = elementName(decision, "a decision");
        var where = "Decision '" + name + "'";
        if (child(decision, "knowledgeRequirement") != null) {
            throw BOXED.exception(("%s requires business knowledge, which Praecepta does not support yet: business "
                    + "knowledge models and their invocations are planned for 0.10 (#118)").formatted(where));
        }
        var requiredDecisions = new ArrayList<String>();
        var requiredInputs = new ArrayList<String>();
        for (var requirement : children(decision, "informationRequirement")) {
            var requiredDecision = child(requirement, "requiredDecision");
            var requiredInput = child(requirement, "requiredInput");
            if ((requiredDecision == null) == (requiredInput == null)) {
                throw new InvalidDecisionTableException(where + ": an information requirement must name either a required decision or a "
                        + "required input (DMN 1.5, 6.3.13)");
            }
            if (requiredDecision != null) {
                requiredDecisions.add(required(requiredDecision, elements, where));
            } else {
                requiredInputs.add(required(requiredInput, elements, where));
            }
        }
        return new DecisionModel.Decision(name, requiredDecisions, requiredInputs, logic(decision, types, model, where));
    }

    /**
     * The name of the element a requirement refers to ({@code href="#id"}), or the {@code id} it refers to if the model
     * has no element with that id.
     */
    private static String required(Element requirement, Map<String, Element> elements, String where) {
        var href = requirement.getAttribute("href").strip();
        if (!href.startsWith("#")) {
            throw IMPORTS.exception(("%s requires '%s', an element of another model, which Praecepta does not support "
                    + "yet: imports are planned for 0.11 (#119)").formatted(where, href));
        }
        var id = href.substring(1);
        var element = elements.get(id);
        return element == null ? id : nameOr(element, "id");
    }

    /** The kinds of boxed expression a decision's logic may be, which Praecepta does not evaluate yet, as messages name them. */
    private static final Map<String, String> BOXED_EXPRESSIONS = Map.of(
            "context", "a boxed context",
            "invocation", "a boxed invocation",
            "functionDefinition", "a function definition",
            "relation", "a relation",
            "list", "a boxed list",
            "conditional", "a boxed conditional",
            "for", "a boxed iteration (for)",
            "every", "a boxed quantified expression (every)",
            "some", "a boxed quantified expression (some)",
            "filter", "a boxed filter");

    /** A decision's logic: its decision table or its literal expression. */
    private static DecisionLogic logic(Element decision, ItemDefinitions types, ModelTypes model, String where) {
        for (var node = decision.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            var element = (Element) node;
            var kind = element.getLocalName();
            if (kind.equals("decisionTable")) {
                return new DecisionLogic.Table(decisionTable(decision, types, model), declaredTableType(decision, types, where));
            }
            if (kind.equals("literalExpression")) {
                return literalExpression(decision, element, types, where);
            }
            if (BOXED_EXPRESSIONS.containsKey(kind)) {
                throw BOXED.exception(("%s: its logic is %s, which Praecepta does not support yet: boxed expressions are "
                        + "planned for 0.10 (#118)").formatted(where, BOXED_EXPRESSIONS.get(kind)));
            }
        }
        throw new InvalidDecisionTableException(where + " has no decision logic; Praecepta evaluates decisions whose logic is a decision "
                + "table or a literal expression");
    }

    /** A literal expression, of the type its decision's variable (or the expression itself) declares. */
    private static DecisionLogic literalExpression(Element decision, Element literal, ItemDefinitions types, String where) {
        var language = literal.getAttribute("expressionLanguage").strip();
        if (!language.isEmpty() && !language.toUpperCase(Locale.ROOT).contains("FEEL")) {
            throw new InvalidDecisionTableException("%s: its literal expression is in '%s'; Praecepta reads FEEL".formatted(where, language));
        }
        if (child(literal, "importedValues") != null) {
            throw IMPORTS.exception(("%s: its literal expression imports values, which Praecepta does not support yet: "
                    + "imports are planned for 0.11 (#119)").formatted(where));
        }
        var text = child(literal, "text") == null ? "" : textOf(literal).strip();
        if (text.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": its literal expression is empty");
        }
        // the value is bound to the decision's variable (DMN 1.5, 10.3.2.9.4), whose typeRef 7.3.1 requires the literal
        // expression's to equal; the literal expression's own is read only where the variable has none
        var typeRef = variableTypeRef(decision);
        if (typeRef.isBlank()) {
            typeRef = literal.getAttribute(TYPE_REF);
        }
        var type = types.feelType(typeRef, where);
        try {
            return new DecisionLogic.LiteralExpression(text, type);
        } catch (IllegalArgumentException e) {
            var planned = e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("function call") ? FUNCTIONS : FEEL;
            throw planned.exception(where + ": " + e.getMessage());
        }
    }

    /** The {@code typeRef} of a decision's variable, blank if it has none. */
    private static String variableTypeRef(Element decision) {
        var variable = child(decision, "variable");
        return variable == null ? "" : variable.getAttribute(TYPE_REF);
    }

    /**
     * The type a decision's variable declares, for its decision table's value to be bound to; any type if it declares
     * none, or one Praecepta cannot hold, in which case the table's value is passed on as its outputs give it.
     */
    private static FeelType declaredTableType(Element decision, ItemDefinitions types, String where) {
        try {
            return types.feelType(variableTypeRef(decision), where);
        } catch (InvalidDecisionTableException e) {
            return FeelType.ANY;
        }
    }

    // ---- the table -------------------------------------------------------------------------------

    /** The model's decisions whose logic is a decision table, in document order. */
    private static List<Element> decisionsWithTables(Element root) {
        return descendants(root, "decision").stream().filter(decision -> child(decision, "decisionTable") != null).toList();
    }

    /** The named decision, or the model's only decision with a table if no name is given. */
    private static Element decisionToRead(Element root, String decisionName) {
        var decisions = decisionsWithTables(root);
        var names = decisions.stream().map(decision -> decision.getAttribute("name")).toList();
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

    /**
     * @param model the types of the model's input data and decisions, which type the columns and outputs that declare
     *              none, when the table is read as part of a model; null when it is read alone
     */
    private static DecisionTable decisionTable(Element decision, ItemDefinitions types, ModelTypes model) {
        var name = nameOr(decision, "id");
        var table = child(decision, "decisionTable");
        var where = "Decision '" + name + "'";

        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute("aggregation"), where);

        var lists = new HashSet<String>();
        var inputs = children(table, "input").stream().map(input -> inputColumn(input, types, model, lists, where)).toList();
        var outputElements = children(table, "output");
        if (outputElements.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the decision table has no output");
        }
        // a single unnamed output is the decision's result, named after the decision, and typed by it
        var single = outputElements.size() == 1;
        var unnamedOutput = single ? name : "";
        var ruleElements = children(table, "rule");
        var resultType = single ? resultType(decision, table, hitPolicy, aggregation, types, where) : ColumnType.STRING;
        var outputs = new ArrayList<OutputColumn>();
        for (int o = 0; o < outputElements.size(); o++) {
            var output = outputElements.get(o);
            var untypedAs = model == null ? resultType : model.untypedOutputType(decision, output, o, ruleElements, types, where);
            outputs.add(outputColumn(output, unnamedOutput, untypedAs, types, where));
        }

        var rules = new ArrayList<Praeceptum>();
        var columns = new Columns(inputs, outputs, lists);
        var names = new ArrayList<NamedInput>();
        for (int i = 0; i < ruleElements.size(); i++) {
            rules.add(rule(ruleElements.get(i), where + ", rule " + (i + 1), i + 1, columns, names));
        }
        // cells stay strict FEEL, where a name compares with another input
        var named = namedInputs(names, where);
        if (!named.isEmpty()) {
            lists.addAll(namedListInputs(decision.getOwnerDocument().getDocumentElement(), named, types, where));
        }
        var checked = checkComputedOutputs(rules, inputs, named, lists, outputs, where);
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, checked, true, lists);
    }

    /**
     * The types of a model's input data and decisions, as declared, by name, for the decision tables of the model whose
     * input expressions and outputs declare no type.
     */
    private static final class ModelTypes {
        private final Map<String, FeelType> types = new HashMap<>();

        /**
         * The type of a column that declares none, from what it reads: the input data or decision of its name, or of its
         * longest part before a dot, at the path the rest names, when that is a simple type or a list of one.
         */
        Optional<Type> columnType(String column) {
            var element = DecisionModel.elementOf(column, types::containsKey);
            if (element == null) {
                return Optional.empty();
            }
            var type = types.get(element);
            if (element.length() < column.length()) {
                for (var component : column.substring(element.length() + 1).split("\\.")) {
                    type = type == null ? null : type.component(component);
                }
            }
            return switch (type) {
                case FeelType.Simple(var simple) -> Optional.of(new Type(simple, null, false));
                case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(new Type(simple, null, true));
                case null, default -> Optional.empty();
            };
        }

        /**
         * The type of an output that declares none ({@link #declaredOutputType}), else that of its literal entries
         * ({@link DmnReader#literalType}), else a string.
         *
         * @param index the output's position among the table's outputs
         * @param rules the table's rules
         */
        ColumnType untypedOutputType(Element decision, Element output, int index, List<Element> rules, ItemDefinitions items, String where) {
            return declaredOutputType(decision, output, items, where)
                    .or(() -> literalType(rules, index, output))
                    .orElse(ColumnType.STRING);
        }

        /**
         * The type of an output that declares none, as the decision's variable declares it: for a single output, the
         * variable's type, or the type of its elements (whatever the hit policy: the table's value is bound to the
         * variable, a single result to a list as a list of one); for several, the type of the variable's component of the
         * output's name. Empty where it declares none Praecepta can hold, and for COUNT, whose outputs say nothing of its
         * value.
         */
        private Optional<ColumnType> declaredOutputType(Element decision, Element output, ItemDefinitions items, String where) {
            var table = child(decision, "decisionTable");
            boolean single = children(table, "output").size() == 1;
            if (!output.getAttribute(TYPE_REF).isBlank() || single && "COUNT".equalsIgnoreCase(table.getAttribute("aggregation").strip())) {
                return Optional.empty();
            }
            var typeRef = table.getAttribute(TYPE_REF);
            FeelType declared;
            try {
                declared = typeRef.isBlank() ? declaredTableType(decision, items, where) : items.feelType(typeRef, where);
            } catch (InvalidDecisionTableException e) {
                return Optional.empty();
            }
            var type = single ? declared : declared.component(nameOr(output, "label"));
            return switch (type) {
                case FeelType.Simple(var simple) -> Optional.of(simple);
                case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(simple);
                case null, default -> Optional.empty();
            };
        }
    }

    /**
     * The type of an output from its entries, for a table of a model whose output declares no type: the type of its
     * literal output entries and default, when all of them that are not {@code null} are literals of one type (a quoted
     * string, or an unquoted number, boolean, date, time or date and time, as FEEL reads {@code 10} as the number 10);
     * empty when one names an input or computes a value, or they differ.
     *
     * @param index the output's position among the table's outputs
     */
    private static Optional<ColumnType> literalType(List<Element> rules, int index, Element output) {
        var texts = new ArrayList<String>();
        for (var rule : rules) {
            var entries = children(rule, "outputEntry");
            if (index >= entries.size()) {
                return Optional.empty(); // reported when the rule is read
            }
            texts.add(textOf(entries.get(index)));
        }
        var defaultEntry = nonBlankText(output, "defaultOutputEntry");
        if (defaultEntry != null) {
            texts.add(defaultEntry);
        }
        var found = new HashSet<ColumnType>();
        for (var text : texts) {
            var literal = text.strip();
            if (literal.isEmpty() || literal.equals("null") || isNull(literal, ColumnType.STRING)) {
                continue;
            }
            var candidates = literal.startsWith("\"") ? List.of(ColumnType.STRING) : UNQUOTED_OUTPUT_TYPES;
            var type = candidates.stream().filter(candidate -> literalValue(literal, candidate).isPresent()).findFirst();
            if (type.isEmpty()) {
                return Optional.empty();
            }
            found.add(type.get());
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
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
        for (var inputData : descendants(root, "inputData")) {
            var variable = child(inputData, "variable");
            var inputName = inputData.getAttribute("name").strip();
            var typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
            if (!named.containsKey(inputName) || typeRef.isBlank()) {
                continue;
            }
            var type = typeIfSupported(types, typeRef, where + ", input '" + inputName + "'");
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

    /** A type, or none if Praecepta cannot hold it, in which case no input value can be bound to it either. */
    private static Optional<Type> typeIfSupported(ItemDefinitions types, String typeRef, String where) {
        try {
            return Optional.of(types.resolve(typeRef, where));
        } catch (InvalidDecisionTableException e) {
            return Optional.empty();
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
    private static InputColumn inputColumn(Element input, ItemDefinitions types, ModelTypes model, Set<String> lists, String where) {
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
        var name = column;
        var type = typeRef.isBlank() && model != null
                ? model.columnType(column).orElseGet(() -> types.resolve("", where + ", input '" + name + "'"))
                : types.resolve(typeRef, where + ", input '" + column + "'");
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

    /**
     * @param untypedAs the type of an output whose {@code typeRef} is blank: a single output's
     *                  {@link #resultType}, else a string
     */
    private static OutputColumn outputColumn(Element output, String unnamed, ColumnType untypedAs, ItemDefinitions types, String where) {
        var column = nameOr(output, "label");
        if (column.isBlank()) {
            column = unnamed;
        }
        if (column.isBlank()) {
            throw new InvalidDecisionTableException(where + ": every output of a multi-output table needs a name");
        }
        var outputWhere = outputWhere(where, column);
        var typeRef = output.getAttribute(TYPE_REF);
        ColumnType type = untypedAs;
        if (!typeRef.isBlank()) {
            var resolved = types.resolve(typeRef, outputWhere);
            if (resolved.list()) {
                throw new InvalidDecisionTableException("%s: type '%s' is a collection, which is not supported for outputs"
                        .formatted(outputWhere, typeRef));
            }
            type = resolved.columnType();
        }
        var valueList = nonBlankText(output, "outputValues");
        List<String> values = List.of();
        if (valueList != null) {
            values = parseCell(valueList, type, outputWhere + " value list").values()
                    .orElseThrow(() -> new InvalidDecisionTableException(outputWhere + ": the output value list must be a list of values"))
                    .stream().map(FeelLiterals::text).toList();
        }
        var defaultValue = defaultValue(output, type, values, outputWhere);
        return defaultValue == null
                ? new OutputColumn(column, type, values)
                : new OutputColumn(column, type, values, defaultValue.text(), true);
    }

    /**
     * The type of a single output whose {@code OutputClause} names none, which DMN 1.5 requires of a single output
     * (Table 34: "The OutputClause of a single output decision table SHALL NOT specify a typeRef"): the type of the
     * decision's result, named by the decision table's {@code typeRef}, else by the decision's {@code variable}.
     * That is the output's type, except that a multi-hit table without aggregation results in a list of outputs,
     * whose item type is then the output's, and that COUNT results in the number of outputs, which says nothing of
     * them. Where nothing names a type the output can take (none is given, it is not one Praecepta can hold, or it
     * is a list for a single result or not a list for a list of results), the output is untyped: a string.
     */
    private static ColumnType resultType(Element decision, Element table, HitPolicy hitPolicy, AggregationOperator aggregation,
            ItemDefinitions types, String where) {
        if (aggregation == AggregationOperator.COUNT) {
            return ColumnType.STRING;
        }
        var typeRef = table.getAttribute(TYPE_REF);
        if (typeRef.isBlank()) {
            var variable = child(decision, "variable");
            typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
        }
        if (typeRef.isBlank()) {
            return ColumnType.STRING;
        }
        boolean listOfOutputs = !hitPolicy.isSingleHit() && aggregation == null;
        return typeIfSupported(types, typeRef, where + ", output")
                .filter(type -> type.list() == listOfOutputs)
                .map(Type::columnType)
                .orElse(ColumnType.STRING);
    }

    /**
     * An output's default value ({@code defaultOutputEntry}), the value when no rule matches, as plain text like
     * a literal output entry, whose text is null for a default of {@code null}; null if the output has none (no
     * {@code defaultOutputEntry}, or a blank one). A {@code null} default is a default: in a table with several
     * outputs, the default output is then a context with that output null (DMN 1.5, 10.3.2.10 and Table 46), where
     * a table whose outputs have no default gives null. It must be a literal of the output's type: a default that
     * names an input or computes a value is rejected, since the engine gives the default without evaluating
     * anything. When the output has a value list, a default other than {@code null} must be one of its values (DMN
     * 1.5, Table 46: "The default output value is one of the output values").
     *
     * @param values the output's value list, as plain text; empty if it has none
     */
    private static LiteralOutput defaultValue(Element output, ColumnType type, List<String> values, String where) {
        var text = nonBlankText(output, "defaultOutputEntry");
        if (text == null) {
            return null;
        }
        var literal = literalOutput(text, type);
        if (literal == null) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not supported; a default output must be a literal %s"
                    .formatted(where, text.strip(), type.feelName()));
        }
        if (literal.text() != null && !values.isEmpty() && values.stream().noneMatch(value -> sameValue(value, literal.text(), type))) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not in its output values %s"
                    .formatted(where, text.strip(), values));
        }
        return literal;
    }

    /** Whether two values of an output, as plain text, are the same: numbers by value ({@code 1.50} is {@code 1.5}). */
    private static boolean sameValue(String a, String b, ColumnType type) {
        if (type == ColumnType.NUMBER) {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        }
        return a.equals(b);
    }

    /**
     * The table's columns, as its rules are read.
     *
     * @param lists the inputs that hold lists
     */
    private record Columns(List<InputColumn> inputs, List<OutputColumn> outputs, Set<String> lists) {

        /** Whether an input is one of the table's columns. */
        boolean isInput(String name) {
            return inputs.stream().anyMatch(input -> input.name().equals(name));
        }
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
        var outputEntries = children(rule, "outputEntry");
        if (inputEntries.size() != columns.inputs().size() || outputEntries.size() != columns.outputs().size()) {
            throw new InvalidDecisionTableException("%s: has %d input and %d output entries; the table has %d inputs and %d outputs"
                    .formatted(where, inputEntries.size(), outputEntries.size(), columns.inputs().size(), columns.outputs().size()));
        }
        var input = inputEntries(inputEntries, where, columns, names);
        return new Praeceptum(number, input, outputEntries(outputEntries, where, columns));
    }

    /** A rule's input entries, kept as written once checked; adds the inputs they compare with that are not columns to {@code names}. */
    private static PraeceptaInput inputEntries(List<Element> entries, String where, Columns columns, List<NamedInput> names) {
        Map<String, String> cells = new HashMap<>();
        for (int c = 0; c < entries.size(); c++) {
            var column = columns.inputs().get(c);
            var text = textOf(entries.get(c)).strip();
            var cellWhere = where + ", column '" + column.name() + "'";
            if (columns.lists().contains(column.name())) {
                parseListCell(text, column.type(), cellWhere); // a list column's cells name no input
            } else {
                var cell = parseCell(text, column.type(), cellWhere);
                if (cell.namesInputs()) {
                    UnaryTests.names(cell).stream()
                            .filter(name -> !columns.isInput(name))
                            .forEach(name -> names.add(new NamedInput(name, column.type())));
                }
            }
            cells.put(column.name(), text);
        }
        return new PraeceptaInput(cells);
    }

    /** A rule's output entries, as plain text: literal values, names of inputs, and expressions computing a value. */
    private static PraeceptaOutput outputEntries(List<Element> entries, String where, Columns columns) {
        Map<String, String> values = new HashMap<>();
        Map<String, String> references = new HashMap<>();
        Map<String, String> expressions = new HashMap<>();
        for (int c = 0; c < entries.size(); c++) {
            var column = columns.outputs().get(c);
            var text = textOf(entries.get(c)).strip();
            var outputWhere = outputWhere(where, column.name());
            var input = namedInput(text, columns.inputs(), column, outputWhere);
            var literal = input == null ? literalOutput(text, column.type()) : null;
            if (input != null) {
                checkReference(input, columns.lists(), outputWhere);
                references.put(column.name(), input.name());
            } else if (literal != null) {
                values.put(column.name(), literal.text());
            } else {
                // checked once the inputs the cells compare with are known (see checkComputedOutputs)
                parseExpression(text, outputWhere);
                expressions.put(column.name(), text);
            }
        }
        return new PraeceptaOutput(values, references, expressions);
    }

    /** An output, as errors name it. */
    private static String outputWhere(String where, String column) {
        return where + ", output '" + column + "'";
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
                var outputWhere = outputWhere(where + ", rule " + rule.priority(), column);
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
        if (literal.isEmpty() || literal.equals("null") || isNull(literal, type)) {
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

    /** Whether an entry is FEEL's {@code null} with comments around it, as {@code null // no fee}. */
    private static boolean isNull(String literal, ColumnType type) {
        try {
            return CellParser.parseFeel(literal, type) instanceof UnaryTests.Positive(var tests) && tests.equals(List.of(new UnaryTests.NullTest()));
        } catch (CellSyntaxException e) {
            return false;
        }
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

    /**
     * The model's {@code definitions} element, parsed with DTDs and external entities disabled. The parser is
     * the JDK's own ({@code newDefaultInstance}), which these settings are written for, never one JAXP would
     * look up on the class path or through the {@code javax.xml.parsers.DocumentBuilderFactory} property.
     */
    private static Element parseModel(InputStream dmn) {
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
                    checkCollection(name, list, allowed != null || allowedValues != null, where);
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

        /**
         * Checks a collection in a chain of item definitions: it is the only one, and neither it nor a
         * definition derived from it declares allowed values, which would constrain the collection as a whole.
         *
         * @param inList      whether an earlier link of the chain is a collection already
         * @param constrained whether it, or a definition derived from it, declares allowed values
         */
        private static void checkCollection(String name, boolean inList, boolean constrained, String where) {
            if (inList) {
                throw new InvalidDecisionTableException("%s: type '%s' is a collection of collections, which is not supported"
                        .formatted(where, name));
            }
            if (constrained) {
                throw new InvalidDecisionTableException(("%s: type '%s' constrains a collection as a whole, which is not supported; "
                        + "declare the allowed values of its items in their item definition").formatted(where, name));
            }
        }

        /**
         * The type of an input data or a decision ({@link FeelType}): a built-in type, {@code Any}, or an item definition,
         * which may be a collection, have components, or both, nested up to {@value DmnReader#MAX_TYPE_DEPTH} levels; any type if
         * {@code typeRef} is blank. An item definition's allowed values are not part of the type.
         */
        FeelType feelType(String typeRef, String where) {
            return typeRef.isBlank() ? FeelType.ANY : typeOf(typeRef, where, 0, new HashSet<>());
        }

        /** @param enclosing the item definitions being read, which a type within them may not name */
        private FeelType typeOf(String typeRef, String where, int depth, Set<String> enclosing) {
            var builtIn = builtInType(typeRef);
            if (builtIn.isPresent()) {
                return FeelType.of(builtIn.get());
            }
            if (localName(typeRef).equals("Any")) {
                return FeelType.ANY;
            }
            var name = byName.containsKey(typeRef.strip()) ? typeRef.strip() : localName(typeRef);
            var definition = byName.get(name);
            if (definition == null) {
                throw new InvalidDecisionTableException("%s: type '%s' is not a built-in type or an item definition of the model"
                        .formatted(where, typeRef));
            }
            if (!enclosing.add(name)) {
                throw new InvalidDecisionTableException("%s: item definition '%s' is defined in terms of itself".formatted(where, name));
            }
            var type = definitionType(definition, name, where, depth + 1, enclosing);
            enclosing.remove(name);
            return type;
        }

        /** The type an item definition, or one of its components, defines. */
        private FeelType definitionType(Element definition, String name, String where, int depth, Set<String> enclosing) {
            if (depth > MAX_TYPE_DEPTH) {
                throw new InvalidDecisionTableException("%s: type '%s' nests more than %d levels of item definitions and components"
                        .formatted(where, name, MAX_TYPE_DEPTH));
            }
            if (child(definition, "functionItem") != null) {
                throw BOXED.exception(("%s: type '%s' is a function type, which Praecepta does not support yet: "
                        + "business knowledge models and functions are planned for 0.10 (#118)").formatted(where, name));
            }
            var components = children(definition, "itemComponent");
            FeelType type;
            if (!components.isEmpty()) {
                var types = new LinkedHashMap<String, FeelType>();
                for (var component : components) {
                    var componentName = component.getAttribute("name");
                    types.put(componentName, definitionType(component, name + "." + componentName, where, depth + 1, enclosing));
                }
                type = new FeelType.Context(types);
            } else {
                var base = child(definition, TYPE_REF);
                if (base == null || base.getTextContent().isBlank()) {
                    throw new InvalidDecisionTableException(
                            "%s: type '%s' is not supported; its item definition has no base type".formatted(where, name));
                }
                type = typeOf(base.getTextContent(), where, depth, enclosing);
            }
            if (Boolean.parseBoolean(definition.getAttribute("isCollection").strip())) {
                if (type instanceof FeelType.ListOf) {
                    throw new InvalidDecisionTableException("%s: type '%s' is a collection of collections, which is not supported"
                            .formatted(where, name));
                }
                type = new FeelType.ListOf(type);
            }
            return type;
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
