/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckValue.Components;
import io.github.brunopacheco1.praecepta.tck.TckValue.Items;
import io.github.brunopacheco1.praecepta.tck.TckValue.Simple;

/**
 * Runs one {@link Check}: reads the decision with {@link DmnReader}, builds an engine with map inputs,
 * evaluates the test case's inputs and compares the result with the expected one.
 *
 * <p>DMN reports errors (a hit policy violation, an input outside its value list) as a null result
 * flagged {@code errorResult}; Praecepta throws instead, so a check flagged {@code errorResult} passes
 * when evaluation throws {@link HitPolicyViolationException} or {@link InvalidInputException}.
 */
final class TckRunner {

    enum Status {
        PASSED,
        FAILED,
        UNSUPPORTED,
    }

    /**
     * @param detail for UNSUPPORTED, the feature Praecepta lacks; for FAILED, what differed; else empty
     */
    record Outcome(Status status, String detail) {
        static final Outcome PASSED = new Outcome(Status.PASSED, "");
    }

    private final Map<String, Object> tables = new HashMap<>();

    Outcome run(Check check) {
        var read = tables.computeIfAbsent(check.model().file() + "#" + check.decision(), key -> read(check));
        if (read instanceof Outcome unsupported) {
            return unsupported;
        }
        var table = (DecisionTable) read;

        var values = new LinkedHashMap<String, String>();
        for (var column : table.inputs()) {
            var value = input(check, column.name());
            if (value instanceof Outcome unsupported) {
                return unsupported;
            }
            if (value != null) {
                values.put(column.name(), (String) value);
            }
        }

        PraeceptaEngine<Map<String, String>, PraeceptaOutput> engine;
        try {
            engine = PraeceptaEngine.<Map<String, String>, PraeceptaOutput>builder()
                    .table(table)
                    .inputs(PraeceptaInput::new)
                    .output(outputs(table))
                    .build();
        } catch (RuntimeException e) {
            return new Outcome(Status.FAILED, "the engine cannot be built: " + e.getMessage());
        }

        List<PraeceptaOutput> outputs;
        try {
            outputs = engine.evaluate(values);
        } catch (HitPolicyViolationException | InvalidInputException e) {
            return check.error() ? Outcome.PASSED
                    : new Outcome(Status.FAILED, "expected %s, but evaluation threw: %s".formatted(check.expected(), e.getMessage()));
        }
        var actual = result(table, outputs);
        if (check.error()) {
            return new Outcome(Status.FAILED, "expected an error, got " + actual);
        }
        boolean ordered = table.hitPolicy() != HitPolicy.COLLECT;
        return check.expected().matches(actual, ordered) ? Outcome.PASSED
                : new Outcome(Status.FAILED, "expected %s, got %s".formatted(check.expected(), actual));
    }

