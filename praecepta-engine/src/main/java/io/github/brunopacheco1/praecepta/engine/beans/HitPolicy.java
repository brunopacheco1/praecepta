package io.github.brunopacheco1.praecepta.engine.beans;

public enum HitPolicy {
    FIRST(true),
    PRIORITY(true),
    UNIQUE(true),
    ANY(true),
    COLLECT(false),
    RULE_ORDER( false),
    OUTPUT_ORDER(false);

    private HitPolicy(boolean findFirst) {
        this.findFirst = findFirst;
    }

    private final boolean findFirst;

    public boolean isFindFirst() {
        return findFirst;
    }
}
