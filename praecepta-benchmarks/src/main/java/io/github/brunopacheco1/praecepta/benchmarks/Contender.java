package io.github.brunopacheco1.praecepta.benchmarks;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;

/** A decision engine under test. Returns the ids of the matched rules, in hit-policy order. */
public sealed interface Contender permits Contender.Praecepta, Contender.Camunda, Contender.Drools {

    List<String> evaluate(Map<String, String> input);

    static Contender create(String engine, Table table) {
        return switch (engine) {
            case "praecepta" -> new Praecepta(table);
            case "camunda" -> new Camunda(table, false);
            case "camunda-legacy-feel" -> new Camunda(table, true);
            case "drools" -> new Drools(table);
            default -> throw new IllegalArgumentException("Unknown engine: " + engine);
        };
    }

    /** Praecepta through its public API, exactly as an application would call it. */
    final class Praecepta implements Contender {
        private final PraeceptaEngine<Map<String, String>, String> engine;

        Praecepta(Table table) {
            var builder = PraeceptaEngine.<Map<String, String>, String>builder()
                    .hitPolicy(table.hitPolicy())
                    .output(output -> output.values().get(Table.ID_OUTPUT))
                    .rules(table.rules());
            for (var column : table.inputs()) {
                builder.input(column, input -> input.get(column));
            }
            engine = builder.build();
        }

        @Override
        public List<String> evaluate(Map<String, String> input) {
            return engine.evaluate(input);
        }
    }

    /**
     * Camunda 7 DMN. With its default FEEL engine (feel-scala) evaluation is roughly 150x slower on
     * these tables than with the legacy JUEL-based FEEL engine, which still supports the simple
     * unary tests used here, so both configurations are benchmarked.
     */
    final class Camunda implements Contender {
        private final DmnEngine engine;
        private final DmnDecision decision;

        Camunda(Table table, boolean legacyFeel) {
            var configuration = (DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration();
            configuration.enableFeelLegacyBehavior(legacyFeel);
            engine = configuration.buildEngine();
            var xml = DmnWriter.write(table).getBytes(StandardCharsets.UTF_8);
            decision = engine.parseDecision(DmnWriter.DECISION, new ByteArrayInputStream(xml));
        }

        @Override
        public List<String> evaluate(Map<String, String> input) {
            var result = engine.evaluateDecisionTable(decision, new HashMap<String, Object>(input));
            var ids = new ArrayList<String>(result.size());
            for (var row : result) {
                ids.add(row.getEntry(Table.ID_OUTPUT));
            }
            return ids;
        }
    }

    final class Drools implements Contender {
        private final DMNRuntime runtime;
        private final DMNModel model;

        Drools(Table table) {
            var resource = ResourceFactory.newReaderResource(new StringReader(DmnWriter.write(table)), "UTF-8");
            resource.setSourcePath(table.name() + ".dmn");
            runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(resource))
                    .getOrElseThrow(cause -> new IllegalStateException("Drools could not compile the DMN model", cause));
            model = runtime.getModels().getFirst();
            if (model.hasErrors()) {
                throw new IllegalStateException("Drools DMN model has errors: " + model.getMessages());
            }
        }

        @Override
        public List<String> evaluate(Map<String, String> input) {
            var context = runtime.newContext();
            input.forEach(context::set);
            var result = runtime.evaluateByName(model, context, DmnWriter.DECISION)
                    .getDecisionResultByName(DmnWriter.DECISION)
                    .getResult();
            return ids(result);
        }

        private static List<String> ids(Object result) {
            if (result == null) {
                return List.of();
            }
            if (result instanceof List<?> rows) {
                var ids = new ArrayList<String>(rows.size());
                for (var row : rows) {
                    ids.addAll(ids(row));
                }
                return ids;
            }
            if (result instanceof Map<?, ?> row) {
                return List.of(String.valueOf(row.get(Table.ID_OUTPUT)));
            }
            // single-output tables return the bare value
            return List.of(String.valueOf(result));
        }
    }
}
