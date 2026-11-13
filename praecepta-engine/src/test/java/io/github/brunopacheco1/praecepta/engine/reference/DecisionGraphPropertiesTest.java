/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Random acyclic graphs of random tables ({@link DecisionTableScenario}) evaluate as the tables do when each is evaluated
 * by hand, in dependency order, with an engine of its own: a table's columns read input data or, now and then, a
 * component of a table before it ({@code T0.out}, {@code T1.n}, {@code T0.echo}), or the whole value of a table with one
 * output ({@code T2}), whose value the test works out by hand from that table's outputs (a context of its outputs, its
 * one output's value, a list of them under a hit policy with several results, the aggregate of a COLLECT SUM, MIN, MAX
 * or COUNT, null when no rule matches and there is no default). A column of single values reads a table with one
 * result; a column of lists reads any table, the list of the components of a list of contexts, or a single value as a
 * list of one. The decisions are added to the graph, and so listed in its DMN, in a random order, not the order they
 * require one another in. Each decision of the graph, evaluated alone, gives what the hand gives, or throws what one of
 * the tables it needs throws. A second property breaks such graphs (a decision requiring itself, two requiring each
 * other, a requirement the model does not have) and checks that exactly the broken decisions, and those that require
 * them, cannot be evaluated, and that the others still evaluate as before.
 */
class DecisionGraphPropertiesTest {

    /**
     * A table of the graph: its scenario, hit policy and priority list, the choices that wire its columns, and its shape:
     * the scenario's four outputs (0), its {@code out} output alone (1), or its {@code n} output alone, aggregated
     * under COLLECT (2).
     */
    record Layer(DecisionTableScenario scenario, HitPolicy policy, List<String> priority, List<Integer> choices, int shape,
            AggregationOperator aggregation) {
    }

    /** A column that reads a component of an earlier table's value, or its whole value if {@code component} is null. */
    private record Feed(int upstream, String component) {
    }

    /** What evaluating a table gives: a value, or the exceptions it or a table it needs may throw. */
    private sealed interface Outcome {
    }

    private record Value(Object value) implements Outcome {
    }

    private record Thrown(Set<Class<?>> exceptions) implements Outcome {
    }

