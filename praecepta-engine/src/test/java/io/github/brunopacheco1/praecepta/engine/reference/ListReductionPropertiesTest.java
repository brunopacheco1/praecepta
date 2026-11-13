/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Finding;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * A self-test of the reference: for a column that holds lists, {@link ReferenceMatcher} does not enumerate lists, but
 * merges elements every cell answers alike and bounds a list's length (its {@code representativeLists}), an argument
 * the engine's {@code ListAnalysis} rests on too. If the two shared a blind spot, the differential tests would pass
 * on both. So on small tables, the reduced lists must find what every list does, up to the full number of
 * representative elements, each as often as wanted and the missing one included: the same overlaps and unreachable
 * rules, the same uncovered inputs (by their other columns), and the same answer to whether every input matches.
 */
class ListReductionPropertiesTest {

    /** Cells of a list column of strings, in Praecepta's syntax: some element passes, none does, the list is missing. */
    private static final List<String> STRING_CELLS = List.of("-", "\"a\"", "\"a\",\"b\"", "\"c\"", "not(\"a\")", "not(\"a\",\"b\")",
            "not(\"c\")", "null", "\"b\", null", ">= \"b\"");
    /** Cells of a list column of numbers: comparisons and intervals are unknown for a missing element. */
    private static final List<String> NUMBER_CELLS = List.of("-", ">= 2", "< 1", "[1..2]", "not(>= 2)", "1", "not(1, 3)", "null",
            "not([1..2])");
    private static final List<String> SINGLE_CELLS = List.of("-", "\"p\"", "not(\"p\")");

    record Table(List<Praeceptum> rules, Map<String, ColumnType> types, Set<String> lists, Map<String, String> allowed) {
    }

    @Provide
    Arbitrary<Table> oneListColumn() {
        var nat = Combinators.combine(Arbitraries.of(STRING_CELLS), Arbitraries.of(SINGLE_CELLS))
                .as((list, single) -> Map.of("nat", list, "x", single)).list().ofMinSize(1).ofMaxSize(4);
        var ages = Combinators.combine(Arbitraries.of(NUMBER_CELLS), Arbitraries.of(SINGLE_CELLS))
                .as((list, single) -> Map.of("ages", list, "x", single)).list().ofMinSize(1).ofMaxSize(4);
        return Arbitraries.oneOf(
                Combinators.combine(nat, Arbitraries.of(Map.<String, String>of(), Map.of("nat", "\"a\",\"b\",\"c\"")))
                        .as((cells, allowed) -> table(cells, Map.of("nat", ColumnType.STRING, "x", ColumnType.STRING), allowed)),
                ages.map(cells -> table(cells, Map.of("ages", ColumnType.NUMBER, "x", ColumnType.STRING), Map.of())));
    }

    @Provide
    Arbitrary<Table> twoListColumns() {
        return Combinators.combine(Arbitraries.of(STRING_CELLS), Arbitraries.of(NUMBER_CELLS))
                .as((nat, ages) -> Map.of("nat", nat, "ages", ages)).list().ofMinSize(1).ofMaxSize(3)
                .map(cells -> table(cells, Map.of("nat", ColumnType.STRING, "ages", ColumnType.NUMBER), Map.of()));
    }

    @Property(tries = 150, edgeCases = EdgeCasesMode.NONE)
    void the_reduced_lists_find_what_every_list_finds(@ForAll("oneListColumn") Table table) {
        assertSameFacts(table, Integer.MAX_VALUE); // every list up to the number of representative elements
    }

    @Property(tries = 60, edgeCases = EdgeCasesMode.NONE)
    void the_reduced_lists_of_two_list_columns_find_what_every_list_finds(@ForAll("twoListColumns") Table table) {
        assertSameFacts(table, 3);
    }

    private static void assertSameFacts(Table table, int longest) {
        var reduced = facts(table);
        ReferenceMatcher.EXHAUSTIVE_LISTS.set(longest);
        try {
            Assertions.assertThat(facts(table)).as("%s", table).isEqualTo(reduced);
        } finally {
            ReferenceMatcher.EXHAUSTIVE_LISTS.remove();
        }
    }

    /** What the analyses ask of the reference: findings under UNIQUE and FIRST, uncovered inputs, completeness. */
    private record Facts(Set<Finding> unique, Set<Finding> first, Set<Map<String, Object>> uncovered, boolean everyInputMatches) {
    }

    private static Facts facts(Table table) {
        var columns = List.copyOf(table.types().keySet().stream().sorted().toList());
        var unique = reference(table, HitPolicy.UNIQUE);
        var first = reference(table, HitPolicy.FIRST);
        // an uncovered input by the values of its single-value columns: the lists that leave it uncovered differ
        var uncovered = first.uncoveredInputs(columns).stream()
                .map(input -> input.entrySet().stream().filter(entry -> !table.lists().contains(entry.getKey()))
                        .collect(HashMap<String, Object>::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll))
                .map(map -> (Map<String, Object>) map)
                .collect(Collectors.toSet());
        return new Facts(unique.expectedFindings(columns), first.expectedFindings(columns), uncovered, first.everyInputMatches(columns));
    }

    private static ReferenceMatcher reference(Table table, HitPolicy policy) {
        return new ReferenceMatcher(table.rules(), policy, Map.of(), table.types(), table.allowed(), false, Set.of(), table.lists());
    }

    private static Table table(List<Map<String, String>> cells, Map<String, ColumnType> types, Map<String, String> allowed) {
        var rules = new java.util.ArrayList<Praeceptum>();
        for (int i = 0; i < cells.size(); i++) {
            rules.add(new Praeceptum(i + 1, new PraeceptaInput(new LinkedHashMap<>(cells.get(i))),
                    new PraeceptaOutput(Map.of("out", "r" + (i + 1)))));
        }
        var lists = types.keySet().stream().filter(column -> !column.equals("x")).collect(Collectors.toSet());
        return new Table(List.copyOf(rules), types, lists, allowed);
    }
}
