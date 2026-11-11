<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Changelog

All notable changes are listed here, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/); until 1.0, minor versions may change the API.

## [Unreleased]

### Added
- **`praecepta-compiler`**: the build-time core of the Maven plugin (`TableCompiler`, and the source
  generation it drives) as a module of its own, independent of Maven, so the Gradle plugin
  ([#18](https://github.com/brunopacheco1/praecepta/issues/18)) and the Quarkus extension
  ([#76](https://github.com/brunopacheco1/praecepta/issues/76)) generate exactly what the Maven plugin
  does. `TableCompiler.compile(TableDefinition, CompilerOptions)` returns a `CompiledTable` with the table,
  its validation report, its DMN and the generated sources, and `CompiledTable.write(sourceRoot,
  resourceRoot)` writes them; `CompilerOptions` names the tool in the generated files and how messages
  name its settings. The Maven plugin's goal, configuration, messages and generated files are unchanged.
- **`praecepta-integration`**: what the Spring Boot, Micronaut and Quarkus integrations share, with no
  dependency but the engine. `TableLocations` finds DMN files by pattern (`classpath*:rules/**/*.dmn`,
  `file:...`) in directories and jars; `Decisions.load(sources, failOn)` builds one engine per decision
  table, bound to maps of text (`PraeceptaEngine<Map<String, String>, Map<String, String>>`), keeps each
  validation report, and fails with every problem at once (`DecisionsException`): unreadable or invalid
  files, two decisions with one name, and validation issues at the given severity. COLLECT aggregations
  give one map with the aggregate (a SUM with FEEL's 34 digits). `Decisions.describe()` gives the
  reports as plain maps for management endpoints. The design shared by the integrations is in
  [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).
- `DmnReader.readAll(InputStream)` reads every decision table of a DMN model, in document order.
- `feel.FeelStrings.compare(String, String)`: FEEL's order of strings, by code point (see *Fixed*,
  [#83](https://github.com/brunopacheco1/praecepta/issues/83)).
- `PraeceptaEngine.Builder.defaultOutput(PraeceptaOutput)`, `DecisionTable.defaultOutput()` and
  `DecisionTable.OutputColumn.defaultValue()`: the output when no rule matches (see *Fixed*,
  [#79](https://github.com/brunopacheco1/praecepta/issues/79)).
- **GraalVM native images** ([#19](https://github.com/brunopacheco1/praecepta/issues/19)): applications
  using the engine, `praecepta-integration`, `CsvLoader` and the classes the Maven plugin generates build
  as native executables with no configuration for Praecepta. The jars carry their reachability metadata
  (`META-INF/native-image/io.github.brunopacheco1/<artifactId>/resource-config.json`): the engine keeps the
  JDK XML parser's messages, without which a malformed DMN file failed natively with a
  `MissingResourceException`, and the integration core registers the default location's files
  (`rules/**/*.dmn`). `ExcelLoader` does not work in native images (Apache POI); compile Excel tables at
  build time instead. A `Native image` workflow builds and runs a test application with GraalVM for JDK 21
  and 25. See [Native images](README.md#native-images).
- **`praecepta-spring-boot-starter`** ([#20](https://github.com/brunopacheco1/praecepta/issues/20)):
  Spring Boot 4 auto-configuration. At startup it loads the DMN files at `praecepta.locations`
  (`classpath*:rules/**/*.dmn` by default), registers a `Decisions` bean (`praeceptaDecisions`) and one
  engine bean per decision table, `PraeceptaEngine<Map<String, String>, Map<String, String>>` injected with
  `@Qualifier("<decision name>")`, and stops the application, listing every problem, when a table cannot
  be loaded or has validation issues at `praecepta.fail-on` (`ERROR` by default); other issues are logged
  as warnings. With Actuator, the endpoint `praecepta` reports each decision's columns and validation
  issues. `praecepta.enabled=false` turns it off. Configuration metadata gives IDEs the keys.
- **`praecepta-micronaut`** ([#77](https://github.com/brunopacheco1/praecepta/issues/77)): Micronaut 4
  applications get their DMN decision tables as beans. At startup the module loads the files
  `praecepta.locations` finds (`classpath*:rules/**/*.dmn` by default) into a `Decisions` bean and
  registers one engine per decision, injected with `@Named("Discount")`. Unreadable or invalid tables,
  and validation issues at `praecepta.fail-on` (`ERROR` by default), stop startup with every problem
  listed; other issues are logged as warnings. With `micronaut-management`, the `praecepta` endpoint
  reports every decision and its validation issues (`/praecepta`, `/praecepta/{name}`). Micronaut is
  `provided`, and the module needs no reflection. `praecepta.enabled=false` turns it off.

### Changed
- The release publishes every module but the benchmarks and the TCK runner, found by the build rather
  than listed, so new modules need no change to the release, the SBOM checks or the website. Rebuilding a
  release from 0.5.0 on uses `-pl '!praecepta-benchmarks,!praecepta-tck'` (see
  [docs/RELEASING.md](docs/RELEASING.md)).
- `DecisionTable.OutputColumn` has a fourth component, `defaultValue`
  ([#79](https://github.com/brunopacheco1/praecepta/issues/79)). Its constructors with two and three
  arguments remain; a record pattern that deconstructs it needs the new component.

### Fixed
- **String literals in cells read FEEL's escape sequences** ([#80](https://github.com/brunopacheco1/praecepta/issues/80)):
  in a quoted string of a cell, an input or output value list or a DMN output entry, a backslash used to
  mean "the next character as it is", so `"a\tb"` was `atb` and `"caf\u00e9"` was `cafu00e9`. They now
  read as DMN 1.5 has them (grammar rule 64, 10.3.1.1), in Praecepta's syntax and in strict FEEL alike, and
  as output expressions have read them since 0.4.0: `\n`, `\r`, `\t`, `\"`, `\\`, `\'`, and a character by
  its code point (`\u00e9`, `\U01F40E`, or a surrogate pair `\uD83D\uDC0E`). A backslash before any other
  character is rejected (`\q is not an escape sequence; write \\ for a backslash`), so a quoted Windows path
  needs its backslashes doubled (`"C:\\temp"`); unquoted words keep their backslashes. `FeelLiterals.literal`,
  and so `DmnWriter`, writes a line feed, a carriage return and a tab as `\n`, `\r` and `\t` (and the other
  vertical space, U+000B and U+000C, as code points), since a FEEL string literal may not hold a line break.
- **Strings are ordered by character** ([#83](https://github.com/brunopacheco1/praecepta/issues/83)):
  comparisons and intervals of strings (`< "N"`, `["A".."M"]`) used Java's `String.compareTo`, which
  compares UTF-16 code units, while FEEL orders strings lexicographically by character, a Unicode code
  point (DMN 1.5, Table 54 and 10.3.2.3.2). The two differ when a character beyond U+FFFF (an emoji, many
  CJK ideographs), which UTF-16 writes as two surrogates, meets one from U+E000 to U+FFFF: the cell
  `< "～"` (U+FF5E) matched `"🐎"` (U+1F40E), and `["～".."🐎"]` was rejected as starting after it ended.
  The new `feel.FeelStrings.compare` orders strings by code point; the index, the validation report's
  examples and `FeelTemporals.compare` use it. Other strings compare as before.
- **Default output values** ([#79](https://github.com/brunopacheco1/praecepta/issues/79)): `DmnReader`
  read an output's value list but skipped its `defaultOutputEntry`, so a table that declares a default gave
  no output when no rule matched, with no warning. DMN 1.5 says the default is then the result (8.2.12;
  Table 34; 10.3.2.10, step 2: "if a default output value d is specified, DTI=FEEL(d)"), which applies
  before the hit policy and any aggregation. The engine now returns that default output, converted once
  when it is built: alone under every hit policy, as the one element of a multi-hit result, and as the
  result of a COLLECT aggregation, COUNT included (`[Status=Declined, Rate=Standard]` for the TCK's
  `0108-first-hitpolicy`, rather than `[]`). An output without a default is null in it. Tables without
  defaults behave as before. `DecisionTable.OutputColumn` gains `defaultValue` (plain text, like a rule's
  output; the three-argument constructor keeps none), so a record pattern over it needs the new component;
  `DecisionTable.defaultOutput()` builds the output, and `PraeceptaEngine.Builder.defaultOutput(PraeceptaOutput)`
  sets one for a table built in code (`table(...)` takes the table's). `DmnReader` reads defaults that are
  literals of the output's type (`null` is no default) and rejects any other default naming the decision and
  output, and `DmnWriter` writes them back. The Maven plugin generates the same classes; a DMN table's
  default reaches its `evaluate`, whose Javadoc then says so, and a time or date-time default counts when
  deciding whether the output's component has a time zone. Gaps are still reported for a table with a
  default, since no rule covers them.

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

[Unreleased]: https://github.com/brunopacheco1/praecepta/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/brunopacheco1/praecepta/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/brunopacheco1/praecepta/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/brunopacheco1/praecepta/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/brunopacheco1/praecepta/releases/tag/v0.1.0
