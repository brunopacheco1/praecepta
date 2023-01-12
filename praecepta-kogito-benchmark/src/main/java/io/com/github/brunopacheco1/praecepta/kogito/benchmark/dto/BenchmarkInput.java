package io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
@AllArgsConstructor
public class BenchmarkInput {
    private final String inputA;
    private final String inputB;
    private final String inputC;
    private final String inputD;
    private final String inputE;
    private final String inputF;
    private final String inputG;
    private final String inputH;
    private final String inputI;
    private final String inputJ;
    private final String inputK;
    private final String inputL;
    private final String inputM;
}
