package io.github.brunopacheco1.praecepta.loader.exceptions;

public class ParsingInputOutputException extends RuntimeException {
    public ParsingInputOutputException() {
        super("Missing input/output value");
    }
}
