package com.github.brunopacheco1.praecepta;

@FunctionalInterface
public interface InputStrategy<I> {

    Input transform(I input);
}
