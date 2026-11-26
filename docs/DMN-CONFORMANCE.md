<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# DMN conformance

Two sources alone judge Praecepta's semantics: the
[DMN 1.5 specification](https://www.omg.org/spec/DMN/1.5/) and the
[DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) (TCK), at the commit
[`5cd912f`](https://github.com/dmn-tck/tck/tree/5cd912f281831bc535e710aba435e16de198028a) that
`praecepta-tck` runs. No other engine is a reference.

Of the kit's 3,511 result nodes, 3,482 pass. Eleven fail: nine loan payments that the kit states as a
double computes them ([case 33](#33-real-numbers-the-tck-states-to-some-digits)), and two times in a zone
with one fixed offset, a [known deviation](#known-deviations). Eighteen are not supported: all are in the
kit's model of external functions (`0076-feel-external-java`), which Praecepta rejects by design.
[praecepta-tck/RESULTS.md](../praecepta-tck/RESULTS.md) lists every model.

This document records the cases where the two sources leave the behaviour open: DMN 1.5 contradicts
itself or says nothing, and the TCK has no test. In those cases Praecepta does not change what it does on
its own reading. It records here what it does, so the case can be settled when a later DMN version or the
TCK decides. Do not describe any of these choices as "FEEL's behaviour". A settled case stays here, marked
as such. Where DMN 1.5 or the TCK decides and Praecepta does otherwise, the case is a
[known deviation](#known-deviations), listed apart.

| # | Case | Status |
|---|---|---|
| 1 | [`-` and a missing input](#1---and-a-missing-input) | open |
| 2 | [Values outside the declared input values](#2-values-outside-the-declared-input-values) | open |
| 3 | [An interval whose ends are inputs that are missing or out of order](#3-an-interval-whose-ends-are-inputs-that-are-missing-or-out-of-order) | open |
| 4 | [`=` between a time or date-time with a zone and one without](#4--between-a-time-or-date-time-with-a-zone-and-one-without) | open |
| 5 | [Two times with different offsets](#5-two-times-with-different-offsets) | open |
| 6 | [COUNT when no rule matches](#6-count-when-no-rule-matches) | settled |
| 7 | [A `null` default output value beside output values](#7-a-null-default-output-value-beside-output-values) | open |
| 8 | [A complete table, and missing inputs](#8-a-complete-table-and-missing-inputs) | open |
| 9 | [A time with a zone id against a time in another zone, at an offset or without a zone](#9-a-time-with-a-zone-id-against-a-time-in-another-zone-at-an-offset-or-without-a-zone) | open; settled for a zone with one fixed offset |
| 10 | [The difference of two times in different zones, or one with a zone and one without](#10-the-difference-of-two-times-in-different-zones-or-one-with-a-zone-and-one-without) | open |
| 11 | [A date and time moved by months onto a local time its zone skips](#11-a-date-and-time-moved-by-months-onto-a-local-time-its-zone-skips) | open |
| 12 | [Blank text as a missing value](#12-blank-text-as-a-missing-value) | open |
| 13 | [A value of another type than the decision table input it feeds](#13-a-value-of-another-type-than-the-decision-table-input-it-feeds) | open |
| 14 | [The default output of a table with several results](#14-the-default-output-of-a-table-with-several-results) | decided by DMN 1.5; followed |
| 15 | [A name that both a requirement and a path into another could mean](#15-a-name-that-both-a-requirement-and-a-path-into-another-could-mean) | open |
| 16 | [Input data whose values are not of its type or allowed values](#16-input-data-whose-values-are-not-of-its-type-or-allowed-values) | open |
| 17 | [An input data without a type](#17-an-input-data-without-a-type) | open |
| 18 | [Times and date-times that differ below the millisecond](#18-times-and-date-times-that-differ-below-the-millisecond) | decided by the TCK |
| 19 | [Hour 24 in a time and a date and time](#19-hour-24-in-a-time-and-a-date-and-time) | decided by DMN 1.5 for a time, by the TCK for a date and time |
| 20 | [Which nulls are errors](#20-which-nulls-are-errors) | decided by the TCK, open beyond it |
| 21 | [A filter whose condition is a number for some elements only](#21-a-filter-whose-condition-is-a-number-for-some-elements-only) | open; a value that is not a list decided by DMN 1.5 |
| 22 | [A name in no scope](#22-a-name-in-no-scope) | open |
| 23 | [Limits of one evaluation](#23-limits-of-one-evaluation) | open |
| 24 | [A unary test that does not name `?` and gives a boolean](#24-a-unary-test-that-does-not-name--and-gives-a-boolean) | decided by DMN 1.5 (7.3.2); followed |
| 25 | [Names a table read alone does not have](#25-names-a-table-read-alone-does-not-have) | open |
| 26 | [A literal entry of another type than its column or output](#26-a-literal-entry-of-another-type-than-its-column-or-output) | decided by DMN 1.5 for output entries (8.3.3); open for input entries |
| 27 | [A default output that reads an input](#27-a-default-output-that-reads-an-input) | open |
| 28 | [A value of another type than its output](#28-a-value-of-another-type-than-its-output) | decided by DMN 1.5 for an entry whose type shows it (8.3.3); open for a value known only when evaluated |
| 29 | [A position, length or scale that is not a whole number](#29-a-position-length-or-scale-that-is-not-a-whole-number) | decided by the TCK |
| 30 | [The regular expressions of `matches`, `replace` and `split`](#30-the-regular-expressions-of-matches-replace-and-split) | decided by DMN 1.5 and the TCK where they say; open beyond |
| 31 | [`string()` of a list, a context, a range or a function](#31-string-of-a-list-a-context-a-range-or-a-function) | open |
| 32 | [The zone of `now()` and the day of `today()`](#32-the-zone-of-now-and-the-day-of-today) | open |
| 33 | [Real numbers the TCK states to some digits](#33-real-numbers-the-tck-states-to-some-digits) | open |
| 34 | [A boxed condition that is not a boolean](#34-a-boxed-condition-that-is-not-a-boolean) | decided by the TCK for a value that is not a boolean; open for null |
| 35 | [A business knowledge model that invokes itself](#35-a-business-knowledge-model-that-invokes-itself) | open |
| 36 | [A decision service nothing invokes, and a decision without logic nothing requires](#36-a-decision-service-nothing-invokes-and-a-decision-without-logic-nothing-requires) | settled: Praecepta reads both |
| 37 | [What a decision service evaluates, and in what order it takes its arguments](#37-what-a-decision-service-evaluates-and-in-what-order-it-takes-its-arguments) | decided by the TCK for the order; open for decisions it does not list |
| 38 | [Invoking a function nothing names](#38-invoking-a-function-nothing-names) | decided by the TCK; followed |
| 39 | [A built-in function as a value, of a function type](#39-a-built-in-function-as-a-value-of-a-function-type) | decided by the TCK for binding; open for `instance of` |

How Praecepta returns DMN's null result is a choice of representation, not of semantics:
see [the end](#how-a-null-result-is-returned).

## 1. `-` and a missing input
- **DMN 1.5.** 8.1: an input entry `-` means "irrelevant", and every value of the input expression
  satisfies it; 8.2.8: it can be used to mean any input value. But 10.3.2.10, on the same entry (grammar
  rule 15.c): when no input values are given, the input satisfies it when `e != null`; when they are
  given, when `FEEL(e in (v))=true`. Read that way, a missing input satisfies `-` in no table.
- **TCK.** No decision-table test case gives a table a missing input.
- **Praecepta.** `-` (and a blank cell) matches every value, a missing one included, with or without
  declared input values.

## 2. Values outside the declared input values
- **DMN 1.5.** 8.2.4: the input values are "a list of unary tests that must be satisfied by the
  corresponding input". 10.3.2.10 uses them only for `-` (case 1); nothing says what a table returns for a
  value outside them.
- **TCK.** No test.
- **Praecepta.** Such a value throws `InvalidInputException` before any rule is matched. A missing value
  is always allowed.

## 3. An interval whose ends are inputs that are missing or out of order
- **DMN 1.5.** 10.3.2.7: the endpoints "must be ordered such that range start <= range end", without saying
  what the range is otherwise. Table 55 expands `e1 in [e2..e3]` to `e1 >= e2 and e1 <= e3`, which by
  Table 50 is false, not null, when one comparison is false and the other null.
- **TCK.** No test.
- **Praecepta.** With literal ends, Praecepta rejects an interval that starts after it ends when it parses
  the cell. With ends that name inputs (`[low..high]`, [#51](https://github.com/brunopacheco1/praecepta/issues/51)),
  a missing end makes the test unknown, so neither the cell nor its `not(...)` matches. (Table 55 read
  literally would let `not([low..high])` match a value above `high` when `low` is missing.) Ends out of
  order make the test false, so its `not(...)` matches.

## 4. `=` between a time or date-time with a zone and one without
- **DMN 1.5.** Table 54 defines `<`: "If one input has a null timezone offset, that input uses the timezone
  offset of the other input." Table 53 defines `=` as `value(e1) = value(e2)` and does not mention the
  case.
- **TCK.** `0068-feel-equality` has these tests commented out, pending a clarification of equality with
  zones.
- **Praecepta.** `=` follows the rule of Table 54, because 10.3.2.15 defines `e1 <= e2` as
  `e1 < e2 or e1 = e2`: the two are equal when their local times are. A zone's offset is the one it has at
  the value's own date and time.

## 5. Two times with different offsets
- **DMN 1.5.** 10.3.2.3.4: a time is a value of the XML Schema time datatype, and one with an offset is
  comparable to all time values that have UTC forms; its value is the number of seconds since midnight,
  with no word on the offset.
- **TCK.** Only `time("10:30:00Z") = time("10:30:00+00:00")`; `time("10:30:00+02:00") =
  time("10:30:00+01:00")` is commented out.
- **Praecepta.** By instant, as XML Schema compares them: `time("09:00:00+01:00")` equals
  `time("08:00:00Z")`.

## 6. COUNT when no rule matches
**Settled.** DMN 1.5, 10.3.2.10, step 2, deals with "no rules match" before any aggregation: the
result is the default output value if one is specified, else null. Step 3b aggregates only the rules that
match. So when no rule matches, Praecepta gives the default output, or no output, under every aggregation,
COUNT included (it does not give 0). The engine, the generated classes and the framework integrations all do so.

## 7. A `null` default output value beside output values
- **DMN 1.5.** Table 46: "The default output value is one of the output values." A `null` default is not
  one of them (FEEL's `null in ("Approved", "Declined")` is false), yet 10.3.2.10, step 2, gives null itself
  when no default is specified, and the specification does not say what an engine does with a model that
  breaks the constraint.
- **TCK.** No test declares a `null` default.
- **Praecepta ([#92](https://github.com/brunopacheco1/praecepta/issues/92)).** Praecepta accepts a `null`
  default whatever the output values, and treats it as a default: with several outputs, defaults that are
  all `null` give an output of nulls rather than none. It rejects any other default outside the output
  values when it reads the table.

## 8. A complete table, and missing inputs
- **DMN 1.5.** 10.3.2.10: "A complete decision table SHALL NOT specify a default output value." 8.2.4: a
  table is complete when "its rules cover all combinations of expected input values"; whether a missing
  input is an expected value is not said. With declared input values, 10.3.2.10's reading of `-` (case 1)
  suggests it is not; without them, nothing says.
- **TCK.** No test has a complete table with a default output; the TCK's tables with defaults are all
  incomplete.
- **Praecepta ([#91](https://github.com/brunopacheco1/praecepta/issues/91)).** A missing value counts as an
  input in every column, because the engine accepts one (case 2) and `-` matches it (case 1). So Praecepta
  reports a table as complete (`UNREACHABLE_DEFAULT_OUTPUT`, an ERROR) only when its default can never be
  given. It does not report a table whose rules cover every declared value but not a missing one: a missing
  input gives that table's default. For the same reason the search counts every list a list column takes (a
  missing list, the empty list and lists holding missing elements), and a time with a zone id wherever a
  time column takes one (case 9).

## 9. A time with a zone id against a time in another zone, at an offset or without a zone
- **DMN 1.5.** 10.3.4.1 lets a time name a zone id (`time("10:30:00@Europe/Paris")`), which has no offset
  without a date. Neither 10.3.2.3.4 nor Tables 53 and 54 say how such a time compares with one in another
  zone, with one at an offset, or with one without a zone. One example decides a narrower case: Table 72, for
  `time(from)`, has `time("23:59:00z") + duration("PT2M") = time("00:01:00@Etc/UTC")` true, so a time in a
  zone with one fixed offset (`Etc/UTC`) equals the time at that offset. The TCK points the same way for
  date-times (`0068-feel-equality`: `date and time("2018-12-08T00:00:00+00:00") = date and
  time("2018-12-08T00:00:00@Etc/UTC")`).
- **TCK.** `0068-feel-equality` compares two times in the same zone, and leaves the other cases out
  "pending RTF equality comparisons with zones clarification".
- **Praecepta ([#82](https://github.com/brunopacheco1/praecepta/issues/82)).** Two times in the same zone
  compare by local time. Date-times compare by instant, a zone id by the offset it has at the date and time, so
  `0068-feel-equality`'s case holds for them.

  **A zone with one fixed offset** (`Etc/UTC`, `Etc/GMT-2`, `UTC`: a zone whose rules never change its offset)
  follows Table 72: a time in it is the time at that offset. This holds wherever the time is written (cells, input
  value lists, output entries and expressions, map-based inputs, `time(date and time(...))` of a date and time in
  such a zone) or given (a `ZonedTime` bound with `zonedTimeInput` or held by a list). So
  `time("00:01:00@Etc/UTC")` matches `00:01:00Z` and `02:01:00+02:00`, and compares, subtracts and aggregates as
  they do. A column bound with `zonedTimeInput` takes times at an offset too, and the analyses look for overlaps
  and gaps among them. Praecepta writes such a time at its offset: the DMN writer writes `time("00:01:00Z")`,
  which reads back equal, and an output that names or computes it gives `00:01:00Z`. An output written as text
  keeps the zone id. The TCK expects `string(time("00:01:00@Etc/UTC"))` to keep it too
  (`1116-feel-time-function`): a [known deviation](#known-deviations). The generated classes give an `OffsetTime`
  to a time column whose zone ids all have a fixed offset, and a `ZonedTime`, which may be in a zone with a fixed
  offset, to one that mixes zone ids with offsets.

  **Still open:** how a time in a zone whose offset changes (`Europe/Paris`) compares with one in another zone, at
  an offset or without a zone. Praecepta takes such a comparison as one FEEL does not define, which is null
  (10.3.2.2). So neither a test nor its `not(...)` matches, and an interval's ends must be in one zone. A COLLECT
  MIN or MAX over such times in two zones is null, as FEEL's `min` and `max` take a list of comparable items
  (Table 75) and a built-in function is null outside its domain (10.3.2.16). (This happens in the framework
  integrations' map-based engine; the generated classes aggregate numbers only.) A column of map-based inputs
  (or a list's elements) whose cells name no such zone id still takes such a time, which only the cells that
  accept any value match. The analyses of overlaps, unreachable rules and gaps count it
  ([#114](https://github.com/brunopacheco1/praecepta/issues/114)), as the completeness search does (case 8). So
  Praecepta does not report as unreachable a rule that only such a time reaches, and a table whose rules all
  compare in that column has a gap there.

## 10. The difference of two times in different zones, or one with a zone and one without
- **DMN 1.5.** Table 57 defines `e1 - e2` of two times as the difference of their values (10.3.2.3.4), which
  says nothing of offsets or zone ids (case 5); for date-times it makes the subtraction undefined where only
  one has a zone, but it does not say so for times.
- **TCK.** `0100-arithmetic` subtracts times without a zone, at one offset, and in one zone id
  (`@"10:10:10@Australia/Melbourne" - @"11:10:10@Australia/Melbourne"`), and no others.
- **Praecepta ([#85](https://github.com/brunopacheco1/praecepta/issues/85)).** Times subtract as they compare
  (cases 4, 5 and 9):
  - by instant when both have an offset;
  - by local time when one has no offset, or both have a zone id in one zone;
  - to null for a time with a zone id and any time not in its zone. A zone with one fixed offset is the
    exception: a time in it is the time at that offset (case 9).

  By instant, two times are taken on one day, so times at offsets far apart can be more than a day apart:
  `@"00:00:00+14:00" - @"23:59:59-12:00"` is `-PT49H59M59S`. That is beyond the range 10.3.2.3.4 gives `valuet`
  ([0..86400]), within which Table 57's `valuet(e1) - valuet(e2)` would stay. Taking each time's seconds since
  midnight UTC instead would keep that range, but break the TCK's subtractions at one offset:
  `@"10:10:10+11:00" - @"11:10:10+11:00"` is `-PT1H`, where it would give `PT23H`. So neither reading is settled.

## 11. A date and time moved by months onto a local time its zone skips
- **DMN 1.5.** Table 57 moves a date and time by a years and months duration as
  `date and time(date(e1.year + ..., e1.month + ..., e1.day), time(e1))`, keeping its time of day and zone.
  When the zone skips that local time on the new date (a change to daylight saving time), nothing says what
  the result is.
- **TCK.** `0100-arithmetic` moves date-times with a zone id by months only where no time is skipped.
- **Praecepta ([#85](https://github.com/brunopacheco1/praecepta/issues/85)).** Praecepta moves the local time
  forward by the length of the gap, as `java.time` does:
  `date and time("2025-01-30T02:30:00@Europe/Paris") + @"P2M"` is `2025-03-30T03:30:00+02:00`. A days and time
  duration moves a date and time by instant (10.3.2.3.5), so it never meets this case.

## 12. Blank text as a missing value
- **DMN 1.5.** `""` is a string (10.3.2.3.2), distinct from null.
- **TCK.** No decision-table test gives a blank input.
- **Praecepta.** Blank text is a missing value wherever Praecepta reads an input from text or a Java value. This
  holds for a single value, as Excel and CSV cells and maps of text have no other way to leave a value out, and
  for an element of a list from a `Collection` ([#84](https://github.com/brunopacheco1/praecepta/issues/84)). So
  `[""]` is a list holding a missing element, which `list contains(?, null)` matches. A quoted `""` in a cell is
  the empty string.

## 13. A value of another type than the decision table input it feeds
- **DMN 1.5.** A decision's logic reads the variables of its information requirements (7.3.4), and a decision
  table's input expression may declare a `typeRef`, which "specifies the Expression's range of possible values"
  (7.3.1). 7.3.4 requires that "the datatype indicated by the typeRef that is associated with an instance of
  InformationItem SHALL be compatible with the datatype that is associated with the DMN model element from which it
  takes its value", and defines conversions only where a decision's value is bound to its variable (10.3.2.9.4). What
  a table does with an input expression whose value is of another type than its `typeRef` (a string into a number
  input, a list into an input of single values) is not said; FEEL itself would compare the value with the entries of
  the other type and get null for each comparison.
- **TCK.** No test gives a table an input of another type than its input expression declares.
- **Praecepta.** `DecisionGraph` reports it as an ERROR (`TYPE_MISMATCH`) when it builds the graph, and evaluates
  neither that decision nor any that requires it. The same holds for a decision table's column that reads a path
  into a value whose type has no such component. A column of lists takes a single value as a list of one, the
  conversion 10.3.2.9.4 calls "to singleton list". A column of a model whose input expression declares no `typeRef`
  has the type of the input data or decision it reads (7.3.4), so it never mismatches it.

  Where DMN 1.5 decides, Praecepta follows it:
  - **Binding.** A decision's value, a decision table's as well as a literal expression's, is bound to the type
    its variable declares (10.3.2.9.4): a date to a date and time at UTC midnight, a single value to a list as a
    list of one, and a list of one value to that value. Any other value of a type that does not conform
    (10.3.2.9.2) becomes null.
  - **Operands FEEL does not combine.** A literal expression whose operands FEEL's arithmetic does not combine (a
    string and a number, a boolean, a list) is null, as the text before Tables 56 and 58 has it ("if input values
    are not of the listed types, the result is null") and the TCK's `0100-arithmetic` expects. So is a decision
    table's output entry.
  - **A path to a component its type does not have.** In a literal expression, such a path (`Applicant.age` where
    `Applicant`'s item definition has no `age`) is null: the TCK's `0057-feel-context` has `{a: 1}.b` null, with an
    error.
  - **Always null.** Praecepta evaluates a decision whose value is therefore always null, and reports it as a
    WARNING (`ALWAYS_NULL`). For an output entry, the WARNING is in the table's report.
  - **Untyped outputs.** An output that declares no type takes the one type its entries give, as FEEL types them
    (10.3.2.10, step 3c): the entry `10` is the number 10, and `Base * 2` is a number. Where the entries give
    several types, a list or a context, the output gives each entry's own value. An output with an output value list or
    a default output keeps a type, a string where nothing else gives one, as both are written as text.
  - **A table read alone** (`DmnReader.read`) types what reads a name as a string: untyped outputs, and untyped
    input expressions that name what they read, even where the input data they read declares a type. An input
    expression that computes its value (`Age * 12`) is typed as FEEL types it from the file's input data, read
    alone or not.

## 14. The default output of a table with several results
- **DMN 1.5.** 10.3.2.10, step 2: when no rule matches, "if a default output value d is specified, DTI=FEEL(d)", a
  single value, also under RULE ORDER, OUTPUT ORDER and COLLECT, whose results are otherwise lists. Bound to a
  decision whose variable is typed by a list, that value becomes a list of one (10.3.2.9.4, "to singleton list"); a
  decision whose variable declares no type keeps the single value.
- **TCK.** `0109-ruleOrder-hitpolicy` (test 003) expects the list of one, its decision's variable being typed by a
  collection.
- **Praecepta.** DMN 1.5 decides this case, so it is not open. A decision graph gives a table's default output as
  the single value it is, bound to the decision's variable: a list of one where the variable is typed by a list, as
  the TCK expects, and the value itself where the variable declares a single value or no type. The graph types as
  `Any` a decision of no declared type whose table has several results and a default output, as its value is a list
  or that single value. The engine of a table on its own returns the default as one output among the results it
  returns (`[{Status=Declined, Rate=Standard}]`), which is no DMN value.

## 15. A name that both a requirement and a path into another could mean
- **DMN 1.5.** A name may hold a dot (10.3.1.6 on ambiguity; grammar rules 27 to 30), so `Pricing.discount` may name
  a variable of that name or the component `discount` of `Pricing`; scope decides (10.3.2.11), and a decision's
  scope is its requirements (7.3.4).
- **TCK.** No test has both.
- **Praecepta.** A name a decision reads is the requirement of that whole name if the decision has one, else a
  path into the requirement named by its longest part before a dot. A component's own name is not split further:
  `a.b.c` is the path `b`, `c` into `a`. A literal expression is parsed with its requirements' names in scope, and
  the longest name in scope wins wherever the text holds one (case 22). That reads `Pricing.discount` the same way:
  the requirement of that whole name if there is one, else the path `discount` into `Pricing`. A path into a list
  of lists keeps the outer list and gives null for each inner one, as 10.3.2.5 defines the selection only on "a
  list of contexts".

## 16. Input data whose values are not of its type or allowed values
- **DMN 1.5.** An input data's value comes from "a data source attached at runtime" (7.3.4), whose type the
  variable's `typeRef` gives; an item definition's `allowedValues` constrain it (7.3.3). What an engine does with a
  value outside them is not said.
- **TCK.** `0003-input-data-string-allowed-values` gives only allowed values.
- **Praecepta.** A value not of the input data's type (text that is not a number, a list for a number) throws
  `InvalidInputException` when the decision is evaluated, as an input of a table does. Praecepta does not check an
  item definition's allowed values for input data; a decision table still checks its own input values. Blank text
  is a missing value for every simple type but `string`. For a `string`, the engine's `DecisionGraph` and the
  framework integrations' maps of text both pass `""` on as a string, as FEEL has it; a decision table that reads
  it takes it as missing (case 12).

## 17. An input data without a type
- **DMN 1.5.** An input data's variable "may include a typeRef" (6.3.11); without one, its values are of any type.
- **TCK.** Every TCK input data has a `typeRef`.
- **Praecepta.** Its type is that of the decision table columns that read it whole, when they agree; where they do
  not, Praecepta reports a `TYPE_MISMATCH`. Read by path, or by a table that types it otherwise, its value is taken,
  when it is read, as an input data of the column's type would be (text, an `Integer`, an `OffsetDateTime`...); a
  value that is not one throws `InvalidInputException`. A literal expression that reads it is typed when it is
  evaluated: FEEL's arithmetic applies to the values given, and gives null for those it does not combine. The
  framework integrations take such an input data as text under its name, which is a string, or as text under the
  paths a decision reads into it (`Applicant.age`), a context of those components.

## 18. Times and date-times that differ below the millisecond
- **DMN 1.5.** 10.3.2.3.4 makes a time a value of XML Schema's `time` datatype, and 10.3.2.3.6 a date and time one of
  its `dateTime`, compared by their values in seconds, fraction included (Tables 53 and 54); it does not say to what
  precision. XML Schema 1.0 lets an application limit the digits of a fraction of a second it supports, to the
  millisecond at the least, if it documents the limit. So DMN 1.5 leaves the precision open.
- **TCK.** `0068-feel-equality` expects `time("10:30:00.0001") = time("10:30:00.0002")` (`time_005`) and
  `date and time("2018-12-08T00:00:00.0001") = date and time("2018-12-08T00:00:00.0000")` (`datetime_003_a`) true,
  saying "value resolution is seconds"; `1116-feel-time-function` and `1117-feel-date-and-time-function` give times and
  date-times with nanoseconds as their values.
- **Praecepta.** Times and date-times keep their fraction to the nanosecond, as values and as the property `second`
  reads them. FEEL compares them to the millisecond: `=`, `!=`, `<`, `<=`, `>`, `>=`, `between`, `in`, ranges, and
  decision tables' cells beyond the simple unary tests leave out what is below it, so both TCK nodes pass. The
  difference of two such values (`-`) keeps their nanoseconds: `@"10:30:00.0002" - @"10:30:00.0001"` is
  `PT0.0001S`, though the two are equal.

  Three things compare to the nanosecond: a decision table's simple cells, which its bitset index and its analysis
  evaluate; the cells that compare with another input; and the MIN and MAX of a COLLECT. The index works out its
  classes, gaps and overlaps to the nanosecond. So a cell `@"10:30:00.0001"` does not match `10:30:00.0002`, where
  the same test in a FEEL cell, `= @"10:30:00.0001" + @"PT0S"`, does. No TCK node has such a cell.

## 19. Hour 24 in a time and a date and time
- **DMN 1.5.** 10.3.4.1 reads a time string in XML Schema's lexical form for `time`, which takes `24:00:00` as
  `00:00:00`; so `time("24:00:00")` is midnight. It composes a date time string from a date string, `T` and a time
  string, which says what the time is but not on which day `24:00:00` falls.
- **TCK.** `0100-arithmetic` has `@"2021-01-01T24:00:00" + @"PT1S"` give `2021-01-02T00:00:01`, the first instant of
  the next day, as XML Schema's `dateTime` reads it; `1116-feel-time-function` and `1117-feel-date-and-time-function`
  have `24:00:01` and `24:01:00` invalid.
- **Praecepta.** A time at `24:00:00` (with any zero fraction, and any offset or zone),
  `time("24:00:00")` or `@"24:00:00Z"`, is `00:00:00`. A date and time at `24:00:00`, `@"2021-01-01T24:00:00"` or
  `date and time("2021-01-01T24:00:00")`, is midnight of the next day. Any other time past `23:59:59` is invalid,
  which FEEL makes null, with an error. Decision tables' cells read them as literal expressions do:
  `< @"2021-01-01T24:00:00"` holds for the values before `2021-01-02T00:00:00`.

## 20. Which nulls are errors
- **DMN 1.5.** Where FEEL defines no value, the value is null (10.3.2.15's semantic mappings, and the text before
  Tables 56 and 58: "if input values are not of the listed types, the result is null"). 10.3.2.16 says that a built-in
  function given input outside its domain "SHOULD report or log diagnostic information if appropriate and SHALL return
  null"; it does not say which other nulls are errors.
- **TCK.** Its `errorResult` nodes say: arithmetic with a null operand (`10 + null`) or operands of types it does not
  combine, a division by zero, `=` between values of two types (`1 = "1"`, `[100] = 100`), comparisons and `between`
  with null, `in` against a range with a null included endpoint, a context with a key twice, a path to an entry a
  context does not have or a property a value does not have, an iteration over what is not a list, an invalid date
  and time literal. Each is null, with an error. An index beyond a list (`[1, 2, 3][4]`, `[1, 2, 3][0]`,
  `[1, 2, 3][-4]`: `0069-feel-list`'s `decision002`, `decision003` and `decision008`) and a range open at a null
  endpoint (`5 in (null..10]`) are plain nulls, not error results, and `true in [false, 2, 3]` is false, as
  `list contains` is.
- **Praecepta.** Each error result is null with an error; `DecisionGraph.evaluateWithErrors` and
  `FeelExpression.evaluate(values, errors)` give the errors. So is any other null an operation gives from values: a
  comparison of values FEEL does not order, an invocation of what is not a function or with the wrong arguments, an
  invocation that names an argument twice, `instance of` a type FEEL does not have, the limits of case 23. An index
  beyond a list is null without an error, as the TCK has it. `and`, `or`, `if` and `instance of` a type FEEL has
  report no error, as FEEL defines them for every value. Neither does the condition of a filter of an empty list,
  which tests no element. Praecepta reports a test's null (`5 in (null..10]`), which the TCK leaves open: it reports
  an error where the value is null, and that only matters where null is the value. A decision table's output
  entries report their errors as a literal expression does. Its input entries report none: a cell FEEL gives null
  for is a rule that does not match, which is all a decision table asks of it, and reporting each rule a value does
  not match would be noise.

## 21. A filter whose condition is a number for some elements only
- **DMN 1.5.** 10.3.2.5: `e1[e2]` is the element at a position when `e2` is a number, and the elements for which it
  is true when it is a boolean; `e2` is evaluated with each element in scope, so it could be either. A value that is
  not a list is filtered as the list of one it converts to (10.3.2.9.4; 10.3.2.5, and Table 45's `3[item > 2]`, which
  is `[3]`): that much DMN 1.5 decides.
- **TCK.** `0069-feel-list` filters by a number literal, a boolean literal, or a test of each element.
- **Praecepta.** The condition's value for the first element decides: a number makes the filter an
  index (whatever the condition gives for other elements), anything else a test, in which only `true` keeps an
  element. An empty list filtered by a number is null (case 20: no error); filtered by anything else, it is the
  empty list. To tell the two apart, Praecepta evaluates the condition with `item` null, and reports no error from
  it. A value that is not a list is filtered as a list of one.

## 22. A name in no scope
- **DMN 1.5.** 10.3.1.2 lets names hold spaces, keywords and `. / - ' + *`, and resolves them by scope (10.3.2.11);
  it does not say how an expression reads a name that is in no scope.
- **TCK.** Its names are all in scope, but for the entries of the elements a filter tests (`0069-feel-list`:
  `[{a: 1}, {a: 2}][a > 1]`).
- **Praecepta.** A name in scope is read as the longest name in scope the text holds, with whitespace where the
  name has a space and none where it has none. So `Date-Time` is a name where it is in scope, and `Date - Time`
  otherwise. A name in no scope is read as words separated by whitespace, up to a keyword (`and`, `or`, `in`,
  `between`, `instance`, `then`, `else`, `return`, `satisfies`) or anything that is not a word. Outside a filter,
  it is a name the decision reads, which no requirement provides (`MISSING_REQUIREMENT`). Inside one, it is an
  entry of the element tested, null where the element has none. A built-in function's name is in scope only where
  it is invoked, so an entry named `count` reads as the entry.

## 23. Limits of one evaluation
- **DMN 1.5.** FEEL defines no limit; a function may invoke itself (10.3.2.13), and `for` iterates any list.
- **TCK.** Its expressions are small.
- **Praecepta.** Each evaluation has limits, so that an expression of a few characters takes bounded time, memory
  and stack:
  - **Nesting**, a syntax error beyond it: an expression's tree nests at most 300 levels, and its text at most 1,751 of
    the parser's calls, which is 250 levels of parentheses, brackets or braces, 218 of `if` or `function`, 194 of
    `1 + (...)`, 74 of `for`, `some` or `every` (each four levels of the tree), 149 of filters, invocations, `in` or
    contexts (two levels each), and 582 of types (`list<list<...>>`). Every construct nested as deeply as it may be is
    parsed, typed and evaluated on a thread stack of 448 KiB (checked in an interpreted JVM), an eighth less than
    512 KiB, half the default thread stack of Linux on x64.
  - **Steps**: one evaluation takes at most 1,000,000: an iteration of a `for`, `some` or `every` is one, and so is
    each element of a range it iterates (`1..1000`, which holds none of them, is a thousand when it is made), a
    function invocation, an element a filter tests, a path projects (`xs.a`) or `in` looks through, and an element or
    entry equality and `instance of` look at. A built-in function counts a step for each element or entry it looks
    at, each comparison of a sort, and each hundred characters a regular expression reads (case 30). A function
    body nests at most 800 levels with the expression's own depth.
  - **Memory**: the strings `+` and the built-in functions make in one evaluation hold at most 10,000,000 characters in
    all; the steps that make other values bound them. An error's message quotes a string by its first 64
    characters, and is built only where errors are reported. One evaluation reports at most 100 errors, then one
    that counts the others.
  - Beyond the steps or the characters, the evaluation stops, and its value is null, with an error; beyond the nesting
    of function bodies, the invocation that goes beyond it is null, with an error.
  - Each evaluation has limits of its own: a literal expression's, each of a decision table's cells and output
    entries for each rule it is applied to, and each invocation from Java of a function value
    (`FeelFunction.invoke`). A function value invoked inside an evaluation counts against that evaluation.

## 24. A unary test that does not name `?` and gives a boolean
- **DMN 1.5.** Grammar rule 13 makes a positive unary test any expression, and 7.3.2 says when unary tests are
  satisfied: (a) an expression's value equals the input value; (b) its value is a list the input value is in; (c) its
  value is a function that is true applied to the input value; (d) it is "a boolean expression using the special '?'
  variable" that is true with the input value as `?`. Only (d) makes an expression a test of the input, and only one
  that names `?`; an expression that does not name `?` but gives a boolean (`Income > 2 * Rent` in a column of another
  input) is (a), a value the input equals. Table 55's last row reads `e1 in e2`, for `e2` a boolean expression that
  uses `?`, as `{? : e1, r : e2}.r` likewise.
- **TCK.** Its boolean columns (`0004-simpletable-U` to `0007-simpletable-P2` among the decision tables, and
  `0072-feel-in` for `in`) hold `true` and `false`, which match only those values, as (a) has them.
- **Praecepta.** As DMN 1.5 has it: such a
  test is a value, compared with the input (`true in [true]`), so `Income > 2 * Rent` in a boolean column holds when
  the column's value equals it. A test that names `?` is a test (`? > 5 and ? < 10`); one whose value is a function is
  applied to the input (`function(x) x > 3`); and `= e` and `!= e` compare the input with `e` (`= null` holds for a
  missing value, as `null` does).

## 25. Names a table read alone does not have
- **DMN 1.5.** A decision's logic reads the variables of its information requirements (7.3.4), typed by their
  `typeRef`. A decision table read on its own, outside a model, has no requirements to read a name from.
- **TCK.** It evaluates decisions within their models.
- **Praecepta.** A table's FEEL (a cell beyond the simple unary tests, an input expression, an output entry) may
  read a name that is not one of its columns. That name is an input of the engine, as is an input a simple cell
  compares with. It is bound by its own getter. With map-based inputs, it is read from text with the type
  `columnType(...)` declares, else that of the column or output whose entry reads it first
  (`DecisionTable.namedInputs`). In a decision graph it is the value of the requirement it names, of that
  requirement's type, as DMN 1.5 has it.

## 26. A literal entry of another type than its column or output
- **DMN 1.5.** For outputs, 8.3.3 decides: "The ith outputEntry SHALL be consistent with the typeRef of the ith
  OutputClause", so a model whose output entry is a literal of another type than its output's is not valid. For input
  entries it says only that "the ith inputExpression must satisfy the ith inputEntry"; FEEL compares a number with a
  string as null (Table 53), so a cell `"10"` in a number column holds for no input, and whether a model with such a
  cell is valid is not said.
- **TCK.** No test has one.
- **Praecepta.** When it reads a table, Praecepta rejects an output entry or a default output that is a single
  literal of another type than its output (`"one"` in a number output), or a date and time literal that gives no
  value (`time("08:00:00+14:01")`): 8.3.3 makes the model invalid. It also rejects a cell that is a literal, a
  comparison with one or an interval between two (the simple unary tests the bitset index evaluates) of another
  type than its column's, and a cell whose interval starts after it ends. Such a cell can never hold, and the table
  is wrong where it can be shown to be; for input entries, that is Praecepta's choice. An entry that computes a
  value is FEEL (cases 13 and 28): null where its operands do not combine, with a WARNING.

## 27. A default output that reads an input
- **DMN 1.5.** 10.3.2.10, step 2: "if a default output value d is specified, DTI=FEEL(d)", an expression; Table 46:
  "The default output value is one of the output values", a value.
- **TCK.** Its default outputs are literals.
- **Praecepta.** A default output is a literal, or a FEEL expression that reads no input (`-1`, `"N/" + "A"`). Its
  value is the same whatever the inputs, so Praecepta takes it when it reads the table. It rejects a default
  output that reads an input, which no output value list could hold.

## 28. A value of another type than its output
- **DMN 1.5.** An output clause of a table with several outputs may declare a `typeRef`; that of a table with one
  output "SHALL NOT specify a typeRef" (Table 34), its decision's variable typing the table's value. 8.3.3 requires
  that "The ith outputEntry SHALL be consistent with the typeRef of the ith OutputClause", so an entry whose type shows
  it gives a value of another type makes the model invalid. 10.3.2.9.4 binds a value to a declared type where a
  decision's value is bound to its variable; what a value known only when the entry is evaluated (of a name of no
  declared type, say) becomes when it is not of its output's type is not said.
- **TCK.** Its output entries give values of their output's type.
- **Praecepta.** An output entry's value is bound to its output's type as 10.3.2.9.4 binds a decision's: as it is
  where it conforms, a date to a date and time at UTC midnight, a list of one to its element, and null otherwise.
  8.3.3 makes invalid an entry whose type shows that its value is not of its output's type. Praecepta does not
  reject it, as it does a literal entry of another type (case 26): it reports a WARNING (`ALWAYS_NULL`) when the
  table is built, and evaluates the entry to null. It reads a single output clause's `typeRef` where one is given,
  though Table 34 says the clause has none. A string output takes any value as its text: unquoted numbers and
  dates become their text.

## 29. A position, length or scale that is not a whole number
- **DMN 1.5.** `substring`, `sublist`, `insert before`, `remove` and `list replace` take positions and lengths, and
  `decimal`, `floor`, `ceiling` and the `round` functions a scale, each a number (10.3.4); what one that is not whole
  means is not said.
- **TCK.** `substring("foobar",3,3.8)` is `"oba"` (`1103-feel-substring-function`), `decimal(1/3, 2.5)` is `0.33`
  (`1100-feel-decimal-function`), and `list replace([1,2,3], 2.5, 4)` and `list replace([1,2,3], -1.5, 4)` are
  `[1, 4, 3]` and `[1, 2, 4]` (`1155-list-replace-function`): the number's whole part.
- **Praecepta.** Every position, length
  and scale is its number's whole part, toward zero, as the TCK has them.

## 30. The regular expressions of `matches`, `replace` and `split`
- **DMN 1.5.** `matches` and `replace` are XQuery 1.0 and XPath 2.0 Functions and Operators' `fn:matches` and
  `fn:replace` (7.6), and `split` splits by a pattern of the same kind (10.3.4.3), whose regular expressions are XML
  Schema's with XPath's additions, and its flags `s`, `m`, `i`, `x` and `q`.
- **TCK.** `1109-feel-replace-function`, `1111-feel-matches-function` and `0067-feel-split-function` test flags,
  back-references, block escapes (`\p{IsBasicLatin}`), whitespace with `x`, and the patterns that are errors: an
  unknown flag, a back-reference to a group the pattern does not have, a pattern that matches the empty string in
  `replace`.
- **Praecepta.** Java's regular expressions read the pattern. First Praecepta writes XML Schema's character class
  subtraction (`[a-z-[aeiou]]`) as Java's intersection and its block escapes (`\p{IsBasicLatin}`) as Java's
  (`\p{InBasicLatin}`); with `x`, it removes the whitespace outside character classes, as XPath has it. Praecepta
  follows every case the TCK has. It reads a pattern that XML Schema does not allow but Java does, such as a
  possessive quantifier (`a*+`) or an inline flag (`(?i)`), as Java reads it, and does not reject it. Matching
  counts against the evaluation's steps (case 23). So a pattern that backtracks without end, such as `(.*a){12}!x`,
  gives null with an error. So does a match that Java's regular expressions would make by recursing deeper than the
  thread's stack, as they do for an alternation repeated over a long text (`(a|b)*` over a hundred thousand
  characters).

## 31. `string()` of a list, a context, a range or a function
- **DMN 1.5.** `string(from)` converts "from" to a string (Table 72), and its examples are a number and a string;
  FEEL's other values are written by their literals elsewhere, but what `string()` gives for a list, a context, a range
  or a function is not said.
- **TCK.** Its cases convert simple values: numbers, strings, dates, times, date-times and durations.
- **Praecepta.** A simple value is written
  as its text (`1.5`, `2025-01-31T08:30:00@Europe/Paris`, `P1DT2H`), a string as it is; a list as `[1, "a", null]` and
  a context as `{a: 1}`, the strings in them quoted, each element written as it would be on its own; a range as FEEL
  writes it (`[1..10)`); a built-in function by its name. `string(null)` is null.

## 32. The zone of `now()` and the day of `today()`
- **DMN 1.5.** `now()` is "the current date and time" and `today()` "the current date" (10.3.4.11), whose zone is not
  said.
- **TCK.** `1148-feel-now-function` and `1149-feel-today-function` check their types alone.
- **Praecepta.** `now()` is the date and
  time in the JVM's default zone, with its zone id, to the millisecond; `today()` the date there. Each invocation reads
  the clock, so two in one expression may differ.

## 33. Real numbers the TCK states to some digits
- **DMN 1.5.** A number is a decimal128 (10.3.2.3.1): 34 significant digits. `exp`, `log`, `sqrt` and `stddev` give
  real numbers that no decimal holds exactly, so FEEL's value is the real number rounded to 34 digits.
- **TCK.** It writes such results to fewer digits, cut or rounded from a double's 15 or so: `exp(4)` is `54.59815003`
  (`0052-feel-exp-function`), the sample standard deviation of `[2, 4, 7, 5]` is `2.0816659994661`
  (`0063-feel-stddev-function`), `5 ** 2.55` is `60.58617166606` (`0100-arithmetic`). Two values of
  `0008-LX-arithmetic`, the monthly payment of a loan, are off in their last digits: for a principal of 30,000 at 4.75 %
  over 60 months it states `562.707359373292`, where the payment is `562.7073593732659271562143285576...` (and a double
  gives `562.7073593732719`).
- **Praecepta.** FEEL's values have their
  34 digits. `praecepta-tck` takes a number the kit writes to at least 8 significant digits as the real number it
  states: a result of more decimals that is less than one unit of its last digit away from it passes. A number of fewer
  digits, such as `0.33`, is compared exactly. The two payments of `0008-LX-arithmetic` that the kit states wrongly
  fail. So do the seven payments of the same formula that a business knowledge model computes, which the kit states
  as a double computes them (`0009-invocation-arithmetic`, `0003-iteration`, `0004-lending`,
  `0005-literal-invocation`, `0014-loan-comparison`).

## 34. A boxed condition that is not a boolean
- **DMN 1.5.** A boxed conditional, filter and iterator (10.2.7 to 10.2.9) are FEEL's `if`, filter, `some` and `every`
  in boxes. In FEEL text, `if` gives its `else` value for a condition that is not true, so `if "abc" then 1 else 2` is
  `2`, and a filter or quantifier counts only `true`.
- **TCK.** The boxed forms are stricter: a conditional whose `if` is `"abc"`, a filter whose `match` is a string for
  some element, and `some` or `every` whose `satisfies` is a string for some element are null, with an error
  (`1150-boxed-conditional` to `1154-boxed-every`), even where another element satisfies `some`. It has no boxed
  condition that is null.
- **Praecepta.** A boxed condition that
  is neither a boolean nor null makes its construct null, with an error; a null condition is not true and gives no
  error, as in FEEL. A boxed filter's `match` that is a number is not a boolean either: the filter is null, with an
  error, where a filter in FEEL text selects the element at that position (case 21). The FEEL forms are unchanged.

## 35. A business knowledge model that invokes itself
- **DMN 1.5.** A business knowledge model reads its parameters and the business knowledge models its knowledge
  requirements name (6.3.10, 7.3.4); it does not say whether one may name itself, or whether such requirements may form
  a cycle, as decisions' information requirements may not.
- **TCK.** It has no such model.
- **Praecepta.** A business knowledge
  model's own name is in scope in its logic, as an entry's is in a context it defines a function in (10.3.2.6), so it may
  invoke itself, and business knowledge models that require one another in a cycle are not reported. Each is a
  function made once, so a cycle only recurses when invoked, and an invocation that never ends is null, with an error,
  beyond the evaluation's limits (case 23).

## 36. A decision service nothing invokes, and a decision without logic nothing requires
- **DMN 1.5.** A decision service (6.3.9) is an invocable that encapsulates decisions; a decision's logic is optional
  (6.3.7), as for a decision a person makes.
- **TCK.** `0087-chapter-11-example` has two decision services that nothing invokes, and a decision without logic
  that no decision requires; its decision tables are evaluated from the test cases' input data alone.
  `0088-no-decision-logic` gives the value of a decision without logic that others require.
- **Praecepta.** Praecepta reads every decision service, whether anything invokes it or not, and evaluates it when
  invoked (case 37). It reads every decision without logic (`DecisionLogic.NoLogic`). The graph evaluates neither
  such a decision nor a decision that requires it, and reports neither: the model is valid, and the value is a
  person's to give. A decision service that takes such a decision as an input decision is given that value as an
  argument, as `0088-no-decision-logic` has it.

## 37. What a decision service evaluates, and in what order it takes its arguments
- **DMN 1.5.** A decision service (6.3.9) has output decisions, encapsulated decisions, input decisions and input
  data; invoked, it evaluates its output decisions from its inputs, and its value is its one output decision's, or a
  context of them.
- **TCK.** `0085-decision-services` invokes services positionally with their input data first, then their input
  decisions, whatever order the service lists them in (`decisionService_011("A", "B", "C", "D")`), and by parameter name;
  an input decision is not evaluated, its argument taking its place ("I never get invoked"), and invoking a service
  leaves the model's own decisions and input data as they are. An argument of another type than its parameter's makes
  the invocation null, with an error (`decision_007_1`).
- **Praecepta.** As the TCK has it. A service evaluates its output decisions and every decision they require,
  directly or not, except its input decisions. It does so whether it lists those decisions as encapsulated or not:
  it evaluates one it does not list as it would an encapsulated decision. One of those that requires input data the
  service does not take could only read null. Praecepta reports it (`MISSING_REQUIREMENT`), and evaluates neither
  the service nor what invokes it. Arguments are bound to their parameters' types as a function's are
  (10.3.2.9.4), and the service's value to the output type of the function type its variable declares, if any.

## 38. Invoking a function nothing names
- **DMN 1.5.** A decision reads only what its requirements provide (7.3.4), and invoking what is not a function is
  an error, whose value is null (10.3.2.16); it does not say whether invoking a name nothing provides makes the
  model invalid or the decision null.
- **TCK.** `1131-feel-function-invocation` has `non_existing_function()` be null, with an error, as it has `null()`
  and `"abs"(-1)`.
- **Praecepta.** As the TCK has it. An invocation of a name that no requirement, and no element of the model,
  provides is null, with an error. The graph reports the decision as always null (`ALWAYS_NULL`, a WARNING), so
  `failOn(Severity.WARNING)` rejects it; it is not a missing requirement, and blocks nothing. An invocation of an
  element of the model that the decision does not require, such as a business knowledge model it forgot, is a
  missing requirement (`MISSING_REQUIREMENT`, an ERROR), so a model's slip is not taken for a null.

## 39. A built-in function as a value, of a function type
- **DMN 1.5.** A function conforms to a function type whose parameters conform to its own and whose result its own
  result conforms to (10.3.2.9.1); built-in functions are named in every scope (10.3.4), but their types are not
  given, and some give values of several types (`abs` of a number is a number, of a duration a duration).
- **TCK.** `0092-feel-lambda` passes `abs` and `sqrt` to a business knowledge model whose parameter is a
  `function<number> -> number`, and expects `abs` to be invoked through it (`decision_014_1`); a built-in passed
  where it does not fit, `sqrt` for a function of two parameters, is null with an error (`decision_016_1`).
- **Praecepta.** In a graph, a name of a decision or business knowledge model that no requirement provides, but a
  built-in function does, is that function as a value. A table read alone reads every unknown name as an input, so
  there it is not (a column may be named `date` or `count`). A built-in's type gives its result as `Any` where it
  depends on its arguments, and `Any` conforms to no other type. So `abs` is not of the type
  `function<number> -> number`: `abs instance of function<number> -> number` is false. Bound to that type, as an
  argument or a declared value, `abs` is taken where its parameters fit. Each of its results is then bound to
  `number` as a value of a declared type is (10.3.2.9.4): a duration, which is not one, is null with an error. So
  binding follows the TCK, and `instance of` answers by the type the function has.

## Known deviations
Where DMN 1.5 or the TCK decides and Praecepta does otherwise. Praecepta reports or documents each where it shows.

- **External functions are not evaluated, by design.** DMN 1.5 lets a model define a function in Java or PMML. The
  model does so in FEEL
  (`function(d1, d2) external {java: {class: "java.lang.Math", method signature: "max(double,double)"}}`, 10.3.2.13.3)
  or as a boxed function definition of kind `Java` or `PMML` (10.2.3). The TCK's `0076-feel-external-java` invokes
  static methods of `java.lang` classes that way (18 result nodes, all Java, none PMML). Praecepta runs no code from
  outside the model it evaluates: evaluating a DMN file must not reach the class path, the file system or anything
  else of the application around it, whatever the file says ([security assurance](SECURITY-ASSURANCE.md)). A list of
  classes deemed safe would be a judgement each application would have to trust. So Praecepta rejects a model that
  defines an external function when it reads it (an `UnsupportedFeatureException`), or, for a literal expression of
  a graph built in code, reports it (`UNSUPPORTED_FEATURE`). The TCK's 18 nodes are not supported. This choice is
  permanent: Praecepta's claim of DMN 1.5 conformance, planned for 1.0, will name it as its one deliberate
  exception, for security.
- **A time in a zone with one fixed offset keeps no zone id.** `time("00:01:00@Etc/UTC")` is the time at that zone's
  offset, `00:01:00Z`, as Table 72's example of `time(from)` has it equal (case 9). But the TCK also has
  `string(time("00:01:00@Etc/UTC"))` be `"00:01:00@Etc/UTC"` (`1116-feel-time-function`) and
  `is(@"23:00:50@Etc/GMT", @"23:00:50Z")` be false (`0103-feel-is-function`), which keep the zone id apart from the
  offset. Praecepta gives `"00:01:00Z"` and true, so these two result nodes fail. Keeping the zone id would change
  how Praecepta reads every time, in decision tables' cells, their bitset index and generated code too.
- **A literal expression's own `typeRef`.** A literal expression's value is bound to the type its decision's variable
  declares (10.3.2.9.4). Praecepta reads the literal expression's own `typeRef` only where the variable has none,
  and does not report one that differs from the variable's, though 7.3.1 says that "the referenced type SHALL be the
  same as the type of the containing Decision element". (A value bound to a context type conforms as 10.3.2.9.2 has
  it: `{a: 1}` bound to `context<a: number, b: string>` is null, with an `ALWAYS_NULL` WARNING, and a context with
  components beyond the type's keeps them.)

## How a null result is returned
DMN 1.5 gives a decision table one result, which may be null. Praecepta's engine returns a list of
outputs (`List<O>`), the generated classes an `Optional` or a `List`, and the framework integrations a list
of maps. They return DMN's null in two ways. DMN decides each case; only the representation is
Praecepta's:
- **No output** (an empty list, an empty `Optional`, no map) when no rule matches and the table declares no
  default output (10.3.2.10, step 2: "else DTI=null"), whatever the hit policy or aggregation.
- **An output whose value is null** (`[{fee=null}]`, `FeesOutput[fee=null]`) when a rule, a default or an
  aggregate gives null for an output: a matching rule's `null` entry or an expression with a missing
  operand; a `null` default output value (case 7); and a SUM, MIN or MAX over a matching rule whose output is
  missing, as FEEL's `sum`, `min` and `max` are null for a list that holds null (10.3.4.4, 10.3.2.16;
  [#90](https://github.com/brunopacheco1/praecepta/issues/90)), or over values that do not compare (case 9).

With one output and a single-hit policy or an aggregation, both mean DMN's null. With several outputs, the
second is a context with null entries, which DMN tells from null. Under COLLECT without an operator, RULE
ORDER and OUTPUT ORDER, the result is a list of the matching rules' outputs (10.3.2.10, steps 3a and 3b). There
the same `[{fee=null}]` stands for two DMN results that the representation does not tell apart: DMN's `[null]`,
when one rule matches and its entry is `null`, and DMN's null, when no rule matches and the default is
`null` (step 2a).

A decision of a decision requirements graph has DMN's value itself, so each evaluator tells more of these apart:
- **`DecisionGraph`** returns Java's `null` for DMN's null, a `List` for a list (`[null]` is a list holding
  `null`, distinct from `null`), and a `Map` for a context, whose components may be `null`.
- **The generated decision classes** return what the graph returns, as their Java types: `null` for DMN's null, a
  `List` whose elements may be `null`, and for a context a record, whose components may be `null`.
- **The framework integrations** (`Decisions.evaluate`, `GraphModel`) give maps of text, as a table's map-based
  engine does: no map for DMN's null, one map per element of a list (an element that is null maps the decision's
  value name to null, so `[null]` is `[{fee=null}]`), and one map of the components for a context. An empty list
  gives no map, like null.
