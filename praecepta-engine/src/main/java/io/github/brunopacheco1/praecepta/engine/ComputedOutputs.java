/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Issue;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * A rule's outputs that read inputs: those that name an input ({@link PraeceptaOutput#references()}) and those that are
 * FEEL expressions ({@link PraeceptaOutput#expressions()}), beside the rule's other outputs, as written. The engine
 * converts the outputs of every other rule once, when it is built; it computes these on every evaluation that selects
 * their rule.
 *
 * <p>{@link #of} builds and checks them when the engine is built. On evaluation, the engine computes each value, binds it
 * to its output's type ({@link #bound}) and gives the result ({@link Output}) to the output strategy. A class, not a
 * record: it holds an array and is never compared.
 */
final class ComputedOutputs {

    final Map<String, String> values;
    /** The outputs that read no input, as {@link ValueOutputs} takes them beside the values of the others. */
    final PraeceptaOutput literals;
    final Computed[] computed;

    private ComputedOutputs(Map<String, String> values, Computed[] computed) {
        this.values = values;
        this.literals = new PraeceptaOutput(values);
        this.computed = computed;
    }

    /**
     * How an output's computed value is given (DMN 1.5, 10.3.2.9.4): as it is, for an output whose type the table does
     * not declare, each entry giving its own (10.3.2.10, step 3c); as its text, for a string output, which takes any
     * value as its text, as it takes unquoted literals; else bound to the output's type, as it is where it conforms,
     * a date as a date and time at UTC midnight, a list of one as its element, and as null where it does not conform.
     */
    enum OutputBinding { AS_IS, TEXT, CONFORM }

    /**
     * One output of a rule that reads inputs: the output column, the expression that computes it, or, for an output that
     * names an input, that input's position in the evaluation order, and how its value is given.
     *
     * @param expression the expression, null for an output that names an input
     * @param column     the input it names, by position in the evaluation order; -1 for an expression
     * @param type       the output's type, for {@link OutputBinding#CONFORM}
     */
    record Computed(String output, FeelExpression expression, int column, OutputBinding binding, FeelType type) {

        /** Whether the output names an input, so it takes that input's value as its text, a number keeping its scale. */
        boolean reference() {
            return expression == null;
        }
    }

    /**
     * What building the computed outputs of rules needs: where the inputs are, the table's FEEL and its types, and where to
     * report the issues found in the output entries (one always null, say), which the validation report lists.
     */
    record Context(List<String> evaluationOrder, Set<String> lists, TableExpressions expressions, Map<String, FeelType> nameTypes,
            Map<String, ColumnType> outputTypes, Set<String> untypedOutputs, List<Issue> issues) {

        /** How an output's computed value is given: as it is if the table does not type it, as text if it is a string. */
        OutputBinding binding(String output) {
            if (untypedOutputs.contains(output)) {
                return OutputBinding.AS_IS;
            }
            var type = outputTypes.get(output);
            return type == null || type == ColumnType.STRING ? OutputBinding.TEXT : OutputBinding.CONFORM;
        }
    }

    // ---- when the engine is built -------------------------------------------------------------------

    /**
     * The outputs of {@code rule} that read inputs, checked: an output that names an input names one of the engine's
     * inputs that holds single values (the table's columns, or inputs its cells compare with), and none is in an output
     * priority list (see {@link HitPolicies#bitToRule}), where its place cannot be known before evaluation. An expression
     * may read any input, a list too ({@code Orders[1]}). An expression whose value is null whatever its inputs, as one
     * whose operands FEEL does not combine, or one of another type than its output, is reported
     * ({@link ValidationReport.Kind#ALWAYS_NULL}), and evaluated to null. {@code DmnReader} accepts the same names.
     */
    static ComputedOutputs of(Praeceptum rule, int ruleIndex, Context context) {
        var computed = new ArrayList<Computed>();
        rule.output().references().forEach((output, input) -> computed.add(new Computed(output, null,
                inputColumn(rule, output, input, context.evaluationOrder(), context.lists()), context.binding(output),
                outputType(context, output))));
        context.expressions().outputs(ruleIndex).forEach((output, expression) -> {
            var binding = context.binding(output);
            checkOutput(rule, output, expression, binding, context);
            computed.add(new Computed(output, expression, -1, binding, outputType(context, output)));
        });
        return new ComputedOutputs(rule.output().values(), computed.toArray(Computed[]::new));
    }

    private static FeelType outputType(Context context, String output) {
        var type = context.outputTypes().get(output);
        return type == null ? FeelType.ANY : FeelType.of(type);
    }

    /**
     * Reports an output entry whose value is null whatever its inputs ({@link ValidationReport.Kind#ALWAYS_NULL}): its
     * expression is (operands FEEL does not combine, DMN 1.5, 10.3.2.15), or it gives values of a type that does not
     * conform to its output's (10.3.2.9.4).
     */
    private static void checkOutput(Praeceptum rule, String output, FeelExpression expression, OutputBinding binding, Context context) {
        var reasons = new ArrayList<String>();
        var type = expression.type(context.nameTypes()::get, reasons::add);
        var where = "rule %s, output '%s': '%s'".formatted(rule.priority(), output, expression.text().strip());
        if (type instanceof FeelType.Null) {
            var reason = reasons.isEmpty() ? "it is null" : reasons.getFirst();
            context.issues().add(new Issue(ValidationReport.Kind.ALWAYS_NULL, List.of(rule.priority()),
                    "%s is null whatever its inputs: %s (DMN 1.5, 10.3.2.15)".formatted(where, reason), Map.of()));
        } else if (binding == OutputBinding.CONFORM && !mayConform(type, context.outputTypes().get(output))) {
            context.issues().add(new Issue(ValidationReport.Kind.ALWAYS_NULL, List.of(rule.priority()),
                    "%s gives a %s, but the output is a %s, so its value is null (DMN 1.5, 10.3.2.9.4)".formatted(where, type.feelName(),
                            context.outputTypes().get(output).feelName()), Map.of()));
        }
    }

    /**
     * Whether a value of type {@code type} may be bound to an output of type {@code output} as something other than null
     * (DMN 1.5, 10.3.2.9.4): a value of that type, a date for a date and time, a list (of one), or one of any type.
     */
    private static boolean mayConform(FeelType type, ColumnType output) {
        return switch (type) {
            case FeelType.Simple(var simple) -> simple == output || simple == ColumnType.DATE && output == ColumnType.DATE_TIME;
            case FeelType.ListOf(var element) -> mayConform(element, output);
            case FeelType.Any any -> true;
            default -> false;
        };
    }

    /** The position, in the evaluation order, of the input an output reads, which must hold single values. */
    private static int inputColumn(Praeceptum rule, String output, String input, List<String> evaluationOrder, Set<String> lists) {
        int column = evaluationOrder.indexOf(input);
        if (column < 0) {
            throw new InvalidDecisionTableException("Rule %s: output '%s' names '%s', which is not an input column %s"
                    .formatted(rule.priority(), output, input, evaluationOrder));
        }
        if (lists.contains(input)) {
            throw new InvalidDecisionTableException("Rule %s: output '%s' names '%s', which holds lists; an output holds one value"
                    .formatted(rule.priority(), output, input));
        }
        return column;
    }

    /**
     * The outputs that hold numbers, which ANY compares by value ({@code 1.50} equals {@code 1.5}): those
     * a {@linkplain PraeceptaEngine.Builder#table table} declares as numbers and, for outputs the table does not declare,
     * those some rule computes as a number (naming a number input, or a number expression).
     */
    static Set<String> numericOutputs(Map<String, ColumnType> declared, List<Praeceptum> rules, Map<String, ColumnType> types,
            TableExpressions expressions, Map<String, FeelType> nameTypes) {
        var numeric = new HashSet<String>();
        var number = FeelType.of(ColumnType.NUMBER);
        for (int r = 0; r < rules.size(); r++) {
            rules.get(r).output().references().forEach((output, input) -> {
                if (types.get(input) == ColumnType.NUMBER) {
                    numeric.add(output);
                }
            });
            expressions.outputs(r).forEach((output, expression) -> {
                if (number.equals(expression.type(nameTypes::get))) {
                    numeric.add(output);
                }
            });
        }
        declared.forEach((output, type) -> {
            if (type == ColumnType.NUMBER) {
                numeric.add(output);
            } else {
                numeric.remove(output);
            }
        });
        return Set.copyOf(numeric);
    }

    // ---- on every evaluation that selects the rule --------------------------------------------------

    /** A computed output's value, given as its output takes it ({@link OutputBinding}). */
    static Object bound(Object value, Computed computed, Consumer<String> errors) {
        if (value == null) {
            return null;
        }
        return switch (computed.binding()) {
            case AS_IS -> computed.reference() ? value : GraphValues.literalValue(value);
            case TEXT -> value instanceof String ? value : text(value, computed);
            case CONFORM -> {
                var conformed = GraphValues.conform(computed.reference() ? value : GraphValues.literalValue(value), computed.type(), 0);
                if (conformed == null && errors != null) {
                    errors.accept("output '%s': %s is not a %s, the output's type (DMN 1.5, 10.3.2.9.4)".formatted(computed.output(),
                            OutputText.of(value), computed.type().feelName()));
                }
                yield conformed;
            }
        };
    }

    /** A computed output's value as text: a computed number without trailing zeros, the value an output names as it is written. */
    private static String text(Object value, Computed computed) {
        return value instanceof BigDecimal number && !computed.reference() ? OutputText.numberText(number) : OutputText.of(value);
    }

    /**
     * The outputs of a rule that reads inputs: the values of those it computes, and, written only when asked for, all of
     * them as text, which an output strategy other than {@link ValueOutputs} takes. A class, not a record: it holds an
     * array and is never compared.
     */
    static final class Output {
        final ComputedOutputs outputs;
        /** By computed output: its value, bound to its output. */
        private final Object[] values;
        private PraeceptaOutput text;

        Output(ComputedOutputs outputs, Object[] values) {
            this.outputs = outputs;
            this.values = values;
        }

        /**
         * The outputs as text, the computed ones filled in with the text of their values; a computed number has no
         * trailing zeros, since FEEL numbers are values. An output that names an input gives the input's value as
         * {@link FeelLiterals#text} writes it, a number keeping its scale, as a literal output keeps it.
         */
        PraeceptaOutput text() {
            if (text == null) {
                var texts = new HashMap<>(outputs.values);
                for (int i = 0; i < values.length; i++) {
                    var computed = outputs.computed[i];
                    texts.put(computed.output(), values[i] == null ? null : ComputedOutputs.text(values[i], computed));
                }
                text = new PraeceptaOutput(texts);
            }
            return text;
        }

        /** The values of the computed outputs, by output; null for one that is null. */
        Map<String, Object> values() {
            var byOutput = new HashMap<String, Object>();
            for (int i = 0; i < values.length; i++) {
                byOutput.put(outputs.computed[i].output(), values[i]);
            }
            return byOutput;
        }

        /** Whether an output is missing: null as written, or computed to null. */
        boolean isNull(String output) {
            for (int i = 0; i < values.length; i++) {
                if (outputs.computed[i].output().equals(output)) {
                    return values[i] == null;
                }
            }
            return outputs.values.get(output) == null;
        }
    }
}
