package io.com.github.brunopacheco1.praecepta.kogito.benchmark.service;

import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.kogito.benchmark.dto.BenchmarkOutput;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class BenchmarkService {

    private final BenchmarkEngine benchmarkEngine;

    public BenchmarkOutput benchmark(BenchmarkInput input) {
        return benchmarkEngine.evaluate(input);
    }
}
