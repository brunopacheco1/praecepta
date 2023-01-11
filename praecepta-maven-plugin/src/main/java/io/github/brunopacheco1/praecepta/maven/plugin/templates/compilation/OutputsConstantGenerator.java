package io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation;

import freemarker.template.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.loader.PraeceptaLoader;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans.OutputsConstantModel;

import static io.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class OutputsConstantGenerator {

    public static void generateOutputsConst(String outputPackage, Set<String> outputs, PraeceptaLoader loader, String outputPath) {
        try {
            var ruleOutputs = loader.getPraecepta().stream()
                    .map(Praeceptum::output)
                    .collect(Collectors.toList());

            var template = CONFIGURATION.getTemplate("compilation/outputs-constant.ftl");
            var model = new OutputsConstantModel(outputPackage, outputs, ruleOutputs);
            generate(model, template, outputPath + File.separator + "OutputsConst.java");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void generate(Object templateModel, Template template, String outputFile) throws TemplateException, IOException {
        var file = new File(outputFile);
        var writer = new FileWriter(file);
        template.process(templateModel, writer);
        writer.close();
    }
}
