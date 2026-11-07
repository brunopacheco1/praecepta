/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

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

import io.github.brunopacheco1.praecepta.engine.dmn.DmnWriter;

/**
 * Time to read a DMN document and build a ready engine, in a warm JVM: what a service pays to load
 * or reload a table. Each measurement builds one engine. For the cost in a fresh JVM, see
 * {@link FirstDecisionBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx6g"})
public class LifecycleBenchmark {

    @Param({"praecepta", "camunda", "drools"})
    public String engine;

    @Param({"real", "mixed-100", "mixed-1000"})
    public String table;

    private Table decisionTable;
    private String dmn;

    @Setup(Level.Trial)
    public void setUp() {
        decisionTable = Table.named(table);
        dmn = DmnWriter.write(decisionTable.decisionTable());
    }

    @Benchmark
    public Contender build() {
        return Contender.create(engine, dmn, decisionTable.inputs(), decisionTable.types());
    }
}
