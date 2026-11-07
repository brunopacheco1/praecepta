/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * DMN files come from outside (modelling tools, users), so any bytes must either load or be
 * rejected with an {@link InvalidDecisionTableException}; any other exception is a bug.
 *
 * <p>A plain Jazzer fuzz target, so that both {@link DmnReaderFuzzTest} (JUnit, in every build) and
 * ClusterFuzzLite (see {@code .clusterfuzzlite/build.sh}) run it.
 */
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
    }
}
