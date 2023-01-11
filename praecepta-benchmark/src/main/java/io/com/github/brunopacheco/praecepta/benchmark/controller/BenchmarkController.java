package io.com.github.brunopacheco.praecepta.benchmark.controller;

import io.com.github.brunopacheco.praecepta.benchmark.dto.BenchmarkInput;
import io.com.github.brunopacheco.praecepta.benchmark.dto.BenchmarkOutput;
import io.com.github.brunopacheco.praecepta.benchmark.service.BenchmarkService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class BenchmarkController {
    
    private final BenchmarkService benchmarkService;
    
    @PostMapping("/benchmark")
    public BenchmarkOutput benchmark(@RequestBody BenchmarkInput input) {
        return benchmarkService.benchmark(input);
    }
}
