/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.TableLocations;
import io.github.brunopacheco1.praecepta.integration.TableLocations.Location;
import io.github.brunopacheco1.praecepta.integration.TableSource;
import io.github.brunopacheco1.praecepta.quarkus.PraeceptaRecorder;
import io.github.brunopacheco1.praecepta.quarkus.deployment.PraeceptaTablesBuildItem.Table;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.paths.PathTree;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

/**
 * The extension's build steps: find the DMN files, compile the declared Excel and CSV tables, validate
 * everything and fail the build on the configured issues, then hand the validated DMN to the runtime as
 * beans. The design is shared with the Spring Boot and Micronaut integrations (docs/INTEGRATIONS.md).
 */
public class PraeceptaProcessor {

    /** The feature's name in Quarkus' startup log. */
    static final String FEATURE = "praecepta";

    /** Where the validated DMN documents go in the application, one resource per table. */
    static final String RESOURCES = "META-INF/praecepta/tables/";

    private static final Logger LOG = Logger.getLogger(PraeceptaProcessor.class);

    private static final DotName ENGINE = DotName.createSimple(PraeceptaEngine.class);
    private static final Type TEXT_MAP = ParameterizedType.create(DotName.createSimple(Map.class),
            new Type[] {ClassType.create(String.class), ClassType.create(String.class)}, null);

