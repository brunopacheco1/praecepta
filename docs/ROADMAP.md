<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## The plan: a full DMN 1.5 engine
From 0.7, Praecepta grows from a validated decision-table engine into a full DMN 1.5 engine, one layer
per release, tracked in [#120](https://github.com/brunopacheco1/praecepta/issues/120). Each layer is
scored against the whole [DMN TCK](../praecepta-tck/RESULTS.md) and judged by the DMN 1.5
specification and the TCK alone. Decision tables keep what sets Praecepta apart, the validation report
and the typed generated code; the general engine evaluates everything around them, and the generated
classes gain each feature through it.

## 0.7
Released in November 2026 (see the [changelog](../CHANGELOG.md)):
- **Decision requirement graphs** ([#14](https://github.com/brunopacheco1/praecepta/issues/14)):
  DMN models whose decisions require other decisions and input data, where each decision is a
  decision table or a literal expression in the arithmetic of output entries. `DecisionGraph`
  evaluates them in dependency order and reports cycles, missing requirements and type mismatches
  when it is built; the Maven and Gradle plugins generate a typed class per decision, and Spring
  Boot, Micronaut and Quarkus evaluate them by name.
- **The whole DMN TCK, scored** ([#115](https://github.com/brunopacheco1/praecepta/issues/115)):
  every model of compliance levels 2 and 3. 454 of its 3,511 result nodes pass (12.9 %), and 454
  of the 458 that Praecepta evaluates; every other node is listed with the release that brings what
  it needs. The decision-table checks still pass 83 of 86, and the build fails only on a
  regression.
- **Fixes**: rules reached only by a time with a zone id that no cell names are no longer reported
  unreachable ([#114](https://github.com/brunopacheco1/praecepta/issues/114)), and dates, times and
  date-times are read in XML Schema's lexical forms: an offset beyond -14:00..+14:00
  ([#123](https://github.com/brunopacheco1/praecepta/issues/123)) and a year with a leading `+`
  ([#124](https://github.com/brunopacheco1/praecepta/issues/124)) are rejected.

## 0.8
Released in November 2026 (see the [changelog](../CHANGELOG.md)):
- **The full FEEL language** ([#116](https://github.com/brunopacheco1/praecepta/issues/116)): the
  expression language of DMN 1.5, chapter 10, but its built-in functions: three-valued boolean logic,
  lists, contexts, ranges and functions as values, `if`, comparisons, `between`, `in` and
  `instance of`, paths, filters, `for` and `some`/`every`, invocations of function values, and FEEL's
  errors, which give null. Literal expression decisions are any FEEL expression, and so are decision
  tables' input expressions and output entries; their input entries are any FEEL unary tests. Tables
  keep their bitset index, analysis and generated code: a cell beyond the simple unary tests is
  applied to the rules the index matches, and the analysis reports nothing it cannot prove.
- **The DMN TCK**: 2,275 of its 3,511 result nodes pass (64.8 %, from 454), and 2,275 of the 2,279
  that Praecepta evaluates; all 1,289 nodes of FEEL's feature area pass. Most of the 1,232
  nodes not supported yet need FEEL's built-in functions. The decision-table checks still pass 83 of
  86.

## 0.9
Released in November 2026 (see the [changelog](../CHANGELOG.md)):
- **Built-in functions** ([#117](https://github.com/brunopacheco1/praecepta/issues/117)): DMN 1.5's
  whole function library, conversion, boolean, string, list, sort, numeric, date and time, range and
  context functions, such as `substring`, `sum`, `round half up` and `day of week`, in literal
  expressions and in decision tables alike, with named arguments, and each function's work counted
  against the evaluation's limits, regular expressions included.
- **The DMN TCK**: 3,276 of its 3,511 result nodes pass (93.3 %, from 2,275), and 3,276 of the 3,280
  that Praecepta evaluates; 1,066 of the built-in functions' 1,071. Most of the 231 nodes not
  supported yet need boxed expressions and business knowledge models. The decision-table checks still
  pass 83 of 86.

## Next: 0.10
- **Boxed expressions and business knowledge models**
  ([#118](https://github.com/brunopacheco1/praecepta/issues/118)): invocations, function
  definitions, boxed contexts, relations, lists, conditionals, iterators and filters, and
  structured inputs typed by item definitions. With them the decision tables of the TCK reach 86
  of 86.

## 0.11 and 1.0
- **Decision services, imports and the rest of DMN 1.5**
  ([#119](https://github.com/brunopacheco1/praecepta/issues/119)), then 1.0: a claim of DMN 1.5
  conformance, scored against the whole TCK, and a stable API, once the project has a second
  maintainer (see [GOVERNANCE.md](../GOVERNANCE.md)). After 1.0, breaking changes wait for a major
  version.

## After 1.0
- **An online playground** ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a
  table, see its validation report and evaluate inputs, without installing anything.
- **Praecepta for Kotlin**: a null-safe API through [JSpecify](https://jspecify.dev)
  ([#104](https://github.com/brunopacheco1/praecepta/issues/104)), so Kotlin, and Java tools such
  as NullAway, see which values may be null, in the engine and in the generated classes; Kotlin
  code from the build plugins ([#105](https://github.com/brunopacheco1/praecepta/issues/105)), data
  classes and objects instead of Java when asked; and a Kotlin DSL module
  ([#106](https://github.com/brunopacheco1/praecepta/issues/106)), `praecepta-kotlin`, for building
  engines and rules in Kotlin.

## Later
- **Micronaut 5** ([#94](https://github.com/brunopacheco1/praecepta/issues/94)), which needs Java 25:
  once the project's Java baseline moves past 21.

## The project itself
- **A second maintainer**, so every change can be reviewed by someone other than its author.
- **Keeping the OpenSSF Best Practices silver badge** current, and working towards gold, which needs
  a second maintainer and reviewer; and keeping the security work current: fuzzing, dependency
  updates, and the [assurance case](SECURITY-ASSURANCE.md).
