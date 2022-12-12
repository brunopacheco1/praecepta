package com.github.brunopacheco1.praecepta.loader.exceptions;

import java.util.Set;

public class MissingFieldException extends RuntimeException {
    public MissingFieldException(Set<String> fieldName) {
        super(String.format("Fields not found in file: %s", String.join(", ", fieldName)));
    }
}
