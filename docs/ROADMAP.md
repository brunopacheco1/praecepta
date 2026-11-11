<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do over the next year (to late 2027). These are intentions, not promises:
priorities follow what users ask for, so a 👍 or a comment on an issue moves it up. How decisions
are made is in [GOVERNANCE.md](../GOVERNANCE.md); past decisions are in
[MODERNISATION.md](MODERNISATION.md).

## 0.3
Released in November 2026 (see the [changelog](../CHANGELOG.md)), with three items pulled forward
from later in the year:
- **Cells that compare with another input**
  ([#51](https://github.com/brunopacheco1/praecepta/issues/51)), as in `> limit`, in DMN tables.
  The index treats them as "any value" and the engine checks them only on the rules it leaves, so
  other tables keep their speed.
- **Duration columns** ([#52](https://github.com/brunopacheco1/praecepta/issues/52)) and **string
  ranges** ([#13](https://github.com/brunopacheco1/praecepta/issues/13)).
- **COUNT aggregation** ([#15](https://github.com/brunopacheco1/praecepta/issues/15)), **output
  entries that name an input** ([#53](https://github.com/brunopacheco1/praecepta/issues/53)) and
  **columns typed by an item definition**
  ([#54](https://github.com/brunopacheco1/praecepta/issues/54)).
- **Primitive keys for number, date, time and duration columns**
  ([#16](https://github.com/brunopacheco1/praecepta/issues/16)), 20 to 35 % faster on mixed tables.

The TCK went from 51 to 78 of 86 checks, and every check Praecepta supports passes. Each of the
other 8 needs a feature with its own issue.

## Next: 0.4
The rest of the TCK's decision tables that fit in a single table:
- **Times and date-times with a time zone**
  ([#70](https://github.com/brunopacheco1/praecepta/issues/70)).
- **Output entries that are expressions**
  ([#71](https://github.com/brunopacheco1/praecepta/issues/71)), starting with string
  concatenation and arithmetic.
- **List-valued inputs** ([#25](https://github.com/brunopacheco1/praecepta/issues/25)), which one
  of those TCK tables also needs.

## Later in the year
- **Compressed bitmaps for very large tables**
  ([#17](https://github.com/brunopacheco1/praecepta/issues/17)).
- **Integrations, as people ask for them:** a Gradle plugin
  ([#18](https://github.com/brunopacheco1/praecepta/issues/18)), GraalVM native image
  ([#19](https://github.com/brunopacheco1/praecepta/issues/19)), a Spring Boot starter
  ([#20](https://github.com/brunopacheco1/praecepta/issues/20)), and an online playground
  ([#21](https://github.com/brunopacheco1/praecepta/issues/21)).
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
