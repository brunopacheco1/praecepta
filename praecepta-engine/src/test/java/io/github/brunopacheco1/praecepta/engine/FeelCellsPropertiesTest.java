/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * The two ways a decision table applies an input entry agree: the bitset index, over the simple unary tests
 * {@code CellParser} reads, and the FEEL language ({@link FeelUnaryTests}), which applies any unary tests (DMN 1.5,
 * 10.3.2.10 and Table 55). So a simple cell means the same wherever it is evaluated, and a table whose cells are
 * literals evaluates as FEEL defines, missing values and {@code not(...)} included.
 */
class FeelCellsPropertiesTest {

    private static final int TRIES = 2000;
    private static final FeelExpression.Scope NO_NAMES = FeelExpression.scope(List.of(), Map.of());

    @Provide
    Arbitrary<String> numberCells() {
        Arbitrary<Integer> number = Arbitraries.integers().between(-3, 3);
        Arbitrary<String> test = Arbitraries.oneOf(
                number.map(String::valueOf),
                Combinators.combine(Arbitraries.of("<", "<=", ">", ">=", "=", "!="), number).as((operator, n) -> operator + " " + n),
                Combinators.combine(Arbitraries.of("[", "(", "]"), number, Arbitraries.integers().between(0, 3), Arbitraries.of("]", ")", "["))
                        .as((open, low, width, close) -> open + low + ".." + (low + width) + close),
                Arbitraries.of("null", "= null", "!= null"));
        Arbitrary<String> tests = test.list().ofMinSize(1).ofMaxSize(3).map(parts -> String.join(", ", parts));
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just("-")),
                Tuple.of(6, tests),
                Tuple.of(3, tests.map(inner -> "not(" + inner + ")")));
    }

    @Provide
    Arbitrary<BigDecimal> numbers() {
        return Arbitraries.oneOf(Arbitraries.integers().between(-4, 7).map(BigDecimal::valueOf),
                Arbitraries.of(new BigDecimal("0.5"), new BigDecimal("2.5")), Arbitraries.just(null));
    }

    @Property(tries = TRIES)
    void a_number_cell_matches_as_feel_applies_it(@ForAll("numberCells") String cell, @ForAll("numbers") BigDecimal value) {
        var feel = FeelUnaryTests.parse(cell, NO_NAMES);
        assertThat(feel.isSimple()).as(cell).isTrue();
        assertThat(indexMatches(cell, ColumnType.NUMBER, value == null ? null : FeelLiterals.text(value)))
                .as("%s with %s", cell, value).isEqualTo(feel.matches(value, name -> null, null));
    }

    @Provide
    Arbitrary<String> stringCells() {
        Arbitrary<String> word = Arbitraries.of("\"a\"", "\"b\"", "\"c\"");
        Arbitrary<String> test = Arbitraries.oneOf(word,
                Combinators.combine(Arbitraries.of("<", "<=", ">", ">=", "=", "!="), word).as((operator, w) -> operator + " " + w),
                Arbitraries.of("[\"a\"..\"b\"]", "(\"a\"..\"c\"]", "null", "!= null"));
        Arbitrary<String> tests = test.list().ofMinSize(1).ofMaxSize(3).map(parts -> String.join(", ", parts));
        return Arbitraries.oneOf(tests, tests.map(inner -> "not(" + inner + ")"));
    }

    @Property(tries = TRIES)
    void a_string_cell_matches_as_feel_applies_it(@ForAll("stringCells") String cell, @ForAll("strings") String value) {
        var feel = FeelUnaryTests.parse(cell, NO_NAMES);
        assertThat(indexMatches(cell, ColumnType.STRING, value)).as("%s with %s", cell, value)
                .isEqualTo(feel.matches(value, name -> null, null));
    }

    @Provide
    Arbitrary<String> strings() {
        return Arbitraries.of("a", "b", "bb", "c", "d", null);
    }

    // ---- a table mixing simple and FEEL cells ---------------------------------------------------------------------------

    /**
     * The literals of a column type, in increasing order, and the values (as text, as map-based inputs read them) an input
     * of it takes: some equal to a literal, some between or beyond them, and a missing one.
     */
    private record Kind(ColumnType type, List<String> literals, List<String> values, boolean ordered) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind(ColumnType.NUMBER, List.of("-1", "0", "1", "2"), List.of("-2", "-1", "0", "0.5", "1", "2", "3"), true),
            new Kind(ColumnType.STRING, List.of("\"a\"", "\"b\"", "\"c\""), List.of("a", "b", "bb", "c", "d"), true),
            new Kind(ColumnType.DATE, List.of("@\"2020-01-02\"", "@\"2020-01-03\"", "@\"2020-01-04\""),
                    List.of("2020-01-01", "2020-01-02", "2020-01-03", "2020-01-04", "2020-01-05"), true),
            new Kind(ColumnType.TIME, List.of("@\"10:00:02\"", "@\"10:00:03\"", "@\"10:00:04\""),
                    List.of("10:00:01", "10:00:02", "10:00:03", "10:00:04", "10:00:05"), true),
            new Kind(ColumnType.DATE_TIME, List.of("@\"2020-01-01T10:00:02\"", "@\"2020-01-01T10:00:03\"", "@\"2020-01-01T10:00:04\""),
                    List.of("2020-01-01T10:00:01", "2020-01-01T10:00:02", "2020-01-01T10:00:03", "2020-01-01T10:00:05"), true),
            new Kind(ColumnType.DAY_TIME_DURATION, List.of("@\"PT2S\"", "@\"PT3S\"", "@\"PT4S\""), List.of("PT1S", "PT2S", "PT3S", "PT5S"), true),
            new Kind(ColumnType.YEAR_MONTH_DURATION, List.of("@\"P2M\"", "@\"P3M\"", "@\"P4M\""), List.of("P1M", "P2M", "P3M", "P5M"), true),
            new Kind(ColumnType.BOOLEAN, List.of("true", "false"), List.of("true", "false"), false));

    /** A FEEL test that never holds, nor is null, so a list of tests holds as it did without it: it makes a cell FEEL. */
    private static final String NEVER = "? = null and false";

    /** A cell of a column: its tests, simple, and whether to make it FEEL ({@link #NEVER}, or a number literal plus zero). */
    private record Cell(List<String> tests, boolean negated, boolean feel) {

        String simple() {
            return tests.isEmpty() ? "-" : wrap(String.join(", ", tests));
        }

        String feel(Kind kind) {
            if (tests.isEmpty() || !feel) {
                return simple();
            }
            if (kind.type() == ColumnType.NUMBER) {
                return wrap(tests.stream().map(test -> test.replaceAll("(?<![\\d.])(-?\\d+)(?![\\d.])", "$1 + 0")).reduce((a, b) -> a + ", " + b)
                        .orElseThrow());
            }
            return wrap(String.join(", ", tests) + ", " + NEVER);
        }

        private String wrap(String inner) {
            return negated ? "not(" + inner + ")" : inner;
        }
    }

    private static Arbitrary<String> test(Kind kind) {
        var literal = Arbitraries.of(kind.literals());
        if (!kind.ordered()) {
            return Arbitraries.oneOf(literal, Combinators.combine(Arbitraries.of("= ", "!= "), literal).as(String::concat),
                    Arbitraries.just("null"));
        }
        var size = kind.literals().size();
        Arbitrary<String> interval = Combinators.combine(Arbitraries.of("[", "("), Arbitraries.integers().between(0, size - 1),
                        Arbitraries.integers().between(0, size - 1), Arbitraries.of("]", ")"))
                .as((open, a, b, close) -> open + kind.literals().get(Math.min(a, b)) + ".." + kind.literals().get(Math.max(a, b)) + close);
        return Arbitraries.oneOf(literal, Combinators.combine(Arbitraries.of("< ", "<= ", "> ", ">= ", "= ", "!= "), literal)
                .as(String::concat), interval, Arbitraries.just("null"));
    }

    private static Arbitrary<Cell> cell(Kind kind) {
        var tests = test(kind).list().ofMinSize(1).ofMaxSize(2);
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(new Cell(List.of(), false, false))),
                Tuple.of(5, Combinators.combine(tests, Arbitraries.of(false, false, true), Arbitraries.of(false, true))
                        .as(Cell::new)));
    }

    /** A random table: its hit policy, two columns, whether each is computed in the mixed table, and its rules' cells. */
    private record Scenario(HitPolicy hitPolicy, List<Kind> kinds, List<Boolean> computed, List<List<Cell>> rules,
            List<String> outputs, List<Map<String, String>> inputs) {
    }

    @Provide
    Arbitrary<Scenario> mixedTables() {
        return Combinators.combine(Arbitraries.of(HitPolicy.FIRST, HitPolicy.UNIQUE, HitPolicy.RULE_ORDER, HitPolicy.ANY, HitPolicy.COLLECT),
                        Arbitraries.of(KINDS).list().ofSize(2), Arbitraries.of(false, false, true).list().ofSize(2),
                        Arbitraries.integers().between(2, 5), Arbitraries.longs())
                .flatAs((policy, kinds, computed, ruleCount, seed) -> {
                    var rules = Combinators.combine(cell(kinds.get(0)), cell(kinds.get(1))).as(List::of).list().ofSize(ruleCount);
                    var outputs = Arbitraries.of("p", "q", "r").list().ofSize(ruleCount);
                    var inputs = Combinators.combine(value(kinds.get(0)), value(kinds.get(1)))
                            .as((x, y) -> {
                                var input = new HashMap<String, String>();
                                input.put("x", x);
                                input.put("y", y);
                                return (Map<String, String>) input;
                            }).list().ofSize(8);
                    return Combinators.combine(rules, outputs, inputs).as((r, o, i) -> new Scenario(policy, kinds, computed, r, o, i));
                });
    }

    private static Arbitrary<String> value(Kind kind) {
        return Arbitraries.frequencyOf(Tuple.of(6, Arbitraries.of(kind.values())), Tuple.of(1, Arbitraries.just(null)));
    }

    /**
     * A table whose cells are simple evaluates as the same table where some of them are FEEL beyond the simple unary tests,
     * and where some columns are computed by an input expression ({@code [x][1]}), and the analysis of the
     * second finds no issue the first does not: it leaves out the rules with FEEL cells, or with cells on computed columns,
     * rather than guess.
     */
    @Property(tries = 500)
    void a_table_mixing_simple_and_feel_cells_evaluates_and_is_analysed_as_feel_alone(@ForAll("mixedTables") Scenario scenario) {
        var simple = engine(scenario, false);
        var mixed = engine(scenario, true);
        for (var input : scenario.inputs()) {
            assertThat(outcome(mixed, input)).as("%s with %s", scenario, input).isEqualTo(outcome(simple, input));
        }
        var simpleIssues = simple.validationReport().issues().stream().map(issue -> issue.kind() + " " + issue.ruleIds()).toList();
        var mixedIssues = mixed.validationReport().issues().stream().map(issue -> issue.kind() + " " + issue.ruleIds()).toList();
        assertThat(simpleIssues).as("%s", scenario).containsAll(mixedIssues);
    }

    /** The outputs an engine gives the input, or the rules a hit policy violation names. */
    private static Object outcome(PraeceptaEngine<Map<String, String>, Map<String, String>> engine, Map<String, String> input) {
        try {
            return engine.evaluate(input);
        } catch (io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException e) {
            return "violation " + e.getRuleIds();
        }
    }

    private static PraeceptaEngine<Map<String, String>, Map<String, String>> engine(Scenario scenario, boolean mixed) {
        var names = List.of("x", "y");
        var columns = new java.util.ArrayList<DecisionTable.InputColumn>();
        for (int c = 0; c < 2; c++) {
            var name = mixed && scenario.computed().get(c) ? "[%s][1]".formatted(names.get(c)) : names.get(c);
            columns.add(new DecisionTable.InputColumn(name, scenario.kinds().get(c).type()));
        }
        var rules = new java.util.ArrayList<Praeceptum>();
        for (int r = 0; r < scenario.rules().size(); r++) {
            var cells = new HashMap<String, String>();
            for (int c = 0; c < 2; c++) {
                var cell = scenario.rules().get(r).get(c);
                cells.put(columns.get(c).name(), mixed ? cell.feel(scenario.kinds().get(c)) : cell.simple());
            }
            rules.add(new Praeceptum(r + 1, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("o", scenario.outputs().get(r)))));
        }
        var table = new DecisionTable("t", scenario.hitPolicy(), null, columns, List.of(new DecisionTable.OutputColumn("o", ColumnType.STRING)),
                rules, true);
        var builder = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values).logValidationReport(false);
        // a computed column reads its input as a value of the column's type
        for (int c = 0; c < 2; c++) {
            builder.columnType(names.get(c), scenario.kinds().get(c).type());
        }
        return builder.build();
    }

    /** Whether a one-rule table of the cell, in strict FEEL, matches the value, as the bitset index decides it. */
    private static boolean indexMatches(String cell, ColumnType type, String value) {
        var rule = new Praeceptum(1, new PraeceptaInput(Map.of("x", cell)), new PraeceptaOutput(Map.of("o", "yes")));
        var table = new DecisionTable("t", HitPolicy.FIRST, null,
                List.of(new DecisionTable.InputColumn("x", type)),
                List.of(new DecisionTable.OutputColumn("o", ColumnType.STRING)),
                List.of(rule), true);
        var engine = PraeceptaEngine.<Map<String, String>, Map<String, String>>builder().table(table).inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values).logValidationReport(false).build();
        var input = new HashMap<String, String>();
        input.put("x", value);
        return !engine.evaluate(input).isEmpty();
    }
}
