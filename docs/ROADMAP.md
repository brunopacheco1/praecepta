<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## Next: 0.2
Already done for 0.2: fast builds for large FIRST and PRIORITY tables
([#29](https://github.com/brunopacheco1/praecepta/issues/29)); the DMN conformance kit (TCK) in
every build ([#22](https://github.com/brunopacheco1/praecepta/issues/22)), with its results in
[praecepta-tck/RESULTS.md](../praecepta-tck/RESULTS.md); and boolean, time and date-time columns
([#12](https://github.com/brunopacheco1/praecepta/issues/12)), which took the TCK from 16 to 51 of
86 checks; and input names with spaces ([#47](https://github.com/brunopacheco1/praecepta/issues/47)).
Every check Praecepta supports passes. Most of the other 35 compare a cell with another input
(`> Complex.aNumber`, 20 checks), which a table indexed ahead of evaluation cannot do; the rest need
durations, COUNT ([#15](https://github.com/brunopacheco1/praecepta/issues/15)), expression outputs,
item-definition types or decision requirement graphs
([#14](https://github.com/brunopacheco1/praecepta/issues/14)).

## Later in the year
- **More of DMN's decision tables:** string ranges in cells
  ([#13](https://github.com/brunopacheco1/praecepta/issues/13)), COUNT
  ([#15](https://github.com/brunopacheco1/praecepta/issues/15)), and list-valued inputs
  ([#25](https://github.com/brunopacheco1/praecepta/issues/25)).
- **Speed and size:** primitive keys for number and date columns
  ([#16](https://github.com/brunopacheco1/praecepta/issues/16)), and compressed bitmaps for very
  large tables ([#17](https://github.com/brunopacheco1/praecepta/issues/17)).
- **Integrations, as people ask for them:** a Gradle plugin
  ([#18](https://github.com/brunopacheco1/praecepta/issues/18)), GraalVM native image
  ([#19](https://github.com/brunopacheco1/praecepta/issues/19)), a Spring Boot starter
  ([#20](https://github.com/brunopacheco1/praecepta/issues/20)), and an online playground
  ([#21](https://github.com/brunopacheco1/praecepta/issues/21)).
- **Decision requirement graphs** (decisions feeding other decisions,
  [#14](https://github.com/brunopacheco1/praecepta/issues/14)), if they fit without turning
  Praecepta into a full DMN engine.

## Towards 1.0
A stable API, once the features above have settled and the project has a second maintainer
(see [GOVERNANCE.md](../GOVERNANCE.md)). After 1.0, breaking changes wait for a major version.

## The project itself
- **A second maintainer**, so every change can be reviewed by someone other than its author.
- **Keeping the OpenSSF Best Practices silver badge** current, and working towards gold, which needs
  a second maintainer and reviewer; and keeping the security work current: fuzzing, dependency
  updates, and the [assurance case](SECURITY-ASSURANCE.md).
