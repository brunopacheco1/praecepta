<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# DMN conformance

Praecepta's semantics are judged by two sources only: the
[DMN 1.5 specification](https://www.omg.org/spec/DMN/1.5/) and the
[DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) (TCK), at the commit
[`5cd912f`](https://github.com/dmn-tck/tck/tree/5cd912f281831bc535e710aba435e16de198028a) that
`praecepta-tck` runs. No other engine is a reference. How Praecepta does on the TCK is in
[praecepta-tck/RESULTS.md](../praecepta-tck/RESULTS.md).

This document records the cases where the two leave the behaviour open: DMN 1.5 contradicts itself or
says nothing, and the TCK has no test. In those cases Praecepta changes no behaviour on its own
reading; it records here what it does, so the case can be settled when a later DMN version or the TCK
decides. None of these choices should be described as "FEEL's behaviour". The list started with the
0.4.0 conformance review ([#86](https://github.com/brunopacheco1/praecepta/issues/86)); a case that is
settled stays here, marked as such, with the release that settled it.

| # | Case | Status |
|---|---|---|
| 1 | [`-` and a missing input](#1---and-a-missing-input) | open |
| 2 | [Values outside the declared input values](#2-values-outside-the-declared-input-values) | open |
| 3 | [An interval whose ends are inputs that are missing or out of order](#3-an-interval-whose-ends-are-inputs-that-are-missing-or-out-of-order) | open |
| 4 | [`=` between a time or date-time with a zone and one without](#4--between-a-time-or-date-time-with-a-zone-and-one-without) | open |
| 5 | [Two times with different offsets](#5-two-times-with-different-offsets) | open |
| 6 | [COUNT when no rule matches](#6-count-when-no-rule-matches) | settled in 0.5.0 |
| 7 | [A `null` default output value beside output values](#7-a-null-default-output-value-beside-output-values) | open |
| 8 | [A complete table, and missing inputs](#8-a-complete-table-and-missing-inputs) | open |
| 9 | [A time with a zone id against a time in another zone, at an offset or without a zone](#9-a-time-with-a-zone-id-against-a-time-in-another-zone-at-an-offset-or-without-a-zone) | open; a zone with one fixed offset settled in 0.6.0 |
| 10 | [The difference of two times in different zones, or one with a zone and one without](#10-the-difference-of-two-times-in-different-zones-or-one-with-a-zone-and-one-without) | open |
| 11 | [A date and time moved by months onto a local time its zone skips](#11-a-date-and-time-moved-by-months-onto-a-local-time-its-zone-skips) | open |
| 12 | [Blank text as a missing value](#12-blank-text-as-a-missing-value) | open |
| 13 | [A value of another type than the decision table input it feeds](#13-a-value-of-another-type-than-the-decision-table-input-it-feeds) | open |
| 14 | [The default output of a table with several results](#14-the-default-output-of-a-table-with-several-results) | open |
| 15 | [A name that both a requirement and a path into another could mean](#15-a-name-that-both-a-requirement-and-a-path-into-another-could-mean) | open |
| 16 | [Input data whose values are not of its type or allowed values](#16-input-data-whose-values-are-not-of-its-type-or-allowed-values) | open |
| 17 | [An input data without a type](#17-an-input-data-without-a-type) | open |

How Praecepta returns DMN's null result, which is a choice of representation rather than of semantics,
is [at the end](#how-a-null-result-is-returned).

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
- **Praecepta.** With literal ends, an interval that starts after it ends is rejected when the cell is
  parsed. With ends that name inputs (`[low..high]`, [#51](https://github.com/brunopacheco1/praecepta/issues/51)):
  a missing end makes the test unknown, so neither the cell nor its `not(...)` matches (Table 55 read
  literally would let `not([low..high])` match a value above `high` when `low` is missing); ends out of
  order make the test false, so its `not(...)` matches.

## 4. `=` between a time or date-time with a zone and one without
- **DMN 1.5.** Table 54 defines `<`: "If one input has a null timezone offset, that input uses the timezone
  offset of the other input." Table 53 defines `=` as `value(e1) = value(e2)` and does not mention the
  case.
- **TCK.** `0068-feel-equality` has these tests commented out, pending a clarification of equality with
  zones.
- **Praecepta (since 0.4.0).** `=` follows the rule of Table 54, since 10.3.2.15 defines `e1 <= e2` as
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
**Settled in 0.5.0.** DMN 1.5, 10.3.2.10, step 2, deals with "no rules match" before any aggregation: the
result is the default output value if one is specified, else null; step 3b aggregates only the rules that
match. In 0.4.0 Praecepta gave 0 (`OutputStrategy.count(0)`); since 0.5.0 it gives the default output, or no
output, under every aggregation, COUNT included, in the engine, the generated classes and the framework
integrations alike.

## 7. A `null` default output value beside output values
- **DMN 1.5.** Table 46: "The default output value is one of the output values." A `null` default is not
  one of them (FEEL's `null in ("Approved", "Declined")` is false), yet 10.3.2.10, step 2, gives null itself
  when no default is specified, and the specification does not say what an engine does with a model that
  breaks the constraint.
- **TCK.** No test declares a `null` default.
- **Praecepta (since 0.6.0, [#92](https://github.com/brunopacheco1/praecepta/issues/92)).** A `null`
  default is accepted whatever the output values (as it was before 0.6.0, when it counted as no
  default), and is a default: with several outputs, defaults that are all `null` give an output of nulls
  rather than none. Any other default outside the output values is rejected when the table is read.

## 8. A complete table, and missing inputs
- **DMN 1.5.** 10.3.2.10: "A complete decision table SHALL NOT specify a default output value." 8.2.4: a
  table is complete when "its rules cover all combinations of expected input values"; whether a missing
  input is an expected value is not said. With declared input values, 10.3.2.10's reading of `-` (case 1)
  suggests it is not; without them, nothing says.
- **TCK.** No test has a complete table with a default output; the TCK's tables with defaults are all
  incomplete.
- **Praecepta (since 0.6.0, [#91](https://github.com/brunopacheco1/praecepta/issues/91)).** A missing
  value counts as an input in every column, as the engine accepts one (case 2) and `-` matches it (case 1).
  So a table is reported as complete (`UNREACHABLE_DEFAULT_OUTPUT`, an ERROR) only when its default can
  really never be given: a table whose rules cover every declared value but not a missing one is not
  reported, since its default is what a missing input gives. For the same reason the search counts every
  list a list column takes (a missing list, the empty list and lists holding missing elements), and a time
  with a zone id wherever a time column takes one (case 9).

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
- **Praecepta (since 0.6.0, [#82](https://github.com/brunopacheco1/praecepta/issues/82)).** Two times in the
  same zone compare by local time. A time in a zone with one fixed offset (`Etc/UTC`, `Etc/GMT-2`, `UTC`: a zone
  whose rules never change its offset) follows Table 72: it is the time at that offset, wherever it is written
  (cells, input value lists, output entries and expressions, map-based inputs, `time(date and time(...))` of a
  date and time in such a zone) or given (a `ZonedTime` bound with `zonedTimeInput` or held by a list). So
  `time("00:01:00@Etc/UTC")` matches `00:01:00Z` and `02:01:00+02:00`, compares, subtracts and aggregates as
  they do, and a column bound with `zonedTimeInput` takes times at an offset too, among which the analyses look
  for overlaps and gaps. Praecepta writes such a time at its offset: the DMN writer writes `time("00:01:00Z")`,
  which reads back equal, and an output that names or computes it gives `00:01:00Z` (the TCK's
  `1116-feel-time-function`, which Praecepta does not run, has `string(time("00:01:00@Etc/UTC"))` keep the zone
  id; an output written as text keeps it too). The generated classes give a time column whose zone ids all have a
  fixed offset an `OffsetTime`, and one that mixes zone ids with offsets a `ZonedTime`, which may be in a zone with
  a fixed offset. Date-times compare by instant, a zone id by the offset it has at the date and time, so
  `0068-feel-equality`'s case holds for them as it did before.
  **Still open:** how a time in a zone whose offset changes (`Europe/Paris`) compares with one in another zone, at
  an offset or without a zone. Such a comparison is taken as one FEEL does not define, which is null (10.3.2.2):
  neither a test nor its `not(...)` matches, an interval's ends must be in one zone, and a COLLECT MIN or MAX over
  such times in two zones is null (in the framework integrations' map-based engine; the generated classes
  aggregate numbers only), as FEEL's `min` and `max` take a list of comparable items (Table 75) and a built-in
  function is null outside its domain (10.3.2.16). A column of map-based inputs (or a list's elements) whose cells
  name no such zone id still takes such a time, which only the cells that accept any value match; since 0.7.0
  ([#114](https://github.com/brunopacheco1/praecepta/issues/114)) the analyses of overlaps, unreachable rules and
  gaps count it, as the completeness search (case 8) did, so a rule that only such a time reaches is not reported
  unreachable, and a table whose rules all compare in that column has a gap there.

## 10. The difference of two times in different zones, or one with a zone and one without
- **DMN 1.5.** Table 57 defines `e1 - e2` of two times as the difference of their values (10.3.2.3.4), which
  says nothing of offsets or zone ids (case 5); for date-times it makes the subtraction undefined where only
  one has a zone, but it does not say so for times.
- **TCK.** `0100-arithmetic` subtracts times without a zone, at one offset, and in one zone id
  (`@"10:10:10@Australia/Melbourne" - @"11:10:10@Australia/Melbourne"`), and no others.
- **Praecepta (since 0.6.0, [#85](https://github.com/brunopacheco1/praecepta/issues/85)).** Times subtract as
  they compare (cases 4, 5 and 9): by instant when both have an offset, by local time when one has none or
  both have a zone id in one zone, and the result is null for a time with a zone id and any time not in its
  zone (a zone with one fixed offset aside: such a time is the time at that offset, case 9). By instant, two times are taken on one day, so times at offsets far apart can be more than a day
  apart (`@"00:00:00+14:00" - @"23:59:59-12:00"` is `-PT49H59M59S`), beyond the range 10.3.2.3.4 gives
  `valuet` ([0..86400]), within which Table 57's `valuet(e1) - valuet(e2)` would stay. Taking each time's
  seconds since midnight UTC instead would keep that range but break the TCK's subtractions at one offset
  (`@"10:10:10+11:00" - @"11:10:10+11:00"` is `-PT1H`, where it would give `PT23H`), so neither reading is
  settled.

## 11. A date and time moved by months onto a local time its zone skips
- **DMN 1.5.** Table 57 moves a date and time by a years and months duration as
  `date and time(date(e1.year + ..., e1.month + ..., e1.day), time(e1))`, keeping its time of day and zone.
  When the zone skips that local time on the new date (a change to daylight saving time), nothing says what
  the result is.
- **TCK.** `0100-arithmetic` moves date-times with a zone id by months only where no time is skipped.
- **Praecepta (since 0.6.0, [#85](https://github.com/brunopacheco1/praecepta/issues/85)).** The local time is
  moved forward by the length of the gap, as `java.time` does:
  `date and time("2025-01-30T02:30:00@Europe/Paris") + @"P2M"` is `2025-03-30T03:30:00+02:00`. A days and time duration moves a date and time by instant
  (10.3.2.3.5), so it never meets this case.

## 12. Blank text as a missing value
- **DMN 1.5.** `""` is a string (10.3.2.3.2), distinct from null.
- **TCK.** No decision-table test gives a blank input.
- **Praecepta.** Blank text is a missing value wherever an input is read from text or a Java value: a single
  value (since before 0.4.0, as Excel and CSV cells and maps of text have no other way to leave a value out),
  and, since 0.6.0, an element of a list from a `Collection`
  ([#84](https://github.com/brunopacheco1/praecepta/issues/84)). So `[""]` is a list holding a missing element,
  which `list contains(?, null)` matches. A quoted `""` in a cell is still the empty string.

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
- **Praecepta (since 0.7.0, [#14](https://github.com/brunopacheco1/praecepta/issues/14)).** `DecisionGraph` reports
  it when the graph is built, as an ERROR (`TYPE_MISMATCH`), and does not evaluate that decision, nor any that
  requires it. The same holds for a path into a value whose type has no such component, and for operands a literal
  expression cannot combine (FEEL gives null there, Tables 57 and 59; output expressions already reject them when a
  table is built). A column of lists takes a single value as a list of one, the conversion 10.3.2.9.4 calls "to
  singleton list". Where 10.3.2.9.4 does decide, Praecepta follows it: a literal expression's value is bound to the
  type its decision declares, a date to a date and time at UTC midnight, a single value to a list as a list of one,
  and any other value of a type that does not conform as null, which the report also lists as a `TYPE_MISMATCH`.
  A decision table's value is typed by its outputs, not by its decision's variable; that a variable could declare
  another type is not checked.

## 14. The default output of a table with several results
- **DMN 1.5.** 10.3.2.10, step 2: when no rule matches, "if a default output value d is specified, DTI=FEEL(d)", a
  single value, also under RULE ORDER, OUTPUT ORDER and COLLECT, whose results are otherwise lists. Bound to a
  decision whose variable is typed by a list, that value becomes a list of one (10.3.2.9.4, "to singleton list"); a
  decision whose variable declares no type keeps the single value.
- **TCK.** `0109-ruleOrder-hitpolicy` (test 003) expects the list of one, its decision's variable being typed by a
  collection.
- **Praecepta (since 0.7.0, [#14](https://github.com/brunopacheco1/praecepta/issues/14)).** A decision table with
  several results always has a list type (`list<T>`), so its default output is always a list of one, whether the
  variable declares a type or not; the engine has always returned it so (`[{Status=Declined, Rate=Standard}]`).

## 15. A name that both a requirement and a path into another could mean
- **DMN 1.5.** A name may hold a dot (10.3.1.6 on ambiguity; grammar rules 27 to 30), so `Pricing.discount` may name
  a variable of that name or the component `discount` of `Pricing`; scope decides (10.3.2.11), and a decision's
  scope is its requirements (7.3.4).
- **TCK.** No test has both.
- **Praecepta (since 0.7.0).** A name read by a decision is the requirement of that whole name if the decision has
  one, else a path into the requirement named by its longest part before a dot. A component's own name is not
  split further: `a.b.c` is the path `b`, `c` into `a`. A path into a list of lists keeps the outer list and gives
  null for each inner one, as 10.3.2.5 defines the selection only on "a list of contexts".

## 16. Input data whose values are not of its type or allowed values
- **DMN 1.5.** An input data's value comes from "a data source attached at runtime" (7.3.4), whose type the
  variable's `typeRef` gives; an item definition's `allowedValues` constrain it (7.3.3). What an engine does with a
  value outside them is not said.
- **TCK.** `0003-input-data-string-allowed-values` gives only allowed values.
- **Praecepta (since 0.7.0).** A value not of the input data's type (text that is not a number, a list for a
  number) throws `InvalidInputException` when the decision is evaluated, as an input of a table does. An item
  definition's allowed values are not checked for input data (a decision table still checks its own input values).
  Blank text is a missing value for every simple type but `string`, for which `""` is passed on as a string, as FEEL
  has it; a decision table reading it takes it as missing (case 12).

## 17. An input data without a type
- **DMN 1.5.** An input data's variable "may include a typeRef" (6.3.11); without one, its values are of any type.
- **TCK.** Every TCK input data has a `typeRef`.
- **Praecepta (since 0.7.0).** Its type is that of the decision table columns that read it whole, when they agree;
  where they do not, a `TYPE_MISMATCH` is reported. A literal expression cannot read a name whose type is not known,
  which is reported too (the subset Praecepta evaluates is typed when the graph is built). Read by path, a value of
  undeclared type is checked when it is read, and a value of another type than its column throws
  `InvalidInputException`.

## How a null result is returned
DMN 1.5 gives a decision table one result, which may be null; Praecepta's engine returns a list of
outputs (`List<O>`), the generated classes an `Optional` or a `List`, and the framework integrations a list
of maps. They return DMN's null in two ways, and each case is decided by DMN; only the representation is
Praecepta's:
- **No output** (an empty list, an empty `Optional`, no map) when no rule matches and the table declares no
  default output (10.3.2.10, step 2: "else DTI=null"), whatever the hit policy or aggregation.
- **An output whose value is null** (`[{fee=null}]`, `FeesOutput[fee=null]`) when a rule, a default or an
  aggregate gives null for an output: a matching rule's `null` entry or an expression with a missing
  operand, a `null` default output value (case 7), and since 0.6.0 a SUM, MIN or MAX over a matching rule
  whose output is missing, as FEEL's `sum`, `min` and `max` are null for a list that holds null (10.3.4.4,
  10.3.2.16; [#90](https://github.com/brunopacheco1/praecepta/issues/90)), or over values that do not compare
  (case 9).

With one output and a single-hit policy or an aggregation, both mean DMN's null; with several outputs, the
second is a context with null entries, which DMN tells from null. Under COLLECT without an operator, RULE
ORDER and OUTPUT ORDER, the result is a list of the matching rules' outputs (10.3.2.10, steps 3a and 3b), and
the same `[{fee=null}]` stands for two DMN results the representation does not tell apart: DMN's `[null]`,
when one rule matches and its entry is `null`, and DMN's null, when no rule matches and the default is
`null` (step 2a).
