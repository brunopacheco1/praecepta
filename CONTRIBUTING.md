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

The Gradle plugin's functional tests build small Gradle projects with Gradle TestKit, which downloads
a Gradle distribution, so they run only when asked for, on a Java that Gradle version runs on:

```
mvn -pl praecepta-gradle-plugin -am verify -P gradle-functional [-Dpraecepta.gradle.version=8.5]
```

The Spring Boot, Micronaut and Quarkus modules are tested in the default build, each against its
framework (Quarkus with `QuarkusExtensionTest` and `QuarkusDevModeTest`, dev mode included).
[docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) has what every integration must do alike.

The code targets **Java 21** (`--release 21`): newer language features or APIs fail to compile.
CI builds and tests on Java 21, 25 and 27, runs the Maven plugin's integration tests on Maven 3.9,
3.10 and 4.0 (on pull requests only when the plugin, the engine or a POM changes), and the Gradle
plugin's functional tests on Gradle 8.5, 8.14 and 9 (on pull requests only when that plugin, the
compiler, loader or engine changes), and the native-image test project with GraalVM for JDK 21 and 25
(on pull requests only when what goes into the executable changes). A newer push to a pull request
cancels the checks still running for the previous one.

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

## Adding a module
A new integration or other module needs these, and nothing else:
1. **Reactor:** a `<module>` line in the parent POM, after `praecepta-maven-plugin` and before
   `praecepta-benchmarks`. The module is a directory directly under the repository root, and its parent
   is the reactor's parent POM (`../pom.xml`), so the release command and Reproducible Central's version
   rewrite (`pom.xml */pom.xml`) reach it as they are.
2. **Versions:** every dependency and plugin version is a property in the parent POM, in a block
   commented with the issue (`<!-- Spring Boot starter (#20) -->`), and only exact, current, stable
   releases from Maven Central. A framework's BOM is imported in the module's own
   `<dependencyManagement>` (importing it in the parent would change the versions every other module
   resolves), with its version property in the parent. The enforcer's `dependencyConvergence` applies to
   every module.
3. **Publishing:** nothing to list. The release builds every module but the benchmarks and the TCK runner
   and publishes every one that does not opt out; the GitHub release, provenance and third-party notices
   find the published jars themselves (`.github/scripts/published_modules.py`). A module that must never
   be published (test projects, samples) sets `skipPublishing`, `maven.deploy.skip`,
   `maven.install.skip` and `cyclonedx.skip` to `true`, as `praecepta-tck` does; it is still built and
   tested in the release.
4. **SBOM and licences:** every published module writes `target/bom.json`, and CI checks all of them
   against the licence allowlist and scans them for known vulnerabilities, without being told about new
   modules. Framework dependencies the application always has (Spring Boot, Micronaut, Quarkus' ArC, the
   Gradle and Maven APIs) are `provided`, so they are not part of what Praecepta ships. If the licence
   check rejects a dependency, raise it in the pull request rather than widening the allowlist there.
5. **API documentation:** the website publishes every published module's Javadoc at
   `api/<directory without "praecepta-">` (`api/spring-boot-starter`, `api/quarkus`) with no change to
   `pages.yml`; link it from `site/index.html` when the module is released.
6. **CI load:** the default `mvn verify` (every pull request, on Java 21, 25 and 27) runs a module's fast
   tests only, seconds rather than minutes. Slow checks (other Gradle or framework versions, native
   images) get a workflow of their own that runs on pull requests only when the module's paths (or what it
   is built from) change, always on main and weekly, and cancels superseded runs, as
   `maven-versions.yml` does. The required checks need no change.
7. **Files:** SPDX headers, Checkstyle and `-Werror`, as for every module (see *Pull requests*).
8. **Documentation:** a section in the README after the Maven plugin's, a row in the modules table of
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and in [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) for an
   integration, a line under `## [Unreleased]` in `CHANGELOG.md`, and Javadoc on public types.

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
