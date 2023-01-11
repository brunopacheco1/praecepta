package io.github.brunopacheco1.praecepta.engine;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;

public interface OutputStrategy<O> {

    O transform(PraeceptaOutput output);

    default O max(O obj1, O obj2) {
        throw new IllegalArgumentException("Not implemented.");
    }

    default O min(O obj1, O obj2) {
        throw new IllegalArgumentException("Not implemented.");
    }

    default O sum(O obj1, O obj2) {
        throw new IllegalArgumentException("Not implemented.");
    }
}
