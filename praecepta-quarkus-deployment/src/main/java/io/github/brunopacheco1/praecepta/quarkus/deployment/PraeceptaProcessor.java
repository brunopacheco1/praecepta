/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus.deployment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.DecisionsException;
import io.github.brunopacheco1.praecepta.integration.DecisionsLoader;
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

    /** The prefix of the extension's settings, as messages name them. */
    static final String PREFIX = "quarkus.praecepta";

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
        var tables = new TreeMap<String, Table>();
        var problems = new ArrayList<String>();
        var declared = compileDeclaredTables(config, projectDirectory(curateOutcome), watched, problems);
        var finder = new TableFinder(classPath(archives, curateOutcome));
        var decisions = new DecisionsLoader(PREFIX, config.locations().orElse(null), config.failOn())
                .declared(declared.stream().map(PraeceptaProcessor::source).toList(), problems)
                .load(location -> {
                    var matches = finder.find(location);
                    watch(location, matches, watched);
                    matches.forEach(table -> tables.putIfAbsent(table.location(), table));
                    return matches.stream().map(PraeceptaProcessor::source).toList();
                }, new DecisionsLoader.Log() {
                    @Override
                    public void info(String message) {
                        LOG.info(message);
                    }

                    @Override
                    public void warn(String message) {
                        LOG.warn(message);
                    }
                });
        declared.forEach(table -> tables.putIfAbsent(table.location(), table));
        return new PraeceptaTablesBuildItem(List.copyOf(tables.values()), decisions);
    }

    private static TableSource source(Table table) {
        return TableSource.of(table.location(), table.dmn());
    }

    /**
     * Dev mode restarts the application when a table changes: for a class path pattern, when a matching resource
     * changes or one is added; for a {@code file:} pattern, when one of the files it found changes (a file added
     * there needs a manual restart, as Quarkus watches files, not directories).
     */
    private static void watch(Location location, List<Table> matches, BuildProducer<HotDeploymentWatchedFileBuildItem> watched) {
        if (location.classpath()) {
            watched.produce(HotDeploymentWatchedFileBuildItem.builder().setLocationPredicate(location::matches).build());
        } else {
            matches.forEach(table -> watched.produce(new HotDeploymentWatchedFileBuildItem(table.location())));
        }
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

    /** Compiles the Excel and CSV tables declared under {@code quarkus.praecepta.tables}, adding their mistakes to {@code problems}. */
    private static List<Table> compileDeclaredTables(PraeceptaBuildTimeConfig config, Path projectDirectory,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watched, List<String> problems) {
        var tables = new ArrayList<Table>();
        for (var entry : config.tables().entrySet()) {
            entry.getValue().source().ifPresent(source -> watched.produce(new HotDeploymentWatchedFileBuildItem(
                    DeclaredTables.resolve(source, projectDirectory).toString())));
            try {
                tables.add(DeclaredTables.compile(entry.getKey(), entry.getValue(), projectDirectory));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return tables;
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
