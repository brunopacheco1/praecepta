<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Architecture

Praecepta evaluates single DMN decision tables. It is a library, not a service: an application
builds an engine once and calls it.

## Modules

| Module | Depends on | Role |
|---|---|---|
| `praecepta-engine` | the JDK only (`java.xml`) | The engine, the cell parser, DMN reading and writing, and validation. A JPMS module, `io.github.brunopacheco1.praecepta.engine`. |
| `praecepta-loader` | engine, Apache POI | Reads rules from Excel (first sheet) and semicolon-separated CSV files. |
| `praecepta-maven-plugin` | engine, loader, Maven API | Compiles tables at build time: validates them, writes them as DMN resources and generates typed Java classes. |
| `praecepta-benchmarks` | engine, Camunda 8, Drools, JMH | Compares Praecepta with other engines; not published. |

## From a table to an answer

```
 Excel / CSV ──(loader)──► rules ──┐
                                   ├──► PraeceptaEngine.Builder ──► PraeceptaEngine ──► evaluate(input) ──► outputs
 DMN file ──(DmnReader)──► DecisionTable ┘        │
                                                  └──► ValidationReport (overlaps, unreachable rules, gaps)
```

1. **Rules and cells.** A rule (`Praeceptum`) maps input columns to cells and output columns to
   values. `CellParser` parses each cell, according to its column's type (`ColumnType`: STRING,
   NUMBER, DATE, BOOLEAN, TIME, DATE_TIME, DAY_TIME_DURATION, YEAR_MONTH_DURATION; times and date-times with or without a time zone), into `UnaryTests`: the subset of DMN FEEL *simple unary tests* that looks at one
   column value (lists, `not(...)`, comparisons, intervals, `null`, "any"). In a DMN table (strict
   FEEL) a cell may also compare with another input (`> limit`); the index sees such a cell as "any
   value", and the engine checks it on the rules the index leaves, with the inputs' values. The
   analysis then never counts those rules as overlapping or hiding others, and skips gaps. A column
   may also hold **lists** (`DecisionTable.listInputs`): its cells keep the same syntax and hold when
   some element passes a test (`not(...)`: when none does), as FEEL's `list contains` would; a cell
   that names a list input asks whether the value is in that list.
2. **The bitset index** (`BitsetIndex`, `ColumnIndex`). Every rule owns one bit. Each column splits
   its values into *classes* that no rule can tell apart, and each class carries a mask: the rules
   that accept it.
   - **Strings:** one class per value a rule mentions (or the column declares), one for any other
     value, and one for a missing value. Lookup is a hash, or an array index for enum inputs.
   - **Numbers, dates, times, date-times and durations:** the boundaries the rules mention split the line into
     intervals and points; lookup is a binary search, over `long` keys when the values have them
     (`PrimitiveKeys`: epoch days, nanoseconds, months, scaled numbers). A string column whose cells compare
     (`< "N"`, `["A".."M"]`) is split the same way; a value the rules mention is still found by hash,
     and only other values need the binary search. Booleans are the same with `false < true`,
     both always boundaries, so they get one class each.
   - **Times and date-times with a time zone** (`OffsetTime`, `ZonedDateTime`) lie on a line of
     their own, ordered by instant and keyed in `PrimitiveKeys` by nanoseconds at offset zero, which
     the boundaries with a zone split as above; values without a zone lie on the other line. FEEL
     cannot compare across the lines, so a cell's test with a literal of the other kind is unknown on
     every class of a line. A column has only the lines its input can reach (`Zoning`): `timeInput`
     and `dateTimeInput` give values without a zone, `offsetTimeInput` and `zonedDateTimeInput` with
     one, and map-based inputs, read from text, either; so the analysis looks for gaps among the
     values that can really occur.
   - **Lists** (`ListColumn`): an ordinary column index over the same cells classifies one element.
     For each element, its class mask gives the positive cells it passes and the `not(...)` cells it
     breaks; ORing those over the elements gives "some element passes", and the list's mask is the
     `-` rules, the positive rules some element passes, and the negated rules none passes. A missing
     and an empty list have fixed masks. Two nationalities cost one more OR per mask word. The
     element index stores its masks in either form (see below), and the list masks it gives are
     dense. Elements of a time or date-time list may have a zone or not: the element index has
     both lines, and an element on the other line than a cell's literal makes that test unknown,
     which, as in FEEL's `or`, keeps the `not(...)` cells from matching.
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
   the set bits, and COLLECT aggregates with SUM, MIN, MAX or COUNT (`OutputStrategy`).
