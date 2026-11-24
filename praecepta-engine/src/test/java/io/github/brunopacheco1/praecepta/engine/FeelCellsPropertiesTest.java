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
