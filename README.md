![Workflow](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml/badge.svg) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=coverage)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta)

# Praecepta
Praecepta evaluates DMN-style decision tables in Java, with strict DMN 1.3 hit-policy semantics and
lookups that cost a fraction of a microsecond.

# Features
- All DMN hit policies: UNIQUE, ANY, PRIORITY, FIRST, OUTPUT_ORDER, RULE_ORDER and COLLECT
  (with SUM / MIN / MAX aggregation)
- Static validation when the table is built: overlapping rules (UNIQUE), conflicting outputs (ANY)
  and rules that can never be selected (FIRST, PRIORITY)
- Immutable, thread-safe engines; no runtime dependencies
- Rules from code, Excel or CSV

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

Notes:
- `PRIORITY` and `OUTPUT_ORDER` need DMN output value lists:
  `.outputPriority("grade", List.of("A", "B"))`, highest priority first.
- An input matching several rules under `UNIQUE` (or rules with different outputs under `ANY`)
  throws `HitPolicyViolationException`.
- `.failOn(Severity.ERROR)` makes `build()` reject tables whose validation report has errors.
- `.inputs(order -> new PraeceptaInput(...))` binds all columns from a map instead of per column.

## Praecepta Loader
Loads rules from the first sheet of an Excel file (or a CSV file) on the classpath. The header row
names the columns; each following row is a rule, identified by its row number.

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

# Building and Testing
Praecepta requires **Java 25** (the current LTS) and Maven 3.9+. From the repository root:

```
mvn clean verify
```

This runs the unit tests and the property-based tests in `praecepta-engine`, which check the
engine against a naive reference implementation of DMN decision-table semantics on random tables.

# Benchmarks
`praecepta-benchmarks` compares Praecepta with Camunda DMN and Drools DMN in-process using
[JMH](https://github.com/openjdk/jmh). Every engine evaluates the same rules (the bundled
119-rule table, or generated tables of 100 and 1,000 rules), and each run first checks that all
engines return the same answers.

```
mvn -pl praecepta-benchmarks -am package -DskipTests
java -jar praecepta-benchmarks/target/benchmarks.jar                      # latency, all engines and tables
java -jar praecepta-benchmarks/target/benchmarks.jar -p table=real        # one table
java -cp praecepta-benchmarks/target/benchmarks.jar \
  io.github.brunopacheco1.praecepta.benchmarks.FootprintReport praecepta real   # retained heap
```

The `Benchmarks` GitHub workflow runs the same on demand and uploads the results.

# Dependencies
Praecepta Engine has no runtime dependencies.

Praecepta Loader depends on Apache POI.

# Roadmap
See [docs/MODERNISATION.md](docs/MODERNISATION.md).

# Contributing
We welcome contributions to Praecepta! If you would like to contribute, please follow these steps:

1. Fork the repository
2. Create a new branch for your changes
3. Make your changes
4. Run the tests to ensure they pass
5. Submit a pull request

# License
Praecepta is licensed under the MIT License. See LICENSE for more information.