package io.github.brunopacheco1.praecepta.engine.exceptions;

import java.util.List;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/**
 * An input matched rules in a way its hit policy forbids: several rules under {@link HitPolicy#UNIQUE},
 * or rules with different outputs under {@link HitPolicy#ANY}. In DMN terms the decision has no result.
 */
public class HitPolicyViolationException extends RuntimeException {

    private final transient HitPolicy hitPolicy;
    private final transient List<Integer> ruleIds;

    public HitPolicyViolationException(HitPolicy hitPolicy, List<Integer> ruleIds) {
        super(switch (hitPolicy) {
            case UNIQUE -> "UNIQUE hit policy violated: rules " + ruleIds + " all match";
            case ANY -> "ANY hit policy violated: matching rules " + ruleIds + " have different outputs";
            default -> hitPolicy + " hit policy violated by rules " + ruleIds;
        });
        this.hitPolicy = hitPolicy;
        this.ruleIds = List.copyOf(ruleIds);
    }

    public HitPolicy getHitPolicy() {
        return hitPolicy;
    }

    /** Identifiers ({@code Praeceptum.priority}) of the conflicting rules, in rule order. */
    public List<Integer> getRuleIds() {
        return ruleIds;
    }
}
