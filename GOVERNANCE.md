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
- **Larger decisions** (semantics, Java baseline, licence, dependencies, public API) are recorded with
  their reasons in [docs/MODERNISATION.md](docs/MODERNISATION.md), and new ones are proposed as an
  issue first.
- **Compatibility:** versions follow [Semantic Versioning](https://semver.org/). Until 1.0, minor
  versions may change the API; every change is listed in [CHANGELOG.md](CHANGELOG.md).
- **Scope:** DMN 1.5, judged by its specification and the DMN TCK alone. Since 0.7 the project grows
  from a decision-table engine into a full DMN 1.5 engine, one layer per release
  ([#120](https://github.com/brunopacheco1/praecepta/issues/120), [docs/ROADMAP.md](docs/ROADMAP.md)),
  keeping decision tables' validation and typed generated code. What it will not do: become a process
  engine, a modeller or a server around the decisions (see the README, "What Praecepta is not").

## Releases
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
