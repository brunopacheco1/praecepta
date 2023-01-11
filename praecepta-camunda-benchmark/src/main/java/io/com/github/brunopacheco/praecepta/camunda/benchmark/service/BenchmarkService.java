package io.com.github.brunopacheco.praecepta.camunda.benchmark.service;

import io.com.github.brunopacheco.praecepta.camunda.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco.praecepta.camunda.benchmark.dto.BenchmarkOutput;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class BenchmarkService {
    
    public BenchmarkOutput benchmark(BenchmarkInput input) {
        return BenchmarkOutput.builder()
                .output(input.getInput())
                .build();
    }
}
