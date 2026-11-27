/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeSet;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelFunction;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelRange;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;

/**
 * Evaluates a result node through its model's decision requirements graph, as Praecepta does: {@link DmnReader#readModel}
 * reads the model, with the models it imports, and a {@link DecisionGraph} of it evaluates the decision from the test
 * case's input data, computing the decisions it requires. A decision whose value the test case gives is input data
 * instead, as the kit means it: the graph is built with that decision replaced by an input data of its type. A test
 * case that invokes a decision service or a business knowledge model, and a result node named after a decision service,
 * is that service or model invoked with the test case's values as its arguments.
 *
 * <p>A node is {@link Evaluation.Unsupported} only where the reader rejects its model for what Praecepta does not
 * evaluate by design ({@link UnsupportedFeatureException}: an external function), with the reader's message. Any other
 * model it does not read, graph it does not build or decision it does not evaluate is an {@link Evaluation.Failure}.
 *
 * <p>A hit policy violation and an input outside its value list are an {@link Evaluation.Error}; so is a null value
 * FEEL reported an error for, where the kit expects one ({@link #evaluation}).
 */
final class GraphEvaluator {

    /** What reading a model gives: the model and its graph, or what its nodes are instead, where the reader rejects it. */
    private record Read(DecisionModel model, DecisionGraph graph, Evaluation rejected) {
    }

    /** Models read, by file. */
    private final Map<Path, Read> models = new HashMap<>();
    /** Graphs built, by file and the decisions whose values the test case gives. */
    private final Map<String, DecisionGraph> graphs = new HashMap<>();

    /** Evaluates one result node. */
    Evaluation evaluate(Check check) {
        var read = read(check.model());
        if (read.rejected() != null) {
            return read.rejected();
        }
        var name = check.decision();
        return switch (check.kind()) {
            case DECISION_SERVICE -> service(check, read.graph(), check.invocable(), name);
            case KNOWLEDGE_MODEL -> knowledgeModel(check, read.graph(), check.invocable());
            case DECISION -> {
                if (read.model().decision(name).isPresent()) {
                    yield decision(check, read);
                }
                // a result node named after a decision service, without the test case's type, is the service's value
                yield read.graph().decisionServices().contains(name) ? service(check, read.graph(), name, null)
                        : new Evaluation.Failure("the model has no decision or decision service named '" + name + "'");
            }
        };
    }

    /** A decision evaluated from the test case's input data, and from the decisions whose values it gives. */
    private Evaluation decision(Check check, Read read) {
        DecisionGraph graph;
        try {
            graph = graph(check, read);
        } catch (InvalidDecisionTableException | IllegalArgumentException e) {
            return new Evaluation.Failure("the decision requirements graph cannot be built: " + e.getMessage());
        }
        var decision = check.decision();
        if (!graph.canEvaluate(decision)) {
            return new Evaluation.Failure("the decision requirements graph reports the decision and does not evaluate it: "
                    + graph.validationReport());
        }
        DecisionGraph.Outcome outcome;
        try {
            outcome = graph.evaluateWithErrors(decision, inputs(check, graph));
        } catch (HitPolicyViolationException | InvalidInputException e) {
            return new Evaluation.Error(e.getMessage());
        }
        var logic = graph.model().decision(decision).orElseThrow().logic();
        boolean ordered = !(logic instanceof DecisionLogic.Table(var table, var type) && table.hitPolicy() == HitPolicy.COLLECT);
        return evaluation(check, outcome, ordered);
    }

    /**
     * A decision service invoked with the test case's inputs as its arguments (DMN 1.5, 6.3.9): the value of the output
     * decision the node names, or, for {@code output} null, the service's value, its one output decision's or a context
     * of them all.
     */
    private Evaluation service(Check check, DecisionGraph graph, String service, String output) {
        var model = graph.model().decisionService(service).orElse(null);
        if (model == null) {
            return new Evaluation.Failure("the model has no decision service named '" + service + "'");
        }
        var arguments = new HashMap<String, Object>();
        for (var parameter : model.parameters()) {
            var given = check.inputs().get(parameter);
            if (given != null) {
                // FEEL values, typed as the kit types them, which the service binds to its parameters' types as an invocation does
                arguments.put(parameter, input(given, FeelType.ANY));
            }
        }
        DecisionGraph.Outcome outcome;
        try {
            outcome = graph.evaluateService(service, arguments);
        } catch (InvalidDecisionTableException e) {
            return new Evaluation.Failure("the decision service is not evaluated: " + e.getMessage());
        } catch (HitPolicyViolationException | InvalidInputException e) {
            return new Evaluation.Error(e.getMessage());
        }
        var outputs = (Map<?, ?>) outcome.value();
        Object value;
        if (output != null) {
            value = outputs.get(output);
        } else {
            value = outputs.size() == 1 ? outputs.values().iterator().next() : outputs;
        }
        var errors = new ArrayList<String>();
        outcome.errors().values().forEach(errors::addAll);
        return evaluation(check, new DecisionGraph.Outcome(value, errors.isEmpty() ? Map.of() : Map.of(check.decision(), errors)), true);
    }

