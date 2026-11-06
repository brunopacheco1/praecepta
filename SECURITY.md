<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Security policy

## Supported versions
Praecepta has not had a release yet. Security fixes go to `main`; once releases exist, the latest
release line receives them.

## Reporting a vulnerability
Please **do not** open a public issue. Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/brunopacheco1/praecepta/security/advisories/new).

Include what is affected, how to reproduce it, and the impact you see. You can expect an
acknowledgement within a week. We will agree on a disclosure date with you, and credit you in the
advisory unless you prefer otherwise.

## Scope
Of particular interest: the DMN reader (`DmnReader`, which parses untrusted XML), the Excel and
CSV loaders, and the Maven plugin's code generation.
