package com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans;

import java.util.Map;
import java.util.TreeSet;

public record InputsConstantModel(
    String outputPackage,
    Map<String, TreeSet<String>> possibleValues) {
}
