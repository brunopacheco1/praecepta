package io.github.brunopacheco1.praecepta.maven.plugin.templates;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

import io.github.brunopacheco1.praecepta.maven.plugin.templates.beans.InputBeanModel;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.beans.OutputBeanModel;

import static io.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class InputOutputClassesGenerator {

    public static void generateEngine(
            String outputPackage,
            Set<String> inputs,
            Set<String> outputs,
            String outputPath
    ) {
        generateInput(outputPackage, inputs, outputPath);
        generateOutput(outputPackage, outputs, outputPath);
    }

    private static void generateInput(String outputPackage, Set<String> inputs, String outputPath) {
        try {
            var template = CONFIGURATION.getTemplate("input-bean.ftl");
            var model = new InputBeanModel(outputPackage, inputs);
            generate(model, template, outputPath + File.separator + "EngineInput.java");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void generateOutput(String outputPackage, Set<String> outputs, String outputPath) {
        try {
            var template = CONFIGURATION.getTemplate("output-bean.ftl");
            var model = new OutputBeanModel(outputPackage, outputs);
            generate(model, template, outputPath + File.separator + "EngineOutput.java");
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
