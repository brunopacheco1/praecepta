package com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans;

import java.util.List;
import java.util.Set;

public record NodeClassModel(
    String outputPackage,
    String nodeName,
    String input,
    Set<String> childValues,
    List<Integer> outputKeys,
    Boolean hasAny) {
}
