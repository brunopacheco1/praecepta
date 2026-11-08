<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/); until 1.0, minor versions may change the API.

## [Unreleased]

### Added
- **Project documents:** governance (roles, decisions, continuity), architecture, a security
  assurance case and a one-year roadmap. The website shows the roadmap, a C4 system context diagram,
  and what users can expect on security, and links them all.
- **A project website** at https://bruno.pacheco.lu/praecepta/, with the API documentation of the
  latest release, and a Classic Rome identity: a bronze *tabula ansata* logo, Cinzel and EB Garamond.
- **Fuzz tests** (Jazzer) for the cell parser and the DMN reader: they replay saved inputs in every
  build, fuzz weekly in CI, and fuzz every pull request that touches the engine (ClusterFuzzLite).
- **Releases attach their build provenance** (`praecepta-X.Y.Z.intoto.jsonl`) for offline
  verification.
- **Broader benchmarks**, in which every engine, Praecepta included, reads the same DMN document:
  - tables from 10 to 10,000 rules, and with 5 to 20 columns;
  - every hit policy, including UNIQUE and PRIORITY;
  - requests that match no rule;
  - latency percentiles and memory allocated per decision;
  - throughput with an engine shared by 1, 4 and 8 threads;
  - time to build an engine from DMN, warm and in a fresh JVM;
  - `run-benchmarks.sh` runs them all and records the machine and versions with the results.
- **An enforced code style:** Checkstyle checks every build (`checkstyle.xml`, described in
  CONTRIBUTING.md), and compiler warnings fail the build (`-Xlint:all -Werror`).

### Changed
- **`CsvLoader` and `ExcelLoader` are `final`**, and `BaseLoader`'s helpers are `final` too: the
  loaders read their file in the constructor, which a subclass could otherwise observe half-built.
  Extend `BaseLoader` to write a loader of your own.
- **Exceptions declare a `serialVersionUID`.**

### Fixed
- **Large FIRST and PRIORITY tables build quickly** ([#29](https://github.com/brunopacheco1/praecepta/issues/29)):
  the unreachable-rule check, still exact, now remembers the regions it has proven covered, tests
  whether one rule covers the rest with a single mask intersection, and looks for an uncovered input
  before searching deeper. A 1,000-rule, 9-column table now builds in 0.5 s under FIRST (was 3.2 s)
  and 0.7 s under PRIORITY (was 65 s), against 0.3 s under COLLECT; the reports are unchanged.

## [0.1.0] - 2026-11-07

The first release since the 2022–2023 prototype: the engine, loader and Maven plugin are rewritten.
Code written against the prototype needs the changes listed under *Changed*.

### Changed
- **Licence: Praecepta is now licensed under the Apache License 2.0** (previously MIT). No version
  was ever released under MIT.
- **Java 21** baseline (tested on 21, 25 and 27). The engine is a JPMS module (`io.github.brunopacheco1.praecepta.engine`,
  requires only `java.xml`). The loader and plugin declare stable automatic module names.
- **Engine API:**
  - engines are built once with `PraeceptaEngine.builder()`, and are immutable and thread-safe;
    `register()` is gone;
  - inputs bind per column (`input`, `numberInput`, `dateInput`, `enumInput`) or as a map with
    `inputs(...)`;
  - `evaluate` returns an empty list when no rule matches.
- **Strict DMN 1.3 hit policies** (UNIQUE, ANY, PRIORITY, FIRST, OUTPUT_ORDER, RULE_ORDER, COLLECT
  with SUM/MIN/MAX):
  - rule order is the order of the rule list; `Praeceptum.priority` is only an identifier now;
  - UNIQUE overlaps and ANY disagreements throw `HitPolicyViolationException`;
  - PRIORITY and OUTPUT_ORDER take output value lists (`outputPriority`).
- **Matching uses a bitset index:** memory grows linearly with the table (10,000 rules in under
  1 MB), and engines build in milliseconds.
- **The Maven plugin is rewritten** as a real plugin (goal `praecepta:generate`):
  - it reads `.xlsx`, `.csv` or `.dmn` tables;
  - validates them at build time (`<failOn>`);
  - writes them as DMN resources;
  - generates typed input/output records (enums for string value lists) and a decision class
    that needs only the engine at runtime;
  - it runs on Maven 3.9 and later (tested on 3.9, 3.10 and 4.0);
  - the old `COMPILATION` and in-memory generation modes are gone.

### Added
- **Cells in a DMN FEEL subset** on string, number and date columns: lists, `not(...)`,
  comparisons, intervals, `null`. Unquoted `PT,ES` cells from the prototype keep working.
- **Typed columns:** number values compare as exact decimals, dates are `LocalDate`, and map-based
  inputs declare types with `columnType`.
- **Input value lists** (`allowedValues`), enforced at evaluation: other values throw
  `InvalidInputException`.
- **Validation reports** (`validationReport()`, `failOn(...)`): overlapping rules, conflicting
  outputs, unreachable rules, and gaps (declared inputs no rule matches, with an example).
- **DMN 1.1–1.5 import** (`DmnReader`), including Camunda Modeler files, and **DMN 1.3 export**
  (`DmnWriter`). `Builder.table(...)` applies a whole table.
- **Enum input bindings** (`enumInput`): lookups by ordinal instead of hashing (104 ns vs 233 ns on
  the bundled 119-rule table).
- **Maven plugin `<fileHeader>`:** your own licence header on generated sources.
- **Loaders:** the Excel and CSV loaders also read from an `InputStream`.
- **Maven Central publishing** (`io.github.brunopacheco1`): `praecepta-engine`,
  `praecepta-loader`, `praecepta-maven-plugin`. Each release is GPG-signed, with a CycloneDX SBOM
  per module, build provenance and third-party notices.
- **Project tooling:**
  - REUSE-compliant licensing (SPDX headers) and a dependency licence check;
  - reproducible builds;
  - a mandatory SonarCloud quality gate;
  - CodeQL, the OpenSSF Scorecard and ORT licence policy;
  - Dependabot with full dependency-graph submission, and dependency review;
  - pinned CI actions.

### Fixed
- **Excel loader:** numeric cells keep their exact value (they were rounded); date cells become ISO
  dates (they were serial numbers); formula cells use their cached result.

### Security
- **`DmnReader` refuses DTDs and external entities** (XXE), and parses with the JDK's secure
  processing enabled.
- **The Maven plugin rejects `<fileHeader>` text with backslashes,** which could otherwise inject
  Java unicode escapes into generated sources.

### Removed
- **The App Engine benchmark services and Gatling stress tests** (replaced by in-process JMH
  benchmarks against Camunda 8 and Drools).
- **`MissingOutputException`** (no match now yields an empty result), **`InvalidPraeceptaException`**
  and the trie visitor API.

[Unreleased]: https://github.com/brunopacheco1/praecepta/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/brunopacheco1/praecepta/releases/tag/v0.1.0
