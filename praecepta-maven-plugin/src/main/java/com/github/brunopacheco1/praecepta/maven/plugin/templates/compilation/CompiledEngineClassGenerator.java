package com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation;

import com.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.beans.EngineClassModel;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static com.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class CompiledEngineClassGenerator {

    public static void generateEngineClass(
            String outputPackage,
            String outputPath
    ) {
        try {
            var template = CONFIGURATION.getTemplate("compilation/engine.ftl");
            var model = new EngineClassModel(outputPackage);
            generate(model, template, outputPath + File.separator + "GeneratedRulesEngine.java");
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
