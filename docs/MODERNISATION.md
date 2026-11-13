<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Modernisation plan

Goal: make Praecepta a fast, small, correct, publishable decision-table library — strict DMN
semantics, a core that does not explode on wildcards, and build-time tooling that validates
tables and generates typed classes rather than per-rule code.

## Decisions

| # | Topic | Decision |
|---|-------|----------|
| 1 | Hit policies | Strict DMN semantics (`UNIQUE` fails on overlap, `ANY` requires equal outputs, `PRIORITY`/`OUTPUT_ORDER` order by output values). |
| 2 | Java baseline | Latest LTS: **Java 25** (Phase 0). **Revised in Phase 6 to Java 21**: the code compiled and passed unchanged with `--release 21`, and 21 is the LTS most users run. CI builds on 21, 25 and 27. |
| 3 | Benchmarks | Retire the GCP App Engine deployments; benchmark in-process with JMH instead. |
| 4 | DMN import | In scope (Phase 4). |
| 5 | Licence | Relicense from MIT to **Apache-2.0** (Phase 5). |
| 6 | Scope | A decision-table library (the goal above). **Revised for 0.7**: Praecepta grows into a full DMN 1.5 engine, one layer per release, scored against the whole DMN TCK and judged by DMN 1.5 and the TCK only ([#120](https://github.com/brunopacheco1/praecepta/issues/120); the layers are in [ROADMAP.md](ROADMAP.md)). Decision tables keep their validation report and typed generated code; the general engine evaluates everything around them. |

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

**Status: done** (branch `modernisation/phase-4`). Decisions:
- The plugin converts every table to a DMN resource at build time, and the engine reads DMN with
  the JDK's XML parser only, so applications need no Apache POI at runtime.
- Excel/CSV column types and value lists are declared in the pom.
- The pre-built serialised index is dropped: engines build in 40–160 ms for up to 1,000 rules,
  and a binary format would need versioning.

What was done:
- `DecisionTable` (engine `beans`): a whole table, i.e. name, hit policy, aggregation, typed input
  columns with value lists, output columns with output value lists, and rules.
  `Builder.table(...)` applies it, then checks that every column is bound with the declared type.
- `DmnReader` (engine `dmn` package) reads DMN 1.1–1.5.
  - It handles Camunda Modeler output (diagram data, vendor attributes, annotations), `feel:`- and
    `xsd:`-prefixed types, and several decisions per model (pick one by name).
  - DTDs and external entities are refused.
  - Supported: input expressions that name a variable; `string`, `number` / `integer` / `long` /
    `double`, and `date` columns; cells in Praecepta's FEEL subset; input and output value lists;
    literal outputs; every hit policy; SUM, MIN and MAX.
  - Anything else fails with the decision, rule and column: boolean and date-time columns,
    computed expressions, function calls, COUNT.
- `DmnWriter` (moved from the benchmarks into the engine) writes DMN 1.3, adds value lists and
  aggregation, and uses DMN's spelling of hit policies (`RULE ORDER`, `OUTPUT ORDER`). The
  benchmarks feed Camunda and Drools through it.
- Int-coded path: `Builder.enumInput(column, Enum.class, [value,] getter)`.
  - Each constant's mask is computed once, so a lookup is an array access by ordinal.
  - The enum is the column's input value list unless one is declared, in which case every
    constant must be in it (checked at build).
- Maven plugin rewritten as a real plugin (`maven-plugin` packaging, goal `praecepta:generate`;
  FreeMarker and the never-committed templates are gone). Per table it:
  1. loads `.xlsx`, `.csv` or `.dmn`;
  2. validates with the engine (`failOn`: ERROR by default) and logs the rest;
  3. writes `<Name>.dmn` next to the classes;
  4. generates `<Name>Input` (a nested enum per string column with a value list; `BigDecimal`,
     `LocalDate`), `<Name>Output`, and `<Name>` (static `evaluate` returning an `Optional` or a
     `List`, plus `validationReport()` and `engine()`).

  Generated code fully qualifies outside types. Column and value names become Java identifiers,
  with reserved words and collisions handled.
- Loaders take an `InputStream` as well as a classpath path.

Tests:
- DMN round-trip property: 500 random typed tables, every hit policy, written, read back and
  evaluated against the reference; writing is stable.
- Reader fixtures in Camunda Modeler and DMN 1.1 style, with rejection cases.
- Enum bindings agree with string bindings.
- Plugin tests generate sources, compile them with `-Xlint:all -Werror` against the engine jar
  alone, and evaluate them.
- Mutation-checked: planted "interval end brackets swapped" (writer) and "input value lists
  dropped" (reader) bugs were both caught.
- The benchmark module now uses the plugin itself: Maven orders it in the reactor automatically,
  `-am` included.

Performance, real table, `InputPathBenchmark`: the same engine, fed three ways, timed in one run.

| Input | ns/op |
|---|---:|
| `Map` of strings (two hash lookups per column) | 233 |
| generated record of `String`s (field read + one hash lookup) | 151 |
| generated record of enums (field read + array index) | **104** |

- The Phase 2 target (≤ 120 ns on the real table) is met by the enum path. All three variants pay
  the same benchmark indirection, which is why the map variant reads higher here than in
  `EvaluationBenchmark` (188 ns).
- Existing paths are unchanged: interleaved against the Phase 3 jar, real 185/188 vs 183/179 ns,
  synthetic-1000 218/218 vs 217/217, mixed-100 442/431 vs 435/437.
- This run's cross-engine numbers were 10–17% noisier than Phase 3's; the Phase 3 table remains
  the reference.

Not done:
- Boolean and date-time columns.
- DMN models whose decisions require other decisions (DRG chaining).
- A Maven-invoker integration test: the benchmark module's use of the plugin covers the Maven
  wiring.

<!-- REUSE-IgnoreStart: this section quotes SPDX tags as examples -->
### Phase 5 — Open-source compliance
Relicense to Apache-2.0, and make licensing, provenance and project health machine-checkable
before the first public release. The repository already runs FOSSLight and ORT on pushes to
`main`; this phase builds on them rather than adding parallel tools.

- **Relicensing, MIT → Apache-2.0** (decision 5), done first so the rest of the phase applies the
  right identifier:
  - check ownership first. Every human commit is by the maintainer, but nine early commits
    (2022–2023) use a work email address. Their surviving code is the loader module, the small
    bean and strategy types, and the POMs, all since renamed or reworked. Confirm that this code
    is the maintainer's to relicense.
  - if it is: relicense the whole tree.
  - if not, or in doubt: keep those files under MIT, which Apache-2.0 projects may include.
    REUSE records the split per file (`SPDX-License-Identifier: MIT`, plus
    `LICENSES/MIT.txt`), or the code is rewritten. No artifacts were ever released, so there are
    no published MIT versions to consider.
  - replace `LICENSE` with the Apache-2.0 text and add `LICENSES/Apache-2.0.txt`;
  - add a `NOTICE` file (the Apache convention) with the copyright line, and any notices that
    bundled third-party code requires (none today: the published modules bundle nothing);
  - update the README licence section and the POM `<licenses>` block;
  - record the change in `CHANGELOG.md`.

- **REUSE compliance** ([reuse.software](https://reuse.software), spec 3.3):
  - an SPDX header on every source, POM, workflow and doc file:
    `SPDX-FileCopyrightText: <year> <holder>` and `SPDX-License-Identifier: Apache-2.0`
    (or `MIT` for any files kept under MIT);
  - `LICENSES/Apache-2.0.txt` (and `LICENSES/MIT.txt` only if MIT files remain);
  - `REUSE.toml` for files that cannot carry a header (`.xlsx`, `.csv` and `.dmn` fixtures,
    `.jqwik-database`);
  - `reuse lint` in CI, failing the build on any unlicensed file.
- **Header enforcement in the build:** a Maven check (e.g. Spotless or `license-maven-plugin`)
  that verifies headers on `verify` and can add them, so new files cannot slip through between
  CI runs. Generated sources from `praecepta-maven-plugin` get a header too, with the license the
  user configures (default: none, since generated code belongs to the user).
- **Copyright:** one holder string, used consistently in headers, `NOTICE`, POM `<licenses>` /
  `<developers>` / `<scm>` metadata (Maven Central requires these), and the generated code
  banner.
- **SBOM:** CycloneDX (`cyclonedx-maven-plugin`) per published module, attached to the release
  artifacts (`-cyclonedx.json` / `.xml`). Optionally SPDX as well, if a consumer needs it.
- **Dependency licences:**
  - fail the build on dependency licences incompatible with distributing the published modules
    (engine, loader, plugin) under Apache-2.0, via ORT's `fail-on: violations` with an explicit
    policy. Permissive licences are fine. GPL-2.0-only is not (it is incompatible with Apache-2.0),
    and strong copyleft generally needs review;
  - list third-party licences for the loader's Apache POI dependency;
  - exclude the benchmark module, which is not published.
- **Supply chain:**
  - Dependabot (or Renovate) for Maven and GitHub Actions;
  - Actions pinned by commit SHA;
  - CodeQL;
  - the OpenSSF Scorecard workflow and badge;
  - vulnerability scanning of the SBOM (e.g. `osv-scanner`).
- **Release integrity:**
  - reproducible builds (`project.build.outputTimestamp`, checked with
    `artifact:compare`);
  - GPG-signed artifacts (required by Maven Central);
  - build provenance via GitHub artifact attestations (SLSA level 2/3).
- **Community health files:** `CONTRIBUTING.md` (moved out of the README), `CODE_OF_CONDUCT.md`
  (Contributor Covenant), `SECURITY.md` (private vulnerability reporting), issue and PR templates,
  `CHANGELOG.md` (Keep a Changelog) with semantic versioning.
- **OpenSSF Best Practices badge**, once the above is in place.
- **Cleanup:** make the ORT workflow also run on pull requests, not only after merge; decide
  whether FOSSLight is still needed.

**Status: done** (branch `modernisation/phase-5`). Decisions:
- the maintainer owns all the code, including the early work-email commits, so everything is
  Apache-2.0;
- copyright holder "Bruno Pacheco";
- no contributor sign-off (Apache-2.0 section 5 covers contributions);
- CycloneDX SBOMs only;
- FOSSLight is removed. Its one unique job was scanning our sources for foreign licence texts.
  REUSE now enforces per-file licensing, the repository holds no vendored third-party code, and its
  `pip install` of about a hundred ScanCode dependencies was SonarCloud's last security finding.
  ORT stays and runs on pull requests; it gets policy rules and attribution reports in Phase 6.

The history was rewritten before this phase. The work email and then the personal Yahoo address
were replaced by the GitHub noreply address in every commit, and the commits were re-signed; trees,
messages and dates are unchanged.

What was done:
- **Relicensing:** `LICENSE` now holds the Apache-2.0 text, plus `LICENSES/Apache-2.0.txt` and a
  `NOTICE` file. The POM gained `<licenses>`, `<developers>` (no email), `<scm>`,
  `<issueManagement>` and `<inceptionYear>`. The README and the changelog record the change.
- **REUSE 3.3:**
  - an SPDX header on all Java sources and POMs (`license-maven-plugin`, slash-star style),
    workflows, Markdown and `.gitignore`;
  - `REUSE.toml` for files that cannot hold a comment (CSV, XLSX, DMN fixtures, `NOTICE`, VS Code
    settings);
  - `reuse lint` passes. Text that quotes SPDX tags is wrapped in `REUSE-IgnoreStart/End`.
- **Header enforcement:** `license:check` runs in `verify` on every module, and `mvn license:format`
  adds missing headers. Checked: removing a header fails the build.
- **Generated code:** the plugin's new `<fileHeader>` option prepends the user's own header to
  generated sources (none by default, since generated code belongs to the user's project).
- **SBOMs:** `cyclonedx-maven-plugin` writes `target/bom.json` per published module and attaches
  it as `-cyclonedx.json`. The benchmark module is skipped.
- **Licence policy:** `.github/scripts/check_sbom_licenses.py` checks the SBOMs against an
  allowlist of permissive licences in CI. A component passes if any of its licences is allowed,
  and unlicensed components fail. Today the engine has no dependencies, and the loader and plugin
  carry Apache-2.0 components plus one BSD-3-Clause (`curvesapi`). Tested against a fake SBOM with
  GPL-only and unlicensed components: both fail.
- **Reproducible builds:** `project.build.outputTimestamp` is set. Two clean builds produced
  byte-identical jars and SBOMs for engine, loader and plugin.
- **CI:**
  - every action is pinned to a commit SHA with a version comment;
  - `build.yml` adds the licence check, an SBOM artifact, a `reuse lint` job, and OSV-Scanner on
    the SBOMs (advisory: results go to code scanning);
  - new `codeql.yml` (Java and Actions, build mode none) and `scorecard.yml` (published results,
    README badge);
  - `dependabot.yml` for Maven and Actions, weekly, with grouped tooling updates.
  - `dependencies.yml` submits the resolved Maven tree of the published modules to GitHub on
    every push to `main` and on same-repository pull requests. GitHub otherwise reads POMs
    statically and saw only direct dependencies (2 of the loader's 13). Dependabot now alerts on,
    and fixes, transitive ones such as `commons-compress` and `xmlbeans`. Dependency review fails a
    pull request that adds a runtime dependency with a moderate-or-worse vulnerability.
  - SonarCloud is kept for code quality (bug patterns, smells, duplication, coverage on new
    code), which the security tools do not cover. Its token had expired (every analysis since
    Phase 0 failed with 403, hidden by the advisory step). The build now sends all modules to the
    single project `brunopacheco1_praecepta`, with no per-module keys, and excludes only the
    unpublished benchmarks; the Maven plugin, `beans` and `exceptions` packages are analysed.
    The owner renews the token.
  - ORT had failed on every run, because its action was pinned to the retired
    `upload-artifact@v3`; it now pins ORT action v1.2.0. FOSSLight had failed on every run too
    (Python 3.15 without wheels for a dependency) before it was removed.
- **Community files:**
  - `CONTRIBUTING.md`, replacing the README section;
  - `CODE_OF_CONDUCT.md`, adopting Contributor Covenant 2.1 by reference;
  - `SECURITY.md`, pointing to GitHub private vulnerability reporting;
  - `CHANGELOG.md` (Keep a Changelog);
  - issue forms and a PR template.

Not done here (owner actions or later phases):
- **Repository settings:** turn on private vulnerability reporting (Settings → Code security),
  which `SECURITY.md` links to.
- **OpenSSF Best Practices badge:** register the project at bestpractices.dev.
- **GPG-signed artifacts and build provenance (attestations):** Phase 6, with the release
  workflow.
- **GitHub's old PR references:** `refs/pull/1–5` still hold the pre-rewrite commits until GitHub
  Support removes them.
- **OSV-Scanner's SBOM input is untested:** it couldn't be run locally, so it is advisory until its
  first CI run confirms it.

<!-- REUSE-IgnoreEnd -->

### Phase 6 — Release
- Zero-dependency engine artifact, JPMS `module-info`, Maven Central, README rewrite.
- Align package names (`io.com.github…` → `io.github…`).
- Signed artifacts (GPG, required by Maven Central) and build provenance (GitHub artifact
  attestations) in the release workflow.
- Give ORT a real job. Today it only analyses dependencies:
  - an evaluator policy, so that `fail-on: violations` can actually fail;
  - attribution reports for releases;
  - optionally ScanCode on our own sources only.

**Status: done** (branch `modernisation/phase-6`). Decisions:
- the first release is **0.1.0** (semantic versioning; the API may change in 0.x);
- releases are triggered by pushing a **signed `v*` tag**;
- CI signs artifacts with a **dedicated release key**, so the maintainer's personal key never
  enters CI;
- the engine is a real **JPMS module**, and the loader and plugin get `Automatic-Module-Name`.

What was done:
- **Package names:** already `io.github.brunopacheco1.praecepta.*` everywhere; nothing to change.
- **Engine module:** `module io.github.brunopacheco1.praecepta.engine` requires only `java.xml`
  and exports `engine`, `beans`, `dmn`, `exceptions` and `feel`. Tests run on the module path (all
  pass). The loader and plugin declare `io.github.brunopacheco1.praecepta.loader` and
  `…maven.plugin`; POI's module layout makes a full module-info fragile there.
- **POMs:** version `0.1.0-SNAPSHOT`; descriptions on every published module.
- **`release` profile:**
  - source and javadoc jars, with doclint on everything but missing comments; it passes;
  - GPG signing with the BouncyCastle signer, reading `MAVEN_GPG_KEY` / `MAVEN_GPG_PASSPHRASE`,
    so CI needs no gpg keyring;
  - `central-publishing-maven-plugin` (autoPublish, waits until published).

  Verified locally with a throwaway key: jars, sources, javadoc, POM and SBOM all signed, and the
  signatures verify. `versions:set` from a tag version also works.
- **`release.yml`** (on `v*` tags, in a `release` environment):
  1. checks that the tag is annotated, signed by a key GitHub publishes for the owner, and has a
     changelog section;
  2. sets the version, builds, tests, signs and publishes the parent, engine, loader and plugin;
  3. records build provenance (`actions/attest-build-provenance`);
  4. creates the GitHub release with the changelog notes, jars, signatures, SBOMs and
     `THIRD-PARTY-NOTICES.txt` (generated from the SBOMs by
     `.github/scripts/third_party_notices.py`; 13 components today).
- **ORT:**
  - the evaluator now runs ORT's default policy (`ort-config`): copyleft and unclassified
    licences, missing project files, …;
  - `fail-on: violations` therefore enforces something;
  - `.ort.yml` excludes the benchmarks and the test and provided scopes;
  - the reporter adds the plain-text attribution notice.

  The first real run is on this branch's pull request; ScanCode on our own sources remains
  optional.
- **`docs/RELEASING.md`:** owner setup (Central Portal token, release key on keyservers, `release`
  environment and secrets) and the release steps.
- **README:** Maven coordinates, a 0.x status note, and a "What Praecepta is not" section.

- **Java and Maven support** (decided during the phase):
  - **Java 21** baseline instead of 25. The whole build, benchmarks included, passed unchanged on
    JDK 21 with `--release 21`. Java 17 would have needed rewriting the pattern-matching switches.
  - **CI:** one `Build & test` matrix over **Java 21, 25 and 27**, each building and testing once.
    The licence check, SBOM upload and SonarCloud run only on the 25 entry, whose check name
    ("Build & test (Java 25)") stays the ruleset's required check.
  - **Plugin integration test:** a user project (`praecepta-maven-plugin/src/it/csv-table`)
    compiles a CSV table with the plugin and runs JUnit tests on the generated classes. CI runs it
    under Maven **3.9.16** and **3.10.0** (required) and **4.0.0-rc-7** (preview), installed
    through the Maven wrapper. All three passed locally.
  - **Plugin's Maven floor:** it stays 3.9 (its descriptor's `requiredMavenVersion` follows
    `maven.api.version`). Dependabot ignores minor and major Maven API bumps, and PR #8 (3.10.0)
    was closed for that reason.

Not done (owner actions): creating the Central token, the release key and the `release`
environment, then tagging `v0.1.0`.

### Phase 7 — Benchmark refresh, roadmap and OpenSSF improvements
After 0.1.0 reached Maven Central: measure the released artifacts, publish what could come next as
issues, and raise the OpenSSF Scorecard.

**Benchmark of the released 0.1.0.** The benchmark module is built at tag `v0.1.0` against the
artifacts downloaded from Maven Central, including the Maven plugin, which generates the
real-table classes. Settings: Apple M2 Pro, JDK 27; JMH with 5 × 2 s warm-up and 5 × 3 s
measurement, 2 forks. Cross-check: the same suite on a GitHub Actions runner (x86, Java 25).
The absolute numbers differ there, but the ranking holds.

| µs per evaluation | real (119) | synthetic-100 | synthetic-1000 | mixed-100 | mixed-1000 |
|---|---:|---:|---:|---:|---:|
| Praecepta 0.1.0 | 0.19 | 0.15 | 0.23 | 0.49 | 0.68 |
| Camunda 8 (dmn-scala 1.12.6) | 16.7 | 19.5 | 127 | 37.2 | 283 |
| Drools 10.2.0 | 59.8 ± 6.1 | 16.4 | 144 ± 42 | 32.4 | 276 |

- **Input paths** (real table): `Map` 231 ns, generated string record 142 ns, generated enum record
  101 ns.
- **Memory:** Praecepta 0.2–0.9 MB against 4.6–16.5 MB for the others.
- **Build time:** Praecepta 39–167 ms against 0.3–1.1 s.
- **Fairness:**
  - same rules for every engine, cross-checked before timing;
  - default, supported setups;
  - `Map` inputs for every engine;
  - Praecepta's enum path reported separately.
- **Biases:** in-process, single-threaded, warm JVM. The DMN engines interpret full FEEL. Drools'
  build-time (executable) model was not measured.

**Roadmap as issues.** What could come next is published as issues #12–#25, labelled `enhancement`
/ `help wanted`, with `good first issue` on the smaller ones. Each asks for a 👍 or an offer to
contribute:
- boolean, time and date-time columns;
- string ranges;
- inputs with several values;
- decision requirement graphs;
- a COUNT aggregation;
- faster number and date columns;
- compressed bitmaps;
- Gradle;
- GraalVM native image;
- a Spring Boot starter;
- an online playground;
- the DMN TCK;
- fuzzing.

**OpenSSF Scorecard improvements.** The score was 7.8/10 after 0.1.0. The checks below 10:

| Check | Score | Work | By |
|---|---:|---|---|
| Fuzzing | 0 | Jazzer fuzz tests (`FuzzedDataProvider`) for `CellParser` and `DmnReader`, both of which parse untrusted input. They run as regression tests in every build, and fuzz longer on a schedule (issue #23). Scorecard only detects Java fuzzing through OSS-Fuzz or ClusterFuzzLite, so ClusterFuzzLite also fuzzes every pull request that touches the engine. | code |
| Signed-Releases | 8 | Attach each artifact's provenance bundle (`*.intoto.jsonl`) to the GitHub release, beside the signatures. | code (`release.yml`) |
| Branch-Protection | 3 | Ruleset on `main`: pull requests with 1 approval from a code owner (`.github/CODEOWNERS`), approval of the latest push, stale approvals dismissed, branches up to date, signed commits, and the required checks. Repository admins may bypass only when merging a pull request, since a sole maintainer cannot approve their own. Expected 8/10: 2 reviewers (Tier 4) needs a second maintainer. | owner (settings) |
| OpenSSF Best Practices badge (Scorecard check `CII-Best-Practices`) | 0 | Silver badge earned at bestpractices.dev ([project 16426](https://www.bestpractices.dev/projects/16426)): passing and silver at 100%. Gold needs two-person review, a bus factor of 2 and unassociated contributors (see Code-Review and Contributors), and security headers on the website. | owner |
| Code-Review | 0 | Counts merged changes approved by someone other than their author. Contributions already arrive through forks and PRs; each one the maintainer reviews counts, and a co-maintainer reviewing the maintainer's PRs completes it. | community |
| Contributors | 3 | Contributors from several organisations; grows with contributions. | community |

Also: **secret scanning and push protection** are enabled.

**Status:**
- **Done:** fuzzing (Jazzer fuzz tests, the weekly `fuzz.yml` (issue #23) and ClusterFuzzLite on pull
  requests), provenance bundles on releases (from the next release on), and the ruleset on `main`.
- **Done (owner):** the OpenSSF Best Practices silver badge and OpenSSF Baseline level 1, a
  repository successor for continuity, and secret scanning with push protection.
