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

## Before 1.0
Everything planned comes before 1.0, in three releases. The milestones list every issue. The project's
baselines do not change before 1.0: Java 21, and the oldest framework and build-tool versions supported
today.

**[0.13](https://github.com/brunopacheco1/praecepta/milestone/11): fixes and firmer builds**
- The smaller defects the 0.12 review found ([#153](https://github.com/brunopacheco1/praecepta/issues/153), [#154](https://github.com/brunopacheco1/praecepta/issues/154), [#155](https://github.com/brunopacheco1/praecepta/issues/155),
  [#156](https://github.com/brunopacheco1/praecepta/issues/156)).
- The DMN TCK updated to its latest test cases ([#148](https://github.com/brunopacheco1/praecepta/issues/148)).
- CI that does not fail when a download does ([#161](https://github.com/brunopacheco1/praecepta/issues/161)), and that tests on the baseline JDK
  ([#164](https://github.com/brunopacheco1/praecepta/issues/164)).
- Framework modules named after the framework major they support ([#163](https://github.com/brunopacheco1/praecepta/issues/163)), as the
  [support policy](../GOVERNANCE.md#what-is-supported) says, and with them modules for Micronaut 5
  ([#94](https://github.com/brunopacheco1/praecepta/issues/94)), which needs Java 25 on its own, and Quarkus 4 ([#167](https://github.com/brunopacheco1/praecepta/issues/167)).

**[0.14](https://github.com/brunopacheco1/praecepta/milestone/13): quality, speed and the TCK site**
- A listing on the [DMN TCK results site](https://dmn-tck.github.io/tck/) ([#149](https://github.com/brunopacheco1/praecepta/issues/149);
  [how](../praecepta-tck/README.md#publishing-results-on-the-dmn-tck-site)).
- Fresh benchmark numbers from the current code ([#147](https://github.com/brunopacheco1/praecepta/issues/147)), after reducing what a decision graph
  allocates ([#157](https://github.com/brunopacheco1/praecepta/issues/157)).
- More mutation testing and tests where coverage is thin ([#150](https://github.com/brunopacheco1/praecepta/issues/150), [#151](https://github.com/brunopacheco1/praecepta/issues/151)).
- An API compatibility check in the build ([#159](https://github.com/brunopacheco1/praecepta/issues/159)).
- Each framework module tested at the oldest and the latest version of its line ([#165](https://github.com/brunopacheco1/praecepta/issues/165)).
- Simpler code where it is still dense ([#152](https://github.com/brunopacheco1/praecepta/issues/152)).

**[0.15](https://github.com/brunopacheco1/praecepta/milestone/14): the playground and Kotlin**
- An online playground ([#21](https://github.com/brunopacheco1/praecepta/issues/21)): paste a table, see its validation report and evaluate inputs,
  without installing anything.
- Praecepta for Kotlin: a null-safe API through [JSpecify](https://jspecify.dev) ([#104](https://github.com/brunopacheco1/praecepta/issues/104)), Kotlin
  code from the build plugins ([#105](https://github.com/brunopacheco1/praecepta/issues/105)) and a Kotlin DSL ([#106](https://github.com/brunopacheco1/praecepta/issues/106)).

## 1.0
1.0 is released only once Praecepta has been tested with a real use case ([#162](https://github.com/brunopacheco1/praecepta/issues/162)): real tables or
models that an application depends on, run on a release candidate as a user would, with every problem it
finds fixed first. Nothing else waits for 1.0.

With 1.0 come the claim of DMN 1.5 conformance, with external functions as its one deliberate exception,
and a stable API: after 1.0, breaking changes wait for a major version.

## The project itself
- Find a second maintainer, so that someone other than its author reviews every change.
- Keep the OpenSSF Best Practices silver badge current, and work towards gold, which needs a second
  maintainer.
- Keep the security work current: fuzzing, dependency updates and the
  [assurance case](SECURITY-ASSURANCE.md).
