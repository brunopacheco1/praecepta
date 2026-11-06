/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.List;
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
 * Average latency of one decision evaluation, per engine and table.
 *
 * <p>Before timing, the selected engine is cross-checked against Praecepta on the whole workload;
 * a disagreement aborts the run, so results always compare engines computing the same answers.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx6g"})
public class EvaluationBenchmark {

    static final int WORKLOAD_SIZE = 4096;

    @Param({"praecepta", "camunda", "drools"})
    public String engine;

    @Param({"real", "synthetic-100", "synthetic-1000", "mixed-100", "mixed-1000"})
    public String table;

    private Contender contender;
    private Map<String, Object>[] inputs;
    private int cursor;

    @Setup(Level.Trial)
    @SuppressWarnings("unchecked")
    public void setUp() {
        var decisionTable = Table.named(table);
        var workload = Workload.generate(decisionTable, WORKLOAD_SIZE, 7);
        contender = Contender.create(engine, decisionTable);
        CrossCheck.assertAgreement(decisionTable, workload, contender, Contender.create("praecepta", decisionTable));
        inputs = workload.toArray(new Map[0]);
    }

    @Benchmark
    public List<String> evaluate() {
        var input = inputs[cursor];
        cursor = (cursor + 1) & (WORKLOAD_SIZE - 1);
        return contender.evaluate(input);
    }
}
