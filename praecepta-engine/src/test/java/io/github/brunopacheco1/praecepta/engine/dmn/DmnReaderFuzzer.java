/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/**
 * DMN files come from outside (modelling tools, users), so any bytes must either load or be
 * rejected with an {@link InvalidDecisionTableException}; any other exception is a bug. That holds for a
 * single table ({@link DmnReader#read(java.io.InputStream)}) and for a whole decision model
 * ({@link DmnReader#readModel}), whose graph ({@link DecisionGraph}) must then build, evaluate each of its
 * decisions with no input data (failing only as an evaluation may: a decision that cannot be evaluated, an
 * input a table rejects, a hit policy violated), and, written back as DMN, read back into a model that writes the same
 * DMN.
 *
 * <p>A plain Jazzer fuzz target, so that both {@link DmnReaderFuzzTest} (JUnit, in every build) and
 * ClusterFuzzLite (see {@code .clusterfuzzlite/build.sh}) run it.
 */
@SuppressWarnings("exports") // Jazzer's API is on the class path, not a module
public final class DmnReaderFuzzer {

    private DmnReaderFuzzer() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        var bytes = data.consumeRemainingAsBytes();
        try {
            DmnReader.read(new ByteArrayInputStream(bytes));
        } catch (InvalidDecisionTableException rejected) {
            // the expected outcome for anything that is not a supported DMN decision table
        }
        try {
            var model = DmnReader.readModel(new ByteArrayInputStream(bytes));
            var graph = DecisionGraph.builder().model(model).logValidationReport(false).build();
            for (var decision : graph.decisions()) {
                evaluate(graph, decision);
            }
            var written = DmnWriter.write(model);
            var read = DmnReader.readModel(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8)));
            if (!DmnWriter.write(read).equals(written)) {
                throw new IllegalStateException("a model written as DMN and read back writes otherwise:\n" + written);
            }
        } catch (InvalidDecisionTableException rejected) {
            // a model Praecepta does not support, or one with a requirement DMN cannot write
        }
    }

    private static void evaluate(DecisionGraph graph, String decision) {
        try {
            graph.evaluate(decision, Map.of());
        } catch (InvalidDecisionTableException | InvalidInputException | HitPolicyViolationException e) {
            // a decision that cannot be evaluated, or a table that rejects a missing input
        }
    }
}