    @Provide
    Arbitrary<List<Layer>> layers() {
        var layer = Combinators.combine(DecisionTableScenario.arbitrary(), Arbitraries.of(HitPolicy.class),
                Arbitraries.shuffle(DecisionTableScenario.OUT_VALUES), Arbitraries.integers().between(0, 999).list().ofSize(8),
                Arbitraries.integers().between(0, 2), Arbitraries.of(AggregationOperator.class))
                .as(Layer::new);
        return layer.list().ofMinSize(2).ofMaxSize(4);
    }

    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void evaluating_a_graph_equals_evaluating_each_table_by_hand_in_dependency_order(@ForAll("layers") List<Layer> layers,
            @ForAll long seed) {
        var tables = new ArrayList<DecisionTable>();
        var feeds = new ArrayList<Map<String, Feed>>();
        for (int i = 0; i < layers.size(); i++) {
            tables.add(table(layers.get(i)));
            feeds.add(feeds(layers, tables, i));
        }
        var graph = graph(layers, tables, feeds, order(layers.size(), seed));
        assertThat(graph.validationReport().issues()).as("the graph is well formed").isEmpty();
        // the graph written as DMN and read back evaluates the same
        var xml = DmnWriter.write(graph.model());
        var copy = DecisionGraph.builder().model(DmnReader.readModel(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                .logValidationReport(false).build();

        int rows = layers.stream().mapToInt(layer -> layer.scenario().inputs().size()).max().orElseThrow();
        for (int r = 0; r < Math.min(rows, 8); r++) {
            var expected = byHand(layers, tables, feeds, r);
            var inputData = inputData(layers, feeds, r);
            for (int i = 0; i < layers.size(); i++) {
                var decision = "T" + i;
                var actual = outcome(graph, decision, inputData);
                switch (expected.get(i)) {
                    case Value(var value) -> assertThat(actual).as("%s for %s", decision, inputData).isEqualTo(value);
                    case Thrown(var exceptions) -> assertThat(actual).as("%s for %s", decision, inputData).isIn(exceptions);
                }
                assertThat(outcome(copy, decision, inputData)).as("%s read back from DMN, for %s\n%s", decision, inputData, xml).isEqualTo(actual);
            }
            // and all at once, each table once
            if (expected.stream().allMatch(Value.class::isInstance)) {
                var all = graph.evaluate(graph.decisions(), inputData);
                for (int i = 0; i < layers.size(); i++) {
                    assertThat(all.get("T" + i)).isEqualTo(((Value) expected.get(i)).value());
                }
            }
        }
    }

    /**
     * A fault put into a well-formed graph: decision {@code at} requires itself (kind 0), requires {@code other}, which
     * requires it (1), or requires a decision the model does not have (2).
     */
    record Fault(int kind, int at, int other) {
    }

    @Provide
    Arbitrary<List<Fault>> faults() {
        return Combinators.combine(Arbitraries.integers().between(0, 2), Arbitraries.integers().between(0, 99), Arbitraries.integers().between(0, 99))
                .as(Fault::new).list().ofMinSize(1).ofMaxSize(2);
    }

    @Property(tries = 150, edgeCases = EdgeCasesMode.NONE)
    void a_broken_graph_blocks_exactly_the_broken_decisions_and_those_that_require_them(@ForAll("layers") List<Layer> layers,
            @ForAll("faults") List<Fault> faults, @ForAll long seed) {
        var tables = new ArrayList<DecisionTable>();
        var feeds = new ArrayList<Map<String, Feed>>();
        for (int i = 0; i < layers.size(); i++) {
            tables.add(table(layers.get(i)));
            feeds.add(feeds(layers, tables, i));
        }
        var sound = graph(layers, tables, feeds, order(layers.size(), seed));
        // the faults, as requirements added to the model; and by hand, which decisions are broken, and why
        int n = layers.size();
        var required = new ArrayList<List<String>>();
        var broken = new boolean[n];
        var kinds = new HashSet<Kind>();
        for (int i = 0; i < n; i++) {
            required.add(new ArrayList<>(sound.model().decision("T" + i).orElseThrow().requiredDecisions()));
        }
        for (var fault : faults) {
            int at = fault.at() % n;
            int other = (at + 1 + fault.other() % (n - 1)) % n;
            switch (fault.kind()) {
                case 0 -> {
                    required.get(at).add("T" + at);
                    kinds.add(Kind.DECISION_CYCLE);
                }
                case 1 -> {
                    required.get(at).add("T" + other);
                    required.get(other).add("T" + at);
                    kinds.add(Kind.DECISION_CYCLE);
                }
                default -> {
                    required.get(at).add("Nowhere");
                    kinds.add(Kind.MISSING_REQUIREMENT);
                    broken[at] = true;
                }
            }
        }
        var decisions = new ArrayList<DecisionModel.Decision>();
        for (var decision : sound.model().decisions()) {
            int i = Integer.parseInt(decision.name().substring(1));
            decisions.add(new DecisionModel.Decision(decision.name(), required.get(i).stream().distinct().toList(), decision.requiredInputs(),
                    decision.logic()));
        }
        var graph = DecisionGraph.builder().model(new DecisionModel("broken", sound.model().inputData(), decisions))
                .logValidationReport(false).build();
        var blocked = blockedByHand(required, broken);

        assertThat(graph.validationReport().issues()).extracting(ValidationReport.Issue::kind).isSubsetOf(kinds)
                .containsAll(kinds);
        int rows = layers.stream().mapToInt(layer -> layer.scenario().inputs().size()).max().orElseThrow();
        for (int i = 0; i < n; i++) {
            var decision = "T" + i;
            assertThat(graph.canEvaluate(decision)).as("%s can be evaluated, with requirements %s", decision, required).isEqualTo(!blocked[i]);
            for (int r = 0; r < Math.min(rows, 4); r++) {
                var inputData = inputData(layers, feeds, r);
                if (blocked[i]) {
                    assertThat(outcome(graph, decision, inputData)).isEqualTo(InvalidDecisionTableException.class);
                } else {
                    assertThat(outcome(graph, decision, inputData)).as("%s for %s", decision, inputData)
                            .isEqualTo(outcome(sound, decision, inputData));
                }
            }
        }
    }

    /**
     * Which decisions cannot be evaluated: those in a cycle of {@code required}, those {@code broken} otherwise, and every
     * decision that requires one of them, directly or indirectly; worked out by repeating until nothing changes.
     */
    private static boolean[] blockedByHand(List<List<String>> required, boolean[] broken) {
        int n = required.size();
        var reaches = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (var name : required.get(i)) {
                if (name.startsWith("T")) {
                    reaches[i][Integer.parseInt(name.substring(1))] = true;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    reaches[i][j] |= reaches[i][k] && reaches[k][j];
                }
            }
        }
        var blocked = new boolean[n];
        for (int i = 0; i < n; i++) {
            blocked[i] = broken[i] || reaches[i][i];
            for (int j = 0; j < n; j++) {
                blocked[i] |= reaches[i][j] && (broken[j] || reaches[j][j]);
            }
        }
        return blocked;
    }

    /** What a graph gives for a decision: its value, or the class of the exception it throws. */
    private static Object outcome(DecisionGraph graph, String decision, Map<String, Object> inputData) {
        try {
            return graph.evaluate(decision, inputData);
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }

    /** The positions of the decisions, shuffled: the order they are added to the graph in. */
    private static List<Integer> order(int size, long seed) {
        var order = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(seed));
        return order;
    }

    /**
     * The layer's table, as the scenario makes it under its hit policy (ANY with rules whose outputs may agree), with its
     * four outputs, or one of them.
     */
    private static DecisionTable table(Layer layer) {
        var s = layer.scenario();
        return switch (layer.shape()) {
            case 1 -> {
                var rules = layer.policy() == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
                yield project(s.table(rules, layer.policy(), layer.priority()), DecisionTableScenario.OUT, null);
            }
            case 2 -> project(s.table(s.rules(), HitPolicy.COLLECT, layer.priority()), DecisionTableScenario.NUMBER, layer.aggregation());
            default -> {
                var rules = layer.policy() == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
                yield s.table(rules, layer.policy(), layer.priority());
            }
        };
    }

    /** The table with one of its outputs alone, aggregated under COLLECT if {@code aggregation} is given. */
    private static DecisionTable project(DecisionTable table, String output, AggregationOperator aggregation) {
        var rules = new ArrayList<Praeceptum>();
        for (var rule : table.rules()) {
            var values = new HashMap<>(rule.output().values());
            values.keySet().retainAll(Set.of(output));
            var references = new HashMap<>(rule.output().references());
            references.keySet().retainAll(Set.of(output));
            var expressions = new HashMap<>(rule.output().expressions());
            expressions.keySet().retainAll(Set.of(output));
            rules.add(new Praeceptum(rule.priority(), rule.input(), new PraeceptaOutput(values, references, expressions)));
        }
        var outputs = table.outputs().stream().filter(column -> column.name().equals(output)).toList();
        return new DecisionTable(table.name(), aggregation == null ? table.hitPolicy() : HitPolicy.COLLECT, aggregation, table.inputs(), outputs,
                rules, table.feelCells(), table.listInputs());
    }

    /** The table with no aggregation, and no default output: what its matching rules give. */
    private static DecisionTable matching(DecisionTable table) {
        var outputs = table.outputs().stream().map(column -> new DecisionTable.OutputColumn(column.name(), column.type(), column.values())).toList();
        return new DecisionTable(table.name(), table.hitPolicy(), null, table.inputs(), outputs, table.rules(), table.feelCells(),
                table.listInputs());
    }

    /**
     * Which columns of table {@code i} read an earlier table, and which component: a column of single strings reads
     * {@code out} or {@code echo} of a table with one result, or the whole value of one whose one output is {@code out},
     * one of single numbers {@code n}, or the whole value of an aggregate; a column of lists of strings or numbers reads
     * them of any table. Columns that declare input values, and the others, read input data.
     */
    private static Map<String, Feed> feeds(List<Layer> layers, List<DecisionTable> tables, int i) {
        var feeds = new HashMap<String, Feed>();
        if (i == 0) {
            return feeds;
        }
        var s = layers.get(i).scenario();
        var choices = layers.get(i).choices();
        for (int c = 0; c < s.columns().size(); c++) {
            var column = s.columns().get(c);
            int choice = choices.get(c % choices.size());
            int upstream = choice % i;
            var type = s.types().get(column);
            boolean list = s.lists().contains(column);
            boolean single = !list && hasOneResult(tables.get(upstream));
            if (choice % 3 == 0 || s.allowedValues().containsKey(column) || !(list || single)) {
                continue;
            }
            var outputs = tables.get(upstream).outputs();
            Feed feed = null;
            if (outputs.size() == 1) {
                // a table with one output gives its value, read whole
                feed = outputs.getFirst().type() == type ? new Feed(upstream, null) : null;
            } else if (type == ColumnType.STRING) {
                feed = new Feed(upstream, choice % 2 == 0 ? DecisionTableScenario.OUT : DecisionTableScenario.ECHO);
            } else if (type == ColumnType.NUMBER) {
                feed = new Feed(upstream, DecisionTableScenario.NUMBER);
            }
            // a table's columns have names of their own, so two never read the same component
            if (feed != null && !feeds.containsValue(feed)) {
                feeds.put(column, feed);
            }
        }
        return feeds;
    }

    private static boolean hasOneResult(DecisionTable table) {
        return table.hitPolicy().isSingleHit() || table.aggregation() != null;
    }

    /**
     * The graph: each table named {@code T<i>}, its columns renamed to the input data {@code t<i>c<j>} or to the component
     * they read, added in {@code order}.
     */
    private static DecisionGraph graph(List<Layer> layers, List<DecisionTable> tables, List<Map<String, Feed>> feeds, List<Integer> order) {
        var builder = DecisionGraph.builder().logValidationReport(false);
        for (int i : order) {
            var s = layers.get(i).scenario();
            var names = new HashMap<String, String>();
            for (var column : s.columns()) {
                var feed = feeds.get(i).get(column);
                if (feed == null) {
                    names.put(column, "t" + i + column);
                    var type = s.types().get(column);
                    builder.inputData("t" + i + column, s.lists().contains(column) ? FeelType.listOf(type) : FeelType.of(type));
                } else {
                    names.put(column, "T" + feed.upstream() + (feed.component() == null ? "" : "." + feed.component()));
                }
            }
            builder.decision(renamed(tables.get(i), "T" + i, names));
        }
        return builder.build();
    }

    private static final Pattern COLUMN = Pattern.compile("(?<![\\w\"])c\\d+(?!\\w)");

    /** The table named {@code name}, with its columns renamed, in its cells, references and expressions too. */
    private static DecisionTable renamed(DecisionTable table, String name, Map<String, String> names) {
        var inputs = table.inputs().stream()
                .map(column -> new DecisionTable.InputColumn(names.get(column.name()), column.type(), column.allowedValues())).toList();
        var rules = new ArrayList<Praeceptum>();
        for (var rule : table.rules()) {
            var cells = new HashMap<String, String>();
            rule.input().values().forEach((column, cell) -> cells.put(names.get(column), rename(cell, names)));
            var references = new HashMap<String, String>();
            rule.output().references().forEach((output, input) -> references.put(output, names.get(input)));
            var expressions = new HashMap<String, String>();
            rule.output().expressions().forEach((output, expression) -> expressions.put(output, rename(expression, names)));
            var output = new PraeceptaOutput(rule.output().values(), references, expressions);
            rules.add(new Praeceptum(rule.priority(), new PraeceptaInput(cells), output));
        }
        var lists = new HashSet<String>();
        table.listInputs().forEach(list -> lists.add(names.get(list)));
        return new DecisionTable(name, table.hitPolicy(), table.aggregation(), inputs, table.outputs(), rules, table.feelCells(), lists);
    }

    private static String rename(String text, Map<String, String> names) {
        return text == null ? null
                : COLUMN.matcher(text).replaceAll(found -> Matcher.quoteReplacement(names.getOrDefault(found.group(), found.group())));
    }

    /** The graph's input data: each table's columns that read no other table, from row {@code r} of its scenario's inputs. */
    private static Map<String, Object> inputData(List<Layer> layers, List<Map<String, Feed>> feeds, int r) {
        var inputData = new HashMap<String, Object>();
        for (int i = 0; i < layers.size(); i++) {
            var s = layers.get(i).scenario();
            var row = s.inputs().get(r % s.inputs().size());
            for (var column : s.columns()) {
                if (!feeds.get(i).containsKey(column)) {
                    inputData.put("t" + i + column, row.get(column));
                }
            }
        }
        return inputData;
    }

    // ---- by hand -----------------------------------------------------------------------------------

    /** Each table evaluated with an engine of its own, in order, reading the values worked out for the tables before it. */
    private static List<Outcome> byHand(List<Layer> layers, List<DecisionTable> tables, List<Map<String, Feed>> feeds, int r) {
        var outcomes = new ArrayList<Outcome>();
        for (int i = 0; i < layers.size(); i++) {
            var s = layers.get(i).scenario();
            var input = new HashMap<>(s.inputs().get(r % s.inputs().size()));
            var thrown = new HashSet<Class<?>>();
            for (var feed : feeds.get(i).entrySet()) {
                var upstream = outcomes.get(feed.getValue().upstream());
                if (upstream instanceof Thrown(var exceptions)) {
                    thrown.addAll(exceptions);
                } else {
                    var value = component(((Value) upstream).value(), feed.getValue().component());
                    input.put(feed.getKey(), s.lists().contains(feed.getKey()) ? asList(value) : value);
                }
            }
            if (!thrown.isEmpty()) {
                outcomes.add(new Thrown(thrown));
                continue;
            }
            try {
                var table = tables.get(i);
                outcomes.add(new Value(table.aggregation() != null
                        ? aggregate(table, engine(s, matching(table)).evaluate(input))
                        : value(table, engine(s, table).evaluate(input))));
            } catch (RuntimeException e) {
                outcomes.add(new Thrown(Set.of(e.getClass())));
            }
        }
        return outcomes;
    }

    private static PraeceptaEngine<Map<String, Object>, PraeceptaOutput> engine(DecisionTableScenario s, DecisionTable table) {
        var builder = PraeceptaEngine.<Map<String, Object>, PraeceptaOutput>builder().table(table).output(output -> output)
                .logValidationReport(false);
        s.bindColumns(builder);
        s.applyMaskForm(builder);
        return builder.build();
    }

    /**
     * A table's value: null for no output, a context of its outputs or its one output's value, or under a hit policy with
     * several results a list of them.
     */
    private static Object value(DecisionTable table, List<PraeceptaOutput> outputs) {
        if (outputs.isEmpty()) {
            return null;
        }
        if (hasOneResult(table)) {
            return result(table, outputs.getFirst());
        }
        return outputs.stream().map(output -> result(table, output)).toList();
    }

    /** One result: its one output's value, or a context of its outputs. */
    private static Object result(DecisionTable table, PraeceptaOutput output) {
        var context = context(table, output);
        return table.outputs().size() == 1 ? context.get(table.outputs().getFirst().name()) : context;
    }

    /**
     * The aggregate of the matching rules' one output (DMN 1.5, 10.3.2.10): COUNT their number, SUM, MIN and MAX their
     * values, null if one is missing (FEEL's sum, min and max of a list holding null); the default output, or null, when
     * none matches.
     */
    private static Object aggregate(DecisionTable table, List<PraeceptaOutput> matches) {
        var column = table.outputs().getFirst();
        if (matches.isEmpty()) {
            return column.hasDefault() && column.defaultValue() != null ? new BigDecimal(column.defaultValue()) : null;
        }
        if (table.aggregation() == AggregationOperator.COUNT) {
            return BigDecimal.valueOf(matches.size());
        }
        var values = matches.stream().map(match -> match.values().get(column.name())).toList();
        if (values.contains(null)) {
            return null;
        }
        var numbers = values.stream().map(BigDecimal::new).toList();
        return switch (table.aggregation()) {
            case SUM -> numbers.stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MathContext.DECIMAL128));
            case MIN -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
            case MAX -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
            default -> throw new IllegalStateException(table.aggregation().name());
        };
    }

    private static Map<String, Object> context(DecisionTable table, PraeceptaOutput output) {
        var context = new LinkedHashMap<String, Object>();
        for (var column : table.outputs()) {
            var text = output.values().get(column.name());
            context.put(column.name(), text == null || column.type() != ColumnType.NUMBER ? text : new BigDecimal(text));
        }
        return context;
    }

    /** A component of a context, or of each context of a list; the value itself if {@code component} is null. */
    private static Object component(Object value, String component) {
        if (component == null) {
            return value;
        }
        return switch (value) {
            case null -> null;
            case Map<?, ?> context -> context.get(component);
            case Collection<?> list -> list.stream().map(element -> ((Map<?, ?>) element).get(component)).toList();
            default -> throw new IllegalStateException("not a context: " + value);
        };
    }

    private static Object asList(Object value) {
        return value == null || value instanceof List<?> ? value : List.of(value);
    }
}
