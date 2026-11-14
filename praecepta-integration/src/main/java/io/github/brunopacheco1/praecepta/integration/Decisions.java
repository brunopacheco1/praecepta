/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.integration;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;

/**
 * The decisions an application loaded at startup, by name: what the Spring Boot starter, the Micronaut
 * module and the Quarkus extension register as a bean, and what their management endpoints report. Each decision
 * table of a file is a decision, evaluated alone; in a file whose decisions require other decisions, or that has
 * literal expressions, every decision is one, evaluated through the file's decision requirements graph
 * ({@link GraphModel}, {@link #models()}). Immutable and thread-safe.
 *
 * <pre>{@code
 * var decisions = Decisions.load(TableLocations.find(List.of("classpath:rules/*.dmn"), loader), Severity.ERROR);
 * List<Map<String, String>> discount = decisions.evaluate("Discount", Map.of("country", "PT", "age", "42"));
 * }</pre>
 */
public final class Decisions {

    private static final System.Logger LOG = System.getLogger(Decisions.class.getName());

    private final Map<String, Decision> byName;
    private final List<GraphModel> models;

    private Decisions(Map<String, Decision> byName, List<GraphModel> models) {
        this.byName = Collections.unmodifiableMap(byName);
        this.models = List.copyOf(models);
    }

    /** No decisions. */
    public static Decisions empty() {
        return new Decisions(new TreeMap<>(), List.of());
    }

    /**
     * Reads every decision of every source (a DMN file may hold several) and builds their engines, which log their
     * validation reports as {@code PraeceptaEngine} does by default; see {@link #load(Collection, Severity, boolean)}.
     *
     * @param failOn the validation severity that fails loading: ERROR, WARNING, or null for none (unreadable
     *               or invalid tables always fail)
     * @throws DecisionsException listing every problem: a source that cannot be read or holds no decision, a
     *                            table that is not valid DMN or cannot be built, two decisions with the same
     *                            name, and validation issues at least as severe as {@code failOn}
     */
    public static Decisions load(Collection<TableSource> sources, Severity failOn) {
        return load(sources, failOn, true);
    }

    /**
     * Reads every decision of every source and builds their engines. Each source is read as a decision model
     * ({@code DmnReader.readModel}): if one of its decisions requires another decision, or is a literal expression,
     * its decisions are evaluated through its decision requirements graph ({@link GraphModel}), whose validation
     * report is checked too; otherwise each of its decision tables is a decision of its own, evaluated alone. A model
     * that uses what Praecepta does not evaluate yet (business knowledge models, boxed expressions, decision services,
     * imports, FEEL beyond its expression subset), or that is not a well-formed decision model (two elements of one
     * name, such as a table with a column of its own name as {@code DmnWriter} writes it, a decision without logic),
     * which a file of decision tables never had to be, has its decision tables read alone, as before
     * ({@code DmnReader.readAll}), and nothing else of it is evaluated; one that has no decision table is rejected,
     * naming the feature and its issue. A file whose tables are read alone because the model uses what a later release
     * plans is logged, one line at INFO, naming the file, the feature and the release and issue that plan it
     * ({@link #fallbackMessage}), so that its graph is not left out unseen.
     *
     * @param failOn     the validation severity that fails loading: ERROR, WARNING, or null for none
     *                   (unreadable or invalid tables always fail)
     * @param logReports whether each engine, and each graph, logs its validation report
     *                   ({@code PraeceptaEngine.Builder.logValidationReport}), and this class each file whose
     *                   decision tables are read alone (logger {@code io.github.brunopacheco1.praecepta.integration.Decisions},
     *                   at INFO); false when the caller reports the decisions' {@link Decision#report() reports}
     *                   and the models' {@link GraphModel#report() reports} itself, as {@link DecisionsLoader} does,
     *                   which logs those files through the framework's logging
     * @throws DecisionsException listing every problem: a source that cannot be read or holds no decision, a
     *                            model Praecepta cannot evaluate, a table that is not valid DMN or cannot be
     *                            built, two decisions with the same name, and validation issues at least as
     *                            severe as {@code failOn}, a graph's included
     */
    public static Decisions load(Collection<TableSource> sources, Severity failOn, boolean logReports) {
        return load(sources, failOn, logReports, logReports ? message -> LOG.log(Level.INFO, message) : message -> {
            // the caller does not log
        });
    }

