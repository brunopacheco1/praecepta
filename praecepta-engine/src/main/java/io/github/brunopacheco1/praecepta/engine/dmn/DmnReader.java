/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

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
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

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
 * {@link UnsupportedFeatureException}, as they are planned for 0.11 (#119); anything else Praecepta does not read with an
 * {@link InvalidDecisionTableException} naming the decision, rule and column. Rules are numbered
 * from 1 in table order.
 *
 * <p>{@link #readModel} reads a whole decision model instead, with its input data, its decisions (decision tables, literal
 * expressions and boxed expressions), its business knowledge models and their requirements, for {@code DecisionGraph}.
 */
public final class DmnReader {

    private static final String TYPE_REF = "typeRef";
    private static final String INPUT_DATA = "inputData";
    private static final String DECISION = "decision";
    private static final String KNOWLEDGE_MODEL = "businessKnowledgeModel";
    private static final String DECISION_SERVICE = "decisionService";
    private static final String DECISION_TABLE = "decisionTable";
    private static final String VARIABLE = "variable";
    private static final String LABEL = "label";
    private static final String OUTPUT_ENTRY = "outputEntry";
    private static final String DEFAULT_OUTPUT_ENTRY = "defaultOutputEntry";
    private static final String AGGREGATION = "aggregation";
    private static final String COUNT = "COUNT";
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
     * requirement names a decision, an input data or a business knowledge model of the model by its {@code href}
     * ({@code #id}); one that refers to
     * no element of the model is kept as the {@code id} it refers to, which {@code DecisionGraph} reports as a missing
     * requirement. Knowledge sources and authority requirements, which do not take part in evaluation, text annotations,
     * groups and diagrams are ignored, and so are a decision service that nothing in the model invokes and a decision
     * without logic that no decision requires, which change no value Praecepta evaluates. The model's item definitions are
     * its {@linkplain DecisionModel#types() types}, by name, which a literal expression's {@code instance of} may name; a
     * function item among them is a function type, and one with neither a base type nor components is any type.
     *
     * <p>What Praecepta does not evaluate yet is rejected, naming the element and the release that plans it: invoking a
     * decision service, a decision without logic that another decision requires, imports and external functions (in
     * FEEL, or a boxed function definition in Java or PMML), planned for 0.11 (#119).
     *
     * @throws InvalidDecisionTableException if the model cannot be read (a literal expression that is not FEEL among
     *                                       other things), uses what Praecepta does not support yet, or names two of its
     *                                       decisions, input data and business knowledge models alike
     */
    public static DecisionModel readModel(InputStream dmn) {
        var root = parseModel(dmn);
        rejectUnsupported(root);
        var types = new ItemDefinitions(root);
        var elements = new HashMap<String, Element>();
        for (var kind : List.of(INPUT_DATA, DECISION, KNOWLEDGE_MODEL, DECISION_SERVICE)) {
            children(root, kind).forEach(element -> elements.putIfAbsent(element.getAttribute("id"), element));
        }
        var inputData = new ArrayList<DecisionModel.InputData>();
        var model = new ModelTypes();
        for (var input : children(root, INPUT_DATA)) {
            var name = elementName(input, "an input data");
            var variable = child(input, VARIABLE);
            var typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
            var type = types.feelType(typeRef, "Input data '" + name + "'");
            inputData.add(new DecisionModel.InputData(name, type));
            model.types.putIfAbsent(name, type);
        }
        for (var decision : children(root, DECISION)) {
            var name = nameOr(decision, "id");
            model.types.putIfAbsent(name, declaredTableType(decision, types, decisionWhere(name)));
        }
        var decisions = new ArrayList<DecisionModel.Decision>();
        var required = requiredDecisionIds(root);
        for (var decision : children(root, DECISION)) {
            if (expressionIn(decision) == null) {
                if (required.contains(decision.getAttribute("id"))) {
                    throw IMPORTS.exception(("%s has no decision logic, and another decision requires it, which Praecepta does not "
                            + "support yet: decisions without decision logic are planned for 0.11 (#119)").formatted(
                            decisionWhere(elementName(decision, "a decision"))));
                }
                // a decision without logic, such as a human decision, that no decision requires: it changes no value Praecepta
                // evaluates, and is left out (the DMN TCK's 0087-chapter-11-example)
                continue;
            }
            decisions.add(decision(decision, types, model, elements));
        }
        var knowledgeModels = new ArrayList<DecisionModel.BusinessKnowledgeModel>();
        for (var knowledgeModel : children(root, KNOWLEDGE_MODEL)) {
            knowledgeModels.add(knowledgeModel(knowledgeModel, types, model, elements));
        }
        var logic = new ArrayList<DecisionLogic>();
        for (var decision : decisions) {
            checkExpressions(decision.logic(), decision.requirements(), decisionWhere(decision.name()));
            logic.add(decision.logic());
        }
        for (var knowledgeModel : knowledgeModels) {
            var names = new ArrayList<>(knowledgeModel.requiredKnowledge());
            names.add(knowledgeModel.name());
            checkExpressions(knowledgeModel.logic(), names, "Business knowledge model '" + knowledgeModel.name() + "'");
            logic.add(knowledgeModel.logic());
        }
        try {
            var services = new ArrayList<DecisionModel.DecisionService>();
            for (var service : children(root, DECISION_SERVICE)) {
                services.add(decisionService(service, types, elements));
            }
            return new DecisionModel(root.getAttribute("name"), inputData, decisions, knowledgeModels, services, namedTypes(types, logic));
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("DMN model: " + e.getMessage());
        }
    }

    /**
     * The model's item definitions that its FEEL may name (in {@code instance of}, or a function's parameters): those whose
     * name one of its texts holds as a name, not within a longer one ({@code tFlags} in {@code x instance of tFlags}, not
     * in {@code tFlagsCount}), a literal expression or a decision table's entry or input expression.
     */
    private static Map<String, FeelType> namedTypes(ItemDefinitions types, List<DecisionLogic> logic) {
        var texts = logic.stream().flatMap(DmnReader::texts).toList();
        var named = new LinkedHashMap<String, FeelType>();
        if (!texts.isEmpty()) {
            types.all().forEach((name, type) -> {
                if (texts.stream().anyMatch(text -> holdsName(text, name))) {
                    named.put(name, type);
                }
            });
        }
        return named;
    }

    /** Whether a text holds a name, neither preceded nor followed by a letter, a digit or an underscore. */
    private static boolean holdsName(String text, String name) {
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
            int end = at + name.length();
            if ((at == 0 || !isNamePart(text.charAt(at - 1))) && (end == text.length() || !isNamePart(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The FEEL texts of a decision's logic: a literal expression, a table's input expressions, input and output entries,
     * and those of every part of a boxed expression.
     */
    private static Stream<String> texts(DecisionLogic logic) {
        return switch (logic) {
            case DecisionLogic.LiteralExpression literal -> Stream.of(literal.text());
            case DecisionLogic.Table(var table, var type) -> Stream.concat(
                    table.inputs().stream().map(InputColumn::name),
                    table.rules().stream().flatMap(rule -> Stream.concat(rule.input().values().values().stream(),
                            rule.output().expressions().values().stream())).filter(Objects::nonNull));
            case DecisionLogic.Context(var entries, var result, var type) -> Stream.concat(
                    entries.stream().flatMap(entry -> texts(entry.value())), result == null ? Stream.empty() : texts(result));
            case DecisionLogic.Invocation(var function, var bindings, var type) -> Stream.concat(Stream.of(function),
                    bindings.stream().filter(binding -> binding.value() != null).flatMap(binding -> texts(binding.value())));
            case DecisionLogic.FunctionDefinition(var parameters, var body, var type) -> texts(body);
            case DecisionLogic.Relation(var columns, var rows, var type) -> rows.stream().flatMap(List::stream).flatMap(DmnReader::texts);
            case DecisionLogic.BoxedList(var elements, var type) -> elements.stream().flatMap(DmnReader::texts);
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var type) ->
                    Stream.of(condition, then, otherwise).flatMap(DmnReader::texts);
            case DecisionLogic.Iteration(var kind, var variable, var domain, var body, var type) ->
                    Stream.of(domain, body).flatMap(DmnReader::texts);
            case DecisionLogic.Filter(var list, var match, var type) -> Stream.of(list, match).flatMap(DmnReader::texts);
        };
    }

    /** Rejects the elements of a model that Praecepta does not evaluate yet, naming the release that plans them. */
    private static void rejectUnsupported(Element root) {
        rejectAny(root, "import", "namespace", "DMN model imports '%s', which Praecepta does not support yet: imports are planned for 0.11 (#119)",
                IMPORTS);
    }

    /** The ids of the decisions that a decision of the model requires, by {@code href="#id"}. */
    private static Set<String> requiredDecisionIds(Element root) {
        var ids = new HashSet<String>();
        for (var decision : children(root, DECISION)) {
            for (var requirement : children(decision, "informationRequirement")) {
                var required = child(requirement, "requiredDecision");
                var href = required == null ? "" : required.getAttribute("href").strip();
                if (href.startsWith("#")) {
                    ids.add(href.substring(1));
                }
            }
        }
        return ids;
    }

    /**
     * A decision service (DMN 1.5, 6.3.9): its output, encapsulated and input decisions and its input data, each by its
     * {@code href} to an element of the model (one to no element kept as the id it names, which the graph reports), and
     * the type its variable declares.
     */
    private static DecisionModel.DecisionService decisionService(Element service, ItemDefinitions types, Map<String, Element> elements) {
        var name = elementName(service, "a decision service");
        var where = "Decision service '" + name + "'";
        var lists = new ArrayList<List<String>>();
        for (var kind : List.of("outputDecision", "encapsulatedDecision", "inputDecision", "inputData")) {
            var names = new ArrayList<String>();
            children(service, kind).forEach(reference -> names.add(required(reference, elements, where)));
            lists.add(names);
        }
        return new DecisionModel.DecisionService(name, lists.get(0), lists.get(1), lists.get(2), lists.get(3),
                types.feelType(variableTypeRef(service), where));
    }

    /** The releases that plan what Praecepta does not evaluate yet, as {@link UnsupportedFeatureException} names them. */
    private record Planned(String release, int issue) {

        UnsupportedFeatureException exception(String message) {
            return new UnsupportedFeatureException(message, release, issue);
        }
    }

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
        var where = decisionWhere(name);
        var requiredKnowledge = knowledgeRequirements(decision, elements, where);
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
        return new DecisionModel.Decision(name, requiredDecisions, requiredInputs, requiredKnowledge, logic(decision, types, model, where));
    }

    /** The business knowledge models an element requires through its knowledge requirements (DMN 1.5, 6.3.14). */
    private static List<String> knowledgeRequirements(Element element, Map<String, Element> elements, String where) {
        var required = new ArrayList<String>();
        for (var requirement : children(element, "knowledgeRequirement")) {
            var knowledge = child(requirement, "requiredKnowledge");
            if (knowledge == null) {
                throw new InvalidDecisionTableException(where + ": a knowledge requirement must name the required knowledge (DMN 1.5, 6.3.14)");
            }
            required.add(required(knowledge, elements, where));
        }
        return required;
    }

    /**
     * A business knowledge model: its knowledge requirements, and its logic, a function definition in FEEL
     * ({@code encapsulatedLogic}) of the function type its variable declares.
     */
    private static DecisionModel.BusinessKnowledgeModel knowledgeModel(Element knowledgeModel, ItemDefinitions types, ModelTypes model,
            Map<String, Element> elements) {
        var name = elementName(knowledgeModel, "a business knowledge model");
        var where = "Business knowledge model '" + name + "'";
        var required = knowledgeRequirements(knowledgeModel, elements, where);
        if (child(knowledgeModel, "informationRequirement") != null) {
            throw new InvalidDecisionTableException(where + ": a business knowledge model has no information requirements; it reads its "
                    + "parameters only (DMN 1.5, 6.3.10)");
        }
        var logic = child(knowledgeModel, "encapsulatedLogic");
        if (logic == null) {
            throw new InvalidDecisionTableException(where + " has no encapsulated logic");
        }
        var function = (DecisionLogic.FunctionDefinition) functionDefinition(logic, new Site(types, model, Map.of(), name),
                typeOf(variableTypeRef(knowledgeModel), logic, types, where), where);
        return new DecisionModel.BusinessKnowledgeModel(name, required, function);
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

    /** The kinds of boxed expression (DMN 1.5, 10.2) beside decision tables and literal expressions, as messages name them. */
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

    /** A decision's logic: its decision table, its literal expression, or its boxed expression. */
    private static DecisionLogic logic(Element decision, ItemDefinitions types, ModelTypes model, String where) {
        for (var node = decision.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            var element = (Element) node;
            var kind = element.getLocalName();
            if (kind.equals(DECISION_TABLE)) {
                return new DecisionLogic.Table(decisionTable(decision, types, model), declaredTableType(decision, types, where));
            }
            if (kind.equals("literalExpression")) {
                return literalExpression(decision, element, types, where);
            }
            if (BOXED_EXPRESSIONS.containsKey(kind)) {
                // the value is bound to the decision's variable (DMN 1.5, 10.3.2.9.4), whose typeRef 7.3.1 requires the
                // expression's to equal; the expression's own is read only where the variable has none
                return boxed(element, new Site(types, model, Map.of(), nameOr(decision, "id")), typeOf(variableTypeRef(decision), element,
                        types, where), where);
            }
        }
        throw new InvalidDecisionTableException(where + " has no decision logic; Praecepta evaluates decisions whose logic is a decision "
                + "table, a literal expression or a boxed expression");
    }

    /**
     * Where a part of a boxed expression is read: the model's item definitions and the types of its elements, the names
     * its enclosing constructs bind, with their declared types (a context's entries, a function's parameters, an
     * iterator's variable), which a decision table within reads and types its columns by, and the name such a table takes.
     */
    private record Site(ItemDefinitions types, ModelTypes model, Map<String, FeelType> locals, String name) {

        /** The site within a construct that binds {@code local}, of {@code type}. */
        Site with(String local, FeelType type) {
            var inner = new LinkedHashMap<>(locals);
            inner.put(local, type);
            return new Site(types, model, inner, name);
        }

        /** The site where a table takes {@code tableName}. */
        Site named(String tableName) {
            return new Site(types, model, locals, tableName);
        }
    }

    /** Whether an element is an expression (DMN 1.5, 10.2) that a boxed expression may hold. */
    private static boolean isExpression(Element element) {
        var kind = element.getLocalName();
        return kind.equals("literalExpression") || kind.equals(DECISION_TABLE) || BOXED_EXPRESSIONS.containsKey(kind);
    }

    /** The expression an element holds as its only expression child ({@code <if>}, {@code <in>}), or null for none. */
    private static Element expressionIn(Element parent) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && isExpression((Element) node)) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * The type a part of a boxed expression declares: {@code typeRef}, the one its variable declares (a context entry's,
     * a decision's), else the expression's own; any type if neither does.
     */
    private static FeelType typeOf(String typeRef, Element expression, ItemDefinitions types, String where) {
        var ref = typeRef.isBlank() && expression != null ? expression.getAttribute(TYPE_REF) : typeRef;
        return types.feelType(ref, where);
    }

    /** The expression a child element of a boxed expression holds ({@code <if>}, {@code <in>}...), which it must have. */
    private static DecisionLogic part(Element parent, String child, Site site, String where) {
        var element = child(parent, child);
        var expression = element == null ? null : expressionIn(element);
        if (expression == null) {
            throw new InvalidDecisionTableException("%s: its <%s> holds no expression".formatted(where, child));
        }
        return expression(expression, site, typeOf("", expression, site.types(), where), where);
    }

    /**
     * An expression within a boxed expression: a literal expression (an empty one is {@code null}), or a boxed
     * expression in turn, of the type {@code type}.
     */
    private static DecisionLogic expression(Element element, Site site, FeelType type, String where) {
        var kind = element.getLocalName();
        if (kind.equals("literalExpression")) {
            checkLiteral(element, where);
            var text = child(element, "text") == null ? "" : textOf(element).strip();
            return new DecisionLogic.LiteralExpression(text.isEmpty() ? "null" : text, type);
        }
        if (kind.equals(DECISION_TABLE)) {
            // the table is named after what holds it: a context entry, a business knowledge model, else its decision
            var holder = (Element) element.getParentNode();
            var table = decisionTable(holder, site.name(), where, site.types(), site.model().with(site.locals()), site.locals().keySet());
            return new DecisionLogic.Table(table, type);
        }
        return boxed(element, site, type, where);
    }

    /** A boxed expression other than a decision table or a literal expression (DMN 1.5, 10.2), of the type {@code type}. */
    private static DecisionLogic boxed(Element element, Site site, FeelType type, String where) {
        try {
            return switch (element.getLocalName()) {
                case "context" -> context(element, site, type, where);
                case "invocation" -> invocation(element, site, type, where);
                case "functionDefinition" -> functionDefinition(element, site, type, where);
                case "relation" -> relation(element, site, type, where);
                case "list" -> {
                    var elements = new ArrayList<DecisionLogic>();
                    int i = 0;
                    for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                        if (node.getNodeType() == Node.ELEMENT_NODE && isExpression((Element) node)) {
                            var item = (Element) node;
                            var at = "%s, element %d of its list".formatted(where, ++i);
                            elements.add(expression(item, site, typeOf("", item, site.types(), at), at));
                        }
                    }
                    yield new DecisionLogic.BoxedList(elements, type);
                }
                case "conditional" -> new DecisionLogic.Conditional(part(element, "if", site, where), part(element, "then", site, where),
                        part(element, "else", site, where), type);
                case "for" -> iteration(element, DecisionLogic.Iteration.Kind.FOR, "return", site, type, where);
                case "some" -> iteration(element, DecisionLogic.Iteration.Kind.SOME, "satisfies", site, type, where);
                case "every" -> iteration(element, DecisionLogic.Iteration.Kind.EVERY, "satisfies", site, type, where);
                case "filter" -> new DecisionLogic.Filter(part(element, "in", site, where),
                        part(element, "match", site.with("item", FeelType.ANY), where), type);
                default -> throw new InvalidDecisionTableException("%s: <%s> is not an expression".formatted(where, element.getLocalName()));
            };
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** A boxed context: its entries, each of its variable's type, and its final result, the entry without a variable. */
    private static DecisionLogic context(Element context, Site site, FeelType type, String where) {
        var entries = new ArrayList<DecisionLogic.ContextEntry>();
        DecisionLogic result = null;
        var all = children(context, "contextEntry");
        var scope = site;
        for (int i = 0; i < all.size(); i++) {
            var entry = all.get(i);
            var variable = child(entry, VARIABLE);
            var value = expressionIn(entry);
            if (variable == null) {
                if (i != all.size() - 1) {
                    throw new InvalidDecisionTableException(where + ": only a context's last entry may have no variable, as its result "
                            + "(DMN 1.5, 10.2.1)");
                }
                if (value == null) {
                    throw new InvalidDecisionTableException(where + ": the context's result holds no expression");
                }
                result = expression(value, scope.named(site.name()), typeOf("", value, site.types(), where), where + ", its result");
                continue;
            }
            var name = variable.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a context entry's variable has no name");
            }
            var at = "%s, context entry '%s'".formatted(where, name);
            var entryType = typeOf(variable.getAttribute(TYPE_REF), value, site.types(), at);
            // in the scope of itself, and of the entries before it, as a table within it reads them
            scope = scope.with(name, entryType).named(name);
            entries.add(new DecisionLogic.ContextEntry(name, value == null
                    ? new DecisionLogic.LiteralExpression("null", entryType) : expression(value, scope, entryType, at)));
        }
        return new DecisionLogic.Context(entries, result, type);
    }

    /** A boxed invocation: the function its first expression names, and its bindings. */
    private static DecisionLogic invocation(Element invocation, Site site, FeelType type, String where) {
        var function = expressionIn(invocation);
        if (function == null || !function.getLocalName().equals("literalExpression")) {
            throw new InvalidDecisionTableException(where + ": an invocation names the function it invokes with a literal expression");
        }
        checkLiteral(function, where);
        var name = child(function, "text") == null ? "" : textOf(function).strip();
        if (name.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the invocation names no function");
        }
        var bindings = new ArrayList<DecisionLogic.Binding>();
        for (var binding : children(invocation, "binding")) {
            var parameter = child(binding, "parameter");
            var parameterName = parameter == null ? "" : parameter.getAttribute("name").strip();
            if (parameterName.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a binding of the invocation names no parameter");
            }
            var at = "%s, binding of '%s'".formatted(where, parameterName);
            var value = expressionIn(binding);
            bindings.add(new DecisionLogic.Binding(parameterName, value == null ? null
                    : expression(value, site, typeOf(parameter.getAttribute(TYPE_REF), value, site.types(), at), at)));
        }
        return new DecisionLogic.Invocation(name, bindings, type);
    }

    /** A boxed function definition in FEEL: its formal parameters, of their declared types, and its body. */
    private static DecisionLogic functionDefinition(Element function, Site site, FeelType type, String where) {
        var kind = function.getAttribute("kind").strip();
        if (!kind.isEmpty() && !kind.equals("FEEL")) {
            throw IMPORTS.exception(("%s defines a function in %s, which Praecepta does not support yet: external functions are "
                    + "planned for 0.11 (#119)").formatted(where, kind));
        }
        var parameters = new ArrayList<DecisionLogic.Parameter>();
        for (var parameter : children(function, "formalParameter")) {
            var name = parameter.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a parameter of the function has no name");
            }
            var parameterType = site.types().feelType(parameter.getAttribute(TYPE_REF), where + ", parameter '" + name + "'");
            parameters.add(new DecisionLogic.Parameter(name, parameterType));
        }
        var body = expressionIn(function);
        if (body == null) {
            throw new InvalidDecisionTableException(where + ": the function definition has no body");
        }
        var at = where + ", the function's body";
        var inner = site;
        for (var parameter : parameters) {
            inner = inner.with(parameter.name(), parameter.type());
        }
        return new DecisionLogic.FunctionDefinition(parameters, expression(body, inner, typeOf("", body, site.types(), at), at), type);
    }

    /** A relation: its columns, each of its declared type, and its rows of expressions. */
    private static DecisionLogic relation(Element relation, Site site, FeelType type, String where) {
        var columns = new ArrayList<DecisionLogic.Parameter>();
        for (var column : children(relation, "column")) {
            var name = column.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a column of the relation has no name");
            }
            var columnType = site.types().feelType(column.getAttribute(TYPE_REF), where + ", column '" + name + "'");
            columns.add(new DecisionLogic.Parameter(name, columnType));
        }
        var rows = new ArrayList<List<DecisionLogic>>();
        for (var row : children(relation, "row")) {
            var cells = new ArrayList<DecisionLogic>();
            for (var node = row.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeType() == Node.ELEMENT_NODE && isExpression((Element) node)) {
                    var cell = (Element) node;
                    var at = "%s, row %d, column %d".formatted(where, rows.size() + 1, cells.size() + 1);
                    cells.add(expression(cell, site, typeOf("", cell, site.types(), at), at));
                }
            }
            rows.add(cells);
        }
        return new DecisionLogic.Relation(columns, rows, type);
    }

    /** A boxed iterator: its variable, the expression in {@code <in>} and the one in its {@code <return>} or {@code <satisfies>}. */
    private static DecisionLogic iteration(Element iterator, DecisionLogic.Iteration.Kind kind, String body, Site site,
            FeelType type, String where) {
        var variable = iterator.getAttribute("iteratorVariable").strip();
        if (variable.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the iterator has no iteratorVariable");
        }
        var inner = site.with(variable, FeelType.ANY);
        if (kind == DecisionLogic.Iteration.Kind.FOR) {
            inner = inner.with("partial", FeelType.ANY);
        }
        return new DecisionLogic.Iteration(kind, variable, part(iterator, "in", site, where), part(iterator, body, inner, where), type);
    }

    /** Rejects a literal expression in another language than FEEL, or one that imports values. */
    private static void checkLiteral(Element literal, String where) {
        var language = literal.getAttribute("expressionLanguage").strip();
        if (!language.isEmpty() && !language.toUpperCase(Locale.ROOT).contains("FEEL")) {
            throw new InvalidDecisionTableException("%s: its literal expression is in '%s'; Praecepta reads FEEL".formatted(where, language));
        }
        if (child(literal, "importedValues") != null) {
            throw IMPORTS.exception(("%s: its literal expression imports values, which Praecepta does not support yet: "
                    + "imports are planned for 0.11 (#119)").formatted(where));
        }
    }

    /** A literal expression, of the type its decision's variable (or the expression itself) declares. */
    private static DecisionLogic literalExpression(Element decision, Element literal, ItemDefinitions types, String where) {
        checkLiteral(literal, where);
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
        return new DecisionLogic.LiteralExpression(text, types.feelType(typeRef, where));
    }

    /**
     * Checks that a decision's or a business knowledge model's logic, but a decision table, which is checked as it is
     * read, is FEEL, with the names it may read in scope ({@link FeelExpression#compose}): its requirements, or a business
     * knowledge model's knowledge requirements and its own name, and those its constructs bind. A name such as
     * {@code decision A 2.1} reads as one only where it is in scope (the DMN TCK's {@code 0034-drg-scopes}). Type names
     * the model does not define are left to the graph, which reports them.
     *
     * @throws InvalidDecisionTableException if it is not FEEL, or uses what Praecepta does not support yet (an external
     *                                       function, 0.11)
     */
    private static void checkExpressions(DecisionLogic logic, Collection<String> names, String where) {
        if (logic instanceof DecisionLogic.Table) {
            return;
        }
        FeelExpression expression;
        try {
            expression = FeelExpression.compose(logic, FeelExpression.namesScope(names), site -> NO_TABLE);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException(where + (logic instanceof DecisionLogic.LiteralExpression ? ": literal expression " : ": ")
                    + e.getMessage());
        }
        var unsupported = expression.unsupported();
        if (!unsupported.isEmpty()) {
            var planned = new Planned(unsupported.getFirst().release(), unsupported.getFirst().issue());
            throw planned.exception("%s: its %s uses %s, which Praecepta does not support yet".formatted(where,
                    logic instanceof DecisionLogic.LiteralExpression ? "literal expression" : "logic",
                    String.join(" and ", unsupported.stream().map(Object::toString).toList())));
        }
    }

    /** A decision table within a boxed expression, as {@link #checkExpressions} sees it: checked as it was read. */
    private static final FeelExpression.Embedded NO_TABLE = new FeelExpression.Embedded() {
        @Override
        public List<String> names() {
            return List.of();
        }

        @Override
        public FeelType type() {
            return FeelType.ANY;
        }

        @Override
        public Object evaluate(Function<String, Object> values, Consumer<String> errors) {
            return null;
        }
    };

    /** The {@code typeRef} of a decision's variable, blank if it has none. */
    private static String variableTypeRef(Element decision) {
        var variable = child(decision, VARIABLE);
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
        return descendants(root, DECISION).stream().filter(decision -> child(decision, DECISION_TABLE) != null).toList();
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
        return decisionTable(decision, name, decisionWhere(name), types, model, Set.of());
    }

    /**
     * A decision table, the child of {@code decision}: a decision, or what holds a table within a boxed expression (a
     * context entry, whose variable types it as a decision's does, or a function definition).
     *
     * @param name   the table's name, its single unnamed output's
     * @param locals the names the enclosing constructs of a boxed expression bind, which its FEEL may read beside the
     *               model's input data and decisions
     */
    private static DecisionTable decisionTable(Element decision, String name, String where, ItemDefinitions types, ModelTypes model,
            Set<String> locals) {
        var table = child(decision, DECISION_TABLE);

        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute(AGGREGATION), where);

        var lists = new HashSet<String>();
        var inputs = children(table, "input").stream().map(input -> inputColumn(input, types, model, lists, where)).toList();
        var outputElements = children(table, "output");
        if (outputElements.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the decision table has no output");
        }
        // a table read alone has no model, whose item definitions its FEEL could name
        var scope = new TableScope(types, inputs, model != null, locals);
        // a single unnamed output is the decision's result, named after the decision, and typed by it
        var single = outputElements.size() == 1;
        var unnamedOutput = single ? name : "";
        var ruleElements = children(table, "rule");
        var resultType = single ? resultType(decision, table, hitPolicy, aggregation, types, where) : ColumnType.STRING;
        var outputs = new ArrayList<OutputColumn>();
        for (int o = 0; o < outputElements.size(); o++) {
            var output = outputElements.get(o);
            var untypedAs = model == null ? Optional.of(resultType)
                    : model.untypedOutputType(decision, output, o, new Entries(ruleElements, inputs, lists, scope), types, where);
            outputs.add(outputColumn(output, unnamedOutput, untypedAs, types, scope, where));
        }

        var rules = new ArrayList<Praeceptum>();
        var columns = new Columns(inputs, outputs, lists, scope);
        var names = new ArrayList<NamedInput>();
        for (int i = 0; i < ruleElements.size(); i++) {
            rules.add(rule(ruleElements.get(i), where + ", rule " + (i + 1), i + 1, columns, names));
        }
        // cells stay strict FEEL, where a name compares with another input
        var named = namedInputs(names, where);
        if (!named.isEmpty()) {
            lists.addAll(namedListInputs(decision.getOwnerDocument().getDocumentElement(), named, types, where));
        }
        var checked = checkComputedOutputs(rules, named, lists, outputs, where);
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, checked, true, lists);
    }

    /**
     * A table's rules, as written, with its inputs, those that hold lists, and the names in scope, for typing its output
     * entries.
     */
    private record Entries(List<Element> rules, List<InputColumn> inputs, Set<String> lists, TableScope scope) {

        /** The type of a name an entry reads: a column's, else an input data's or a decision's; null if unknown. */
        FeelType type(String name, Map<String, FeelType> model) {
            for (var input : inputs) {
                if (input.name().equals(name)) {
                    return lists.contains(name) ? FeelType.listOf(input.type()) : FeelType.of(input.type());
                }
            }
            return model.get(name);
        }
    }

    /**
     * The names in scope for a table's entries: those of the model's input data and decisions, and of the table's inputs
     * that are names, found and indexed when an entry first needs them, so a table whose entries are all literals and
     * simple tests reads no names.
     */
    private static final class TableScope {
        private final ItemDefinitions types;
        private final List<InputColumn> inputs;
        /** Whether the table is read as part of a model, whose item definitions its FEEL may name. */
        private final boolean model;
        /** The names a boxed expression around the table binds. */
        private final Set<String> locals;
        private FeelExpression.Scope scope;

        TableScope(ItemDefinitions types, List<InputColumn> inputs, boolean model, Set<String> locals) {
            this.types = types;
            this.inputs = inputs;
            this.model = model;
            this.locals = locals;
        }

        FeelExpression.Scope get() {
            if (scope == null) {
                var names = new LinkedHashSet<>(types.elementNames());
                names.addAll(locals);
                // a column that is an expression is no name: its text would read as itself
                inputs.stream().filter(input -> CellParser.isName(input.name())).forEach(input -> names.add(input.name()));
                scope = FeelExpression.scope(names, model ? types.all() : Map.of());
            }
            return scope;
        }
    }

    /** The names of a model's input data and decisions, which its expressions may read. */
    private static Set<String> elementNames(Element root) {
        var names = new LinkedHashSet<String>();
        for (var kind : List.of(INPUT_DATA, DECISION)) {
            descendants(root, kind).forEach(element -> {
                var name = element.getAttribute("name").strip();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    /**
     * The types of a model's input data and decisions, as declared, by name, for the decision tables of the model whose
     * input expressions and outputs declare no type.
     */
    private static final class ModelTypes {
        private final Map<String, FeelType> types = new HashMap<>();

        /**
         * The declared types of a file's input data and decisions, those Praecepta can hold: what a table read alone, of
         * a file that need be no model Praecepta reads, types its columns that compute their value with.
         */
        static ModelTypes of(Element root, ItemDefinitions items) {
            var model = new ModelTypes();
            for (var input : descendants(root, INPUT_DATA)) {
                var variable = child(input, VARIABLE);
                try {
                    model.types.putIfAbsent(nameOr(input, "id"), items.feelType(variable == null ? "" : variable.getAttribute(TYPE_REF), ""));
                } catch (InvalidDecisionTableException e) {
                    // a type Praecepta cannot hold: what reads it is of any type
                }
            }
            for (var decision : descendants(root, DECISION)) {
                model.types.putIfAbsent(nameOr(decision, "id"), declaredTableType(decision, items, ""));
            }
            return model;
        }

        /** These types, and those of the names a boxed expression binds, which hide the model's elements of their names. */
        ModelTypes with(Map<String, FeelType> locals) {
            if (locals.isEmpty()) {
                return this;
            }
            var inner = new ModelTypes();
            inner.types.putAll(types);
            inner.types.putAll(locals);
            return inner;
        }

        /**
         * The type of a column whose input expression is an expression, as FEEL types it from the types of the input data
         * and decisions it reads, when that is a simple type or a list of one.
         */
        Optional<Type> expressionType(FeelExpression expression) {
            return switch (expression.type(types::get)) {
                case FeelType.Simple(var simple) -> Optional.of(new Type(simple, null, false));
                case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(new Type(simple, null, true));
                default -> Optional.empty();
            };
        }

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
         * ({@link #literalType}), else the one type its entries give, as FEEL types them ({@link #entriesType}): empty
         * where they give several, or one Praecepta's columns do not hold (a list, a context), as each entry then gives a
         * value of its own type (DMN 1.5, 10.3.2.10, step 3c). A COUNT's output, which says nothing of its value, is
         * then a string.
         *
         * @param index the output's position among the table's outputs
         */
        Optional<ColumnType> untypedOutputType(Element decision, Element output, int index, Entries entries, ItemDefinitions items,
                String where) {
            var declared = declaredOutputType(decision, output, items, where).or(() -> literalType(entries.rules(), index, output));
            if (declared.isPresent()) {
                return declared;
            }
            var table = child(decision, DECISION_TABLE);
            if (COUNT.equalsIgnoreCase(table.getAttribute(AGGREGATION).strip())) {
                return Optional.of(ColumnType.STRING);
            }
            return entriesType(entries, index, output);
        }

        /**
         * The one simple type an output's entries and default give, each as FEEL types it from the types of the table's
         * inputs and of the model's input data and decisions ({@code a + 1} is a number where {@code a} is one); empty
         * where they give several, or another type, or one that only their values tell.
         */
        private Optional<ColumnType> entriesType(Entries entries, int index, Element output) {
            var texts = new ArrayList<String>();
            for (var rule : entries.rules()) {
                var outputEntries = children(rule, OUTPUT_ENTRY);
                if (index < outputEntries.size()) {
                    texts.add(textOf(outputEntries.get(index)));
                }
            }
            var defaultEntry = nonBlankText(output, DEFAULT_OUTPUT_ENTRY);
            if (defaultEntry != null) {
                texts.add(defaultEntry);
            }
            var found = new HashSet<FeelType>();
            for (var text : texts) {
                var type = entryType(text.strip(), entries);
                if (type != null) {
                    found.add(type);
                }
            }
            return found.size() == 1 && found.iterator().next() instanceof FeelType.Simple(var simple) ? Optional.of(simple) : Optional.empty();
        }

        /** An entry's type: a literal's, else its expression's; null for a {@code null} or blank entry, and one that is no FEEL. */
        private FeelType entryType(String text, Entries entries) {
            if (text.isEmpty() || text.equals("null") || isNull(text, ColumnType.STRING)) {
                return null;
            }
            var candidates = text.startsWith("\"") ? List.of(ColumnType.STRING) : UNQUOTED_OUTPUT_TYPES;
            var literal = candidates.stream().filter(candidate -> literalValue(text, candidate).isPresent()).findFirst();
            if (literal.isPresent()) {
                return FeelType.of(literal.get());
            }
            try {
                var type = FeelExpression.parse(text, entries.scope().get()).type(name -> entries.type(name, types));
                return type instanceof FeelType.Null ? null : type;
            } catch (ExpressionSyntaxException e) {
                return null; // reported when the rule is read
            }
        }

        /**
         * The type of an output that declares none, as the decision's variable declares it: for a single output, the
         * variable's type, or the type of its elements (whatever the hit policy: the table's value is bound to the
         * variable, a single result to a list as a list of one); for several, the type of the variable's component of the
         * output's name. Empty where it declares none Praecepta can hold, and for COUNT, whose outputs say nothing of its
         * value.
         */
        private Optional<ColumnType> declaredOutputType(Element decision, Element output, ItemDefinitions items, String where) {
            var table = child(decision, DECISION_TABLE);
            boolean single = children(table, "output").size() == 1;
            if (!output.getAttribute(TYPE_REF).isBlank() || single && COUNT.equalsIgnoreCase(table.getAttribute(AGGREGATION).strip())) {
                return Optional.empty();
            }
            var typeRef = table.getAttribute(TYPE_REF);
            FeelType declared;
            try {
                declared = typeRef.isBlank() ? declaredTableType(decision, items, where) : items.feelType(typeRef, where);
            } catch (InvalidDecisionTableException e) {
                return Optional.empty();
            }
            var type = single ? declared : declared.component(nameOr(output, LABEL));
            return switch (type) {
                case FeelType.Simple(var simple) -> Optional.of(simple);
                case FeelType.ListOf(FeelType.Simple(var simple)) -> Optional.of(simple);
                case null, default -> Optional.empty();
            };
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
                var entries = children(rule, OUTPUT_ENTRY);
                if (index >= entries.size()) {
                    return Optional.empty(); // reported when the rule is read
                }
                texts.add(textOf(entries.get(index)));
            }
            var defaultEntry = nonBlankText(output, DEFAULT_OUTPUT_ENTRY);
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
            var variable = child(inputData, VARIABLE);
            var inputName = inputData.getAttribute("name").strip();
            var typeRef = variable == null ? "" : variable.getAttribute(TYPE_REF);
            if (!named.containsKey(inputName) || typeRef.isBlank()) {
                continue;
            }
            var type = typeIfSupported(types, typeRef, inputWhere(where, inputName));
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
            case COUNT -> AggregationOperator.COUNT;
            default -> throw new InvalidDecisionTableException(
                    "%s: aggregation '%s' is not supported (SUM, MIN, MAX and COUNT are)".formatted(where, value));
        };
    }

    // ---- columns and rules -----------------------------------------------------------------------

    /**
     * A column; one whose type is a collection is added to {@code lists}, and its allowed values (those of
     * its item type) apply to the list's elements. Its input expression is its name: a name of an input variable, or
     * any FEEL expression ({@code Age * 12}), whose value the engine computes from the inputs it reads, typed, where it
     * declares no type, as FEEL types it in a model; the names of the model's input data and decisions are in scope for
     * it.
     */
    private static InputColumn inputColumn(Element input, ItemDefinitions types, ModelTypes model, Set<String> lists,
            String where) {
        var expression = child(input, "inputExpression");
        var column = expression == null ? "" : textOf(expression).strip();
        if (column.isEmpty()) {
            column = input.getAttribute(LABEL).strip();
        }
        var computed = CellParser.isName(column) ? null : inputExpression(column, types.expressionScope(), where);
        var typeRef = expression == null ? "" : expression.getAttribute(TYPE_REF);
        var name = column;
        var type = typeRef.isBlank()
                ? untypedColumnType(column, computed, model, types).orElseGet(() -> types.resolve("", inputWhere(where, name)))
                : types.resolve(typeRef, inputWhere(where, column));
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
     * The type of a column whose input expression declares none: in a model, that of what it reads, or of its expression
     * as FEEL types it; in a table read alone, which keeps 0.6's typing of the columns that name what they read, that of
     * an expression, typed from the file's input data and decisions as in a model; empty where nothing gives one.
     *
     * @param computed its input expression, if it is an expression rather than a name; else null
     */
    private static Optional<Type> untypedColumnType(String column, FeelExpression computed, ModelTypes model,
            ItemDefinitions types) {
        if (model != null) {
            return computed == null ? model.columnType(column) : model.expressionType(computed);
        }
        return computed == null ? Optional.empty()
                : types.modelTypes().expressionType(computed);
    }

    /**
     * An input expression that is not a name: a FEEL expression, checked.
     *
     * @throws UnsupportedFeatureException  if it defines an external function (0.11, #119)
     * @throws InvalidDecisionTableException if it is not FEEL
     */
    private static FeelExpression inputExpression(String text, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: input expression '%s' is neither a name nor a FEEL expression: %s"
                    .formatted(where, text, e.getMessage()));
        }
        rejectUnsupported(where + ", input expression '" + text + "'", expression.unsupported());
        return expression;
    }

    /** Rejects an expression that uses what Praecepta does not evaluate yet, naming the release that plans it. */
    private static void rejectUnsupported(String where, List<FeelExpression.Unsupported> unsupported) {
        if (!unsupported.isEmpty()) {
            var planned = new Planned(unsupported.getFirst().release(), unsupported.getFirst().issue());
            throw planned.exception("%s uses %s, which Praecepta does not support yet".formatted(where,
                    String.join(" and ", unsupported.stream().map(Object::toString).toList())));
        }
    }

    /**
     * @param untypedAs the type of an output whose {@code typeRef} is blank: a single output's {@link #resultType}, else a
     *                  string; for a model's table, the type the model gives it ({@link ModelTypes#untypedOutputType}),
     *                  empty for one whose entries each give a value of their own type, which is then not
     *                  {@linkplain OutputColumn#typed() typed}, unless it has an output value list
     */
    private static OutputColumn outputColumn(Element output, String unnamed, Optional<ColumnType> untypedAs, ItemDefinitions types,
            TableScope scope, String where) {
        var column = nameOr(output, LABEL);
        if (column.isBlank()) {
            column = unnamed;
        }
        if (column.isBlank()) {
            throw new InvalidDecisionTableException(where + ": every output of a multi-output table needs a name");
        }
        var outputWhere = outputWhere(where, column);
        var typeRef = output.getAttribute(TYPE_REF);
        ColumnType type = untypedAs.orElse(ColumnType.STRING);
        boolean typed = untypedAs.isPresent() || nonBlankText(output, "outputValues") != null;
        if (!typeRef.isBlank()) {
            typed = true;
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
        var defaultValue = defaultValue(output, type, values, scope, outputWhere);
        // an output with a value list or a default keeps a type, a string where nothing else gives one: both are text
        typed = typed || defaultValue != null;
        return defaultValue == null
                ? new OutputColumn(column, type, values, null, false, typed)
                : new OutputColumn(column, type, values, defaultValue.text(), true, typed);
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
            var variable = child(decision, VARIABLE);
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
     * a table whose outputs have no default gives null. It is a literal of the output's type, or a FEEL expression that
     * reads no input ({@code -1}, {@code "N/" + "A"}), whose value, the same whatever the inputs, is taken when the table
     * is read (DMN 1.5, 10.3.2.10, step 2: "DTI=FEEL(d)"), bound to the output's type as an output entry's is: a string
     * output takes any value as its text, and another one a value of its type, else null. A default that reads an input
     * is rejected: Table 46 has the default output value "one of the output values", which no input changes. When the
     * output has a value list, a default other than {@code null} must be one of its values.
     *
     * @param values the output's value list, as plain text; empty if it has none
     */
    private static LiteralOutput defaultValue(Element output, ColumnType type, List<String> values, TableScope scope,
            String where) {
        var text = nonBlankText(output, DEFAULT_OUTPUT_ENTRY);
        if (text == null) {
            return null;
        }
        var literal = literalOutput(text, type);
        if (literal == null && isSimple(text.strip(), scope.get())) {
            // a literal of another type, or a name: no value of the output's type
            throw new InvalidDecisionTableException("%s: default output '%s' is not supported; a default output must be a literal %s"
                    .formatted(where, text.strip(), type.feelName()));
        }
        if (literal == null) {
            literal = constantOutput(text.strip(), type, scope.get(), where);
        }
        var given = literal;
        if (given.text() != null && !values.isEmpty() && values.stream().noneMatch(value -> sameValue(value, given.text(), type))) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not in its output values %s"
                    .formatted(where, text.strip(), values));
        }
        return given;
    }

    /** Whether an entry is a single literal or a name, as simple unary tests hold them, rather than an expression. */
    private static boolean isSimple(String text, FeelExpression.Scope scope) {
        try {
            var tests = FeelUnaryTests.parse(text, scope);
            FeelExpression.parse(text, scope); // one test, not several
            return !tests.isAny() && tests.isSimple();
        } catch (ExpressionSyntaxException e) {
            return false;
        }
    }

    /**
     * A default output that is a FEEL expression reading no input, evaluated: its value bound to the output's type, as
     * plain text, null where it is null.
     *
     * @throws InvalidDecisionTableException if it is not FEEL, or reads an input
     */
    private static LiteralOutput constantOutput(String text, ColumnType type, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not FEEL: %s".formatted(where, text, e.getMessage()));
        }
        rejectUnsupported(where + ", default output '" + text + "'", expression.unsupported());
        if (!expression.names().isEmpty()) {
            throw new InvalidDecisionTableException(("%s: default output '%s' reads '%s'; a default output is a value, which no input "
                    + "changes (DMN 1.5, Table 46)").formatted(where, text, expression.names().getFirst()));
        }
        var value = expression.evaluate(name -> null);
        if (value == null) {
            return new LiteralOutput(null);
        }
        if (type == ColumnType.STRING) {
            return new LiteralOutput(value instanceof String string ? string : FeelLiterals.text(value));
        }
        var bound = bound(value, type);
        return new LiteralOutput(bound == null ? null : FeelLiterals.text(bound));
    }

    /**
     * A value bound to a simple type (DMN 1.5, 10.3.2.9.4): a list of one as its element, a date as a date and time at UTC
     * midnight for a date and time, a value of the type as it is, and null for any other.
     */
    private static Object bound(Object value, ColumnType type) {
        var single = value instanceof List<?> list && list.size() == 1 ? list.getFirst() : value;
        if (single instanceof LocalDate date && type == ColumnType.DATE_TIME) {
            return date.atStartOfDay(ZoneOffset.UTC);
        }
        boolean ofType = switch (type) {
            case STRING -> single instanceof String;
            case NUMBER -> single instanceof BigDecimal;
            case BOOLEAN -> single instanceof Boolean;
            case DATE -> single instanceof LocalDate;
            case TIME -> single instanceof LocalTime || single instanceof OffsetTime || single instanceof ZonedTime;
            case DATE_TIME -> single instanceof LocalDateTime || single instanceof ZonedDateTime;
            case DAY_TIME_DURATION -> single instanceof Duration;
            case YEAR_MONTH_DURATION -> single instanceof YearsAndMonths;
        };
        return ofType ? single : null;
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
    private record Columns(List<InputColumn> inputs, List<OutputColumn> outputs, Set<String> lists, TableScope scope) {

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
     * @throws UnsupportedFeatureException  if it defines an external function (0.11, #119)
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
            rejectUnsupported(where, tests.unsupported());
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
            var outputWhere = outputWhere(where, column.name());
            var input = namedInput(text, columns.inputs(), column, outputWhere);
            var literal = input == null ? literalOutput(text, column.type()) : null;
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

    /** A decision, as errors name it. */
    private static String decisionWhere(String name) {
        return "Decision '" + name + "'";
    }

    /** An input, as errors name it. */
    private static String inputWhere(String where, String column) {
        return where + ", input '" + column + "'";
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
                var outputWhere = outputWhere(where + ", rule " + rule.priority(), column);
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
     * @throws UnsupportedFeatureException  if it defines an external function (0.11, #119)
     * @throws InvalidDecisionTableException if it is not FEEL
     */
    private static void parseExpression(String text, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not a FEEL expression (%s)".formatted(where, text, e.getMessage()));
        }
        rejectUnsupported(where, expression.unsupported());
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
        private final Map<String, Element> byName = new LinkedHashMap<>();
        private final Element root;
        /** The names of the model's input data and decisions, found once for all its tables; null until asked for. */
        private Set<String> elementNames;
        private FeelExpression.Scope expressionScope;
        private ModelTypes modelTypes;
        /** Every type the item definitions define ({@link #all}), read once. */
        private Map<String, FeelType> all;

        ItemDefinitions(Element root) {
            this.root = root;
            children(root, "itemDefinition").forEach(definition -> byName.putIfAbsent(definition.getAttribute("name"), definition));
        }

        /** The names of the model's input data and decisions ({@link DmnReader#elementNames}), found once. */
        Set<String> elementNames() {
            if (elementNames == null) {
                elementNames = DmnReader.elementNames(root);
            }
            return elementNames;
        }

        /** Those names in scope, for an input expression, indexed once. */
        FeelExpression.Scope expressionScope() {
            if (expressionScope == null) {
                expressionScope = FeelExpression.scope(elementNames(), Map.of());
            }
            return expressionScope;
        }

        /** The declared types of the model's input data and decisions ({@link ModelTypes#of}), read once. */
        ModelTypes modelTypes() {
            if (modelTypes == null) {
                modelTypes = ModelTypes.of(root, this);
            }
            return modelTypes;
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

        /**
         * Every type the model's item definitions define, by name, for FEEL's {@code instance of} and function parameters
         * (DMN 1.5, 10.3.2.9): function types (a {@code functionItem}) among them; a definition Praecepta cannot read is
         * left out.
         */
        Map<String, FeelType> all() {
            if (all == null) {
                all = Collections.unmodifiableMap(allTypes());
            }
            return all;
        }

        private Map<String, FeelType> allTypes() {
            var read = new LinkedHashMap<String, FeelType>();
            for (var name : byName.keySet()) {
                try {
                    read.put(name, typeOf(name, "Item definition '" + name + "'", 0, new HashSet<>()));
                } catch (InvalidDecisionTableException e) {
                    // a type Praecepta cannot read: an expression that names it is reported
                }
            }
            return read;
        }

        /** A function item's type (DMN 1.5, 7.3.4): its parameters' types and its output's, {@code Any} where it names none. */
        private FeelType functionType(Element function, String where, int depth, Set<String> enclosing) {
            var parameters = new ArrayList<FeelType>();
            for (var parameter : children(function, "parameters")) {
                var typeRef = parameter.getAttribute(TYPE_REF);
                parameters.add(typeRef.isBlank() ? FeelType.ANY : typeOf(typeRef, where, depth + 1, enclosing));
            }
            var output = function.getAttribute("outputTypeRef");
            return new FeelType.FunctionOf(parameters, output.isBlank() ? FeelType.ANY : typeOf(output, where, depth + 1, enclosing));
        }

        /** The type an item definition, or one of its components, defines. */
        private FeelType definitionType(Element definition, String name, String where, int depth, Set<String> enclosing) {
            if (depth > MAX_TYPE_DEPTH) {
                throw new InvalidDecisionTableException("%s: type '%s' nests more than %d levels of item definitions and components"
                        .formatted(where, name, MAX_TYPE_DEPTH));
            }
            var function = child(definition, "functionItem");
            if (function != null) {
                return functionType(function, where, depth, enclosing);
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
                // an item definition, or a component, with neither a base type nor components may hold any value (the DMN
                // TCK's 0031-user-defined-functions and 1120-feel-duration-function)
                type = base == null || base.getTextContent().isBlank() ? FeelType.ANY : typeOf(base.getTextContent(), where, depth, enclosing);
            }
            // a collection of a collection is a list of lists (the DMN TCK's 0009-append-flatten)
            if (Boolean.parseBoolean(definition.getAttribute("isCollection").strip())) {
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