    /** {@code PraeceptaEngine<Map<String, String>, Map<String, String>>}, the type of every engine bean. */
    static final Type ENGINE_TYPE = ParameterizedType.create(ENGINE, new Type[] {TEXT_MAP, TEXT_MAP}, null);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Finds, compiles and validates the tables. Every problem is listed at once, as one
     * {@link DecisionsException}, which fails the build (and shows on dev mode's error page).
     */
    @BuildStep
    PraeceptaTablesBuildItem validate(PraeceptaBuildTimeConfig config, ApplicationArchivesBuildItem archives,
            CurateOutcomeBuildItem curateOutcome, BuildProducer<HotDeploymentWatchedFileBuildItem> watched) {
        Severity failOn;
        try {
            failOn = Decisions.failOn(config.failOn());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("quarkus.praecepta." + e.getMessage(), e);
        }
        var problems = new ArrayList<String>();
        var tables = new ArrayList<Table>();
        findDmnFiles(config, classPath(archives, curateOutcome), watched, problems, tables);
        compileDeclaredTables(config, projectDirectory(curateOutcome), watched, problems, tables);

        Decisions decisions = Decisions.empty();
        try {
            decisions = Decisions.load(tables.stream().map(table -> TableSource.of(table.location(), table.dmn())).toList(), failOn);
        } catch (DecisionsException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) {
            throw new DecisionsException(problems);
        }
        for (var decision : decisions.all()) {
            for (var issue : decision.report().issues()) {
                LOG.warnf("%s: %s", decision.name(), issue);
            }
        }
        LOG.debugf("Praecepta validated %d decision(s): %s", decisions.names().size(), decisions.names());
        return new PraeceptaTablesBuildItem(tables, decisions);
    }

    /**
     * Writes the validated DMN into the application and registers the beans: the application's
     * {@link Decisions}, and one engine per decision, qualified by the decision's name.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void beans(PraeceptaTablesBuildItem validated, PraeceptaRecorder recorder, BuildProducer<GeneratedResourceBuildItem> resources,
            BuildProducer<NativeImageResourceBuildItem> nativeResources, BuildProducer<SyntheticBeanBuildItem> beans) {
        var locations = new ArrayList<String>();
        var names = new ArrayList<String>();
        var tables = validated.tables();
        for (int i = 0; i < tables.size(); i++) {
            var name = RESOURCES + i + ".dmn";
            resources.produce(new GeneratedResourceBuildItem(name, tables.get(i).dmn()));
            locations.add(tables.get(i).location());
            names.add(name);
        }
        if (!names.isEmpty()) {
            nativeResources.produce(new NativeImageResourceBuildItem(names));
        }
        var decisions = recorder.decisions(locations, names);
        beans.produce(SyntheticBeanBuildItem.configure(Decisions.class)
                .scope(Singleton.class)
                .unremovable()
                .startup()
                .runtimeValue(decisions)
                .done());
        for (var name : validated.decisions().names()) {
            beans.produce(SyntheticBeanBuildItem.create(PraeceptaEngine.class)
                    .addType(ENGINE_TYPE)
                    .addQualifier(AnnotationInstance.builder(Identifier.class).value(name).build())
                    .addQualifier(AnnotationInstance.builder(Named.class).value(name).build())
                    .scope(Singleton.class)
                    .unremovable()
                    .supplier(recorder.engine(decisions, name))
                    .done());
        }
    }

    private static void findDmnFiles(PraeceptaBuildTimeConfig config, List<PathTree> classPath,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watched, List<String> problems, List<Table> tables) {
        var explicit = config.locations().isPresent();
        var patterns = config.locations().orElse(List.of(TableLocations.DEFAULT)).stream()
                .map(String::strip)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
        var finder = new TableFinder(classPath);
        var found = new LinkedHashMap<String, Table>();
        for (var pattern : patterns) {
            Location location;
            List<Table> matches;
            try {
                location = Location.parse(pattern);
                matches = finder.find(location);
            } catch (IllegalArgumentException | UncheckedIOException e) {
                problems.add(pattern + ": " + e.getMessage());
                continue;
            }
            if (location.classpath()) {
                // dev mode restarts when a matching resource changes, or one is added
                watched.produce(HotDeploymentWatchedFileBuildItem.builder().setLocationPredicate(location::matches).build());
            } else {
                matches.forEach(table -> watched.produce(new HotDeploymentWatchedFileBuildItem(table.location())));
            }
            if (matches.isEmpty()) {
                if (explicit) {
                    problems.add(pattern + ": no DMN file matches this location (quarkus.praecepta.locations)");
                } else {
                    LOG.infof("No decision tables found at %s", pattern);
                }
            }
            matches.forEach(table -> found.putIfAbsent(table.location(), table));
        }
        tables.addAll(found.values());
    }

    private static void compileDeclaredTables(PraeceptaBuildTimeConfig config, Path projectDirectory,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watched, List<String> problems, List<Table> tables) {
        for (var entry : config.tables().entrySet()) {
            entry.getValue().source().ifPresent(source -> watched.produce(new HotDeploymentWatchedFileBuildItem(
                    DeclaredTables.resolve(source, projectDirectory).toString())));
            try {
                tables.add(DeclaredTables.compile(entry.getKey(), entry.getValue(), projectDirectory));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
    }

    /**
     * The application's class path: its own classes and resources, the other application archives (indexed
     * dependencies, and archives added to the build), then every runtime dependency, each root once.
     */
    private static List<PathTree> classPath(ApplicationArchivesBuildItem archives, CurateOutcomeBuildItem curateOutcome) {
        var seen = new HashSet<Path>();
        var trees = new ArrayList<PathTree>();
        for (var archive : archives.getAllArchives()) {
            for (var root : archive.getResolvedPaths()) {
                if (seen.add(root)) {
                    trees.add(PathTree.ofDirectoryOrArchive(root));
                }
            }
        }
        for (var dependency : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
            var paths = dependency.getResolvedPaths();
            if (paths.stream().noneMatch(seen::contains)) {
                paths.forEach(seen::add);
                trees.add(dependency.getContentTree());
            }
        }
        return trees;
    }

    /** The project's directory, which relative table sources are resolved against; the working directory if unknown. */
    private static Path projectDirectory(CurateOutcomeBuildItem curateOutcome) {
        var module = curateOutcome.getApplicationModel().getApplicationModule();
        return module == null || module.getModuleDir() == null ? Path.of("").toAbsolutePath() : module.getModuleDir().toPath();
    }
}
