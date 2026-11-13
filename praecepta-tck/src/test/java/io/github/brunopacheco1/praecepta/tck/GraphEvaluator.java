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
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeSet;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Invocable;

/**
 * Evaluates a decision through its model's decision requirements graph, as Praecepta does since 0.7 (#14): the model
 * is read with {@link DmnReader#readModel} and built into a {@link DecisionGraph}, which evaluates the decision from the
 * test case's input data and computes the decisions it requires. A decision whose value the test case gives is input
 * data instead, as the kit means it: the graph is built with that decision replaced by an input data of its type.
 *
 * <p>It takes a node only when everything the node needs is something Praecepta reads, and the model as a whole is one
 * {@link DmnReader#readModel} reads (a model with a business knowledge model, a decision service or an import, even one
 * the node does not use, is not). The nodes it does not take go to the next evaluator ({@link ModelEvaluator#orElse}),
 * {@link SingleDecisionEvaluator}, which evaluates a decision on its own; the needs it gives are those of
 * {@link SingleDecisionEvaluator#needsBesideGraphs}, as it computes required decisions.
 *
 * <p>As {@link DecisionTableEvaluator} does, it reports a hit policy violation or an input outside its value list as an
 * {@link Evaluation.Error}; and as {@link LiteralExpressionEvaluator} does, a literal expression's null where the kit
 * expects an error is not supported, as Praecepta reports no errors for expressions.
 */
final class GraphEvaluator implements ModelEvaluator {

    /** A decision the graph cannot evaluate, as its validation report says (a type mismatch, say). */
    static final Feature REPORTED = Feature.feel("decisions the decision requirements graph reports and does not evaluate");

    /** What reading a model gives: the model, or the feature {@link DmnReader#readModel} rejected. */
    private record Read(DecisionModel model, DecisionGraph graph, Feature rejected) {
    }

    private final SingleDecisionEvaluator single;
    /** Models read, by file. */
    private final Map<String, Read> models = new HashMap<>();
    /** Graphs built, by file and the decisions whose values the test case gives. */
    private final Map<String, DecisionGraph> graphs = new HashMap<>();

    GraphEvaluator(SingleDecisionEvaluator single) {
        this.single = single;
    }

    @Override
    public Evaluation evaluate(Check check) {
        if (check.kind() != Invocable.DECISION || check.model().decision(check.decision()) == null) {
            return new Evaluation.Unsupported(single.needsBesideGraphs(check, List.of()));
        }
        if (!single.needsBesideGraphs(check, List.of()).isEmpty()) {
            return new Evaluation.Unsupported(besideGraphs(check));
        }
        var read = read(check.model());
        if (read.rejected() != null) {
            return notTaken(check, read.rejected());
        }
        DecisionGraph graph;
        try {
            graph = graph(check, read);
        } catch (InvalidDecisionTableException | IllegalArgumentException e) {
            return new Evaluation.Failure("the decision requirements graph cannot be built: " + e.getMessage());
        }
        var decision = check.decision();
        if (!graph.canEvaluate(decision)) {
            return notTaken(check, REPORTED);
        }
        Object value;
        try {
            value = graph.evaluate(decision, inputs(check, graph));
        } catch (HitPolicyViolationException | InvalidInputException e) {
            return new Evaluation.Error(e.getMessage());
        }
        var logic = graph.model().decision(decision).orElseThrow().logic();
        if (value == null && check.expectsError() && logic instanceof DecisionLogic.LiteralExpression) {
            return new Evaluation.Unsupported(LiteralExpressionEvaluator.EXPRESSION_ERRORS);
        }
        boolean ordered = !(logic instanceof DecisionLogic.Table(var table) && table.hitPolicy() == HitPolicy.COLLECT);
        return new Evaluation.Value(value(value), ordered);
    }

