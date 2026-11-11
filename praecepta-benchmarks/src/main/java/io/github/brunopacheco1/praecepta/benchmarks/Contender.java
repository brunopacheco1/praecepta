/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.camunda.dmn.DmnEngine;
import org.camunda.dmn.parser.ParsedDmn;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.compiler.AlphaNetworkOption;
import org.kie.dmn.core.compiler.DMNCompilerConfigurationImpl;
import org.kie.dmn.core.compiler.DMNCompilerImpl;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;

/**
 * A decision engine under test. Returns the ids of the matched rules, in hit-policy order. Every
 * engine, Praecepta included, is built from the same DMN document, as an application loading a
 * {@code .dmn} file would do.
 */
public sealed interface Contender permits Contender.Praecepta, Contender.Camunda, Contender.Drools {

    List<String> evaluate(Map<String, Object> input);

    static Contender create(String engine, Table table) {
        return create(engine, DmnWriter.write(table.decisionTable()), table.inputs(), table.types());
    }

    /**
     * @param dmn    the DMN document, with one decision table named {@link Table#DECISION}
     * @param inputs the table's input columns, which Praecepta binds to the input map by type
     * @param types  their types
     */
    static Contender create(String engine, String dmn, List<String> inputs, List<ColumnType> types) {
        return switch (engine) {
            case "praecepta" -> new Praecepta(dmn, inputs, types);
            case "camunda" -> new Camunda(dmn);
            case "drools" -> new Drools(dmn, false);
            case "drools-alphanetwork" -> new Drools(dmn, true);
            default -> throw new IllegalArgumentException("Unknown engine: " + engine);
        };
    }

    /**
     * Praecepta built straight from the table's rules, without DMN. The benchmarks check every engine,
     * including Praecepta read from DMN, against it.
     */
    static Contender reference(Table table) {
        var builder = PraeceptaEngine.<Map<String, Object>, String>builder()
                .hitPolicy(table.hitPolicy())
                .rules(table.rules());
        if (table.hitPolicy() == HitPolicy.PRIORITY) {
            builder.outputPriority(Table.PRIORITY_OUTPUT, Table.PRIORITY_VALUES);
        }
        return new Praecepta(builder, table.inputs(), table.types());
    }

    /** Praecepta through its public API, exactly as an application would call it. */
    final class Praecepta implements Contender {
        private final PraeceptaEngine<Map<String, Object>, String> engine;

