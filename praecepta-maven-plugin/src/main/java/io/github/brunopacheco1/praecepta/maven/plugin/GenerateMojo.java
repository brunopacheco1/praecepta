/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;

/**
 * Compiles decision tables at build time. For each configured table it:
 * <ol>
 * <li>loads it from Excel, CSV or DMN;</li>
 * <li>validates it with the engine (overlaps, unreachable rules, gaps), failing the build at
 * {@code failOn} severity and logging the rest;</li>
 * <li>writes it as a DMN resource ({@code <package>/<Name>.dmn});</li>
 * <li>generates {@code <Name>Input} and {@code <Name>Output} records and a {@code <Name>} class that
 * evaluates the DMN resource, so applications need only {@code praecepta-engine} at runtime.</li>
 * </ol>
 *
 * <pre>{@code
 * <plugin>
 *   <groupId>io.github.brunopacheco1</groupId>
 *   <artifactId>praecepta-maven-plugin</artifactId>
 *   <executions><execution><goals><goal>generate</goal></goals></execution></executions>
 *   <configuration>
 *     <packageName>com.acme.pricing</packageName>
 *     <tables>
 *       <table>
 *         <source>src/main/rules/discount.xlsx</source>
 *         <hitPolicy>FIRST</hitPolicy>
 *         <inputs>
 *           <input><name>country</name><allowedValues>"PT","ES","FR"</allowedValues></input>
 *           <input><name>age</name><type>NUMBER</type></input>
 *         </inputs>
 *         <outputs>
 *           <output><name>discount</name><type>NUMBER</type></output>
 *         </outputs>
 *       </table>
 *       <table>
 *         <source>src/main/rules/shipping.dmn</source>
 *       </table>
 *     </tables>
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Package of the generated classes and DMN resources. */
    @Parameter(required = true)
    private String packageName;

    /** The tables to compile. */
    @Parameter(required = true)
    private List<TableConfig> tables = new ArrayList<>();

    /** Fail the build if validation finds issues at least this severe: ERROR (default), WARNING, or NONE. */
    @Parameter(defaultValue = "ERROR")
    private String failOn;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/praecepta")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/praecepta")
    private File resourceDirectory;

    /**
     * Text put first in every generated source, as {@code //} comments: typically the project's own
     * SPDX licence header. Generated code belongs to the project using the plugin, so there is none by
     * default.
     */
    @Parameter
    private String fileHeader;

    @Parameter(property = "praecepta.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("Skipping Praecepta table generation");
            return;
        }
        if (tables.isEmpty()) {
            throw new MojoFailureException("Configure at least one <table>");
        }
        var severity = severity(failOn);
        var packagePath = packageName.replace('.', File.separatorChar);
        var classNames = new HashSet<String>();
        try {
            for (var table : tables) {
                if (table.source != null && !table.source.isAbsolute()) {
                    table.source = new File(project.getBasedir(), table.source.getPath());
                }
                TableCompiler.Result result;
                try {
                    result = TableCompiler.compile(table, packageName, severity, fileHeader);
                } catch (IllegalArgumentException e) {
                    throw new MojoFailureException(e.getMessage(), e);
                }
                if (!classNames.add(result.className())) {
                    throw new MojoFailureException("Two tables generate the class " + result.className() + "; set <name> on one");
                }
                result.report().issues().forEach(issue -> getLog().warn(result.className() + ": " + issue));
                write(resourceDirectory.toPath().resolve(packagePath).resolve(result.className() + ".dmn"), result.dmn());
                for (var source : result.sources().entrySet()) {
                    write(outputDirectory.toPath().resolve(packagePath).resolve(source.getKey() + ".java"), source.getValue());
                }
                getLog().info("Compiled table %s (%d rules, %s) into %s.%s".formatted(table.source.getName(),
                        result.table().rules().size(), result.table().hitPolicy(), packageName, result.className()));
            }
        } catch (IOException e) {
            throw new MojoFailureException("Cannot write generated files: " + e.getMessage(), e);
        }
        project.addCompileSourceRoot(outputDirectory.getPath());
        var resource = new Resource();
        resource.setDirectory(resourceDirectory.getPath());
        project.addResource(resource);
    }

    private static Severity severity(String value) throws MojoFailureException {
        if (value == null || value.equalsIgnoreCase("NONE")) {
            return null;
        }
        try {
            return Severity.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("<failOn> must be ERROR, WARNING or NONE, not " + value);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
