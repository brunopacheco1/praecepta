package io.github.brunopacheco1.praecepta.loader.exceptions;

public class MissingFileException extends RuntimeException {
    public MissingFileException(String path, Throwable cause) {
        super(String.format("The file not found: %s", path), cause);
    }
}
