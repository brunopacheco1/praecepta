package io.github.brunopacheco1.praecepta.benchmarks;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Prints retained heap and build time for each engine and table. JMH measures latency only;
 * this complements it with the footprint that the modernisation targets.
 *
 * <p>Run each measurement in a fresh JVM for clean numbers:
 * {@code java -cp benchmarks.jar io.github.brunopacheco1.praecepta.benchmarks.FootprintReport praecepta real}
 * Without arguments, every combination runs in-process (indicative only).
 */
public final class FootprintReport {

    private FootprintReport() {
    }

    public static void main(String[] args) {
        var engines = args.length > 0 ? List.of(args[0]) : List.of("praecepta", "camunda", "camunda-legacy-feel", "drools");
        var tables = args.length > 1 ? List.of(args[1]) : List.of("real", "synthetic-100", "synthetic-1000");
        System.out.printf("%-10s %-15s %14s %10s%n", "engine", "table", "retained heap", "build");
        for (var table : tables) {
            var decisionTable = Table.named(table);
            for (var engine : engines) {
                long before = usedHeap();
                long start = System.nanoTime();
                var contender = Contender.create(engine, decisionTable);
                long buildMs = (System.nanoTime() - start) / 1_000_000;
                long retained = usedHeap() - before;
                System.out.printf("%-10s %-15s %11.1f MB %7d ms%n", engine, table, retained / 1048576.0, buildMs);
                // keep the contender reachable until after measuring
                if (contender.hashCode() == 42) {
                    System.out.print("");
                }
            }
        }
    }

    private static long usedHeap() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
