package io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.beans;

import java.util.Set;

public record InMemoryLoaderClassModel(
    String outputPackage,
    String matrixPath,
    Set<String> inputs,
    Set<String> outputs) {
}
