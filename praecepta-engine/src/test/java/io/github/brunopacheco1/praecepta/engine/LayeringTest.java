/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The layers the engine's packages form, checked on the compiled classes: the root package uses every other; {@code dmn}
 * uses {@code feel}, {@code beans} and {@code exceptions}; {@code feel} and {@code beans} use each other and nothing
 * else of the engine; {@code exceptions} uses {@code beans}, and {@link ValidationReport} alone of the root package. So
 * the language, the model and the DMN files know nothing of the engine that evaluates them.
 */
class LayeringTest {

    private static final String ROOT = "io.github.brunopacheco1.praecepta.engine";
    private static final String BEANS = ROOT + ".beans";
    private static final String DMN = ROOT + ".dmn";
    private static final String EXCEPTIONS = ROOT + ".exceptions";
    private static final String FEEL = ROOT + ".feel";

    private static JavaClasses engine;

    @BeforeAll
    static void readTheCompiledClasses() {
        engine = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    @Test
    void the_classes_of_every_package_are_read() {
        for (var layer : new String[] {ROOT, BEANS, DMN, EXCEPTIONS, FEEL}) {
            assertThat(engine.that(resideInAPackage(layer))).as(layer).isNotEmpty();
        }
        assertThat(engine.that(resideInAPackage(ROOT))).as("the tests are left out").noneMatch(type -> type.getSimpleName().endsWith("Test"));
        // a rule that passes has seen no use: this one is broken by the one use there is, which shows that uses are seen
        assertThat(noClasses().that().resideInAPackage(EXCEPTIONS).should().dependOnClassesThat().resideInAPackage(ROOT).evaluate(engine)
                .getFailureReport().getDetails()).as("the uses of the root package by the exceptions").isNotEmpty()
                .allMatch(use -> use.contains("InvalidDecisionTableException") && use.contains("ValidationReport"));
    }

    @Test
    void feel_and_beans_use_each_other_and_no_other_package_of_the_engine() {
        noClasses().that().resideInAnyPackage(FEEL, BEANS).should().dependOnClassesThat().resideInAnyPackage(ROOT, DMN, EXCEPTIONS)
                .check(engine);
    }

    @Test
    void dmn_does_not_use_the_root_package() {
        noClasses().that().resideInAPackage(DMN).should().dependOnClassesThat().resideInAPackage(ROOT).check(engine);
    }

    @Test
    void exceptions_use_beans_and_of_the_root_package_the_validation_report_alone() {
        noClasses().that().resideInAPackage(EXCEPTIONS).should().dependOnClassesThat().resideInAnyPackage(DMN, FEEL).check(engine);
        noClasses().that().resideInAPackage(EXCEPTIONS).should()
                .dependOnClassesThat(resideInAPackage(ROOT).and(not(belongTo(equivalentTo(ValidationReport.class))))).check(engine);
    }
}
