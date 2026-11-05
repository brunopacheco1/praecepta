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

### Phase 2 — Bitset-index core
- Per column: value → bitmask of accepting rules (wildcards included); AND across columns.
- Behind an internal `Matcher` interface; trie removed once differential tests are green.
- Optional int-coded fast path; partition by most selective column for very large tables.
- Targets: real table ≤ 120 ns / < 200 KB; 1k-rule table ≤ 250 ns / < 1 MB; build in ms.

### Phase 3 — Richer rule language
- Single-column predicates: ranges, negation, sets, numbers, dates — compiled to per-interval
  bitmasks (binary search on sorted boundaries). Cross-column expressions out of scope.

### Phase 4 — Build-time tooling & DMN import
- Proper `maven-plugin` packaging (today it is packaged as a plain `jar` and its FreeMarker
  templates were never committed, so it cannot run).
- Generate typed `Input`/`Output` records and enums; validate tables at build time.
- Optional pre-built serialised index.
- DMN XML import (decision tables with simple unary tests).

### Phase 5 — Release
- Zero-dependency engine artifact, JPMS `module-info`, Maven Central, README rewrite.
- Align package names (`io.com.github…` → `io.github…`).
