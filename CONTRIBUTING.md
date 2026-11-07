<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Contributing to Praecepta

Thanks for helping. Bug reports, questions and pull requests are all welcome.

## Before you start
- For anything larger than a small fix, open an issue first so we can agree on the approach.
- Security problems: do **not** open a public issue; see [SECURITY.md](SECURITY.md).
- Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Building
Praecepta needs **Java 21** or later and Maven 3.9+:

```
mvn verify
```

This runs the unit tests and the property-based tests, runs the Maven plugin's integration tests
(`praecepta-maven-plugin/src/it`, skip with `-Dinvoker.skip`), checks the SPDX header of every Java
source and POM, and writes a CycloneDX SBOM per module (`target/bom.json`).

The code targets **Java 21** (`--release 21`): newer language features or APIs fail to compile.
CI builds and tests on Java 21, 25 and 27, and runs the plugin's integration tests on Maven 3.9,
3.10 and 4.0.

## Fuzzing
`CellParser` and `DmnReader` read untrusted input, so they have [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
fuzz tests (`*FuzzTest`). Every build replays their saved inputs; to fuzz for real (5 minutes per
target), run:

```
JAZZER_FUZZ=1 mvn -pl praecepta-engine test -Dtest=CellParserFuzzTest
```

A crash saves its input under `src/test/resources/.../<Test>Inputs/<method>/crash-*`: fix the bug
and commit that file, so it stays a regression test. The `Fuzzing` workflow does the same weekly.

## Pull requests
1. Fork the repository and create a branch from `main`.
2. Keep the change focused, and add or update tests. Engine changes should keep
   `EngineMatchesReferencePropertiesTest` (the engine against a naive reference) passing.
3. New files need an SPDX header. `mvn license:format` adds it to Java sources and POMs; for other
   files copy an existing header, or list data files in `REUSE.toml`. CI runs
   [`reuse lint`](https://reuse.software).
4. New dependencies must have a licence compatible with Apache-2.0; CI checks the SBOMs against an
   allowlist in `.github/scripts/check_sbom_licenses.py`.
5. Describe what changed and why in the pull request, and add a line to `CHANGELOG.md` under
   *Unreleased*.

## Licensing of contributions
Praecepta is licensed under the [Apache License 2.0](LICENSE). Unless you state otherwise, any
contribution you submit is licensed under the same terms, as section 5 of the licence describes.
No separate contributor agreement or sign-off is required.
