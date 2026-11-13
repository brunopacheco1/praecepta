/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;

/** The Maven wiring of the generate goal: where files go and what the project learns about them. */
class GenerateMojoTest {

    @TempDir
    Path basedir;

    private MavenProject project;

    @BeforeEach
    void project() throws IOException, URISyntaxException {
        project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        var rules = Files.createDirectories(basedir.resolve("src/main/rules"));
        for (var name : List.of("fees.csv", "overlapping.csv", "credit.dmn")) {
            Files.copy(Path.of(GenerateMojoTest.class.getResource("/tables/" + name).toURI()), rules.resolve(name));
        }
    }

    @Test
    void writes_sources_and_dmn_and_registers_them_with_the_project() throws Exception {
        var mojo = mojo(List.of(fees("Fees")), "ERROR", "SPDX-FileCopyrightText: Acme");
        mojo.execute();

        var sources = basedir.resolve("target/generated-sources/praecepta/com/acme/rules");
        var resources = basedir.resolve("target/generated-resources/praecepta/com/acme/rules");
        Assertions.assertThat(sources.resolve("Fees.java")).exists();
        Assertions.assertThat(sources.resolve("FeesInput.java")).exists();
        Assertions.assertThat(sources.resolve("FeesOutput.java")).exists();
        Assertions.assertThat(resources.resolve("Fees.dmn")).content().contains("aggregation=\"SUM\"");
        Assertions.assertThat(sources.resolve("Fees.java")).content().startsWith("// SPDX-FileCopyrightText: Acme\n");

        Assertions.assertThat(project.getCompileSourceRoots())
                .contains(basedir.resolve("target/generated-sources/praecepta").toString());
        Assertions.assertThat(project.getResources()).extracting(Resource::getDirectory)
                .contains(basedir.resolve("target/generated-resources/praecepta").toString());
    }

    /** A DMN model whose decisions require one another (#14): a model class, and an input record and a class per decision. */
    @Test
    void a_decision_requirements_graph_generates_a_class_per_decision() throws Exception {
        var credit = new TableConfig(new File("src/main/rules/credit.dmn"), null, null, null, null, List.of(), List.of());
        mojo(List.of(credit), "ERROR", null).execute();

        var sources = basedir.resolve("target/generated-sources/praecepta/com/acme/rules");
        for (var name : List.of("CreditModel", "RiskCategory", "RiskCategoryInput", "CreditLimit", "CreditLimitInput", "MonthlyLimit",
                "MonthlyLimitInput")) {
            Assertions.assertThat(sources.resolve(name + ".java")).exists();
        }
        Assertions.assertThat(basedir.resolve("target/generated-resources/praecepta/com/acme/rules/CreditModel.dmn")).content()
                .contains("<literalExpression");

        // the model and a decision of it, compiled apart, would both generate the decision's classes
        var monthly = new TableConfig(new File("src/main/rules/credit.dmn"), null, "Monthly Limit", null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> mojo(List.of(credit, monthly), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessage("Two tables generate the class MonthlyLimitInput; set <name> on one");
    }

    /**
     * A DMN file whose model uses what a later release plans compiles its decision table alone, as in 0.6, and the
     * build says so at info level, once per file however many tables read it, naming the file, the feature and the
     * release and issue that plan it.
     */
    @Test
    void a_table_compiled_alone_for_what_a_later_release_plans_is_logged_once_per_file() throws Exception {
        Files.writeString(basedir.resolve("src/main/rules/later.dmn"), """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="d" namespace="n">
                  <businessKnowledgeModel id="bkm" name="Rate"/>
                  <decision id="tier" name="Tier"><decisionTable hitPolicy="FIRST">
                    <input><inputExpression typeRef="number"><text>age</text></inputExpression></input>
                    <output name="tier" typeRef="string"/>
                    <rule><inputEntry><text>-</text></inputEntry><outputEntry><text>"any"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>""");
        var tiers = List.of(new TableConfig(new File("src/main/rules/later.dmn"), "Tier", null, null, null, List.of(), List.of()),
                new TableConfig(new File("src/main/rules/later.dmn"), "OtherTier", null, null, null, List.of(), List.of()));
        var infos = new ArrayList<String>();
        var mojo = mojo(tiers, "ERROR", null);
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void info(CharSequence content) {
                infos.add(content.toString());
            }
        });
        mojo.execute();

        Assertions.assertThat(basedir.resolve("target/generated-sources/praecepta/com/acme/rules/OtherTier.java")).exists();
        Assertions.assertThat(infos).filteredOn(line -> line.contains("compiled alone")).singleElement().asString()
                .startsWith(basedir.resolve("src/main/rules/later.dmn") + ": its decision table is compiled alone, "
                        + "without its decision requirements graph, which needs Praecepta 0.10 (#118): ")
                .contains("business knowledge model 'Rate'");
        Assertions.assertThat(infos).filteredOn(line -> line.startsWith("Compiled table")).hasSize(2);
    }

