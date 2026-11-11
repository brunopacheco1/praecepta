<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## 0.4
Released in November 2026 (see the [changelog](../CHANGELOG.md)), with the rest of the TCK's
decision tables that fit in a single table:
- **Inputs that hold lists** ([#25](https://github.com/brunopacheco1/praecepta/issues/25)), such as
  a customer's nationalities: a cell holds when some element passes it, as FEEL's `list contains`
  reads it, and DMN files write it so.
- **Output entries that are expressions**
  ([#71](https://github.com/brunopacheco1/praecepta/issues/71)): string joins and arithmetic on
  literals and inputs, as in `(price - discount) * 1.2`, evaluated only for the rules that match.
- **Times and date-times with a time zone**
  ([#70](https://github.com/brunopacheco1/praecepta/issues/70)), compared by instant as in FEEL.
- **Compact masks for very large tables**
  ([#17](https://github.com/brunopacheco1/praecepta/issues/17)): the mask of a value only a few
  rules accept is stored sparse, without a new dependency, so a 10,000-rule table with a code per
  rule takes 4.6 MB instead of 17.6 MB and answers in 0.39 µs instead of 0.68 µs.

The TCK went from 78 to 83 of 86 checks, and every check Praecepta supports passes. The other 3 need
decision requirement graphs.

## Next: 0.5
Integrations, so Praecepta fits the build and the framework an application already uses:
- **GraalVM native image** ([#19](https://github.com/brunopacheco1/praecepta/issues/19)), tested in
  CI.
- **A Gradle plugin** ([#18](https://github.com/brunopacheco1/praecepta/issues/18)), doing what the
  Maven plugin does.
- **A Spring Boot starter** ([#20](https://github.com/brunopacheco1/praecepta/issues/20)), a
  **Quarkus extension** ([#76](https://github.com/brunopacheco1/praecepta/issues/76)) and a
  **Micronaut module** ([#77](https://github.com/brunopacheco1/praecepta/issues/77)), which give
  engines as beans, validated when the application builds or starts.

## 0.6
- **An online playground** ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a
  table, see its validation report and evaluate inputs, without installing anything.

## Later
- **Decision requirement graphs** (decisions feeding other decisions,
  [#14](https://github.com/brunopacheco1/praecepta/issues/14)), the last 3 TCK checks, if they fit
  without turning Praecepta into a full DMN engine.

## Towards 1.0
A stable API, once the features above have settled and the project has a second maintainer
(see [GOVERNANCE.md](../GOVERNANCE.md)). After 1.0, breaking changes wait for a major version.

## The project itself
- **A second maintainer**, so every change can be reviewed by someone other than its author.
- **Keeping the OpenSSF Best Practices silver badge** current, and working towards gold, which needs
  a second maintainer and reviewer; and keeping the security work current: fuzzing, dependency
  updates, and the [assurance case](SECURITY-ASSURANCE.md).
