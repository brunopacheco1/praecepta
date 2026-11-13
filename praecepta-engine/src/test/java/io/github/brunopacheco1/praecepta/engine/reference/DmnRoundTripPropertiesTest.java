/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.reference;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.api.Assertions;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.reference.ReferenceMatcher.Outcome;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Random typed tables survive a DMN round trip: written by {@link DmnWriter}, read back by
 * {@link DmnReader} and loaded with {@code Builder#table}, they evaluate exactly as the reference
 * evaluates the original rules, under every hit policy, with the outputs' default values when no rule
 * matches. Writing is also stable: a table read back writes the same XML.
 */
class DmnRoundTripPropertiesTest {

    @Provide
    Arbitrary<DecisionTableScenario> scenarios() {
        return DecisionTableScenario.arbitrary();
    }

    @Provide
    Arbitrary<DecisionTableScenario> temporalScenarios() {
        return DecisionTableScenario.temporal();
    }

    @Provide
    Arbitrary<DecisionTableScenario> wideScenarios() {
        return DecisionTableScenario.wide();
    }

    @Provide
    Arbitrary<List<String>> priorities() {
        return Arbitraries.shuffle(DecisionTableScenario.OUT_VALUES);
    }

    @Property(tries = 500, edgeCases = EdgeCasesMode.NONE)
    void tables_read_back_from_dmn_evaluate_like_the_originals(@ForAll("scenarios") DecisionTableScenario s,
            @ForAll HitPolicy policy, @ForAll("priorities") List<String> priority) {
        assertRoundTrip(s, policy, priority);
    }

    /**
     * Times and date-times with and without a zone, lists mixing both, and cells naming other temporal
     * columns: the most DMN-specific rendering ({@code some x in ? satisfies x >= time("08:00:00Z")},
     * {@code list contains(?, date and time("...@Europe/Paris"))}).
     */
    @Property(tries = 300, edgeCases = EdgeCasesMode.NONE)
    void temporal_tables_read_back_from_dmn_evaluate_like_the_originals(@ForAll("temporalScenarios") DecisionTableScenario s,
            @ForAll HitPolicy policy, @ForAll("priorities") List<String> priority) {
        assertRoundTrip(s, policy, priority);
    }

    @Property(tries = 30, edgeCases = EdgeCasesMode.NONE)
    void wide_tables_read_back_from_dmn_evaluate_like_the_originals(@ForAll("wideScenarios") DecisionTableScenario s,
            @ForAll HitPolicy policy, @ForAll("priorities") List<String> priority) {
        assertRoundTrip(s, policy, priority);
    }

    private static void assertRoundTrip(DecisionTableScenario s, HitPolicy policy, List<String> priority) {
        var rules = policy == HitPolicy.ANY ? DecisionTableScenario.outOnly(s.rules()) : s.rules();
        var original = table(s, rules, policy, priority);

        var xml = DmnWriter.write(original);
        var read = DmnReader.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Assertions.assertThat(DmnWriter.write(read)).as("writing is stable").isEqualTo(xml);
        Assertions.assertThat(read.hitPolicy()).isEqualTo(policy);
        Assertions.assertThat(read.outputs()).as("outputs, with their value lists and defaults").isEqualTo(original.outputs());
        Assertions.assertThat(read.listInputs()).as("list inputs").isEqualTo(s.lists());
        Assertions.assertThat(read.inputs()).extracting(DecisionTable.InputColumn::name, DecisionTable.InputColumn::type)
                .containsExactlyElementsOf(original.inputs().stream()
                        .map(input -> Assertions.tuple(input.name(), input.type())).toList());

        var engine = engine(s, read);

        var outputs = original.outputs().stream().map(DecisionTable.OutputColumn::name).toList();
        var reference = s.reference(rules, policy, DecisionTableScenario.outputPriority(policy, priority), true, outputs);
        for (var input : s.inputs()) {
            switch (reference.evaluate(input)) {
                case Outcome.Hits hits -> Assertions.assertThat(engine.evaluate(input))
                        .as("%s, input %s\n%s", policy, input, xml)
                        .containsExactlyElementsOf(hits.outputs());
                case Outcome.Violation violation -> Assertions.assertThatThrownBy(() -> engine.evaluate(input))
                        .as("%s, input %s", policy, input)
                        .isInstanceOf(HitPolicyViolationException.class);
                case Outcome.InvalidInput invalid -> Assertions.assertThatThrownBy(() -> engine.evaluate(input))
                        .as("%s, input %s", policy, input)
                        .isInstanceOf(InvalidInputException.class);
            }
        }

        // the same file in the other forms DMN 1.5 allows, which DmnWriter does not write, evaluates the same
        var variant = variant(xml);
        var variantEngine = engine(s, DmnReader.read(new ByteArrayInputStream(variant.getBytes(StandardCharsets.UTF_8))));
        for (var input : s.inputs()) {
            Assertions.assertThat(outcome(variantEngine, input)).as("%s, input %s\n%s", policy, input, variant)
                    .isEqualTo(outcome(engine, input));
        }
    }

    private static PraeceptaEngine<Map<String, Object>, PraeceptaOutput> engine(DecisionTableScenario s, DecisionTable table) {
        var builder = PraeceptaEngine.<Map<String, Object>, PraeceptaOutput>builder()
                .table(table)
                .output(output -> output);
        s.bindColumns(builder);
        s.applyMaskForm(builder);
        return builder.build();
    }

    /** What an engine gives for an input: its outputs, or the class of the exception it throws. */
    private static Object outcome(PraeceptaEngine<Map<String, Object>, PraeceptaOutput> engine, Map<String, Object> input) {
        try {
            return engine.evaluate(input);
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }

    /**
     * A DMN file as {@link DmnWriter} writes it, in the forms it does not write: at-literals for the date and time
     * functions in every entry, value list and default (DMN 1.5, grammar rule 65), and comments around every input
     * entry (10.3.1.2).
     */
    private static String variant(String xml) {
        var atLiterals = DecisionTableScenario.atLiterals(xml);
        return INPUT_ENTRY.matcher(atLiterals).replaceAll(entry -> Matcher.quoteReplacement(
                entry.group(1) + "/* entry */ " + entry.group(2) + " // " + entry.group(2).length() + entry.group(3)));
    }

    private static final Pattern INPUT_ENTRY = Pattern.compile("(<inputEntry[^>]*>\\s*<text>)([^<]*)(</text>)");

    private static DecisionTable table(DecisionTableScenario s, List<Praeceptum> rules, HitPolicy policy, List<String> priority) {
        var inputs = s.columns().stream()
                .map(column -> new DecisionTable.InputColumn(column, s.types().get(column), s.allowedValues().get(column)))
                .toList();
        // each with the scenario's default value, if it has one
        var outputs = new ArrayList<DecisionTable.OutputColumn>();
        if (policy != HitPolicy.ANY) {
            outputs.add(s.output(DecisionTableScenario.ID, ColumnType.STRING, List.of()));
        }
        outputs.add(s.output(DecisionTableScenario.OUT, ColumnType.STRING, priority));
        if (policy != HitPolicy.ANY) {
            outputs.add(s.output(DecisionTableScenario.NUMBER, ColumnType.NUMBER, List.of()));
        }
        // a string output, which takes the value of any input it names
        outputs.add(s.output(DecisionTableScenario.ECHO, ColumnType.STRING, List.of()));
        return new DecisionTable("round_trip", policy, null, inputs, outputs, rules, s.feelCells(), s.lists());
    }
}
