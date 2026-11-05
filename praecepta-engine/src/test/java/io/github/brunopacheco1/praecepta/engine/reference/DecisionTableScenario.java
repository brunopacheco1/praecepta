package io.github.brunopacheco1.praecepta.engine.reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * A random decision table plus inputs to evaluate against it. Tables are small (≤ 4 columns,
 * ≤ 10 rules, ≤ 4 values per column) so the current trie stays tiny and shrinking stays readable,
 * yet overlaps, wildcards and multi-value cells are frequent.
 *
 * <p>Every rule has outputs {@code id} (its 1-based position, unique), {@code out} (one of x/y/z,
 * often shared) and {@code n} (a small number, for aggregation).
 */
public record DecisionTableScenario(List<String> columns, List<Praeceptum> rules, List<Map<String, String>> inputs) {

    public static final String ID = "id";
    public static final String OUT = "out";
    public static final String NUMBER = "n";
    public static final List<String> OUT_VALUES = List.of("x", "y", "z");

    private static final List<String> ALL_VALUES = List.of("a", "b", "c", "d");
    private static final String UNKNOWN_VALUE = "q";

    public static Arbitrary<DecisionTableScenario> arbitrary() {
        return Arbitraries.integers().between(1, 4).flatMap(columnCount ->
                Arbitraries.integers().between(2, 4).flatMap(domainSize -> {
                    var columns = new ArrayList<String>();
                    for (int c = 0; c < columnCount; c++) {
                        columns.add("c" + c);
                    }
                    var domain = ALL_VALUES.subList(0, domainSize);
                    var rules = rule(columnCount, domain).list().ofMinSize(1).ofMaxSize(10);
                    var inputs = input(columnCount, domain).list().ofMinSize(1).ofMaxSize(15);
                    return Combinators.combine(rules, inputs)
                            .as((ruleRows, inputRows) -> build(columns, ruleRows, inputRows));
                }));
    }

    public List<String> ids(List<PraeceptaOutput> outputs) {
        return outputs.stream().map(output -> output.values().get(ID)).toList();
    }

    public List<String> ruleIds(List<Praeceptum> matches) {
        return ids(matches.stream().map(Praeceptum::output).toList());
    }

    private record RuleRow(List<String> cells, String out, int number) {
    }

    private static Arbitrary<RuleRow> rule(int columnCount, List<String> domain) {
        Arbitrary<String> wildcard = Arbitraries.just("");
        Arbitrary<String> single = Arbitraries.of(domain);
        Arbitrary<String> pair = Arbitraries.of(domain).set().ofSize(2)
                .map(values -> String.join(",", values.stream().sorted().toList()));
        @SuppressWarnings("unchecked")
        Arbitrary<String> cell = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, wildcard),
                net.jqwik.api.Tuple.of(5, single),
                net.jqwik.api.Tuple.of(1, pair));
        return Combinators.combine(
                cell.list().ofSize(columnCount),
                Arbitraries.of(OUT_VALUES),
                Arbitraries.integers().between(1, 9)).as(RuleRow::new);
    }

    private static Arbitrary<List<String>> input(int columnCount, List<String> domain) {
        var values = new ArrayList<String>(domain);
        values.add(UNKNOWN_VALUE);
        values.add("");   // stands for null / missing
        return Arbitraries.of(values).list().ofSize(columnCount);
    }

    private static DecisionTableScenario build(List<String> columns, List<RuleRow> ruleRows, List<List<String>> inputRows) {
        var rules = new ArrayList<Praeceptum>();
        for (int r = 0; r < ruleRows.size(); r++) {
            var row = ruleRows.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                cells.put(columns.get(c), row.cells().get(c));
            }
            var outputs = Map.of(ID, String.valueOf(r + 1), OUT, row.out(), NUMBER, String.valueOf(row.number()));
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(outputs)));
        }
        var inputs = new ArrayList<Map<String, String>>();
        for (var row : inputRows) {
            var input = new HashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                var value = row.get(c);
                input.put(columns.get(c), value.isEmpty() ? null : value);
            }
            inputs.add(input);
        }
        return new DecisionTableScenario(List.copyOf(columns), List.copyOf(rules), List.copyOf(inputs));
    }
}