    /** The decision table, or the UNSUPPORTED outcome explaining why it cannot be read. */
    private static Object read(Check check) {
        try (InputStream dmn = Files.newInputStream(check.model().file())) {
            return DmnReader.read(dmn, check.decision());
        } catch (InvalidDecisionTableException e) {
            return new Outcome(Status.UNSUPPORTED, unsupportedFeature(e.getMessage()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The text value of an input column (null if missing), or the UNSUPPORTED outcome. A qualified
     * column ({@code applicant.age}) reads a component of a structured input.
     */
    private static Object input(Check check, String column) {
        var path = column.split("\\.");
        TckValue value = check.inputs().get(path[0]);
        if (value == null) {
            return check.model().decisions().contains(path[0])
                    ? new Outcome(Status.UNSUPPORTED, "inputs computed by other decisions (decision requirement graphs, #14)")
                    : null;
        }
        for (int i = 1; i < path.length && value != null; i++) {
            value = value instanceof Components components ? components.values().get(path[i]) : null;
        }
        return switch (value) {
            case null -> null;
            case TckValue.Nil nil -> null;
            case Items items -> new Outcome(Status.UNSUPPORTED, "list inputs (#25)");
            case Components components -> new Outcome(Status.UNSUPPORTED, "structured inputs compared as a whole");
            case Simple simple -> switch (simple.type()) {
                case "", "string", "decimal", "double", "integer", "int", "long", "float", "date" -> simple.text();
                default -> new Outcome(Status.UNSUPPORTED, unsupportedType(simple.type()));
            };
        };
    }

    /** One output per matching rule; aggregations sum, min or max the single NUMBER output. */
    private static OutputStrategy<PraeceptaOutput> outputs(DecisionTable table) {
        var column = table.outputs().getFirst().name();
        return new OutputStrategy<>() {
            @Override
            public PraeceptaOutput transform(PraeceptaOutput output) {
                return output;
            }

            @Override
            public PraeceptaOutput sum(PraeceptaOutput a, PraeceptaOutput b) {
                return combine(a, b, BigDecimal::add);
            }

            @Override
            public PraeceptaOutput min(PraeceptaOutput a, PraeceptaOutput b) {
                return combine(a, b, BigDecimal::min);
            }

            @Override
            public PraeceptaOutput max(PraeceptaOutput a, PraeceptaOutput b) {
                return combine(a, b, BigDecimal::max);
            }

            private PraeceptaOutput combine(PraeceptaOutput a, PraeceptaOutput b, BinaryOperator<BigDecimal> operator) {
                var x = a.values().get(column);
                var y = b.values().get(column);
                if (x == null || y == null) {
                    return x == null ? b : a;
                }
                return new PraeceptaOutput(Map.of(column, operator.apply(new BigDecimal(x), new BigDecimal(y)).toPlainString()));
            }
        };
    }

    /** The DMN result: one value (or none) for single-hit policies and aggregations, else a list. */
    private static TckValue result(DecisionTable table, List<PraeceptaOutput> outputs) {
        boolean singleHit = switch (table.hitPolicy()) {
            case UNIQUE, ANY, FIRST, PRIORITY -> true;
            case COLLECT -> table.aggregation() != null;
            case RULE_ORDER, OUTPUT_ORDER -> false;
        };
        if (singleHit) {
            return outputs.isEmpty() ? TckValue.NIL : value(table, outputs.getFirst());
        }
        var items = new ArrayList<TckValue>();
        outputs.forEach(output -> items.add(value(table, output)));
        return new Items(items);
    }

    /** A single-output table's value is the output itself; a multi-output table's is a context. */
    private static TckValue value(DecisionTable table, PraeceptaOutput output) {
        if (table.outputs().size() == 1) {
            return simple(table.outputs().getFirst(), output);
        }
        var components = new LinkedHashMap<String, TckValue>();
        table.outputs().forEach(column -> components.put(column.name(), simple(column, output)));
        return new Components(components);
    }

    private static TckValue simple(DecisionTable.OutputColumn column, PraeceptaOutput output) {
        var text = output.values().get(column.name());
        return text == null ? TckValue.NIL : new Simple(column.type().name().toLowerCase(Locale.ROOT), text);
    }

    // ---- reasons ----------------------------------------------------------------------------------

    private static final Pattern TYPE = Pattern.compile("type '([^']*)' is not supported");
    private static final Pattern AGGREGATION = Pattern.compile("aggregation '([^']*)' is not supported");
    private static final Pattern EXPRESSION = Pattern.compile("input expression '([^']*)' is not supported");
    /** A cell naming a variable where a literal is expected, e.g. {@code > otherDate}. */
    private static final Pattern REFERENCE = Pattern.compile("'[\\p{L}_][\\p{L}\\p{N}_]*' is not a (number|date)");

    /** A short, stable name for what {@link DmnReader} rejected, used to group results. */
    static String unsupportedFeature(String message) {
        var type = TYPE.matcher(message);
        if (type.find()) {
            return unsupportedType(type.group(1));
        }
        var aggregation = AGGREGATION.matcher(message);
        if (aggregation.find()) {
            return aggregation.group(1) + " aggregation" + (aggregation.group(1).equals("COUNT") ? " (#15)" : "");
        }
        var expression = EXPRESSION.matcher(message);
        if (expression.find()) {
            return expression.group(1).matches("[\\p{L}_?][\\p{L}\\p{N}_? ]*")
                    ? "input names with spaces" : "input expressions other than a variable name";
        }
        if (REFERENCE.matcher(message).find()) {
            return "input entries that refer to other inputs";
        }
        if (message.contains("output entries must be literals")) {
            return "output entries that are expressions";
        }
        return "input entries outside the supported FEEL subset";
    }

    private static String unsupportedType(String type) {
        var local = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return switch (local) {
            case "boolean", "time", "dateTime", "date and time" -> local + " columns (#12)";
            case "dayTimeDuration", "yearMonthDuration", "duration", "days and time duration", "years and months duration" ->
                    "duration columns";
            default -> local.length() > 1 && local.startsWith("t") && Character.isUpperCase(local.charAt(1))
                    ? "columns typed by an item definition" : local + " columns";
        };
    }
}