    /**
     * As {@link #load(Collection, Severity, boolean)}, giving {@code fallbacks} the line to log of each file whose
     * decision tables are read alone ({@link #fallbackMessage}), whether or not loading then fails.
     */
    static Decisions load(Collection<TableSource> sources, Severity failOn, boolean logReports, Consumer<String> fallbacks) {
        var problems = new ArrayList<String>();
        var byName = new TreeMap<String, Decision>();
        var models = new ArrayList<GraphModel>();
        for (var source : sources) {
            var read = read(source, logReports, problems);
            if (read.fallback() != null) {
                fallbacks.accept(read.fallback());
            }
            if (read.model() != null) {
                models.add(read.model());
                check(read.model(), failOn, problems);
            }
            for (var decision : read.decisions()) {
                var other = byName.putIfAbsent(decision.name(), decision);
                if (other != null) {
                    problems.add("%s: decision '%s' is also defined in %s".formatted(source.location(), decision.name(), other.location()));
                } else if (failOn != null && decision.report().hasIssuesAtLeast(failOn)) {
                    problems.add("%s: decision '%s' has validation issues at %s or above:%n  %s".formatted(source.location(),
                            decision.name(), failOn, decision.report().toString().replace("\n", "\n  ")));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }
        return new Decisions(byName, models);
    }

    /** Adds to {@code problems} the issues of a model's graph at least as severe as {@code failOn}. */
    private static void check(GraphModel model, Severity failOn, List<String> problems) {
        if (failOn != null && model.report().hasIssuesAtLeast(failOn)) {
            problems.add("%s: decision model '%s' has validation issues at %s or above:%n  %s".formatted(model.location(),
                    model.name(), failOn, model.report().toString().replace("\n", "\n  ")));
        }
    }

    /**
     * What a source holds: its decisions, the graph they are evaluated through, or null if they need none, and the line
     * to log if its decision tables are read alone because its model uses what a later release plans, else null.
     */
    private record Read(List<Decision> decisions, GraphModel model, String fallback) {

        static final Read NOTHING = new Read(List.of(), null, null);
    }

    /**
     * The line logged, at INFO, of a DMN file whose decision tables are loaded alone because {@code DmnReader.readModel}
     * rejects its model with what a later release plans: {@code rules/fees.dmn: its decision tables are loaded alone,
     * without its decision requirements graph, which needs Praecepta 0.10 (#118): DMN model has the business
     * knowledge model 'Rate', ...}.
     */
    static String fallbackMessage(String location, UnsupportedFeatureException rejected) {
        return "%s: its decision tables are loaded alone, without its decision requirements graph, which needs Praecepta %s (#%d): %s"
                .formatted(location, rejected.release(), rejected.issue(), rejected.getMessage());
    }

    private static Read read(TableSource source, boolean logReports, List<String> problems) {
        DecisionModel model = null;
        RuntimeException rejected = null;
        try (var dmn = source.opener().open()) {
            model = DmnReader.readModel(dmn);
        } catch (IOException e) {
            problems.add("%s: cannot be read (%s)".formatted(source.location(), e.getMessage()));
            return Read.NOTHING;
        } catch (RuntimeException e) {
            // not a decision model Praecepta evaluates as a graph: one with what a later release plans, or not well
            // formed (two elements of one name, a decision without logic...), which a file of decision tables read alone
            // never had to be; its tables are read as before, and if it has none, why the model was rejected is the problem
            rejected = e;
        }
        if (model != null && GraphModel.needed(model)) {
            return graph(source, model, logReports, problems);
        }
        var tableProblems = new ArrayList<String>();
        var tables = tables(source, logReports, tableProblems);
        if (!(rejected instanceof UnsupportedFeatureException unsupported)) {
            problems.addAll(tableProblems);
            return new Read(tables, null, null);
        }
        if (tables.isEmpty()) {
            problems.add(source.location() + ": " + unsupported.getMessage());
            return Read.NOTHING;
        }
        problems.addAll(tableProblems);
        return new Read(tables, null, fallbackMessage(source.location(), unsupported));
    }

    /** Each decision table of a source, read alone, as {@code DmnReader.readAll} reads them. */
    private static List<Decision> tables(TableSource source, boolean logReports, List<String> problems) {
        try (var dmn = source.opener().open()) {
            var tables = DmnReader.readAll(dmn);
            if (tables.isEmpty()) {
                problems.add(source.location() + ": no decision table");
                return List.of();
            }
            var decisions = new ArrayList<Decision>();
            for (var table : tables) {
                decision(source, table, logReports, problems).ifPresent(decisions::add);
            }
            return decisions;
        } catch (IOException e) {
            problems.add("%s: cannot be read (%s)".formatted(source.location(), e.getMessage()));
        } catch (RuntimeException e) {
            problems.add(source.location() + ": " + e.getMessage());
        }
        return List.of();
    }

    /** The decisions of a model that needs its graph, each evaluated through it. */
    private static Read graph(TableSource source, DecisionModel model, boolean logReports, List<String> problems) {
        GraphModel graph;
        try {
            graph = GraphModel.of(source.location(), model, logReports);
        } catch (IllegalArgumentException e) {
            problems.add(source.location() + ": " + e.getMessage());
            return Read.NOTHING;
        }
        var decisions = new ArrayList<Decision>();
        for (var decision : model.decisions()) {
            try {
                // the graph's tables log their reports when asked to: their engines evaluated alone do not log them again
                decisions.add(Decision.of(graph, decision.name(), false));
            } catch (IllegalArgumentException e) {
                problems.add(source.location() + ": " + e.getMessage());
            }
        }
        return new Read(decisions, graph, null);
    }

    private static Optional<Decision> decision(TableSource source, DecisionTable table, boolean logReports, List<String> problems) {
        try {
            return Optional.of(Decision.of(source.location(), table, logReports));
        } catch (IllegalArgumentException e) {
            problems.add(source.location() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The decision with this name, if loaded. */
    public Optional<Decision> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The decision with this name.
     *
     * @throws NoSuchElementException naming the decisions there are, if there is none by this name
     */
    public Decision get(String name) {
        var decision = byName.get(name);
        if (decision == null) {
            throw new NoSuchElementException("No decision named '%s'; there are %s".formatted(name, byName.keySet()));
        }
        return decision;
    }

    /**
     * The engine of the decision with this name, which evaluates its table alone; see {@link #get(String)}.
     *
     * @throws NoSuchElementException if there is no decision by this name, or it has no table (a literal expression of
     *                                a decision model)
     */
    public PraeceptaEngine<Map<String, String>, Map<String, String>> engine(String name) {
        var decision = get(name);
        if (decision.engine() == null) {
            throw new NoSuchElementException(("Decision '%s' is a literal expression, which has no engine of its own; evaluate it "
                    + "with evaluate(\"%s\", inputData)").formatted(name, name));
        }
        return decision.engine();
    }

    /**
     * The decision requirements graphs of the files whose decisions require other decisions, or have literal
     * expressions, by location.
     */
    public List<GraphModel> models() {
        return models;
    }

    /**
     * Evaluates the decision with this name: a table read alone from its columns, a decision of a model from the
     * model's input data; see {@link Decision#evaluate(Map)} and {@link #get(String)}.
     */
    public List<Map<String, String>> evaluate(String name, Map<String, String> input) {
        return get(name).evaluate(input);
    }

    /** The decisions' names, in order. */
    public Set<String> names() {
        return byName.keySet();
    }

    /** The decisions, ordered by name. */
    public Collection<Decision> all() {
        return byName.values();
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Every decision as {@link Decision#describe()} gives it, under {@code decisions} by name, for a
     * management endpoint ({@code /actuator/praecepta} and its Micronaut and Quarkus counterparts); and, when files
     * hold decision requirements graphs, each as {@link GraphModel#describe()} gives it, under {@code models}, in
     * the order of their locations.
     */
    public Map<String, Object> describe() {
        var decisions = new LinkedHashMap<String, Object>();
        byName.forEach((name, decision) -> decisions.put(name, decision.describe()));
        if (models.isEmpty()) {
            return Map.of("decisions", Collections.unmodifiableMap(decisions));
        }
        var description = new LinkedHashMap<String, Object>();
        description.put("decisions", Collections.unmodifiableMap(decisions));
        description.put("models", models.stream().map(GraphModel::describe).toList());
        return Collections.unmodifiableMap(description);
    }

    /**
     * Parses a {@code fail-on} setting: {@code ERROR}, {@code WARNING}, or {@code NONE} (null), in any case, as
     * {@link Severity#failOn} reads it for every integration.
     *
     * @throws IllegalArgumentException for any other value, null included:
     *                                  {@code fail-on must be ERROR, WARNING or NONE, not <value>}
     */
    public static Severity failOn(String value) {
        try {
            return Severity.failOn(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("fail-on " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "Decisions" + byName.keySet();
    }
}
