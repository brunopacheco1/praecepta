/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;

/**
 * A DMN file whose decisions require one another, loaded by a framework integration: its decision requirements graph
 * ({@link DecisionGraph}, DMN 1.5, chapter 6), evaluated from maps of text, as the map-based engines of decision tables
 * are. {@link Decisions#load} loads a file as one when one of its decisions requires another decision, or is a literal
 * expression; every decision of the file is then evaluated through it ({@link Decision#evaluate}). Immutable and
 * thread-safe.
 *
 * <p><b>Input data</b> are given by name, as text, read with the type the model declares for them (or, for one it
 * declares none for, the type of the decision table columns that read it): {@code "42"}, {@code "2025-01-31"}, a list
 * as a list column takes it ({@code ["PT", "BR"]}), and a structured input data by its components, each under its path
 * ({@code Loan.principal}, {@code Loan.term.years}), as a decision table's column of that name reads it. An input data
 * of no known type is given as text under its name, a string, or by the paths a decision reads into it
 * ({@code Applicant.age}), each component then read as the column that reads it is typed. A missing value is missing,
 * and so is blank text, except for a {@code string} input data, for which it is the empty string, as FEEL has it
 * (docs/DMN-CONFORMANCE.md, case 16). A list of structured values cannot be written as text.
 *
 * <p><b>A decision's value</b> is given as the maps a decision table's map-based engine gives: one map per value of a
 * list, the components of a context as text by name, any other value under the name of the table's one output, or of
 * the decision for a literal expression; none when the value is null. So a table whose decision declares no type, or
 * the type its outputs give, gives exactly the maps its own engine gives for the same values, and a decision with a
 * single result gives one map; a table whose value is bound to another type its decision declares (DMN 1.5,
 * 10.3.2.9.4) gives the bound value's maps, a single result as a list of one, one map either way.
 */
public final class GraphModel {

    /** The deepest components of a structured input data read from text: {@code DmnReader.MAX_TYPE_DEPTH}, with room. */
    private static final int MAX_DEPTH = 64;

    private final String location;
    private final DecisionGraph graph;
    /** The key of the one map a decision's single value is given under, by decision. */
    private final Map<String, String> valueNames;

    private GraphModel(String location, DecisionGraph graph) {
        this.location = location;
        this.graph = graph;
        var names = new HashMap<String, String>();
        for (var decision : graph.model().decisions()) {
            names.put(decision.name(), decision.logic() instanceof DecisionLogic.Table(var table, var type)
                    ? table.outputs().getFirst().name() : decision.name());
        }
        this.valueNames = Map.copyOf(names);
    }

    /**
     * Builds a model's graph. Validation never fails here; {@link Decisions#load} decides what fails.
     *
     * @param location  where the model was read from ({@link TableSource#location()})
     * @param logReport whether the graph logs its validation report, and each of its tables its own
     *                  ({@code DecisionGraph.Builder.logValidationReport})
     * @throws IllegalArgumentException if one of its decision tables cannot be built into an engine
     */
    public static GraphModel of(String location, DecisionModel model, boolean logReport) {
        Objects.requireNonNull(location, "location");
        try {
            return new GraphModel(location, DecisionGraph.builder().model(model).logValidationReport(logReport).build());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Decision model '%s': %s".formatted(model.name(), e.getMessage()), e);
        }
    }

    /**
     * Whether a model needs its graph ({@link DecisionModel#needsGraph()}): one of its decisions requires another
     * decision, or is not a decision table. The decision tables of any other model are evaluated alone, from their
     * columns, as each file's tables always were.
     */
    public static boolean needed(DecisionModel model) {
        return model.needsGraph();
    }

    /** The model's name: the DMN {@code definitions} element's. */
    public String name() {
        return graph.model().name();
    }

    /** Where the model was read from. */
    public String location() {
        return location;
    }

    /** The graph: the model's decisions, ready to evaluate with Java values, and its validation reports. */
    public DecisionGraph graph() {
        return graph;
    }

    /**
     * The graph's validation report: decisions that require one another in a cycle, requirements the model does not
     * have, and values that do not fit where they go, all ERRORs. Each decision table's own analysis is its
     * {@link Decision#report()}.
     */
    public ValidationReport report() {
        return graph.validationReport();
    }

    /**
     * Evaluates a decision from input data as text, through the decisions it requires.
     *
     * @param decision  the decision's name
     * @param inputData the input data's values as text, by name (a structured one's components by path)
     * @return the decision's value as maps of text (see the class description); empty for null
     * @throws java.util.NoSuchElementException if the model has no decision of that name
     * @throws io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException if the decision cannot
     *         be evaluated: the graph's report has an issue that concerns it or a decision it requires
     * @throws InvalidInputException if a value is not one of its input data's type, or outside a column's value list
     * @throws io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException if a table's hit policy
     *         is violated
     */
    public List<Map<String, String>> evaluate(String decision, Map<String, String> inputData) {
        var needed = graph.requiredInputData(decision);
        var types = graph.inputData();
        var values = new HashMap<String, Object>();
        for (var name : needed) {
            values.put(name, value(name, types.get(name), inputData, 0));
        }
        return maps(graph.evaluate(decision, values), valueNames.get(decision));
    }

