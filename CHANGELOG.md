<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions will follow [Semantic Versioning](https://semver.org/) from the first release.

## [Unreleased]

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
