package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.List;
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
 * Praecepta on the real table, fed three ways (see {@link GeneratedInputs}): a map of strings, the
 * plugin-generated record of strings, and the generated record of enums (int-coded path). Every
 * variant is cross-checked against the map variant before timing.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx6g"})
public class InputPathBenchmark {

    @Param({"map", "strings", "enums"})
    public String input;

    private Object[] inputs;
    private java.util.function.Function<Object, List<?>> evaluate;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        var workload = Workload.generate(Table.real(), EvaluationBenchmark.WORKLOAD_SIZE, 7);
        var variant = GeneratedInputs.variant(input, workload);
        var reference = GeneratedInputs.variant("map", workload);
        for (int i = 0; i < workload.size(); i++) {
            var actual = variant.ruleIds().apply(variant.evaluate().apply(variant.inputs().get(i)));
            var expected = reference.ruleIds().apply(reference.evaluate().apply(reference.inputs().get(i)));
            if (!actual.equals(expected)) {
                throw new IllegalStateException("%s input disagrees with map input on %s: %s vs %s"
                        .formatted(input, workload.get(i), actual, expected));
            }
        }
        inputs = variant.inputs().toArray();
        evaluate = variant.evaluate();
    }

    @Benchmark
    public List<?> evaluate() {
        var next = inputs[cursor];
        cursor = (cursor + 1) & (EvaluationBenchmark.WORKLOAD_SIZE - 1);
        return evaluate.apply(next);
    }
}
