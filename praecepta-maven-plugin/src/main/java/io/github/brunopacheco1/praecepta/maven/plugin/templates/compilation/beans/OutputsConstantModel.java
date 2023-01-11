package io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans;

import java.util.List;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;

public record OutputsConstantModel(
    String outputPackage,
    Set<String> outputs,
    List<PraeceptaOutput> ruleOutputs) {
}
