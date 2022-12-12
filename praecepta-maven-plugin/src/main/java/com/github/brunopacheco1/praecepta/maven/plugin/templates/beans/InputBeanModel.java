package com.github.brunopacheco1.praecepta.maven.plugin.templates.beans;

import java.util.Set;

public record InputBeanModel(
    String outputPackage, Set<String> inputs) {
}
