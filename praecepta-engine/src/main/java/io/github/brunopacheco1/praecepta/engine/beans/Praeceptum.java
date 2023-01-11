package io.github.brunopacheco1.praecepta.engine.beans;

public final record Praeceptum(
        Integer priority,
        PraeceptaInput input,
        PraeceptaOutput output) {
}
