package io.github.brunopacheco1.praecepta.engine;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;

@FunctionalInterface
public interface InputStrategy<I> {

    PraeceptaInput transform(I input);
}