    /** A business knowledge model invoked with the test case's inputs as its arguments, by parameter name (DMN 1.5, 6.3.10). */
    private Evaluation knowledgeModel(Check check, DecisionGraph graph, String knowledgeModel) {
        if (!graph.knowledgeModels().contains(knowledgeModel)) {
            return new Evaluation.Failure("the model has no business knowledge model named '" + knowledgeModel + "'");
        }
        var arguments = new HashMap<String, Object>();
        check.inputs().forEach((name, given) -> arguments.put(name, input(given, FeelType.ANY)));
        DecisionGraph.Outcome outcome;
        try {
            outcome = graph.invokeKnowledgeModel(knowledgeModel, arguments);
        } catch (InvalidDecisionTableException e) {
            return new Evaluation.Failure("the business knowledge model is not invoked: " + e.getMessage());
        }
        var errors = new ArrayList<String>();
        outcome.errors().values().forEach(errors::addAll);
        return evaluation(check, new DecisionGraph.Outcome(outcome.value(), errors.isEmpty() ? Map.of() : Map.of(check.decision(), errors)), true);
    }

    /**
     * What a graph's outcome is for the kit: the error FEEL reported, where the decision's value is null, its own logic
     * reported one and the kit expects an error (DMN 1.5, 10.3.2.15: the value is null, and the error is reported);
     * otherwise the value.
     */
    private static Evaluation evaluation(Check check, DecisionGraph.Outcome outcome, boolean ordered) {
        var errors = outcome.errorsOf(check.decision());
        if (outcome.value() == null && check.expectsError() && !errors.isEmpty()) {
            return new Evaluation.Error(String.join("; ", errors));
        }
        return new Evaluation.Value(value(outcome.value()), ordered);
    }

