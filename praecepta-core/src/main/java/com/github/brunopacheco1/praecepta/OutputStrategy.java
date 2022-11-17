package com.github.brunopacheco1.praecepta;

public interface OutputStrategy<O> {

    O transform(Output output);

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
