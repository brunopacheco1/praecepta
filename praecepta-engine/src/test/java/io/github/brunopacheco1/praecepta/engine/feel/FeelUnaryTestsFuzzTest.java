/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Runs {@link FeelUnaryTestsFuzzer} on the inputs under {@code src/test/resources/.../FeelUnaryTestsFuzzTestInputs} in
 * every build; with {@code JAZZER_FUZZ=1} Jazzer generates new ones (see {@code .github/workflows/fuzz.yml}).
 */
class FeelUnaryTestsFuzzTest {

    @FuzzTest(maxDuration = "5m")
    void cells_parse_or_fail_cleanly_and_apply_without_throwing(FuzzedDataProvider data) {
        FeelUnaryTestsFuzzer.fuzzerTestOneInput(data);
    }
}
