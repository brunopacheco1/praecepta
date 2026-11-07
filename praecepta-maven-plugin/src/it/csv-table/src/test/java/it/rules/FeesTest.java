/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package it.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Runs in a separate Maven build against the classes the plugin generated from rules/fees.csv. */
class FeesTest {

    @Test
    void generated_decision_sums_the_matching_fees() {
        // rules: any zone -> 5; islands -> 12.5 (COLLECT, SUM)
        assertEquals(new BigDecimal("17.5"), Fees.evaluate(new FeesInput(FeesInput.Zone.ISLANDS)).orElseThrow().fee());
        assertEquals(new BigDecimal("5"), Fees.evaluate(new FeesInput(FeesInput.Zone.MAINLAND)).orElseThrow().fee());
    }

    @Test
    void build_time_validation_found_no_issues() {
        assertTrue(Fees.validationReport().isEmpty());
    }
}
