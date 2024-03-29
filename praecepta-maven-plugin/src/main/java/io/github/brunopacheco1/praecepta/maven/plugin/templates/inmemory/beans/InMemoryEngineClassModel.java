package io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.beans;

import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

public record InMemoryEngineClassModel(
    String outputPackage,
    HitPolicy hitPolicy,
    Set<String> inputs,
    Set<String> outputs) {
}
