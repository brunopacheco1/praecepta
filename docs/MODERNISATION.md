# Modernisation plan

Goal: make Praecepta a fast, small, correct, publishable decision-table library — strict DMN
semantics, a core that does not explode on wildcards, and build-time tooling that validates
tables and generates typed classes rather than per-rule code.

## Decisions

| # | Topic | Decision |
|---|-------|----------|
| 1 | Hit policies | Strict DMN semantics (`UNIQUE` fails on overlap, `ANY` requires equal outputs, `PRIORITY`/`OUTPUT_ORDER` order by output values). |
| 2 | Java baseline | Latest LTS: **Java 25**. |
| 3 | Benchmarks | Retire the GCP App Engine deployments; benchmark in-process with JMH instead. |
| 4 | DMN import | In scope (Phase 4). |

## Why not generate Java code from the rules

Measured before starting (hand-rolled harness, JDK 21, Apple Silicon; numbers are indicative):

| Table | Implementation | ns/lookup | Memory |
|---|---|---:|---:|
| Real (119 rules, 13 cols) → 30,346 trie nodes | current trie | 223 (656 via public API) | 6.2 MB |
| | generated code (String `switch`) | 261 | 5.5 MB heap + 9.8 MB metaspace |
| | bitset index | 116 | 93 KB |
| Synthetic (1,000 rules, 10 cols) → 3.7 M trie nodes | current trie | 526 | 1.2 GB |
| | generated code | 1,494 | 654 MB heap + 1.2 GB metaspace, 538 MB source, 259 s javac |
| | bitset index | 223 | 160 KB |

The footprint problem is the trie's wildcard expansion, not its representation. Code generation
moves that blow-up into bytecode, which is costlier than heap and defeats the JIT. Data-oriented
structures win.

## Phases

### Phase 0 — Safety net & baseline
- Java 25 toolchain, current Maven plugins and test libraries, enforcer rules.
- Reference matcher (naive scan of every rule, strict DMN hit policies) as the source of truth.
- Differential property tests (jqwik): engine vs. reference on random tables and inputs.
- JMH module benchmarking Praecepta, Camunda DMN and Drools DMN in-process on the same table,
  with a correctness cross-check before timing.
- Retire App Engine, the Spring Boot benchmark apps and the Gatling module.
- Remove the plugin's per-node `COMPILATION` mode.
- CI: build/test on Java 25 without deployment; JMH on demand.

**Status: done** (branch `modernisation/phase-0`). Notes:
- Builds and tests with `--release 25`. Verified locally on JDK 27; CI runs on Temurin 25.
- `ReferenceMatcher` + `EngineMatchesReferencePropertiesTest` (engine test sources). Five properties
  pass today (COLLECT, RULE_ORDER, SUM/MIN/MAX, FIRST and UNIQUE where the table is accepted).
  Four are `@Disabled("Phase 1 …")` and were confirmed to fail on the current engine: accepting
  tables with unreachable rules, UNIQUE overlap detection, ANY output agreement, empty result on
  no match.
- `praecepta-benchmarks` replaces the three Spring Boot apps and the Gatling module. A single rule
  list feeds every engine (DMN is generated from it), and `ContendersAgreeTest` plus a pre-run
  cross-check guarantee identical answers. The retired apps were not like-for-like: Praecepta ran
  `UNIQUE` while Camunda/Kogito ran `FIRST`, and two of them mapped `inputM` from `inputB`.
- Root POM converges POI's transitive `commons-io` / `commons-codec` (enforced by
  `dependencyConvergence` for the library modules).
- Camunda is benchmarked twice: its default FEEL engine (feel-scala) costs ~12 ms per evaluation
  on the real table, against ~80 µs with the legacy JUEL FEEL engine and identical results.

Phase 0 baseline (JMH smoke run: 3 × 2 s iterations, one fork, JDK 27, Apple Silicon; indicative,
error bars are wide). Footprint = retained heap after building, from `FootprintReport`.

| Engine | real (119 rules) | synthetic-100 | synthetic-1000 |
|---|---:|---:|---:|
| **Praecepta** (public API) | 0.68 µs · 5.6 MB | 0.57 µs · 3.4 MB | 0.84 µs · **1,078 MB** |
| Drools DMN 10.2 | 72 µs · 6.5 MB | 24 µs · 7.0 MB | 140 µs · 13.6 MB |
| Camunda 7.24, legacy FEEL | 84 µs · 1.7 MB | 23 µs · 2.0 MB | 255 µs · 3.4 MB |
| Camunda 7.24, default FEEL | 12,280 µs · 1.8 MB | 4,218 µs · 2.1 MB | 40,120 µs · 3.5 MB |

