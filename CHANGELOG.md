<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions will follow [Semantic Versioning](https://semver.org/) from the first release.

## [Unreleased]

### Changed
- **Licence: Praecepta is now licensed under the Apache License 2.0** (previously MIT). No version
  was ever released under MIT.
- Java 25 baseline. Strict DMN 1.3 hit-policy semantics (UNIQUE, ANY, PRIORITY, FIRST,
  OUTPUT_ORDER, RULE_ORDER, COLLECT with SUM/MIN/MAX).
- Matching uses a bitset index: memory grows linearly with the table, and engines build in
  milliseconds.
- The Maven plugin is rewritten. It validates tables at build time, writes DMN resources, and
  generates typed input/output records and a decision class that needs only the engine at runtime.

### Added
- Cells in a DMN FEEL subset on string, number and date columns: lists, `not(...)`, comparisons,
  intervals, `null`.
- Input value lists, enforced at evaluation, and validation reports: overlaps, conflicting
  outputs, unreachable rules, gaps.
- DMN 1.1–1.5 import (`DmnReader`) and DMN 1.3 export (`DmnWriter`).
- Enum input bindings (int-coded lookups).
- Excel and CSV loaders also read from an `InputStream`.
- REUSE-compliant licensing (SPDX headers), CycloneDX SBOMs per module, a dependency licence check,
  reproducible builds, CodeQL, OpenSSF Scorecard, Dependabot and pinned CI actions.

### Removed
- The App Engine benchmark services and Gatling stress tests (replaced by in-process JMH
  benchmarks).
- `MissingOutputException` (no match now yields an empty result) and `InvalidPraeceptaException`.
