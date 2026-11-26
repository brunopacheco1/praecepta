/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION_TABLE;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.INPUT_DATA;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.child;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nameOr;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.namespaceOf;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.parseModel;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader.ImportResolver;
import io.github.brunopacheco1.praecepta.engine.dmn.ExpressionReader.Site;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Reads a whole decision model: first the models it imports, each once, then its input data, its decisions and its
 * business knowledge models, with their requirements and their logic, and its decision services. It checks that the
 * logic is FEEL, with the names it may read in scope.
 */
final class ModelReader {

    private static final String KNOWLEDGE_MODEL = "businessKnowledgeModel";
    private static final String DECISION_SERVICE = "decisionService";

    private ModelReader() {
    }

    /**
     * The model of a {@code definitions} element, read with the models it imports.
     *
     * @param imports finds the models imported; null to reject a model that imports another
     */
    static DecisionModel read(Element root, ImportResolver imports) {
        return new Imports(imports).read(root).model();
    }

    /** A model read, and the names of its elements by id, which an importing model's hrefs name. */
    private record ReadModel(DecisionModel model, Map<String, String> names, ItemDefinitions types) {
    }

    /** The reading of a model and of the models it imports, each once, by namespace. */
    private static final class Imports {
        private final ImportResolver resolver;
        private final Map<String, ReadModel> read = new HashMap<>();
        private final Set<String> reading = new HashSet<>();

        Imports(ImportResolver resolver) {
            this.resolver = resolver;
        }

        /** A model: the models it imports, read first, then the model itself, which reads their elements and types. */
        ReadModel read(Element root) {
            var imports = children(root, "import");
            if (!imports.isEmpty() && resolver == null) {
                throw new UnsupportedFeatureException(("DMN model imports '%s', which Praecepta reads only with the models it imports "
                        + "(DmnReader.readModel(dmn, imports))").formatted(nameOr(imports.getFirst(), "namespace")));
            }
            var namespace = namespaceOf(root);
            var imported = new ArrayList<DecisionModel.Import>();
            var references = new References(namespace);
            var importedTypes = new LinkedHashMap<String, ItemDefinitions>();
            reading.add(namespace);
            try {
                for (var element : imports) {
                    var name = element.getAttribute("name").strip();
                    var importedNamespace = element.getAttribute("namespace").strip();
                    checkImportsDmn(element);
                    var model = model(importedNamespace, element.getAttribute("locationURI").strip());
                    imported.add(new DecisionModel.Import(name, model.model()));
                    references.imports.computeIfAbsent(importedNamespace, ns -> new ArrayList<>()).add(new ImportedNames(name, model.names()));
                    importedTypes.put(name, model.types());
                }
            } finally {
                reading.remove(namespace);
            }
            return readOne(root, references, importedTypes, imported);
        }

        /** Rejects an import of anything but a DMN model. */
        private static void checkImportsDmn(Element element) {
            var importType = element.getAttribute("importType").strip();
            if (!importType.contains("/DMN/") || !importType.endsWith("/MODEL/")) {
                throw new UnsupportedFeatureException(
                        "DMN model imports '%s' of type '%s', which Praecepta does not read: it imports DMN models"
                                .formatted(nameOr(element, "namespace"), importType));
            }
        }

        /** The imported model of that namespace, read once; one that is still being read imports itself, which is rejected. */
        private ReadModel model(String namespace, String location) {
            var known = read.get(namespace);
            if (known != null) {
                return known;
            }
            if (reading.contains(namespace)) {
                throw new InvalidDecisionTableException("DMN model '%s' imports itself, directly or through the models it imports"
                        .formatted(namespace));
            }
            Element root;
            try (var dmn = resolver.open(namespace, location)) {
                if (dmn == null) {
                    throw new InvalidDecisionTableException("DMN model imports the model '%s'%s, which cannot be found".formatted(namespace,
                            location.isEmpty() ? "" : " at '" + location + "'"));
                }
                root = parseModel(dmn);
            } catch (IOException e) {
                throw new InvalidDecisionTableException("DMN model imports the model '%s', which cannot be read: %s".formatted(namespace,
                        e.getMessage()));
            }
            var found = namespaceOf(root);
            if (!found.equals(namespace)) {
                throw new InvalidDecisionTableException("DMN model imports the model '%s', but what was found for it is the model '%s'"
                        .formatted(namespace, found));
            }
            var model = read(root);
            read.put(namespace, model);
            return model;
        }
    }

