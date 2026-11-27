<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# User guide

How to write decision tables, evaluate them, and evaluate whole DMN models. For the build plugins and
the framework integrations, see [INTEGRATIONS.md](INTEGRATIONS.md). For how Praecepta works inside, see
[ARCHITECTURE.md](ARCHITECTURE.md).

Praecepta follows the [DMN 1.5 specification](https://www.omg.org/spec/DMN/1.5/) and the
[DMN TCK](https://github.com/dmn-tck/tck). This guide does not repeat them. Where the two leave a case
open, [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md) records what Praecepta does.

- [Decision tables in code](#decision-tables-in-code)
- [Cells](#cells)
- [Typed columns and input values](#typed-columns-and-input-values)
- [List inputs](#list-inputs)
- [Hit policies and results](#hit-policies-and-results)
- [Validation](#validation)
- [DMN files](#dmn-files)
- [Decision requirement graphs](#decision-requirement-graphs)
- [FEEL expressions on their own](#feel-expressions-on-their-own)
- [Excel and CSV files](#excel-and-csv-files)

## Decision tables in code
A rule (`Praeceptum`) maps input columns to cells and output columns to values. The engine evaluates
rules in the order you give them, which is DMN's rule order.

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

An engine is immutable. Build it once and share it between threads.

Builder options:

| Call | What it does |
|---|---|
| `.input(name, getter)` and its typed forms | Binds a column to a getter; see [typed columns](#typed-columns-and-input-values). |
| `.inputs(order -> new PraeceptaInput(...))` | Binds all columns from one map of text instead. |
| `.enumInput(name, Country.class, Order::country)` | Binds a column to an enum. The constants become the column's input values, and lookups index an array instead of hashing. Each constant stands for its `name()`, or pass a function such as `Country::code`. |
| `.outputPriority("grade", List.of("A", "B"))` | The output value list that `PRIORITY` and `OUTPUT_ORDER` need, highest priority first. |
| `.defaultOutput(...)` | The output when no rule matches. |
| `.failOn(Severity.ERROR)` | Makes `build()` reject a table whose validation report has errors. |
| `.logValidationReport(false)` | Stops `build()` from logging the validation issues. By default it logs them as one warning through `System.Logger` (logger `io.github.brunopacheco1.praecepta.engine.PraeceptaEngine`). |
| `.table(decisionTable)` | Takes hit policy, columns, value lists and rules from a [DMN file](#dmn-files). |

## Cells
A cell is a list of DMN FEEL *unary tests* (DMN 1.5, 10.3.1.2, grammar rules 13 to 17). Praecepta
parses it according to the column's type.

| Cell | Matches | Columns |
|---|---|---|
| blank or `-` | any value, including a missing one | all |
| `"PT","ES"` or `PT,ES` | one of the values (in Excel, CSV and code, an unquoted word is a string) | STRING |
| `18`, `2.5`, `.5`, `1.2e3` | that number (`1.0` equals `1`) | NUMBER |
| `date("2025-01-31")` or `2025-01-31` | that date | DATE |
| `true`, `false` | that boolean | BOOLEAN |
| `time("08:30:00")` or `08:30:00` | that time of day | TIME |
| `time("08:30:00+01:00")`, `08:30:00Z`, `08:30:00@Europe/Paris` | a time at an offset or in a zone | TIME |
| `date and time("2025-01-31T08:30:00")` or `2025-01-31T08:30:00` | that date and time, with or without `Z`, an offset or `@Europe/Paris` | DATE_TIME |
| `duration("P1DT2H")` or `P1DT2H` | that length of time (`PT24H` equals `P1D`) | DAY_TIME_DURATION |
| `duration("P1Y2M")` or `P1Y2M` | that number of months (`P12M` equals `P1Y`) | YEAR_MONTH_DURATION |
| `@"2025-01-31"`, `date(2025, 1, 31)`, `time(8, 30, 0)` | the other FEEL literal forms (DMN 1.5, Table 72) | its type |
| `< 18`, `<= 18`, `> 18`, `>= 18`, `< "N"` | comparisons | all but BOOLEAN |
| `[18..65)`, `(0..1]`, `]a..b[`, `["A".."M"]` | intervals: `[` `]` closed; `(` `)` or outward brackets open | all but BOOLEAN |
| `= 18`, `!= "PT"` | equal, or not equal (a missing value is not equal) | all |
| `null`, `= null`, `!= null` | a missing value, or any value that is there | all |
| `not(...)` | anything the tests inside do not match | all |

Rules to remember:
- **Missing values follow FEEL.** A missing value fails every comparison and interval, equals only
  `null`, and so matches `not("FR")` and `!= "FR"`. Blank text counts as missing.
- **Strings compare by Unicode code point**, case-sensitively: `"Zed" < "apple"`.
- **Quoted strings take FEEL's escapes**: `\"`, `\\`, `\n`, `\t`, `\u00e9`. Write a Windows path as
  `"C:\\temp"`.
- **Comments are ignored**: `< 18 // minors`, `/* adults */ >= 18`.
- **Time zones.** Two values with a zone compare by instant. A value with a zone and one without compare
  by local time. A time with a zone id (`@Europe/Paris`) compares only with times in the same zone,
  except a zone with one fixed offset (`@Etc/UTC`), which is the time at that offset. Cases 4, 5, 9 and
  10 of [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md) have the details.

In a DMN file a cell is strict FEEL: strings are quoted, and an unquoted word names another input. A DMN
cell may also be any FEEL unary test; see [DMN files](#dmn-files).

## Typed columns and input values
```java
var engine = PraeceptaEngine.<Applicant, String>builder()
        .hitPolicy(HitPolicy.FIRST)
        .input("country", Applicant::country)             // STRING
        .numberInput("age", Applicant::age)               // any Number, compared as exact decimals
        .dateInput("since", Applicant::since)             // LocalDate
        .booleanInput("student", Applicant::student)      // Boolean
        .timeInput("callTime", Applicant::callTime)       // LocalTime
        .dateTimeInput("appliedAt", Applicant::appliedAt) // LocalDateTime
        .offsetTimeInput("cutOff", Applicant::cutOff)     // OffsetTime, compared by instant
        .zonedTimeInput("callAt", Applicant::callAt)      // ZonedTime: a time with a zone id
        .zonedDateTimeInput("signedAt", Applicant::signedAt) // ZonedDateTime
        .durationInput("waited", Applicant::waited)       // Duration
        .periodInput("tenure", Applicant::tenure)         // Period of years and months
        .allowedValues("country", List.of("PT", "ES", "FR"))
        .allowedValues("age", "[0..120]")
        .output(output -> output.values().get("tier"))
        .rules(rules)
        .build();
```

- **Declared input values** (`allowedValues`, DMN's input values) limit a column. Any other value that
  is not missing throws `InvalidInputException`.
- **Map inputs** (`.inputs(...)`) carry text. Declare each column that is not a string with
  `.columnType("age", ColumnType.NUMBER)`, and the engine parses the text. A time or date-time column
  then takes values with and without a zone.

## List inputs
A column may hold several values, such as a customer's nationalities. Write its cells as usual. A cell
matches when **some element** passes one of its tests. `not(...)` matches when **no element** does.

```java
var engine = PraeceptaEngine.<Customer, String>builder()
        .hitPolicy(HitPolicy.FIRST)
        .listInput("nationalities", Customer::nationalities)          // a Collection<String>
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

These are the semantics of FEEL's `list contains` (DMN 1.5, Table 75). A list may hold missing
elements: a literal is then false for that element, and a comparison is unknown.

With map inputs, declare the column with `.listColumnType("nationalities", ColumnType.STRING)` and give
a FEEL list as text: `["PT", "BR"]`, `[]`, or `["PT", null]`.

In a DMN file, type the column with a collection (`isCollection="true"`) and write its entries with
FEEL's list functions: `list contains(?, "PT")`, `some x in ? satisfies x >= 18`.

## Hit policies and results
Praecepta supports every DMN hit policy (DMN 1.5, 8.2.10): UNIQUE, ANY, PRIORITY, FIRST, OUTPUT ORDER,
RULE ORDER, and COLLECT with SUM, MIN, MAX or COUNT.

- `evaluate` returns a list: one output for a single-hit policy or an aggregation, and one per matching
  rule otherwise.
- **No match** gives an empty list, or the table's default output when it declares one. This holds for
  every hit policy and aggregation, so COUNT gives no output, not 0 (DMN 1.5, 10.3.2.10).
- **A violation throws** `HitPolicyViolationException`: several matching rules under UNIQUE, or rules
  with different outputs under ANY.
- **SUM, MIN and MAX are null** when a matching rule's output is missing, as FEEL's `sum`, `min` and
  `max` are for a list that holds null.

[How a null result is returned](DMN-CONFORMANCE.md#how-a-null-result-is-returned) lists each case.

## Validation
`build()` analyses the table and reports what it finds in `engine.validationReport()`. The analysis is
exact: it searches the same value classes the index uses, so it reports nothing it cannot prove.

| Kind | Severity | Meaning |
|---|---|---|
| `OVERLAPPING_RULES` | ERROR | Two rules of a UNIQUE table match the same input. |
| `CONFLICTING_OUTPUTS` | ERROR | Two rules of an ANY table match the same input with different outputs. |
| `UNREACHABLE_DEFAULT_OUTPUT` | ERROR | The table is complete, so no input can reach its default output (DMN 1.5, 10.3.2.10). |
| `UNREACHABLE_RULE` | WARNING | A rule of a FIRST or PRIORITY table can never be selected. |
| `GAP` | WARNING | No rule matches some declared inputs. The issue gives an example. |
| `ALWAYS_NULL` | WARNING | An output entry or a decision is null whatever its inputs, such as `"a" + 1`. |

A [decision graph](#decision-requirement-graphs) adds `DECISION_CYCLE`, `MISSING_REQUIREMENT`,
`TYPE_MISMATCH`, `UNSUPPORTED_FEATURE` and `INVALID_EXPRESSION`, all errors.

Limits of the analysis:
- It looks for gaps only when every string column declares its input values, or uses only comparisons
  and intervals.
- A rule whose cell is a FEEL test beyond literals, comparisons and intervals (such as `> Limit * 2`)
  is never counted as overlapping or hiding another rule, and its table is not searched for gaps.
- The report lists the first 100 overlapping pairs and the first 100 gaps.

## DMN files
`DmnReader` reads decision tables from DMN 1.1 to 1.5 files, as Camunda Modeler, Drools and Trisotech
write them. `Builder.table(...)` applies the table's hit policy, column types, value lists, default
outputs and rules. You bind the inputs as usual.

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

`DmnReader.readAll(dmn)` reads every decision table of a file. `DmnWriter.write(table)` writes a table as
DMN 1.3, which Camunda and Drools evaluate the same way.

What a DMN table may hold:

| Part | Supported |
|---|---|
| Input expressions | A name (`age`, `customer.age`, `Credit Score`) or any FEEL expression (`Age * 12`, `Birth.year`), of a FEEL simple type or an item definition of one. |
| Input entries | The [cells](#cells) above, as strict FEEL, and any FEEL unary test: `> Limit * 2`, `[Low..Low + 10]`, `? > 5 and ? < 10`. A name that is not a column becomes an input of the engine. |
| Output entries | A literal, or any FEEL expression: `(price - discount) * 1.2`, `if score > 700 then "A" else "B"`. The engine parses and types each entry when it is built, and evaluates it only for the rules that match. |
| Default outputs | `defaultOutputEntry`: a literal, or a FEEL expression that reads no input. |
| Hit policies | All, with every aggregation. |

The bitset index evaluates cells made of literals, comparisons and intervals. The engine applies any
other FEEL test to the rules the index matches.

The reader rejects what it cannot read with an error that names the decision, rule and column. It
rejects an external function (Java or PMML) with an `UnsupportedFeatureException`: Praecepta runs no code
from outside the model ([known deviations](DMN-CONFORMANCE.md#known-deviations)).

## Decision requirement graphs
A DMN model whose decisions require other decisions is a decision requirement graph (DMN 1.5,
chapter 6). `DecisionGraph` reads the model and evaluates any decision by name. It evaluates each
required decision once, in dependency order.

```java
DecisionGraph graph;
try (var dmn = Files.newInputStream(Path.of("credit.dmn"))) {
    graph = DecisionGraph.read(dmn);
}
graph.evaluate("Monthly Limit", Map.of("Applicant Age", 30, "Income", 1200));   // 200
graph.requiredInputData("Monthly Limit");      // [Applicant Age, Income]
graph.validationReport();                      // cycles, missing requirements, type mismatches...
graph.validationReport("Credit Limit");        // that table's own analysis
```

A graph is immutable and thread-safe. It parses every table, expression and name when it is built.

What a model may hold:

| DMN element | Notes |
|---|---|
| Decision tables | Each keeps its bitset index and its own validation report. |
| Literal expressions | The full FEEL language (DMN 1.5, chapter 10) with every built-in function (10.3.4). |
| Boxed expressions | Contexts, invocations, function definitions, relations, lists, conditionals, iterators and filters (10.2). |
| Business knowledge models | A decision invokes them by name: `PMT(Principal, Rate, 12)`. |
| Decision services | A decision invokes them by name. From outside: `graph.evaluateService("Pricing", arguments)`. |
| Imports | `DmnReader.readModel(dmn, imports)` reads a model with the models it imports. The importing model reads an element as `importName.elementName`. |
| External functions | Rejected by design. |

The [DMN TCK results](../praecepta-tck/RESULTS.md) show, model by model, what Praecepta evaluates.

**Input data.** Give Java values (`Integer`, `BigDecimal`, `LocalDate`, `List`, `Map` for a structured
type) or, for simple types, text.

**Values.** A table with one output gives its value. A table with several outputs gives a context (a
`Map`). A multi-hit table gives a list. No match without a default output gives null. The graph binds
each value to the type the decision declares (DMN 1.5, 10.3.2.9.4).

**Errors.** Where FEEL defines no value, the value is null. `graph.evaluateWithErrors(decision,
inputData)` returns the value with FEEL's errors.

**Checks.** The graph's validation report lists what is wrong with the model. A decision with an error,
and every decision that requires it, cannot be evaluated; the others can.
`DecisionGraph.builder().failOn(Severity.ERROR)` rejects the graph instead.

| Kind | Meaning |
|---|---|
| `DECISION_CYCLE` | Decisions require one another in a cycle. |
| `MISSING_REQUIREMENT` | A decision reads a name that no requirement provides. |
| `TYPE_MISMATCH` | A value does not fit where it goes, such as a string into a number column. |
| `UNSUPPORTED_FEATURE` | The model uses an external function. |
| `INVALID_EXPRESSION` | A literal expression is not FEEL. |
| `ALWAYS_NULL` (WARNING) | A decision is null whatever its inputs. |

**In code.** `DecisionGraph.builder()` assembles a graph from input data, decision tables, literal
expressions and business knowledge models. `graph.model()` is its `DecisionModel`, which
`DmnWriter.write(model)` writes as DMN.

## FEEL expressions on their own
`FeelExpression` parses and evaluates FEEL without a model:

```java
var expression = FeelExpression.parse("if Score > 700 then Limit / 12 else 0", List.of("Score", "Limit"));
expression.evaluate(Map.of("Score", new BigDecimal(720), "Limit", new BigDecimal(2400))::get);   // 200
```

Parse once, then evaluate from any thread. One evaluation has limits, so a hostile expression cannot
run without end: case 23 of [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md) lists them.

## Excel and CSV files
`praecepta-loader` reads rules from the first sheet of an Excel file, or from a semicolon-separated CSV
file, on the class path or from an `InputStream`. The header row names the columns. Each following row
is a rule, identified by its row number. Cells use the [syntax above](#cells).

```java
var rules = new ExcelLoader("rules/discounts.xlsx", Set.of("country", "segment"), Set.of("grade"))
        .getPraecepta();
```

Pass the rules to a builder as in [Decision tables in code](#decision-tables-in-code). To keep Apache POI
out of your application, compile the spreadsheet at build time with the
[Maven or Gradle plugin](INTEGRATIONS.md#build-plugins) instead.
