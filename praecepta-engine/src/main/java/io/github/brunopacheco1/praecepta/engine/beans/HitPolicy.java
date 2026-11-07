/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.beans;

/**
 * DMN 1.3 decision-table hit policies (§8.2.12). "Rule order" is the order of the rules passed to
 * the engine builder.
 */
public enum HitPolicy {
    /** At most one rule may match; more than one is a {@code HitPolicyViolationException}. */
    UNIQUE(true),
    /** Several rules may match if they all have the same outputs; otherwise a violation. */
    ANY(true),
    /** The matching rule whose outputs have the highest priority (output value lists); ties go to rule order. */
    PRIORITY(true),
    /** The first matching rule in rule order. */
    FIRST(true),
    /** All matching rules, ordered by decreasing output priority; ties keep rule order. */
    OUTPUT_ORDER(false),
    /** All matching rules, in rule order. */
    RULE_ORDER(false),
    /** All matching rules (in rule order), optionally aggregated with an {@link AggregationOperator}. */
    COLLECT(false);

    private final boolean singleHit;

    HitPolicy(boolean singleHit) {
        this.singleHit = singleHit;
    }

    /** Whether evaluation yields at most one output. */
    public boolean isSingleHit() {
        return singleHit;
    }
}
