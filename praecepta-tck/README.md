<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# praecepta-tck

Runs the decision-table test cases of the [DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck)
(TCK) against `praecepta-engine`. Not published. The results are in [RESULTS.md](RESULTS.md).

```
mvn -pl praecepta-tck -am test
```

`TckTest` turns every expected result of every TCK test file into a check:

- **passed**: the engine returns the expected result;
- **not supported** (JUnit reports it as skipped): the table uses a feature Praecepta does not have
  (`DmnReader` rejects it), or the inputs are of a kind it does not take. The message names the
  feature;
- **failed**: anything else. Every supported check must pass, so a failure fails the build.

Only decisions whose logic is a decision table are checked. RESULTS.md must match the run, so a
change in the results fails the build until it is regenerated:

```
mvn -pl praecepta-tck -am test -Dtck.update=true
```

## How results are compared
- Inputs are given as text, the way `inputs(...)` takes them; qualified input expressions
  (`applicant.age`) read a component of a structured input.
- A single-hit table (and a COLLECT aggregation) yields one value or null, a multi-hit table a
  list; a multi-output table yields a context per matching rule. COLLECT lists are compared in any
  order, other lists in order. Praecepta has no null list, so an empty list matches null.
- Numbers compare as decimals; dates, times and date-times by value; booleans ignoring case.
- DMN reports errors (a hit policy violation, an input outside its value list) as a null result
  flagged `errorResult`; Praecepta throws instead. A check flagged `errorResult` passes when
  evaluation throws `HitPolicyViolationException` or `InvalidInputException`, and fails otherwise.

## The TCK files
`src/test/resources/tck` holds the TCK test directories that contain a decision table (their DMN
models and test files, without the PDFs), copied unchanged from
[dmn-tck/tck](https://github.com/dmn-tck/tck) at the commit named in `TckSuite.TCK_COMMIT`. They
are licensed under the Apache License 2.0 by the DMN TCK contributors (see `REUSE.toml`).

To update them, copy the same directories (and any new ones with a `decisionTable`) from a newer
commit, update `TckSuite.TCK_COMMIT`, and regenerate RESULTS.md.
