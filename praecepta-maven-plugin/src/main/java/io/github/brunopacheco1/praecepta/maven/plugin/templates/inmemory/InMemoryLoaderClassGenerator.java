package io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory;

import io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.beans.InMemoryLoaderClassModel;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

import static io.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class InMemoryLoaderClassGenerator {

    public static void generateLoaderClass(
            String outputPackage,
            String matrixPath,
            Set<String> inputs,
            Set<String> outputs,
            String outputPath
    ) {
        try {
            var template = CONFIGURATION.getTemplate("inmemory/loader.ftl");
            var model = new InMemoryLoaderClassModel(outputPackage, matrixPath, inputs, outputs);
            generate(model, template, outputPath + File.separator + "GeneratedExcelLoader.java");
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
