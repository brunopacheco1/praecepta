<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Security policy

## Supported versions
Until 1.0, only the latest minor release line receives security fixes. Fixes land on `main` and
ship in a patch release.

| Version | Supported |
|---|---|
| The latest minor release line ([releases](https://github.com/brunopacheco1/praecepta/releases)) | Yes |
| Earlier lines | No: upgrade to the latest release |
| `main` (unreleased) | Yes |

## Reporting a vulnerability
Please **do not** open a public issue. Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/brunopacheco1/praecepta/security/advisories/new).

Include what is affected, how to reproduce it, and the impact you see. What to expect:
- an acknowledgement within 7 days;
- a fix released within 60 days for vulnerabilities of medium severity or higher, sooner for
  critical ones;
- a disclosure date agreed with you, a GitHub security advisory (with a CVE when it applies), and
  credit in the advisory unless you prefer otherwise.

## Scope
Of particular interest: the DMN reader (`DmnReader`, which parses untrusted XML, whole decision models
included) and the decision requirement graphs built from what it reads (`DecisionGraph`), the cell parser,
the Excel and CSV loaders, the code generation that the Maven and Gradle plugins and the Quarkus
extension share (`praecepta-compiler`), and the framework integrations' report endpoints. What users can
expect, the threat model and how each threat is countered are in the
[security assurance case](docs/SECURITY-ASSURANCE.md).

## Verifying a release
Every artifact on Maven Central is GPG-signed (`gpg --verify praecepta-engine-X.Y.Z.jar.asc`), and
its build provenance is recorded as a GitHub attestation
(`gh attestation verify praecepta-engine-X.Y.Z.jar --repo brunopacheco1/praecepta`). Each
[GitHub release](https://github.com/brunopacheco1/praecepta/releases) also carries the signatures and
an SBOM (CycloneDX). [docs/RELEASING.md](docs/RELEASING.md) has the details.