Praecepta is already ~100x faster than the DMN engines per evaluation; its problem is memory,
which grows with wildcard expansion (1 GB at 1,000 rules versus 3–14 MB for the others).
Phase 2 targets exactly that.

### Phase 1 — API & semantics
- Immutable engine via a builder; thread-safe by construction.
- Positional / typed input path; no per-call maps, streams or copies; precomputed outputs.
- Empty result instead of `MissingOutputException` on no match.
- Strict DMN hit policies (see decision 1).
- Validation report (shadowed rules, overlaps, gaps) with configurable severity instead of
  throwing on any shadowed rule.

**Status: done** (branch `modernisation/phase-1`). Notes:
- API: `PraeceptaEngine.builder()` → immutable, thread-safe engine. Inputs bind per column
  (`.input("country", Order::country)`, no maps) or via the map-based `InputStrategy`.
  `register()`, `MissingOutputException`, `InvalidPraeceptaException`, the trie visitors and
  `InputString` are gone.
- The trie is now only a matcher (all matching rules per input region, frozen into immutable
  nodes; regions with identical matches share a leaf). Hit policies are applied once per leaf at
  build time, aggregations included, so `evaluate` is a walk plus an array lookup.
- Strict DMN: rule order is list order (`Praeceptum.priority` is now just an identifier);
  UNIQUE overlaps and ANY disagreements throw `HitPolicyViolationException`; PRIORITY and
  OUTPUT_ORDER use output value lists (`outputPriority`); aggregation only with COLLECT.
- `ValidationReport`, exact because every leaf is a reachable input region: UNREACHABLE_RULE
  (FIRST, PRIORITY), OVERLAPPING_RULES (UNIQUE), CONFLICTING_OUTPUTS (ANY). `failOn(Severity)`
  makes them build errors. "Gaps" (inputs no rule covers) are deferred to Phase 3: without typed
  input domains every table has gaps.
- Tests: all four Phase 1 properties enabled; PRIORITY, OUTPUT_ORDER and "validation report
  matches the reference" added (11 properties × 1,000 tables). Mutation-checked: a planted
  tie-breaking bug and a planted reachability bug were both caught.
- Not done: DMN's COUNT aggregator (needs a way to build an output from a number).

| Praecepta (JMH, same settings as Phase 0) | real (119 rules) | synthetic-100 | synthetic-1000 |
|---|---:|---:|---:|
| Phase 0 | 0.68 µs · 5.6 MB | 0.57 µs · 3.4 MB | 0.84 µs · 1,078 MB |
| Phase 1 | **0.19 µs · 1.6 MB** | **0.14 µs · 0.7 MB** | **0.23 µs · 153 MB** |

### Phase 2 — Bitset-index core
- Per column: value → bitmask of accepting rules (wildcards included); AND across columns.
- Behind an internal `Matcher` interface; trie removed once differential tests are green.
- Optional int-coded fast path; partition by most selective column for very large tables.
- Targets: real table ≤ 120 ns / < 200 KB; 1k-rule table ≤ 250 ns / < 1 MB; build in ms.

**Status: done** (branch `modernisation/phase-2`). Notes:
- `BitsetIndex` replaces the trie (deleted). Each rule owns a bit; each column maps every value
  written in it to the mask of rules accepting it (wildcards included); missing, blank or unknown
  values use the wildcard mask. Evaluation ANDs one mask per column, most selective column first,
  and stops as soon as no rule is left.
- Bit order does the hit-policy work: rule order for most policies, priority order (ties in rule
  order) for PRIORITY and OUTPUT_ORDER, so FIRST and PRIORITY are "lowest set bit". ANY compares the
  matches against a precomputed "same outputs" mask. Results are resolved at evaluation time;
  COLLECT aggregations now call the `OutputStrategy` on every evaluation.
- Validation no longer enumerates input regions (that was the blow-up). OVERLAPPING_RULES and
  CONFLICTING_OUTPUTS are now reported **per pair of rules** — exact, because a UNIQUE/ANY violation
  happens precisely when two such rules share an input. UNREACHABLE_RULE uses a search over the
  rule's own region, branching only on values the rules distinguish and pruned as soon as one
  candidate covers the rest. The reference oracle was updated to pairs; it still derives them by
  brute force over every input region.
