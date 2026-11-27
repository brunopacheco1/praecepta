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

[![Maven Central](https://img.shields.io/maven-central/v/io.github.brunopacheco1/praecepta-engine)](https://central.sonatype.com/artifact/io.github.brunopacheco1/praecepta-engine) [![Javadoc](https://img.shields.io/maven-central/v/io.github.brunopacheco1/praecepta-engine?label=javadoc&color=brightgreen)](https://bruno.pacheco.lu/praecepta/api/engine/) [![Build](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml/badge.svg)](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml) [![CodeQL](https://github.com/brunopacheco1/praecepta/actions/workflows/codeql.yml/badge.svg)](https://github.com/brunopacheco1/praecepta/actions/workflows/codeql.yml) [![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/brunopacheco1/praecepta/badge)](https://scorecard.dev/viewer/?uri=github.com/brunopacheco1/praecepta) [![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16426/badge)](https://www.bestpractices.dev/projects/16426) [![OpenSSF Baseline](https://www.bestpractices.dev/projects/16426/baseline)](https://www.bestpractices.dev/projects/16426) [![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE) [![REUSE status](https://api.reuse.software/badge/github.com/brunopacheco1/praecepta)](https://api.reuse.software/info/github.com/brunopacheco1/praecepta) [![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/io/github/brunopacheco1/praecepta/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md) [![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=coverage)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta)

# Praecepta
**DMN decision tables for Java.** *Praecepta* is Latin for "rules" (say *pry-KEP-ta*).
[Website](https://bruno.pacheco.lu/praecepta/) ·
[API documentation](https://bruno.pacheco.lu/praecepta/api/engine/)

Praecepta is a DMN 1.5 engine for Java. It evaluates decision tables in a fraction of a microsecond, and
the decision requirement graphs around them. Write tables in a DMN modeller, Excel, CSV or code. Validate
them when you build. Evaluate them with an engine that has no dependencies, on its own or as beans in
Spring Boot, Quarkus or Micronaut.

> **Status: 0.x.** The API may change between minor versions until 1.0. Praecepta passes 3,482 of the
> 3,511 checks of the DMN TCK (99.2 %) and rejects external functions by design. A formal claim of
> DMN 1.5 conformance is planned for 1.0; see [DMN conformance](#dmn-conformance).

# Features
- **Every DMN hit policy**: UNIQUE, ANY, PRIORITY, FIRST, OUTPUT ORDER, RULE ORDER, and COLLECT with SUM,
  MIN, MAX and COUNT.
- **Validation when the table is built**: overlapping rules, conflicting outputs, rules that can never be
  selected, gaps, and default outputs no input can reach.
- **Bitset matching**: one lookup per column and a bitwise AND. Memory grows linearly with the table.
- **The full FEEL language** with its built-in functions, in cells, input expressions, output entries
  and literal expressions.
- **Decision requirement graphs**: decision tables, literal and boxed expressions, business knowledge
  models, decision services and imported models.
- **Rules from code, Excel, CSV or DMN** (DMN 1.1 to 1.5), and DMN export.
- **Typed columns**: strings, numbers, booleans, dates, times, date-times and durations, with or without
  a time zone, and list inputs.
- **Immutable, thread-safe engines** with no runtime dependencies.
- **[Maven and Gradle plugins](docs/INTEGRATIONS.md#build-plugins)** that validate tables at build time
  and generate typed classes.
- **[Spring Boot, Micronaut and Quarkus integrations](docs/INTEGRATIONS.md#frameworks)** that inject one
  engine per decision and refuse to start with an invalid table.
- **[GraalVM native images](docs/INTEGRATIONS.md#native-images)** with no configuration for Praecepta.

# Installation
Praecepta needs Java 21 or later. CI tests it on Java 21, 25 and 27.

```xml
<!-- the engine: no dependencies, a JPMS module (io.github.brunopacheco1.praecepta.engine) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-engine</artifactId>
  <version>0.11.0</version>
</dependency>

<!-- optional: load rules from Excel or CSV at runtime (brings Apache POI) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-loader</artifactId>
  <version>0.11.0</version>
</dependency>
```

Or let your build or framework do it:

| You use | Add | What it does |
|---|---|---|
| Maven | [`praecepta-maven-plugin`](docs/INTEGRATIONS.md#maven-plugin) | Validates tables at build time and generates typed classes. Your application ships only the engine. |
| Gradle | [plugin `io.github.brunopacheco1.praecepta`](docs/INTEGRATIONS.md#gradle-plugin) | The same, for Gradle 8.5 and later. |
| Spring Boot 4 | [`praecepta-spring-boot-starter`](docs/INTEGRATIONS.md#spring-boot) | Loads the DMN files under `rules/` at startup and injects one engine per decision. |
| Micronaut 4 | [`praecepta-micronaut`](docs/INTEGRATIONS.md#micronaut) | The same, for Micronaut. |
| Quarkus 3.40+ | [`praecepta-quarkus`](docs/INTEGRATIONS.md#quarkus) | Validates the tables while Quarkus builds the application, and injects one engine per decision. |
| Another framework, or none | [`praecepta-integration`](docs/INTEGRATIONS.md#without-a-framework) | Loads DMN files into named engines. |

Every module is on Maven Central under the same version. Every release is signed and has an SBOM, SLSA
Build Level 2 provenance (`gh attestation verify`) and third-party notices on its
[GitHub release](https://github.com/brunopacheco1/praecepta/releases).
[Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md)
rebuilds each release and checks that it matches Maven Central bit for bit.

# Getting started
The [user guide](docs/GUIDE.md) covers cells, typed columns, list inputs, hit policies, validation, DMN
files and decision graphs.

## A table in code
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
engine.validationReport();                 // static findings, such as unreachable rules
```

In a cell, blank means "any value" and `PT,ES` means "PT or ES". Cells are DMN FEEL unary tests:
`< 18`, `[18..65)`, `not("FR")`, `date("2025-01-31")`. See [cells](docs/GUIDE.md#cells).

## A table from a DMN file
```java
DecisionTable table;
try (var dmn = Files.newInputStream(Path.of("discount.dmn"))) {
    table = DmnReader.read(dmn);
}
var engine = PraeceptaEngine.<Customer, String>builder()
        .table(table)                              // hit policy, columns, value lists and rules
        .input("country", Customer::country)
        .numberInput("age", Customer::age)
        .output(output -> output.values().get("tier"))
        .build();
```

## A decision requirement graph
```java
DecisionGraph graph;
try (var dmn = Files.newInputStream(Path.of("credit.dmn"))) {
    graph = DecisionGraph.read(dmn);
}
graph.evaluate("Monthly Limit", Map.of("Applicant Age", 30, "Income", 1200));   // 200
graph.validationReport();                      // cycles, missing requirements, type mismatches...
```

## Typed classes from your build
The Maven and Gradle plugins compile a table into records and a class
([setup](docs/INTEGRATIONS.md#build-plugins)):

```java
Optional<DiscountOutput> discount = Discount.evaluate(new DiscountInput(DiscountInput.Country.PT, new BigDecimal(30)));
```

## Beans in your framework
Put DMN files under `src/main/resources/rules/` and add the integration for
[Spring Boot, Micronaut or Quarkus](docs/INTEGRATIONS.md#frameworks):

```java
@Service
class Pricing {
    private final Decisions decisions;

    Pricing(Decisions decisions) {
        this.decisions = decisions;
    }

    List<Map<String, String>> discounts(String country, int age) {
        return decisions.evaluate("Discount", Map.of("country", country, "age", Integer.toString(age)));
    }
}
```

# What Praecepta is not
- **Not a process engine, a modeller or a server.** It is a library your application calls.
- **No external functions** (Java and PMML). Praecepta runs no code from outside the model it evaluates.
  This is a deliberate exception to DMN 1.5, for security
  ([known deviations](docs/DMN-CONFORMANCE.md#known-deviations)). `DmnReader.readModel` rejects such a
  model with a message that names the function.

If you need one of these, use an engine that has it, such as Camunda or Drools.

# DMN conformance
`praecepta-tck` runs the whole [DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck)
(TCK) in every build: every model of compliance levels 2 and 3.

| | Result nodes | Share |
|---|---:|---:|
| Pass | 3,482 | 99.2 % |
| Fail | 11 | 0.3 % |
| Not supported (external functions) | 18 | 0.5 % |
| **Total** | **3,511** | |

- The 11 failures are [known](praecepta-tck/README.md#known-failures): nine loan payments that the kit
  states as a `double` computes them, where FEEL has 34 decimal digits, and two that follow from one
  [known deviation](docs/DMN-CONFORMANCE.md#known-deviations).
- [praecepta-tck/RESULTS.md](praecepta-tck/RESULTS.md) has the results per model.
- The build fails when a check that passed stops passing.
- Praecepta judges semantics by DMN 1.5 and the TCK alone. Where both leave a case open,
  [docs/DMN-CONFORMANCE.md](docs/DMN-CONFORMANCE.md) records what Praecepta does.

# Benchmarks
`praecepta-benchmarks` compares Praecepta in-process with Camunda 8's DMN engine and Drools DMN, using
[JMH](https://github.com/openjdk/jmh). Every engine reads the same DMN document, and each run first
checks that all engines give the same answers.

| On an Apple M2 Pro | Praecepta | Camunda 8 | Drools |
|---|---:|---:|---:|
| One decision, a real table of 119 rules | 0.19 µs | 19.3 µs | 43.5 µs |
| One decision, 1,000 mixed rules | 0.64 µs | 263 µs | 256 µs |
| Heap retained, 10,000 mixed rules | 5.0 MB | 133 MB | 108 MB |
| First decision in a fresh JVM | 76 ms | 469 ms | 379 ms |

These numbers come from the [run of 2026-11-07](praecepta-benchmarks/results/2026-11-07-apple-m2-pro-full/summary.md),
on Praecepta 0.2, and cover decision tables with literal cells. A quick run of the current code on another
machine gives the same ratios. For one decision of a decision requirement graph (`GraphBenchmark`), that quick
run puts Praecepta about ten times ahead of both engines.

[praecepta-benchmarks/README.md](praecepta-benchmarks/README.md) explains the tables, the benchmarks and
how to run them. Every published run is kept whole under
[praecepta-benchmarks/results](praecepta-benchmarks/results), with its logs and raw results.

# Building and testing
Building Praecepta needs Java 21 or later and Maven 3.9 or later:

```
mvn clean verify
```

This runs the unit tests, the property-based tests, which check the engine against a naive reference
implementation on random tables, and the DMN TCK. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

# Documentation
- [User guide](docs/GUIDE.md): tables, cells, DMN files and decision graphs.
- [Integrations](docs/INTEGRATIONS.md): the build plugins, the framework integrations and native images.
- [Architecture](docs/ARCHITECTURE.md): how Praecepta is built, in C4 diagrams.
- [DMN conformance](docs/DMN-CONFORMANCE.md): the cases DMN 1.5 and its TCK leave open, and the known
  deviations.
- [Roadmap](docs/ROADMAP.md) and [changelog](CHANGELOG.md).
- [Security assurance case](docs/SECURITY-ASSURANCE.md) and [security policy](SECURITY.md).
- [Governance](GOVERNANCE.md), [contributing](CONTRIBUTING.md) and
  [code of conduct](CODE_OF_CONDUCT.md).

# License
Praecepta is licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE). Every file
carries an SPDX header or is covered by [REUSE.toml](REUSE.toml), and the repository is
[REUSE](https://reuse.software)-compliant. Each published module ships a CycloneDX SBOM.
