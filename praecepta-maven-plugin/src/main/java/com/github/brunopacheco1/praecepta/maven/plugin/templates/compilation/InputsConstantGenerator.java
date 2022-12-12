package com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans.InputsConstantModel;
import com.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import com.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import com.github.brunopacheco1.praecepta.loader.PraeceptaLoader;

import static com.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;

public class InputsConstantGenerator {

    public static void generateInputsConst(
            String outputPackage,
            Set<String> inputs,
            PraeceptaLoader loader,
            String outputPath
    ) {
        try {
            var inputValues = loader.getPraecepta().stream().map(Praeceptum::input).collect(Collectors.toSet());
            var possibleValues = aggregate(inputs, inputValues);
            var template = CONFIGURATION.getTemplate("compilation/inputs-constant.ftl");
            var model = new InputsConstantModel(outputPackage, possibleValues);
            generate(model, template, outputPath + File.separator + "InputsConst.java");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TreeMap<String, TreeSet<String>> aggregate(Set<String> inputs, Set<PraeceptaInput> inputValues) {
        var possibleValues = new TreeMap<String, TreeSet<String>>();
        for (var inputKey : inputs) {
            for (var inputValue : inputValues) {
                possibleValues.putIfAbsent(inputKey, new TreeSet<>());

                var values = ofNullable(inputValue.values().get(inputKey))
                        .filter(not(String::isBlank))
                        .orElse("NULL")
                        .split(",");

                for (String value : values) {
                    possibleValues.get(inputKey).add(value);
                }
            }
            possibleValues.get(inputKey).add("NULL");
        }
        return possibleValues;
    }

    private static void generate(Object templateModel, Template template, String outputFile) throws TemplateException, IOException {
        var file = new File(outputFile);
        var writer = new FileWriter(file);
        template.process(templateModel, writer);
        writer.close();
    }
}
