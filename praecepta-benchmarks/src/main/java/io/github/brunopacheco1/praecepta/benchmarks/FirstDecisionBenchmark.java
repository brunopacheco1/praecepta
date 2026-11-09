/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.benchmarks;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
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

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * Time from nothing to the first answer in a fresh JVM: read the DMN document, build the engine and
 * evaluate one input, with every class still to load and nothing compiled yet. This is what a new
 * instance (a scaled-out pod, a serverless function) pays before its first decision. Each fork
 * measures it once.
 *
 * <p>The setup reads the DMN document and the input from files written beforehand by
 * {@link FirstDecisionFixtures} (in another JVM), using only JDK classes, so that it loads none of the
 * engines' classes before the measurement. Point {@code -Dpraecepta.fixtures} at that directory
 * (default {@code benchmark-fixtures}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 10, jvmArgsAppend = {"-Xms2g", "-Xmx6g"})
public class FirstDecisionBenchmark {

    /** How {@link FirstDecisionFixtures} writes a missing value. */
    static final String NULL = "\\N";

    @Param({"praecepta", "camunda", "drools"})
    public String engine;

    @Param({"real", "mixed-100", "mixed-1000"})
    public String table;

    private String dmn;
    private List<String> inputs;
    private List<ColumnType> types;
    private Map<String, Object> input;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        var directory = Path.of(System.getProperty("praecepta.fixtures", "benchmark-fixtures"));
        var dmnFile = directory.resolve(table + ".dmn");
        if (!Files.exists(dmnFile)) {
            throw new IllegalStateException("No fixture " + dmnFile.toAbsolutePath() + ": write the fixtures first with "
                    + "java -cp benchmarks.jar " + FirstDecisionBenchmark.class.getPackageName() + ".FirstDecisionFixtures" + " <directory>");
        }
        dmn = Files.readString(dmnFile);
        inputs = new ArrayList<>();
        types = new ArrayList<>();
        input = new HashMap<>();
        for (var line : Files.readAllLines(directory.resolve(table + ".input"))) {
            var fields = line.split("\t", 3);
            var column = fields[0];
            var type = ColumnType.valueOf(fields[1]);
            inputs.add(column);
            types.add(type);
            input.put(column, value(type, fields[2]));
        }
    }

    @Benchmark
    public List<String> firstDecision() {
        return Contender.create(engine, dmn, inputs, types).evaluate(input);
    }

    /** A value as {@link FirstDecisionFixtures} wrote it, read back as the Java type its column takes. */
    private static Object value(ColumnType type, String text) {
        if (text.equals(NULL)) {
            return null;
        }
        return switch (type) {
            case STRING -> text;
            case NUMBER -> new BigDecimal(text);
            case DATE -> LocalDate.parse(text);
            case BOOLEAN -> Boolean.valueOf(text);
            case TIME -> LocalTime.parse(text);
            case DATE_TIME -> LocalDateTime.parse(text);
        };
    }
}
