<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## 0.6
Released in November 2026 (see the [changelog](../CHANGELOG.md)), with the DMN 1.5 gaps that the
reviews of 0.4 and 0.5 against the specification found in single tables:
- **Analysis of rules that test list columns**
  ([#78](https://github.com/brunopacheco1/praecepta/issues/78)): overlaps, unreachable rules and
  gaps for them too.
- **FEEL literal forms and the `=` and `!=` tests**
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)), such as `!= "PT"`, `1.2e3`,
  `@"2025-01-31"`, `date(2025, 1, 31)` and comments.
- **Times with a zone id** ([#82](https://github.com/brunopacheco1/praecepta/issues/82)), such as
  `time("10:30:00@Europe/Paris")`.
- **Missing elements in list inputs** ([#84](https://github.com/brunopacheco1/praecepta/issues/84)),
  as FEEL's list functions treat them.
- **Output expressions over dates, times and durations**
  ([#85](https://github.com/brunopacheco1/praecepta/issues/85)), as in `due + duration("P14D")`.
- **Behaviour that DMN 1.5 and the TCK leave undecided**
  ([#86](https://github.com/brunopacheco1/praecepta/issues/86)), recorded in
  [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md) until a later DMN version or the TCK decides it.
- **Fixes** that follow DMN 1.5: SUM, MIN and MAX give null when a matching rule's output is null
  ([#90](https://github.com/brunopacheco1/praecepta/issues/90)), a complete table that declares a
  default output is an error ([#91](https://github.com/brunopacheco1/praecepta/issues/91)), and a
  null default is told from no default ([#92](https://github.com/brunopacheco1/praecepta/issues/92)).

And for the integrations shipped in 0.5:
- **The Gradle plugin on the Gradle Plugin Portal**
  ([#93](https://github.com/brunopacheco1/praecepta/issues/93)), so `pluginManagement` needs no
  `mavenCentral()`.
- **Native builds of Spring Boot, Micronaut and Quarkus applications in CI**
  ([#99](https://github.com/brunopacheco1/praecepta/issues/99)).
- Smaller fixes: validation issues logged once
  ([#95](https://github.com/brunopacheco1/praecepta/issues/95)), `DmnReader` pinned to the JDK's XML
  parser ([#96](https://github.com/brunopacheco1/praecepta/issues/96)), and published POMs without
  the JUnit BOM ([#98](https://github.com/brunopacheco1/praecepta/issues/98)). The Gradle plugin
  keeps compiling against the Gradle 8.11.1 API, for reasons recorded in
  [ARCHITECTURE.md](ARCHITECTURE.md) ([#97](https://github.com/brunopacheco1/praecepta/issues/97)).

The TCK stays at 83 of 86 checks; the other 3 need decision requirement graphs. Its arithmetic test
cases now also run as output expressions: Praecepta reads 489 of their 1,101 checks, and gives the
kit's result for all but one.

## Next: 0.7
- **Decision requirement graphs** (decisions feeding other decisions,
  [#14](https://github.com/brunopacheco1/praecepta/issues/14)), the last 3 TCK checks, if they fit
  without turning Praecepta into a full DMN engine.

## 0.8
- **An online playground** ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a
  table, see its validation report and evaluate inputs, without installing anything.

## 0.9
Praecepta for Kotlin:
- **A null-safe API** through [JSpecify](https://jspecify.dev)
  ([#104](https://github.com/brunopacheco1/praecepta/issues/104)), so Kotlin, and Java tools such as
  NullAway, see which values may be null, in the engine and in the generated classes.
- **Kotlin code from the build plugins**
  ([#105](https://github.com/brunopacheco1/praecepta/issues/105)): the Maven and Gradle plugins and
  the Quarkus extension generate Kotlin data classes and objects, when asked, instead of Java.
- **A Kotlin DSL module** ([#106](https://github.com/brunopacheco1/praecepta/issues/106)),
  `praecepta-kotlin`, for building engines and rules in Kotlin.

## Later
- **Micronaut 5** ([#94](https://github.com/brunopacheco1/praecepta/issues/94)), which needs Java 25:
  once the project's Java baseline moves past 21.

## Towards 1.0
A stable API, once the features above have settled and the project has a second maintainer
(see [GOVERNANCE.md](../GOVERNANCE.md)). After 1.0, breaking changes wait for a major version.

## The project itself
- **A second maintainer**, so every change can be reviewed by someone other than its author.
- **Keeping the OpenSSF Best Practices silver badge** current, and working towards gold, which needs
  a second maintainer and reviewer; and keeping the security work current: fuzzing, dependency
  updates, and the [assurance case](SECURITY-ASSURANCE.md).
