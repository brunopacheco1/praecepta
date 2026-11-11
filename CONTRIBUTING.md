<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Contributing to Praecepta

Thanks for helping. Bug reports, questions and pull requests are all welcome.

## Before you start
How the project is run, and how to become a maintainer, is in [GOVERNANCE.md](GOVERNANCE.md); the
design is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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

Every build runs the checks of the native-image test project (`praecepta-maven-plugin/src/it/native-app`)
on the JVM. To build it as a native executable and run that too, use GraalVM (for JDK 21 or later) as the
JDK and enable the `native` profile; it takes a few minutes, and CI does it in the `Native image` workflow:

```
mvn -pl praecepta-maven-plugin -am verify -Pnative
```

The code targets **Java 21** (`--release 21`): newer language features or APIs fail to compile.
CI builds and tests on Java 21, 25 and 27, and runs the plugin's integration tests on Maven 3.9,
3.10 and 4.0 (on pull requests only when the plugin, the engine or a POM changes). A newer push to a
pull request cancels the checks still running for the previous one.

## Code style
The code follows standard Java conventions; the build enforces them, so a pull request that breaks
them fails before the tests run:
- **Checkstyle** (`checkstyle.xml`, in the `validate` phase): Java naming (test methods may use
  `snake_case`), 4-space indentation without tabs, braces on every block, explicit imports with
  none unused, whitespace around operators and after commas and keywords, no trailing whitespace,
  and lines of at most 150 characters (long string literals, imports and URLs excepted).
- **Compiler warnings are errors** (`-Xlint:all -Werror`). Suppress a warning only where it cannot be
  fixed, as narrowly as possible, with a comment saying why.
- **SonarCloud** reviews every pull request; its quality gate must pass.

Run `mvn validate` to check the style alone.

## Fuzzing
`CellParser` and `DmnReader` read untrusted input, so they have [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
fuzz tests (`*FuzzTest`). Every build replays their saved inputs; to fuzz for real (5 minutes per
target), run:

```
JAZZER_FUZZ=1 mvn -pl praecepta-engine test -Dtest=CellParserFuzzTest
```

A crash saves its input under `src/test/resources/.../<Test>Inputs/<method>/crash-*`: fix the bug
and commit that file, so it stays a regression test. The `Fuzzing` workflow does the same weekly.

Each fuzz test calls a plain Jazzer target (`*Fuzzer`), which [ClusterFuzzLite](https://google.github.io/clusterfuzzlite/)
also runs for 5 minutes on every pull request that touches the engine (`.clusterfuzzlite/`, the
`ClusterFuzzLite` workflow). Its report names the crashing input; save it the same way.

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
6. Commits must be signed, and `main` only changes through pull requests that pass the required
   checks, are up to date with `main`, and are approved by a code owner (`.github/CODEOWNERS`).

## Licensing of contributions
Praecepta is licensed under the [Apache License 2.0](LICENSE). Unless you state otherwise, any
contribution you submit is licensed under the same terms, as section 5 of the licence describes.
No separate contributor agreement or sign-off is required.
