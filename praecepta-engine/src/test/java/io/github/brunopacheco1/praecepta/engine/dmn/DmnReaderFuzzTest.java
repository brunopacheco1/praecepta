/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * DMN files come from outside (modelling tools, users), so any bytes must either load or be
 * rejected with an {@link InvalidDecisionTableException}; any other exception is a bug. The seed
 * inputs are the real DMN fixtures, so Jazzer mutates valid models rather than noise.
 *
 * <p>Runs on the inputs under {@code src/test/resources/.../DmnReaderFuzzTestInputs} in every
 * build; with {@code JAZZER_FUZZ=1} Jazzer generates new ones (see {@code .github/workflows/fuzz.yml}).
 */
class DmnReaderFuzzTest {

    @FuzzTest(maxDuration = "5m")
    void dmn_models_load_or_fail_cleanly(FuzzedDataProvider data) {
        var bytes = data.consumeRemainingAsBytes();
        try {
            DmnReader.read(new ByteArrayInputStream(bytes));
        } catch (InvalidDecisionTableException rejected) {
            // the expected outcome for anything that is not a supported DMN decision table
        }
    }
}
