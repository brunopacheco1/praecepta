<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/brand/wordmark-dark.svg">
    <img src="site/brand/wordmark-light.svg" alt="Praecepta" width="380">
  </picture>
</p>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brunopacheco1/praecepta-engine)](https://central.sonatype.com/artifact/io.github.brunopacheco1/praecepta-engine) [![Javadoc](https://img.shields.io/maven-central/v/io.github.brunopacheco1/praecepta-engine?label=javadoc&color=brightgreen)](https://javadoc.io/doc/io.github.brunopacheco1/praecepta-engine) [![Build](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml/badge.svg)](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml) [![CodeQL](https://github.com/brunopacheco1/praecepta/actions/workflows/codeql.yml/badge.svg)](https://github.com/brunopacheco1/praecepta/actions/workflows/codeql.yml) [![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/brunopacheco1/praecepta/badge)](https://scorecard.dev/viewer/?uri=github.com/brunopacheco1/praecepta) [![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16426/badge)](https://www.bestpractices.dev/projects/16426) [![OpenSSF Baseline](https://www.bestpractices.dev/projects/16426/baseline)](https://www.bestpractices.dev/projects/16426) [![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE) [![REUSE status](https://api.reuse.software/badge/github.com/brunopacheco1/praecepta)](https://api.reuse.software/info/github.com/brunopacheco1/praecepta) [![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/io/github/brunopacheco1/praecepta/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md) [![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=coverage)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta)

# Praecepta
**DMN decision tables for Java.** *Praecepta* is Latin for "rules" (classical pronunciation:
*pry-KEP-ta*). [Website](https://bruno.pacheco.lu/praecepta/) ·
[API documentation](https://bruno.pacheco.lu/praecepta/api/engine/)

Praecepta evaluates DMN decision tables in Java, with strict DMN 1.3 hit-policy semantics and
lookups that cost a fraction of a microsecond. Write tables in Excel, CSV, DMN or code; validate
them when you build; evaluate them with a dependency-free engine.

> **Status: 0.x.** The API may still change between minor versions until 1.0. It covers the
> decision-table part of DMN, not full FEEL or decision requirement graphs; see
> [what Praecepta is not](#what-praecepta-is-not).

# Features
- All DMN hit policies: UNIQUE, ANY, PRIORITY, FIRST, OUTPUT_ORDER, RULE_ORDER and COLLECT
  (with SUM / MIN / MAX / COUNT aggregation)
- DMN FEEL cells on STRING, NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION and
  YEAR_MONTH_DURATION columns: lists, `not(...)`,
  comparisons, intervals, `null`; unquoted `PT,ES` cells from earlier versions keep working
- List inputs, such as a customer's nationalities: a cell holds when some element passes it
- Optional DMN input value lists: values outside them are rejected
- Static validation when the table is built: overlapping rules (UNIQUE), conflicting outputs (ANY),
  rules that can never be selected (FIRST, PRIORITY), and gaps (declared inputs no rule matches)
- Bitset matching: memory grows linearly with the table (10,000 rules take 1.5 to 5 MB of heap,
  against about 90 to 130 MB for Camunda and Drools) and most engines build in milliseconds; the
  mask of a value only a few rules accept, such as a product code, is stored compactly, so even a
  column with a value per rule stays small
- Enum inputs: lookups by ordinal instead of hashing
- Immutable, thread-safe engines; no runtime dependencies
- Rules from code, Excel, CSV or DMN files (DMN 1.1 to 1.5, as written by Camunda Modeler and
  other tools), and DMN export
- A Maven plugin that validates tables at build time and generates typed input and output
  records, so applications ship only the engine

# Installation
Java 21 or later (tested on 21, 25 and 27). Pick the modules you need:

```xml
<!-- the engine: no dependencies, a JPMS module (io.github.brunopacheco1.praecepta.engine) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-engine</artifactId>
  <version>0.3.0</version>
</dependency>

<!-- optional: load rules from Excel or CSV at runtime (brings Apache POI) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-loader</artifactId>
  <version>0.3.0</version>
</dependency>
```

Or compile tables at build time with the [Maven plugin](#praecepta-maven-plugin), so that only
`praecepta-engine` is needed at runtime. Every release is signed and has an SBOM, SLSA Build Level 2
provenance (`gh attestation verify`) and third-party notices on its
[GitHub release](https://github.com/brunopacheco1/praecepta/releases), and
[Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md) rebuilds it independently to check it is bit-for-bit identical to Maven
Central.

# What Praecepta is not
Praecepta does one thing: single DMN decision tables, fast. Use Camunda or Drools when you need:
- **Full FEEL**: functions, arithmetic, contexts, lists and computed expressions (e.g.
  `income > 2 * rent`). Praecepta cells are FEEL *simple unary tests*; in DMN they may compare with
  another input (`> limit`), but not compute with it.
- **Decision requirement graphs**: decisions feeding other decisions, business knowledge models,
  boxed expressions.
- **A process engine, a modeller or a decision service** around the tables.

# Getting Started
## Praecepta Engine
A rule (`Praeceptum`) maps input columns to cells and output columns to values. In a cell, blank
means "any value" and `a,b` means "a or b". Rules are evaluated in the order given (DMN rule order).

```java
record Order(String country, String segment) {}
record Discount(String grade) {}

var rules = List.of(
        new Praeceptum(1, new PraeceptaInput(Map.of("country", "PT", "segment", "SMB")),
                new PraeceptaOutput(Map.of("grade", "A"))),
        new Praeceptum(2, new PraeceptaInput(Map.of("country", "PT,ES", "segment", "")),
                new PraeceptaOutput(Map.of("grade", "B"))));

var engine = PraeceptaEngine.<Order, Discount>builder()
        .hitPolicy(HitPolicy.FIRST)
        .input("country", Order::country)          // bind each column to a getter
        .input("segment", Order::segment)
        .output(output -> new Discount(output.values().get("grade")))
        .rules(rules)
        .build();

engine.evaluate(new Order("PT", "SMB"));   // [Discount[grade=A]]
engine.evaluate(new Order("FR", "SMB"));   // [] - no rule matches
engine.validationReport();                 // static findings, e.g. unreachable rules
```

### Cells
Cells are DMN FEEL simple unary tests, parsed according to the column's type:

| Cell | Matches | Columns |
|---|---|---|
| blank or `-` | any value, including a missing one | all |
| `"PT","ES"` or `PT,ES` | one of the values (unquoted words are string literals) | STRING |
| `18`, `2.5` | that number (`1.0` equals `1`) | NUMBER |
| `date("2025-01-31")` or `2025-01-31` | that date | DATE |
| `true`, `false` | that boolean | BOOLEAN |
| `time("08:30:00")` or `08:30:00` | that time of day | TIME |
| `time("08:30:00+01:00")` or `08:30:00Z` | that instant of the day, at an offset | TIME |
| `date and time("2025-01-31T08:30:00")` or `2025-01-31T08:30:00` | that date and time | DATE_TIME |
| `date and time("2025-01-31T08:30:00Z")`, `date and time("2025-01-31T08:30:00@Europe/Paris")` | that instant | DATE_TIME |
| `duration("P1DT2H")` or `P1DT2H` | that length of time (`PT24H` equals `P1D`) | DAY_TIME_DURATION |
| `duration("P1Y2M")` or `P1Y2M` | that number of months (`P12M` equals `P1Y`) | YEAR_MONTH_DURATION |
| `< 18`, `<= 18`, `> 18`, `>= 18`, `< "N"` | comparisons | all but BOOLEAN |
| `[18..65)`, `(0..1]`, `]a..b[`, `["A".."M"]` | intervals: `[`/`]` closed, `(`/`)` or outward brackets open | all but BOOLEAN |
| `null` | only a missing value | all |
| `not(...)` | anything the list inside does not match | all |

Missing values follow FEEL: they fail comparisons and intervals, equal only `null`, and so satisfy
`not("FR")`. Blank text counts as missing. Strings compare as Java's `String.compareTo` does,
character by character and case-sensitively (`"Zed" < "apple"`).

Times may have an offset (`Z`, `+01:00`) and date-times an offset or a zone id (`@Europe/Paris`).
As in FEEL, values with a time zone compare by the instant they denote (`time("09:00:00+01:00")`
equals `time("08:00:00Z")`), and a value with a zone cannot be compared with one without: the test
is unknown, like a comparison with a missing value, so neither `time("08:00:00")` nor
`not(time("08:00:00"))` matches `08:00Z`. A time with a zone id (`time("08:00:00@Europe/Paris")`)
is rejected, as it has no fixed offset.

### Typed columns and input value lists
```java
var engine = PraeceptaEngine.<Applicant, String>builder()
        .hitPolicy(HitPolicy.FIRST)
        .input("country", Applicant::country)          // STRING
        .numberInput("age", Applicant::age)            // any Number, compared as exact decimals
        .dateInput("since", Applicant::since)          // LocalDate
        .booleanInput("student", Applicant::student)   // Boolean
        .timeInput("callTime", Applicant::callTime)    // LocalTime
        .dateTimeInput("appliedAt", Applicant::appliedAt) // LocalDateTime
        .offsetTimeInput("cutOff", Applicant::cutOff)  // OffsetTime, compared by instant
        .zonedDateTimeInput("signedAt", Applicant::signedAt) // ZonedDateTime (from an OffsetDateTime: toZonedDateTime())
        .durationInput("waited", Applicant::waited)    // Duration
        .periodInput("tenure", Applicant::tenure)      // Period of years and months
        .allowedValues("country", List.of("PT", "ES", "FR"))
        .allowedValues("age", "[0..120]")
        .output(output -> output.values().get("tier"))
        .rules(rules)
        .build();
```
With declared input values, other (non-missing) values throw `InvalidInputException`, and
`validationReport()` lists **gaps**: declared inputs no rule matches, e.g.
`country = "FR", age in [18..120]`. Gaps are only analysed when every string column the rules test
declares its values or uses comparisons or intervals (any other string then falls between them). With map-based inputs, declare non-string columns with
`.columnType("age", ColumnType.NUMBER)`; their text is parsed. A time or date-time column then takes
values with and without a time zone (`08:30`, `08:30Z`, `2025-01-31T08:30@Europe/Paris`), and its
gaps are looked for among both, while a typed getter gives one kind only.

Notes:
- `PRIORITY` and `OUTPUT_ORDER` need DMN output value lists:
  `.outputPriority("grade", List.of("A", "B"))`, highest priority first.
- An input matching several rules under `UNIQUE` (or rules with different outputs under `ANY`)
  throws `HitPolicyViolationException`.
- `.failOn(Severity.ERROR)` makes `build()` reject tables whose validation report has errors.
- `.inputs(order -> new PraeceptaInput(...))` binds all columns from a map instead of per column.
- `.enumInput("country", Country.class, Order::country)` binds a column to an enum. Lookups then
  index an array by ordinal instead of hashing, and the enum's constants become the column's
  input value list. Each constant stands for its `name()`, or pass a function such as
  `Country::code`.

### List inputs
A column may hold several values, such as a customer's nationalities. Its cells are written as
usual, and hold when **some element** passes one of the tests; `not(...)` holds when **no element**
does:

```java
var engine = PraeceptaEngine.<Customer, String>builder()
        .hitPolicy(HitPolicy.FIRST)
        .listInput("nationalities", Customer::nationalities)        // a Collection<String>
        .listInput("ages", ColumnType.NUMBER, Customer::childrenAges) // a Collection of numbers
        .output(output -> output.values().get("offer"))
        .rules(rules)
        .build();
```

| Cell | `["PT","BR"]` | `["BR"]` | `[]` (empty) | `null` (missing) |
|---|---|---|---|---|
| `"PT","ES"` (holds PT or ES) | yes | | | |
| `not("PT")` (holds no PT) | | yes | yes | |
| `null` (no list) | | | | yes |
| `-` | yes | yes | yes | yes |

These are the semantics of FEEL's `list contains`: a missing list makes every test unknown, while an
empty one passes none. Elements may not be missing, and declared input values apply to each element.
With map-based inputs, declare the column with `.listColumnType("nationalities", ColumnType.STRING)`
and give a FEEL list as text: `["PT", "BR"]` (or `PT, BR`), `[]` for an empty list. Rules that test a
list column are left out of the static analysis (overlaps, unreachable rules, gaps), since a list
can match rules whose values never meet in a single value.

### DMN
`DmnReader` loads a decision table from a DMN file (DMN 1.1 to 1.5), including its hit policy,
column types and value lists. `Builder.table(...)` applies all of it; inputs are bound as usual,
with the types the table declares.

```java
DecisionTable table;
try (var dmn = Files.newInputStream(Path.of("discount.dmn"))) {
    table = DmnReader.read(dmn);               // or read(dmn, "Decision name") if there are several
}
var engine = PraeceptaEngine.<Customer, String>builder()
        .table(table)
        .input("country", Customer::country)
        .numberInput("age", Customer::age)
        .output(output -> output.values().get("tier"))
        .build();
```

Supported: input expressions that name a variable, including FEEL names with spaces (`age`,
`customer.age`, `Credit Score`); `string`, `number` (`integer`, `long`, `double`), `date`,
`boolean`, `time`, `dateTime`, `dayTimeDuration` and `yearMonthDuration` columns, or an item definition of one of them (such as a `string`
with allowed values, which become the input value list); input entries in the cell syntax above, as strict FEEL
(strings quoted, since an unquoted word is a reference to another variable, and dates and times
written with `date(...)`, `time(...)` and `date and time(...)`); input entries that compare with
another input (`> limit`, `[floor..limit]`, `not(home)`), which then becomes an input of the engine,
bound like a column (with map-based inputs it is read with the type of the column that names it);
columns typed by a collection (`isCollection="true"`, with allowed values on the item type), whose
entries test the elements with FEEL's list functions (`list contains(?, "PT")`,
`some x in ? satisfies x >= 18`, `? = null`, joined with `or`, and `not(...)`); entries that name a
list input (`Flu Symptoms`, typed by its `inputData`), which hold when the value is in the list;
output entries that are literals, name one of the table's inputs (`a` returns the input `a`), or
compute their value from literals and inputs with `+`, `-`, `*`, `/` and parentheses
(`Symptom + " is listed"`, `(price - discount) * 1.2`: `+` joins strings or adds numbers, a missing
operand gives a missing output); times and date-times with or without a time zone; every hit policy,
with SUM, MIN, MAX and COUNT aggregation. Anything else (a time with a zone id, function calls such as `upper case(a)`, `if`, inputs computed by other
decisions) is rejected with a message naming the decision, rule and column. `DmnWriter.write(table)` produces DMN 1.3 that Camunda and Drools
evaluate identically, which is how the benchmarks feed them.

## Praecepta Loader
Loads rules from the first sheet of an Excel file (or a semicolon-separated CSV file), from the
classpath or from an `InputStream`. The header row
names the columns; each following row is a rule, identified by its row number. Cells are read as
text in the syntax above; numeric cells keep their exact value and date-formatted cells become ISO
dates.

```java
var rules = new ExcelLoader("rules/discounts.xlsx", Set.of("country", "segment"), Set.of("grade"))
        .getPraecepta();

var engine = PraeceptaEngine.<Order, Discount>builder()
        .hitPolicy(HitPolicy.FIRST)
        .input("country", Order::country)
        .input("segment", Order::segment)
        .output(output -> new Discount(output.values().get("grade")))
        .rules(rules)
        .build();
```

## Praecepta Maven Plugin
Runs on Maven 3.9 and later (CI tests 3.9, 3.10 and the 4.0 release candidate) with Java 21+.

Compiles tables at build time. For each table the `generate` goal:
1. loads it from Excel, CSV or DMN;
2. validates it, failing the build on errors (`<failOn>`: `ERROR` by default, `WARNING` or
   `NONE`) and logging the rest;
3. writes it as a DMN resource next to the generated classes;
4. generates `<Name>Input` and `<Name>Output` records and a `<Name>` class that evaluates the DMN.

A string column with a value list becomes an enum in the input record, using the int-coded path.
Numbers are `BigDecimal` and dates `LocalDate`. Times and date-times are `LocalTime` and
`LocalDateTime`, or `OffsetTime` and `ZonedDateTime` when every value written for the column has a
time zone (an input compared with such a column in a cell, as in `> limit`, takes a zone too); a
column that mixes both kinds fails the build. An input declared with `<list>true</list>` (or typed
by a collection in a DMN file) becomes a `java.util.List` of its type. At runtime the generated code
needs only
`praecepta-engine`; Apache POI stays a build-time dependency.

```xml
<plugin>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-maven-plugin</artifactId>
  <version>0.3.0</version>
  <executions><execution><goals><goal>generate</goal></goals></execution></executions>
  <configuration>
    <packageName>com.acme.pricing</packageName>
    <tables>
      <table>
        <source>src/main/rules/discount.xlsx</source>
        <hitPolicy>FIRST</hitPolicy>
        <inputs>
          <input><name>country</name><allowedValues>"PT","ES","FR"</allowedValues></input>
          <input><name>age</name><type>NUMBER</type></input>
        </inputs>
        <outputs>
          <output><name>discount</name><type>NUMBER</type></output>
        </outputs>
      </table>
      <table>
        <!-- DMN files carry their own hit policy, columns and value lists -->
        <source>src/main/rules/shipping.dmn</source>
      </table>
    </tables>
  </configuration>
</plugin>
```

```java
Optional<DiscountOutput> discount = Discount.evaluate(new DiscountInput(DiscountInput.Country.PT, new BigDecimal(30)));
```

Single-hit tables (and aggregations) return an `Optional`; multi-hit tables return a `List`.
Generated code belongs to your project, so it carries no Praecepta licence header. Set
`<fileHeader>` to prepend your own, such as your project's SPDX lines.
`Discount.validationReport()` gives the build-time findings, and `Discount.engine()` the engine.

# Building and Testing
Building Praecepta needs **Java 21** or later and Maven 3.9+; CI builds on Java 21, 25 and 27. From the repository root:

```
mvn clean verify
```

This runs the unit tests and the property-based tests in `praecepta-engine`, which check the
engine against a naive reference implementation of DMN decision-table semantics on random tables.

# DMN conformance
`praecepta-tck` runs the decision-table test cases of the
[DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) in every build. Every check whose
table Praecepta can read passes (80 of 86); the others need features it does not have yet: inputs
computed by other decisions ([#14](https://github.com/brunopacheco1/praecepta/issues/14)) and times
with a time zone ([#70](https://github.com/brunopacheco1/praecepta/issues/70)). The pass rate, the results per
test case and what each unsupported check needs are in
[praecepta-tck/RESULTS.md](praecepta-tck/RESULTS.md).

# Benchmarks
`praecepta-benchmarks` compares Praecepta in-process with Camunda 8's DMN engine (dmn-scala, as
embedded by Zeebe) and Drools DMN, using [JMH](https://github.com/openjdk/jmh). Every engine reads
the same DMN document, and each run first checks that every engine returns the same answers as
Praecepta built straight from the rules.

The tables (`-p table=...`) are:
- `real`: the bundled 119-rule, 13-column table (FIRST);
- `synthetic-<rules>[x<columns>]`: generated string tables whose rules overlap (COLLECT);
- `mixed-<rules>[x<columns>]`: generated tables mixing string, number and date columns, with
  comparisons, intervals and `not(...)` (COLLECT);
- `unique-<rules>[x<columns>]`: generated string tables whose rules never overlap (UNIQUE);
- `lookup-<rules>[x<columns>]`: generated string tables with many values per column, as in a price
  list by product code: each rule has its own code, so most value classes hold a few rules
  (COLLECT);
- a suffix `-first`, `-priority`, `-ruleorder`, `-collect` or `-unique` changes only the hit
  policy: `mixed-1000-first` has the rules of `mixed-1000`.

The benchmarks are:
- `EvaluationBenchmark`: latency of one decision, with `-p workload=match` (every input matches a
  rule) or `nomatch` (none does); add `-bm sample` for percentiles and `-prof gc` for the memory
  allocated per decision;
- `ThroughputBenchmark`: decisions per millisecond with one engine shared by `-t <n>` threads;
- `LifecycleBenchmark`: reading the DMN and building the engine, in a warm JVM;
- `FirstDecisionBenchmark`: reading the DMN, building the engine and evaluating one input in a
  fresh JVM (needs fixtures, see `run-benchmarks.sh`);
- `InputPathBenchmark`: the real table fed as a map, generated records or enums;
- `FootprintReport` (a `main`, not JMH): retained heap and build time.

`run-benchmarks.sh` runs the whole matrix and writes the raw JMH results, the machine and versions,
and a summary (`summary.md`, `results.csv`) into `praecepta-benchmarks/results/`. Published runs
are kept there whole, so they can be audited: every suite's log and raw JSON, the footprint log, the
SHA-256 of the `benchmarks.jar` that ran (in `environment.txt`) and each exact command line
(`commands.txt`).

```
mvn -pl praecepta-benchmarks -am package -DskipTests
praecepta-benchmarks/run-benchmarks.sh               # everything, about 2.5 hours
praecepta-benchmarks/run-benchmarks.sh quick         # a few tables, about 15 minutes
java -jar praecepta-benchmarks/target/benchmarks.jar EvaluationBenchmark -p table=real   # one benchmark
java -cp praecepta-benchmarks/target/benchmarks.jar \
  io.github.brunopacheco1.praecepta.benchmarks.FootprintReport praecepta real           # retained heap
```

The `Benchmarks` GitHub workflow runs the quick profile on demand (or the full one, when asked) and
uploads the results. Shared runners are noisy: use them to spot large regressions, not to publish
numbers.

Drools' experimental alpha-network compilation is not part of the default comparison: on Drools
10.2.0 it disagrees with every other engine on these tables (see
[docs/MODERNISATION.md](docs/MODERNISATION.md)). It can be checked on demand with
`-p engine=drools-alphanetwork`.

# Dependencies
Praecepta Engine has no runtime dependencies; it reads DMN with the JDK's XML parser.

Praecepta Loader depends on Apache POI. The Maven plugin uses the loader at build time only.

# Roadmap
[docs/ROADMAP.md](docs/ROADMAP.md) has the plans for the next year; past decisions and their reasons are in
[docs/MODERNISATION.md](docs/MODERNISATION.md).

# Project documents
- [Architecture](docs/ARCHITECTURE.md): modules, the bitset index, DMN and the Maven plugin.
- [Security assurance case](docs/SECURITY-ASSURANCE.md): what to expect, threats, and how they are countered.
- [Governance](GOVERNANCE.md): roles, decisions and continuity.
- [Contributing](CONTRIBUTING.md), [code of conduct](CODE_OF_CONDUCT.md) and [security policy](SECURITY.md).

# Contributing
Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems
privately as described in [SECURITY.md](SECURITY.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md).

# License
Praecepta is licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE). Every
file carries an SPDX header or is covered by [REUSE.toml](REUSE.toml), and the repository is
[REUSE](https://reuse.software)-compliant. Each published module ships a CycloneDX SBOM.