- Mutation-checked: a planted "no region search" bug in the analysis and a planted "wildcards
  missing from value masks" bug in the index were both caught.

| Praecepta (JMH, same settings) | real (119 rules) | synthetic-100 | synthetic-1000 | synthetic-10000 |
|---|---:|---:|---:|---:|
| Phase 0 | 0.68 µs · 5.6 MB | 0.57 µs · 3.4 MB | 0.84 µs · 1,078 MB | — |
| Phase 1 | 0.19 µs · 1.6 MB | 0.14 µs · 0.7 MB | 0.23 µs · 153 MB | not buildable in practice |
| Phase 2 | **0.20 µs · 0.1 MB** | **0.14 µs · 0.2 MB** | **0.21 µs · 0.3 MB** | **0.69 µs · 0.8 MB** |

Build time is now milliseconds (18 ms real, 59 ms for 1,000 rules, 110 ms for 10,000; Phase 1 took
1.8 s for 1,000). Against the targets: memory and the 1,000-rule latency are met; the real-table
latency (≤ 120 ns) is not. Allocation is 32 B per evaluation, so the remaining cost is two hash
lookups per column for 13 columns: reading the benchmark's `Map` input, then finding the value's
mask. The target came from an experiment that fed pre-built arrays and skipped the first lookup.
Getting below it needs the int-coded input path, left for later along with partitioning by the most
selective column, which 10,000 rules at 0.69 µs and 0.8 MB do not yet call for.

**Drools' own index (checked after Phase 2).** Drools DMN ships an experimental alpha-network
compilation for decision tables (`AlphaNetworkOption`, `org.kie.dmn.compiler.alphanetwork`), the
closest equivalent of this bitset index, so default Drools alone is not a fair comparison. On
Drools 10.2.0:
- The documented system property is ignored by `DMNRuntimeBuilder`: the default evaluator runs.
- Forcing the option on the compiler configuration installs `DMNAlphaNetworkEvaluatorImpl`, but it
  cannot compile tables with more than one output column (the real table fails under every hit
  policy) nor RULE_ORDER tables, and on the tables it does compile it disagrees with default Drools
  on ~99% of inputs (for example returning rules whose cells do not match the input).

We could not rule out that it needs set-up from Drools' build-time (Kogito) code generation that
the runtime route skips. Either way it cannot be benchmarked, because the harness refuses to time
engines that disagree. The `drools-alphanetwork` contender stays available on demand
(`-p engine=drools-alphanetwork`) to re-check newer Drools releases.

### Phase 3 — Richer rule language
- Single-column predicates: ranges, negation, sets, numbers, dates — compiled to per-interval
  bitmasks (binary search on sorted boundaries). Cross-column expressions out of scope.

**Status: done** (branch `modernisation/phase-3`). Decisions: FEEL unary-test syntax with unquoted
words still accepted (existing tables unchanged); optional input value lists, enforced at
evaluation and used for gap reporting.
- `CellParser` / `UnaryTests` (package `engine.feel`): lists, `not(...)`, `null`, `-`, comparisons and
  intervals (every bracket style), `date("…")` or bare ISO dates; errors name the rule and column.
- Typed columns: `input` (STRING), `numberInput` (any `Number`, compared as exact decimals),
  `dateInput` (`LocalDate`); `columnType` for map-based inputs, whose text is parsed.
- `ColumnIndex` splits each column into value classes no rule can tell apart: written values plus
  "other" and "missing" for strings; boundary points and the open gaps between them for numbers and
  dates (binary search). Gaps between consecutive days hold no date and are marked empty. FEEL's
  three-valued logic is applied per class (missing fails comparisons, satisfies `not("A")`).
- Input value lists (`allowedValues`): other values throw `InvalidInputException` (even after
  early exit); analyses ignore them; GAP issues report uncovered declared regions with a readable
  description (adjacent classes merged: `age in [18..120]`) and a concrete example.
- Excel loader: numeric cells kept exact (they were rounded with `Math.round`), date-formatted
  cells become ISO dates, formulas use cached results.