    /**
     * The model as plain maps, lists and strings, for a management endpoint: {@code name}, {@code location},
     * {@code decisions} (their names, in the model's order), {@code inputData} (each {@code name} and {@code type}, as
     * FEEL writes it) and {@code issues}, as {@link Decision#describe()} describes a table's.
     */
    public Map<String, Object> describe() {
        var description = new LinkedHashMap<String, Object>();
        description.put("name", name());
        description.put("location", location);
        description.put("decisions", graph.decisions());
        description.put("inputData", graph.inputData().entrySet().stream()
                .map(input -> Map.<String, Object>of("name", input.getKey(), "type", input.getValue().feelName()))
                .toList());
        description.put("issues", report().issues().stream().map(Decision::describe).toList());
        return Collections.unmodifiableMap(description);
    }

    /**
     * A decision as {@link Decision#describe()} adds it for a decision of a model: {@code model}, {@code logic}
     * ({@code decisionTable} or {@code literalExpression}), its {@code expression} for a literal expression, its value's
     * {@code type} as FEEL writes it, {@code requiredDecisions}, {@code inputData} (what it reads, directly or through
     * the decisions it requires; empty if it cannot be evaluated) and {@code canEvaluate}.
     */
    Map<String, Object> describe(String decision) {
        var element = graph.model().decision(decision).orElseThrow();
        var description = new LinkedHashMap<String, Object>();
        description.put("model", name());
        switch (element.logic()) {
            case DecisionLogic.Table table -> description.put("logic", "decisionTable");
            case DecisionLogic.LiteralExpression literal -> {
                description.put("logic", "literalExpression");
                description.put("expression", literal.text().strip());
            }
        }
        description.put("type", graph.type(decision).feelName());
        description.put("requiredDecisions", element.requiredDecisions());
        boolean evaluable = graph.canEvaluate(decision);
        description.put("inputData", evaluable ? graph.requiredInputData(decision) : List.of());
        description.put("canEvaluate", evaluable);
        return description;
    }

    @Override
    public String toString() {
        return "GraphModel[%s at %s: %s]".formatted(name(), location, String.join(", ", graph.decisions()));
    }

    // ---- input data from text ----------------------------------------------------------------------

    /**
     * An input data's value from the text under its name, or for a context, under the paths of its components. Simple
     * values stay text, which the graph reads with their type; blank text is missing, but for a string. A value of no
     * known type is the text under its name, or else a context of the text under the paths into it.
     *
     * @param depth how many contexts enclose it, bounded so that no input recurses without limit
     */
    private static Object value(String name, FeelType type, Map<String, String> text, int depth) {
        if (depth > MAX_DEPTH) {
            throw new InvalidInputException(name, null, "nests more than %d components".formatted(MAX_DEPTH));
        }
        return switch (type) {
            case FeelType.Context(var components) -> contextValue(name, components, text, depth);
            case FeelType.ListOf(FeelType.Simple(var element)) -> listValue(name, element, text.get(name));
            case FeelType.ListOf(FeelType.Any any) -> listValue(name, ColumnType.STRING, text.get(name));
            case FeelType.ListOf list -> {
                if (text.get(name) != null && !text.get(name).isBlank()) {
                    throw new InvalidInputException(name, text.get(name), "is a %s, which cannot be given as text".formatted(type.feelName()));
                }
                yield null;
            }
            case FeelType.Simple(var simple) -> simple == ColumnType.STRING ? text.get(name) : present(text.get(name));
            case FeelType.Any any -> text.get(name) != null ? present(text.get(name)) : paths(name, text);
        };
    }

