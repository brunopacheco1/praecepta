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
   analysis then never counts those rules as overlapping or hiding others, and skips gaps.
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
   - **Memory** grows with columns × classes × rules, never with the product of the values.
3. **Evaluation.** `evaluate(input)` reads each column's value through the bound getter
   (`InputStrategy`), finds its class, and ANDs the masks, 64 rules per machine word. The hit policy
   then reads the result: FIRST and PRIORITY take the lowest set bit (bits are numbered in rule or
   priority order), UNIQUE and ANY check their constraint, RULE ORDER, OUTPUT ORDER and COLLECT walk
   the set bits, and COLLECT aggregates with SUM, MIN, MAX or COUNT (`OutputStrategy`).
4. **Validation** (`TableAnalysis`, `ValidationReport`), when the engine is built: overlapping rules
   (UNIQUE), conflicting outputs (ANY), rules that can never fire (FIRST, PRIORITY), and gaps
   (declared inputs that no rule covers, with an example). The analysis is exact, because it searches
   the same column classes the index uses.

An engine is **immutable** once built: any number of threads may share it without locks.

## DMN
- `DmnReader` reads DMN 1.1 to 1.5 (as written by Camunda Modeler, Drools, Trisotech and
  `DmnWriter`) into a `DecisionTable`: hit policy, aggregation, column types, value lists and rules.
  It uses the JDK's XML parser with DTDs and external entities disabled. Anything it does not
  support (a time with a zone id, computed expressions, function calls) is rejected
  with an error naming the decision, rule and column.
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
