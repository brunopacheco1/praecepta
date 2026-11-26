/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Latency of one decision of a decision requirement graph, per engine, on one thread: {@link GraphContender#DECISION},
 * a literal expression that requires a decision table, which requires another. Run with {@code -prof gc} for the
 * memory allocated per decision.
 *
 * <p>Before timing, the benchmark checks the selected engine against the model's answers computed in plain Java, on
 * the whole workload. A disagreement aborts the run.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx2g"})
public class GraphBenchmark {

    static final int WORKLOAD_SIZE = 4096;

    @Param({"praecepta", "camunda", "drools"})
    public String engine;

    private GraphContender contender;
    private Map<String, Object>[] inputs;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        var workload = GraphContender.workload(WORKLOAD_SIZE, 7);
        contender = GraphContender.create(engine);
        GraphContender.assertAgreement(workload, contender);
        @SuppressWarnings("unchecked") // a generic array cannot be created otherwise
        Map<String, Object>[] array = workload.toArray(Map[]::new);
        inputs = array;
    }

    @Benchmark
    public BigDecimal evaluate() {
        var input = inputs[cursor];
        cursor = (cursor + 1) & (WORKLOAD_SIZE - 1);
        return contender.evaluate(input);
    }
}
