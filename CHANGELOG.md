<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/); until 1.0, minor versions may change the API.

## [Unreleased]

## [0.3.0] - 2026-11-11

Cells that compare with another input, duration columns, string ranges, COUNT, output entries that
name an input, and columns typed by an item definition: the DMN conformance kit (TCK) now passes 78
of its 86 decision-table checks (was 51). Number, date, time and duration columns are also faster to
evaluate. Read *Changed* before upgrading if you switch over `ColumnType` or deconstruct
`PraeceptaOutput` or `DecisionTable` with record patterns.

### Added
- **Cells that compare with another input** ([#51](https://github.com/brunopacheco1/praecepta/issues/51)):
  in a DMN table a cell may name another input, as in `> limit`, `[floor..limit]` or `not(home)`.
  `DecisionTable` gains `feelCells` (its cells are strict FEEL, set by `DmnReader`) and
  `namedInputs()`; `UnaryTests.Name` holds such a name. The index sees these cells as "any value",
  and the engine checks them, with FEEL's three-valued logic, only on the rules the index leaves, so
  tables without them keep their speed. A named input becomes an input of the engine: bound with
  its own getter, or with map-based inputs read with the type of the column that names it; the Maven
  plugin adds it to the generated input record. The static analysis stays sound: such rules never
  count as overlapping or as hiding another rule (they are still reported unreachable when their
  other cells are covered), and gaps are not searched. Praecepta's own syntax (Excel, CSV, code) is
  unchanged: there an unquoted word is still a string. With the other additions below, the DMN TCK
  passes 78 of 86 checks (was 51 in 0.2.0); the rest need times with a zone
  ([#70](https://github.com/brunopacheco1/praecepta/issues/70)), chained decisions
  ([#14](https://github.com/brunopacheco1/praecepta/issues/14)) or output entries that are
  expressions ([#71](https://github.com/brunopacheco1/praecepta/issues/71)).
- **Duration columns** ([#52](https://github.com/brunopacheco1/praecepta/issues/52)):
  `DAY_TIME_DURATION` (`java.time.Duration`, bound with `durationInput`) and `YEAR_MONTH_DURATION`
  (bound with `periodInput` from a `java.time.Period` of years and months, held as the new
  comparable `feel.YearsAndMonths`, so `P12M` equals `P1Y`). Cells write `duration("P1DT2H")` or,
  outside DMN, the bare ISO text, with comparisons and intervals. `DmnReader` and `DmnWriter` map
  them to `dayTimeDuration` and `yearMonthDuration`, and the Maven plugin generates `Duration` and
  `Period` components. Their TCK checks also compare with other inputs, and pass together with
  #51.
- **String ranges in cells** ([#13](https://github.com/brunopacheco1/praecepta/issues/13)): STRING
  columns take comparisons and intervals (`< "N"`, `["A".."M"]`, also unquoted outside DMN),
  ordered as `String.compareTo` orders them (by UTF-16 code unit, so case-sensitively). Such a
  column is indexed like a number column: values the rules mention keep the hash lookup, and other
  values find their interval by binary search. Since every string then falls in some interval, gaps
  are reported for it without an input value list. `ColumnType.supportsComparisons()` is now true
  for every type but BOOLEAN.
- **COUNT aggregation** ([#15](https://github.com/brunopacheco1/praecepta/issues/15)): a COLLECT
  table can return the number of matching rules. `OutputStrategy.count(int)` builds the output from
  it (zero when no rule matches, so a COUNT table always returns one output); the engine calls it
  once when built, so a strategy without it fails there. `DmnReader` reads `aggregation="COUNT"`,
  and the Maven plugin generates it for a COLLECT table with one NUMBER output.
- **Output entries that name an input** ([#53](https://github.com/brunopacheco1/praecepta/issues/53)):
  a DMN output entry `a` returns the input `a`. `PraeceptaOutput` gains `references` (output
  column to input column; the one-argument constructor keeps working), and the engine fills those
  outputs in from each evaluated input with the text of its value, so an `OutputStrategy` still sees
  only values. Rules without references keep their outputs built once. Under ANY such outputs are
  compared per evaluation; in an output with a priority list they are rejected. `DmnReader` reads
  them when the input has the output's type (a string output takes any input), and `DmnWriter`
  writes them back.
- **Columns typed by an item definition** ([#54](https://github.com/brunopacheco1/praecepta/issues/54)):
  `DmnReader` reads columns whose `typeRef` names one of the model's item definitions, such as
  `tBureauCallType`, a `string` with the allowed values `"FULL","MINI","NONE"`. The column takes
  the base type, and the allowed values become its input value list unless the input declares its
  own. Item definitions may build on one another; structured types and collections are still rejected.
- **`UnaryTests.values()`:** the values of a cell that is a plain list of literals (`"A","B"`), or
  empty for any other cell. The engine, the DMN reader and the Maven plugin now share this one
  definition of a value list.
- **Independently verified reproducible builds:** [Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central)
  rebuilds Praecepta's releases and checks them against Maven Central; 0.2.0 matches bit for bit (14 of
  14 artifacts). Its badge is in the README and on the website.

### Changed
- **Faster number, date, time and duration columns** ([#16](https://github.com/brunopacheco1/praecepta/issues/16)):
  their boundaries are also kept as `long` keys (days since the epoch, nanoseconds, months, or numbers
  scaled to the finest boundary), so a lookup binary-searches a `long[]` instead of comparing
  `BigDecimal` or `LocalDate` objects; a value without an exact key falls back to the old search. On
  an Apple M2 Pro, `EvaluationBenchmark` went from 448-493 to 357-365 ns/op on `mixed-100` and from
  726-911 to 569-573 ns/op on `mixed-1000` (two runs each); string tables are unchanged.
- **Releases keep the build timestamp committed in the tag** (`project.build.outputTimestamp`), so
  a release can be rebuilt bit for bit from its tag with only the version changed. 0.2.0 was
  stamped with the time of its release run; its rebuild sets that value explicitly (see
  `docs/RELEASING.md`).
- **`ColumnType` has two new constants** (`DAY_TIME_DURATION`, `YEAR_MONTH_DURATION`): an exhaustive
  `switch` over it needs new cases.
- **Two records gain a component:** `PraeceptaOutput` gains `references` and `DecisionTable` gains
  `feelCells`. Their former constructors remain, but record patterns that deconstruct them need the
  new component.

### Fixed
- **A rule that tests a column with no input binding is rejected with a clear message** whatever its
  cell. A comparison or interval there (`> 18`, `[18..65]`) used to fail with a raw `CellSyntaxException`
  ("comparisons are only supported on NUMBER…") instead of the `InvalidDecisionTableException` "Rule 7
  tests column 'age', which has no declared input". Wildcards on such columns are still allowed.

## [0.2.0] - 2026-11-08

Boolean, time and date-time columns; the DMN conformance kit (TCK) in every build; fast builds for
large FIRST and PRIORITY tables; and strict FEEL when reading DMN files. Read *Changed* before
upgrading: DMN files that relied on unquoted strings or bare dates no longer load.

### Added
- **Boolean, time and date-time columns** ([#12](https://github.com/brunopacheco1/praecepta/issues/12)):
  `ColumnType.BOOLEAN`, `TIME` and `DATE_TIME`, with cells `true` / `false`, `time("08:30:00")` and
  `date and time("2025-01-31T08:30:00")` (or the bare ISO text), comparisons and intervals on times
  and date-times, and the bindings `booleanInput`, `timeInput` and `dateTimeInput`. `DmnReader`
  reads `boolean`, `time` and `dateTime` columns, `DmnWriter` writes them, and the Maven plugin
  generates `Boolean`, `LocalTime` and `LocalDateTime` components. Times are local: values with a
  zone or offset are rejected. The DMN TCK now passes 51 of 86 checks (was 16).
- **Input names with spaces** ([#47](https://github.com/brunopacheco1/praecepta/issues/47)):
  `DmnReader` accepts FEEL names such as `Credit Score` or `Applicant data.Monthly income`.
- **DMN TCK results** ([#22](https://github.com/brunopacheco1/praecepta/issues/22)): a new
  `praecepta-tck` module runs the decision-table test cases of the DMN Technology Compatibility Kit
  in every build, and keeps the published results (`praecepta-tck/RESULTS.md`) current: what passes,
  and what each unsupported check needs. Every supported check passes.
- **Broader benchmarks**, in which every engine, Praecepta included, reads the same DMN document:
  - tables from 10 to 10,000 rules, and with 5 to 20 columns;
  - every hit policy, including UNIQUE and PRIORITY;
  - requests that match no rule;
  - latency percentiles and memory allocated per decision;
  - throughput with an engine shared by 1, 4 and 8 threads;
  - time to build an engine from DMN, warm and in a fresh JVM;
  - `run-benchmarks.sh` runs them all and records the machine and versions with the results.
- **Fuzz tests** (Jazzer) for the cell parser and the DMN reader: they replay saved inputs in every
  build, fuzz weekly in CI, and fuzz every pull request that touches the engine (ClusterFuzzLite).
- **Releases attach their build provenance** (`praecepta-X.Y.Z.intoto.jsonl`) for offline
  verification.
- **Project documents:** governance (roles, decisions, continuity), architecture, a security
  assurance case and a one-year roadmap. The website shows the roadmap, a C4 system context diagram,
  and what users can expect on security, and links them all.
- **A project website** at https://bruno.pacheco.lu/praecepta/, with the API documentation of the
  latest release, and a Classic Rome identity: a bronze *tabula ansata* logo, Cinzel and EB Garamond.
- **An enforced code style:** Checkstyle checks every build (`checkstyle.xml`, described in
  CONTRIBUTING.md), and compiler warnings fail the build (`-Xlint:all -Werror`).

### Changed
- **`DmnReader` reads cells as strict FEEL:** an unquoted word in a string cell is a reference to
  another variable, so it is rejected as unsupported instead of being read as a string (the DMN TCK
  caught `Complex.aString` being compared as text); and dates and times need their functions
  (`date("2025-01-31")`), since in FEEL a bare `2025-01-31` is a subtraction. Excel, CSV and code
  keep the shorter `PT,ES` and bare ISO forms; `CellParser.parseFeel` is the strict parser.
- **`ColumnType` has three new constants** (`BOOLEAN`, `TIME`, `DATE_TIME`): an exhaustive `switch`
  over it needs new cases.
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

[Unreleased]: https://github.com/brunopacheco1/praecepta/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/brunopacheco1/praecepta/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/brunopacheco1/praecepta/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/brunopacheco1/praecepta/releases/tag/v0.1.0