    /** The names an import gives the elements of an imported model: by id, then qualified by the import's name. */
    private record ImportedNames(String name, Map<String, String> byId) {
    }

    /**
     * What a model's hrefs refer to: its own elements, by id, and those of the models it imports, by namespace and id
     * ({@code namespace#id}), qualified by the import's name.
     */
    private static final class References {
        private final String namespace;
        private final Map<String, Element> elements = new HashMap<>();
        private final Map<String, List<ImportedNames>> imports = new HashMap<>();

        References(String namespace) {
            this.namespace = namespace;
        }
    }

    /** One model, its imports read already. */
    private static ReadModel readOne(Element root, References references, Map<String, ItemDefinitions> importedTypes,
            List<DecisionModel.Import> imported) {
        var types = new ItemDefinitions(root);
        types.importTypes(importedTypes);
        for (var kind : List.of(INPUT_DATA, DECISION, KNOWLEDGE_MODEL, DECISION_SERVICE)) {
            children(root, kind).forEach(element -> references.elements.putIfAbsent(element.getAttribute("id"), element));
        }
        var model = new ModelTypes();
        var inputData = inputData(root, types, model);
        for (var decision : children(root, DECISION)) {
            var name = nameOr(decision, "id");
            model.declare(name, types.declaredTableType(decision, TableReader.decisionWhere(name)));
        }
        var decisions = new ArrayList<DecisionModel.Decision>();
        for (var decision : children(root, DECISION)) {
            decisions.add(decision(decision, types, model, references));
        }
        var knowledgeModels = new ArrayList<DecisionModel.BusinessKnowledgeModel>();
        for (var knowledgeModel : children(root, KNOWLEDGE_MODEL)) {
            knowledgeModels.add(knowledgeModel(knowledgeModel, types, model, references));
        }
        var logic = checkedLogic(decisions, knowledgeModels);
        try {
            var services = new ArrayList<DecisionModel.DecisionService>();
            for (var service : children(root, DECISION_SERVICE)) {
                services.add(decisionService(service, types, references));
            }
            var names = new HashMap<String, String>();
            references.elements.forEach((id, element) -> names.put(id, nameOr(element, "id")));
            var decisionModel = new DecisionModel(root.getAttribute("name"), inputData, decisions, knowledgeModels, services,
                    namedTypes(types, logic), references.namespace, imported);
            return new ReadModel(decisionModel, names, types);
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("DMN model: " + e.getMessage());
        }
    }

    /** The model's input data, each of the type its variable declares, which is declared to {@code model}. */
    private static List<DecisionModel.InputData> inputData(Element root, ItemDefinitions types, ModelTypes model) {
        var inputData = new ArrayList<DecisionModel.InputData>();
        for (var input : children(root, INPUT_DATA)) {
            var name = elementName(input, "an input data");
            var type = types.feelType(variableTypeRef(input), "Input data '" + name + "'");
            inputData.add(new DecisionModel.InputData(name, type));
            model.declare(name, type);
        }
        return inputData;
    }

