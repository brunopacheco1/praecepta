package com.github.brunopacheco1.praecepta;

@FunctionalInterface
public interface OutputStrategy<O> {

    O transform(Output output);
}
