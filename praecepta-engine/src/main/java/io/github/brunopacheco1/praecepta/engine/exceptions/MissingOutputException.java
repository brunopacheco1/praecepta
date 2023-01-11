package io.github.brunopacheco1.praecepta.engine.exceptions;

/**
 * Exception raised if no output was returned by the engine.
 */
public final class MissingOutputException extends RuntimeException {
    public MissingOutputException() {
        super("Missing output for the given input.");
    }
}
