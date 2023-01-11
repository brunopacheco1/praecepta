package io.github.brunopacheco1.praecepta.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.maven.plugin.beans.LoadingMode;
import io.github.brunopacheco1.praecepta.maven.plugin.rules.PluginExcelLoader;
import io.github.brunopacheco1.praecepta.maven.plugin.rules.PluginRulesEngine;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.InputOutputEngineGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.CompiledEngineClassGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.InputsConstantGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.NodeClassGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.compilation.OutputsConstantGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.InMemoryEngineGenerator;
import io.github.brunopacheco1.praecepta.maven.plugin.templates.inmemory.InMemoryLoaderClassGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class CommonRulesEngineCompilerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "io.github.brunopacheco1.praecepta")
    private String outputPackage;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/praecepta-engine")
    private String outputDirectory;

    @Parameter(defaultValue = "COMPILATION")
    private LoadingMode loadingMode;

    @Parameter(required = true)
    private String matrixPath;

    @Parameter(required = true)
    private String inputs;

    @Parameter(required = true)
    private String outputs;

    @Parameter(required = true)
    private HitPolicy hitPolicy;

    public void execute() {
        project.addCompileSourceRoot(this.outputDirectory);

        var outputDirectoryFile = new File(this.outputDirectory + File.separator + outputPackage.replaceAll("\\.", File.separator));

        if (!outputDirectoryFile.exists()) {
            outputDirectoryFile.mkdirs();
        }

        var inputsAsSet = asCollection(this.inputs);
        var outputsAsSet = asCollection(this.outputs);
        var absolutePath = outputDirectoryFile.getAbsolutePath();

        InputOutputEngineGenerator.generateInputOutputBeans(outputPackage, inputsAsSet, outputsAsSet, absolutePath);

        if (LoadingMode.COMPILATION == loadingMode) {
            var loader = new PluginExcelLoader(matrixPath, inputsAsSet, outputsAsSet);
            var engine = new PluginRulesEngine(hitPolicy);
            engine.register(loader.getPraecepta());

            OutputsConstantGenerator.generateOutputsConst(outputPackage, outputsAsSet, loader, absolutePath);

            InputsConstantGenerator.generateInputsConst(outputPackage, inputsAsSet, loader, absolutePath);

            engine.accept(new NodeClassGenerator(absolutePath, outputPackage, new ArrayList<>(engine.getInputs())));

            CompiledEngineClassGenerator.generateEngineClass(outputPackage, absolutePath);
        } else {
            InMemoryLoaderClassGenerator.generateLoaderClass(outputPackage, matrixPath, inputsAsSet, outputsAsSet, absolutePath);

            InMemoryEngineGenerator.generateEngineClass(outputPackage, hitPolicy, inputsAsSet, outputsAsSet, absolutePath);
        }
    }

    private Set<String> asCollection(String value) {
        return Stream.of(value.split(",")).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
