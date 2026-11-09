/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.benchmarks.generated.RealEnums;
import io.github.brunopacheco1.praecepta.benchmarks.generated.RealEnumsInput;
import io.github.brunopacheco1.praecepta.benchmarks.generated.RealEnumsOutput;
import io.github.brunopacheco1.praecepta.benchmarks.generated.RealStrings;
import io.github.brunopacheco1.praecepta.benchmarks.generated.RealStringsInput;
import io.github.brunopacheco1.praecepta.benchmarks.generated.RealStringsOutput;

/**
 * The three ways an application can feed the real table, from the same workload:
 * <ul>
 * <li>{@code map}: a {@code Map} of strings, read with one hash lookup per column, then each value's
 * mask found with a second;</li>
 * <li>{@code strings}: the plugin-generated record with {@code String} components (a field read,
 * then the mask lookup);</li>
 * <li>{@code enums}: the plugin-generated record with enum components (a field read, then the mask
 * by ordinal: the int-coded path).</li>
 * </ul>
 * Inputs are converted once, before timing, as an application holding typed objects would have them.
 */
final class GeneratedInputs {

    /**
     * Prepared inputs and the evaluation that takes them. {@code evaluate} returns the engine's result
     * as is (what is timed); {@code ruleIds} turns it into rule ids for cross-checking.
     */
    record Variant(List<Object> inputs, Function<Object, List<?>> evaluate, Function<List<?>, List<String>> ruleIds) {

        /** The ids of the rules the {@code index}-th input matches. */
        List<String> ruleIdsOf(int index) {
            return ruleIds.apply(evaluate.apply(inputs.get(index)));
        }
    }

    private GeneratedInputs() {
    }

    static Variant variant(String name, List<Map<String, Object>> workload) {
        return switch (name) {
            case "map" -> {
                var praecepta = Contender.create("praecepta", Table.real());
                yield new Variant(List.copyOf(workload), input -> praecepta.evaluate(asMap(input)),
                        outputs -> outputs.stream().map(String.class::cast).toList());
            }
            case "strings" -> {
                var engine = RealStrings.engine();
                yield new Variant(workload.stream().<Object>map(GeneratedInputs::strings).toList(),
                        input -> engine.evaluate((RealStringsInput) input),
                        outputs -> outputs.stream().map(output -> ((RealStringsOutput) output).ruleId()).toList());
            }
            case "enums" -> {
                var engine = RealEnums.engine();
                yield new Variant(workload.stream().<Object>map(GeneratedInputs::enums).toList(),
                        input -> engine.evaluate((RealEnumsInput) input),
                        outputs -> outputs.stream().map(output -> ((RealEnumsOutput) output).ruleId()).toList());
            }
            default -> throw new IllegalArgumentException("Unknown input variant: " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object input) {
        return (Map<String, Object>) input;
    }

    static RealStringsInput strings(Map<String, Object> input) {
        return new RealStringsInput(text(input, "inputA"), text(input, "inputB"), text(input, "inputC"), text(input, "inputD"),
                text(input, "inputE"), text(input, "inputF"), text(input, "inputG"), text(input, "inputH"), text(input, "inputI"),
                text(input, "inputJ"), text(input, "inputK"), text(input, "inputL"), text(input, "inputM"));
    }

    static RealEnumsInput enums(Map<String, Object> input) {
        return new RealEnumsInput(
                constant(RealEnumsInput.InputA.class, input, "inputA"), constant(RealEnumsInput.InputB.class, input, "inputB"),
                constant(RealEnumsInput.InputC.class, input, "inputC"), constant(RealEnumsInput.InputD.class, input, "inputD"),
                constant(RealEnumsInput.InputE.class, input, "inputE"), constant(RealEnumsInput.InputF.class, input, "inputF"),
                constant(RealEnumsInput.InputG.class, input, "inputG"), constant(RealEnumsInput.InputH.class, input, "inputH"),
                constant(RealEnumsInput.InputI.class, input, "inputI"), constant(RealEnumsInput.InputJ.class, input, "inputJ"),
                constant(RealEnumsInput.InputK.class, input, "inputK"), constant(RealEnumsInput.InputL.class, input, "inputL"),
                constant(RealEnumsInput.InputM.class, input, "inputM"));
    }

    private static String text(Map<String, Object> input, String column) {
        return (String) input.get(column);
    }

    /** The table's values are plain letters and YES/NO, so each constant is named like its value. */
    private static <E extends Enum<E>> E constant(Class<E> type, Map<String, Object> input, String column) {
        var value = (String) input.get(column);
        return value == null ? null : Enum.valueOf(type, value);
    }
}
