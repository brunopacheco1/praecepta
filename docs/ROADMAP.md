<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## 0.5
Released in November 2026 (see the [changelog](../CHANGELOG.md)), with integrations, so Praecepta
fits the build and the framework an application already uses:
- **A Spring Boot starter** ([#20](https://github.com/brunopacheco1/praecepta/issues/20)), a
  **Quarkus extension** ([#76](https://github.com/brunopacheco1/praecepta/issues/76)) and a
  **Micronaut module** ([#77](https://github.com/brunopacheco1/praecepta/issues/77)): DMN tables as
  engine beans, validated when the application builds (Quarkus) or starts, with their reports at
  runtime.
- **A Gradle plugin** ([#18](https://github.com/brunopacheco1/praecepta/issues/18)) that generates
  the same classes as the Maven plugin, with Gradle's build cache and configuration cache.
- **GraalVM native images** ([#19](https://github.com/brunopacheco1/praecepta/issues/19)), with no
  configuration for Praecepta, built and run in CI with GraalVM for JDK 21 and 25.
- **Fixes** that bring tables closer to DMN 1.5: an output's default value when no rule matches
  ([#79](https://github.com/brunopacheco1/praecepta/issues/79)), FEEL's escape sequences in quoted
  strings ([#80](https://github.com/brunopacheco1/praecepta/issues/80)), and strings ordered by
  character ([#83](https://github.com/brunopacheco1/praecepta/issues/83)).

The TCK stays at 83 of 86 checks; the other 3 need decision requirement graphs.

## Next: 0.6
The DMN 1.5 gaps that a review of 0.4 against the specification found in single tables:
- **Analysis of rules that test list columns**
  ([#78](https://github.com/brunopacheco1/praecepta/issues/78)): overlaps, unreachable rules and
  gaps for them too.
- **FEEL literal forms and the `=` and `!=` tests** that cells reject
  ([#81](https://github.com/brunopacheco1/praecepta/issues/81)), such as `!= "PT"`, `1.2e3`,
  `@"2025-01-31"` and comments.
- **Times with a zone id** ([#82](https://github.com/brunopacheco1/praecepta/issues/82)), such as
  `time("10:30:00@Europe/Paris")`.
- **Missing elements in list inputs** ([#84](https://github.com/brunopacheco1/praecepta/issues/84)),
  as FEEL's list functions treat them.
- **Output expressions over dates, times and durations**
  ([#85](https://github.com/brunopacheco1/praecepta/issues/85)), as in `due + duration("P14D")`.
- **Behaviour that DMN 1.5 and the TCK leave undecided**
  ([#86](https://github.com/brunopacheco1/praecepta/issues/86)): recorded, and settled when a later
  DMN version or the TCK decides it.

## 0.7
- **Decision requirement graphs** (decisions feeding other decisions,
  [#14](https://github.com/brunopacheco1/praecepta/issues/14)), the last 3 TCK checks, if they fit
  without turning Praecepta into a full DMN engine.

## 0.8
- **An online playground** ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a
  table, see its validation report and evaluate inputs, without installing anything.

## Towards 1.0
A stable API, once the features above have settled and the project has a second maintainer
(see [GOVERNANCE.md](../GOVERNANCE.md)). After 1.0, breaking changes wait for a major version.

## The project itself
- **A second maintainer**, so every change can be reviewed by someone other than its author.
- **Keeping the OpenSSF Best Practices silver badge** current, and working towards gold, which needs
  a second maintainer and reviewer; and keeping the security work current: fuzzing, dependency
  updates, and the [assurance case](SECURITY-ASSURANCE.md).