    /** A decision of a model: its information requirements and its logic. */
    private static DecisionModel.Decision decision(Element decision, ItemDefinitions types, ModelTypes model, References elements) {
        var name = elementName(decision, "a decision");
        var where = TableReader.decisionWhere(name);
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

    /**
     * A business knowledge model: its knowledge requirements, and its logic, a function definition in FEEL
     * ({@code encapsulatedLogic}) of the function type its variable declares.
     */
    private static DecisionModel.BusinessKnowledgeModel knowledgeModel(Element knowledgeModel, ItemDefinitions types, ModelTypes model,
            References elements) {
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
        var site = new Site(types, model, Map.of(), name);
        var type = ExpressionReader.typeOf(variableTypeRef(knowledgeModel), logic, types, where);
        var function = (DecisionLogic.FunctionDefinition) ExpressionReader.functionDefinition(logic, site, type, where);
        return new DecisionModel.BusinessKnowledgeModel(name, required, function);
    }

    /**
     * A decision service (DMN 1.5, 6.3.9): its output, encapsulated and input decisions and its input data, each by its
     * {@code href} to an element of the model (one to no element kept as the id it names, which the graph reports), and
     * the type its variable declares.
     */
    private static DecisionModel.DecisionService decisionService(Element service, ItemDefinitions types, References elements) {
        var name = elementName(service, "a decision service");
        var where = "Decision service '" + name + "'";
        var lists = new ArrayList<List<String>>();
        for (var kind : List.of("outputDecision", "encapsulatedDecision", "inputDecision", INPUT_DATA)) {
            var names = new ArrayList<String>();
            children(service, kind).forEach(reference -> names.add(required(reference, elements, where)));
            lists.add(names);
        }
        return new DecisionModel.DecisionService(name, lists.get(0), lists.get(1), lists.get(2), lists.get(3),
                types.feelType(variableTypeRef(service), where));
    }

    /** The business knowledge models an element requires through its knowledge requirements (DMN 1.5, 6.3.14). */
    private static List<String> knowledgeRequirements(Element element, References elements, String where) {
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
     * The name of the element a requirement refers to: {@code href="#id"} (or {@code namespace#id} with the model's own
     * namespace, the DMN TCK's {@code 0091-local-hrefs}) for an element of the model, or the {@code id} it refers to if the
     * model has no element with that id; {@code namespace#id} with an imported model's namespace for that model's element,
     * by the name the import gives it ({@code importName.elementName}).
     */
    private static String required(Element requirement, References elements, String where) {
        var href = requirement.getAttribute("href").strip();
        int hash = href.indexOf('#');
        var namespace = hash < 0 ? href : href.substring(0, hash);
        var id = hash < 0 ? "" : href.substring(hash + 1);
        if (namespace.isEmpty() || namespace.equals(elements.namespace)) {
            var element = elements.elements.get(id);
            return element == null ? id : nameOr(element, "id");
        }
        var imported = elements.imports.get(namespace);
        if (imported == null) {
            throw new InvalidDecisionTableException("%s requires '%s', an element of a model it does not import (DMN 1.5, 6.3.3)"
                    .formatted(where, href));
        }
        var names = imported.getFirst();
        var name = names.byId().get(id);
        return names.name().isEmpty() || name == null ? (name == null ? id : name) : names.name() + "." + name;
    }

    /** The name of an input data or a decision of a model, or its id if it has none; one with neither is rejected. */
    private static String elementName(Element element, String kind) {
        var name = nameOr(element, "id");
        if (name.isBlank()) {
            throw new InvalidDecisionTableException("DMN model has %s with neither a name nor an id".formatted(kind));
        }
        return name;
    }

    /** A decision's logic: its decision table, its literal expression, or its boxed expression. */
    private static DecisionLogic logic(Element decision, ItemDefinitions types, ModelTypes model, String where) {
        for (var node = decision.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            var element = (Element) node;
            var kind = element.getLocalName();
            if (kind.equals(DECISION_TABLE)) {
                return new DecisionLogic.Table(TableReader.decisionTable(decision, types, model), types.declaredTableType(decision, where));
            }
            if (kind.equals("literalExpression")) {
                return ExpressionReader.literalExpression(decision, element, types, where);
            }
            if (ExpressionReader.isBoxed(kind)) {
                // the value is bound to the decision's variable (DMN 1.5, 10.3.2.9.4), whose typeRef 7.3.1 requires the
                // expression's to equal; the expression's own is read only where the variable has none
                var site = new Site(types, model, Map.of(), nameOr(decision, "id"));
                var type = ExpressionReader.typeOf(variableTypeRef(decision), element, types, where);
                return ExpressionReader.boxed(element, site, type, where);
            }
        }
        // a decision a person makes, whose value is given (DMN 1.5, 6.3.7; the DMN TCK's 0087 and 0088)
        return new DecisionLogic.NoLogic(types.declaredTableType(decision, where));
    }

    /** The logic of the decisions, then of the business knowledge models, each checked ({@link #checkExpressions}). */
    private static List<DecisionLogic> checkedLogic(List<DecisionModel.Decision> decisions,
            List<DecisionModel.BusinessKnowledgeModel> knowledgeModels) {
        var logic = new ArrayList<DecisionLogic>();
        for (var decision : decisions) {
            checkExpressions(decision.logic(), decision.requirements(), TableReader.decisionWhere(decision.name()));
            logic.add(decision.logic());
        }
        for (var knowledgeModel : knowledgeModels) {
            var names = new ArrayList<>(knowledgeModel.requiredKnowledge());
            names.add(knowledgeModel.name());
            checkExpressions(knowledgeModel.logic(), names, "Business knowledge model '" + knowledgeModel.name() + "'");
            logic.add(knowledgeModel.logic());
        }
        return logic;
    }

    /**
     * Checks that a decision's or a business knowledge model's logic, but a decision table, which is checked as it is
     * read, is FEEL, with the names it may read in scope ({@link FeelExpression#compose}): its requirements, or a business
     * knowledge model's knowledge requirements and its own name, and those its constructs bind. A name such as
     * {@code decision A 2.1} reads as one only where it is in scope (the DMN TCK's {@code 0034-drg-scopes}). Type names
     * the model does not define are left to the graph, which reports them.
     *
     * @throws InvalidDecisionTableException if it is not FEEL, or uses what Praecepta does not evaluate (an external
     *                                       function)
     */
    private static void checkExpressions(DecisionLogic logic, Collection<String> names, String where) {
        if (logic instanceof DecisionLogic.Table || logic instanceof DecisionLogic.NoLogic) {
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
            throw new UnsupportedFeatureException("%s: its %s uses %s".formatted(where,
                    logic instanceof DecisionLogic.LiteralExpression ? "literal expression" : "logic",
                    FeelExpression.Unsupported.describe(unsupported)));
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

    /**
     * The model's item definitions that its FEEL may name (in {@code instance of}, or a function's parameters): those whose
     * name one of its texts holds as a name, not within a longer one ({@code tFlags} in {@code x instance of tFlags}, not
     * in {@code tFlagsCount}), a literal expression or a decision table's entry or input expression.
     */
    private static Map<String, FeelType> namedTypes(ItemDefinitions types, List<DecisionLogic> logic) {
        var texts = logic.stream().flatMap(ModelReader::texts).toList();
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
        if (name.isEmpty()) {
            return false; // an item definition without a name, which no text can name (and indexOf would find for ever)
        }
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
            case DecisionLogic.Relation(var columns, var rows, var type) -> rows.stream().flatMap(List::stream).flatMap(ModelReader::texts);
            case DecisionLogic.BoxedList(var elements, var type) -> elements.stream().flatMap(ModelReader::texts);
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var type) ->
                    Stream.of(condition, then, otherwise).flatMap(ModelReader::texts);
            case DecisionLogic.Iteration(var kind, var variable, var domain, var body, var type) ->
                    Stream.of(domain, body).flatMap(ModelReader::texts);
            case DecisionLogic.Filter(var list, var match, var type) -> Stream.of(list, match).flatMap(ModelReader::texts);
            case DecisionLogic.NoLogic none -> Stream.empty();
        };
    }
}
