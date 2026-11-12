<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Security assurance case

Why Praecepta's security requirements are met: what users can expect, the threats considered, the
trust boundaries, the design principles applied, the common weaknesses countered, and the evidence.
How to report a vulnerability is in [SECURITY.md](../SECURITY.md); the design itself is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## What users can and cannot expect
Praecepta is a library that runs inside the application that calls it, with that application's
permissions. It opens no network connections, starts no processes, uses no cryptography and stores no
data. The engine needs no configuration; the framework integrations read a few settings of the
application's (`praecepta.locations`, `praecepta.fail-on`, `praecepta.enabled`), which only choose the
tables to load and how strictly to check them.

**Users can expect:**
- **Reading untrusted DMN does not reach outside the document:** `DmnReader` resolves no DTDs and no
  external entities, so a DMN file cannot read local files or make network requests (XXE).
- **Bad input fails cleanly:** any malformed table, cell or DMN document is rejected with a
  documented exception (`InvalidDecisionTableException`, `CellSyntaxException`) naming where the
  problem is, never with an unexpected exception or a half-built engine.
- **Input values are checked:** when a column declares its input values (a DMN value list), values
  outside it are rejected rather than silently matched.
- **No surprises at runtime:** an engine is immutable and thread-safe; evaluation has no side
  effects and allocates little.
- **Code the Maven and Gradle plugins generate is safe to compile:** table, column and value names
  become sanitised Java identifiers, and text copied into string literals or comments cannot break out
  of them. Both plugins generate through the same `praecepta-compiler`.
- **Bad tables stop the application early:** the framework integrations stop the application's startup
  (Spring Boot, Micronaut) or its build (Quarkus) when a table cannot be read or built, or has validation
  issues at the configured severity, listing every problem.
- **Releases are authentic:** signed, with SLSA Build Level 2 provenance and an SBOM, and reproducible:
  [Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/io/github/brunopacheco1/praecepta/README.md) rebuilds each release independently and checks it is bit-for-bit
  identical to Maven Central.

**Users cannot expect (out of scope):**
- **Protection from a malicious table's logic.** A table decides what the application does with
  its answer; who may change tables is the application's responsibility.
