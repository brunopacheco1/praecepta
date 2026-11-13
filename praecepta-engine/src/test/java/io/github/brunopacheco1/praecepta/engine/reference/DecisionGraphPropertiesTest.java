/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
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
 * component of a table before it ({@code T0.out}, {@code T1.n}, {@code T0.echo}), whose value the test works out by hand
 * from that table's outputs (a context of its outputs, a list of them under a hit policy with several results, null
 * when no rule matches and there is no default). A column of single values reads a table with one result; a column of
 * lists reads any table, the list of the components of a list of contexts, or a single value as a list of one. Each
 * decision of the graph, evaluated alone, gives what the hand gives, or throws what one of the tables it needs throws.
 */
class DecisionGraphPropertiesTest {

    /** A table of the graph: its scenario, hit policy and priority list, and the choices that wire its columns. */
    record Layer(DecisionTableScenario scenario, HitPolicy policy, List<String> priority, List<Integer> choices) {
    }

    /** A column that reads a component of an earlier table's value. */
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
                Arbitraries.shuffle(DecisionTableScenario.OUT_VALUES), Arbitraries.integers().between(0, 999).list().ofSize(8))
                .as(Layer::new);
        return layer.list().ofMinSize(2).ofMaxSize(4);
    }

    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void evaluating_a_graph_equals_evaluating_each_table_by_hand_in_dependency_order(@ForAll("layers") List<Layer> layers) {
        var tables = new ArrayList<DecisionTable>();
        var feeds = new ArrayList<Map<String, Feed>>();
        for (int i = 0; i < layers.size(); i++) {
            tables.add(table(layers.get(i)));
            feeds.add(feeds(layers, i));
        }
        var graph = graph(layers, tables, feeds);
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

    /** What a graph gives for a decision: its value, or the class of the exception it throws. */
    private static Object outcome(DecisionGraph graph, String decision, Map<String, Object> inputData) {
        try {
            return graph.evaluate(decision, inputData);
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }

    /** The layer's table, as the scenario makes it under its hit policy (ANY with rules whose outputs may agree). */
    private static DecisionTable table(Layer layer) {
        var s = layer.scenario();
        var rules = layer.policy() == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
        return s.table(rules, layer.policy(), layer.priority());
    }

    /**
     * Which columns of table {@code i} read an earlier table, and which component: a column of single strings reads
     * {@code out} or {@code echo} of a table with one result, one of single numbers {@code n}; a column of lists of strings
     * or numbers reads them of any table. Columns that declare input values, and the others, read input data.
     */
    private static Map<String, Feed> feeds(List<Layer> layers, int i) {
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
            boolean single = !list && hasOneResult(layers.get(upstream).policy());
            if (choice % 3 == 0 || s.allowedValues().containsKey(column) || !(list || single)) {
                continue;
            }
            Feed feed = null;
            if (type == ColumnType.STRING) {
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

    private static boolean hasOneResult(HitPolicy policy) {
        return policy.isSingleHit();
    }

    /** The graph: each table named {@code T<i>}, its columns renamed to the input data {@code t<i>c<j>} or to the component they read. */
    private static DecisionGraph graph(List<Layer> layers, List<DecisionTable> tables, List<Map<String, Feed>> feeds) {
        var builder = DecisionGraph.builder().logValidationReport(false);
        for (int i = 0; i < layers.size(); i++) {
            var s = layers.get(i).scenario();
            var names = new HashMap<String, String>();
            for (var column : s.columns()) {
                var feed = feeds.get(i).get(column);
                if (feed == null) {
                    names.put(column, "t" + i + column);
                    var type = s.types().get(column);
                    builder.inputData("t" + i + column, s.lists().contains(column) ? FeelType.listOf(type) : FeelType.of(type));
                } else {
                    names.put(column, "T" + feed.upstream() + "." + feed.component());
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
                outcomes.add(new Value(value(tables.get(i), engine(s, tables.get(i)).evaluate(input))));
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

    /** A table's value: null for no output, a context of its outputs, or under a hit policy with several results a list of them. */
    private static Object value(DecisionTable table, List<PraeceptaOutput> outputs) {
        if (outputs.isEmpty()) {
            return null;
        }
        if (hasOneResult(table.hitPolicy())) {
            return context(table, outputs.getFirst());
        }
        return outputs.stream().map(output -> context(table, output)).toList();
    }

    private static Map<String, Object> context(DecisionTable table, PraeceptaOutput output) {
        var context = new LinkedHashMap<String, Object>();
        for (var column : table.outputs()) {
            var text = output.values().get(column.name());
            context.put(column.name(), text == null || column.type() != ColumnType.NUMBER ? text : new BigDecimal(text));
        }
        return context;
    }

    /** A component of a context, or of each context of a list. */
    private static Object component(Object value, String component) {
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
