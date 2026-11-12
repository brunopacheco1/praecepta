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
**DMN decision tables for Java.** *Praecepta* is Latin for "rules" (classical pronunciation:
*pry-KEP-ta*). [Website](https://bruno.pacheco.lu/praecepta/) ·
[API documentation](https://bruno.pacheco.lu/praecepta/api/engine/)

Praecepta evaluates DMN decision tables in Java, with strict DMN hit-policy semantics and lookups
that cost a fraction of a microsecond. Write tables in Excel, CSV, DMN or code; validate them when
you build; evaluate them with a dependency-free engine, on its own or as beans in Spring Boot,
Quarkus or Micronaut.

> **Status: 0.x.** The API may still change between minor versions until 1.0. It covers the
> decision-table part of DMN, not full FEEL or decision requirement graphs; see
> [what Praecepta is not](#what-praecepta-is-not).

# Features
- All DMN hit policies: UNIQUE, ANY, PRIORITY, FIRST, OUTPUT_ORDER, RULE_ORDER and COLLECT
  (with SUM / MIN / MAX / COUNT aggregation)
- DMN FEEL cells on STRING, NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION and
  YEAR_MONTH_DURATION columns: lists, `not(...)`, comparisons, `=` and `!=`, intervals, `null`; unquoted `PT,ES`
  cells from earlier versions keep working
- Times and date-times with or without a time zone; two with one compare by instant, and one with
  a zone compares with one without by local time (DMN 1.5, Table 54; see
  [DMN conformance](docs/DMN-CONFORMANCE.md) for the cases it leaves open)
- List inputs, such as a customer's nationalities: a cell holds when some element passes it
- DMN output entries that name an input or compute with `+`, `-`, `*`, `/` and `**`
  (`(price - discount) * 1.2`, `Symptom + " is listed"`), evaluated only for the rules that match
- Optional DMN input value lists: values outside them are rejected
- Static validation when the table is built: overlapping rules (UNIQUE), conflicting outputs (ANY),
  rules that can never be selected (FIRST, PRIORITY), gaps (declared inputs no rule matches), and
  default outputs no input can reach (a complete table)
- Bitset matching: memory grows linearly with the table (10,000 rules take 1.5 to 5 MB of heap,
  against about 90 to 130 MB for Camunda and Drools) and most engines build in milliseconds; the
  mask of a value only a few rules accept, such as a product code, is stored compactly, so even a
  column with a value per rule stays small
- Enum inputs: lookups by ordinal instead of hashing
- Immutable, thread-safe engines; no runtime dependencies
- Rules from code, Excel, CSV or DMN files (DMN 1.1 to 1.5, as written by Camunda Modeler and
  other tools), and DMN export
- [Maven](#praecepta-maven-plugin) and [Gradle](#praecepta-gradle-plugin) plugins that validate
  tables at build time and generate typed input and output records, so applications ship only the
  engine
- [Spring Boot](#praecepta-spring-boot-starter), [Micronaut](#praecepta-micronaut) and
  [Quarkus](#praecepta-quarkus-extension) integrations that inject one engine per DMN decision and
  refuse to start (Quarkus: to build) when a table is invalid, with the validation reports at runtime
- GraalVM native images with no configuration for Praecepta itself (see [Native images](#native-images))

# Installation
Java 21 or later (tested on 21, 25 and 27). Pick the modules you need:

```xml
<!-- the engine: no dependencies, a JPMS module (io.github.brunopacheco1.praecepta.engine) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-engine</artifactId>
  <version>0.5.0</version>
</dependency>

<!-- optional: load rules from Excel or CSV at runtime (brings Apache POI) -->
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-loader</artifactId>
  <version>0.5.0</version>
</dependency>
```

Or let your build or framework do it:

| You use | Add | What it does |
|---|---|---|
| Maven | [`praecepta-maven-plugin`](#praecepta-maven-plugin) | Validates tables at build time and generates typed classes; only `praecepta-engine` is needed at runtime. |
| Gradle | [plugin `io.github.brunopacheco1.praecepta`](#praecepta-gradle-plugin) | The same, for Gradle 8.5 and later. |
| Spring Boot 4 | [`praecepta-spring-boot-starter`](#praecepta-spring-boot-starter) | Loads the DMN files under `rules/` at startup and injects one engine per decision. |
| Micronaut 4 | [`praecepta-micronaut`](#praecepta-micronaut) | The same, for Micronaut. |
| Quarkus 3.40+ | [`praecepta-quarkus`](#praecepta-quarkus-extension) | Validates the tables while Quarkus builds the application, and injects one engine per decision. |
| Another framework, or none | [`praecepta-integration`](#praecepta-integration) | Loads DMN files into named engines, as the framework integrations do. |
| GraalVM | nothing | [Native images](#native-images) need no configuration for Praecepta. |

Every module is published to Maven Central under the same version. How the integrations fit together,
and what they share, is in [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).

Every release is signed and has an SBOM, SLSA Build Level 2 provenance (`gh attestation verify`) and
third-party notices on its [GitHub release](https://github.com/brunopacheco1/praecepta/releases), and
[Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md)
rebuilds it independently to check it is bit-for-bit identical to Maven Central.

# What Praecepta is not
Praecepta does one thing: single DMN decision tables, fast. Use Camunda or Drools when you need:
- **Full FEEL**: functions, contexts, `if`, and tests that compute (e.g. `income > 2 * rent`).
  Praecepta's input entries are FEEL *simple unary tests*: in DMN they may compare with another
  input (`> limit`) or test a list's elements, but not compute. Output entries may compute with `+`,
  `-`, `*`, `/` and `**` on strings and numbers, but call no functions. Some FEEL that fits a single
  table is not read yet either: arithmetic on dates, times and durations
  ([#85](https://github.com/brunopacheco1/praecepta/issues/85)); see the [roadmap](docs/ROADMAP.md).
- **Decision requirement graphs**: decisions feeding other decisions, business knowledge models,
  boxed expressions ([#14](https://github.com/brunopacheco1/praecepta/issues/14)).
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
| `18`, `2.5`, `.5`, `1.2e3` | that number (`1.0` equals `1`) | NUMBER |
| `date("2025-01-31")` or `2025-01-31` | that date | DATE |
| `true`, `false` | that boolean | BOOLEAN |
| `time("08:30:00")` or `08:30:00` | that time of day | TIME |
| `time("08:30:00+01:00")` or `08:30:00Z` | that instant of the day, at an offset | TIME |
| `time("08:30:00@Europe/Paris")` or `08:30:00@Europe/Paris` | that time of day in that zone, which compares only with times in the same zone | TIME |
| `date and time("2025-01-31T08:30:00")` or `2025-01-31T08:30:00` | that date and time | DATE_TIME |
| `date and time("2025-01-31T08:30:00Z")`, `date and time("2025-01-31T08:30:00@Europe/Paris")` | that instant | DATE_TIME |
| `duration("P1DT2H")` or `P1DT2H` | that length of time (`PT24H` equals `P1D`) | DAY_TIME_DURATION |
| `duration("P1Y2M")` or `P1Y2M` | that number of months (`P12M` equals `P1Y`) | YEAR_MONTH_DURATION |
| `@"2025-01-31"`, `@"08:30:00Z"`, `@"P1D"` | at-literals: a date, time, date and time or duration, as the string's form says | its type |
| `date(2025, 1, 31)`, `time(8, 30, 0)`, `time(8, 30, 0, duration("PT1H"))`, `date and time(date("2025-01-31"), time("08:30:00"))`, `date and time("2025-01-31")` | the other forms of the date and time functions (DMN 1.5, Table 72); a date and time without a time is at midnight | its type |
| `< 18`, `<= 18`, `> 18`, `>= 18`, `< "N"` | comparisons | all but BOOLEAN |
| `[18..65)`, `(0..1]`, `]a..b[`, `["A".."M"]` | intervals: `[`/`]` closed, `(`/`)` or outward brackets open | all but BOOLEAN |
| `= 18`, `= "PT"` | the same as `18`, `"PT"` | all |
| `!= "PT"`, `!= 18` | any value but that one, a missing one included (`!=` is `not(=)`, DMN 1.5, 10.3.2.15) | all |
| `null`, `= null` | only a missing value | all |
| `!= null` | any value that is not missing | all |
| `not(...)` | anything the list inside does not match | all |

FEEL's comments are ignored: `< 18 // minors`, `/* adults */ >= 18` (DMN 1.5, 10.3.1.2), in cells and in
output entries; in Praecepta's syntax a string column keeps them as text, as an unquoted URL holds `//`.

Quoted strings take FEEL's escape sequences (DMN 1.5, grammar rule 64): `\"`, `\\`, `\'`, `\n` (a line
feed), `\r`, `\t` (a tab), and a character by its code point, `\u00e9` or `\U01F40E`. A backslash before
any other character is an error; unquoted words keep their backslashes as they are. So a quoted Windows
path is written `"C:\\temp"`: written `"C:\temp"`, it is read without an error as `C:`, a tab and `emp`
(and `"D:\new"` holds a line feed). Before upgrading from 0.4, search your tables' quoted strings for
backslashes. In strict FEEL (DMN files), a quoted string may not hold a line break itself (grammar rule
33): write `\n` or `\r`. `DmnWriter` writes line breaks and tabs back as `\n`, `\r` and `\t`.

Missing values follow FEEL: they fail comparisons and intervals, equal only `null`, and so satisfy
`not("FR")` and `!= "FR"`. Unlike `not(...)`, which goes around a whole cell, `!=` is one test among
the others: `!= 0, > 10` holds for every value but 0, and for a missing one. Blank text counts as missing. Strings compare as FEEL orders them, character by
character by Unicode code point and case-sensitively (`"Zed" < "apple"`), so a character beyond U+FFFF,
such as an emoji, comes after every other (`"🐎" > "～"`), where Java's `String.compareTo` would put it
before U+E000 to U+FFFF. Where DMN 1.5 and its TCK leave a
case open (`-` against a missing value, `=` between a time with a zone and one without, two times at
different offsets, a time with a zone id against a time in another zone, at an offset or without a zone),
Praecepta's choice is recorded in [DMN conformance](docs/DMN-CONFORMANCE.md).

Times may have an offset (`Z`, `+01:00`) and date-times an offset or a zone id (`@Europe/Paris`).
Two values with a time zone compare by the instant they denote, as XML Schema compares them
(`time("09:00:00+01:00")` equals `time("08:00:00Z")`; for times DMN 1.5 leaves this open). When only one
of the two has a zone, the other takes its offset (DMN 1.5, Table 54, which says so for `<`; Praecepta
applies it to `=` too), so they compare by local time: `time("08:00:00")` matches `08:00`, `08:00Z` and
`08:00+05:00`, `< time("12:00:00+01:00")` matches `11:59`, and an interval may have an end of each
kind.

A time may also have a zone id (`time("08:00:00@Europe/Paris")`, DMN 1.5, 10.3.4.1), which has no offset
without a date. Two such times in the same zone compare by local time, which is all the DMN TCK asks
(`0068-feel-equality`). How one compares with a time in another zone, at an offset or without a zone, DMN 1.5
does not say, and the TCK leaves those cases out "pending RTF equality comparisons with zones clarification";
Praecepta takes such a comparison as one FEEL does not define, which is null: neither the test nor its
`not(...)` matches (`time("08:00:00@Europe/Paris")` and `not(time("08:00:00@Europe/Paris"))` both miss
`08:00@Asia/Dhaka`, `08:00Z` and `08:00`), an interval's ends must be in one zone, and a COLLECT MIN or MAX
over such times in two zones fails. A column whose cells name no zone id still takes such a time read from
text: only the cells that accept any value (`-`, `!= null`) match it.

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
        .zonedTimeInput("callAt", Applicant::callAt)   // ZonedTime: a time with a zone id
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
declares its values or uses comparisons or intervals (any other string then falls between them).
With map-based inputs, declare non-string columns with `.columnType("age", ColumnType.NUMBER)`;
their text is parsed. A time or date-time column then takes values with and without a time zone
(`08:30`, `08:30Z`, `2025-01-31T08:30@Europe/Paris`), and its gaps are looked for among both, while
a typed getter gives one kind only. To cells without a zone the zone makes no difference (`08:30Z`
and `08:30+05:00` both count as `08:30`), so a table whose cells all lack one reports the same gaps
either way, once; to cells with a zone, a value with a zone counts by its instant and one without
by its local time, so gaps are reported for each kind (`cutOff in {[time("12:00:00")..+inf),
[time("12:00:00Z")..+inf)}`). Declared input values are compared the same way. To keep a column to
one kind, bind the inputs per column, with `timeInput` or `offsetTimeInput`, `dateTimeInput` or
`zonedDateTimeInput`.

Notes:
- `PRIORITY` and `OUTPUT_ORDER` need DMN output value lists:
  `.outputPriority("grade", List.of("A", "B"))`, highest priority first.
- An input matching several rules under `UNIQUE` (or rules with different outputs under `ANY`)
  throws `HitPolicyViolationException`.
- `.failOn(Severity.ERROR)` makes `build()` reject tables whose validation report has errors.
- `build()` logs a table's validation issues as one warning through `System.Logger` (by default
  `java.util.logging`, logger `io.github.brunopacheco1.praecepta.engine.PraeceptaEngine`).
  `.logValidationReport(false)` turns that off when you report `validationReport()` yourself, as the
  framework integrations and the build plugins do.
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
empty one passes none. Elements must be values of the element type (as its single-value getter
returns them, or text), and declared input values apply to each element. An element may also be
missing (null or blank), with FEEL's answers for it (DMN 1.5, Tables 49, 50 and 75): a literal is
false, a comparison or an interval unknown, and a cell's tests are joined over the elements with FEEL's
three-valued `or`. So a cell holds when some element passes, whatever the others, and its `not(...)`
only when every element fails every test:

| Cell (DMN form) | List | Matches |
|---|---|---|
| `list contains(?, "PT")` | `["PT", null]` | yes |
| `not(list contains(?, "PT"))` | `["BR", null]` | yes (`null = "PT"` is false) |
| `some x in ? satisfies x >= 18` | `[20, null]` | yes (`true or null`) |
| `not(some x in ? satisfies x >= 18)` | `[10, null]` | no (`false or null` is null) |
| `list contains(?, null)` | `["PT", null]` | yes |

`list contains(?, null)` (DMN files only: in Praecepta's syntax `null` asks whether the list itself is
missing, as `? = null` does) holds when the list holds a missing element.
The elements of a list of times or date-times may have a time zone or not; each is compared as a
single value is (by instant when the element and the cell both have a zone, else by local time).
With map-based inputs, declare the column with `.listColumnType("nationalities", ColumnType.STRING)`
and give a FEEL list as text: `["PT", "BR"]` (or `PT, BR`), `[]` for an empty list, `["PT", null]` for
a list holding a missing element.

The static analysis treats a list as the set of its elements' kinds, so it sees what a list can match
that a single value cannot: rules `"PT"` and `"BR"` overlap (on `["PT", "BR"]`), `[20..30]` after
`>= 18` can never be selected under FIRST, and two rules `"PT"` and `"BR"` with declared values
`"PT","BR"` leave a gap, `nationalities = []`. Overlaps and unreachable rules count every list,
missing ones and ones holding missing elements included; gaps are looked for among lists of declared
values and described by the elements such lists hold, as in
`nationalities has no element in "PT", nationalities has an element in "ES"` (lists without `"PT"`
that hold `"ES"`), with an example list.

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

Supported:
- **Inputs:** input expressions that name a variable, including FEEL names with spaces (`age`,
  `customer.age`, `Credit Score`), of type `string`, `number` (`integer`, `long`, `double`), `date`,
  `boolean`, `time`, `dateTime`, `dayTimeDuration` or `yearMonthDuration`, or an item definition of one
  of them (such as a `string` with allowed values, which become the input value list); times and
  date-times with or without a time zone.
- **Input entries** in the cell syntax above, as strict FEEL: strings quoted (an unquoted word is a
  reference to another variable), with FEEL's escape sequences, and dates, times and durations written
  with `date(...)`, `time(...)`, `date and time(...)` and `duration(...)`, in any of their forms, or as
  at-literals (`@"2025-01-31"`).
- **Entries that compare with another input** (`> limit`, `[floor..limit]`, `not(home)`), which then
  becomes an input of the engine, bound like a column (with map-based inputs it is read with the type of
  the column that names it).
- **List columns**, typed by a collection (`isCollection="true"`, with allowed values on the item type),
  whose entries test the elements with FEEL's list functions (`list contains(?, "PT")`,
  `list contains(?, null)`, `some x in ? satisfies x >= 18`, `? = null`, joined with `or`, and `not(...)`);
  and entries that name a list input (`Flu Symptoms`, typed by its `inputData`), which hold when the value
  is in the list (`list contains(Flu Symptoms, Symptom)`: a missing value is in a list that holds a missing
  element).
- **Output entries** that are literals, name one of the table's inputs (`a` returns the input `a`), or
  compute their value from literals and inputs with `+`, `-`, `*`, `/`, `**` and parentheses
  (`Symptom + " is listed"`, `(price - discount) * 1.2`, `amount * (1 + rate) ** years`): `+` joins
  strings or adds numbers, results have FEEL's 34 digits, and a missing operand gives a missing output.
  The inputs they name are columns, or inputs the cells compare with, that hold single values.
- **Default output values** (`defaultOutputEntry`, literals of the output's type, and one of its output
  values when it lists them), which are the result when no rule matches, whatever the hit policy or
  aggregation (`[{Status=Declined, Rate=null}]` rather than `[]`; an output without a default is null in
  it). A default of `null` is a default too: a table whose outputs all default to `null` gives
  `[{Status=null, Rate=null}]`, and one whose outputs declare no default gives `[]` (DMN 1.5, 10.3.2.10).
  `Builder.defaultOutput(...)` gives a table built in code the same. "A complete decision table SHALL NOT
  specify a default output value" (10.3.2.10): when every input matches a rule, a missing value in any
  column included, the validation report has the ERROR `UNREACHABLE_DEFAULT_OUTPUT`, so the build plugins
  and the framework integrations reject the table by default.
- **A single output typed by its decision**: DMN 1.5 says the output clause of a single-output table has
  no `typeRef` of its own (Table 34), so its type is the decision table's `typeRef`, or else the decision
  variable's (the item type of a list for a multi-hit table; nothing for COUNT, whose result is a number).
- **Every hit policy**, with SUM, MIN, MAX and COUNT aggregation. The engine leaves what they mean to the
  output strategy; `AggregationOperator.check(outputs)` says where DMN defines them: on a single output,
  SUM over numbers, MIN and MAX over any ordered type, COUNT over any. They apply only when a rule
  matches: when none does, the result is the default output, or none (`[]`), whatever the aggregation,
  so COUNT gives no output rather than 0 (DMN 1.5, 10.3.2.10, step 2). When a matching rule's output is
  missing (a `null` entry, or an expression with a missing operand), SUM, MIN and MAX are missing too, as
  FEEL's `sum`, `min` and `max` are null for a list that holds null (10.3.4.4): the engine gives that rule's
  output (`[{fee=null}]`), and COUNT counts the rule.

Anything else is rejected with a message naming the decision, rule and column: function calls such as
`upper case(a)`, `if`, output entries that compute with dates,
times or durations ([#85](https://github.com/brunopacheco1/praecepta/issues/85)), and inputs computed by
other decisions ([#14](https://github.com/brunopacheco1/praecepta/issues/14)). `DmnReader.readAll(dmn)`
reads every decision table of a file. `DmnWriter.write(table)` produces DMN 1.3 that Camunda and Drools
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
time zone (an input compared with such a column in a cell, as in `> limit`, takes a zone too), and a
time is the engine's `ZonedTime` when every value written for it has a zone id; a column that mixes
kinds fails the build. An input declared with `<list>true</list>` (or typed
by a collection in a DMN file) becomes a `java.util.List` of its type. At runtime the generated code
needs only `praecepta-engine`; Apache POI stays a build-time dependency.

```xml
<plugin>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-maven-plugin</artifactId>
  <version>0.5.0</version>
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

Single-hit tables (and aggregations) return an `Optional`; multi-hit tables return a `List`. When no
rule matches, these are empty (a COUNT too, rather than 0), unless the DMN table declares default output
values: then they hold the default output. A SUM, MIN or MAX over a matching rule whose output is missing
is an output whose value is null (`TotalOutput[total=null]`), as in FEEL.
Generated code belongs to your project, so it carries no Praecepta licence header. Set
`<fileHeader>` to prepend your own, such as your project's SPDX lines.
`Discount.validationReport()` gives the build-time findings, and `Discount.engine()` the engine.

## Praecepta Gradle Plugin
Does for Gradle what the Maven plugin does, with the same settings and the same generated files.
Runs on Gradle 8.5 and later (CI tests 8.5, the last 8.x and the current 9.x) on Java 21+. It is
published to Maven Central with its plugin marker, so Gradle finds it there. From 0.6.0 it is on the Gradle
Plugin Portal too ([#93](https://github.com/brunopacheco1/praecepta/issues/93)), where Gradle looks by default, and `pluginManagement` needs no change;
for 0.5.0 add Maven Central:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    java
    id("io.github.brunopacheco1.praecepta") version "0.5.0"
}

dependencies {
    implementation("io.github.brunopacheco1:praecepta-engine:0.5.0")
}

praecepta {
    packageName = "com.acme.pricing"
    tables {
        register("discount") {
            source = file("src/main/rules/discount.xlsx")
            hitPolicy = "FIRST"
            inputs {
                column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
                column("age") { type = "NUMBER" }
            }
            outputs {
                column("discount") { type = "NUMBER" }
            }
        }
        // DMN files carry their own hit policy, columns and value lists
        register("shipping") { source = file("src/main/rules/shipping.dmn") }
    }
}
```

The `generatePraecepta` task writes the classes to `build/generated/sources/praecepta/java/main`
and the DMN resources to `build/generated/resources/praecepta/main`, both part of the `main` source
set, so `compileJava` and `processResources` run it first. It is cacheable (its inputs are the
table files, by name and content, and the settings) and works with the configuration cache. Each run
empties both directories first, so a table you remove leaves no class behind.

The settings are the Maven plugin's, as Gradle properties: `packageName`, `failOn` (`ERROR` by
default, `WARNING` or `NONE`), `fileHeader` and `skip` (or `-Ppraecepta.skip=true`); per table
`source`, `decision`, `hitPolicy` and `aggregation`; per column `type`, `allowedValues`, `priority`
and `list`. One differs: `name` is the name a table is registered under, as in every Gradle
container, so the generated class is named by `className` (the Maven plugin's `<name>`), which
defaults to the DMN decision's name or the file name. A column's name is the argument of `column`.
The Groovy DSL takes the same settings (`register('discount') { hitPolicy = 'FIRST' ... }`).

## Praecepta Spring Boot Starter
For Spring Boot 4 applications on Java 21+. At startup it loads every DMN file under `rules/` on the
class path (such as `src/main/resources/rules/discount.dmn`), validates the decision tables, and
registers them as beans:

```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-spring-boot-starter</artifactId>
  <version>0.5.0</version>
</dependency>
```

Each decision table becomes an engine bean, injected by the decision's name (its DMN `name`, or its
`id` when it has none). Inputs and outputs are maps of column names to values as text, read with the
column types the DMN declares (`"42"`, `"2025-01-31"`, `["PT", "BR"]` for a list column; a missing
value is a missing input):

```java
@Service
class Pricing {
    private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    Pricing(@Qualifier("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount) {
        this.discount = discount;
    }

    List<Map<String, String>> discounts(String country, int age) {
        return discount.evaluate(Map.of("country", country, "age", Integer.toString(age)));
    }
}
```

Each output map holds one matching rule's outputs, in the hit policy's order; under a COLLECT
aggregation, the one map holds the aggregate. The `Decisions` bean (named `praeceptaDecisions`) holds them
all: `decisions.evaluate("Discount", Map.of("country", "PT"))`, `decisions.names()`. The engine beans are
named `praecepta.<decision name>`. `@Named("Discount")` (with `jakarta.inject-api` on the class path)
injects them too, as in the Micronaut and Quarkus integrations.

| Property | Default | Meaning |
|---|---|---|
| `praecepta.locations` | `classpath*:rules/**/*.dmn` | The DMN files to load: patterns with `classpath:` or `classpath*:` (both search every class path root, from a directory: `classpath*:rules/...`), or `file:`; `*` matches within a path segment, `**` across segments. A comma-separated list or a YAML list. |
| `praecepta.fail-on` | `ERROR` | The validation severity that stops startup: `ERROR`, `WARNING` or `NONE`. |
| `praecepta.enabled` | `true` | `false` turns the starter off. |

The application does not start if a table cannot be read or built, if two decisions have the same
name, if a location you set finds no file, or if a table has validation issues at `praecepta.fail-on`
or above; the failure lists every problem at once. Other issues are logged as warnings, such as
`Discount: WARNING rule 3 can never be selected`.

With Actuator, the endpoint `praecepta` reports each decision's columns, hit policy and validation
issues (`/actuator/praecepta`, and `/actuator/praecepta/{name}` for one). Like every Actuator endpoint
it is exposed only when you say so, for example with
`management.endpoints.web.exposure.include=health,praecepta`. Nothing is evaluated through it.

Spring AOT (`spring-boot:process-aot`, for a native image or the JVM's AOT mode) processes the starter like
any other configuration: the tables are validated while the application is built, its engine beans are
those of the decisions found then, and the tables are read again when it starts (a decision missing then
fails that engine bean). As AOT fixes the beans and their conditions at build time, set
`praecepta.locations` and `praecepta.enabled` for the build as for the application.

To keep tables in Excel or CSV, convert them at build time with the [Maven plugin](#praecepta-maven-plugin)
or the [Gradle plugin](#praecepta-gradle-plugin): the DMN resources they write are found by a pattern that
covers their package, such as `classpath*:com/acme/pricing/*.dmn`. Their generated classes are static and
need no bean.

## Praecepta Micronaut
For Micronaut 4 applications on Java 21+. At startup, the module:
1. loads every DMN file under `src/main/resources/rules`;
2. validates each decision table, refusing to start on errors;
3. provides each table as an engine bean named after its decision.

```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-micronaut</artifactId>
  <version>0.5.0</version>
</dependency>
```

```java
@Singleton
class Pricing {

    private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    Pricing(@Named("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount) {
        this.discount = discount;
    }

    List<Map<String, String>> discounts(String country, int age) {
        return discount.evaluate(Map.of("country", country, "age", Integer.toString(age)));
    }
}
```

- **Names.** Every decision table of every file is one engine, named after the DMN decision's `name`
  (its `id` when it has none). The `Decisions` bean holds them all:
  `decisions.evaluate("Discount", Map.of("country", "PT"))`.
- **Inputs and outputs are maps of text**, keyed by column name. Values are written as a table's cells
  write literals: `42`, `2025-01-31`, `08:30Z`, or `["PT", "BR"]` for a list column. A missing or blank
  value is a missing input. Each output map holds one matching rule's outputs, in the hit policy's order;
  under a COLLECT aggregation, the one map holds the aggregate.
- **Excel and CSV tables** reach the application through the [Maven plugin](#praecepta-maven-plugin) or
  the [Gradle plugin](#praecepta-gradle-plugin), which write them as DMN resources: point
  `praecepta.locations` at their package as well
  (`classpath*:rules/**/*.dmn, classpath*:com/acme/pricing/*.dmn`). The classes they generate stay
  static (`Discount.evaluate(...)`) and are not beans.

Settings, in `application.yml` (the same keys as the other framework integrations):

| Key | Default | Meaning |
|---|---|---|
| `praecepta.enabled` | `true` | `false` turns the module off: no beans, no endpoint. |
| `praecepta.locations` | `classpath*:rules/**/*.dmn` | The DMN files to load, as a comma-separated list or a YAML list. `classpath:` and `classpath*:` search every class path root from a directory (`classpath*:rules/...`), `file:` the file system; `*` matches within a directory, `**` across directories. When set, a location that finds no file stops startup. |
| `praecepta.fail-on` | `ERROR` | The validation severity that stops startup: `ERROR`, `WARNING` or `NONE`. Unreadable or invalid tables stop it whatever this says. |

A failed startup lists every problem at once (a `DecisionsException`). Issues below `fail-on` are logged
as warnings, such as `Discount: WARNING rule 3 can never be selected`.

With `micronaut-management`, the `praecepta` endpoint reports every decision: its hit policy, columns,
rule count and validation issues (`GET /praecepta`, or `GET /praecepta/Discount` for one). Like other
Micronaut endpoints it is sensitive by default, since the reports show the rules' conditions. Open it with
`endpoints.praecepta.sensitive: false`, or secure it with Micronaut Security.

Micronaut builds the module's beans and binds its settings at compile time, so it needs no reflection
and no annotation processing in your build. In a native image, include the DMN files as resources.

## Praecepta Quarkus Extension
Runs on Quarkus 3.40 and later with Java 21+. Add the extension to a Quarkus application:

```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-quarkus</artifactId>
  <version>0.5.0</version>
</dependency>
```

While Quarkus builds the application (`quarkus:build`, dev mode and tests), the extension:
1. finds the DMN files under `rules/` on the class path (`src/main/resources/rules/` and any
   dependency), and compiles the Excel and CSV tables declared in `application.properties`;
2. validates them, failing the build on errors (`quarkus.praecepta.fail-on`: `ERROR` by default,
   `WARNING` or `NONE`) with every problem listed at once, and logging the rest;
3. registers the application's `Decisions` and one engine per decision, named after the DMN
   decision's `name` (or, for a spreadsheet, its file name without the extension).

```java
@ApplicationScoped
class Pricing {

    @Inject
    @Identifier("Discount") // or @Named("Discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Inject
    Decisions decisions;

    List<Map<String, String>> discounts(String country, int age) {
        return discount.evaluate(Map.of("country", country, "age", Integer.toString(age)));
        // the same: decisions.evaluate("Discount", Map.of("country", country, "age", ...))
    }
}
```

Inputs and outputs are the columns' values as text, keyed by column name: numbers as `42`, dates as
`2025-01-31`, a list column's value as `["PT", "BR"]`; a missing or blank value is a missing input.
Each output map holds one matching rule's outputs (under a COLLECT aggregation, the aggregate).
`decisions.get("Discount").report()` gives the validation report at runtime, and `decisions.describe()`
every decision's columns and issues as plain maps. The engines are immutable `@Singleton` beans; for
typed input and output records, use the [Maven](#praecepta-maven-plugin) or
[Gradle](#praecepta-gradle-plugin) plugin and declare the generated class as a bean yourself.

| Property | Default | Meaning |
|---|---|---|
| `quarkus.praecepta.locations` | `classpath*:rules/**/*.dmn` | The DMN files to load: patterns, comma-separated. `classpath:` and `classpath*:` search the application and its dependencies from a directory (`classpath*:rules/...`), `file:` the file system; `*` matches within a directory, `**` across them. A pattern set here that finds nothing fails the build. |
| `quarkus.praecepta.fail-on` | `ERROR` | The validation severity that fails the build: `ERROR`, `WARNING` or `NONE`. Tables that cannot be read fail it whatever this says. |
| `quarkus.praecepta.tables."<key>".*` | | An Excel or CSV table to compile, with the Maven plugin's settings in kebab-case (below). |

```properties
quarkus.praecepta.tables.discount.source=src/main/rules/discount.xlsx
quarkus.praecepta.tables.discount.hit-policy=FIRST
quarkus.praecepta.tables.discount.inputs[0].name=country
quarkus.praecepta.tables.discount.inputs[0].allowed-values="PT","ES","FR"
quarkus.praecepta.tables.discount.inputs[1].name=age
quarkus.praecepta.tables.discount.inputs[1].type=NUMBER
quarkus.praecepta.tables.discount.outputs[0].name=discount
quarkus.praecepta.tables.discount.outputs[0].type=NUMBER
```

A relative `source` is resolved against the project's directory; keep spreadsheets out of
`src/main/resources` so they are not packaged. Apache POI is used at build time only and never reaches
the application. In dev mode, changing a DMN file or a declared spreadsheet restarts the application
with the new table, as does adding a DMN file a class path location matches, and a table that fails
validation shows on the error page until it is fixed. A file added under a `file:` location needs a
manual restart (press `s`), since Quarkus watches files, not directories. Like any Quarkus extension,
it depends on `quarkus-arc`, and its deployment module on `quarkus-arc-deployment`, so Quarkus' tools
check that the application's Quarkus version matches the one the extension was built for. The
deployment module only runs inside the Quarkus build. It uses no reflection; the tables
are read while the application initialises, and in a native executable while the image is built, but
the native-image workflow does not build a Quarkus application yet (see [Native images](#native-images)).

## Praecepta Integration
What the Spring Boot, Micronaut and Quarkus integrations share, for another framework or none: it finds
DMN files, builds one engine per decision table, and evaluates them by name. It needs only the engine.

```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-integration</artifactId>
  <version>0.5.0</version>
</dependency>
```

```java
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.integration.Decisions;
import io.github.brunopacheco1.praecepta.integration.TableLocations;

var sources = TableLocations.find(List.of(TableLocations.DEFAULT), getClass().getClassLoader());
var decisions = Decisions.load(sources, Severity.ERROR);

List<Map<String, String>> discount = decisions.evaluate("Discount", Map.of("country", "PT", "age", "42"));
PraeceptaEngine<Map<String, String>, Map<String, String>> engine = decisions.engine("Discount");
```

- **Locations** are the integrations' patterns: `TableLocations.DEFAULT` is `classpath*:rules/**/*.dmn`,
  `classpath:` and `classpath*:` search every class path root from a directory, `file:` the file system;
  `*` matches within a directory, `**` across them. `TableSource.of(path)`, `of(url)` or
  `of(name, bytes)` load a file you already have.
- **Loading fails** with a `DecisionsException` that lists every problem at once: a file that cannot be
  read, two decisions with the same name, or validation issues at the given severity or above (`null`
  for none).
- **Inputs and outputs** are maps of text, as in the framework integrations. `decisions.get("Discount")`
  gives the table, its validation report and its engine; `decisions.names()` and `decisions.describe()`
  list them.

## Native images
Applications using Praecepta build as GraalVM native executables with no configuration for Praecepta
itself: the engine uses no reflection, and the jars carry the reachability metadata they need under
`META-INF/native-image/io.github.brunopacheco1/<artifactId>/`, which `native-image` reads on its own.
CI builds an application that uses all of the following as a native executable with GraalVM for JDK 21
and 25, and checks its answers:

| Works natively | Notes |
|---|---|
| The engine: building, evaluating, validation, `DmnReader`, `DmnWriter` | Its metadata keeps the XML parser's error messages, so a malformed DMN file fails as it does on the JVM. |
| Classes generated by the Maven and Gradle plugins | Nothing to do: they read their DMN through a constant name, which `native-image` registers itself. |
| `praecepta-integration` (`TableLocations`, `Decisions`) | Its metadata registers the default location, `rules/**/*.dmn`; register files elsewhere yourself. |
| `CsvLoader` | Register the CSV files it reads (below). |
| `ExcelLoader` | **Not supported.** Apache POI fails on the first workbook (charsets, XMLBeans resources and schema metadata), and GraalVM's shared metadata repository has no entry for it. Compile Excel tables at build time with the Maven or Gradle plugin instead: the executable then reads DMN. |

Files your own code reads by a name it computes, such as `new CsvLoader("tables/discount.csv", ...)`, a
`DmnReader.read(...)` of a resource, or DMN files outside `rules/`, must be registered as resources, for
example in `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/resource-config.json`:

```json
{ "resources": { "includes": [ { "pattern": "\\Qtables/discount.csv\\E" }, { "pattern": "tables/.*\\.dmn" } ] } }
```

With GraalVM's `native-maven-plugin`, the build is the usual one:

```xml
<plugin>
  <groupId>org.graalvm.buildtools</groupId>
  <artifactId>native-maven-plugin</artifactId>
  <version>1.1.18</version>
  <extensions>true</extensions>
  <executions>
    <execution><id>build-native</id><goals><goal>compile-no-fork</goal></goals><phase>package</phase></execution>
  </executions>
  <configuration><mainClass>com.acme.Main</mainClass></configuration>
</plugin>
```

A minimal application, without a framework: with `praecepta-integration` as a dependency and
`src/main/resources/rules/discount.dmn`, `mvn package` writes an executable that needs no resource
configuration, since `rules/**/*.dmn` is the default location the integration core registers.

```java
public final class Main {
    public static void main(String[] args) {
        var decisions = Decisions.load(
                TableLocations.find(TableLocations.DEFAULT, Main.class.getClassLoader()), Severity.ERROR);
        System.out.println(decisions.evaluate("Discount", Map.of("country", "PT", "age", "42")));
    }
}
```

With Gradle, GraalVM's `org.graalvm.buildtools.native` plugin (`nativeCompile`) runs the same
`native-image`, which reads the same metadata from the jars.

`--exact-reachability-metadata` (GraalVM for JDK 24 and later) works for Praecepta's own reads. Applied to
the whole program, it also stops `java.util.logging`'s console output, which the engine's validation
warnings go through, on the JDK's localised level names (`sun/util/logging/resources/logging_<locale>.properties`
in module `java.logging`): register those for your locale, or limit exact handling to your packages
(`--exact-reachability-metadata=com.acme`).

The framework integrations use no reflection either, and with the default `praecepta.locations` the
integration core's metadata covers their files. The Spring Boot starter works with Spring AOT, which a
Spring native image needs, and its tests run the AOT processing and start an application from the
generated code; Quarkus' tests check that the validated tables become resources registered for the image.
CI does not yet build a Spring Boot, Micronaut or Quarkus application natively, though
([#99](https://github.com/brunopacheco1/praecepta/issues/99)), so those native executables are untested;
files at other locations must be registered through the framework's own native support.

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
table Praecepta can read passes (83 of 86); the other three need inputs computed by other decisions
([#14](https://github.com/brunopacheco1/praecepta/issues/14)), which Praecepta does not have yet.
The pass rate, the results per test case and what each unsupported check needs are in
[praecepta-tck/RESULTS.md](praecepta-tck/RESULTS.md). Semantics are judged by DMN 1.5 and the TCK alone;
where both leave a case open, what Praecepta does is recorded in
[docs/DMN-CONFORMANCE.md](docs/DMN-CONFORMANCE.md), to be settled when a later DMN version or the TCK
decides.

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
Praecepta Engine has no runtime dependencies; it reads DMN with the JDK's own XML parser, even when
another one, such as Xerces, is on the class path.

Praecepta Loader depends on Apache POI. The Maven and Gradle plugins use it at build time only, through
`praecepta-compiler`, so an application that compiles its tables needs only the engine at runtime. The
Spring Boot, Micronaut and Quarkus integrations need only `praecepta-integration`, which needs only the
engine; Spring Boot and Micronaut are the application's own (`provided`), so the integrations bring no
version of them. The Quarkus extension depends on `quarkus-arc`, as every Quarkus extension does, so
Quarkus' tools can check it matches the application's Quarkus version. Its deployment module, which only
Quarkus' build uses, brings `praecepta-compiler` and Apache POI; neither reaches the application.

# Roadmap
[docs/ROADMAP.md](docs/ROADMAP.md) has the plans for the next year; past decisions and their reasons are in
[docs/MODERNISATION.md](docs/MODERNISATION.md).

# Project documents
- [Architecture](docs/ARCHITECTURE.md): modules, the bitset index, DMN, the build plugins and the
  framework integrations.
- [Integrations](docs/INTEGRATIONS.md): what the build-tool and framework integrations share, and how
  they differ.
- [DMN conformance](docs/DMN-CONFORMANCE.md): the cases DMN 1.5 and its TCK leave open, and what
  Praecepta does in them.
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