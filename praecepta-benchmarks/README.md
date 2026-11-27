<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# praecepta-benchmarks

Compares Praecepta in-process with Camunda 8's DMN engine (dmn-scala, as Zeebe embeds it) and Drools
DMN, using [JMH](https://github.com/openjdk/jmh). Not published.

Every engine reads the same DMN document. Before a run measures anything, it checks that every engine
returns the same answers as Praecepta built straight from the rules (`CrossCheck`), and the harness
refuses to time engines that disagree.

## Running
```
mvn -pl praecepta-benchmarks -am package -DskipTests
praecepta-benchmarks/run-benchmarks.sh               # everything, about 2.5 hours
praecepta-benchmarks/run-benchmarks.sh quick         # a few tables, about 15 minutes
java -jar praecepta-benchmarks/target/benchmarks.jar EvaluationBenchmark -p table=real   # one benchmark
java -cp praecepta-benchmarks/target/benchmarks.jar \
  io.github.brunopacheco1.praecepta.benchmarks.FootprintReport praecepta real           # retained heap
```

`run-benchmarks.sh` runs the whole matrix and writes everything into one directory under `results/`: the
raw JMH results, every log, the machine and versions (`environment.txt`), each command line
(`commands.txt`), the SHA-256 of the jar that ran, and a summary (`summary.md`, `results.csv`).

To publish numbers, use the `full` profile on a quiet machine on mains power. The `Benchmarks` GitHub
workflow runs either profile on demand and uploads the results. Shared runners are noisy: use them to
spot large regressions, not to publish numbers.

## Benchmarks

| Benchmark | Measures |
|---|---|
| `EvaluationBenchmark` | Latency of one decision. `-p workload=match` (every input matches a rule) or `nomatch` (none does). Add `-bm sample` for percentiles and `-prof gc` for the memory allocated per decision. |
| `ThroughputBenchmark` | Decisions per millisecond with one engine shared by `-t <n>` threads. |
| `LifecycleBenchmark` | Reading the DMN and building the engine, in a warm JVM. |
| `FirstDecisionBenchmark` | Reading the DMN, building the engine and evaluating one input in a fresh JVM. Needs fixtures; see `run-benchmarks.sh`. |
| `InputPathBenchmark` | The real table fed as a map, as generated records or as enums. Praecepta only. |
| `GraphBenchmark` | One decision of a decision requirement graph: two decision tables and a literal expression. |
| `FootprintReport` | Retained heap and build time. A `main`, not JMH. |

## Tables
Choose a table with `-p table=...`:

| Table | Content | Hit policy |
|---|---|---|
| `real` | The bundled table of 119 rules and 13 columns. | FIRST |
| `synthetic-<rules>[x<columns>]` | Generated string tables whose rules overlap. | COLLECT |
| `mixed-<rules>[x<columns>]` | Generated tables that mix string, number and date columns, with comparisons, intervals and `not(...)`. | COLLECT |
| `unique-<rules>[x<columns>]` | Generated string tables whose rules never overlap. | UNIQUE |
| `lookup-<rules>[x<columns>]` | Generated string tables with a value per rule, as in a price list by product code. | COLLECT |

A suffix `-first`, `-priority`, `-ruleorder`, `-collect` or `-unique` changes only the hit policy:
`mixed-1000-first` has the rules of `mixed-1000`.

## Engines
Choose engines with `-p engine=praecepta,camunda,drools`.

`drools-alphanetwork` runs Drools with its experimental alpha-network compilation of decision tables,
the closest equivalent of Praecepta's index. It is not part of the default comparison: on Drools 10.2.0
it cannot compile tables with several outputs or RULE ORDER tables, and on the tables it compiles it
disagrees with default Drools on most inputs. Use it to re-check newer Drools releases.

## Published runs
Each directory under [`results/`](results) holds one run, whole, so anyone can audit it. Its
`environment.txt` names the Praecepta version and commit that ran. Quote a number together with that
version and machine.
