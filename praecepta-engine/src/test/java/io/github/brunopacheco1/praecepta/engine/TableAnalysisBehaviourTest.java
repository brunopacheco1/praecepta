/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Pins the validation reports {@link TableAnalysis} produces: which issues, in which order, with
 * which text and gap examples.
 */
class TableAnalysisBehaviourTest {

    /** Column types by name; any other column holds strings. */
    private static final Map<String, ColumnType> TYPES = Map.of("n", ColumnType.NUMBER, "m", ColumnType.NUMBER, "d", ColumnType.DATE);

    @Test
    void unique_reports_every_overlapping_pair_in_rule_order() {
        var rules = List.of(
                rule(30, "out", "a", "x", "\"P\",\"Q\"", "n", "< 10"),
                rule(10, "out", "b", "x", "not(\"P\")", "n", "[5..20]"),
                rule(20, "out", "c", "x", "-", "n", "null"),
                rule(40, "out", "d", "x", "null", "n", "-"));
        var report = engine(HitPolicy.UNIQUE, rules, Map.of()).validationReport();
        Assertions.assertThat(report).hasToString("""
                ERROR rules [30, 10] both match some inputs (UNIQUE)
                ERROR rules [10, 40] both match some inputs (UNIQUE)
                ERROR rules [20, 40] both match some inputs (UNIQUE)""");
    }

    @Test
    void any_reports_only_overlapping_pairs_with_different_outputs() {
        var rules = List.of(
                rule(1, "out", "yes", "x", "\"P\"", "n", ">= 0"),
                rule(2, "out", "yes", "x", "-", "n", "> 5"),
                rule(3, "out", "no", "x", "not(\"Q\")", "n", "[3..4]"),
                rule(4, "out", "no", "x", "\"Q\"", "n", "-"));
        var report = engine(HitPolicy.ANY, rules, Map.of()).validationReport();
        Assertions.assertThat(report).hasToString("""
                ERROR rules [1, 3] both match some inputs but differ in outputs (ANY)
                ERROR rules [2, 4] both match some inputs but differ in outputs (ANY)""");
    }

    @Test
    void first_reports_rules_covered_by_earlier_ones_and_rules_no_valid_input_reaches() {
        var rules = List.of(
                rule(1, "out", "a", "x", "\"P\"", "n", "< 10"),
                rule(2, "out", "b", "x", "-", "n", ">= 10"),
                rule(3, "out", "c", "x", "\"P\",\"Q\"", "n", "[0..50]"),
                rule(4, "out", "d", "x", "\"Q\"", "n", "< 0"),
                rule(5, "out", "e", "x", "-", "n", "> 200"),
                rule(6, "out", "f", "x", "\"R\"", "n", "[-5..5]"),
                rule(7, "out", "g", "x", "\"P\"", "n", "[5..15]"));
        var allowed = Map.of("x", "\"P\",\"Q\",\"R\"", "n", "[-100..100]");
        var report = engine(HitPolicy.FIRST, rules, allowed).validationReport();
        Assertions.assertThat(report).hasToString("""
                WARNING rule 5 can never be selected
                WARNING rule 7 can never be selected
                WARNING no rule matches n in [-100..-5), x = "R"
                WARNING no rule matches n in (5..10), x = "R\"""");
        Assertions.assertThat(examples(report)).containsExactly("{n=-100, x=R}", "{n=7.5, x=R}");
    }

