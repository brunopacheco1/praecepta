<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/); until 1.0, minor versions may change the API.

## [Unreleased]

## [0.7.0] - 2026-11-13

Decision requirement graphs, the first step of Praecepta's plan to grow from a decision-table engine into a full DMN
1.5 engine, one layer per release ([#120](https://github.com/brunopacheco1/praecepta/issues/120)): Praecepta now
evaluates DMN models whose decisions require other decisions and input data
([#14](https://github.com/brunopacheco1/praecepta/issues/14)), where each decision is a decision table or a literal
expression in the arithmetic that output entries compute. `DecisionGraph` evaluates any decision by name from the
input data it needs, each decision it requires once, in dependency order, and reports cycles, missing requirements and
type mismatches when it is built; `DmnReader.readModel` and `DmnWriter` read and write whole models; the Maven and
Gradle plugins generate a typed class per decision; and Spring Boot, Micronaut, Quarkus and `praecepta-integration`
evaluate a graph's decisions through `Decisions`. A model that uses what later releases bring (the full FEEL
language, built-in functions, business knowledge models and boxed expressions, decision services and imports) is
rejected when it is read, naming the release that plans it. The DMN conformance kit (TCK) is now scored whole
([#115](https://github.com/brunopacheco1/praecepta/issues/115)): of the 3,511 result nodes of its compliance levels 2
and 3, 454 pass (12.9 %), 7 fail and 3,050 need a feature Praecepta does not have yet, each listed with the release
that brings it, in [RESULTS.md](praecepta-tck/RESULTS.md). That measures how much of DMN 1.5 Praecepta covers, not how
well it does what it covers: of the 461 nodes it evaluates, 454 pass. No node of the kit needs graphs alone, so they
add no pass yet; the decision-table checks still pass 83 of 86 (the other three also invoke business knowledge models,
0.10), and the build now fails only on a regression from a committed baseline. One fix: rules reached only by a time
with a zone id that no cell names are no longer reported unreachable
([#114](https://github.com/brunopacheco1/praecepta/issues/114)). Build and test dependencies were updated (Guava
33.8.0 and Groovy 3.0.25 in tests, Jackson 2.23.0 in the benchmarks, the versions-maven-plugin 2.25.1); no published
module's dependencies changed. Read *Changed* and *Fixed* before upgrading if a framework integration loads DMN files
in which a decision requires another decision (its decisions now evaluate through the graph, from the model's input
data, and the graph's errors stop startup), your code switches over `ValidationReport.Kind` exhaustively or
deconstructs `integration.Decision` with a record pattern, or a map-based time column of yours has cells that name no
zone id with a changing offset (its report now lists a time in another zone as a gap, a WARNING).

### Added
- **Decision requirement graphs** ([#14](https://github.com/brunopacheco1/praecepta/issues/14)): `DecisionGraph`
  evaluates a DMN model whose decisions require other decisions and input data (DMN 1.5, chapter 6), where each
  decision's logic is a decision table or a literal expression in the subset output entries take (literals and names
  joined with `+`, `-`, `*`, `/` and `**`). `DecisionGraph.read(dmn)` reads a file, and
  `evaluate(decision, inputData)` evaluates any decision by name from the input data it needs, each decision it
  requires once, in dependency order, with nothing parsed per call; `evaluate(decisions, inputData)` evaluates several
  at once. A decision's value is given to the decisions that require it under its name: a table with one output gives
  that output's value, one with several a context (`Map`) whose components a decision reads by path
  (`Pricing.discount`), and one with several results (RULE ORDER, OUTPUT ORDER, COLLECT without an aggregation) a
  list, which a list input takes (`Checks.code` being the list of each result's `code`, DMN 1.5, 10.3.2.5); no match
  without a default output gives null. Input data are typed by their `typeRef`, a built-in type or an item definition
  (a collection, or a structure whose components are read by path, `Loan.principal`), and are given as Java values or,
  for simple types, as text. `requiredInputData(decision)`, `inputData()`, `type(decision)` and `decisions()` describe
  the graph, for the build plugins and framework integrations to build on (see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)). `DecisionGraph.builder()` assembles a graph in code from input data,
  `DecisionTable`s and literal expressions, each decision requiring what it reads.
- **A graph is checked when it is built** ([#14](https://github.com/brunopacheco1/praecepta/issues/14)): its
  validation report (`DecisionGraph.validationReport()`) has three new issue kinds, all ERRORs:
  `ValidationReport.Kind.DECISION_CYCLE` (decisions that require one another, directly or indirectly: "a Decision
  element SHALL not require itself", 6.3.7), `MISSING_REQUIREMENT` (a required decision or input data the model does
  not have, or a name a decision reads that none of its information requirements provides, 7.3.4) and `TYPE_MISMATCH`
  (a value of another type than the decision table input it feeds, a path into a value without such a component,
  operands a literal expression cannot combine, or a literal expression's value of another type than its decision
  declares). Such a decision, and every decision that requires it, cannot be evaluated
  (`InvalidDecisionTableException`), and the others can; a literal expression whose value does not conform to its
  decision's declared type is the exception, as DMN 1.5 defines it to be null (10.3.2.9.4). `Builder.failOn` rejects a
  graph with issues at a severity, and each decision table keeps its own analysis (`validationReport(decision)`).
- **Decision models in DMN** ([#14](https://github.com/brunopacheco1/praecepta/issues/14)): `DmnReader.readModel(dmn)`
  reads a whole model (`beans.DecisionModel`: its input data, its decisions, their information requirements and their
  logic, `beans.DecisionLogic.Table` or `LiteralExpression`), and `DmnWriter.write(DecisionModel)` writes one back as
  DMN 1.3, which reads back as the same model. The types of input data and decisions are `feel.FeelType`: a simple
  type, a list, a context of components, or any type. What later releases bring is rejected when the model is read,
  with a message that names the feature and its issue: business knowledge models, knowledge requirements and boxed
  expressions (0.10, [#118](https://github.com/brunopacheco1/praecepta/issues/118)), FEEL beyond the subset (0.8,
  [#116](https://github.com/brunopacheco1/praecepta/issues/116)) and function calls (0.9,
  [#117](https://github.com/brunopacheco1/praecepta/issues/117)), decision services and imports (0.11,
  [#119](https://github.com/brunopacheco1/praecepta/issues/119)). `DmnReader.read(dmn)`, `read(dmn, decision)` and
  `readAll(dmn)` read single tables as before. What DMN 1.5 and the TCK leave open about graphs, and what Praecepta
  does there, is recorded in [docs/DMN-CONFORMANCE.md](docs/DMN-CONFORMANCE.md) (cases 13 to 17).
- **Generated classes for decision requirement graphs** ([#14](https://github.com/brunopacheco1/praecepta/issues/14)):
  the Maven and Gradle plugins (and `praecepta-compiler`) compile a DMN file whose decisions require other decisions,
  or are literal expressions, into a model class (`CreditModel`, named after the model or `name`) that loads the model
  once into a `DecisionGraph`, and per decision an input record of the input data it needs, directly or through the
  decisions it requires (`MonthlyLimitInput`), and a class whose `evaluate` evaluates it through the graph and gives
  its value as its Java type: `String`, `BigDecimal`, `LocalDate`..., a `List` for a list, a `<Decision>Output` record
  for a context (a table with several outputs), a record of the model class for a structured input data
  (`CreditModel.Loan`). `decision` compiles one decision of such a file. The graph's issues fail the build at
  `failOn`. A decision table that requires no other decision, and every file without such decisions, compiles exactly
  as before. `CompiledTable` gains `model()`, `reports()` (every report by the class it concerns), `classNames()` and
  `summary()`; its `table()` is null for a model.
- **Decision requirement graphs in the framework integrations**
  ([#14](https://github.com/brunopacheco1/praecepta/issues/14)): in Spring Boot, Micronaut and Quarkus, and in
  `praecepta-integration`, every decision of a file whose decisions require other decisions, or are literal
  expressions, is a decision of the `Decisions` bean, and `decisions.evaluate(name, inputData)` evaluates it from the
  model's input data, as text by name (a structured one by its components' paths, `Loan.principal`), through the
  decisions it requires, giving its value as the maps a table gives. The new `GraphModel` holds a file's graph
  (`Decisions.models()`, `Decision.model()`); the graph's issues fail startup, or the Quarkus build, at `fail-on`, and
  are logged once below it (`Decision model Credit: ERROR ...`); the report endpoints and `Decisions.describe()` list
  each model under `models` and describe each of its decisions' logic, type, requirements and input data. Each
  decision table is still an engine bean, which evaluates the table alone from its columns; a literal expression has
  no engine bean (`Decision.table()` and `engine()` are null for it).
- **The whole DMN TCK, scored** ([#115](https://github.com/brunopacheco1/praecepta/issues/115)): `praecepta-tck` now
  runs every model of compliance levels 2 and 3 at the pinned commit (148 models, 3,511 result nodes; the test files'
  commented-out nodes are not counted), not only the decision-table checks, and scores each result node as passed,
  failed or not supported, with every feature an unsupported node needs (a FEEL construct, a built-in function by
  name, business knowledge models, invocations, boxed contexts, decision services, imports, structured inputs...) and
  the release of the DMN 1.5 plan ([#120](https://github.com/brunopacheco1/praecepta/issues/120)) that brings it. 454
  of the 3,511 pass (12.9 %): Praecepta evaluates a decision through its model's decision requirement graph
  ([#14](https://github.com/brunopacheco1/praecepta/issues/14)) where `DmnReader.readModel` reads the model, and
  otherwise on its own, a decision table as before and a literal expression as an output expression, and 454 of the
  461 nodes it evaluates pass; no node of the kit needs graphs alone, so they add no pass.
  [RESULTS.md](praecepta-tck/RESULTS.md) keeps the decision-table checks (still 83 of 86) and adds the totals by
  compliance level, by feature area, by feature, by model, and how many nodes each release would complete. The build
  now fails only on a regression, a node that passed in the committed baseline (`praecepta-tck/baseline.txt`) and no
  longer does; a node that fails or is not supported never fails it, and RESULTS.md and the baseline are regenerated
  together, deliberately, with `-Dtck.update=true`. The run takes about a second. How a model is evaluated is one
  seam, `ModelEvaluator`, which later layers plug into as graphs do. The run found that the engine reads
  `time("13:20:00+19:00")` as the local time `13:20:00` and accepts `date and time("+99999-12-01T11:22:33")`, where
  the kit expects errors, and that `0008-LX-arithmetic`'s payments, a power that is not whole, which graphs now
  evaluate, are computed to FEEL's 34 digits where the kit gives 15; they are reported as failures.

### Changed
- **The framework integrations read each DMN file as a decision model** (#14). **Upgrade note:** in a file where a
  decision requires another decision, `Decisions.evaluate` and `Decision.evaluate` now evaluate that file's decisions
  from the model's input data through the graph, where 0.6 evaluated each table alone from its columns, the value of a
  decision it requires taken as an input; the table's engine bean, `Decision.engine()` and `Decisions.engine(name)`
  still do the latter. Such a file is also checked as a graph (a cycle, a missing requirement, a type mismatch are
  ERRORs, which stop the startup at the default `fail-on`), and its literal expression decisions, which 0.6 ignored,
  are decisions now (a file of literal expressions alone, which 0.6 rejected as having no decision table, loads).
  Files of decision tables that require no other decision load as before, `Decisions.describe()` included, and so does
  a file with a business knowledge model, a decision service, an import, a boxed expression or a literal expression
  beyond the expression subset: its decision tables load alone, as in 0.6; such a file without a decision table fails
  with a message that names the release planning what it uses, rather than `no decision table`.
- **`Decision` has a sixth component, `model`** (#14), the graph the decision belongs to, null for a table read alone;
  the five-component constructor remains. **Upgrade note:** a record pattern over `Decision` needs the sixth
  component.
- **`ValidationReport.Kind` has three more constants**, `DECISION_CYCLE`, `MISSING_REQUIREMENT` and `TYPE_MISMATCH`
  (see *Added*). **Upgrade note:** code that switches over `ValidationReport.Kind` exhaustively must handle them;
  reports of decision tables never hold them, so the results and reports of existing tables are unchanged.
- **The build plugins check that no two tables generate any one class** (#14), the input and output records included,
  where they compared only the decision classes; and they log `Compiled decision model ...` for a model.
- **Build and test dependencies**: Guava 33.8.0 and Groovy 3.0.25 (tests only), Jackson 2.23.0 (the benchmarks) and
  the versions-maven-plugin 2.25.1. No published module's dependencies change.

### Fixed
- **Rules reached only by a time with a zone id are no longer reported unreachable**
  ([#114](https://github.com/brunopacheco1/praecepta/issues/114)): a time column whose inputs can be times with a zone
  id (map-based inputs, as the framework integrations read them, and the elements of a list) but whose cells name no
  zone id with a changing offset left such times out of the validation report's overlaps, unreachable rules and gaps,
  though the completeness search counted them since 0.6.0. Such a time compares with none of those cells, which is
  FEEL's null, so neither a test nor its `not(...)` matches it (docs/DMN-CONFORMANCE.md, case 9): earlier rules that
  together matched every other time, such as `< time("12:00:00")` and `>= time("12:00:00")`, made a later rule look
  unreachable (`UNREACHABLE_RULE`, a WARNING) although `10:30@Europe/Paris` reaches it. The column's index now has a
  class for a time in a zone no cell names wherever the column takes one, and every analysis visits it as it does the
  others; the property tests' reference does the same. Evaluation is unchanged, and so are tables bound with typed
  getters, columns of other types and time columns whose cells name such a zone id, which had the class already. **The
  report changes** for map-based time columns, and time list columns, whose cells name no such zone id: unless a rule
  accepts any value there (`-`, `!= null`), a time in another zone is now reported as a gap
  (`cutOff in {[time("12:00:00")..+inf), any time with a zone id no cell names}`, a WARNING, which stops a startup
  only where `fail-on` is `WARNING`), and a rule that only such a time reaches is no longer reported unreachable.
  Overlaps cannot change, as a cell that matches such a time matches every time. Declared input values that rule such
  times out, such as `[time("00:00:00")..time("23:59:59")]`, leave the report as it was.

## [0.6.0] - 2026-11-13

DMN 1.5 in single tables: Praecepta now reads the FEEL that 0.5 rejected in cells and output entries, and
analyses the rules it skipped. Cells take `=` and `!=` tests, numbers such as `.5` and `1.2e3`, at-literals
(`@"2025-01-31"`) and every form of the date and time functions, comments, and times with a zone id
(`time("10:30:00@Europe/Paris")`); output entries compute with dates, times and durations
(`due + duration("P14D")`); list inputs may hold missing elements; and the validation report covers rules
that test list columns and reports a default output that no input can reach. What DMN 1.5 and its TCK leave
open, and what Praecepta does there, is recorded in [docs/DMN-CONFORMANCE.md](docs/DMN-CONFORMANCE.md). Two
fixes follow DMN 1.5 where 0.5 did not: SUM, MIN and MAX are null when a matching rule's output is missing,
and a `null` default output value is a default; and the integrations log each validation issue once. The
Gradle plugin is now on the Gradle Plugin Portal, so applying it needs no `pluginManagement` repository, and
CI builds a Spring Boot, a Micronaut and a Quarkus application as native executables, which found a bug, now
fixed, with `@Named` engines under Spring AOT. The DMN conformance kit (TCK) still passes 83 of its 86
decision-table checks; the other three need decision requirement graphs
([#14](https://github.com/brunopacheco1/praecepta/issues/14)). Its arithmetic test cases are now run as
output expressions too: of their 1,101 checks Praecepta reads 489, of which 276 give the kit's result, 212
are rejected when the engine is built where the kit expects null, and one differs. Read *Changed* and *Fixed* before upgrading if your tables have unquoted string cells that start with
`=` or `!=` (now tests, not text), declare a default output although every input matches a rule (now an
ERROR, which fails builds and startups), test list columns (their overlaps are now found, and are errors
under UNIQUE and ANY), receive lists holding null elements (now evaluated, not rejected), declare `null`
default output values (no match now gives an output of nulls instead of none), or aggregate with SUM, MIN or
MAX over rules whose output may be missing (now null, not the aggregate of the others); and if another XML
parser on the class path read your DMN files (`DmnReader` now always uses the JDK's), or your code
deconstructs `DecisionTable.OutputColumn` or `UnaryTests.Equals`, switches over `UnaryTests.Test` or
`ValidationReport.Kind`, or assigns `FeelTemporals.parseTime` to a `Temporal`.

### Added
- **`=` and `!=` tests in cells** ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): DMN 1.5
  lets a unary test start with `=` or `!=` (grammar rule 7.a), which cells, value lists and DMN files
  rejected (`'= 18' is not a number`). `= 18` is the test `18`, `= null` the test `null`. `!= x` is
  `not(= x)` for one value (10.3.2.15: "FEEL(e1 != e2) is FEEL(not(e1 = e2))"), so it holds for a missing
  value, which does not equal `x`, and `!= null` holds for any value that is not missing; unlike `not(...)`,
  which goes around a whole cell, it is one test among others (`!= 0, > 10`), and it may name another input
  in a DMN file (`!= home`). On a list column it holds when some element differs; `!= null` is rejected
  there in Praecepta's syntax, where `null` tests the list itself and the other tests its elements (write
  `not(null)` for a list that is there; a DMN file writes the elements' test
  `some x in ? satisfies x != null`). In a DMN file, `= home` of an input that holds a list is rejected when
  the engine is built, as `!= home` is: it is FEEL's `? = home`, an equality of a value and a list, which is
  null (Tables 52 and 53), while the bare `home` asks whether the value is in the list
  (`list contains(home, ?)`, Table 55); `UnaryTests.Equals.explicit` tells the two apart (see *Changed*), and
  `DmnWriter` writes `= home` back as written. `UnaryTests.NotEqual`
  holds it; `DmnWriter` writes it back as `!= x` (on a list column, `some x in ? satisfies x != "PT"`, which
  `DmnReader` reads), and the index, the validation report and every integration treat it as the engine
  does.
- **Date time literals in every form DMN 1.5 has**
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): at-literals (grammar rule 65;
  `>= @"2019-03-31"`, `< @"08:30:00"`, `@"P1D"`), whose string's syntax says whether they are a date, a
  time, a date and time or a duration, and the other forms of the date and time functions (rule 60, Table
  72): `date(2025, 1, 31)`, `date(date and time(...))`, `time(8, 30, 0)`,
  `time(8, 30, 0, duration("PT1H"))`, `time(date and time(...))`, `date and time(date(...), time(...))`, and
  `date and time("2025-01-31")`, a date and time string without a time, which is that date at midnight
  (10.3.4.1; the TCK's `0068-feel-equality` has it equal `date and time("2025-01-31T00:00:00")`). Cells,
  value lists and DMN entries read them in columns of their type, and reject one of another type by name
  (`'@"08:30:00"' is a time, not a date`); `DmnWriter` writes them as `date("...")`, `time("...")`,
  `date and time("...")` and `duration("...")`. Input values read from text are unchanged: a date-time input
  is still written with its time.
- **Numbers that start with their fraction or have an exponent**
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): a number in a cell, a value list or a DMN
  entry may be written `.5` or `1.2e3` (`1E-2`, `2.5E+1`), as FEEL's numeric literal allows (grammar rule
  35; 10.3.2.3.1: "FEEL supports literal scientific notation, e.g., 1.2e3"), as output expressions already
  read them. `DmnWriter` writes such a number as plain digits (`1200`). A number beyond FEEL's decimal128
  (an exponent past 6144 or -6176, 10.3.2.3.1) has no FEEL value and is rejected, in cells, value lists, DMN
  entries, the arguments of the date and time functions and output expressions alike
  (`'1e999999999' is beyond FEEL's numbers (decimal128)`): written out digit by digit, as the validation
  report and `DmnWriter` write numbers, `< 1e999999999` would exhaust the heap.
- **Comments** ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): `//` to the end of the line
  and `/* ... */`, which DMN 1.5 allows in FEEL (10.3.1.2: "Java-style comments can be used"), are ignored
  outside string literals, in cells, value lists, DMN entries, default output values (`null // no fee` is a
  `null` default, [#92](https://github.com/brunopacheco1/praecepta/issues/92)) and output expressions
  (`< 18 // minors`). In Praecepta's syntax (Excel, CSV and code), a string column keeps them as text, as it
  always has, since an unquoted word there may hold `//` (a URL). `DmnWriter` leaves them out.
- **Times with a zone id** ([#82](https://github.com/brunopacheco1/praecepta/issues/82)): a time may name a
  zone instead of an offset (DMN 1.5, 10.3.4.1: a local time "followed by the character "@", followed by
  [...] a time zone identifier in the IANA Time Zones Database"), as in `time("10:30:00@Europe/Paris")`,
  `@"10:30:00@Europe/Paris"` or, in Praecepta's syntax, `10:30:00@Europe/Paris`; cells, value lists, DMN
  files and map-based inputs rejected it. Such a time is a `feel.ZonedTime` (a local time and a zone id:
  `OffsetTime` cannot hold a zone without its offset, which a zone id has only on a date), bound with
  `PraeceptaEngine.Builder.zonedTimeInput`, written back by `FeelLiterals` and `DmnWriter`, and generated as
  a `ZonedTime` component by the Maven and Gradle plugins and the Quarkus extension when the values written
  for a time column have zone ids (some of them may instead have an offset, or a zone with one fixed offset).
  Two such times in the same zone compare by local time, which is all the TCK asks (`0068-feel-equality`:
  `time("10:30:00@Europe/Paris") = time("10:30:00@Europe/Paris")`). A time in a zone with one fixed offset
  (`Etc/UTC`, `Etc/GMT-2`, `UTC`) is the time at that offset, as DMN 1.5's example of `time(from)` has it
  (Table 72: `time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC")` is true), and as the TCK has it
  for date-times (`0068-feel-equality`: `date and time("2018-12-08T00:00:00+00:00") = date and
  time("2018-12-08T00:00:00@Etc/UTC")`): read from text, a cell, an output entry or a date and time, it is an
  `OffsetTime` (`FeelTemporals.parseTime("00:01:00@Etc/UTC")` is `00:01Z`), and a `ZonedTime` in such a zone
  that a getter gives is matched, compared and subtracted as one, so a `zonedTimeInput` column also takes
  times at an offset, among which the validation report looks for overlaps and gaps. Such a time is written at
  its offset (`DmnWriter` writes `time("00:01:00Z")`, which reads back equal, and an output that names or
  computes it gives `00:01:00Z`), and the generated component of a time column whose zone ids all have a fixed
  offset is an `OffsetTime`. `FeelTemporals.timeIn` and `withFixedOffset` make such a time, and generated code
  reads time outputs back with `FeelTemporals.parseOffsetTime` and `parseZonedTime`, which take either form.
  Every other comparison of a time with a zone id whose offset changes, with a time in another zone, at an
  offset or without a zone, is left open by DMN 1.5 and by the TCK ("pending RTF equality comparisons with
  zones clarification"); Praecepta does not invent one: such a comparison is FEEL's null, so neither a test
  nor its `not(...)` holds, an interval's ends must be in one zone, and a COLLECT MIN or MAX of such times in
  two zones is null (FEEL's `min` and `max` take comparable items, Table 75, and a built-in function is null
  outside its domain, 10.3.2.16). This choice is recorded in [DMN conformance](docs/DMN-CONFORMANCE.md). The
  index gives each zone id the cells name a line of local times and the other zone ids one class; a
  map-based column whose cells name no zone id takes such a time too, matching only the cells that accept
  any value, and keeps the validation report it had. `FeelTemporals.comparable(a, b)` says whether two
  values compare.
- **Output expressions over dates, times and durations**
  ([#85](https://github.com/brunopacheco1/praecepta/issues/85)): output entries combine dates, times,
  date-times and durations as DMN 1.5 defines it (Tables 57, 59 and 62), where they took strings and numbers
  only and rejected the table when the engine was built
  (`'due' is a date; output expressions combine strings and numbers`). `due + duration("P14D")` is a date,
  `returned - sent` a days and time duration (as are two times or two dates subtracted), `start + @"P1Y"`
  keeps the type of `start`, durations of a kind add, subtract and divide to a number, a duration scales by
  a number, and `-elapsed` is `elapsed * -1`. Their literals are those cells read, at-literals and the date
  and time functions in every form, through one reader. The output is null where FEEL defines no value: a
  date and time with a zone minus one without (Table 57), a date plus a month that does not have its day
  (`date("2025-01-31") + duration("P1M")`, as `date(2025, 2, 31)` is null), a division by zero, a result out
  of range. A years and months duration scaled by a number keeps whole months, toward zero, and a date
  meeting a date and time counts as UTC midnight (10.3.2.3.5), as the DMN TCK's `0100-arithmetic` expects.
  Times subtract as they compare: by instant when both have an offset, by local time when one has none or
  both have a zone id in one zone, and not at all for a time with a zone id and any other (so two times at
  offsets far apart can be more than a day apart; a time in a zone with one fixed offset is a time at that
  offset, [#82](https://github.com/brunopacheco1/praecepta/issues/82)), and a date and time with a zone id moved by months onto a
  local time its zone skips moves forward by the gap; DMN 1.5 leaves those cases open, so they are recorded
  in [DMN conformance](docs/DMN-CONFORMANCE.md). Types FEEL does not
  combine (`due + 1`, `elapsed + tenure`) are still rejected when the engine is built, now naming both types
  (`+ combines a date and a number, which gives null in FEEL`). The engine, the classes the build plugins
  generate (which evaluate through it, with a component that has the zone of the expression's operands:
  `start + duration("PT8H")` of a `ZonedDateTime` input is a `ZonedDateTime`), `praecepta-integration`'s map
  engines and `DmnWriter`, which writes the entries back as read, agree. `praecepta-tck` now also evaluates the TCK's `0099-arithmetic-negation`
  and `0100-arithmetic` literal expressions as output expressions (`OutputExpressionsTckTest`): of their
  1,101 checks, Praecepta reads 489: 276 give the kit's result, 212 are rejected when the engine is built
  where the kit expects null (operands of types FEEL does not combine, counted apart, as they give no result),
  and one differs, a power that is not whole, which the kit gives to 13 digits where FEEL's numbers have 34; the other 612 use functions, lists,
  contexts, booleans, `null` operands or hour 24. The decision-table results in `RESULTS.md` are unchanged.
- **Missing elements in list inputs** ([#84](https://github.com/brunopacheco1/praecepta/issues/84)): a list
  may hold missing (null) elements, as FEEL lists may, where 0.4 and 0.5 threw `InvalidInputException`
  ("holds a missing value"). They get FEEL's three-valued answers (DMN 1.5, Tables 49, 50 and 75): a literal
  is false for a missing element and a comparison or an interval unknown, and a cell's tests are joined over
  the elements with FEEL's `or`, so `list contains(?, "PT")` matches `["PT", null]`,
  `not(list contains(?, "PT"))` matches `["BR", null]`, `some x in ? satisfies x >= 18` matches
  `[20, null]`, and `not(some x in ? satisfies x >= 18)` does not match `[10, null]` (`false or null` is
  null). A DMN cell may ask for a missing element with `list contains(?, null)`, read by
  `CellParser.parseFeelList` as the new test `UnaryTests.NullElement` and written back by `DmnWriter`;
  Praecepta's syntax has no form for it, as `null` there asks whether the list itself is missing
  (`? = null`), which it still does. A null element of a `Collection`, a blank one (by Praecepta's convention
  that blank text is missing, as for single values, though FEEL's `""` is a string), and `null` in a list's
  text (`["PT", null]`, `BR, null`), is a missing element; the text `null` alone is still no list. Declared input
  values apply to the elements that are there. A cell naming a list input (`Flu Symptoms`) is
  `list contains(Flu Symptoms, Symptom)` (Table 55), so a missing value is in a list that holds a missing
  element. The engine, the classes the Maven and Gradle plugins generate and the framework integrations' map
  engines all evaluate them alike.
- **Analysis of rules that test list columns**
  ([#78](https://github.com/brunopacheco1/praecepta/issues/78)): the validation report, and so the Maven and
  Gradle plugins' build-time check, the Quarkus build and the Spring Boot and Micronaut startup checks, now
  look at rules whose cell in a list column is not `-`, which 0.4 and 0.5 left out. A list counts as the set
  of its elements' classes in the column's index, so rules `"PT"` and `"BR"` overlap under UNIQUE (on
  `["PT", "BR"]`), `[20..30]` after `>= 18` can never be selected under FIRST, and two rules `"PT"` and
  `"BR"` with declared values `"PT","BR"` leave the gap `nationalities = []`. Overlaps and unreachable rules
  count every list, missing lists and lists holding missing elements included; gaps are searched among lists
  of values (as for single values, missing ones are not) and described by the elements such lists hold,
  `nationalities has no element in "PT", nationalities has an element in "ES"`, with an example list
  (`{nationalities=[ES]}`). No bound on the length of lists is needed: each question is decided per rule and
  element class. A table with several list columns branches on the minimal sets of rules the lists of each
  but the last can leave; past 1024 of them a rule is not reported unreachable, or the table's gaps are not
  reported, rather than reported wrongly. The report lists the first 100 overlapping pairs, in rule order,
  as it lists the first 100 gaps: rules that each name another value of a list column all overlap, so a UNIQUE
  table of 2,000 such rules has nearly 2,000,000 pairs. Which earlier rules cover a rule on the last list
  column is found with a few mask operations per rule and element class, so a list column with a value per
  rule builds as quickly as a string column. Tables without list columns get the same report as before, but
  for the cap on overlapping pairs (see *Changed*).
- **A default output on a complete table is reported**
  ([#91](https://github.com/brunopacheco1/praecepta/issues/91)): DMN 1.5, 10.3.2.10, "A complete decision
  table SHALL NOT specify a default output value", since no input could reach it. The validation report has
  a new issue kind, `ValidationReport.Kind.UNREACHABLE_DEFAULT_OUTPUT`, an ERROR, when a table declares a
  default output (`defaultOutputEntry`, or `Builder.defaultOutput`) and every input matches a rule: a search
  over the index's classes, like the other analyses, that unlike the gap search also visits a missing value
  in every column (which the engine accepts, and `-` matches) and the strings no rule mentions in a column
  without declared values. So a table whose rules cover every declared value but not a missing one is not
  reported: its default is what a missing input gives. A list column is searched as every list it takes, a
  missing one, the empty one and lists holding missing elements included (with the list analysis of
  [#78](https://github.com/brunopacheco1/praecepta/issues/78)), and a time column of map-based inputs, or of
  lists, whose cells name no zone id as every time it takes, one with a zone id
  ([#82](https://github.com/brunopacheco1/praecepta/issues/82)) included, which only the cells that accept
  any value match. Rules that compare with another input are left out, as from the other analyses, so a
  table complete only through them is not reported. Its message is
  `ERROR every input matches a rule, so the default output is never given (a complete table declares no default output: DMN 1.5, 10.3.2.10)`,
  and `Decisions.describe()` shows it with the kind `UNREACHABLE_DEFAULT_OUTPUT`. The TCK's tables with
  default outputs are not complete, so its results are unchanged.
- **The Gradle plugin on the Gradle Plugin Portal**
  ([#93](https://github.com/brunopacheco1/praecepta/issues/93)), where Gradle looks for plugins by default,
  so `plugins { id("io.github.brunopacheco1.praecepta") version "..." }` needs no `pluginManagement`
  repository. The Portal serves the same jars and POM as Maven Central: the release uploads the files Maven
  built, fetched from Maven Central, and does not rebuild them with Gradle.
- **[docs/DMN-CONFORMANCE.md](docs/DMN-CONFORMANCE.md)**
  ([#86](https://github.com/brunopacheco1/praecepta/issues/86)): the cases DMN 1.5 and the TCK leave open,
  with what each says and what Praecepta does, kept in the repository rather than in an issue, and linked
  from the README and `praecepta-tck/RESULTS.md`: `-` and a missing input, values outside the declared input
  values, intervals whose ends are missing or out of order, `=` between a time with a zone and one without,
  times at different offsets, and, new in 0.6, a `null` default beside output values, whether a missing
  input counts towards a complete table, how a time with a zone id compares with other times, and how two
  times in different zones subtract. COUNT with no match is marked settled (0.5.0). It also says how
  Praecepta returns DMN's null result: no output, or an output whose value is null. The README no longer
  calls those choices FEEL's.
- `PraeceptaEngine.Builder.logValidationReport(boolean)`: whether `build()` logs the validation report as a
  warning through `System.Logger`, as it always did and still does by default. Turn it off when you report
  `validationReport()` yourself ([#95](https://github.com/brunopacheco1/praecepta/issues/95)).
  `praecepta-integration` gains `Decisions.load(sources, failOn, logReports)` and
  `Decision.of(location, table, logReport)` to build its engines so; `Decisions.load(sources, failOn)` and
  `Decision.of(location, table)` keep the engine's logging.
- **The framework integrations built as native executables in CI**
  ([#99](https://github.com/brunopacheco1/praecepta/issues/99)): `praecepta-native-tests` (not published; in
  the reactor only with the `native-frameworks` profile) holds a small Spring Boot, Micronaut and Quarkus
  application. Each is built as a GraalVM native executable with its framework's own native build (Spring
  Boot's `native` profile, with Spring AOT; Micronaut's `-Dpackaging=native-image`; Quarkus' `-Dnative`,
  with a DMN file under `src/main/resources/rules/` and a CSV table declared in `application.properties`),
  started, and made to evaluate decisions through the engines and the `Decisions` bean it injects, whose
  answers it checks. The `Native frameworks` workflow runs them on main, weekly, and on pull requests that
  change a framework module, the integration core, the compiler or the loader (with which the Quarkus build
  compiles its CSV table), the engine's native-image metadata or the applications, with GraalVM for JDK 25, and the Micronaut one
  with JDK 21 too (Spring Boot 4 and Quarkus 3.40 need GraalVM for JDK 25).

### Changed
- **An unquoted string cell that starts with `=` or `!=` is now a test**
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): in Praecepta's syntax (Excel, CSV and
  code), `= PT` was the string `= PT`, and is now the test `= "PT"`; `!= PT` now matches every value but
  `PT`. **Upgrade note:** this changes results silently; before upgrading, search your string columns for
  cells that start with `=` or `!=` and quote them (`"= PT"`) if they mean the text.
- **Builds and startups fail on a default output that no input can reach**
  ([#91](https://github.com/brunopacheco1/praecepta/issues/91)): the new issue is an ERROR, the default
  `failOn` of the Maven and Gradle plugins and the default `fail-on` of the Spring Boot, Micronaut and
  Quarkus integrations, so a complete table with a default output that built or started in 0.5 now fails,
  naming the decision. Remove the default output (it is never given), or set `failOn`/`fail-on` to `NONE`
  until the table is fixed. Engines built directly fail only with `failOn(Severity.ERROR)`, as before.
  `ValidationReport.Kind` has a fifth constant, so an exhaustive `switch` over it needs a case for it.
- **Upgrade note:** tables with list columns may now get validation findings they did not get before
  ([#78](https://github.com/brunopacheco1/praecepta/issues/78)): overlaps under UNIQUE are errors, so an
  engine built with `failOn(Severity.ERROR)`, a Maven or Gradle build, a Quarkus build or a Spring Boot or
  Micronaut application at the default `fail-on` stops on such a table where it did not; the rules involved
  are named in the report.
- **The report lists at most 100 pairs of overlapping rules (UNIQUE) or of rules with conflicting outputs
  (ANY)** ([#78](https://github.com/brunopacheco1/praecepta/issues/78)), the first in rule order, as it lists at
  most 100 gaps; 0.5 listed every pair. The report's severity, and so whether a build or a startup fails, is
  unchanged; code that counts these issues sees at most 100.
- `UnaryTests.Equals` has a second component, `explicit`
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)): whether a name is written after `=`
  (`= home`), which FEEL reads as an equality rather than as the bare name's membership test of a list. It
  is false for literals, for which `= x` and `x` are the same test, and the constructor with one argument
  remains; a record pattern that deconstructs `Equals` needs the new component.
- **Upgrade note:** a list input holding a missing element is evaluated rather than rejected
  ([#84](https://github.com/brunopacheco1/praecepta/issues/84)); code that relied on the
  `InvalidInputException` to catch such lists must check them itself.
- **`DmnReader` uses the JDK's own XML parser**
  ([#96](https://github.com/brunopacheco1/praecepta/issues/96)): it created its parser with
  `DocumentBuilderFactory.newInstance()`, which JAXP looks up through the
  `javax.xml.parsers.DocumentBuilderFactory` system property and the class path's service files, and now
  with `newDefaultInstance()`, the JDK's built-in parser, which its security settings (no DTDs, no external
  entities) are written for. **Upgrade note:** an application with another DOM parser on the class path,
  such as Xerces, or with that system property set, used to read DMN files with that parser and now reads
  them with the JDK's, with its security settings applied as intended; a malformed file's message is the JDK
  parser's. The engine's native-image metadata no longer registers JAXP's service file
  (`META-INF/services/javax.xml.parsers.DocumentBuilderFactory`), which only that lookup needed; it still
  keeps the parser's messages.
- `DecisionTable.OutputColumn` has a fifth component, `hasDefault`
  ([#92](https://github.com/brunopacheco1/praecepta/issues/92)): whether the output declares a default
  value, which tells a default of `null` from none. The constructor with four arguments remains and sets it
  when the default value is not null; the canonical one rejects a default value without `hasDefault`. A
  record pattern that deconstructs `OutputColumn` needs the new component.
- `UnaryTests.Test` permits a fifth record, `NullElement`
  ([#84](https://github.com/brunopacheco1/praecepta/issues/84)): an exhaustive `switch` over a cell's tests
  needs a case for it. `DmnWriter.render` (of a single-value cell) rejects it with an
  `IllegalArgumentException`; `renderList` writes it.
- **`FeelTemporals.parseTime` returns `Object`**
  ([#82](https://github.com/brunopacheco1/praecepta/issues/82)), where it returned `Temporal`, since a time
  with a zone id is a `ZonedTime` (one in a zone with one fixed offset, such as `Etc/UTC`, is the `OffsetTime`
  at that offset); a caller that assigned the result to a `Temporal` casts it. `FeelTemporals.compare` throws an
  `IllegalArgumentException` for two values that are not `comparable`, which only a time with a zone id whose
  offset changes makes.
- **The published POMs no longer import the JUnit BOM**
  ([#98](https://github.com/brunopacheco1/praecepta/issues/98)): the parent POM, which every published
  module inherits, imported `org.junit:junit-bom` in its `dependencyManagement`, as did the Spring Boot
  starter and the Micronaut module, so every build that read a Praecepta POM (Maven and Gradle alike, the
  Gradle plugin's marker included) resolved the JUnit BOM, although no published module depends on JUnit.
  The parent now declares the JUnit artifacts the build uses (`junit-jupiter`, its `-api`, `-engine` and
  `-params`, and `junit-platform-commons`, `-engine` and `-launcher`) each at `junit.version`. Every module
  resolves the same JUnit as before.

### Fixed
- **A `null` default output value is a default**
  ([#92](https://github.com/brunopacheco1/praecepta/issues/92)): `DmnReader` read
  `<defaultOutputEntry><text>null</text></defaultOutputEntry>` as no default, so a table with several
  outputs whose declared defaults were all `null` gave no output when no rule matched. DMN 1.5 builds the
  default output from the outputs' defaults, a context with an entry per output (10.3.2.10, step 2; Table
  46), and gives null only when no default is specified. `DmnReader` now keeps a `null` default
  (`OutputColumn.hasDefault`, see *Changed*), `DmnWriter` writes it back, and
  `DecisionTable.defaultOutput()` is present when any output declares a default; the engine, the generated
  classes and the framework integrations' map-based engine all take it from there. **This changes results**
  for tables that declare a `null` default: where no rule matches, they now give an output whose values are
  null (`[{Status=null, Rate=null}]` in the integrations, an output record with null components in a
  generated class, a present `Optional` for a single-hit table or an aggregation, COUNT included) instead of
  none. A blank `defaultOutputEntry` is still no default.
- **SUM, MIN and MAX of a missing output are missing**
  ([#90](https://github.com/brunopacheco1/praecepta/issues/90)): a COLLECT aggregation skipped the matching
  rules whose output was missing (a `null` output entry, an output that names a missing input, an expression
  with a missing operand or a division by zero), so outputs of `10` and null summed to `10`. DMN 1.5
  aggregates with FEEL's `sum`, `min` and `max` (8.2.11; 10.3.2.10, step 3b), which give null for a list
  that holds null, as null is outside their parameters' domain (10.3.4.4, 10.3.2.16). The engine now applies
  this itself, before the output strategy: under SUM, MIN or MAX, a table with one output whose output is
  missing in a matching rule gives that rule's output, converted by the strategy, and `sum`, `min` and `max`
  of an `OutputStrategy` only ever see outputs that are present. COUNT counts every matching rule, as
  before, and no rule matching still gives the default output or none. **This changes results silently**:
  where some matching rules have an output and others have none, a framework integration's engine now gives
  `[{fee=null}]` where it gave the sum, minimum or maximum of the others, a generated class an output whose
  component is null (`Optional[FeesOutput[fee=null]]`), and the engine the missing rule's output; a table
  that wants the old behaviour can give such rules an output of `0` (for SUM) or split them out. Engines
  built in code with several outputs, which DMN does not aggregate, still leave a missing output to their
  strategy. The generated aggregation strategy no longer checks for null, and its `evaluate` says
  `(null if one of them is null)`, so the generated class of a COLLECT table with SUM, MIN or MAX changes in
  those lines.
- **Validation issues were logged twice in the integrations**
  ([#95](https://github.com/brunopacheco1/praecepta/issues/95)): the Spring Boot starter and the Micronaut
  module logged each issue as a warning, one line per issue, and the engine logged the whole report again
  through `System.Logger` (which Spring Boot routes to its own logging); the Quarkus extension logged the
  issues at build time and its engines logged them again when the application started. `DecisionsLoader`,
  which the three use, now builds the engines without their own logging, and Quarkus' runtime does too, so
  each issue is logged once, by the framework's logger (in Quarkus, by the build). The engine that
  `praecepta-compiler` validates a table with no longer logs either, since the Maven and Gradle plugins (and
  Quarkus, for the tables it compiles) log `CompiledTable.report()` themselves; another build tool using
  `TableCompiler` logs the report as [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) describes. The classes the
  build plugins generate are unchanged: their engines still log the report when the application loads them.
- **Spring Boot starter: `@Named` engines under Spring AOT** (found by the native build of
  [#99](https://github.com/brunopacheco1/praecepta/issues/99)): an application processed ahead of time
  (every Spring native image, and the JVM's AOT mode) did not start when a bean injected an engine with
  `@Named("<decision name>")`; `@Qualifier` worked. Spring writes a bean definition's qualifiers from a set
  in which two qualifiers with the same value are one, so only the `@Qualifier` one reached the generated
  code. The starter's AOT processor now writes both.

## [0.5.0] - 2026-11-12

Integrations: Praecepta now fits the build and the framework an application already uses. A Spring
Boot starter, a Quarkus extension and a Micronaut module load DMN decision tables as engine beans and
refuse to start (Quarkus: to build) when a table is invalid; a Gradle plugin generates the same classes
as the Maven plugin; and applications build as GraalVM native images with no configuration for
Praecepta. What the integrations share is in two new modules, `praecepta-compiler` (the Maven plugin's
build-time core, now independent of Maven) and `praecepta-integration` (DMN files to named engines);
the engine still has no dependencies. Three fixes bring tables closer to DMN 1.5: an output's default
value is the result when no rule matches, quoted strings read FEEL's escape sequences, and strings are
ordered by character. The DMN conformance kit (TCK) still passes 83 of its 86 decision-table checks;
the other three need decision requirement graphs
([#14](https://github.com/brunopacheco1/praecepta/issues/14)). Read *Fixed* before upgrading if your
DMN tables declare default output values (they now give that output instead of none), type a single
output through their decision rather than its output clause (the output, and a generated record's
component, now take that type), hold quoted strings with a backslash (`"C:\temp"` now holds a tab,
silently; a path is written `"C:\\temp"`), or COUNT matching rules (no match now gives no output, not 0),
and *Changed* if you deconstruct `DecisionTable.OutputColumn` with a record pattern.

### Added
- **`praecepta-spring-boot-starter`** ([#20](https://github.com/brunopacheco1/praecepta/issues/20)):
  Spring Boot 4 auto-configuration. At startup it loads the DMN files at `praecepta.locations`
  (`classpath*:rules/**/*.dmn` by default), registers a `Decisions` bean (`praeceptaDecisions`) and one
  engine bean per decision table, `PraeceptaEngine<Map<String, String>, Map<String, String>>` injected
  with `@Qualifier("<decision name>")` (or `@Named`, as in Micronaut and Quarkus), and stops the
  application, listing every problem, when a table cannot be loaded or has validation issues at
  `praecepta.fail-on` (`ERROR` by default); other issues are logged as warnings. With Actuator, the
  endpoint `praecepta` reports each decision's columns and validation issues. `praecepta.enabled=false`
  turns it off. Configuration metadata gives IDEs the keys. Spring AOT processes the starter (for a
  Spring native image or the JVM's AOT mode): the tables are validated then, the engine beans are those
  of the decisions found then, and the tables are read again when the application starts.
- **Quarkus extension** ([#76](https://github.com/brunopacheco1/praecepta/issues/76)):
  `praecepta-quarkus` (with its build-time module, `praecepta-quarkus-deployment`), for Quarkus 3.40 and
  later. While Quarkus builds the application, it finds the DMN files under `rules/` on the class path
  (`quarkus.praecepta.locations`), compiles the Excel and CSV tables declared under
  `quarkus.praecepta.tables."<key>"` with the Maven plugin's settings in kebab-case, validates them and
  fails the build on the configured issues (`quarkus.praecepta.fail-on`), listing every problem at once.
  The application gets a `Decisions` bean and one map-based engine bean per decision, injected with
  `@Identifier("Discount")` (or `@Named`), with the validation reports at runtime. In dev mode a changed
  table restarts the application (a new file under a `file:` location needs a manual restart, as Quarkus
  watches files, not directories). Apache POI stays in the build; the extension uses no reflection, but
  the native-image workflow does not build a Quarkus application yet. Like any Quarkus extension it
  depends on `quarkus-arc`, and its deployment module on `quarkus-arc-deployment`. That brings Quarkus'
  build-time core (EPL-2.0, UPL-1.0, and GPL-2.0 with the Classpath exception) into the deployment
  module only. The licence check allows these licences for that module alone, and Jakarta EE APIs
  (EPL-2.0 OR GPL-2.0 with the Classpath exception) for every module.
- **`praecepta-micronaut`** ([#77](https://github.com/brunopacheco1/praecepta/issues/77)): Micronaut 4
  applications get their DMN decision tables as beans. At startup the module loads the files
  `praecepta.locations` finds (`classpath*:rules/**/*.dmn` by default) into a `Decisions` bean and
  registers one engine per decision, injected with `@Named("Discount")`. Unreadable or invalid tables,
  and validation issues at `praecepta.fail-on` (`ERROR` by default), stop startup with every problem
  listed; other issues are logged as warnings. With `micronaut-management`, the `praecepta` endpoint
  reports every decision and its validation issues (`/praecepta`, `/praecepta/{name}`). Micronaut is
  `provided`, and the module needs no reflection. `praecepta.enabled=false` turns it off.
- **Gradle plugin** ([#18](https://github.com/brunopacheco1/praecepta/issues/18)), id
  `io.github.brunopacheco1.praecepta`: does for Gradle builds what the Maven plugin does, through
  `praecepta-compiler`, so the generated classes and DMN resources are the Maven plugin's byte for byte
  (but for the tool they name). The `praecepta` extension takes the Maven plugin's settings (`tables {
  register("discount") { source = file(...); hitPolicy = "FIRST"; inputs { column("age") { type =
  "NUMBER" } } } }`), except that a table's generated class is named by `className`, since `name` is the
  name it is registered under. The `generatePraecepta` task feeds the `main` source set, is cacheable
  (across machines: tables count by name and content) and works with the configuration cache. It runs on
  Gradle 8.5 and later with Java 21+, and is published to Maven Central with its plugin marker
  (`io.github.brunopacheco1.praecepta:io.github.brunopacheco1.praecepta.gradle.plugin`), not to the
  Gradle Plugin Portal.
- **GraalVM native images** ([#19](https://github.com/brunopacheco1/praecepta/issues/19)): applications
  using the engine, `praecepta-integration`, `CsvLoader` and the classes the Maven and Gradle plugins
  generate build as native executables with no configuration for Praecepta. The jars carry their
  reachability metadata
  (`META-INF/native-image/io.github.brunopacheco1/<artifactId>/resource-config.json`): the engine keeps
  the JDK XML parser's messages, without which a malformed DMN file failed natively with a
  `MissingResourceException`, and the integration core registers the default location's files
  (`rules/**/*.dmn`). `ExcelLoader` does not work in native images (Apache POI); compile Excel tables at
  build time instead. A `Native image` workflow builds and runs a test application with GraalVM for JDK
  21 and 25. See [Native images](README.md#native-images).
- **`praecepta-compiler`**: the build-time core of the Maven plugin (`TableCompiler`, and the source
  generation it drives) as a module of its own, independent of Maven, so the Gradle plugin
  ([#18](https://github.com/brunopacheco1/praecepta/issues/18)) and the Quarkus extension
  ([#76](https://github.com/brunopacheco1/praecepta/issues/76)) generate exactly what the Maven plugin
  does. `TableCompiler.compile(TableDefinition, CompilerOptions)` returns a `CompiledTable` with the
  table, its validation report, its DMN and the generated sources, and `CompiledTable.write(sourceRoot,
  resourceRoot)` writes them; `CompilerOptions` names the tool in the generated files and how messages
  name its settings. The Maven plugin's goal, configuration and messages are unchanged; for its generated
  files, see *Fixed*.
- **`praecepta-integration`**: what the Spring Boot, Micronaut and Quarkus integrations share, with no
  dependency but the engine. `TableLocations` finds DMN files by pattern (`classpath*:rules/**/*.dmn`,
  `file:...`) in directories and jars; a class path pattern with wildcards must start with a directory,
  since the class path's roots cannot be listed inside jars. `Decisions.load(sources, failOn)` builds one
  engine per decision table, bound to maps of text (`PraeceptaEngine<Map<String, String>, Map<String,
  String>>`), keeps each validation report, and fails with every problem at once (`DecisionsException`):
  unreadable or invalid files, two decisions with one name, and validation issues at the given severity.
  COLLECT aggregations give one map with the aggregate, as DMN 1.5 defines them over a single output:
  SUM adds numbers with FEEL's 34 digits and keeps their scale (`10.50` and `1.50` give `12.00`), MIN and
  MAX take the smallest or largest value of any ordered type (a string, a date, a duration...), and COUNT
  counts outputs of any type. `DecisionsLoader` is what the three frameworks do with their settings
  (`locations`, `fail-on`): it finds the files through the framework's own resource lookup, loads them,
  words every problem alike and logs what it loaded. `Decisions.describe()` gives the reports as plain
  maps for management endpoints. The design shared by the integrations is in
  [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).
- `ValidationReport.Severity.failOn(String)` reads a `failOn` setting (`ERROR`, `WARNING` or `NONE`, in
  any case, surrounding spaces ignored) as every build-tool and framework integration now does.
- `AggregationOperator.check(outputs)`: whether DMN defines the aggregation for a table's outputs (a single
  output; SUM over numbers, MIN and MAX over any type but boolean, COUNT over any), with the reason if not.
- `FeelLiterals.value(String, ColumnType)` reads back the plain text `FeelLiterals.text` writes.
- `DmnReader.readAll(InputStream)` reads every decision table of a DMN model, in document order.
- `feel.FeelStrings.compare(String, String)`: FEEL's order of strings, by code point (see *Fixed*,
  [#83](https://github.com/brunopacheco1/praecepta/issues/83)).
- `PraeceptaEngine.Builder.defaultOutput(PraeceptaOutput)`, `DecisionTable.defaultOutput()` and
  `DecisionTable.OutputColumn.defaultValue()`: the output when no rule matches (see *Fixed*,
  [#79](https://github.com/brunopacheco1/praecepta/issues/79)).

### Changed
- `DecisionTable.OutputColumn` has a fourth component, `defaultValue`
  ([#79](https://github.com/brunopacheco1/praecepta/issues/79)). Its constructors with two and three
  arguments remain; a record pattern that deconstructs it needs the new component.
- The release publishes every module but the benchmarks and the TCK runner, found by the build rather
  than listed, so new modules need no change to the release, the SBOM checks or the website. Rebuilding
  a release from 0.5.0 on uses `-pl '!praecepta-benchmarks,!praecepta-tck'` (see
  [docs/RELEASING.md](docs/RELEASING.md)).

### Fixed
- **Default output values** ([#79](https://github.com/brunopacheco1/praecepta/issues/79)): `DmnReader`
  read an output's value list but skipped its `defaultOutputEntry`, so a table that declares a default
  gave no output when no rule matched, with no warning. DMN 1.5 says the default is then the result
  (8.2.12; Table 34; 10.3.2.10, step 2: "if a default output value d is specified, DTI=FEEL(d)"), which
  applies before the hit policy and any aggregation. The engine now returns that default output,
  converted once when it is built: alone under every hit policy, as the one element of a multi-hit
  result, and as the result of a COLLECT aggregation, COUNT included (`[Status=Declined, Rate=Standard]`
  for the TCK's `0108-first-hitpolicy`, rather than `[]`). An output without a default is null in it.
  Tables without defaults behave as before. `DecisionTable.OutputColumn` gains `defaultValue` (plain
  text, like a rule's output; the three-argument constructor keeps none), so a record pattern over it
  needs the new component; `DecisionTable.defaultOutput()` builds the output, and
  `PraeceptaEngine.Builder.defaultOutput(PraeceptaOutput)` sets one for a table built in code
  (`table(...)` takes the table's). `DmnReader` reads defaults that are literals of the output's type
  (`null` is no default) and, when the output lists its values, one of them ("The default output value is
  one of the output values", Table 46); it rejects any other default naming the decision and output, and
  `DmnWriter` writes them back. The Maven plugin generates the same classes; a DMN table's default reaches its
  `evaluate`, whose Javadoc then says so, and a time or date-time default counts when deciding whether
  the output's component has a time zone. Gaps are still reported for a table with a default, since no
  rule covers them.
- **String literals in cells read FEEL's escape sequences**
  ([#80](https://github.com/brunopacheco1/praecepta/issues/80)): in a quoted string of a cell, an input
  or output value list or a DMN output entry, a backslash used to mean "the next character as it is", so
  `"a\tb"` was `atb` and `"caf\u00e9"` was `cafu00e9`. They now read as DMN 1.5 has them (grammar rule
  64, 10.3.1.1), in Praecepta's syntax and in strict FEEL alike, and as output expressions have read
  them since 0.4.0: `\n`, `\r`, `\t`, `\"`, `\\`, `\'`, and a character by its code point (`\u00e9`,
  `\U01F40E`, or a surrogate pair `\uD83D\uDC0E`). **This changes some quoted strings silently**: a
  backslash followed by `n`, `r`, `t`, `'`, `"`, `\`, `u` or `U` and hexadecimal digits is now an escape
  sequence, so `"C:\temp"` holds a tab (and no longer matches the path `C:\temp`) and `"D:\new"` a line
  feed; search your tables' quoted strings for backslashes before upgrading. A backslash before any other
  character is rejected (`\q is not an escape sequence; write \\ for a backslash`). A quoted Windows path
  needs its backslashes doubled (`"C:\\temp"`); unquoted words keep their backslashes. In strict FEEL (DMN
  files), a quoted string may no longer hold a line break itself (U+000A to U+000D, grammar rules 33 and
  62), in cells and output entries alike: it is written `\n` or `\r`. `FeelLiterals.literal`, and so
  `DmnWriter`, writes a line feed, a carriage return and a tab as `\n`, `\r` and `\t` (and the other
  vertical space, U+000B and U+000C, as code points).
- **Strings are ordered by character** ([#83](https://github.com/brunopacheco1/praecepta/issues/83)):
  comparisons and intervals of strings (`< "N"`, `["A".."M"]`) used Java's `String.compareTo`, which
  compares UTF-16 code units, while FEEL orders strings lexicographically by character, a Unicode code
  point (DMN 1.5, Table 54 and 10.3.2.3.2). The two differ when a character beyond U+FFFF (an emoji,
  many CJK ideographs), which UTF-16 writes as two surrogates, meets one from U+E000 to U+FFFF: the cell
  `< "～"` (U+FF5E) matched `"🐎"` (U+1F40E), and `["～".."🐎"]` was rejected as starting after it ended.
  The new `feel.FeelStrings.compare` orders strings by code point; the index, the validation report's
  examples and `FeelTemporals.compare` use it. Other strings compare as before. One known deviation
  remains: Table 54 pads the shorter string with U+0000 before comparing, so a string followed by U+0000
  is not after it in FEEL, while here it is.
- **A single output takes its decision's type**: DMN 1.5 says "The OutputClause of a single output
  decision table SHALL NOT specify a typeRef" (Table 34), so a conformant table types its result through the
  decision table's `typeRef` or the decision's `variable`, which `DmnReader` ignored: such an output was
  read as a string. It now takes that type when its output clause has none: the type itself for a
  single-hit table or a SUM, MIN or MAX, the item type of a list for a multi-hit table, and none for COUNT
  (whose result is a number, whatever it counts); a type that says nothing of one output, such as a
  structure, leaves it a string as before. So the TCK's `0114-min-collect-hitpolicy`,
  `0115-sum-collect-hitpolicy` and `0020-vacation-days` load in the framework integrations, and the Maven
  and Gradle plugins generate a `BigDecimal` component for such a number output where they generated a
  `String`.
- **A COUNT that no rule matches gives no output, not 0**: DMN 1.5 deals with "no rules match" before any
  aggregation (10.3.2.10, step 2: "if a default output value d is specified, DTI=FEEL(d)", "else
  DTI=null"; step 3b aggregates only the rules that match), so a COLLECT table with COUNT (`C#`) gives its
  default output when no rule matches, and null without one. Since 0.4.0 it gave 0. **This changes results
  silently**: where no rule matches and the table declares no default, the engine now returns an empty list,
  a generated class an empty `Optional`, and a framework integration's engine no map, where they returned 0
  (`[{fee=0}]` in the integrations); code that reads the count must take an empty result as no match, or the
  table can declare a default output value of `0`. SUM, MIN and MAX already gave no output there, so every
  aggregation now agrees, in the engine, the generated classes and `praecepta-integration` alike.
  `OutputStrategy.count` is now called only for one or more matches (and once when the engine is built, as
  before, so a strategy without it still fails there). A generated aggregation's `evaluate` says what it
  returns (`@return the number of matching rules, or empty if no rule matches`), so the generated classes of a
  COLLECT table with an aggregation change in that Javadoc line.
- **Generated aggregations** sum with FEEL's 34 significant digits (DMN 1.5, 10.3.2.3.1), as the framework
  integrations do, where they added exactly; a sum that needs more digits is rounded, half to even.
- `failOn` ignores surrounding spaces in the Maven and Gradle plugins too (`"ERROR "`), as in the
  framework integrations: every integration reads it with `ValidationReport.Severity.failOn`.

## [0.4.0] - 2026-11-11

Inputs that hold lists, output entries that are expressions, times and date-times with a time zone,
and compact masks for large tables. The features work together: a list may hold times with a time
zone, and a list column's elements are looked up in masks of either form. The DMN conformance kit
(TCK) now passes 83 of its 86 decision-table checks (was 78); the other three need inputs computed
by other decisions ([#14](https://github.com/brunopacheco1/praecepta/issues/14)). Tables with a value
per rule, such as a price list by product code, take about a quarter of the memory and evaluate
faster. `ColumnType` has no new constants (a list column keeps its element type), but read *Changed*
before upgrading if you deconstruct `PraeceptaOutput` or `DecisionTable` with record patterns, switch
over the value types of a cell (`UnaryTests`), or feed map-based inputs to tables that compare times.

### Added
- **Inputs that hold lists** ([#25](https://github.com/brunopacheco1/praecepta/issues/25)): a column
  may hold several values, such as a customer's nationalities. Bind it with
  `listInput("nationalities", Customer::nationalities)` (strings) or
  `listInput("ages", ColumnType.NUMBER, Customer::ages)`, or with map-based inputs declare it with
  `listColumnType(column, type)` and pass a FEEL list as text (`["PT", "BR"]`, or `PT, BR`). A cell
  of a list column holds when **some element** passes one of its tests: `"PT","ES"` matches a list
  holding `"PT"` or `"ES"`, `>= 18` one with an element of at least 18. `not(...)` holds when **no
  element** does, so `not("PT")` reads "without PT". As in FEEL's `list contains`, a missing (null)
  list makes every test unknown, so it matches only `-` and `null`, while an empty list matches no
  test, and so every `not(...)`. Elements may not be missing, must be of the Java types a getter of
  a single value returns (or text), and declared input values apply to them; a table's input of single
  values cannot be bound as a list. The elements of a TIME or DATE_TIME list may have a time zone or
  not, each compared as a single value of its kind is: by instant when the element and the cell's
  literal both have a zone, else by local time. The index ORs the masks of the elements' values, a few word operations per element (only on
  an element's own words when its mask is sparse and no cell of the column is a `not(...)`), then
  ANDs across columns as usual, list columns after the others with as many `-` cells. `DecisionTable` gains `listInputs`, and `DecisionTable.cell(rule, column)` parses a cell as
  the table holds it.
  - **DMN:** strict FEEL compares a list as a whole (`"PT"` never equals `["PT","BR"]`), so a list
    column's entries are written with FEEL's list functions, joined with `or`:
    `list contains(?, "PT")`, `some x in ? satisfies x >= 18`, `some x in ? satisfies x in [18..65)`,
    `? = null`, and `not(...)` around them (`CellParser.parseFeelList`, `DmnWriter.renderList`).
    `DmnReader` reads columns typed by a collection item definition (`isCollection="true"`), with
    allowed values on the item type, and `DmnWriter` writes them back so.
  - **A cell that names a list input** holds when the column's value is in that list, as DMN reads
    `Flu Symtoms` in the TCK's `0039-dt-list-semantics` (and `not(...)` when it is not). `DmnReader`
    takes the list from the named input's `inputData` type. Comparisons with a list (`> limits`) are
    rejected, since FEEL never orders a value against a list.
  - **The Maven plugin** declares a list column with `<list>true</list>` and generates a
    `java.util.List` component for it (also for named list inputs of a DMN table).
  - **Validation:** a list's matches cannot be read from the classes of single values (rules `"PT"`
    and `"BR"` overlap on `["PT","BR"]`), so rules whose cell in a list column is not `-` are left
    out of the static analysis, as rules that compare with another input are: they never count as
    overlapping or hiding another rule, and gaps are not searched while there are any.
- **Output entries that are expressions** ([#71](https://github.com/brunopacheco1/praecepta/issues/71)):
  an output may compute its value from literals and inputs with `+`, `-`, `*`, `/`, `**`, unary minus
  and parentheses, as in `Symptom + " is in the list of Flu symptoms"`, `(price - discount) * 1.2` or
  `amount * (1 + rate) ** years` (`feel.OutputExpression`). `+` joins two strings or adds two numbers,
  the others take numbers; numbers are decimals, every result is rounded to 34 digits, ties to even
  (FEEL's decimal128; DMN 1.5, Tables 57, 59 and 60), and a computed number is given without trailing
  zeros (`12`, not `12.00`). As FEEL has it, unary minus binds tighter than `**` (`-4 ** 2` is 16) and
  `**` associates to the left; an exponent may be any number, so `2 ** 0.5` is a square root. Number
  literals may have an exponent (`1.2e3`), and strings take FEEL's escape sequences (`\n`, `\t`,
  `\"`, `\\`, `é`, `\U01F40E`). A missing operand gives a missing output, and so does a result
  FEEL does not define: a division by zero, zero to a negative power, a negative number to a power
  that is not whole, an exponent beyond ±999,999,999, or a number out of a decimal128's range.
  `PraeceptaOutput` gains `expressions` (output column to the expression
  as written). The engine parses each expression and checks the types of the inputs it names when it
  is built (a string and a number never combine; inputs of other types are rejected), and evaluates
  it only for the rules that match, as it does for outputs that name an input. Under ANY the evaluated
  outputs are compared, number outputs by value as FEEL's `=` compares them (`1.50` agrees with
  `price * 1` for a price of 1.5, although each output keeps its text), and so are the values of a
  table's number outputs in their priority lists; an output with a priority list cannot be an expression. `DmnReader` reads such
  entries, checking that the value has the output's type (a string output takes a number as its text)
  and that they read only inputs of single values the engine has: the table's columns and the inputs
  its cells compare with (so may an entry that names an input),
  and `DmnWriter` writes them back unchanged. Other FEEL (function calls, comparisons, `if`, lists) is
  still rejected with a message saying what is not supported. With list inputs above, both checks of
  the DMN TCK's `0039-dt-list-semantics` pass.
- **Times and date-times with a time zone** ([#70](https://github.com/brunopacheco1/praecepta/issues/70)):
  TIME columns also take times with an offset (`time("16:12:00+01:00")`, `16:12:00Z`), held as
  `OffsetTime`, and DATE_TIME columns date-times with an offset or a zone id
  (`date and time("2018-07-30T16:12:00Z")`, `date and time("2018-07-30T16:12:00@Europe/Paris")`),
  held as `ZonedDateTime`. As in FEEL, two of them compare by the instant they denote, so
  `time("09:00:00+01:00")` equals `time("08:00:00Z")`. When only one of two values has a zone, the
  other takes its offset (DMN 1.5, Table 54, on `<`: "If one input has a null timezone offset, that input uses
  the timezone offset of the other input"), so the two compare by local time. DMN 1.5 does not say how
  `=` treats such a pair (Table 53) and the DMN TCK has no case for it, so equality is kept consistent
  with the comparisons, `<=` being defined as `<` or `=` (10.3.2.15): the two are equal exactly when
  neither is before the other. So the cell `time("08:00:00")` matches the inputs `08:00`, `08:00Z` and `08:00+05:00`, the
  cell `< time("12:00:00+01:00")` matches `11:59`, and an interval may have an end of each kind
  (`[time("09:00:00")..time("17:00:00Z")]`). The builder gains `offsetTimeInput` and `zonedDateTimeInput` (bind an `OffsetDateTime`
  through `toZonedDateTime()`); map-based inputs read both kinds from text, including the
  `[Europe/Paris]` form `ZonedDateTime.toString()` writes. A zoned date-time is written as FEEL
  writes it (`2025-12-24T09:00:00@Europe/Paris`), except the later of two equal local times when
  clocks go back, which also takes its offset (`2025-10-26T02:30:00+01:00[Europe/Paris]`, and in a
  FEEL literal `date and time("2025-10-26T02:30:00+01:00")`), so it reads back as the same instant. In the index, a value
  without a zone is classified by its local time among the local times of every literal; a value with
  a zone has two coordinates, its instant among the literals with a zone (keyed in `PrimitiveKeys` by
  nanoseconds) and its local time among those without, and its class is the pair. A column has only
  the classes its binding can give, and a pair that no value has (an instant and a local time more
  than 18 hours apart, or not a whole number of seconds apart) is empty, so gap analysis stays exact:
  a typed getter's column is searched for gaps among its own kind only, a map-based one among both
  (`cutOff in (-inf..time("11:00:00Z")) at local time [time("12:00:00")..+inf)` is a gap among times
  with a zone). `DmnReader` and `DmnWriter` read and write the
  zoned literals, and the new `feel.FeelTemporals` parses FEEL time text and orders values as FEEL
  does. The Maven plugin generates `OffsetTime` and `ZonedDateTime` components for columns whose
  values are all written with a zone (and for the inputs their cells compare with), and fails the
  build for a column that mixes both kinds. A time with a zone id (`time("16:12:00@Europe/Paris")`)
  is still rejected, as it has no fixed offset ([#82](https://github.com/brunopacheco1/praecepta/issues/82)). With it, the three checks of the DMN TCK's
  `0036-dt-variable-input` that use zoned inputs pass.

### Changed
- **Compact masks for large tables** ([#17](https://github.com/brunopacheco1/praecepta/issues/17)):
  the mask of a column value that only a few rules accept is stored sparse, as its non-zero 64-bit
  words, instead of one bit per rule. A mask is stored sparse only when at most a quarter of its
  words hold a bit (so it takes at most half the memory), which never happens in tables of up to 192
  rules except for empty masks; other masks stay dense, as before. When a column's mask is sparse,
  evaluation intersects the later columns only on its words. Under ANY, the masks of rules with the
  same outputs are stored the same way, and a list column looks up its elements in masks of either
  form. There is no new dependency and no API change. On an Apple M2 Pro, with the new
  `lookup-<rules>` benchmark tables (a code per rule, as in a price list), `lookup-10000` went from
  17.6 to 4.6 MB of retained heap and from 676 to 387 ns/op (`EvaluationBenchmark`); `mixed-10000`
  from 6.7 to 5.3 MB. `mixed-100`, `mixed-1000`,
  `synthetic-1000` and `synthetic-10000` are unchanged within noise (331-353 ns/op against 331-336
  before on `mixed-100`, 551-574 against 542-575 on `mixed-1000`, two runs each).
- **`PraeceptaOutput` gains a component, `expressions`.** Its former constructors remain, but record
  patterns that deconstruct it need the new component.
- **`DecisionTable` gains a component, `listInputs`.** Its former constructors remain, but record
  patterns that deconstruct it need the new component.
- **Cell values (`UnaryTests`)** may now be `OffsetTime` and `ZonedDateTime` in TIME and DATE_TIME
  columns, and `FeelLiterals` writes them: code that switches over the value types of a cell needs
  these cases.
- **Map-based inputs accept times and date-times with a time zone**, which they used to reject as
  invalid. Against cells and declared input values without a zone they count by their local time, as
  FEEL compares them (`08:30Z` and `08:30+05:00` match `time("08:30:00")`), so a table whose cells
  all lack a zone matches them as it matches the same times without one, and its validation report
  does not change. To keep a column to one kind, bind the inputs with typed getters, `timeInput` or
  `dateTimeInput` for values without a zone (`inputs(...)` does not mix with per-column bindings, so
  every input is then bound per column).
- **`DmnReader` reads columns typed by a collection** instead of rejecting them; an output of a
  collection type, a collection of collections, and allowed values on a collection itself are still
  rejected.
- **`DmnWriter` writes an `inputData` for every input the cells compare with**, not only for the
  table's columns, so a list input keeps its type and Drools can bind every input.
- **The Maven plugin validates a table with getters of the generated Java types** instead of
  map-based inputs, so the build-time analysis matches what the generated code can evaluate.

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

[Unreleased]: https://github.com/brunopacheco1/praecepta/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/brunopacheco1/praecepta/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/brunopacheco1/praecepta/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/brunopacheco1/praecepta/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/brunopacheco1/praecepta/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/brunopacheco1/praecepta/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/brunopacheco1/praecepta/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/brunopacheco1/praecepta/releases/tag/v0.1.0
