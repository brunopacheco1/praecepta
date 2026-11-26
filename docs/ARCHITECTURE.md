<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Architecture

Praecepta evaluates DMN decision tables, and the decision requirement graphs of tables, literal expressions, boxed
expressions and business knowledge models around them. It is a library, not a service: an application builds an engine, or a graph, once and calls it.

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
   "any"). In a DMN table (strict FEEL) a cell may also compare with another input (`> limit`), or be any
   FEEL unary tests (`> Limit * 2`, `? > 5 and ? < 10`; `FeelUnaryTests`, read where `CellParser` rejects a
   cell, see [Decision tables in FEEL](#decision-tables-in-feel-116)); the index sees such a cell as "any
   value", and the engine checks it on the rules the index leaves, with the inputs' values. The analysis then
   never counts those rules as overlapping or hiding others, and skips gaps. A column
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
     instant and a local time are a whole number of seconds apart, at most 14 hours, as FEEL's offsets
     lie in -14:00..+14:00 (#123; a getter may give a Java value beyond, which is no FEEL value). A column has
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
  support (an external function, rejected by design: [DMN conformance](DMN-CONFORMANCE.md#known-deviations)) is rejected
  with an error naming the decision, rule and column. Output entries may be any FEEL expression
  (`FeelExpression`): they are kept as written, parsed and typed when the engine is built, and evaluated only
  for matching rules, like outputs that name an input; `DmnWriter` writes them back unchanged, and so a cell
  beyond the simple unary tests, and an input expression that is an expression. Strict FEEL compares a list with a unary test
  as a whole, so a list column's entries are written with FEEL's list functions
  (`list contains(?, "PT") or some x in ? satisfies x >= 18`); `CellParser.parseFeelList` reads
  exactly those forms back into the same tests Praecepta's own syntax writes `"PT", >= 18`.
- `DmnWriter` writes a `DecisionTable` as standard DMN 1.3, which `DmnReader` reads back unchanged
  and which Camunda 8 and Drools evaluate identically (the benchmarks check this).

## Decision requirement graphs (#14)
A DMN model whose decisions require other decisions and input data is a decision requirements graph (DMN 1.5,
chapter 6). 0.7 evaluates graphs whose decisions are decision tables or literal expressions, which since 0.8 are full
FEEL ([FEEL expressions](#feel-expressions-116) below); later releases add the other kinds of logic as further
implementations of the same types (see *Growing it* below).

```
 DMN file ──(DmnReader.readModel)──► DecisionModel ──► DecisionGraph.Builder ──► DecisionGraph ──► evaluate(decision, inputData)
 code ──(DecisionGraph.builder())───────────────────────────┘        │
                                                                     ├──► validationReport(): cycles, missing requirements, type mismatches
 DecisionModel ──(DmnWriter.write)──► DMN                            └──► validationReport(decision): each table's own analysis
```

**The model** (`beans`, plain records, no behaviour): `DecisionModel(name, inputData, decisions, knowledgeModels,
types)`, `types` being the item definitions a literal expression's `instance of` may name; an
`InputData(name, FeelType type)`; a `Decision(name, requiredDecisions, requiredInputs, requiredKnowledge, DecisionLogic
logic)`, whose requirements are names of the model's elements (the information requirements of DMN 1.5, 6.3.13, and
its knowledge requirements, 6.3.14); a `BusinessKnowledgeModel(name, requiredKnowledge, FunctionDefinition logic)`
(since 0.10). `DecisionLogic` is a sealed interface, every kind with its declared `type()`: `Table(DecisionTable)`,
named after its decision, `LiteralExpression(text, declared FeelType)`, and the boxed expressions of
[Boxed expressions and business knowledge models](#boxed-expressions-and-business-knowledge-models-118), whose parts are
`DecisionLogic` in turn.
`FeelType` (`feel`) is the type of a value: `Simple(ColumnType)`, `ListOf(FeelType)`, `Context(components by name,
ordered)`, `RangeOf(FeelType)`, `FunctionOf(parameter types, result type)`, `Null`, or `Any` where the model declares
none. The model
rejects two elements of one name (6.3.7, 6.3.11), and nothing else: a model may be ill-formed, which the graph reports.

**Reading and writing.** `DmnReader.readModel` reads every `inputData` (its variable's `typeRef`, resolved through
item definitions to a `FeelType`: collections, components, nested to `MAX_TYPE_DEPTH` levels, with a check for types
defined in terms of themselves) and every `decision`: its information requirements, by `href` to an element of the
model (one to no element is kept as the id it names, which the graph reports as missing), and its logic, a decision
table, a literal expression or a boxed expression, each with the type the decision variable's `typeRef` declares
(`FeelType.ANY` for none), which its value is bound to; and every `businessKnowledgeModel`, with its knowledge
requirements and its `encapsulatedLogic`. Each literal expression is then checked to be FEEL with the names its element
may read in scope, and those its enclosing constructs bind, as a name such as `decision A 2.1` is one only there. A table is read as `read(dmn, decision)` reads it, except that what declares no
`typeRef` is typed from the model (`ModelTypes`): an input expression by the input data or decision it names (or the
component a path names), an output by the decision's variable (its component of the output's name, for several
outputs), else by the one type its output entries give, as FEEL types them, and else not at all (`typed` false: each
entry gives its own type). What Praecepta does not evaluate is rejected
whole with an `UnsupportedFeatureException`, so that callers fall back by its type, never by its message: its
`release()` and `issue()` name the release that plans it, or are null and 0 for what none does, external functions,
which Praecepta rejects by design. Knowledge sources, authority requirements, annotations and diagrams take no
part in evaluation and are ignored. A decision without logic is read as `DecisionLogic.NoLogic`, which the graph
blocks, and what requires it, without reporting an issue, but which a decision service may take as an input decision
([DMN conformance](DMN-CONFORMANCE.md), case 36). `readModel(dmn, imports)` (since 0.11) reads the models a model
imports, each once by namespace, through an `ImportResolver` (namespace and `locationURI`; a model found under another
namespace is rejected, so a wrong answer cannot recurse), and resolves an href into an imported namespace, or a
`typeRef` qualified by an import's name, to the imported element, under the name the import gives it
(`importName.elementName`). `DecisionGraph` flattens a model and those it imports into one (`ImportedModels`): every
element under its name in the graph (the path of imports that first reaches its model, so a model two others import is
one), each element keeping the names its own model reads, which `GraphAnalysis` maps to those names when it resolves
what the element reads. Decision services (since 0.11) are read with their output,
encapsulated and input decisions and input data, by reference. `DmnWriter.write(DecisionModel)` writes DMN 1.3
with ids by position (`input_data_0`, `decision_0`, and `decision_0_` before the ids of a decision's table, so the
tables of a model never share an id), item definitions for list and context types, and literal expressions as
written; a model it writes reads back as the same model, and writes the same again. `read(dmn)`, `read(dmn, decision)`
and `readAll(dmn)` read single tables as before.

**Building** (`DecisionGraph`, with the package-private `GraphAnalysis`, `GraphNodes` and `GraphValues`). Every
input data and decision gets a *slot*: input data first, then decisions, in the model's order. `GraphAnalysis` then:
1. checks each requirement exists and is of its kind (`MISSING_REQUIREMENT`);
2. parses each literal expression with the names of its decision's requirements in scope (a `FeelExpression.Scope`),
   so that names with spaces and keywords read as the requirements they are, and resolves each name a decision's
   logic reads (a table's columns and the inputs its cells compare with, a literal expression's free names) among
   that decision's requirements: the requirement so named, else the one named by its longest part before a dot, the
   rest being a path into its value (`Pricing.discount` in a table column), as `DecisionModel.elementOf` resolves
   names for every part of Praecepta; a name no requirement provides is a `MISSING_REQUIREMENT` (7.3.4), an
   external function a literal expression defines an `UNSUPPORTED_FEATURE` (by design), and a literal expression that
   is not FEEL an `INVALID_EXPRESSION`;
3. finds cycles among required decisions (Tarjan's algorithm with an explicit stack), reported with the cycle's
   path (`DECISION_CYCLE`), and orders the other decisions so each comes after those it requires (Kahn's algorithm);
4. works out types: an input data without a `typeRef` takes the type of the table columns that read it whole, when
   they agree; a table's value is its one output's type (`Any` for one that is not `typed`), or a context of its
   outputs, a list of that under a hit policy with several results and no aggregation (`Any` where such a table has a
   default output and its decision declares no type, as the default is a single value), a number for COUNT; a literal
   expression's is its
   `FeelExpression.type` from the types of the names it reads, `Any` where only the values tell; an expression whose
   type is `Null`, as one combining operands FEEL does not combine, is always null (`ALWAYS_NULL`, a WARNING, with
   the reason). Either value is then bound to the decision's declared type as DMN 1.5, 10.3.2.9.4, binds it
   (`GraphValues.Binding`): as it is where it conforms (10.3.2.9.2), a date to a date and time, a value to a list
   of one, a list of one to its element, checked when evaluated where its type is known only then, and anything
   else that does not conform to null (`ALWAYS_NULL`);
5. checks that each value a table reads fits its column (`TYPE_MISMATCH`): the column's type, or for a column of lists
   a list of it or a single value (a list of one); a path must exist in the type it reads; a cell that names a list
   (`Flu Symptoms`) asks whether the value is in it, so such an input is bound as a list of the table;
6. marks the decisions that cannot be evaluated: those with an issue, in a cycle, or requiring such a decision.

The graph's report lists those issues, ERRORs but for `ALWAYS_NULL`, a WARNING, whose decision is evaluated; each
issue kind is one of `ValidationReport.Kind`, which later layers will extend (a `switch` over it keeps a `default`).
`Builder.failOn(severity)` rejects the graph when its report, or
one of its tables', has issues that severe, and `logValidationReport` logs them once, as `PraeceptaEngine` does.
Each decision table becomes a `PraeceptaEngine<Object[], Object>` (`GraphNodes.TableNode`), whose inputs are the
graph's slots: each column is bound with a getter that reads its slot and path (`PraeceptaEngine.Builder.valueInput`,
package-private, of `Zoning.EITHER`, as map-based inputs are, so each table's validation report is the one the
framework integrations' map-based engines report), and whose output strategy gives the table's value (each output
converted from text to its type once, when the engine is built, for rules that compute nothing). A literal expression
is parsed once (`GraphNodes.LiteralNode`) and reads its names through resolved slots and paths;
`evaluateWithErrors` hands each node a receiver for FEEL's errors, which `evaluate` does not.

**Evaluating.** `evaluate(decision, inputData)` takes the decision's *plan*, worked out the first time and cached:
the slots of the input data it needs and the decisions it needs, itself last, in dependency order. It fills an
`Object[]` of slots with the input data, made FEEL values of their declared types (`GraphValues.normalise`: an
`Integer` becomes a `BigDecimal`, a `Period` a `YearsAndMonths`, text of a simple type its value, a map the declared
components of a context, and a value of no declared type copied, unmodifiable, its numbers `BigDecimal`s), then
evaluates each decision once into its slot. A column that reads a value of no declared type converts it then, as an
input data of the column's type is converted, or rejects it. A path reads a context's component, or of
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

**Growing it.** Each later layer adds to these types rather than replacing them: full FEEL (0.8, #116) gave
literal expressions a FEEL interpreter over the same slots, and `FeelType` FEEL's full type system; decision tables'
cells, input expressions and output entries adopted the same interpreter in 0.8 too ([Decision tables in
FEEL](#decision-tables-in-feel-116)); built-in functions (0.9, #117) are registered in
`BuiltInFunctions`, where the parser resolves their names, each a `BuiltInFunction` of its signatures and a body given
the evaluation that invokes it, one class per family (`StringFunctions`, `ListFunctions`...); boxed expressions and
business knowledge models (0.10, #118) are further `DecisionLogic` kinds, composed into FEEL's own nodes, and business
knowledge models elements whose slots hold functions, required through knowledge requirements
([Boxed expressions and business knowledge models](#boxed-expressions-and-business-knowledge-models-118)); decision
services and imports (0.11,
#119) add elements and qualified names to the resolution of step 2. The reader rejects each of them by name until
then.

## FEEL expressions (#116)
`FeelExpression` (`feel`) is the FEEL language of DMN 1.5, chapter 10, as one public entry point: `parse` a text
once, against a `Scope` of names (and a model's types), then `names()`, `unsupported()`, `type(types)` and
`evaluate(values)` (or `evaluate(values, errors)`) as often as needed, from any thread.

```
 text ──(FeelLexer)──► tokens ──(FeelParser, names in scope)──► Node tree ──► type(TypeEnv)   : FeelType, reasons for Null
                                                                    └───────► evaluate(Env)   : value, errors
```

- **Lexing** (`FeelLexer`) splits words, numbers, strings (escapes resolved) and symbols, drops comments, and
  records whether whitespace came before each token. A word is made of name characters (grammar rules 28 to 30),
  `'` included.
- **Parsing** (`FeelParser`) is recursive descent with precedence climbing for the binary operators (`or`, `and`,
  comparisons with `between` and `in`, `instance of`, `+ -`, `* /`, `**`), unary minus binding tighter than `**`,
  and paths, filters and invocations after a primary expression. **Names** are the hard part: FEEL names may hold
  spaces, keywords and `. / - ' + *`, so a name is matched, token by token, against the names in scope, the longest
  winning: the names given (indexed once by their first word, `FeelParser.Names`, so a graph of thousands of names
  parses each decision in time of its own length), the names the expression binds (iteration variables, `partial`,
  parameters, earlier context entries, a filter's `item`, a test's `?`) and the built-in functions where invoked.
  Whitespace must be where the name has a space and absent where it has none, so `Date-Time` is a name where it is
  in scope and a subtraction where only `Date` and `Time` are. A name in no scope is read as words up to a keyword,
  and is a free name the caller resolves, but inside a filter, where it may name an element's entry. Date and time
  functions of literal arguments are folded into literals while parsing (`TemporalLiterals`, as cells read them),
  an invalid one into a node that is null with an error; a built-in function into an invocation of it; an external
  function, which Praecepta does not evaluate, by design, into an `Unsupported` node, which `unsupported()` lists.
- **Evaluation** (`Node.evaluate(Env)`): each node evaluates its children in a scope (`Env`: frames of bound names,
  innermost first, then the values given), giving FEEL's values (`FeelValues` holds equality, order, `instance of`
  and the properties of Table 65; `FeelArithmetic` the arithmetic tables cells already used). Where FEEL defines no
  value the node gives null and reports an error to the receiver, if any. Function definitions evaluate to closures
  (`Closure`, a `FeelFunction`) over their scope; a context's entries are bound as they are evaluated, so a function
  in a context may invoke itself. Iterations (`Iteration`) loop with arrays of their own, however many variables.
- **Types** (`Node.type(TypeEnv)`) follow the same tree: `Any` where only values tell, `Null` where the value is
  null whatever the inputs, with the reason; `GraphAnalysis` turns `Null` into `ALWAYS_NULL`.
- **Limits.** Parsing recurses a few calls per level of nesting, and evaluation one call per level of the tree, so
  both are bounded: the tree's depth, each node weighted by the stack it takes (`Node.weight`: an iteration or a
  filter evaluates its children through calls of its own), at most 300, else a syntax error; the nesting of function
  bodies being invoked, with the expression's own depth, at most 800; iterations, a million an evaluation. Beyond
  either evaluation limit the value is null with an error. `FeelExpressionStackTest` checks all of them on a 512 KiB
  thread, and `FeelExpressionFuzzer` that any text parses or is a syntax error, and evaluates without throwing.
- **Unary tests** (`FeelUnaryTests`, `FeelParser.parseUnaryTests`): `-`, positive unary tests separated by commas,
  or `not(...)` of them, which must be the whole text (else the text is an expression invoking `not()`). Each test is
  a `Node.Test`, as `in` has them; `= e` and `!= e` are `Node.Equality` of `?` and `e`, so `= null` holds for a missing
  value. `isSimple()` tells the tests `CellParser` reads (literals, names, comparisons and intervals of them) from
  the others by the shape of the tree. `literals()` walks the tree with a stack of its own.

## Boxed expressions and business knowledge models (#118)
DMN 1.5's boxed expressions (10.2) are FEEL in boxes: a context is a context literal whose last entry may be its
result, a relation a list of contexts, a conditional `if`, an iterator `for`, `some` or `every`, a filter a filter, a
function definition a function, an invocation an invocation by named parameters. So they are composed into the same
node tree FEEL text parses to, and nothing evaluates them but the FEEL interpreter:

```
 DMN ──(DmnReader)──► DecisionLogic: Context, Invocation, FunctionDefinition, Relation, BoxedList, Conditional, Iteration, Filter
                           │  parts: DecisionLogic again; literal expressions as text; decision tables as DecisionTable
                           └──(FeelExpression.compose)──► Node tree ──► type(TypeEnv), evaluate(Env), as any FEEL expression
                                     │ literal expressions parsed with the names enclosing constructs bind (FeelParser.parse, locals)
                                     └ a decision table: Node.Embedded ──► GraphNodes.EmbeddedTable ──► its own PraeceptaEngine
```

- **Composing** (`BoxedComposer`, package-private, behind `FeelExpression.compose`). Each literal expression within is
  parsed with the names of the scope it is composed in (a decision's requirements) and those its enclosing constructs
  bind: a context's entries before it and itself (so a function an entry defines may invoke itself), a function's
  parameters, an iterator's variable (and `partial` in `for`), a filter's `item`. Those are the parser's locals, never
  free names, so the composed expression's `names()` are what it reads from outside, which the graph resolves as it
  resolves a literal expression's. An entry named as a requirement reads the requirement where its value is evaluated,
  before the entry is bound (the TCK's `1146-feel-context-put-function`). A part whose model declares a type is wrapped
  in `Node.Bound`, which binds its value as an argument is bound to its parameter (10.3.2.9.4: a list of one to its
  element, a single value to a list of one, a date to a date and time; anything else null, with an error). The TCK has
  a boxed conditional, filter and iterator be null, with an error, when their condition is not a boolean, where FEEL's
  forms take it as false: `Node.Strict` throws a stackless exception that the construct's `Node.Checked` turns into
  null ([DMN conformance](DMN-CONFORMANCE.md), case 34). The tree's depth is checked against FEEL's limit, as a parsed
  expression's is.
- **Decision tables within** keep what sets Praecepta apart. `compose` hands each to the caller as a
  `FeelExpression.TableSite` (the table, and the names in scope there); a graph builds it into a `PraeceptaEngine` of its
  own (`GraphNodes.EmbeddedTable`, the same `TableNode` a decision's table is), whose slots are the names it reads: one
  an enclosing construct binds is read from the FEEL scope by its name, the rest of the column's name a path into it
  (`Person.Gender`, where `Person` is a parameter, or the parameter `Person.Gender` itself); any other as the scope
  outside resolves it, a requirement and a path. Values are checked against the columns' types, as values of no
  declared type are. The table keeps its validation report, which its decision's or business knowledge model's report
  holds. `DmnReader` reads it with those names in scope, naming it after what holds it: the context entry, the business
  knowledge model, or else its decision.
- **Business knowledge models** are elements of the graph, after the decisions, each with a slot. Its logic is a function
  definition, composed with the names of the business knowledge models it requires, and its own, in scope; `GraphAnalysis`
  reports a name it reads that is neither its parameter nor one of those (`MISSING_REQUIREMENT`), and a requirement that
  is missing, of another kind or not evaluable, which blocks what requires it. Its type is the function type its
  variable declares, else the one its logic gives, worked out from those it invokes, any type where it invokes itself.
  `DecisionGraph` evaluates each function definition once, when it is built, into a closure whose names read the other
  functions, itself among them, lazily ([DMN conformance](DMN-CONFORMANCE.md), case 35), and every evaluation's slots
  start with them: a decision invokes one by name, as FEEL invokes any function value, or through a boxed invocation.
- **Writing** (`DmnWriter.write`) writes every part with its type, an item definition (`boxed_type_0`) for a type that
  is no simple type, a table within with ids and list item definitions of its own prefix, and business knowledge models
  after the decisions, with `encapsulatedLogic`; a model with a conditional, an iterator or a filter in DMN 1.5's
  namespace, as DMN 1.3 does not have them.

## Decision tables in FEEL (#116)
The bitset index and the build-time analysis are what set Praecepta apart, so decision tables adopted the full FEEL
language around them, not through them:

```
 cell ──(CellParser)──► simple unary tests ──► ColumnIndex: bitsets, analysed exactly as before
   └── rejected ──(FeelUnaryTests.parseCell)──► FEEL unary tests ──► the rule's cell is "-" for the index; applied after it
 input expression ── a name ──► a column, read from the input
                  └── an expression ──(FeelExpression)──► a computed column, its value from the inputs it reads
 output entry ── a literal or an input's name ──► as before (converted once, or the input's value)
              └── an expression ──(FeelExpression)──► typed when built (ALWAYS_NULL), evaluated for the rules that match
```

- **Cells.** A strict FEEL cell is first read by `CellParser`, whose simple unary tests the index evaluates. Where it
  rejects one, `FeelUnaryTests.parseCell` reads it as FEEL unary tests, unless they are simple themselves (a literal
  of another type than the column's, an interval that starts after it ends): that rejection stands, as the cell is
  wrong, not beyond the simple unary tests ([DMN conformance](DMN-CONFORMANCE.md), case 26). The engine (`TableExpressions`,
  package-private) keeps such a cell by rule and column, gives the index `-` in its place, and adds the rule to the
  mask of variable rules: `keepVariableMatches` applies it to the rules the index matched, with the column's value as
  `?` and the names it reads. A table of literal cells has no such rule, so its evaluation is unchanged; the analysis
  never counts such a rule as overlapping, hiding another or covering a default output, and looks for no gaps.
- **Input expressions.** A column whose name is no FEEL name (`CellParser.isName`) is an expression. With per-column
  bindings it needs none: the engine wraps its column reader (`computing`), which evaluates the expression from the
  values of the names it reads and makes the value one of the column's type (`GraphValues.normalise`, which rejects
  one of another type). A reader without computed columns is the reader itself, so the hot path is unchanged. A
  `DecisionGraph` binds such a column itself, evaluating the expression over the graph's slots.
- **Output entries.** An entry that is an expression is a `FeelExpression`, typed when the engine is built from the
  types of the columns and of the values a graph gives: `FeelType.NULL`, or a type that cannot be bound to the
  output's, is a WARNING (`ALWAYS_NULL`) with the rule's id. Its value is bound to the output's type: as it is for an
  output that is not `typed` (each entry its own type, 10.3.2.10 step 3c), as text for a string output, else
  `GraphValues.conform` (10.3.2.9.4). The output strategy gets the outputs as text (`PraeceptaOutput`), a computed
  number without trailing zeros and a list or context as FEEL writes it; a strategy that implements the package-private
  `ValueOutputs`, as the graph's `TableOutputs` does, also gets the values themselves.
- **Names.** Each expression is parsed with the names in scope the engine reads: its columns but those that are
  expressions (whose text would read as itself), the inputs bound to it, and the values a graph gives
  (`Builder.expressionInput`, raw FEEL values of their requirement's type). A name it reads that is none of those is
  added as an input of the engine, as an input a simple cell compares with is: a column of `-` cells, bound by its
  getter, or read from text with the type `columnType(...)` gives, else that of the first column or output that reads
  it (case 25). `nameReads` maps each name to its column, or to a value the graph gives, once.
- **In a graph** (`GraphAnalysis.tableReads`): the columns that name what they read and the inputs simple cells
  compare with are typed reads, checked against the requirement's type (`TYPE_MISMATCH`) as before; the names the
  table's FEEL reads, parsed with the requirements in scope, are read as they are; a column that is an expression is
  checked by its FEEL type, and one whose path reads a property of a date (`Birth.year`) is computed as FEEL reads it.
  A table with several results gives its default output as the single value it is (`PraeceptaEngine.isDefault`), bound
  to the decision's type (case 14).

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
   which take a time written either way. An output expression, and a cell in the full FEEL language,
   counts by its literals of the column's or output's type and the inputs of that type it reads
   (`FeelExpression.literals()`, `FeelUnaryTests.literals()`), since adding a duration keeps a time's or a date
   and time's zone. A column whose input expression is an expression is no component of the input record: the
   engine computes it from the inputs it reads, which are.

At runtime, the generated code needs only `praecepta-engine`. The tool writes the result with
`CompiledTable.write`, so every tool writes the same files.

**Decision requirement graphs** (#14). Before loading a DMN source as a table, `TableCompiler` reads it as a
decision model (`DmnReader.readModel`); `GraphCompiler` takes over when the decision the definition names, or,
when it names none, one of the model's decisions, requires another decision or is a literal expression. Anything
else compiles as before, byte for byte (the golden files of single tables are unchanged): a decision table that
requires only input data, a model without such decisions, and a file that is not a well-formed model in a way a
file of tables never had to be (two elements of one name, which `DmnWriter` writes for a table with a column of its
own name), and a model that uses what a later release plans (#116 to #119) but has the
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
  and reports. `GraphEvaluator` evaluates a decision through its model's `DecisionGraph` (#14), a decision
  whose value the test case gives being input data instead; the nodes it does not take (a model `readModel`
  rejects, a decision the graph's report blocks) go to `SingleDecisionEvaluator`, which evaluates one decision
  on its own, a decision table through `DmnReader` and the engine and a literal expression as an output
  expression: `graphs.orElse(single)`. A later release adds its evaluator in front the same way, and the nodes
  it cannot take keep the reasons the others give.
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
- **A small subset, not a FEEL interpreter, until 0.8**: string, number and date time literals, input names,
  `+ - * / **`, unary minus and parentheses (FEEL's arithmetic expressions, DMN 1.5 grammar rule 4), rejecting
  anything else by name, so that a table never evaluated differently from other engines in silence. Since 0.8 (#116)
  an output entry is any FEEL expression, evaluated by `FeelExpression`; `OutputExpression` was removed.
- **Parsed and typed when the engine is built**, so a table that adds a string to a number is reported at build
  time, not at the first evaluation: as an error until 0.8, as FEEL's null and a WARNING (`ALWAYS_NULL`) since; the
  expression text is kept in `PraeceptaOutput.expressions`, so `DmnWriter` writes back exactly what was read.
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
  checks the pair of operand types against those tables, so a table that adds two dates is reported when it is
  built (an error until 0.8, an `ALWAYS_NULL` WARNING since), and `evaluate` gives null where FEEL defines no
  value for the values themselves (a date-time with a zone minus one without, a month without the date's day, a
  result out of range). Their literals are read
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