    @Test
    void priority_judges_reachability_in_priority_order() {
        var rules = List.of(
                rule(1, "level", "low", "x", "-", "n", "-"),
                rule(2, "level", "high", "x", "\"P\"", "n", "> 3"),
                rule(3, "level", "mid", "x", "\"P\"", "n", "> 5"));
        var report = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.PRIORITY)
                .inputs(PraeceptaInput::new)
                .columnType("n", ColumnType.NUMBER)
                .outputPriority("level", List.of("high", "mid", "low"))
                .output(output -> output.values().get("level"))
                .rules(rules)
                .build()
                .validationReport();
        Assertions.assertThat(report).hasToString("""
                WARNING rule 3 can never be selected""");
    }

    @Test
    void gaps_name_the_uncovered_regions_with_an_example() {
        var rules = List.of(
                rule(1, "out", "a", "x", "\"P\"", "n", "[0..10)", "d", ">= date(\"2024-01-01\")"),
                rule(2, "out", "b", "x", "\"Q\",\"R\"", "n", "[0..10]", "d", "-"),
                rule(3, "out", "c", "x", "-", "n", "> 50", "d", "< date(\"2024-01-01\")"));
        var allowed = Map.of("x", "\"P\",\"Q\",\"R\",\"S\"", "n", "[0..100]");
        var report = engine(HitPolicy.FIRST, rules, allowed).validationReport();
        Assertions.assertThat(report).hasToString("""
                WARNING no rule matches n in [0..10), d in (-inf..date("2024-01-01")), x in {"P", "S"}
                WARNING no rule matches n in [0..10), d in [date("2024-01-01")..+inf), x = "S"
                WARNING no rule matches n = 10, x in {"P", "S"}
                WARNING no rule matches n in (10..50]
                WARNING no rule matches n in (50..100], d in [date("2024-01-01")..+inf)""");
        Assertions.assertThat(examples(report)).containsExactly(
                "{n=0, d=2023-12-31, x=P}", "{n=0, d=2024-01-01, x=S}", "{n=10, x=P}", "{n=30.0}", "{n=75.0, d=2024-01-01}");
    }

    @Test
    void a_table_no_valid_input_reaches_has_one_gap_covering_any_input() {
        var rules = List.of(rule(1, "out", "a", "x", "-", "n", "> 200"));
        var report = engine(HitPolicy.UNIQUE, rules, Map.of("n", "[0..100]")).validationReport();
        Assertions.assertThat(report).hasToString("""
                WARNING no rule matches any input""");
        Assertions.assertThat(examples(report)).containsExactly("{}");
    }

    @Test
    void gaps_are_capped() {
        var rules = new ArrayList<Praeceptum>();
        for (int i = 0; i < 120; i++) {
            rules.add(rule(i + 1, "out", "r" + i, "x", "-", "n", String.valueOf(i), "m", String.valueOf(i)));
        }
        var gaps = engine(HitPolicy.UNIQUE, rules, Map.of()).validationReport().issues(Kind.GAP);
        Assertions.assertThat(gaps).hasSize(TableAnalysis.MAX_GAPS);
        // the first column tells every rule apart; every other value of it matches no rule
        Assertions.assertThat(gaps.getFirst().detail()).startsWith("m in {(-inf..0), (0..1), (1..2), ").endsWith(", (119..+inf)}");
        Assertions.assertThat(gaps.subList(1, 3)).extracting(Issue::detail)
                .containsExactly("m = 0, n in {(-inf..0), (0..+inf)}", "m = 1, n in {(-inf..1), (1..+inf)}");
        Assertions.assertThat(gaps.getLast().detail()).isEqualTo("m = 98, n in {(-inf..98), (98..+inf)}");
    }

    // ---- columns that hold lists (#78) ----------------------------------------------------------

    @Test
    void list_rules_overlap_when_some_list_matches_both() {
        var rules = List.of(
                rule(1, "out", "a", "nat", "\"PT\""),
                rule(2, "out", "b", "nat", "\"BR\""),
                rule(3, "out", "c", "nat", "not(\"PT\")"),
                rule(4, "out", "d", "nat", "not(\"BR\")"),
                rule(5, "out", "e", "nat", "null"));
        var report = lists(HitPolicy.UNIQUE, rules, Map.of()).validationReport();
        // ["PT", "BR"], ["PT"], ["BR"] and [] respectively; only a missing list matches null, and nothing else does
        Assertions.assertThat(report).hasToString("""
                ERROR rules [1, 2] both match some inputs (UNIQUE)
                ERROR rules [1, 4] both match some inputs (UNIQUE)
                ERROR rules [2, 3] both match some inputs (UNIQUE)
                ERROR rules [3, 4] both match some inputs (UNIQUE)""");
    }

    @Test
    void a_list_rule_is_unreachable_when_every_list_it_matches_matches_an_earlier_rule() {
        var rules = List.of(
                rule(1, "out", "a", "ages", ">= 18"),
                rule(2, "out", "b", "ages", "not(< 18)"),
                rule(3, "out", "c", "ages", "[20..30]"),
                rule(4, "out", "d", "ages", "-"),
                rule(5, "out", "e", "ages", "< 5"));
        var report = lists(HitPolicy.FIRST, rules, Map.of()).validationReport();
        // a list with an element in [20..30] has one of at least 18; every list matches -; [] reaches rule 2, [10] rule 4
        Assertions.assertThat(report).hasToString("""
                WARNING rule 3 can never be selected
                WARNING rule 5 can never be selected""");
    }

    @Test
    void a_gap_in_a_list_column_is_described_by_the_elements_its_lists_hold() {
        var rules = List.of(
                rule(1, "out", "a", "nat", "\"PT\""),
                rule(2, "out", "b", "nat", "not(\"ES\")"));
        var report = lists(HitPolicy.COLLECT, rules, Map.of("nat", "\"PT\",\"ES\",\"BR\"")).validationReport();
        // lists without "PT" (rule 1) that hold "ES" (rule 2)
        Assertions.assertThat(report).hasToString("WARNING no rule matches nat has no element in \"PT\", nat has an element in \"ES\"");
        Assertions.assertThat(examples(report)).containsExactly("{nat=[ES]}");

        var each = List.of(rule(1, "out", "a", "nat", "\"PT\""), rule(2, "out", "b", "nat", "\"BR\""));
        var empty = lists(HitPolicy.COLLECT, each, Map.of("nat", "\"PT\",\"BR\"")).validationReport();
        Assertions.assertThat(empty).hasToString("WARNING no rule matches nat = []");
        Assertions.assertThat(examples(empty)).containsExactly("{nat=[]}");
    }

    @Test
    void list_columns_are_searched_after_the_columns_of_single_values() {
        // the list column has fewer wildcards, so the index evaluates it first; the analysis fixes x first
        var rules = List.of(
                rule(1, "out", "a", "x", "\"a\"", "nat", "\"PT\""),
                rule(2, "out", "b", "x", "-", "nat", "\"BR\""));
        var report = lists(HitPolicy.COLLECT, rules, Map.of("x", "\"a\",\"b\"", "nat", "\"PT\",\"BR\",\"ES\"")).validationReport();
        Assertions.assertThat(report).hasToString("""
                WARNING no rule matches x = "a", nat has no element in {"PT", "BR"}
                WARNING no rule matches x = "b", nat has no element in "BR\"""");
        Assertions.assertThat(examples(report)).containsExactly("{x=a, nat=[]}", "{x=b, nat=[]}");
    }

    @Test
    void every_list_column_but_the_last_branches_on_the_rules_its_lists_leave() {
        var rules = List.of(
                rule(1, "out", "a", "nat", "\"PT\"", "ages", "-"),
                rule(2, "out", "b", "nat", "-", "ages", ">= 18"));
        var report = lists(HitPolicy.COLLECT, rules, Map.of("nat", "\"PT\",\"ES\"")).validationReport();
        // ages comes first (the map inputs list it first): its lists leave rule 1 alone, or both rules, and only the
        // first is searched, as it holds fewer rules; nat, the last, is then decided directly
        Assertions.assertThat(report).hasToString("WARNING no rule matches ages has no element in [18..+inf), nat has no element in \"PT\"");
        Assertions.assertThat(examples(report)).containsExactly("{ages=[], nat=[]}");
    }

    // ---- past ListAnalysis.MAX_OUTCOMES: left unreported, never reported wrongly ------------------

    /**
     * Rules {@code i} and {@code not(i)} on {@code ages} for every {@code i < n}, with {@code nat} as given: a list of
     * each set {@code S} of those numbers matches the first rule of each number in {@code S} and the second of each
     * number outside it, so the lists leave {@code 2^n} sets of rules, none inside another. Past 10 numbers, that is
     * more than {@link ListAnalysis#MAX_OUTCOMES}.
     */
    private static List<Praeceptum> eachNumberAndItsNegation(int n, String nat) {
        var rules = new ArrayList<Praeceptum>();
        for (int i = 0; i < n; i++) {
            rules.add(rule(rules.size() + 1, "out", "is " + i, "ages", String.valueOf(i), "nat", nat));
            rules.add(rule(rules.size() + 1, "out", "not " + i, "ages", "not(" + i + ")", "nat", nat));
        }
        return rules;
    }

    @Test
    void past_the_bound_on_outcomes_a_list_columns_gaps_are_not_reported() {
        // ages comes before nat, the last: lists without "PT" match no rule, whatever ages holds
        var few = lists(HitPolicy.COLLECT, eachNumberAndItsNegation(3, "\"PT\""), Map.of("nat", "\"PT\",\"BR\"")).validationReport();
        Assertions.assertThat(few.issues(Kind.GAP)).isNotEmpty()
                .allSatisfy(gap -> Assertions.assertThat(gap.detail()).endsWith("nat has no element in \"PT\""));
        var many = lists(HitPolicy.COLLECT, eachNumberAndItsNegation(11, "\"PT\""), Map.of("nat", "\"PT\",\"BR\"")).validationReport();
        Assertions.assertThat(many.issues(Kind.GAP)).isEmpty();
    }

    @Test
    void past_the_bound_on_outcomes_an_unreachable_rule_is_not_reported() {
        // a missing list, the empty one and every other list of ages match a rule before the last, which so never wins
        // (nor do the rules after the first number's two, but they are judged on fewer rules, within the bound)
        for (int n : new int[] {3, 11}) {
            var rules = new ArrayList<Praeceptum>();
            rules.add(rule(100, "out", "none", "ages", "null", "nat", "-"));
            rules.addAll(eachNumberAndItsNegation(n, "-"));
            rules.add(rule(200, "out", "last", "ages", "-", "nat", "\"PT\""));
            var unreachable = lists(HitPolicy.FIRST, rules, Map.of()).validationReport().issues(Kind.UNREACHABLE_RULE).stream()
                    .map(issue -> issue.ruleIds().getFirst()).toList();
            Assertions.assertThat(unreachable).as("%d numbers", n).contains(3, 4).doesNotContain(100, 1, 2);
            if (n == 3) {
                Assertions.assertThat(unreachable).contains(200);
            } else {
                Assertions.assertThat(unreachable).doesNotContain(200);
            }
        }
        // as the last column, a list column is decided directly, whatever the number of outcomes
        var last = new ArrayList<Praeceptum>();
        last.add(rule(100, "out", "none", "ages", "null"));
        for (int i = 0; i < 11; i++) {
            last.add(rule(last.size() + 1, "out", "is " + i, "ages", String.valueOf(i)));
            last.add(rule(last.size() + 1, "out", "not " + i, "ages", "not(" + i + ")"));
        }
        last.add(rule(200, "out", "last", "ages", "-"));
        Assertions.assertThat(lists(HitPolicy.FIRST, last, Map.of()).validationReport().issues(Kind.UNREACHABLE_RULE))
                .extracting(issue -> issue.ruleIds().getFirst()).contains(200);
    }

    @Test
    void past_the_bound_on_outcomes_a_table_is_not_reported_complete() {
        for (int n : new int[] {3, 11}) {
            // complete: a missing list matches the first rule, every other list one of the others
            var complete = new ArrayList<Praeceptum>();
            complete.add(rule(100, "out", "none", "ages", "null"));
            for (int i = 0; i < n; i++) {
                complete.add(rule(complete.size() + 1, "out", "is " + i, "ages", String.valueOf(i)));
                complete.add(rule(complete.size() + 1, "out", "not " + i, "ages", "not(" + i + ")"));
            }
            var report = withDefault(complete).validationReport();
            Assertions.assertThat(report.issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).as("%d numbers", n).hasSize(n == 3 ? 1 : 0);
            // without the first rule, a missing list matches none: never complete, whatever the number of outcomes
            var incomplete = withDefault(complete.subList(1, complete.size())).validationReport();
            Assertions.assertThat(incomplete.issues(Kind.UNREACHABLE_DEFAULT_OUTPUT)).as("%d numbers", n).isEmpty();
        }
    }

    private static PraeceptaEngine<Map<String, String>, String> withDefault(List<Praeceptum> rules) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("out"))
                .listColumnType("ages", ColumnType.NUMBER)
                .defaultOutput(new PraeceptaOutput(Map.of("out", "default")))
                .rules(rules)
                .build();
    }

    // ---- large tables ----------------------------------------------------------------------------

    /**
     * A list column with a value per rule: under FIRST, the analysis takes a few mask operations per rule and element
     * class (it took one search per pair of rules, which made 2,000 rules take minutes); under UNIQUE, almost every pair
     * of rules overlaps on a list holding both values, and the report lists the first {@link TableAnalysis#MAX_OVERLAPS}
     * pairs in rule order.
     */
    @Test
    @Timeout(30)
    void a_list_column_with_a_value_per_rule_is_analysed_quickly_and_its_overlaps_are_capped() {
        var rules = new ArrayList<Praeceptum>();
        for (int i = 0; i < 2000; i++) {
            rules.add(rule(i + 1, "out", "r" + i, "nat", "\"T" + i + "\"", "n", "> " + i));
        }
        Assertions.assertThat(listsAndNumbers(HitPolicy.FIRST, rules).validationReport().isEmpty()).isTrue();

        var overlaps = listsAndNumbers(HitPolicy.UNIQUE, rules).validationReport().issues(Kind.OVERLAPPING_RULES);
        Assertions.assertThat(overlaps).hasSize(TableAnalysis.MAX_OVERLAPS);
        Assertions.assertThat(overlaps.getFirst().ruleIds()).containsExactly(1, 2);
        Assertions.assertThat(overlaps.getLast().ruleIds()).containsExactly(1, 101);
    }

    private static PraeceptaEngine<Map<String, String>, String> listsAndNumbers(HitPolicy policy, List<Praeceptum> rules) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("out"))
                .listColumnType("nat", ColumnType.STRING)
                .columnType("n", ColumnType.NUMBER)
                .rules(rules)
                .build();
    }

    /** An engine over map inputs whose columns {@code nat} (strings) and {@code ages} (numbers) hold lists. */
    private static PraeceptaEngine<Map<String, String>, String> lists(HitPolicy policy, List<Praeceptum> rules, Map<String, String> allowed) {
        var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("out"))
                .rules(rules);
        var columns = rules.getFirst().input().values().keySet();
        if (columns.contains("nat")) {
            builder.listColumnType("nat", ColumnType.STRING);
        }
        if (columns.contains("ages")) {
            builder.listColumnType("ages", ColumnType.NUMBER);
        }
        allowed.forEach(builder::allowedValues);
        return builder.build();
    }

    private static List<String> examples(ValidationReport report) {
        return report.issues(Kind.GAP).stream().map(gap -> gap.example().toString()).toList();
    }

    private static PraeceptaEngine<Map<String, String>, String> engine(HitPolicy policy, List<Praeceptum> rules,
            Map<String, String> allowed) {
        var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(policy)
                .inputs(PraeceptaInput::new)
                .output(output -> output.values().get("out"))
                .rules(rules);
        var columns = rules.getFirst().input().values().keySet();
        TYPES.forEach((column, type) -> {
            if (columns.contains(column)) {
                builder.columnType(column, type);
            }
        });
        allowed.forEach(builder::allowedValues);
        return builder.build();
    }

    /** A rule with one output and input cells given as column, cell pairs. */
    private static Praeceptum rule(int id, String output, String value, String... columnsAndCells) {
        var cells = new HashMap<String, String>();
        for (int i = 0; i < columnsAndCells.length; i += 2) {
            cells.put(columnsAndCells[i], columnsAndCells[i + 1]);
        }
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of(output, value)));
    }
}