- Tests: typed reference oracle (evaluates FEEL on concrete values, brute-forces every region);
  12 properties over random STRING/NUMBER/DATE tables. It found four bugs during development:
  `not(...)` around half-open intervals, validation skipped after early exit, gaps reported in an
  empty domain, and rules matching nothing not reported as unreachable. Mutation-checked: planted
  "date gaps not empty" and "not(unknown) matches" bugs were both caught.
- Performance on string tables stays within ~3–10% of Phase 2 (real table 186–189 ns vs 175–184 ns,
  interleaved runs). The first version was ~2× slower; restoring Phase 2's single-lookup path for
  string columns, with blank and value-list checks only on a miss, fixed it.
- Cross-engine benchmarks now include typed tables, and Camunda is now Camunda 8's DMN engine.
  - `mixed-100` / `mixed-1000`: nine interleaved string, number (0..100) and date (2025) columns,
    using every cell form (lists, `not(...)`, comparisons, intervals with open and closed ends).
    The workload passes `BigDecimal` and `LocalDate` values.
  - `DmnWriter` parses each cell with `CellParser` and writes standard FEEL with DMN types
    (`string`, `number`, `date`), so every engine reads the same model.
  - Camunda 7 community edition ended at 7.24.0. Camunda is now benchmarked through Camunda 8's
    engine (dmn-scala 1.12.6 with feel-scala 1.22.1, as embedded by Zeebe). Camunda 8 has no legacy
    FEEL mode, so `camunda-legacy-feel` is gone; that mode had no `date()` function anyway. The
    Jackson family is pinned to 2.22.1 in the benchmark module, because feel-scala needs it and
    Drools would otherwise resolve 2.19.
  - Camunda 8 and Drools agree with Praecepta on every input of every table, missing values
    included.

  JMH, same settings as Phase 0 (3 × 2 s warm-up, 5 × 2 s measurement, one fork, JDK 27, Apple
  Silicon). Footprint = retained heap · build time, each in a fresh JVM.

  | Engine | real (119) | synthetic-100 | synthetic-1000 | mixed-100 | mixed-1000 |
  |---|---:|---:|---:|---:|---:|
  | **Praecepta** | **0.19 µs** · 0.2 MB · 41 ms | **0.16 µs** · 0.3 MB · 56 ms | **0.21 µs** · 0.4 MB · 79 ms | **0.43 µs** · 0.4 MB · 81 ms | **0.62 µs** · 0.9 MB · 161 ms |
  | Camunda 8 (dmn-scala 1.12.6) | 16 µs · 4.6 MB · 427 ms | 19 µs · 4.8 MB · 471 ms | 155 µs · 16.1 MB · 677 ms | 33 µs · 4.9 MB · 528 ms | 278 µs · 16.3 MB · 796 ms |
  | Drools DMN 10.2 | 39 µs · 6.4 MB · 334 ms | 16 µs · 7.0 MB · 403 ms | 135 µs · 13.5 MB · 640 ms | 31 µs · 7.4 MB · 491 ms | 247 µs · 15.6 MB · 1,055 ms |

  - Camunda 8 is far faster than Camunda 7 with its default FEEL engine (16 µs vs 12,280 µs on the
    real table) and about 5× faster than Camunda 7's legacy FEEL (84 µs).
  - Praecepta stays 70–450× faster than both engines, with 10–40× less memory.
  - Praecepta's mixed tables cost 2–3× its string tables. Each number or date column needs a binary
    search over `BigDecimal` / `LocalDate` boundaries instead of one hash lookup. Range cells also
    set many bits, so the AND rarely empties early and all nine columns are read.
  - Possible follow-up (Phase 4, with the int-coded path): compare primitive boundary keys (epoch
    days, scaled longs).
- Not done: date-times; string ranges.

### Phase 4 — Build-time tooling & DMN import
- Proper `maven-plugin` packaging (today it is packaged as a plain `jar` and its FreeMarker
  templates were never committed, so it cannot run).
- Generate typed `Input`/`Output` records and enums; validate tables at build time.
- Int-coded input path: generated enums whose ordinals are the index's value classes, so
  evaluation needs no hashing (moved here from Phase 2; target ≤ 120 ns on the real table).
- Optional pre-built serialised index.
- DMN XML import (decision tables with simple unary tests).

### Phase 5 — Release
- Zero-dependency engine artifact, JPMS `module-info`, Maven Central, README rewrite.
- Align package names (`io.com.github…` → `io.github…`).
