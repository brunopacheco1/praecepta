/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;

/**
 * Cells are written by people, so any text must either parse or be rejected with a
 * {@link CellSyntaxException}; any other exception is a bug. A cell that parses must also survive
 * being written as standard FEEL and parsed again (what {@code DmnWriter} relies on).
 *
 * <p>Runs on the inputs under {@code src/test/resources/.../CellParserFuzzTestInputs} in every
 * build; with {@code JAZZER_FUZZ=1} Jazzer generates new ones (see {@code .github/workflows/fuzz.yml}).
 */
class CellParserFuzzTest {

    @FuzzTest(maxDuration = "5m")
    void cells_parse_or_fail_cleanly_and_round_trip(FuzzedDataProvider data) {
        var type = data.pickValue(ColumnType.values());
        var cell = data.consumeRemainingAsString();
        UnaryTests parsed;
        try {
            parsed = CellParser.parse(cell, type);
        } catch (CellSyntaxException rejected) {
            return; // a clear rejection is the expected outcome for invalid cells
        }
        var rendered = DmnWriter.render(parsed);
        UnaryTests reparsed;
        try {
            reparsed = CellParser.parse(rendered, type);
        } catch (CellSyntaxException e) {
            throw new AssertionError("%s cell %s parsed, but its FEEL form %s does not: %s"
                    .formatted(type, quote(cell), quote(rendered), e.getMessage()), e);
        }
        if (!reparsed.equals(parsed)) {
            throw new AssertionError("%s cell %s parsed as %s, but its FEEL form %s parses as %s"
                    .formatted(type, quote(cell), parsed, quote(rendered), reparsed));
        }
    }

    private static String quote(String text) {
        return "'" + text.replace("\n", "\\n") + "'";
    }
}