    @Test
    void skip_writes_nothing() throws Exception {
        var mojo = mojo(List.of(fees("Fees")), "ERROR", null);
        set(mojo, "skip", true);
        mojo.execute();
        Assertions.assertThat(basedir.resolve("target")).doesNotExist();
        Assertions.assertThat(project.getCompileSourceRoots()).isEmpty();
    }

    @Test
    void two_tables_cannot_generate_the_same_class() {
        var mojo = mojo(List.of(fees("Fees"), fees("Fees")), "ERROR", null);
        Assertions.assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("Two tables generate the class Fees");
    }

    @Test
    void fail_on_controls_whether_validation_issues_fail_the_build() throws Exception {
        var overlapping = new TableConfig(new File("src/main/rules/overlapping.csv"), "Tier", null, HitPolicy.UNIQUE, null,
                List.of(new ColumnConfig("country", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("tier", ColumnType.STRING, null, List.of())));
        Assertions.assertThatThrownBy(() -> mojo(List.of(overlapping), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("overlap");

        mojo(List.of(overlapping), "NONE", null).execute(); // reported as warnings only
        Assertions.assertThat(basedir.resolve("target/generated-sources/praecepta/com/acme/rules/Tier.java")).exists();

        Assertions.assertThatThrownBy(() -> mojo(List.of(fees("Fees")), "SOMETIMES", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("<failOn> must be ERROR, WARNING or NONE");
    }

    @Test
    void configuration_errors_become_build_failures() {
        Assertions.assertThatThrownBy(() -> mojo(List.of(), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("Configure at least one <table>");
        var missing = new TableConfig(new File("src/main/rules/missing.csv"), null, null, HitPolicy.FIRST, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> mojo(List.of(missing), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void messages_and_generated_files_name_the_maven_plugin_and_its_pom_elements() throws Exception {
        var withoutHitPolicy = new TableConfig(new File("src/main/rules/fees.csv"), "Fees", null, null, null,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
        Assertions.assertThatThrownBy(() -> mojo(List.of(withoutHitPolicy), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageEndingWith(": <hitPolicy> is required for Excel and CSV tables");
        var withoutSource = new TableConfig(null, "Fees", null, null, null, List.of(), List.of());
        Assertions.assertThatThrownBy(() -> mojo(List.of(withoutSource), "ERROR", null).execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessage("Every <table> needs a <source>");

        mojo(List.of(fees("Fees")), "ERROR", null).execute();
        Assertions.assertThat(basedir.resolve("target/generated-sources/praecepta/com/acme/rules/Fees.java")).content()
                .startsWith("// Generated by praecepta-maven-plugin from fees.csv. Do not edit.\n");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A COLLECT/SUM table with a relative source, resolved against the project directory by the mojo. */
    private static TableConfig fees(String name) {
        return new TableConfig(new File("src/main/rules/fees.csv"), name, null, HitPolicy.COLLECT, AggregationOperator.SUM,
                List.of(new ColumnConfig("zone", ColumnType.STRING, null, List.of())),
                List.of(new ColumnConfig("fee", ColumnType.NUMBER, null, List.of())));
    }

    /** The mojo as Maven would configure it from a pom. */
    private GenerateMojo mojo(List<TableConfig> tables, String failOn, String fileHeader) {
        var mojo = new GenerateMojo();
        set(mojo, "project", project);
        set(mojo, "packageName", "com.acme.rules");
        set(mojo, "tables", tables);
        set(mojo, "failOn", failOn);
        set(mojo, "fileHeader", fileHeader);
        set(mojo, "outputDirectory", basedir.resolve("target/generated-sources/praecepta").toFile());
        set(mojo, "resourceDirectory", basedir.resolve("target/generated-resources/praecepta").toFile());
        set(mojo, "skip", false);
        return mojo;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var declared = GenerateMojo.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No field " + field + " on GenerateMojo", e);
        }
    }
}
