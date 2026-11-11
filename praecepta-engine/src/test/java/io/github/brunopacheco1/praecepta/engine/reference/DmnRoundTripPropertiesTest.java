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
 * evaluates the original rules, under every hit policy. Writing is also stable: a table read back
 * writes the same XML.
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
        Assertions.assertThat(read.listInputs()).as("list inputs").isEqualTo(s.lists());
        Assertions.assertThat(read.inputs()).extracting(DecisionTable.InputColumn::name, DecisionTable.InputColumn::type)
                .containsExactlyElementsOf(original.inputs().stream()
                        .map(input -> Assertions.tuple(input.name(), input.type())).toList());

        var builder = PraeceptaEngine.<Map<String, Object>, PraeceptaOutput>builder()
                .table(read)
                .output(output -> output);
        s.bindColumns(builder);
        s.applyMaskForm(builder);
        var engine = builder.build();

        var reference = s.reference(rules, policy, DecisionTableScenario.outputPriority(policy, priority));
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
    }

    private static DecisionTable table(DecisionTableScenario s, List<Praeceptum> rules, HitPolicy policy, List<String> priority) {
        var inputs = s.columns().stream()
                .map(column -> new DecisionTable.InputColumn(column, s.types().get(column), s.allowedValues().get(column)))
                .toList();
        var outputs = new ArrayList<DecisionTable.OutputColumn>();
        if (policy != HitPolicy.ANY) {
            outputs.add(new DecisionTable.OutputColumn(DecisionTableScenario.ID, ColumnType.STRING));
        }
        outputs.add(new DecisionTable.OutputColumn(DecisionTableScenario.OUT, ColumnType.STRING, priority));
        if (policy != HitPolicy.ANY) {
            outputs.add(new DecisionTable.OutputColumn(DecisionTableScenario.NUMBER, ColumnType.NUMBER));
        }
        // a string output, which takes the value of any input it names
        outputs.add(new DecisionTable.OutputColumn(DecisionTableScenario.ECHO, ColumnType.STRING));
        return new DecisionTable("round_trip", policy, null, inputs, outputs, rules, s.feelCells(), s.lists());
    }
}
