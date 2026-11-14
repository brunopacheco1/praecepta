/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * DMN files come from outside (modelling tools, users), so any bytes must either load or be
 * rejected with an {@link InvalidDecisionTableException}; any other exception is a bug. That holds for a
 * single table ({@link DmnReader#read(java.io.InputStream)}) and for a whole decision model
 * ({@link DmnReader#readModel}), whose graph ({@link DecisionGraph}) must then build, evaluate each of its
 * decisions with no input data, with a value of each input data's type, and with values of other types (failing only
 * as an evaluation may: a decision that cannot be evaluated, an input value not of its type or one a table rejects, a
 * hit policy violated), and, written back as DMN, read back into a model that writes the same DMN. The values are
 * derived from the model's types, so the seeds, which are DMN files, keep their meaning.
 *
 * <p>A plain Jazzer fuzz target, so that both {@link DmnReaderFuzzTest} (JUnit, in every build) and
 * ClusterFuzzLite (see {@code .clusterfuzzlite/build.sh}) run it.
 */
@SuppressWarnings("exports") // Jazzer's API is on the class path, not a module
public final class DmnReaderFuzzer {

    private DmnReaderFuzzer() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        var bytes = data.consumeRemainingAsBytes();
        try {
            DmnReader.read(new ByteArrayInputStream(bytes));
        } catch (InvalidDecisionTableException rejected) {
            // the expected outcome for anything that is not a supported DMN decision table
        }
        try {
            var model = DmnReader.readModel(new ByteArrayInputStream(bytes));
            var graph = DecisionGraph.builder().model(model).logValidationReport(false).build();
            var typed = new HashMap<String, Object>();
            var mistyped = new HashMap<String, Object>();
            graph.inputData().forEach((name, type) -> {
                typed.put(name, value(type, 0));
                mistyped.put(name, type instanceof FeelType.Simple(var simple) && simple != ColumnType.STRING ? "x" : List.of("x", 1));
            });
            for (var decision : graph.decisions()) {
                evaluate(graph, decision, Map.of());
                evaluate(graph, decision, typed);
                evaluate(graph, decision, mistyped);
            }
            var written = DmnWriter.write(model);
            var read = DmnReader.readModel(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8)));
            if (!DmnWriter.write(read).equals(written)) {
                throw new IllegalStateException("a model written as DMN and read back writes otherwise:\n" + written);
            }
        } catch (InvalidDecisionTableException rejected) {
            // a model Praecepta does not support, or one with a requirement DMN cannot write
        }
    }

    private static void evaluate(DecisionGraph graph, String decision, Map<String, Object> inputData) {
        try {
            graph.evaluate(decision, inputData);
        } catch (InvalidDecisionTableException | InvalidInputException | HitPolicyViolationException e) {
            // a decision that cannot be evaluated, a value not of its type, or one a table rejects
        }
    }

    /**
     * A value of a type: a fixed one of each simple type, a list of one element, a context of every component; for any
     * type, a context with a number, a string and a list, which paths into it read. A model's types nest at most
     * {@link DmnReader#MAX_TYPE_DEPTH} levels, which the depth bounds again.
     */
    private static Object value(FeelType type, int depth) {
        if (depth > DmnReader.MAX_TYPE_DEPTH) {
            return null;
        }
        return switch (type) {
            case FeelType.Simple(var simple) -> switch (simple) {
                case STRING -> "a";
                case NUMBER -> BigDecimal.ONE;
                case BOOLEAN -> true;
                case DATE -> LocalDate.of(2025, 1, 31);
                case TIME -> LocalTime.of(10, 30);
                case DATE_TIME -> ZonedDateTime.of(2025, 1, 31, 10, 30, 0, 0, ZoneOffset.UTC);
                case DAY_TIME_DURATION -> Duration.ofHours(1);
                case YEAR_MONTH_DURATION -> Period.ofMonths(1);
            };
            case FeelType.ListOf(var element) -> {
                var list = new ArrayList<>();
                list.add(value(element, depth + 1));
                yield list;
            }
            case FeelType.Context(var components) -> {
                var context = new HashMap<String, Object>();
                components.forEach((name, component) -> context.put(name, value(component, depth + 1)));
                yield context;
            }
            default -> Map.of("a", 1, "b", "x", "c", List.of(2));
        };
    }
}
