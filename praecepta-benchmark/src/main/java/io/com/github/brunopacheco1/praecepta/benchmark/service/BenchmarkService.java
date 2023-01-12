package io.com.github.brunopacheco1.praecepta.benchmark.service;

import io.com.github.brunopacheco1.praecepta.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco1.praecepta.benchmark.dto.BenchmarkOutput;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class BenchmarkService {

    private BenchmarkEngine benchmarkEngine;

    public BenchmarkOutput benchmark(BenchmarkInput input) {
        var evaluation = benchmarkEngine.evaluate(input);
        return evaluation.get(0);
    }
}
