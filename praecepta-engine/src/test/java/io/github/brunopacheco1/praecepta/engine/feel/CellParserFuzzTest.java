/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Runs {@link CellParserFuzzer} on the inputs under {@code src/test/resources/.../CellParserFuzzTestInputs}
 * in every build; with {@code JAZZER_FUZZ=1} Jazzer generates new ones (see
 * {@code .github/workflows/fuzz.yml}).
 */
class CellParserFuzzTest {

    @FuzzTest(maxDuration = "5m")
    void cells_parse_or_fail_cleanly_and_round_trip(FuzzedDataProvider data) {
        CellParserFuzzer.fuzzerTestOneInput(data);
    }
}
