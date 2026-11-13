<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Architecture

Praecepta evaluates DMN decision tables, and the decision requirement graphs of tables and literal expressions
around them. It is a library, not a service: an application builds an engine, or a graph, once and calls it.

## Modules

| Module | Depends on | Role |
|---|---|---|
| `praecepta-engine` | the JDK only (`java.xml`) | The engine, the cell parser, DMN reading and writing, validation, and decision requirement graphs (`DecisionGraph`). A JPMS module, `io.github.brunopacheco1.praecepta.engine`. |
| `praecepta-loader` | engine, Apache POI | Reads rules from Excel (first sheet) and semicolon-separated CSV files. |
| `praecepta-compiler` | engine, loader | The build-time core of the build tools: loads a table, validates it, writes it as DMN and generates typed Java classes (`TableCompiler`). Independent of any build tool. |
| `praecepta-integration` | engine | The runtime core of the framework integrations: finds DMN files by location pattern and builds a map-based engine per decision, with its validation report (`Decisions`). Independent of any framework. |
| `praecepta-maven-plugin` | compiler, Maven API | The `generate` goal: runs the compiler on the configured tables and adds the results to the Maven build. |
| `praecepta-gradle-plugin` | compiler, Gradle API | The plugin `io.github.brunopacheco1.praecepta`: the `generatePraecepta` task runs the compiler on the tables of the `praecepta` extension and adds the results to the `main` source set. Built by Maven against the Gradle API. |
| `praecepta-gradle-plugin-marker` | gradle plugin | The Gradle plugin marker (a POM), so that Gradle resolves the plugin by id from Maven Central. On the Gradle Plugin Portal the Portal writes its own. |
| `praecepta-spring-boot-starter` | integration, Spring Boot (`provided`) | Spring Boot auto-configuration: loads the tables at `praecepta.locations` at startup, registers a `Decisions` bean and an engine bean per decision, and reports them through the Actuator endpoint `praecepta`. |
| `praecepta-micronaut` | integration, Micronaut 4 (`provided`) | The Micronaut module: loads the DMN files at startup into a `Decisions` bean and one engine bean per decision, named after it, and reports through the `praecepta` management endpoint. |
| `praecepta-quarkus` | integration, Quarkus ArC | Quarkus extension, runtime: builds the `Decisions` bean and one engine bean per decision (`@Identifier("Discount")`) from the DMN the build step validated (`PraeceptaRecorder`). |
| `praecepta-quarkus-deployment` | runtime, compiler, Quarkus ArC deployment | Quarkus extension, build time: finds DMN files, compiles declared Excel and CSV tables, validates them and fails the build on the configured issues; reloads changed tables in dev mode (`PraeceptaProcessor`). |
| `praecepta-benchmarks` | engine, Camunda 8, Drools, JMH | Compares Praecepta with other engines; not published. |
| `praecepta-tck` | engine, integration | Runs the whole DMN TCK (compliance levels 2 and 3) and scores every result node against a committed baseline; not published. |
| `praecepta-native-tests` | the framework modules | A Spring Boot, a Micronaut and a Quarkus application, built as GraalVM native executables and run; in the reactor only with the `native-frameworks` profile; not published. |

The build-tool and framework integrations build on `praecepta-compiler` and `praecepta-integration`, so
that they behave alike; what they share (settings, bean names, reports, failures) is in
[INTEGRATIONS.md](INTEGRATIONS.md). Every module is a directory directly under the repository root, with
the reactor's POM as its parent, and its Java package is `io.github.brunopacheco1.praecepta.<module>`
(`engine`, `loader`, `compiler`, `integration`, `maven.plugin`, `gradle`, `spring`, `micronaut`,
`quarkus`, `quarkus.deployment`); no package is split across modules, and each jar has an
`Automatic-Module-Name` equal to its package.

## From a table to an answer

```
 Excel / CSV ──(loader)──► rules ──┐
                                   ├──► PraeceptaEngine.Builder ──► PraeceptaEngine ──► evaluate(input) ──► outputs
 DMN file ──(DmnReader)──► DecisionTable ┘        │
                                                  └──► ValidationReport (overlaps, unreachable rules, gaps)
```

1. **Rules and cells.** A rule (`Praeceptum`) maps input columns to cells and output columns to
   values. `CellParser` parses each cell, according to its column's type (`ColumnType`: STRING,
   NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION, YEAR_MONTH_DURATION; times and
   date-times with or without a time zone), into `UnaryTests`: the subset of DMN FEEL *simple unary
   tests* that looks at one column value (lists, `not(...)`, comparisons, `=` and `!=`, intervals, `null`,
   "any"). In a DMN table (strict FEEL) a cell may also compare with another input (`> limit`); the
   index sees such a cell as "any value", and the engine checks it on the rules the index leaves,
   with the inputs' values. The analysis then never counts those rules as overlapping or hiding
   others, and skips gaps. A column
   may also hold **lists** (`DecisionTable.listInputs`): its cells keep the same syntax and hold when
   some element passes a test (`not(...)`: when none does), as FEEL's `list contains` would; a cell
   that names a list input asks whether the value is in that list.
