package io.com.github.brunopacheco.praecepta.camunda.benchmark.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkOutput {
    private String output;
}
