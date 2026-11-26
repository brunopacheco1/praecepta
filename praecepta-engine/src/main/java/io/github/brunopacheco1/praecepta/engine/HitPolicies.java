/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.IntStream;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * Prepares, when an engine is built, what its hit policy needs to choose among the matching rules: the order of the
 * rules' bits, so that the lowest set bit of a match is the first rule or the one of highest output priority
 * ({@link #bitToRule}); for ANY, the rules with equal outputs ({@link #sameOutputMasks}); and for a COLLECT aggregation,
 * how the outputs combine and which rules leave the aggregated output missing.
 */
final class HitPolicies {

    private HitPolicies() {
    }

    /**
     * The rule at each bit. The lowest set bit wins, so bits follow rule order or, with output
     * priorities, priority order (ties keep rule order).
     *
     * @param numericOutputs the outputs whose values are found in their priority lists by number
     */
    static int[] bitToRule(List<Praeceptum> rules, Map<String, List<String>> outputPriority, Set<String> numericOutputs) {
        if (outputPriority.isEmpty()) {
            return IntStream.range(0, rules.size()).toArray();
        }
        var keys = rules.stream().map(rule -> priorityKey(rule, outputPriority, numericOutputs)).toArray(int[][]::new);
        Comparator<int[]> lexicographic = Arrays::compare;
        return IntStream.range(0, rules.size()).boxed()
                .sorted(Comparator.comparing(rule -> keys[rule], lexicographic)) // stable: ties keep rule order
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * The position of each of the rule's outputs in its priority list, in the order the lists were
     * declared; a number output's value is found by number ({@code 1.50} in a list that holds {@code 1.5}).
     * Compared lexicographically, a smaller key is a higher priority.
     */
    private static int[] priorityKey(Praeceptum rule, Map<String, List<String>> outputPriority, Set<String> numericOutputs) {
        var key = new int[outputPriority.size()];
        int i = 0;
        for (var priorityList : outputPriority.entrySet()) {
            var output = priorityList.getKey();
            var input = rule.output().references().get(output);
            if (input != null) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' names input '%s', so it has no place in its priority list"
                        .formatted(rule.priority(), output, input));
            }
            var expression = rule.output().expressions().get(output);
            if (expression != null) {
                throw new InvalidDecisionTableException("Rule %s: output '%s' is the expression %s, so it has no place in its priority list"
                        .formatted(rule.priority(), output, expression));
            }
            var value = rule.output().values().get(output);
            var values = priorityList.getValue();
            boolean number = numericOutputs.contains(output);
            int position = IntStream.range(0, values.size()).filter(p -> OutputText.sameValue(values.get(p), value, number)).findFirst().orElse(-1);
            if (position < 0) {
                throw new InvalidDecisionTableException("Rule %s: output %s=%s is not in its priority list %s"
                        .formatted(rule.priority(), output, value, priorityList.getValue()));
            }
            key[i++] = position;
        }
        return key;
    }

    /**
     * Per bit, the mask of the rules with the same outputs as the bit's rule (number outputs compared by
     * value); equal outputs share one mask, stored in its form ({@link Masks}): a rule whose outputs no
     * other rule has holds one bit.
     */
    static long[][] sameOutputMasks(List<Praeceptum> rules, int[] bitToRule, int words, Masks.Form form,
            Set<String> numericOutputs) {
        var maskByOutput = new HashMap<PraeceptaOutput, long[]>();
        for (int bit = 0; bit < bitToRule.length; bit++) {
            var output = OutputText.comparable(rules.get(bitToRule[bit]).output(), numericOutputs);
            BitsetIndex.set(maskByOutput.computeIfAbsent(output, any -> new long[words]), bit);
        }
        maskByOutput.replaceAll((output, mask) -> Masks.compact(mask, form));
        var masks = new long[bitToRule.length][];
        for (int bit = 0; bit < bitToRule.length; bit++) {
            masks[bit] = maskByOutput.get(OutputText.comparable(rules.get(bitToRule[bit]).output(), numericOutputs));
        }
        return masks;
    }

    /**
     * The table's output, if it has exactly one: DMN aggregates a single output (8.2.11, "decision tables with
     * compound outputs do not support aggregation"). The outputs are those a {@linkplain PraeceptaEngine.Builder#table table}
     * declares, or else those the rules give; null if there are several, whose aggregation the strategy defines.
     */
    static String onlyOutput(Map<String, ColumnType> declared, List<Praeceptum> rules) {
        var outputs = new HashSet<>(declared.keySet());
        if (outputs.isEmpty()) {
            for (var rule : rules) {
                outputs.addAll(rule.output().values().keySet());
                outputs.addAll(rule.output().references().keySet());
                outputs.addAll(rule.output().expressions().keySet());
            }
        }
        return outputs.size() == 1 ? outputs.iterator().next() : null;
    }

    static <O> BinaryOperator<O> reducer(AggregationOperator aggregation, OutputStrategy<O> strategy) {
        if (aggregation == null) {
            return null;
        }
        return switch (aggregation) {
            case SUM -> strategy::sum;
            case MIN -> strategy::min;
            case MAX -> strategy::max;
            case COUNT -> null;
        };
    }

    /**
     * By bit, for a SUM, MIN or MAX of the {@code aggregatedOutput} (null for none), whether a rule whose output reads no
     * input leaves that output missing.
     */
    static boolean[] missingAggregates(List<Praeceptum> rules, int[] bitToRule, String aggregatedOutput,
            ComputedOutputs[] computedByBit) {
        var missing = new boolean[rules.size()];
        for (int bit = 0; bit < rules.size(); bit++) {
            missing[bit] = aggregatedOutput != null && computedByBit[bit] == null
                    && rules.get(bitToRule[bit]).output().values().get(aggregatedOutput) == null;
        }
        return missing;
    }
}
