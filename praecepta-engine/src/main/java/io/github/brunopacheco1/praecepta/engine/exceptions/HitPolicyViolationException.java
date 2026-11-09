/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.exceptions;

import java.io.Serial;
import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * An input matched rules in a way its hit policy forbids: several rules under {@link HitPolicy#UNIQUE},
 * or rules with different outputs under {@link HitPolicy#ANY}. In DMN terms the decision has no result.
 */
public class HitPolicyViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient HitPolicy hitPolicy;
    private final transient List<Integer> ruleIds;

    /**
     * @param hitPolicy the violated hit policy
     * @param ruleIds   identifiers of the conflicting rules, in rule order
     */
    public HitPolicyViolationException(HitPolicy hitPolicy, List<Integer> ruleIds) {
        super(switch (hitPolicy) {
            case UNIQUE -> "UNIQUE hit policy violated: rules %s all match".formatted(ruleIds);
            case ANY -> "ANY hit policy violated: matching rules %s have different outputs".formatted(ruleIds);
            default -> "%s hit policy violated by rules %s".formatted(hitPolicy, ruleIds);
        });
        this.hitPolicy = hitPolicy;
        this.ruleIds = List.copyOf(ruleIds);
    }

    /** The hit policy that was violated. */
    public HitPolicy getHitPolicy() {
        return hitPolicy;
    }

    /** Identifiers ({@code Praeceptum.priority}) of the conflicting rules, in rule order. */
    public List<Integer> getRuleIds() {
        return ruleIds;
    }
}
