<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brunopacheco1/praecepta-engine)](https://central.sonatype.com/artifact/io.github.brunopacheco1/praecepta-engine) [![Javadoc](https://javadoc.io/badge2/io.github.brunopacheco1/praecepta-engine/javadoc.svg)](https://javadoc.io/doc/io.github.brunopacheco1/praecepta-engine) ![Workflow](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml/badge.svg) [![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/brunopacheco1/praecepta/badge)](https://scorecard.dev/viewer/?uri=github.com/brunopacheco1/praecepta) [![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=coverage)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta)

# Praecepta
Praecepta evaluates DMN decision tables in Java, with strict DMN 1.3 hit-policy semantics and
lookups that cost a fraction of a microsecond. Write tables in Excel, CSV, DMN or code; validate
them when you build; evaluate them with a dependency-free engine.

> **Status: 0.x.** The API may still change between minor versions until 1.0. It covers the
> decision-table part of DMN, not full FEEL or decision requirement graphs; see
> [what Praecepta is not](#what-praecepta-is-not).

# Features
- All DMN hit policies: UNIQUE, ANY, PRIORITY, FIRST, OUTPUT_ORDER, RULE_ORDER and COLLECT
  (with SUM / MIN / MAX aggregation)
- DMN FEEL cells on STRING, NUMBER and DATE columns: lists, `not(...)`, comparisons, intervals,
  `null`; unquoted `PT,ES` cells from earlier versions keep working
- Optional DMN input value lists: values outside them are rejected
- Static validation when the table is built: overlapping rules (UNIQUE), conflicting outputs (ANY),
  rules that can never be selected (FIRST, PRIORITY), and gaps (declared inputs no rule matches)
- Bitset matching: memory grows linearly with the table (10,000 rules fit in under 1 MB) and
  engines build in milliseconds
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
  <version>0.1.0</version>
</dependency>

<!-- optional: load rules from Excel or CSV at runtime (brings Apache POI) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-loader</artifactId>
  <version>0.1.0</version>
</dependency>
```

Or compile tables at build time with the [Maven plugin](#praecepta-maven-plugin), so that only
`praecepta-engine` is needed at runtime. Every release is signed and has an SBOM, build provenance
and third-party notices on its [GitHub release](https://github.com/brunopacheco1/praecepta/releases).

# What Praecepta is not
Praecepta does one thing: single DMN decision tables, fast. Use Camunda or Drools when you need:
- **Full FEEL**: functions, arithmetic, contexts, lists and expressions spanning several columns
  (e.g. `income > 2 * rent`). Praecepta cells are FEEL *simple unary tests* on one column.
- **Decision requirement graphs**: decisions feeding other decisions, business knowledge models,
  boxed expressions.
- **Boolean, time and date-time columns** (planned), and string ranges.
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
| `< 18`, `<= 18`, `> 18`, `>= 18` | comparisons | NUMBER, DATE |
| `[18..65)`, `(0..1]`, `]a..b[` | intervals: `[`/`]` closed, `(`/`)` or outward brackets open | NUMBER, DATE |
| `null` | only a missing value | all |
| `not(...)` | anything the list inside does not match | all |

Missing values follow FEEL: they fail comparisons and intervals, equal only `null`, and so satisfy
`not("FR")`. Blank text counts as missing.

### Typed columns and input value lists
```java
var engine = PraeceptaEngine.<Applicant, String>builder()
        .hitPolicy(HitPolicy.FIRST)
        .input("country", Applicant::country)          // STRING
        .numberInput("age", Applicant::age)            // any Number, compared as exact decimals
        .dateInput("since", Applicant::since)          // LocalDate
        .allowedValues("country", List.of("PT", "ES", "FR"))
        .allowedValues("age", "[0..120]")
        .output(output -> output.values().get("tier"))
        .rules(rules)
        .build();
```
With declared input values, other (non-missing) values throw `InvalidInputException`, and
`validationReport()` lists **gaps**: declared inputs no rule matches, e.g.
`country = "FR", age in [18..120]`. Gaps are only analysed when every string column the rules test
declares its values. With map-based inputs, declare non-string columns with
`.columnType("age", ColumnType.NUMBER)`; their text is parsed.

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

Supported: input expressions that name a variable (`age`, `customer.age`); `string`, `number`
(`integer`, `long`, `double`) and `date` columns; input entries in the cell syntax above; literal
output entries; every hit policy, with SUM, MIN and MAX aggregation. Anything else (boolean or
date-time columns, computed expressions, function calls, COUNT) is rejected with a message naming
the decision, rule and column. `DmnWriter.write(table)` produces DMN 1.3 that Camunda and Drools
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
Numbers are `BigDecimal` and dates `LocalDate`. At runtime the generated code needs only
`praecepta-engine`; Apache POI stays a build-time dependency.

```xml
<plugin>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-maven-plugin</artifactId>
  <version>0.1.0</version>
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

# Benchmarks
`praecepta-benchmarks` compares Praecepta in-process with Camunda 8's DMN engine (dmn-scala, as
embedded by Zeebe) and Drools DMN, using [JMH](https://github.com/openjdk/jmh). Every engine
evaluates the same rules, and each run first checks that all engines return the same answers.
The tables are:
- `real`: the bundled 119-rule table;
- `synthetic-100` / `synthetic-1000`: generated string tables;
- `mixed-100` / `mixed-1000`: generated tables mixing string, number and date columns, with
  comparisons, intervals and `not(...)`.

```
mvn -pl praecepta-benchmarks -am package -DskipTests
java -jar praecepta-benchmarks/target/benchmarks.jar                      # latency, all engines and tables
java -jar praecepta-benchmarks/target/benchmarks.jar -p table=real        # one table
java -jar praecepta-benchmarks/target/benchmarks.jar InputPathBenchmark  # real table: map vs generated records vs enums
java -cp praecepta-benchmarks/target/benchmarks.jar \
  io.github.brunopacheco1.praecepta.benchmarks.FootprintReport praecepta real   # retained heap
```

The `Benchmarks` GitHub workflow runs the same on demand and uploads the results.

Drools' experimental alpha-network compilation is not part of the default comparison: on Drools
10.2.0 it disagrees with every other engine on these tables (see
[docs/MODERNISATION.md](docs/MODERNISATION.md)). It can be checked on demand with
`-p engine=drools-alphanetwork`.

# Dependencies
Praecepta Engine has no runtime dependencies; it reads DMN with the JDK's XML parser.

Praecepta Loader depends on Apache POI. The Maven plugin uses the loader at build time only.

# Roadmap
See [docs/MODERNISATION.md](docs/MODERNISATION.md).

# Contributing
Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems
privately as described in [SECURITY.md](SECURITY.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md).

# License
Praecepta is licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE). Every
file carries an SPDX header or is covered by [REUSE.toml](REUSE.toml), and the repository is
[REUSE](https://reuse.software)-compliant. Each published module ships a CycloneDX SBOM.