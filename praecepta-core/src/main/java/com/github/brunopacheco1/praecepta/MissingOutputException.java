package com.github.brunopacheco1.praecepta;

/**
 * Exception raised if no output was returned by the engine.
 */
public final class MissingOutputException extends RuntimeException {
    public MissingOutputException() {
        super("Missing output for the given input.");
    }
}
