package com.github.brunopacheco1.praecepta.engine;

import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;

@FunctionalInterface
public interface InputStrategy<I> {

    PraeceptaInput transform(I input);
}
