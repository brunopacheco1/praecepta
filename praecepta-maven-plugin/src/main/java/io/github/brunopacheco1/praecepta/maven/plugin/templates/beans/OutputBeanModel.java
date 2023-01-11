package io.github.brunopacheco1.praecepta.maven.plugin.templates.beans;

import java.util.Set;

public record OutputBeanModel(
    String outputPackage,
    Set<String> outputs) {
}