- **Bounded build time for crafted FIRST or PRIORITY tables.** The unreachable-rule check is exact,
  so a table built to defeat it can still be slow to validate. Since 0.2, large realistic tables
  build in under a second (1,000 rules and 9 columns: 0.7 s, against 65 s in 0.1.0;
  [#29](https://github.com/brunopacheco1/praecepta/issues/29)). Applications that build engines
  from untrusted tables should limit the table size, or build them off the request path.
- **Limits on the size of input documents** beyond the JDK's XML processing limits
  (`FEATURE_SECURE_PROCESSING`); applications should cap upload sizes.
- **Spreadsheet safety beyond Apache POI's.** Excel files are parsed by Apache POI, kept up to date
  by Dependabot.
- **Confidential rules behind the report endpoints.** The Spring Boot Actuator endpoint and the
  Micronaut management endpoint `praecepta` describe every table, its columns and the rules' conditions.
  They only read, and nothing is evaluated through them; Actuator exposes the endpoint only when the
  application says so, and Micronaut treats it as sensitive by default. Who may read them is the
  application's decision.

## Threat model
| Asset | Threat | Countered by |
|---|---|---|
| The host application's files and network | A crafted DMN document using XXE or a DTD to read files or make requests | DTDs disallowed, external entities off, secure processing on (`DmnReader`) |
| The host application's availability | Documents or cells crafted to crash the parser or exhaust memory | Fuzzing of the cell parser and DMN reader; JDK XML limits; a cell grammar without nesting (`not(...)` only wraps a whole cell), and output expressions of at most 1,000 operators and parentheses, so parsing and evaluation depth is bounded |
| Correct decisions | Ambiguous or overlapping rules giving different answers than intended | Strict DMN hit policies; validation of overlaps, unreachable rules and gaps; the engine is tested against a reference implementation with property-based tests |
| A build using the Maven or Gradle plugin | Table content injected into generated Java code | `JavaNames` sanitises identifiers (reserved words included); string literals are escaped; comments escape unicode escapes and comment terminators |
| Users' supply chain | A tampered release or dependency | Signed releases with provenance; pinned CI actions; Dependabot, dependency review and OSV scans; a signed-commit, pull-request-only `main` |

## Trust boundaries
1. **Table sources** (DMN, Excel and CSV files, and rule lists built in code) cross into the engine
   through `DmnReader`, the loaders, `CellParser` and the builder. Everything is validated there;
   nothing after that point trusts the table to be well formed.
2. **Input values** cross through the getters the application binds; they are matched, never
   evaluated as code.
3. **The Maven and Gradle plugins** (and the Quarkus extension, for spreadsheets it compiles) turn
   tables into source code or DMN at build time; the output is the boundary that matters, and names are
   sanitised before they are written.
4. **The framework integrations** load the files that the application's own settings name, from its class
   path or file system, and expose the tables' descriptions through a management endpoint only where the
   framework's own rules for endpoints allow it.

## Secure design principles applied
- **Economy of mechanism:** cells are only *simple unary tests* on one column, and output entries
  at most `+ - * / **` on literals and inputs, not a general expression language with functions, so
  evaluating a table cannot run arbitrary code; the engine has no runtime dependencies.
- **Fail-safe defaults:** unsupported DMN features, unknown cell syntax and undeclared values are
  rejected, not ignored; XML features are off unless needed.
- **Complete mediation:** every table goes through the same parser and validation, whatever its
  source.
- **Least privilege and least common mechanism:** no network, file (except reading the tables the
  caller or the application's settings name), process or reflection access at runtime, in the engine and
  in the integrations alike; no shared mutable state.
- **Open design:** everything, including the threat model and the fuzzing, is public.
- **Psychological acceptability:** validation errors name the decision, rule and column, so the safe
  path is also the easy one.

## Common weaknesses countered
| Weakness | How |
|---|---|
| CWE-611, XML external entities | DTDs disallowed, external entities disabled |
| CWE-776, entity expansion | DTDs disallowed; JDK secure-processing limits |
| CWE-20, improper input validation | Cells parsed against a grammar; value lists enforced; DMN elements checked |
| CWE-94, code injection (build plugins) | Identifiers sanitised; string literals and comment text escaped |
| CWE-400, resource exhaustion | Fuzzed parsers; JDK limits; FIRST and PRIORITY validation fast on large tables since 0.2 (#29), but a crafted table can still be slow |
| CWE-362, race conditions | Immutable engines, no shared mutable state |
| CWE-1104, unmaintained components | One third-party dependency Praecepta ships (Apache POI, in the loader and the build-time modules only; frameworks are the application's own), monitored by Dependabot and OSV |

## Evidence
- **Tests:** 91.9% line coverage; property-based tests compare the engine with a naive reference
  implementation; tests run on Java 21, 25 and 27 on every pull request.
- **Fuzzing:** Jazzer targets for the cell parser and the DMN reader run on every pull request that
  touches the engine (ClusterFuzzLite), weekly for longer, and their saved inputs replay in every build.
- **Static analysis:** CodeQL and SonarCloud on every pull request, with SonarCloud as a required
  quality gate; compiler warnings fail the build; Checkstyle enforces the code style.
- **Supply chain:** signed commits and releases, build provenance, CycloneDX SBOMs, reproducible
  builds verified independently by Reproducible Central, pinned workflow actions, least-privilege workflow tokens, OpenSSF Scorecard.
- **Process:** the OpenSSF Best Practices badge
  ([project 16426](https://www.bestpractices.dev/projects/16426)), a private vulnerability
  reporting channel, and a security policy with response times ([SECURITY.md](../SECURITY.md)).