    /** The model of a file, read once, and its graph. */
    private Read read(DmnModel model) {
        return models.computeIfAbsent(model.file().toString(), file -> {
            try (InputStream dmn = Files.newInputStream(model.file())) {
                var decisionModel = DmnReader.readModel(dmn);
                return new Read(decisionModel, build(decisionModel), null);
            } catch (InvalidDecisionTableException e) {
                return new Read(null, null, rejected(e.getMessage()));
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
        return graphs.computeIfAbsent(check.model().file() + "#" + given, key -> build(given(read, given)));
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
            decisions.add(new DecisionModel.Decision(decision.name(), requiredDecisions, requiredInputs, decision.logic()));
        }
        return new DecisionModel(read.model().name(), inputData, decisions);
    }

    /** The test case's inputs as the graph takes them: values of simple types as text, typed by the kit where undeclared. */
    private static Map<String, Object> inputs(Check check, DecisionGraph graph) {
        var values = new HashMap<String, Object>();
        graph.inputData().forEach((name, type) -> {
            var given = check.inputs().get(name);
            if (given != null) {
                values.put(name, input(given, type));
            }
        });
        return values;
    }

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

    /** A simple value of no declared type, typed by its {@code xsi:type}; its text if it has none Praecepta knows. */
    private static Object typed(TckValue.Simple simple) {
        if (ItemType.builtIn(simple.type()) instanceof ItemType.Simple(var type) && !simple.type().isEmpty()) {
            try {
                return FeelLiterals.value(simple.text(), type);
            } catch (IllegalArgumentException e) {
                return simple.text();
            }
        }
        return simple.text();
    }

    /** A graph's value as the kit compares it. */
    static TckValue value(Object value) {
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
            default -> new TckValue.Simple("", FeelLiterals.text(value));
        };
    }

    // ---- reasons ----------------------------------------------------------------------------------

    /**
     * What a node needs that Praecepta cannot read, beside graphs: the reasons {@link SingleDecisionEvaluator} gives,
     * the more precise about the decision's own logic (a date and time literal FEEL gives null for, say), without
     * {@link Feature#REQUIRED_DECISIONS}, which this evaluator has.
     */
    private List<Feature> besideGraphs(Check check) {
        if (single.evaluate(check) instanceof Evaluation.Unsupported alone) {
            var needs = withoutGraphs(alone.needs());
            if (!needs.isEmpty()) {
                return needs;
            }
        }
        return single.needsBesideGraphs(check, List.of());
    }

    /**
     * A node that needs nothing Praecepta cannot read, but which the graph does not take for {@code reason}: the model
     * as a whole is one Praecepta does not read, or the graph reports the decision. A decision that needs no other
     * decision computed is left to {@link SingleDecisionEvaluator}, with its reasons where that does not take it either;
     * one that needs them waits for {@code reason}.
     */
    private Evaluation notTaken(Check check, Feature reason) {
        if (single.evaluate(check) instanceof Evaluation.Unsupported alone) {
            if (!alone.needs().contains(Feature.REQUIRED_DECISIONS)) {
                return alone;
            }
            var needs = new ArrayList<Feature>();
            needs.add(reason);
            needs.addAll(withoutGraphs(alone.needs()));
            return new Evaluation.Unsupported(needs);
        }
        return new Evaluation.Unsupported(reason);
    }

    private static List<Feature> withoutGraphs(List<Feature> needs) {
        return needs.stream().filter(need -> !need.equals(Feature.REQUIRED_DECISIONS)).toList();
    }

    /**
     * What {@link DmnReader#readModel} rejected in a model, by the issue of the release its message names: what the
     * node does not use itself, since its own needs were none.
     */
    static Feature rejected(String message) {
        if (message.contains("decision service")) {
            return Feature.DECISION_SERVICES;
        }
        if (message.contains("import")) {
            return Feature.IMPORTS;
        }
        if (message.contains("business knowledge model") || message.contains("knowledge requirement")) {
            return Feature.KNOWLEDGE_MODELS;
        }
        if (message.contains("(#117)") && message.contains("function call")) {
            return new Feature(Feature.Area.BUILT_IN_FUNCTIONS, "function calls elsewhere in the model");
        }
        if (message.contains("(#116)")) {
            return Feature.feel("FEEL beyond the expression subset elsewhere in the model");
        }
        if (message.contains("(#118)")) {
            return Feature.boxed("boxed expressions elsewhere in the model");
        }
        if (message.contains("item definition")) {
            return Feature.decisionTable("item definitions Praecepta does not read, elsewhere in the model");
        }
        return Feature.decisionTable("models Praecepta does not read as a whole");
    }
}
