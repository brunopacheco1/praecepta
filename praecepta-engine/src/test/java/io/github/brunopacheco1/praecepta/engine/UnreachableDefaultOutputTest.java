/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * A default output on a complete table (#91). DMN 1.5, 10.3.2.10: "A complete decision table SHALL NOT specify a
 * default output value"; a table is complete when "its rules cover all combinations of expected input values" (8.2.4).
 * The engine accepts a missing value in every column, declared input values or not, and {@code -} matches it, so
 * the search counts a missing value as an input: a table whose rules cover every declared value, but not a missing
 * one, still gives its default for a missing input, and is not reported.
 */
class UnreachableDefaultOutputTest {

    private static final Issue UNREACHABLE_DEFAULT = new Issue(Kind.UNREACHABLE_DEFAULT_OUTPUT, List.of());

    @Test
    void a_rule_that_matches_every_input_makes_the_default_unreachable_an_error() {
        var engine = engine(HitPolicy.FIRST, Map.of(), rule(1, "risk", "\"High\""), rule(2, "risk", "-")).build();
        Assertions.assertThat(engine.validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
        Assertions.assertThat(UNREACHABLE_DEFAULT.severity()).isEqualTo(Severity.ERROR);
        Assertions.assertThat(UNREACHABLE_DEFAULT).hasToString("ERROR every input matches a rule, so the default output is never "
                + "given (a complete table declares no default output: DMN 1.5, 10.3.2.10)");
        var strict = engine(HitPolicy.FIRST, Map.of(), rule(1, "risk", "-")).failOn(Severity.ERROR);
        Assertions.assertThatThrownBy(strict::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("the default output is never given");
    }

    /** Whatever the hit policy: the default applies before it (10.3.2.10, step 2). */
    @ParameterizedTest
    @EnumSource(HitPolicy.class)
    void every_hit_policy_reports_it(HitPolicy policy) {
        var builder = engine(policy, Map.of(), rule(1, "risk", "-"));
        if (policy == HitPolicy.PRIORITY || policy == HitPolicy.OUTPUT_ORDER) {
            builder.outputPriority("rating", List.of("A", "B", "C"));
        }
        Assertions.assertThat(builder.build().validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).containsExactly(UNREACHABLE_DEFAULT);
    }

    @Test
    void a_table_without_a_default_output_is_never_reported() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .input("risk", input -> input.get("risk"))
                .output(output -> output.values().get("rating"))
                .rules(List.of(rule(1, "risk", "-")))
                .build();
        Assertions.assertThat(engine.validationReport().isEmpty()).isTrue();
    }

    /** Every declared value is covered, but a missing value is not: the default is what a missing risk gives. */
    @Test
    void a_missing_value_reaches_the_default_where_declared_values_do_not() {
        var declared = Map.of("risk", "\"High\",\"Low\",\"Medium\"");
        var rules = new Praeceptum[] {rule(1, "risk", "\"High\""), rule(2, "risk", "\"Low\",\"Medium\"")};
        var engine = engine(HitPolicy.UNIQUE, declared, rules).build();
        Assertions.assertThat(engine.validationReport().issues()).isEmpty();
        Assertions.assertThat(engine.evaluate(values("risk", null))).containsExactly("C");

        var withNull = Arrays.copyOf(rules, 3);
        withNull[2] = rule(3, "risk", "null");
        Assertions.assertThat(engine(HitPolicy.UNIQUE, declared, withNull).build().validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
    }

    /** A string no rule mentions is an input too, where the column declares no values: here {@code "ES"}. */
    @Test
    void a_string_no_rule_mentions_reaches_the_default_unless_a_rule_takes_every_other_value() {
        var mentioned = engine(HitPolicy.FIRST, Map.of(), rule(1, "risk", "\"High\""), rule(2, "risk", "null")).build();
        Assertions.assertThat(mentioned.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).isEmpty();
        Assertions.assertThat(mentioned.evaluate(values("risk", "ES"))).containsExactly("C");
        // not("High") takes every other string, and a missing one, which is not "High" (FEEL: not(null = "High"))
        var negated = engine(HitPolicy.FIRST, Map.of(), rule(1, "risk", "\"High\""), rule(2, "risk", "not(\"High\")")).build();
        Assertions.assertThat(negated.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).containsExactly(UNREACHABLE_DEFAULT);
    }

    /** Numbers: {@code < 18} and {@code >= 18} cover every number, and a missing age fails both. */
    @Test
    void comparisons_cover_every_number_but_not_a_missing_one() {
        var split = engine(HitPolicy.UNIQUE, Map.of(), Map.of("age", ColumnType.NUMBER),
                rule(1, "age", "< 18"), rule(2, "age", ">= 18"));
        Assertions.assertThat(split.build().validationReport().issues()).isEmpty();
        var withNull = engine(HitPolicy.UNIQUE, Map.of(), Map.of("age", ColumnType.NUMBER),
                rule(1, "age", "< 18"), rule(2, "age", ">= 18"), rule(3, "age", "null"));
        Assertions.assertThat(withNull.build().validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
        // a hole at 18
        var hole = engine(HitPolicy.UNIQUE, Map.of(), Map.of("age", ColumnType.NUMBER),
                rule(1, "age", "< 18"), rule(2, "age", "> 18"), rule(3, "age", "null"));
        Assertions.assertThat(hole.build().validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).isEmpty();
    }

    /** Both booleans and a missing one, across two columns: complete only when every combination is matched. */
    @Test
    void every_combination_of_classes_must_be_matched() {
        var types = Map.of("vip", ColumnType.BOOLEAN, "age", ColumnType.NUMBER);
        var rules = new Praeceptum[] {
            rule(1, Map.of("vip", "true", "age", "-")),
            rule(2, Map.of("vip", "false", "age", "< 65")),
            rule(3, Map.of("vip", "null", "age", "-")),
        };
        // not a VIP, aged 65 or more, or of no known age: no rule
        var engine = engine(HitPolicy.FIRST, Map.of(), types, rules).build();
        Assertions.assertThat(engine.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).isEmpty();
        Assertions.assertThat(engine.evaluate(Map.of("vip", "false"))).containsExactly("C");
        var older = Arrays.copyOf(rules, 4);
        older[3] = rule(4, Map.of("vip", "false", "age", ">= 65"));
        Assertions.assertThat(engine(HitPolicy.FIRST, Map.of(), types, older).build().validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT))
                .as("not a VIP and of no known age: still the default").isEmpty();
        var covering = Arrays.copyOf(older, 5);
        covering[4] = rule(5, Map.of("vip", "false", "age", "null"));
        Assertions.assertThat(engine(HitPolicy.FIRST, Map.of(), types, covering).build().validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT))
                .containsExactly(UNREACHABLE_DEFAULT);
    }

    /**
     * A rule whose cell compares with another input matches inputs known only when evaluating: the search leaves it
     * out, as the other analyses do, so a table is reported only when the other rules cover every input.
     */
    @Test
    void rules_that_compare_with_another_input_are_left_out() {
        var comparing = limits(rule(1, "amount", "<= limit"), rule(2, "amount", "> limit"));
        Assertions.assertThat(comparing.validationReport().issues()).isEmpty();
        Assertions.assertThat(comparing.evaluate(Map.of("amount", "5"))).as("no limit: neither rule matches").containsExactly("C");
        var withAny = limits(rule(1, "amount", "<= limit"), rule(2, "amount", "-"));
        Assertions.assertThat(withAny.validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
    }

    /**
     * A list column is searched as every list it takes (#78 with #91): a missing one, the empty one and ones holding
     * missing elements included. {@code "PT"} and {@code not("PT")} cover every list that is there, but not a missing
     * one, which only {@code null} (Praecepta's {@code ? = null}) matches.
     */
    @Test
    void a_list_column_is_complete_only_when_every_list_a_missing_one_included_matches_a_rule() {
        var lists = engine(HitPolicy.FIRST, Map.of(), rule(1, "nationalities", "\"PT\""), rule(2, "nationalities", "not(\"PT\")"))
                .listColumnType("nationalities", ColumnType.STRING)
                .build();
        Assertions.assertThat(lists.validationReport().issues()).isEmpty();
        Assertions.assertThat(lists.evaluate(values("nationalities", null))).containsExactly("C");
        Assertions.assertThat(lists.evaluate(Map.of("nationalities", "[]"))).containsExactly("B");
        var withNull = engine(HitPolicy.FIRST, Map.of(), rule(1, "nationalities", "\"PT\""), rule(2, "nationalities", "not(\"PT\")"),
                rule(3, "nationalities", "null"))
                .listColumnType("nationalities", ColumnType.STRING)
                .build();
        Assertions.assertThat(withNull.validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
    }

    /**
     * A list holding a missing element ({@code [10, null]}) leaves {@code some x in ? satisfies x >= 18} and its
     * {@code not(...)} both unknown (#84), so it gives the default, until a rule asks for such an element with
     * {@code list contains(?, null)}.
     */
    @Test
    void a_list_holding_a_missing_element_reaches_the_default_until_a_rule_asks_for_one() {
        var ages = List.of(listRule(1, "some x in ? satisfies x >= 18"), listRule(2, "not(some x in ? satisfies x >= 18)"),
                listRule(3, "? = null"));
        var unknown = lists("ages", ColumnType.NUMBER, ages);
        Assertions.assertThat(unknown.validationReport().issues()).isEmpty();
        Assertions.assertThat(unknown.evaluate(Map.of("ages", "[10, null]"))).containsExactly("C");
        var withNullElement = new ArrayList<>(ages);
        withNullElement.add(listRule(4, "list contains(?, null)"));
        Assertions.assertThat(lists("ages", ColumnType.NUMBER, withNullElement).validationReport().issues()).containsExactly(UNREACHABLE_DEFAULT);
    }

    /**
     * Map-based inputs read a time with a zone id even in a column whose cells name none (#82), and only the cells
     * that accept any value ({@code -}, {@code != null}, {@code not(null)}) match it. The other analyses leave such a
     * time out, but it reaches the default, so the table is not complete without such a cell.
     */
    @Test
    void a_time_with_a_zone_id_reaches_the_default_where_the_cells_name_no_zone_id() {
        var types = Map.of("at", ColumnType.TIME);
        var halves = engine(HitPolicy.FIRST, Map.of(), types, rule(1, "at", "< 12:00:00"), rule(2, "at", ">= 12:00:00"), rule(3, "at", "null"))
                .build();
        Assertions.assertThat(halves.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).isEmpty();
        Assertions.assertThat(halves.evaluate(Map.of("at", "10:30:00@Europe/Paris"))).containsExactly("C");
        var anyTime = engine(HitPolicy.FIRST, Map.of(), types, rule(1, "at", "< 12:00:00"), rule(2, "at", ">= 12:00:00"),
                rule(3, "at", "null"), rule(4, "at", "!= null")).build();
        Assertions.assertThat(anyTime.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).containsExactly(UNREACHABLE_DEFAULT);
        // in a list, such an element is unknown to every comparison, and != null holds for it
        var slots = List.of(listRule(1, "some x in ? satisfies x < time(\"12:00:00\")"),
                listRule(2, "not(some x in ? satisfies x < time(\"12:00:00\"))"), listRule(3, "? = null"), listRule(4, "list contains(?, null)"));
        var timeLists = lists("slots", ColumnType.TIME, slots);
        Assertions.assertThat(timeLists.validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).isEmpty();
        Assertions.assertThat(timeLists.evaluate(Map.of("slots", "[10:30:00@Europe/Paris]"))).containsExactly("C");
        var withAnyElement = new ArrayList<>(slots);
        withAnyElement.add(listRule(5, "some x in ? satisfies x != null"));
        Assertions.assertThat(lists("slots", ColumnType.TIME, withAnyElement).validationReport().issues(Kind.UNREACHABLE_DEFAULT_OUTPUT))
                .containsExactly(UNREACHABLE_DEFAULT);
    }

    /** A DMN table (strict FEEL) of FIRST rules over one list column, whose cells the rules hold under {@code "list"}; default C. */
    private static PraeceptaEngine<Map<String, String>, String> lists(String column, ColumnType type, List<Praeceptum> rules) {
        var renamed = rules.stream()
                .map(rule -> new Praeceptum(rule.priority(), new PraeceptaInput(Map.of(column, rule.input().values().get("list"))), rule.output()))
                .toList();
        var table = new DecisionTable("lists", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn(column, type)),
                List.of(new DecisionTable.OutputColumn("rating", ColumnType.STRING, List.of(), "C")), renamed, true, Set.of(column));
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("rating"))
                .build();
    }

    private static Praeceptum listRule(int id, String cell) {
        return rule(id, "list", cell);
    }

    /** A DMN table (strict FEEL) whose number column {@code amount} may compare with the input {@code limit}; default C. */
    private static PraeceptaEngine<Map<String, String>, String> limits(Praeceptum... rules) {
        var table = new DecisionTable("limits", HitPolicy.FIRST, null, List.of(new DecisionTable.InputColumn("amount", ColumnType.NUMBER)),
                List.of(new DecisionTable.OutputColumn("rating", ColumnType.STRING, List.of(), "C")), List.of(rules), true);
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("rating"))
                .build();
    }

    private static PraeceptaEngine.Builder<Map<String, String>, String> engine(HitPolicy policy, Map<String, String> allowed,
            Praeceptum... rules) {
        return engine(policy, allowed, Map.of(), rules);
    }

    /** A map-based engine with the output {@code rating}, whose default is {@code C}. */
    private static PraeceptaEngine.Builder<Map<String, String>, String> engine(HitPolicy policy, Map<String, String> allowed,
            Map<String, ColumnType> types, Praeceptum... rules) {
        var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("rating"))
                .defaultOutput(new PraeceptaOutput(Map.of("rating", "C")))
                .rules(List.of(rules));
        types.forEach(builder::columnType);
        allowed.forEach(builder::allowedValues);
        return builder;
    }

    private static Praeceptum rule(int id, String column, String cell) {
        return rule(id, Map.of(column, cell));
    }

    private static Praeceptum rule(int id, Map<String, String> cells) {
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("rating", id == 1 ? "A" : "B")));
    }

    private static Map<String, String> values(String column, String value) {
        var values = new HashMap<String, String>();
        values.put(column, value);
        return values;
    }
}
