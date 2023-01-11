package io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory;

import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.beans.InMemoryEngineClassModel;

import static io.github.brunopacheco1.praecepta.maven.plugin.templates.TemplateConfigurationConst.CONFIGURATION;

public class InMemoryEngineGenerator {

    public static void generateEngineClass(
            String outputPackage,
            HitPolicy hitPolicy,
            Set<String> inputs,
            Set<String> outputs,
            String outputPath
    ) {
        try {
            var template = CONFIGURATION.getTemplate("inmemory/engine.ftl");
            var model = new InMemoryEngineClassModel(outputPackage, hitPolicy, inputs, outputs);
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