2. **The bitset index** (`BitsetIndex`, `ColumnIndex`). Every rule owns one bit. Each column splits
   its values into *classes* that no rule can tell apart, and each class carries a mask: the rules
   that accept it.
   - **Strings:** one class per value a rule mentions (or the column declares), one for any other
     value, and one for a missing value. Lookup is a hash, or an array index for enum inputs.
   - **Numbers, dates, times, date-times and durations:** the boundaries the rules mention split the
     line into intervals and points; lookup is a binary search, over `long` keys when the values have
     them (`PrimitiveKeys`: epoch days, nanoseconds, months, scaled numbers). A string column whose
     cells compare (`< "N"`, `["A".."M"]`) is split the same way, in FEEL's order by code point
     (`FeelStrings.compare`, not `String.compareTo`, which orders by UTF-16 code unit); a value the rules mention is
     still found by hash, and only other values need the binary search. Booleans are the same with
     `false < true`, both always boundaries, so they get one class each.
   - **Times and date-times with a time zone** (`OffsetTime`, `ZonedDateTime`). FEEL compares two
     values with a zone by instant, and any other pair by local time: the value without a zone takes
     the other's offset (DMN 1.5, Table 54). So a value without a zone lies on the line of local
     times, split by the local time of every boundary, with or without a zone. A value with a zone
     has two coordinates: its instant, on a line split by the boundaries with a zone (keyed in
     `PrimitiveKeys` by nanoseconds at offset zero), and its local time, on a line split by the
     boundaries without one. Its class is the pair of the two classes, and a test reads the
     coordinate of its literal's kind (an interval with an end of each kind reads both). When the
     cells of a column are all of one kind, one coordinate has a single class and the pairs are as
     many as the classes of the other. A pair is empty when no value has both coordinates: an
     instant and a local time are a whole number of seconds apart, at most 18 hours. A column has
     only the classes its input can reach (`Zoning`): `timeInput` and `dateTimeInput` give values
     without a zone, `offsetTimeInput` and `zonedDateTimeInput` with one, `zonedTimeInput` times with a
     zone id or, in a zone with one fixed offset, at an offset, and map-based inputs, read from text, any of
     them; so the analysis looks for gaps among the values that can really occur.
   - **Times with a zone id** (`ZonedTime`, #82). Without a date a zone id has no offset, so such a time
     compares only with a time in the same zone, by local time, which is all the DMN TCK asks; any other
     comparison of it is one DMN 1.5 leaves open, which Praecepta takes as FEEL's null (see
     [DMN conformance](DMN-CONFORMANCE.md)). Each zone id the cells name has a line of
     local times of its own, and one class holds the zone ids no cell names; every test of another kind is
     unknown on them, and theirs on every other class. A column whose inputs can be such times (map-based
     inputs, `zonedTimeInput`, a list's elements) has that class even when its cells name no zone id: only
     the rules that accept any value match it, and the analyses visit it as they do every class, so a rule
     that only such a time reaches is not reported unreachable (#114). A zone with one fixed offset
     (`Etc/UTC`) is no such zone: DMN 1.5's example of `time(from)` (Table 72) has
     `time("00:01:00@Etc/UTC")` equal `time("00:01:00Z")`, so the engine reads a time in such a zone as the
     `OffsetTime` at that offset at its edges (`FeelTemporals.parseTime` for text, cells and output entries,
     `time(date and time(...))`, `ColumnIndex.convert` for what a getter gives), and the index, the analyses
     and the arithmetic only ever see an offset time.
   - **Lists** (`ListColumn`): an ordinary column index over the same cells, without their `null`
     tests (`? = null` tests the list, not an element), classifies one element; a missing element
     falls in its class for a missing value, where `list contains(?, null)` is true, a literal false
     and a comparison unknown. For each element, its class mask gives the positive cells it passes
     and the `not(...)` cells whose inside it does not make false (true, or unknown); ORing those over
     the elements, and the list's mask is the `-` rules, the positive rules some element passes, and
     the negated rules no element leaves true or unknown, which is FEEL's three-valued `or` over the
     elements (DMN 1.5, Table 49). A missing and an empty list have fixed masks. Two nationalities cost one more OR per mask word. The
     element index stores its masks in either form (see below), and the list masks it gives are
     dense. Elements of a time or date-time list may have a zone or not: the element index takes
     both kinds, and classifies each element as it does a single value.
   - **Memory** grows with columns × classes × rules, never with the product of the values.
   - **Compact masks** (`Masks`): a mask is stored dense (one bit per rule) unless at most a quarter
     of its 64-bit words hold a set bit; it is then stored *sparse*, as the pairs (word, bits) of its
     non-zero words, which takes at most half the memory and usually far less. Both forms are a
     `long[]`, told apart by length, so evaluation pays one comparison per column. In a table of
     thousands of rules where a column has a value per rule (a product code, say), each such mask
     drops from rules / 8 bytes to a few longs, and that column's memory from classes × rules to
     about the number of rules. A table of up to 192 rules (three words) stores only its empty masks
     sparse, as one shared empty array. Under ANY, the per-rule masks of the rules with the same
     outputs are stored the same way.
3. **Evaluation.** `evaluate(input)` reads each column's value through the bound getter
   (`InputStrategy`), finds its class, and ANDs the masks, 64 rules per machine word. Columns are
   evaluated with the fewest wildcards first. Once a column's mask is sparse, the candidates are
   confined to its few words: they are kept as (word, bits) pairs, and each later column intersects
   only those, so a lookup by code touches a word or two instead of the whole table. The hit policy
   then reads the result: FIRST and PRIORITY take the lowest set bit (bits are numbered in rule or
   priority order), UNIQUE and ANY check their constraint, RULE ORDER, OUTPUT ORDER and COLLECT walk
   the set bits, and COLLECT aggregates with SUM, MIN, MAX or COUNT (`OutputStrategy`). The engine, not the
   strategy, applies FEEL's null semantics to SUM, MIN and MAX over a table's one output: a matching rule whose
   output is missing is the result, and the strategy combines only outputs that are present, so the generated
   classes, the framework integrations' map-based engine and any other strategy agree.
4. **Validation** (`TableAnalysis`, `ValidationReport`), when the engine is built: overlapping rules
   (UNIQUE), conflicting outputs (ANY), rules that can never fire (FIRST, PRIORITY), and gaps
   (declared inputs that no rule covers, with an example), and, for a table with a default output, whether
   it is complete (`Completeness`): whether every input, a missing value in any column and a string no rule
   mentions included, matches a rule, so that the default can never be given (an ERROR,
   `UNREACHABLE_DEFAULT_OUTPUT`). It searches a list column as the minimal sets of rules every list it takes
   can match (`ListAnalysis.everyOutcome`: missing lists and missing elements included). The analysis is
   exact, because it searches the same column classes the index uses. A list column is searched as the sets of its element
   classes (`ListAnalysis`): rules `"PT"` and `"BR"` overlap on `["PT","BR"]`. Which rules a list
   matches depends only on which element classes it holds, and adding an element only adds positive
   rules and removes `not(...)` ones, so overlaps are decided pairwise and the last list column of a
   search directly: a list matches none of some rules exactly when it holds no element that passes
   their positive cells and, for each of their `not(...)` cells, one that rules it out. That is also
   how a gap there is described (`nationalities has no element in "PT", nationalities has an element
   in "ES"`). The searches fix list columns after the others; any list column before the last
   branches on the minimal sets of rules its lists leave, at most `ListAnalysis.MAX_OUTCOMES` of them.
   Which earlier rules cover a rule on the last list column, by themselves, is found for all of them at once
   with a few mask operations per element class (`ListAnalysis.covering`), so the cost grows with rules times
   classes, not with pairs of rules. Overlapping pairs (UNIQUE) and pairs with conflicting outputs (ANY)
   are listed up to `TableAnalysis.MAX_OVERLAPS` (100), the first in rule order, as gaps are up to 100: a
   list column with a value per rule makes almost every pair of rules overlap.

An engine is **immutable** once built: any number of threads may share it without locks.

## DMN
- `DmnReader` reads DMN 1.1 to 1.5 (as written by Camunda Modeler, Drools, Trisotech and
  `DmnWriter`) into a `DecisionTable`: hit policy, aggregation, column types, value lists, default
  output values and rules. A single output takes the type of the decision's result when its output
  clause names none, as DMN 1.5 requires of it (Table 34): the decision table's `typeRef`, else the
  decision variable's, whose item type for a multi-hit table, and none for COUNT. A table's default output (`DecisionTable.defaultOutput`, from each output's
  `defaultOutputEntry`) is converted once when the engine is built and returned when no rule matches,
  before the hit policy or the aggregation is applied (DMN 1.5, 10.3.2.10), so it costs nothing at
  evaluation; without one, no match gives no output under every aggregation, COUNT included. Each
  `OutputColumn` says whether it declares a default (`hasDefault`), since a default of `null` must be told
  from none: with several outputs, defaults that are all `null` still make a default output, of nulls.
  It uses the JDK's own XML parser (`DocumentBuilderFactory.newDefaultInstance()`, never one JAXP finds
  on the class path) with DTDs and external entities disabled. Anything it does not
  support (function calls) is rejected
  with an error naming the decision, rule and column. Output entries may be expressions of
  literals (dates, times and durations included) and inputs with `+ - * / **` (`OutputExpression`): they are kept as written, parsed and
  type-checked when the engine is built, and evaluated only for matching rules, like outputs that
  name an input; `DmnWriter` writes them back unchanged. Strict FEEL compares a list with a unary test
  as a whole, so a list column's entries are written with FEEL's list functions
  (`list contains(?, "PT") or some x in ? satisfies x >= 18`); `CellParser.parseFeelList` reads
  exactly those forms back into the same tests Praecepta's own syntax writes `"PT", >= 18`.
- `DmnWriter` writes a `DecisionTable` as standard DMN 1.3, which `DmnReader` reads back unchanged
  and which Camunda 8 and Drools evaluate identically (the benchmarks check this).

## Decision requirement graphs (#14)
A DMN model whose decisions require other decisions and input data is a decision requirements graph (DMN 1.5,
chapter 6). 0.7 evaluates graphs whose decisions are decision tables or literal expressions in the expression
subset output entries take; later releases add the other kinds of logic as further implementations of the same
types (see *Growing it* below).

```
 DMN file ──(DmnReader.readModel)──► DecisionModel ──► DecisionGraph.Builder ──► DecisionGraph ──► evaluate(decision, inputData)
 code ──(DecisionGraph.builder())───────────────────────────┘        │
                                                                     ├──► validationReport(): cycles, missing requirements, type mismatches
 DecisionModel ──(DmnWriter.write)──► DMN                            └──► validationReport(decision): each table's own analysis
```

**The model** (`beans`, plain records, no behaviour): `DecisionModel(name, inputData, decisions)`; an
`InputData(name, FeelType type)`; a `Decision(name, requiredDecisions, requiredInputs, DecisionLogic logic)`, whose
requirements are names of the model's elements (the information requirements of DMN 1.5, 6.3.13). `DecisionLogic` is
a sealed interface with two kinds today: `Table(DecisionTable)`, named after its decision, and
`LiteralExpression(text, declared FeelType)`, checked to be in the expression subset when it is made
(`OutputExpression`). `FeelType` (`feel`) is the type of a value passed between elements: `Simple(ColumnType)`,
`ListOf(FeelType)`, `Context(components by name, ordered)`, or `Any` where the model declares none. The model
rejects two elements of one name (6.3.7, 6.3.11), and nothing else: a model may be ill-formed, which the graph reports.

**Reading and writing.** `DmnReader.readModel` reads every `inputData` (its variable's `typeRef`, resolved through
item definitions to a `FeelType`: collections, components, nested to `MAX_TYPE_DEPTH` levels, with a check for types
defined in terms of themselves) and every `decision`: its information requirements, by `href` to an element of the
model (one to no element is kept as the id it names, which the graph reports as missing), and its logic, a decision
table read exactly as `read(dmn, decision)` reads it, or a literal expression, typed by the decision variable's
`typeRef`. What later releases bring is rejected whole, naming the feature and its issue: business knowledge models,
knowledge requirements and boxed expressions as logic (#118), FEEL beyond the subset (#116, function calls #117),
decision services, imports and references into other models (#119). Knowledge sources, authority requirements,
annotations and diagrams take no part in evaluation and are ignored. `DmnWriter.write(DecisionModel)` writes DMN 1.3
with ids by position (`input_data_0`, `decision_0`, and `decision_0_` before the ids of a decision's table, so the
tables of a model never share an id), item definitions for list and context types, and literal expressions as
written; a model it writes reads back as the same model, and writes the same again. `read(dmn)`, `read(dmn, decision)`
and `readAll(dmn)` read single tables as before.

**Building** (`DecisionGraph`, with the package-private `GraphAnalysis`, `GraphNodes` and `GraphValues`). Every
input data and decision gets a *slot*: input data first, then decisions, in the model's order. `GraphAnalysis` then:
1. checks each requirement exists and is of its kind (`MISSING_REQUIREMENT`);
2. resolves each name a decision's logic reads (a table's columns and the inputs its cells compare with, a literal
   expression's names) among that decision's requirements: the requirement so named, else the one named by its
   longest part before a dot, the rest being a path into its value (`Pricing.discount`, `Loan.principal`); a name
   no requirement provides is a `MISSING_REQUIREMENT` (7.3.4);
3. finds cycles among required decisions (Tarjan's algorithm with an explicit stack), reported with the cycle's
   path (`DECISION_CYCLE`), and orders the other decisions so each comes after those it requires (Kahn's algorithm);
4. works out types: an input data without a `typeRef` takes the type of the table columns that read it whole, when
   they agree; a table's value is its one output's type, or a context of its outputs, a list of that under a hit
   policy with several results and no aggregation, a number for COUNT; a literal expression's comes from the types
   of the names it reads (single strings, numbers, dates, times or durations), bound to the declared type as DMN 1.5,
   10.3.2.9.4, binds a decision's value (a date to a date and time, a value to a list of one, anything else that does
   not conform to null, which is also reported);
5. checks that each value a table reads fits its column (`TYPE_MISMATCH`): the column's type, or for a column of lists
   a list of it or a single value (a list of one); a path must exist in the type it reads; a cell that names a list
   (`Flu Symptoms`) asks whether the value is in it, so such an input is bound as a list of the table;
6. marks the decisions that cannot be evaluated: those with an issue, in a cycle, or requiring such a decision.

The graph's report lists those issues, all ERRORs; `Builder.failOn(severity)` rejects the graph when its report, or
one of its tables', has issues that severe, and `logValidationReport` logs them once, as `PraeceptaEngine` does.
Each decision table becomes a `PraeceptaEngine<Object[], Object>` (`GraphNodes.TableNode`), whose inputs are the
graph's slots: each column is bound with a getter that reads its slot and path (`PraeceptaEngine.Builder.valueInput`,
package-private, of `Zoning.EITHER`, as map-based inputs are, so each table's validation report is the one the
framework integrations' map-based engines report), and whose output strategy gives the table's value (each output
converted from text to its type once, when the engine is built, for rules that compute nothing). A literal expression
is parsed once (`GraphNodes.LiteralNode`) and reads its names through resolved slots and paths.

**Evaluating.** `evaluate(decision, inputData)` takes the decision's *plan*, worked out the first time and cached:
the slots of the input data it needs and the decisions it needs, itself last, in dependency order. It fills an
`Object[]` of slots with the input data, made FEEL values of their declared types (`GraphValues.normalise`: an
`Integer` becomes a `BigDecimal`, a `Period` a `YearsAndMonths`, text of a simple type its value, a map the declared
components of a context), then evaluates each decision once into its slot. A path reads a context's component, or of
a list of contexts the list of their components (10.3.2.5), and a column of lists takes a single value as a list of
one (10.3.2.9.4). No walk over the graph recurses, so a chain of thousands of decisions builds and evaluates on any
thread's stack. A graph is immutable once built (the plans are cached in a concurrent map) and thread-safe.

**For the build plugins and the framework integrations** (how they use it is in the two sections below).
Everything they need is public API of `DecisionGraph`:
`decisions()` (names, in model order), `inputData()` (each input data's `FeelType`, declared or worked out),
`type(decision)` (a decision's `FeelType`), `requiredInputData(decision)` (what a decision reads, directly or
through the decisions it requires), `canEvaluate(decision)`, `validationReport()` and `validationReport(decision)`,
`evaluate(decision, inputData)` and `evaluate(decisions, inputData)` (several at once, each required decision once),
and `model()` (to write the model with `DmnWriter`, as generated code would ship it). A typed input record per
decision can be generated from `requiredInputData` and `inputData()`'s types; an output type from `type(decision)`
(`Simple` → its Java type, `Context` → a record of components, `ListOf` → a `List`). The map-based integrations can
pass maps of text, as simple types take text. The values a graph gives are the `FeelType` value classes listed in
its Javadoc, immutable.

**Growing it.** Each later layer adds to these types rather than replacing them: full FEEL (0.8, #116) replaces
`LiteralExpression`'s subset parser and `GraphNodes.LiteralNode` with a FEEL interpreter over the same slots, and
`FeelType` with FEEL's full type system (ranges, functions); built-in functions (0.9, #117) are names that
resolve to the built-in context; boxed expressions and business knowledge models (0.10, #118) are further
`DecisionLogic` kinds with nodes of their own, and invocables become elements with slots that hold functions,
required through knowledge requirements, with the same cycle and type checks; decision services and imports (0.11,
#119) add elements and qualified names to the resolution of step 2. The reader rejects each of them by name until
then.

## The compiler and the build plugins
`TableCompiler` (in `praecepta-compiler`) compiles one table, described by a `TableDefinition`, with
`CompilerOptions` that name the package, the failing severity, the file header, the tool named in the
generated files and how messages name the tool's settings. The Maven plugin's `GenerateMojo` and the
Gradle plugin's `GeneratePraecepta` task turn each configured table into a `TableDefinition` and run
`TableCompiler` on it; the Quarkus extension does the same with its own configuration:
1. load it (Excel or CSV through the loader, or DMN through `DmnReader`);
2. build an engine and validate it, failing the build at the configured severity; each input is
   bound with a getter of the Java type the generated record gives it, so the analysis sees the
   values the generated code can pass (with or without a time zone);
3. write it as a DMN resource next to the generated classes;
4. generate, with `SourceGenerator`, an input record (string columns with a value list become enums,
   bound to the engine's int-coded path), an output record, and a class that loads the DMN resource
   once and evaluates it. `JavaNames` turns table, column and value names into safe identifiers.
   `TimeZones` decides which time and date-time columns become `OffsetTime` and `ZonedDateTime`:
   those whose written values all have a zone, together with the inputs their cells compare with; a time
   column whose written values have zone ids becomes the engine's `ZonedTime`, even if some have an offset
   (a zone id with one fixed offset counts as one, so a column of only such zone ids is an `OffsetTime`),
   and the generated code reads such outputs back with `FeelTemporals.parseZonedTime` or `parseOffsetTime`,
   which take a time written either way. An output expression
   counts by its operands of the output's type, the inputs it reads and its literals, since adding a
   duration keeps a time's or a date and time's zone.

At runtime, the generated code needs only `praecepta-engine`. The tool writes the result with
`CompiledTable.write`, so every tool writes the same files.

**Decision requirement graphs** (#14). Before loading a DMN source as a table, `TableCompiler` reads it as a
decision model (`DmnReader.readModel`); `GraphCompiler` takes over when the decision the definition names, or,
when it names none, one of the model's decisions, requires another decision or is a literal expression. Anything
else compiles as before, byte for byte (the golden files of single tables are unchanged): a decision table that
requires only input data, a model without such decisions, and a file that is not a well-formed model in a way a
file of tables never had to be (two elements of one name, which `DmnWriter` writes for a table with a column of its
own name; a decision without logic), and a model that uses what a later release plans (#116 to #119) but has the
table to compile, which 0.6 compiled; without such a table, the reader's message, which names the release, fails
the build. `GraphCompiler` builds the graph (`DecisionGraph.Builder.failOn` at the build's `failOn`, without
logging), writes the model with `DmnWriter.write(model)`, and `GraphSourceGenerator` generates:
- a model class, `<Model>Model` (or the named decision's class with `Model`), that reads the DMN resource once into
  a `DecisionGraph` (`graph()`, `validationReport()`), with a record per structured input data, and the helpers the
  conversions call (lists element by element, `YearsAndMonths` to `Period`);
- per decision, an input record of `requiredInputData(decision)` and a class whose `evaluate` puts each component in
  a map by input data name, evaluates the decision through the graph, and converts its value: a cast for simple
  types, a record's `of(Map)` for a context (a `<Decision>Output` record; contexts within contexts are nested
  records, depth bounded by the reader's `MAX_TYPE_DEPTH`), the helpers for lists and years and months durations.
  Times and date-times are typed `Object` and `TemporalAccessor`: the graph keeps a value's zone, or lack of one,
  as its class, which the model's types do not say (`TimeZones` decides it for a table's own columns from what its
  cells write, which says nothing of a value one decision passes another).
Each decision's table keeps its analysis (`CompiledTable.reports()`, by generated class, beside the graph's own
report by the model class). The generated classes evaluate through the engine's `DecisionGraph`, so they agree with
it by construction; `GraphCompilerTest` checks them against it and against `praecepta-integration`'s map engine.

The Gradle plugin is built by the Maven reactor like every other module, compiled against the Gradle
API that gradleplugins.dev redistributes on Maven Central (`dev.gradleplugins:gradle-api`, provided),
with a hand-written plugin descriptor (`META-INF/gradle-plugins/io.github.brunopacheco1.praecepta.properties`);
`praecepta-gradle-plugin-marker` is the marker POM through which Gradle finds a plugin by id in a Maven
repository. Its task declares the table files (by name and content) and every setting as inputs and the
two generated directories as outputs, so Gradle's build cache can reuse it across machines; its inputs
are managed properties and nested beans with no reference to the project, so it works with the
configuration cache. Its unit tests run the plugin in an in-process project (`ProjectBuilder`); its
functional tests (Gradle TestKit) build real projects on several Gradle versions in their own workflow.
Releases also go to the Gradle Plugin Portal, through `praecepta-gradle-plugin/portal`: a Gradle build
that compiles nothing and publishes the jars and POM that Maven built (fetched from Maven Central), so
both repositories serve the same files. The same workflow checks on every change that this build
publishes Maven's files byte for byte.

The plugin compiles against the Gradle 8.11.1 API on purpose, although Gradle 9 is current
([#97](https://github.com/brunopacheco1/praecepta/issues/97), closed). `dev.gradleplugins:gradle-api` is the
only Gradle API on Maven Central, and its last release is 8.11.1; but an older API is what the plugin
needs: compiling against it keeps the plugin to API that Gradle 8.5, the oldest version it supports,
already has, and the plugin uses nothing newer. That it works on current Gradle is shown by the
functional tests, which the `Gradle versions` workflow runs on Gradle 8.5, the last 8.x and the current
9.x. The two ways to a newer API would cost the release its reproducibility: taking the API from
Gradle's own repository would add a repository other than Maven Central to the release build and to
Reproducible Central's rebuild of it, and building the plugin with Gradle would take it out of the single
Maven reactor that builds and rebuilds every module the same way. Should the plugin ever need API newer
than 8.11, that decision is to be made again.

## Framework integrations
`praecepta-integration` turns DMN files into named engines for the Spring Boot, Micronaut and Quarkus
integrations: `TableLocations` finds files by pattern (`classpath*:rules/**/*.dmn`), `Decisions.load`
reads every decision table of every file (`DmnReader.readAll`) and builds one `Decision` per table, with
an engine bound to maps of text (`PraeceptaEngine<Map<String, String>, Map<String, String>>`) and its
validation report, and fails with every problem at once (`DecisionsException`): unreadable files, invalid
tables, duplicate names, and validation issues at the configured severity. Maps rather than records,
because a container finds its tables at runtime; typed records remain the build plugins' job. What each
framework does with its settings, from `locations` and `fail-on` to the log lines, is one class,
`DecisionsLoader`, given the framework's resource lookup and logger, so the three word their problems
alike. It logs each validation issue once, through the framework's logger, so the engines it builds do not
log their reports themselves (`Decisions.load(sources, failOn, false)`, which builds them with
`logValidationReport(false)`); nor does the engine `TableCompiler` validates with, as the build tools log
its report. At startup, Quarkus builds its engines without logging either, since its build logged the
issues. Aggregations follow `AggregationOperator.check`, the engine's statement of where DMN defines them,
and compare values of the output's type (`FeelLiterals.value`, `FeelTemporals.compare`).

**Decision requirement graphs** (#14). `Decisions.load` reads each file as a decision model
(`DmnReader.readModel`). When one of its decisions requires another decision, or is a literal expression
(`GraphModel.needed`), the file becomes a `GraphModel`: its `DecisionGraph`, built once, and the conversion of maps
of text to input data (simple values stay text, which the graph parses with their type; lists are read as a list
column reads them; a context is gathered from its components' paths, `Loan.principal`) and of a decision's value to
the maps a table's engine gives (one per element of a list, a context's components as text, any other value under
the table's one output, or the decision's name; none for null). Every decision of the file is then a `Decision`
whose `evaluate` goes through the graph; a table's decision keeps its own map-based engine (its report is the one
the graph's table reports, since the graph binds its columns as map inputs, `Zoning.EITHER`), which the frameworks
register as its bean, so table beans are what 0.6 registered; a literal expression has no table, no engine and no
bean. The graph's report is checked at `fail-on` and logged once, as the model's. Any other file is read as before
with `readAll`, a model the reader rejects included: one with a later release's feature, whose tables 0.6 loaded and
still loads, alone (the rest of the model is not evaluated, and the file fails with the reader's message, which names
the release, when it has no table), and one that is not a well-formed model, as a file of tables never had to be. `Decisions.describe()` adds `models` only when there are graphs, so the description of files of tables
is unchanged.

The Spring Boot starter loads the tables while the context registers its bean definitions (an
`ImportBeanDefinitionRegistrar`), because each decision becomes a bean of its own, named
`praecepta.<name>` and qualified by the decision's name; its resources are found with Spring's
`ResourcePatternResolver`, so the nested jars of an executable jar are searched too. A table that fails
stops the refresh before any application bean is created. Its bean definitions have instance suppliers,
the loaded engines, which Spring AOT cannot write as code; `DecisionsAotProcessor` (registered in
`META-INF/spring/aot.factories`) generates in their place calls that load the tables when the application
starts (`DecisionsRegistrar.decisions`, `engine`), keeping the names, types and qualifiers the registrar
gave the beans while the application was processed.

The Micronaut module (`praecepta-micronaut`) creates `Decisions` as the first context-scoped bean
(`@Context`, highest precedence) and, while doing so, registers one singleton bean definition per
decision (`RuntimeBeanDefinition`, qualified by the decision's name), so every bean of the application
can inject an engine with `@Named`. Micronaut's annotation processors generate the bean definitions and
the configuration binding at compile time, so nothing is reflective.

The Quarkus extension does its work while Quarkus builds the application. Its deployment module
(`PraeceptaProcessor`, with the settings of `PraeceptaBuildTimeConfig`) finds the DMN files with
`Location.matches` in the application's archives and every runtime dependency (the class path the
application will have, not the build's), and `file:` patterns with `TableLocations`; compiles the declared
Excel and CSV tables with `praecepta-compiler`, so Apache POI stays in the build; validates everything with
`Decisions.load`; and writes the validated DMN into the application as generated resources
(`META-INF/praecepta/tables/<n>.dmn`, registered for native images). The runtime module's recorder
(`PraeceptaRecorder`) rebuilds the engines from those resources during static initialisation, so in a
native executable they live in the image heap, and produces the `Decisions` bean and one `@Singleton`
synthetic bean per decision. Dev mode restarts the application when a file a class path pattern matches
changes or appears, or when a `file:` table or a declared spreadsheet changes. `quarkus-arc` and
`quarkus-arc-deployment` are compile dependencies, as Quarkus requires of an extension, so its tools
check the application's Quarkus version against the extension's. The tests use
`QuarkusExtensionTest` and `QuarkusDevModeTest` and run in the default build.

## Native images
Praecepta needs no reflection, so GraalVM's `native-image` builds applications that use it without a
`Feature` or reflection metadata. What it does need is in each jar, under
`META-INF/native-image/io.github.brunopacheco1/<artifactId>/resource-config.json`, which `native-image`
reads from every jar on its class path, whichever build tool or framework runs it:
- **`praecepta-engine`** registers the message bundle of the JDK's XML parser
  (`com.sun.org.apache.xerces.internal.impl.msg.XMLMessages`), without which `DmnReader` fails on a
  malformed file with a `MissingResourceException` instead of its `InvalidDecisionTableException`. It
  needs nothing for the parser itself: `DmnReader` takes the JDK's own
  (`DocumentBuilderFactory.newDefaultInstance()`), so JAXP looks up no service file or system property.
- **`praecepta-integration`** registers `rules` and `rules/**/*.dmn`, the files `TableLocations.DEFAULT`
  finds, so the default location works in a native image; `TableLocations` lists them through the image's
  `resource:` file system.
- **`praecepta-compiler` writes none.** The classes it generates read their DMN with
  `getResourceAsStream` and a constant name, which `native-image` registers itself (checked on GraalVM for
  JDK 21 and 25, the latter with `--exact-reachability-metadata`).
- **`praecepta-loader` ships none**: `CsvLoader` reads the application's files, which the application
  registers. `ExcelLoader` does not work natively (Apache POI needs charsets, XMLBeans bundles and schema
  metadata that neither POI nor GraalVM's shared metadata repository provides); Excel tables reach a native
  executable as DMN, compiled at build time.

The metadata uses the `resource-config.json` format, which GraalVM for JDK 21 reads as well as later
releases. `praecepta-maven-plugin/src/it/native-app` is an application that uses all of this; every build
runs its checks on the JVM, and the `Native image` workflow (`-Pnative`) builds it with GraalVM for JDK 21
and 25 and runs the executable, with `--exact-reachability-metadata` on 25, so a resource no metadata
registers fails the run instead of reading as absent. The framework modules use no reflection and rely
on the integration core's metadata for the default location; the Quarkus extension registers the tables
it validated, and the Spring Boot starter's AOT processor (`DecisionsAotProcessor`) writes the engine
beans' definitions, with both their qualifiers, as code. `praecepta-native-tests` holds an application per
framework (`src/it/spring-boot-app`, `micronaut-app`, `quarkus-app`), each a user project built by
`maven-invoker-plugin` with the framework's own native build (Spring Boot's `native` profile, Micronaut's
`native-image` packaging, Quarkus' `-Dnative`), then run; it evaluates decisions through the beans it
injects and exits with 1 on a wrong answer. The module is in the reactor only with the `native-frameworks`
profile, so the default build does not need GraalVM; the `Native frameworks` workflow builds each
application with GraalVM for JDK 25, and Micronaut's with JDK 21 too (Spring Boot 4 and Quarkus 3.40 need
GraalVM 25; the invoker skips them on an older one). Each takes its framework's defaults, the
`native-maven-plugin` version included, as its users would.

## Scoring the DMN TCK (#115)
`praecepta-tck` runs every model of compliance levels 2 and 3 of the DMN TCK, at a pinned commit, in every
build, and scores each result node as passed, failed or not supported ([RESULTS.md](../praecepta-tck/RESULTS.md)).
Its pieces are meant to outlive the layers of the DMN 1.5 plan (#120):
- **One seam for evaluation.** `ModelEvaluator` takes a result node (the model, the test case's inputs, what
  it invokes) and returns a value, an error, a failure, or the features it lacks; the runner compares, scores
  and reports. Today `SingleDecisionEvaluator` evaluates one decision on its own, a decision table through
  `DmnReader` and the engine and a literal expression as an output expression. A release adds its evaluator
  in front (`graphs.orElse(single)` for decision requirement graphs, #14), and the nodes it cannot take keep
  the reasons the others give.
- **Reasons by feature, with the release that brings them.** A not-supported node lists every `Feature` it
  needs (a FEEL construct, a built-in function by name, business knowledge models, invocations, boxed
  contexts, decision services, imports, structured inputs...), each in one area of the plan, found in its own
  logic and in what it requires. RESULTS.md counts them by feature and by the release after which a node has
  all it needs, so each release can see what it unlocks. A node's feature area comes from its content alone,
  so it stays put as Praecepta grows.
- **Only regressions fail the build.** `baseline.txt` lists the nodes that passed when it was last
  regenerated; one of them no longer passing fails the build, and a node that fails or is not supported never
  does. RESULTS.md and the baseline are regenerated together, deliberately (`-Dtck.update=true`), so a new pass
  is recorded by a reviewed change, not silently.

## Design decisions
The main decisions and their reasons (strict DMN semantics, bitsets instead of a trie or generated
code, the Java baseline, DMN as the exchange format, the licence) are recorded in
[MODERNISATION.md](MODERNISATION.md). Security design is described in
[SECURITY-ASSURANCE.md](SECURITY-ASSURANCE.md). Semantics follow DMN 1.5 and its TCK only; where both leave
a case open, what Praecepta does is recorded in [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md).

### List inputs (#25)
- **Declared beside the type, not as new types.** A list column keeps its element type
  (`ColumnType.STRING`) and is named in `DecisionTable.listInputs`; it is bound with
  `listInput(...)`, or `listColumnType(...)` for map inputs. New `ColumnType` constants would have
  doubled the enum and broken every exhaustive switch over it, for what is one flag.
- **"Some element passes" for cells, "no element passes" for `not(...)`.** This is what
  `list contains(?, "PT")` means in FEEL, so a table means the same in Praecepta and, once written as
  DMN, in Camunda or Drools. `not("PT")` reads "without PT"; "some element is not PT" would match
  nearly every list. "Every element passes" is not offered: no DMN form needs it yet.
- **Missing and empty lists follow FEEL**, not the "an empty list is a missing value" first
  sketched in the issue: `list contains(null, x)` is null, so a missing list matches only `-` and
  `null`; `list contains([], x)` is false, so an empty list matches every `not(...)`.
- **Missing elements follow FEEL too** (#84; 0.4 rejected them): `list contains` takes "any element of
  the semantic domain including null" (DMN 1.5, Table 75) and `some` is a three-valued `or` over the
  elements (Table 49), so a null element is false for a literal (`null = "PT"`) and unknown for a
  comparison. One accumulator per list suffices: a positive rule needs one element that passes, and a
  `not(...)` rule needs every element to make its inside false, so "unknown" and "true" may share the
  bits that rule a negated cell out. `? = null` stays a test of the list, and
  `list contains(?, null)` (`UnaryTests.NullElement`) is the test of an element.
- **DMN keeps strict semantics.** A plain `"PT"` against a list column would compare the whole list
  in other engines, so list entries are written with `list contains` and `some ... satisfies`, and
  only those forms are read back for a list column.
- **The analysis reads lists as sets of element classes** (#78; 0.4 and 0.5 left out the rules that
  test lists). The sets are exponentially many, so none of the questions lists them: two rules
  overlap when one element (or two, or none) makes a list both match; a list escapes some rules when
  every element avoids their positive cells and their `not(...)` cells are each ruled out by one
  element, which is a test per rule and element class. The gap search therefore needs neither a
  bound on the length of lists nor one on the number of classes, and describes a gap symbolically
  ("no element in ..., an element in ...") with an example list, rather than listing lists. Only a
  table with several list columns needs more: every list column but the last branches on the
  minimal sets of rules its lists leave (minimal, since fewer rules left only makes a gap or a
  witness easier), found by adding one element class at a time and dropping the sets that hold
  another. Their number is bounded (`ListAnalysis.MAX_OUTCOMES`, 1024); beyond it the rule in
  question, or the table's gaps, go unreported, so findings stay sound. Gaps are looked for among
  lists of values, as missing values are not searched for single values either; overlaps and
  unreachable rules count missing lists and missing elements too.

### Output expressions (#71)
- **A small subset, not a FEEL interpreter**: string, number and date time literals, input names, `+ - * / **`,
  unary minus and parentheses (FEEL's arithmetic expressions, DMN 1.5 grammar rule 4). That covers string building and arithmetic (and the TCK's case) while
  keeping the engine dependency-free; anything else is rejected by name, so a table never evaluates
  differently from other engines in silence.
- **Parsed and type-checked when the engine is built**, so a table that adds a string to a number
  fails at build time, not at the first evaluation; the expression text is kept in
  `PraeceptaOutput.expressions`, so `DmnWriter` writes back exactly what was read.
- **Evaluated only for matching rules**, through the same path as outputs that name an input; rules
  without such outputs keep their outputs converted once.
- **FEEL's null and decimal semantics**: a missing operand or a division by zero gives null,
  every result is rounded to 34 digits, ties to even (`FeelNumbers`; DMN 1.5, 10.3.2.3.1 and Tables 57,
  59 and 60), unary minus binds tighter than `**`, which associates to the left and takes any exponent
  (a power that is not whole is computed as `exp(y ln x)` with 60 digits, then rounded), and computed
  numbers are written without trailing zeros, since a FEEL
  number is a value, not a scale. Literal outputs and outputs that name an input keep the text they
  were given (`1.50`), so the output strategy sees what the table says.
- **Dates, times and durations (#85)** combine as Tables 57, 59 and 62 list them (`FeelArithmetic`): `type`
  checks the pair of operand types against those tables, so a table that adds two dates fails when it is
  built, and `evaluate` gives null where FEEL defines no value for the values themselves (a date-time with a
  zone minus one without, a month without the date's day, a result out of range). Their literals are read
  by `TemporalLiterals`, the reader cells use, so a cell and an output entry take the same forms. Two
  readings come from the DMN TCK's `0100-arithmetic`, which `praecepta-tck` evaluates in full
  (`OutputExpressionsTckTest`): a years and months duration scaled by a number keeps whole months, toward
  zero, and a date counts as UTC midnight with a zone when it meets a date-time. Times subtract as they
  compare: by instant with offsets (a zone with one fixed offset being one, #82), by local time when one has
  none, by local time in one zone id, and not at all otherwise, the cases DMN 1.5 leaves open (#86).
- **ANY compares evaluated outputs**, and compares number outputs by value, as FEEL's `=` does
  (those a table declares as numbers, or that a rule computes as one), so `1.50`, `1.5` and
  `price * 1` agree, at build time as at evaluation; output priority lists find number outputs by
  value too. An output with a priority list cannot be an expression, since its rank must be known
  when the engine is built.
