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
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.tck.TckSuite.Check;
import io.github.brunopacheco1.praecepta.tck.TckValue.Components;
import io.github.brunopacheco1.praecepta.tck.TckValue.Items;
import io.github.brunopacheco1.praecepta.tck.TckValue.Nil;
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

    /** Thrown while preparing a check that needs a feature Praecepta lacks; the message names it. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String feature) {
            super(feature, null, false, false);
        }
    }

    /** Decision tables already read, by model file and decision. */
    private final Map<String, DecisionTable> tables = new HashMap<>();

    /** UNSUPPORTED if the table or the inputs need a missing feature; else the comparison's outcome. */
    Outcome run(Check check) {
        try {
            var table = tables.computeIfAbsent(check.model().file() + "#" + check.decision(), key -> read(check));
            return evaluate(check, table, inputValues(check, table));
        } catch (Unsupported e) {
            return new Outcome(Status.UNSUPPORTED, e.getMessage());
        }
    }

    /** Builds an engine, evaluates the inputs and compares the result with the expected one: PASSED or FAILED. */
    private static Outcome evaluate(Check check, DecisionTable table, Map<String, String> inputs) {
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
            outputs = engine.evaluate(inputs);
        } catch (HitPolicyViolationException | InvalidInputException e) {
            return check.expectsError() ? Outcome.PASSED
                    : new Outcome(Status.FAILED, "expected %s, but evaluation threw: %s".formatted(check.expected(), e.getMessage()));
        }
        var actual = result(table, outputs);
        if (check.expectsError()) {
            return new Outcome(Status.FAILED, "expected an error, got " + actual);
        }
        boolean ordered = table.hitPolicy() != HitPolicy.COLLECT;
        return check.expected().matches(actual, ordered) ? Outcome.PASSED
                : new Outcome(Status.FAILED, "expected %s, got %s".formatted(check.expected(), actual));
    }

    private static DecisionTable read(Check check) {
        try (InputStream dmn = Files.newInputStream(check.model().file())) {
            return DmnReader.read(dmn, check.decision());
        } catch (InvalidDecisionTableException e) {
            throw new Unsupported(unsupportedFeature(e.getMessage()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The test case's inputs as text, by column and named input; missing and null inputs are left out. */
    private static Map<String, String> inputValues(Check check, DecisionTable table) {
        var values = new LinkedHashMap<String, String>();
        var columns = new LinkedHashMap<String, ColumnType>();
        table.inputs().forEach(column -> columns.put(column.name(), column.type()));
        columns.putAll(table.namedInputs()); // the inputs cells compare with
        columns.forEach((column, type) -> {
            var value = input(check, column, table.listInputs().contains(column) ? type : null);
            if (value != null) {
                values.put(column, value);
            }
        });
        return values;
    }

    /**
     * The text of an input column, or null. A qualified column ({@code applicant.age}) reads a component.
     * An input that holds lists takes a FEEL list, {@code ["fever", "cough"]}.
     *
     * @param listOf the type of the input's elements if it holds lists, else null
     */
    private static String input(Check check, String column, ColumnType listOf) {
        var type = listOf;
        var path = column.split("\\.");
        TckValue value = check.inputs().get(path[0]);
        if (value == null && check.model().decisions().contains(path[0])) {
            throw new Unsupported("inputs computed by other decisions (decision requirement graphs, #14)");
        }
        for (int i = 1; i < path.length && value != null; i++) {
            value = value instanceof Components components ? components.values().get(path[i]) : null;
        }
        if (type != null && value instanceof Items items) {
            return listText(items, type);
        }
        return switch (value) {
            case null -> null;
            case Nil nil -> null;
            case Items items -> throw new Unsupported("lists given to an input of single values");
            case Components components -> throw new Unsupported("structured inputs compared as a whole");
            case Simple simple -> text(simple);
        };
    }

    /**
     * A list of simple values as FEEL writes it: strings quoted, other values as their text, {@code null}
     * for a missing one.
     */
    private static String listText(Items items, ColumnType type) {
        var elements = new ArrayList<String>();
        for (var item : items.values()) {
            elements.add(switch (item) {
                case Nil nil -> "null";
                case Simple simple when type == ColumnType.STRING -> FeelLiterals.literal(simple.text());
                case Simple simple -> text(simple);
                default -> throw new Unsupported("lists of lists or of structured values");
            });
        }
        return "[" + String.join(", ", elements) + "]";
    }

    private static String text(Simple simple) {
        return switch (simple.type()) {
            case "", "string", "decimal", "double", "integer", "int", "long", "float", "date", "boolean", "time", "dateTime",
                 "duration", "dayTimeDuration", "yearMonthDuration" -> simple.text();
            default -> throw new Unsupported(unsupportedType(simple.type()));
        };
    }

    /** One output per matching rule; aggregations sum, min, max or count the single NUMBER output. */
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

            @Override
            public PraeceptaOutput count(int matches) {
                return new PraeceptaOutput(Map.of(column, String.valueOf(matches)));
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
        return text == null ? TckValue.NIL : new Simple(column.type().feelName(), text);
    }

    // ---- reasons ----------------------------------------------------------------------------------

    private static final Pattern TYPE = Pattern.compile("type '([^']*)' is not supported");
    private static final Pattern AGGREGATION = Pattern.compile("aggregation '([^']*)' is not supported");
    private static final Pattern EXPRESSION = Pattern.compile("input expression '([^']*)' is not supported");

    /**
     * Reader messages recognised by a fixed phrase, and the feature each one names. They are tried in
     * this order, after the patterns above.
     */
    private static final List<Map.Entry<String, String>> PHRASES = List.of(
            Map.entry("is a structured item definition", "columns of a structured type"),
            Map.entry("is a collection of collections", "lists of lists"),
            Map.entry("constrains a collection as a whole", "allowed values of a whole list"),
            Map.entry("is a collection", "list outputs"),
            Map.entry("is a time with a zone id", "times with a zone id, which have no fixed offset"),
            Map.entry("output entries must be literals", "output expressions beyond +, -, * and / of literals and inputs (#71)"));

    /**
     * A short, stable name for what {@link DmnReader} rejected, used to group results. A message that
     * matches nothing else is about a cell.
     */
    static String unsupportedFeature(String message) {
        var type = TYPE.matcher(message);
        if (type.find()) {
            return unsupportedType(type.group(1));
        }
        var aggregation = AGGREGATION.matcher(message);
        if (aggregation.find()) {
            return aggregation.group(1) + " aggregation";
        }
        if (EXPRESSION.matcher(message).find()) {
            return "input expressions other than a variable name";
        }
        for (var phrase : PHRASES) {
            if (message.contains(phrase.getKey())) {
                return phrase.getValue();
            }
        }
        return "input entries outside the supported FEEL subset";
    }

    private static String unsupportedType(String type) {
        var local = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return switch (local) {
            case "duration" -> "duration columns that are neither days and time nor years and months";
            default -> local + " columns";
        };
    }
}
