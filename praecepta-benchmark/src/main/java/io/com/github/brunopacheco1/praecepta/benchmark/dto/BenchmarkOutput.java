package io.com.github.brunopacheco1.praecepta.benchmark.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
@AllArgsConstructor
public class BenchmarkOutput {
    private final String outputA;
    private final String outputB;
    private final String ruleId;
}