    /**
     * A value of no known type from the text under the paths into it ({@code Applicant.age}, {@code Applicant.address.city}):
     * a context of each path's text, a path of several components nested as contexts, as a decision reads a path into it
     * (docs/DMN-CONFORMANCE.md, case 15); null if no path is given. Built with a loop, so a long path takes no stack.
     *
     * @throws InvalidInputException if a path both holds text and continues ({@code A.b} and {@code A.b.c})
     */
    private static Map<String, Object> paths(String name, Map<String, String> text) {
        var prefix = name + ".";
        Map<String, Object> root = null;
        for (var entry : new TreeMap<>(text).entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getKey().length() == prefix.length()) {
                continue;
            }
            if (root == null) {
                root = new HashMap<>();
            }
            var components = entry.getKey().substring(prefix.length()).split("\\.", -1);
            var context = root;
            for (int i = 0; i < components.length - 1; i++) {
                var next = context.computeIfAbsent(components[i], component -> new HashMap<String, Object>());
                if (!(next instanceof Map<?, ?>)) {
                    throw new InvalidInputException(entry.getKey(), entry.getValue(), "continues the path '%s', which is given as text"
                            .formatted(name + "." + String.join(".", Arrays.copyOf(components, i + 1))));
                }
                context = cast(next);
            }
            if (context.putIfAbsent(components[components.length - 1], present(entry.getValue())) instanceof Map<?, ?>) {
                throw new InvalidInputException(entry.getKey(), entry.getValue(), "is given as text, but a longer path continues it");
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object context) {
        return (Map<String, Object>) context;
    }

    /** Text, or null where it is blank: a missing value, as for a decision table's map-based inputs. */
    private static String present(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** A context's value: missing (null) if none of its components' paths is given, else the context of them. */
    private static Object contextValue(String name, Map<String, FeelType> components, Map<String, String> text, int depth) {
        if (text.get(name) != null && !text.get(name).isBlank()) {
            throw new InvalidInputException(name, text.get(name), "is a context; give its components by path, such as '%s.%s'"
                    .formatted(name, components.keySet().stream().findFirst().orElse("component")));
        }
        var prefix = name + ".";
        return text.keySet().stream().anyMatch(key -> key.startsWith(prefix)) ? context(prefix, components, text, depth) : null;
    }

    /** A context from the text under its components' paths, each starting with {@code prefix}. */
    private static Map<String, Object> context(String prefix, Map<String, FeelType> components, Map<String, String> text, int depth) {
        var values = new HashMap<String, Object>();
        components.forEach((component, type) -> values.put(component, value(prefix + component, type, text, depth + 1)));
        return values;
    }

    /** A list's value: missing (null) for null or blank text, else the list the text gives ({@link #list}). */
    private static Object listValue(String name, ColumnType type, String text) {
        return text == null || text.isBlank() ? null : list(name, type, text);
    }

    /**
     * A list as a list column takes it as text: {@code ["PT", "BR"]}, {@code PT, BR} or {@code []}, {@code null} for a
     * missing element.
     */
    private static List<Object> list(String name, ColumnType type, String text) {
        var elements = text.strip();
        boolean bracketed = elements.startsWith("[") && elements.endsWith("]");
        if (bracketed) {
            elements = elements.substring(1, elements.length() - 1);
            if (elements.isBlank()) {
                return List.of();
            }
        }
        try {
            if (CellParser.parse(elements, type) instanceof UnaryTests.Positive(var tests)
                    && tests.stream().allMatch(test -> test instanceof UnaryTests.NullTest || isLiteral(test))) {
                var values = new ArrayList<>(tests.size());
                tests.forEach(test -> values.add(test instanceof UnaryTests.Equals(var value, var explicit) ? value : null));
                return values;
            }
        } catch (CellSyntaxException e) {
            // reported below
        }
        throw new InvalidInputException(name, text, "is not a list of %s values, such as [%s]".formatted(type.feelName(),
                type == ColumnType.STRING ? "\"PT\", \"BR\"" : "1, 2"));
    }

    private static boolean isLiteral(UnaryTests.Test test) {
        return test instanceof UnaryTests.Equals(var value, var explicit) && !(value instanceof UnaryTests.Name);
    }

    // ---- values as maps of text --------------------------------------------------------------------

    /** A decision's value as maps of text: one per element of a list, none for null. */
    private static List<Map<String, String>> maps(Object value, String valueName) {
        return switch (value) {
            case null -> List.of();
            case List<?> list -> list.stream().map(element -> map(element, valueName)).toList();
            default -> List.of(map(value, valueName));
        };
    }

    /** One value as a map of text: a context's components by name, any other value under {@code valueName}. */
    private static Map<String, String> map(Object value, String valueName) {
        if (value instanceof Map<?, ?> context) {
            var map = new LinkedHashMap<String, String>();
            context.forEach((component, componentValue) -> map.put(String.valueOf(component), text(componentValue)));
            return Collections.unmodifiableMap(map);
        }
        return Collections.singletonMap(valueName, text(value));
    }

    /** A value as text, as a decision table's outputs are: a list or a context as FEEL writes them; null for null. */
    private static String text(Object value) {
        return switch (value) {
            case null -> null;
            case List<?> list -> list.stream().map(FeelLiterals::literal).collect(Collectors.joining(", ", "[", "]"));
            case Map<?, ?> context -> context.entrySet().stream()
                    .map(component -> component.getKey() + ": " + FeelLiterals.literal(component.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
            default -> FeelLiterals.text(value);
        };
    }
}