        Praecepta(String dmn, List<String> inputs, List<ColumnType> types) {
            this(PraeceptaEngine.<Map<String, Object>, String>builder()
                    .table(DmnReader.read(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)))), inputs, types);
        }

        private Praecepta(PraeceptaEngine.Builder<Map<String, Object>, String> builder, List<String> inputs,
                          List<ColumnType> types) {
            builder.output(output -> output.values().get(Table.ID_OUTPUT));
            for (int c = 0; c < inputs.size(); c++) {
                var column = inputs.get(c);
                switch (types.get(c)) {
                    case STRING -> builder.input(column, input -> (String) input.get(column));
                    case NUMBER -> builder.numberInput(column, input -> (Number) input.get(column));
                    case DATE -> builder.dateInput(column, input -> (LocalDate) input.get(column));
                    case BOOLEAN -> builder.booleanInput(column, input -> (Boolean) input.get(column));
                    case TIME -> builder.timeInput(column, input -> (LocalTime) input.get(column));
                    case DATE_TIME -> builder.dateTimeInput(column, input -> (LocalDateTime) input.get(column));
                    case DAY_TIME_DURATION -> builder.durationInput(column, input -> (java.time.Duration) input.get(column));
                    case YEAR_MONTH_DURATION -> builder.periodInput(column, input -> (java.time.Period) input.get(column));
                }
            }
            engine = builder.build();
        }

        @Override
        public List<String> evaluate(Map<String, Object> input) {
            return engine.evaluate(input);
        }
    }

    /**
     * Camunda 8's DMN engine (dmn-scala with feel-scala), the engine Zeebe embeds. It reads the
     * standard DMN types, so it gets the same model as Drools. Camunda 7 (community edition ended at
     * 7.24.0) and its legacy JUEL-based FEEL mode were benchmarked up to Phase 3.
     */
    final class Camunda implements Contender {
        private final DmnEngine engine;
        private final ParsedDmn dmn;

        Camunda(String document) {
            engine = new DmnEngine.Builder().build();
            var parsed = engine.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
            if (parsed.isLeft()) {
                throw new IllegalStateException("Camunda could not parse the DMN model: " + parsed.swap().toOption().get());
            }
            dmn = parsed.toOption().get();
        }

        @Override
        public List<String> evaluate(Map<String, Object> input) {
            var result = engine.evalByName(dmn, Table.DECISION, input);
            if (result.isLeft()) {
                throw new IllegalStateException("Camunda failed to evaluate " + input + ": " + result.swap().toOption().get());
            }
            var ids = new ArrayList<String>();
            collectIds(result.toOption().get().value(), ids);
            return ids;
        }

        private static void collectIds(Object value, List<String> ids) {
            switch (value) {
                case null -> { }
                case scala.None$ none -> { }
                case java.util.Collection<?> rows -> rows.forEach(row -> collectIds(row, ids));
                case scala.collection.Iterable<?> rows when !(value instanceof scala.collection.Map<?, ?>) ->
                        rows.foreach(row -> {
                            collectIds(row, ids);
                            return null;
                        });
                case Map<?, ?> row -> ids.add(String.valueOf(row.get(Table.ID_OUTPUT)));
                case scala.collection.Map<?, ?> row -> {
                    @SuppressWarnings("unchecked")
                    var columns = (scala.collection.Map<String, Object>) row;
                    collectIds(columns.get(Table.ID_OUTPUT).getOrElse(() -> null), ids);
                }
                default -> ids.add(String.valueOf(value));
            }
        }
    }

    /**
     * Drools (Apache KIE) DMN, with defaults ({@code drools}) or with its experimental alpha-network
     * compilation forced on this compiler's configuration ({@code drools-alphanetwork}).
     *
     * <p>{@code drools-alphanetwork} is <b>not</b> in the default benchmark matrix. On Drools 10.2.0 it
     * cannot compile tables with more than one output column, nor RULE_ORDER tables, and on the tables
     * it does compile it disagrees with default Drools (and every other engine) on ~99% of inputs. The
     * documented system property {@value AlphaNetworkOption#PROPERTY_NAME} is ignored by
     * {@code DMNRuntimeBuilder}. Re-check on a newer Drools with
     * {@code java -jar benchmarks.jar -p engine=drools-alphanetwork -p table=synthetic-100}: the
     * pre-run cross-check aborts until it agrees.
     */
    final class Drools implements Contender {
        private final DMNRuntime runtime;
        private final DMNModel model;

        Drools(String dmn, boolean alphaNetwork) {
            var resource = ResourceFactory.newReaderResource(new StringReader(dmn), "UTF-8");
            resource.setSourcePath(Table.DECISION + ".dmn");
            runtime = DMNRuntimeBuilder.fromDefaults()
                    .buildConfigurationUsingCustomCompiler(configuration -> {
                        ((DMNCompilerConfigurationImpl) configuration)
                                .setProperty(AlphaNetworkOption.PROPERTY_NAME, String.valueOf(alphaNetwork));
                        return new DMNCompilerImpl(configuration);
                    })
                    .fromResources(List.of(resource))
                    .getOrElseThrow(cause -> new IllegalStateException("Drools could not compile the DMN model", cause));
            model = runtime.getModels().getFirst();
            if (model.hasErrors()) {
                throw new IllegalStateException("Drools DMN model has errors: " + model.getMessages());
            }
        }

        @Override
        public List<String> evaluate(Map<String, Object> input) {
            var context = runtime.newContext();
            input.forEach(context::set);
            var result = runtime.evaluateByName(model, context, Table.DECISION)
                    .getDecisionResultByName(Table.DECISION)
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
