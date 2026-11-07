/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Runs {@link DmnReaderFuzzer} on the inputs under {@code src/test/resources/.../DmnReaderFuzzTestInputs}
 * in every build; with {@code JAZZER_FUZZ=1} Jazzer generates new ones (see
 * {@code .github/workflows/fuzz.yml}). The seed inputs are real DMN fixtures, so Jazzer mutates
 * valid models rather than noise.
 */
class DmnReaderFuzzTest {

    @FuzzTest(maxDuration = "5m")
    void dmn_models_load_or_fail_cleanly(FuzzedDataProvider data) {
        DmnReaderFuzzer.fuzzerTestOneInput(data);
    }
}
