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
- **What the project will not do:** become a full DMN engine (see the README, "What Praecepta is
  not"). Proposals in that direction are better served by Camunda or Drools.

## Releases
The maintainer releases by pushing a signed `v*` tag; CI builds, signs and publishes to Maven
Central and GitHub ([docs/RELEASING.md](docs/RELEASING.md)). Security fixes follow
[SECURITY.md](SECURITY.md).

## Continuity
If the maintainer can no longer continue, the project should go on with minimal interruption: within
a week, someone should be able to triage issues, accept changes and publish a release.

**Status: being arranged.** The maintainer is looking for a trusted person to take this role; this
section will name the arrangement once it is in place. The plan:
- **A successor for the repository:** a trusted person named in GitHub's account settings, who can
  take over the repository (and its issues, CI and website) if the maintainer's account becomes
  inactive, and hand it to a new maintainer.
- **Emergency access:** the same or another trusted person gets emergency access, through a password
  manager, to what the repository does not hold, with a note on how to hand the project on.
- **A second maintainer,** once there is one, gets admin access, which removes the need for either.

What already holds today:
- **The code** is Apache-2.0 and public: anyone may fork it and carry on.
- **Releases** need no personal key: CI signs them with a dedicated release key stored as a
  repository secret, which a new maintainer can rotate (docs/RELEASING.md). The Maven Central
  namespace `io.github.brunopacheco1` is verified through this GitHub account; a successor can ask
  Sonatype to transfer it, or publish a fork under their own namespace.
- **Everything else** (SonarCloud, the OpenSSF Best Practices entry, the website on GitHub Pages)
  is tied to the repository and can be taken over with it.

## Changing this document
Changes to governance are proposed in a pull request and decided by the maintainers.