    /** The model of a file, read once with the models it imports, and its graph. */
    private Read read(Path file) {
        return models.computeIfAbsent(file, model -> {
            try (InputStream dmn = Files.newInputStream(model)) {
                var decisionModel = DmnReader.readModel(dmn, imports(model.getParent()));
                return new Read(decisionModel, build(decisionModel), null);
            } catch (UnsupportedFeatureException e) {
                // what Praecepta rejects by design; the reader's message names it
                return new Read(null, null, new Evaluation.Unsupported(e.getMessage()));
            } catch (InvalidDecisionTableException e) {
                return new Read(null, null, new Evaluation.Failure("Praecepta does not read the model: " + e.getMessage()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The graph a node is evaluated with: the model's, with the decisions the test case gives made input data. */
    private DecisionGraph graph(Check check, Read read) {
        var given = new TreeSet<String>();
        check.inputs().keySet().stream()
                .filter(name -> !name.equals(check.decision()) && read.model().decision(name).isPresent())
                .forEach(given::add);
        if (given.isEmpty()) {
            return read.graph();
        }
        return graphs.computeIfAbsent(check.model() + "#" + given, key -> build(given(read, given)));
    }

    private static DecisionGraph build(DecisionModel model) {
        return DecisionGraph.builder().model(model).logValidationReport(false).build();
    }

    /**
     * The model with the decisions the test case gives as input data of their types, which the decisions that require
     * them require instead.
     */
    private static DecisionModel given(Read read, SequencedSet<String> given) {
        var inputData = new ArrayList<>(read.model().inputData());
        given.forEach(name -> inputData.add(new DecisionModel.InputData(name, read.graph().type(name))));
        var decisions = new ArrayList<DecisionModel.Decision>();
        for (var decision : read.model().decisions()) {
            if (given.contains(decision.name())) {
                continue;
            }
            var requiredDecisions = decision.requiredDecisions().stream().filter(name -> !given.contains(name)).toList();
            var requiredInputs = new ArrayList<>(decision.requiredInputs());
            decision.requiredDecisions().stream().filter(given::contains).forEach(requiredInputs::add);
            decisions.add(new DecisionModel.Decision(decision.name(), requiredDecisions, requiredInputs, decision.requiredKnowledge(),
                    decision.logic()));
        }
        return new DecisionModel(read.model().name(), inputData, decisions, read.model().knowledgeModels(), read.model().decisionServices(),
                read.model().types());
    }

    /**
     * The test case's inputs as the graph takes them: values of simple types as text, typed by the kit where undeclared;
     * an input of an imported model under its name in the graph.
     */
    private static Map<String, Object> inputs(Check check, DecisionGraph graph) {
        var given = new HashMap<String, TckValue>();
        check.inputs().forEach((key, value) -> {
            if (key.startsWith("{") && key.indexOf('}') > 0) {
                given.put(graph.nameOf(key.substring(1, key.indexOf('}')), key.substring(key.indexOf('}') + 1)), value);
            } else {
                given.put(key, value);
            }
        });
        var values = new HashMap<String, Object>();
        graph.inputData().forEach((name, type) -> {
            var value = given.get(name);
            if (value != null) {
                values.put(name, input(value, type));
            }
        });
        return values;
    }

    /**
     * Finds the models a model imports among the DMN files of its test case's directory: by its {@code locationURI}, else
     * by namespace.
     */
    private static DmnReader.ImportResolver imports(Path directory) {
        return (namespace, location) -> {
            if (!location.isEmpty()) {
                var file = directory.resolve(location).normalize();
                if (Files.isRegularFile(file)) {
                    return Files.newInputStream(file);
                }
            }
            try (var files = Files.list(directory)) {
                for (var file : files.filter(f -> f.toString().endsWith(".dmn")).sorted().toList()) {
                    if (namespace.equals(TckXml.parse(file).getAttribute("namespace").strip())) {
                        return Files.newInputStream(file);
                    }
                }
            }
            return null;
        };
    }

    /**
     * A test case's value as the graph takes it for an input of this type: a value of a simple type as text, one of no
     * declared type typed by its {@code xsi:type}, a structure as a map of its components, a list as a list.
     */
    private static Object input(TckValue given, FeelType type) {
        return switch (given) {
            case TckValue.Nil nil -> null;
            case TckValue.Simple simple when type instanceof FeelType.Any -> typed(simple);
            case TckValue.Simple simple -> simple.text();
            case TckValue.Items items -> {
                var element = type instanceof FeelType.ListOf(var of) ? of : FeelType.ANY;
                var list = new ArrayList<>();
                items.values().forEach(item -> list.add(input(item, element)));
                yield list;
            }
            case TckValue.Components components -> {
                var declared = type instanceof FeelType.Context(var of) ? of : Map.<String, FeelType>of();
                var map = new LinkedHashMap<String, Object>();
                components.values().forEach((name, value) -> map.put(name, input(value, declared.getOrDefault(name, FeelType.ANY))));
                yield map;
            }
        };
    }

    /** A simple value of no declared type, typed by its {@code xsi:type}; its text if that names no type Praecepta has. */
    private static Object typed(TckValue.Simple simple) {
        var type = columnType(simple.type());
        if (type == null) {
            return simple.text();
        }
        try {
            return FeelLiterals.value(simple.text(), type);
        } catch (IllegalArgumentException e) {
            return simple.text();
        }
    }

    /**
     * The type an XML Schema type names, or null: for no type, and for {@code duration}, which does not say which of
     * FEEL's two durations it is.
     */
    private static ColumnType columnType(String xsiType) {
        return switch (xsiType.toLowerCase(Locale.ROOT)) {
            case "string" -> ColumnType.STRING;
            case "number", "decimal", "integer", "int", "long", "double", "float" -> ColumnType.NUMBER;
            case "boolean" -> ColumnType.BOOLEAN;
            case "date" -> ColumnType.DATE;
            case "time" -> ColumnType.TIME;
            case "datetime" -> ColumnType.DATE_TIME;
            case "daytimeduration" -> ColumnType.DAY_TIME_DURATION;
            case "yearmonthduration" -> ColumnType.YEAR_MONTH_DURATION;
            default -> null;
        };
    }

    /** A graph's value as the kit compares it. */
    private static TckValue value(Object value) {
        return switch (value) {
            case null -> TckValue.NIL;
            case Map<?, ?> context -> {
                var components = new LinkedHashMap<String, TckValue>();
                context.forEach((name, component) -> components.put(String.valueOf(name), value(component)));
                yield new TckValue.Components(components);
            }
            case List<?> list -> new TckValue.Items(list.stream().map(GraphEvaluator::value).toList());
            case String text -> new TckValue.Simple("string", text);
            case Boolean bool -> new TckValue.Simple("boolean", bool.toString());
            case BigDecimal number -> new TckValue.Simple("decimal", number.toPlainString());
            case LocalDate date -> new TckValue.Simple("date", date.toString());
            case LocalTime time -> new TckValue.Simple("time", FeelLiterals.text(time));
            case OffsetTime time -> new TckValue.Simple("time", FeelLiterals.text(time));
            case ZonedTime time -> new TckValue.Simple("time", FeelLiterals.text(time));
            case LocalDateTime dateTime -> new TckValue.Simple("dateTime", FeelLiterals.text(dateTime));
            case ZonedDateTime dateTime -> new TckValue.Simple("dateTime", FeelLiterals.text(dateTime));
            case Duration duration -> new TckValue.Simple("duration", FeelLiterals.text(duration));
            case YearsAndMonths duration -> new TckValue.Simple("duration", FeelLiterals.text(duration));
            case FeelRange range -> new TckValue.Simple("", range.toString());
            case FeelFunction function -> new TckValue.Simple("", function.toString());
            default -> new TckValue.Simple("", FeelLiterals.text(value));
        };
    }
}