4. **Validation** (`TableAnalysis`, `ValidationReport`), when the engine is built: overlapping rules
   (UNIQUE), conflicting outputs (ANY), rules that can never fire (FIRST, PRIORITY), and gaps
   (declared inputs that no rule covers, with an example). The analysis is exact, because it searches
   the same column classes the index uses. The classes of single values cannot say which lists a rule
   matches (rules `"PT"` and `"BR"` overlap on `["PT","BR"]`), so a list column shows the analysis
   one class, and the rules that constrain it are left out like rules that compare with other inputs.

An engine is **immutable** once built: any number of threads may share it without locks.

## DMN
- `DmnReader` reads DMN 1.1 to 1.5 (as written by Camunda Modeler, Drools, Trisotech and
  `DmnWriter`) into a `DecisionTable`: hit policy, aggregation, column types, value lists and rules.
  It uses the JDK's XML parser with DTDs and external entities disabled. Anything it does not
  support (a time with a zone id, function calls, inputs computed by other decisions) is rejected
  with an error naming the decision, rule and column. Output entries may be expressions of
  literals and inputs with `+ - * /` (`OutputExpression`): they are kept as written, parsed and
  type-checked when the engine is built, and evaluated only for matching rules, like outputs that
  name an input; `DmnWriter` writes them back unchanged. Strict FEEL compares a list with a unary test
  as a whole, so a list column's entries are written with FEEL's list functions
  (`list contains(?, "PT") or some x in ? satisfies x >= 18`); `CellParser.parseFeelList` reads
  exactly those forms back into the same tests Praecepta's own syntax writes `"PT", >= 18`.
- `DmnWriter` writes a `DecisionTable` as standard DMN 1.3, which `DmnReader` reads back unchanged
  and which Camunda 8 and Drools evaluate identically (the benchmarks check this).

## The Maven plugin
`GenerateMojo` runs `TableCompiler` for each configured table:
1. load it (Excel or CSV through the loader, or DMN through `DmnReader`);
2. build an engine and validate it, failing the build at the configured severity; each input is
   bound with a getter of the Java type the generated record gives it, so the analysis sees the
   values the generated code can pass (with or without a time zone);
3. write it as a DMN resource next to the generated classes;
4. generate, with `SourceGenerator`, an input record (string columns with a value list become enums,
   bound to the engine's int-coded path), an output record, and a class that loads the DMN resource
   once and evaluates it. `JavaNames` turns table, column and value names into safe identifiers.
   `TimeZones` decides which time and date-time columns become `OffsetTime` and `ZonedDateTime`:
   those whose written values all have a zone, together with the inputs their cells compare with.

At runtime, the generated code needs only `praecepta-engine`.

## Design decisions
The main decisions and their reasons (strict DMN semantics, bitsets instead of a trie or generated
code, the Java baseline, DMN as the exchange format, the licence) are recorded in
[MODERNISATION.md](MODERNISATION.md). Security design is described in
[SECURITY-ASSURANCE.md](SECURITY-ASSURANCE.md).

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
  `null`; `list contains([], x)` is false, so an empty list matches every `not(...)`. Elements may
  not be missing, since `list contains` and `some` would make a null element unknown.
- **DMN keeps strict semantics.** A plain `"PT"` against a list column would compare the whole list
  in other engines, so list entries are written with `list contains` and `some ... satisfies`, and
  only those forms are read back for a list column.
- **The analysis leaves out rules that test lists**, as it does for cells that compare with another
  input: the classes of single values cannot describe sets of values, and an analysis over sets of
  classes would grow exponentially. Findings stay sound (nothing false is reported); some overlaps
  and gaps go unreported.

### Output expressions (#71)
- **A small subset, not a FEEL interpreter**: string and number literals, input names, `+ - * /`,
  unary minus and parentheses. That covers string building and arithmetic (and the TCK's case) while
  keeping the engine dependency-free; anything else is rejected by name, so a table never evaluates
  differently from other engines in silence.
- **Parsed and type-checked when the engine is built**, so a table that adds a string to a number
  fails at build time, not at the first evaluation; the expression text is kept in
  `PraeceptaOutput.expressions`, so `DmnWriter` writes back exactly what was read.
- **Evaluated only for matching rules**, through the same path as outputs that name an input; rules
  without such outputs keep their outputs converted once.
- **FEEL's null and decimal semantics**: a missing operand or a division by zero gives null, a
  quotient keeps 34 digits, and computed numbers are written without trailing zeros, since a FEEL
  number is a value, not a scale.
- **ANY compares evaluated outputs**; an output with a priority list cannot be an expression, since
  its rank must be known when the engine is built.
