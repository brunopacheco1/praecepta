package io.github.brunopacheco1.praecepta.engine.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * A random decision table plus inputs to evaluate against it. Columns are STRING, NUMBER or DATE, and
 * cells use every form of the grammar: wildcards, literals (quoted and legacy unquoted), lists,
 * comparisons, intervals with every bracket style, {@code not(...)} and {@code null}. Values come from
 * small domains, and dates are consecutive days, so overlaps, boundaries and empty gaps between days
 * are frequent. Some columns declare input values, and inputs include values outside them.
 *
 * <p>Every rule has outputs {@code id} (its 1-based position, unique), {@code out} (one of x/y/z,
 * often shared) and {@code n} (a small number, for aggregation).
 */
public record DecisionTableScenario(List<String> columns, Map<String, ColumnType> types, Map<String, String> allowedValues,
        List<Praeceptum> rules, List<Map<String, Object>> inputs) {

    public static final String ID = "id";
    public static final String OUT = "out";
    public static final String NUMBER = "n";
    public static final List<String> OUT_VALUES = List.of("x", "y", "z");

    static final LocalDate DAY0 = LocalDate.of(2025, 1, 1);
    private static final List<String> LETTERS = List.of("a", "b", "c", "d");

    /** Column definition: type, string domain, and declared input values (null if none). */
    private record Column(ColumnType type, List<String> domain, String allowed) {
    }

    public static Arbitrary<DecisionTableScenario> arbitrary() {
        return scenarios(false);
    }

    /** Scenarios where every string column declares its input values, so gaps are analysed. */
    public static Arbitrary<DecisionTableScenario> withStringValueLists() {
        return scenarios(true);
    }

    private static Arbitrary<DecisionTableScenario> scenarios(boolean allStringsDeclared) {
        return column(allStringsDeclared).list().ofMinSize(1).ofMaxSize(3).flatMap(columns -> {
            var rules = rule(columns).list().ofMinSize(1).ofMaxSize(8);
            var inputs = input(columns).list().ofMinSize(1).ofMaxSize(12);
            return Combinators.combine(rules, inputs).as((ruleRows, inputRows) -> build(columns, ruleRows, inputRows));
        });
    }

    private static Arbitrary<Column> column(boolean declareStrings) {
        var string = Arbitraries.integers().between(2, 4).flatMap(size -> {
            var domain = LETTERS.subList(0, size);
            var declared = Arbitraries.of(domain).set().ofMinSize(1).ofMaxSize(size)
                    .map(values -> String.join(",", values.stream().sorted().map(v -> "\"" + v + "\"").toList()));
            var maybe = declareStrings ? declared : Arbitraries.oneOf(declared, Arbitraries.just((String) null), Arbitraries.just((String) null));
            return maybe.map(allowed -> new Column(ColumnType.STRING, domain, allowed));
        });
        var number = Arbitraries.oneOf(Arbitraries.just((String) null), Arbitraries.just((String) null), interval(ColumnType.NUMBER))
                .map(allowed -> new Column(ColumnType.NUMBER, List.of(), allowed));
        var date = Arbitraries.oneOf(Arbitraries.just((String) null), Arbitraries.just((String) null), interval(ColumnType.DATE))
                .map(allowed -> new Column(ColumnType.DATE, List.of(), allowed));
        return Arbitraries.frequencyOf(Tuple.of(2, string), Tuple.of(1, number), Tuple.of(1, date));
    }

    private record RuleRow(List<String> cells, String out, int number) {
    }

    private static Arbitrary<RuleRow> rule(List<Column> columns) {
        var cells = new ArrayList<Arbitrary<String>>();
        columns.forEach(column -> cells.add(cell(column)));
        return Combinators.combine(Combinators.combine(cells).as(List::copyOf), Arbitraries.of(OUT_VALUES), Arbitraries.integers().between(1, 9))
                .as(RuleRow::new);
    }

    @SuppressWarnings("unchecked")
    private static Arbitrary<String> cell(Column column) {
        Arbitrary<String> single = literal(column);
        Arbitrary<String> list = Arbitraries.oneOf(single, test(column)).list().ofMinSize(2).ofMaxSize(3).map(parts -> String.join(", ", parts));
        Arbitrary<String> negated = Arbitraries.oneOf(single, list, test(column)).map(inner -> "not(" + inner + ")");
        return Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.just("")),
                Tuple.of(1, Arbitraries.just("-")),
                Tuple.of(4, single),
                Tuple.of(column.type() == ColumnType.STRING ? 0 : 4, test(column)),
                Tuple.of(2, list),
                Tuple.of(1, negated),
                Tuple.of(1, Arbitraries.just("null")),
                Tuple.of(1, single.map(value -> "null, " + value)));
    }

    /** A literal, written in every accepted style. */
    private static Arbitrary<String> literal(Column column) {
        return switch (column.type()) {
            case STRING -> Arbitraries.of(column.domain()).flatMap(v -> Arbitraries.of(v, "\"" + v + "\""));
            case NUMBER -> Arbitraries.integers().between(0, 5).map(String::valueOf);
            case DATE -> Arbitraries.integers().between(0, 5).flatMap(d -> Arbitraries.of(
                    "date(\"" + DAY0.plusDays(d) + "\")", DAY0.plusDays(d).toString()));
        };
    }

    /** A comparison or an interval (NUMBER and DATE columns). */
    private static Arbitrary<String> test(Column column) {
        if (column.type() == ColumnType.STRING) {
            return literal(column);
        }
        var comparison = Combinators.combine(Arbitraries.of("<", "<=", ">", ">="), literal(column)).as((op, v) -> op + " " + v);
        return Arbitraries.oneOf(comparison, interval(column.type()));
    }

    private static Arbitrary<String> interval(ColumnType type) {
        return Combinators.combine(Arbitraries.integers().between(0, 5), Arbitraries.integers().between(0, 5),
                Arbitraries.of("[", "(", "]"), Arbitraries.of("]", ")", "["))
                .as((a, b, open, close) -> open + value(type, Math.min(a, b)) + ".." + value(type, Math.max(a, b)) + close);
    }

    private static String value(ColumnType type, int i) {
        return type == ColumnType.NUMBER ? String.valueOf(i) : "date(\"" + DAY0.plusDays(i) + "\")";
    }

    private static Arbitrary<List<Object>> input(List<Column> columns) {
        var values = new ArrayList<Arbitrary<Object>>();
        for (var column : columns) {
            values.add(switch (column.type()) {
                case STRING -> {
                    var options = new ArrayList<Object>(column.domain());
                    options.add("q");
                    options.add(" ");
                    yield Arbitraries.of(options).injectNull(0.15);
                }
                case NUMBER -> Arbitraries.integers().between(-2, 12).<Object>map(half -> BigDecimal.valueOf(half).divide(BigDecimal.TWO)).injectNull(0.15);
                case DATE -> Arbitraries.integers().between(-1, 6).<Object>map(DAY0::plusDays).injectNull(0.15);
            });
        }
        return Combinators.combine(values).as(row -> new ArrayList<>(row));
    }

    private static DecisionTableScenario build(List<Column> columns, List<RuleRow> ruleRows, List<List<Object>> inputRows) {
        var names = new ArrayList<String>();
        var types = new LinkedHashMap<String, ColumnType>();
        var allowed = new LinkedHashMap<String, String>();
        for (int c = 0; c < columns.size(); c++) {
            var name = "c" + c;
            names.add(name);
            types.put(name, columns.get(c).type());
            if (columns.get(c).allowed() != null) {
                allowed.put(name, columns.get(c).allowed());
            }
        }
        var rules = new ArrayList<Praeceptum>();
        for (int r = 0; r < ruleRows.size(); r++) {
            var row = ruleRows.get(r);
            var cells = new HashMap<String, String>();
            for (int c = 0; c < names.size(); c++) {
                cells.put(names.get(c), row.cells().get(c));
            }
            var outputs = Map.of(ID, String.valueOf(r + 1), OUT, row.out(), NUMBER, String.valueOf(row.number()));
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(outputs)));
        }
        var inputs = new ArrayList<Map<String, Object>>();
        for (var row : inputRows) {
            var input = new HashMap<String, Object>();
            for (int c = 0; c < names.size(); c++) {
                input.put(names.get(c), row.get(c));
            }
            inputs.add(input);
        }
        return new DecisionTableScenario(List.copyOf(names), Map.copyOf(types), Map.copyOf(allowed), List.copyOf(rules), List.copyOf(inputs));
    }

    public ReferenceMatcher reference(List<Praeceptum> tableRules, io.github.brunopacheco1.praecepta.engine.beans.HitPolicy policy,
            Map<String, List<String>> outputPriority) {
        return new ReferenceMatcher(tableRules, policy, outputPriority, types, allowedValues);
    }

    public List<String> ids(List<PraeceptaOutput> outputs) {
        return outputs.stream().map(output -> output.values().get(ID)).toList();
    }

    public List<String> ruleIds(List<Praeceptum> matches) {
        return ids(matches.stream().map(Praeceptum::output).toList());
    }

    /** The input as text, for map-based binding: numbers in plain notation, dates in ISO format. */
    public static Map<String, String> asText(Map<String, Object> input) {
        var text = new HashMap<String, String>();
        input.forEach((column, value) -> text.put(column, switch (value) {
            case null -> null;
            case BigDecimal decimal -> decimal.toPlainString();
            default -> value.toString();
        }));
        return text;
    }
}
