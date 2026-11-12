/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.quarkus;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.TableSource;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Builds the application's decisions from the DMN the extension's build step found and validated. Called
 * by the code Quarkus generates at build time, not by applications.
 *
 * <p>The build step writes every table it validated (DMN files as found, Excel and CSV tables as the DMN
 * they compiled to) as a resource of the application, and records here where each one came from. The
 * tables are read when the application initialises its static state: at startup on the JVM, and while the
 * image is built for a native executable, whose engines then live in the image heap. They were validated
 * at build time with the configured {@code fail-on}, so nothing here fails on validation issues.
 */
@Recorder
public class PraeceptaRecorder {

    /**
     * Reads the validated tables and builds their engines.
     *
     * @param locations where each table came from, for reports ({@code Decision.location()})
     * @param resources the class path resource holding each table's DMN, in the same order
     * @return the application's decisions
     * @throws io.github.brunopacheco1.praecepta.integration.DecisionsException if a table cannot be read
     *                                                                          back, which the build step
     *                                                                          rules out
     */
    public RuntimeValue<Decisions> decisions(List<String> locations, List<String> resources) {
        var loader = Thread.currentThread().getContextClassLoader();
        var sources = new ArrayList<TableSource>(locations.size());
        for (int i = 0; i < locations.size(); i++) {
            var resource = resources.get(i);
            sources.add(new TableSource(locations.get(i), () -> {
                var stream = loader.getResourceAsStream(resource);
                if (stream == null) {
                    throw new FileNotFoundException("resource " + resource + " is missing from the application");
                }
                return stream;
            }));
        }
        return new RuntimeValue<>(Decisions.load(sources, null));
    }

    /**
     * The engine of one decision, for its bean.
     *
     * @param decisions the application's decisions
     * @param name      the decision's name
     */
    public Supplier<PraeceptaEngine<Map<String, String>, Map<String, String>>> engine(RuntimeValue<Decisions> decisions, String name) {
        return () -> decisions.getValue().engine(name);
    }
}
