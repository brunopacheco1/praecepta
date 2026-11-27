<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Governance

Praecepta is a small project with a single maintainer. This document says who decides what, how
to take part, and how the project would continue if the maintainer stopped.

## Roles

| Role | Who | Responsibilities |
|---|---|---|
| **Maintainer** | Bruno Pacheco ([@brunopacheco1](https://github.com/brunopacheco1)) | Reviews and merges pull requests; decides the roadmap; publishes releases; answers security reports ([SECURITY.md](SECURITY.md)); keeps CI, dependencies and the website healthy; enforces the [code of conduct](CODE_OF_CONDUCT.md). |
| **Contributor** | Anyone who opens an issue, a pull request or a review | Follows [CONTRIBUTING.md](CONTRIBUTING.md) and the code of conduct. Contributors keep the copyright of their work, licensed under Apache-2.0. |
| **Reviewer** | Any contributor | Reviews pull requests. Reviews from people other than a change's author are welcome on every pull request, including the maintainer's own. |

There is one maintainer today, so every change still needs that person's approval (`.github/CODEOWNERS`).
**A second maintainer is wanted:** someone who has contributed and reviewed for a while can ask, or
be invited; the maintainer adds them to CODEOWNERS, gives them write access, and records it here.

## Decisions
- **Day to day:** in issues and pull requests, in the open. The maintainer decides when there is no
  agreement, and says why.
- **Larger decisions** (semantics, Java baseline, licence, dependencies, public API) start as an issue.
  The [architecture document](docs/ARCHITECTURE.md#design-decisions) records them with their reasons.
- **Compatibility:** versions follow [Semantic Versioning](https://semver.org/). Until 1.0, minor
  versions may change the API; every change is listed in [CHANGELOG.md](CHANGELOG.md).
  [What is supported](#what-is-supported) says what the promise covers.
- **Scope:** a DMN 1.5 engine, judged by the specification and the DMN TCK alone. Praecepta is a
  library: it will not become a process engine, a modeller or a server around the decisions (see the
  README, "What Praecepta is not"). [docs/ROADMAP.md](docs/ROADMAP.md) has what comes next.

## What is supported
Praecepta promises its own API. It does not promise every version of the frameworks and tools around it.

- **One version, one release.** Every module has the same version, and all are released together.
- **Java.** The baseline is Java 21. A release is built with the latest long-term-support JDK, compiling
  for Java 21, so that it stays reproducible, and tested on Java 21. The baseline does not change before
  1.0. After 1.0 it changes only in a major version.
- **Frameworks.** Each framework module supports one major line of its framework: Spring Boot 4,
  Micronaut 4 and Quarkus 3 today. The module's name says which (`praecepta-quarkus3`). CI tests the
  module at the oldest version of the line it supports and at the latest ([#165](https://github.com/brunopacheco1/praecepta/issues/165)), on the higher
  of Java 21 and the framework's own baseline.
- **The oldest framework version supported may rise in a minor release**, past versions the framework's
  vendor no longer supports. The changelog says so in an upgrade note.
- **A new framework major gets a module of its own**, in the same release as the others. Where the
  framework needs a newer Java than the baseline, only that module requires it. The module for a
  major that its vendor no longer supports is deprecated, then removed in a minor release at least six
  months later. This is the one exception to semantic versioning.
- **There is always a way without a framework module.** `praecepta-integration` works with any version of
  any framework: one bean method that calls `Decisions.load` replaces the module.

## Releases
1.0 is released only once Praecepta has been tested with a real use case
([#162](https://github.com/brunopacheco1/praecepta/issues/162)).

The maintainer releases by pushing a signed `v*` tag; CI builds, signs and publishes to Maven
Central and GitHub ([docs/RELEASING.md](docs/RELEASING.md)). Security fixes follow
[SECURITY.md](SECURITY.md).

## Continuity
If the maintainer can no longer continue, the project should go on with minimal interruption: within
a week, someone should be able to triage issues, accept changes and publish a release.

**In place:**
- **A successor for the repository:** the maintainer has named a trusted person as successor in
  GitHub's account settings. If the maintainer's account becomes inactive, the successor can take
  over the repository, with its issues, pull requests, CI, secrets and website, and either carry on
  or hand it to a new maintainer.
- **Releases after a takeover:** the release workflow runs in a protected `release` environment
  whose required reviewer is the maintainer; as the repository's new owner, the successor can name
  a new reviewer and release with the existing signing key and Maven Central credentials, held as
  secrets in the repository.
- **A second maintainer,** once there is one, gets admin access, so the project would not depend on
  the successor process at all.

Also:
- **The code** is Apache-2.0 and public: anyone may fork it and carry on.
- **Releases** need no personal key: CI signs them with a dedicated release key stored as a
  repository secret, which a new maintainer can rotate (docs/RELEASING.md). The Maven Central
  namespace `io.github.brunopacheco1` is verified through this GitHub account; a successor can ask
  Sonatype to transfer it, or publish a fork under their own namespace.
- **Everything else** (SonarCloud, the OpenSSF Best Practices entry, the website on GitHub Pages)
  is tied to the repository and can be taken over with it.

## Changing this document
Changes to governance are proposed in a pull request and decided by the maintainers.
