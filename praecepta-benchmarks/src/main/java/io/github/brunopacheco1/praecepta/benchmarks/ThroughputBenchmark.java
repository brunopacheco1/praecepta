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
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Decisions per millisecond with one engine instance shared by every benchmark thread, as in a web
 * service. Choose the number of threads with {@code -t} (one run per thread count).
 *
 * <p>Each thread walks the workload from its own offset. Before timing, the engine is cross-checked as
 * in {@link EvaluationBenchmark}; {@code ContendersAgreeTest} also checks that each engine gives the
 * same answers when called from several threads at once.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx6g"})
public class ThroughputBenchmark {

    @Param({"praecepta", "camunda", "drools"})
    public String engine;

    @Param({"real", "mixed-1000"})
    public String table;

    private Contender contender;
    private Map<String, Object>[] inputs;

    /** The position of one thread in the workload. */
    @State(Scope.Thread)
    public static class Cursor {
        int next;

        @Setup(Level.Trial)
        public void start(ThreadParams thread) {
            next = (thread.getThreadIndex() * 997) & (EvaluationBenchmark.WORKLOAD_SIZE - 1);
        }
    }

    @Setup(Level.Trial)
    @SuppressWarnings("unchecked")
    public void setUp() {
        var decisionTable = Table.named(table);
        var requests = Workload.generate(decisionTable, EvaluationBenchmark.WORKLOAD_SIZE, 7);
        contender = Contender.create(engine, decisionTable);
        CrossCheck.assertAgreement(decisionTable, requests, contender);
        @SuppressWarnings("unchecked") // a generic array cannot be created otherwise
        Map<String, Object>[] array = requests.toArray(Map[]::new);
        inputs = array;
    }

    @Benchmark
    public List<String> evaluate(Cursor cursor) {
        var input = inputs[cursor.next];
        cursor.next = (cursor.next + 1) & (EvaluationBenchmark.WORKLOAD_SIZE - 1);
        return contender.evaluate(input);
    }
}
