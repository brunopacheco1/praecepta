package io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class InputOutputConst {
    public static final String INPUT_A = "inputA";
    public static final String INPUT_B = "inputB";
    public static final String INPUT_C = "inputC";
    public static final String INPUT_D = "inputD";
    public static final String INPUT_E = "inputE";
    public static final String INPUT_F = "inputF";
    public static final String INPUT_G = "inputG";
    public static final String INPUT_H = "inputH";
    public static final String INPUT_I = "inputI";
    public static final String INPUT_J = "inputJ";
    public static final String INPUT_K = "inputK";
    public static final String INPUT_L = "inputL";
    public static final String INPUT_M = "inputM";
    public static final String OUTPUT_A = "outputA";
    public static final String OUTPUT_B = "outputB";
    public static final String RULE_ID = "ruleId";

    public static final List<String> INPUTS = List.of(
            INPUT_A,
            INPUT_B,
            INPUT_C,
            INPUT_D,
            INPUT_E,
            INPUT_F,
            INPUT_G,
            INPUT_H,
            INPUT_I,
            INPUT_J,
            INPUT_K,
            INPUT_L,
            INPUT_M
    );

    public static final List<String> OUTPUTS = List.of(
            OUTPUT_A,
            OUTPUT_B,
            RULE_ID
    );
}
