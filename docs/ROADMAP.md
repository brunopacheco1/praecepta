<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Roadmap

What Praecepta intends to do next. These are intentions, not promises: priorities follow what users ask
for, so a 👍 or a comment on an issue moves it up. [GOVERNANCE.md](../GOVERNANCE.md) explains how the
project decides. The [changelog](../CHANGELOG.md) lists what each release brought.

## Where it stands
Praecepta evaluates all of DMN 1.5 except external functions, which it rejects by design. It passes
3,482 of the 3,511 checks of the DMN TCK ([results](../praecepta-tck/RESULTS.md)).

## Next: 1.0
1.0 is released only once Praecepta has been tested with a real use case.

- **A test with a real use case** ([#162](https://github.com/brunopacheco1/praecepta/issues/162)): real tables or models that an application depends on, run
  on a release candidate as a user would, with every problem it finds fixed first.
- **A claim of DMN 1.5 conformance**, scored against the whole TCK, with external functions as its one
  deliberate exception ([#120](https://github.com/brunopacheco1/praecepta/issues/120)).
- **A listing on the [DMN TCK results site](https://dmn-tck.github.io/tck/)**
  ([#149](https://github.com/brunopacheco1/praecepta/issues/149), after updating the kit, [#148](https://github.com/brunopacheco1/praecepta/issues/148);
  [how](../praecepta-tck/README.md#publishing-results-on-the-dmn-tck-site)).
- **Fresh benchmark numbers** from the current code ([#147](https://github.com/brunopacheco1/praecepta/issues/147)).
- **A stable API**, which the build checks ([#159](https://github.com/brunopacheco1/praecepta/issues/159)). After 1.0, breaking changes wait for a major
  version.
- **Micronaut 5** ([#94](https://github.com/brunopacheco1/praecepta/issues/94)), which needs Java 25.
- **Simpler code where it is still dense** ([#152](https://github.com/brunopacheco1/praecepta/issues/152)).
- **A second maintainer**, so that someone other than its author reviews every change.

## After 1.0
- **An online playground** ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a table,
  see its validation report and evaluate inputs, without installing anything.
- **Praecepta for Kotlin**: a null-safe API through [JSpecify](https://jspecify.dev)
  ([#104](https://github.com/brunopacheco1/praecepta/issues/104)), Kotlin code from the build plugins
  ([#105](https://github.com/brunopacheco1/praecepta/issues/105)) and a Kotlin DSL
  ([#106](https://github.com/brunopacheco1/praecepta/issues/106)).

## The project itself
- Keep the OpenSSF Best Practices silver badge current, and work towards gold, which needs a second
  maintainer.
- Keep the security work current: fuzzing, dependency updates and the
  [assurance case](SECURITY-ASSURANCE.md).
