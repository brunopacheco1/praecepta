<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# praecepta-tck

Runs the [DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) (TCK) against Praecepta in every
build: every model of compliance levels 2 and 3. It scores each result, fails the build on a regression, and
writes the results to [RESULTS.md](RESULTS.md). The module is not published.

```
mvn -pl praecepta-tck -am test
```

The kit's files are in `src/test/resources/tck`, at the commit `TckSuite.TCK_COMMIT` names. A *result node* is one
expected result in a test file: a decision, a decision service or a business knowledge model evaluated with one
test case's inputs. The pinned commit has 3,511 result nodes: 126 in the 28 models of level 2 and 3,385 in the 120
models of level 3. Result nodes that a test file has commented out are not counted.

`TckTest` makes one check of each result node:

1. `TckSuite` reads the test files.
2. `GraphEvaluator` evaluates the node through its model's decision requirements graph. `DmnReader.readModel`
   reads the model and `DecisionGraph` evaluates it, as an application does.
3. `TckRunner` compares the value with the expected one and scores the node.
4. `Baseline` checks that no node that passed before stopped passing.
5. `TckResults` renders RESULTS.md.

Three more tests use the kit's models outside the run:

- `DefaultOutputsTest` evaluates the tables that declare default outputs where no rule matches.
- `OutputExpressionsTckTest` evaluates the kit's arithmetic expressions as the output entries of a decision table.
- `IntegrationLoadsTckModelsTest` loads models as the framework integrations load them.

## How a result node is scored

- **Passed**: Praecepta gives the expected result, or reports an error where the kit expects one.
- **Failed**: Praecepta gives another result, or does not evaluate the node. JUnit reports the check as skipped,
  with what differed.
- **Not supported**: the DMN reader rejects the node's model because it uses what Praecepta rejects by design, an
  external function (Java, PMML): Praecepta runs no code from outside the model
  ([known deviations](../docs/DMN-CONFORMANCE.md#known-deviations)). JUnit reports the check as skipped, with the
  reader's message. The 18 nodes of `0076-feel-external-java` are the only ones.

### How a node is evaluated

- A model that imports others is read with them. An imported model is found among the DMN files of the same
  directory, by its `locationURI`, else by its namespace.
- A decision is evaluated from the test case's input data. The graph computes the decisions it requires.
- A decision whose value the test case gives is not computed: it becomes input data of its type.
- A test case of `type="decisionService"`, or a result node named after a decision service, invokes the service
  with the test case's values as its arguments.
- A test case of `type="bkm"` invokes the business knowledge model with the test case's values, by parameter name.
- An input of a declared simple type is given as text, a structure as a map of its components, and a list as a
  list. An input of no declared type gets its type from the test file's `xsi:type`.

### How results are compared

- A simple value must be of the kind the kit expects: a number, a string, a boolean, a date, a time, a date and
  time, or a duration. The text `"10"` is not the number 10.
- Numbers compare as decimals. Dates, times, date-times and durations compare by value. Booleans ignore case.
- A number the kit writes to at least 8 significant digits, such as `exp(4)` (`54.59815003`), is the real number
  it states. A result with more decimals that is less than one unit of the kit's last digit away matches it, as
  FEEL's 34 digits do ([DMN conformance](../docs/DMN-CONFORMANCE.md), case 33). A number of fewer digits is
  compared exactly.
- A time or a date-time with a zone matches only one with a zone, at the same instant; a time with a zone id only
  one in the same zone.
- A context matches when it has the same entries and each one matches.
- Lists compare in order. The list of a COLLECT table compares in any order. An empty list and null match each
  other.
- DMN reports an error as a null result, which the kit flags `errorResult`. Such a node passes when evaluation
  throws `HitPolicyViolationException` or `InvalidInputException`, or when the value is null and FEEL reported an
  error (DMN 1.5, 10.3.2.15). It fails when evaluation gives a value. A node that is not flagged fails when
  evaluation throws.

## The baseline

`baseline.txt` lists every result node that passes, one key per line. The build fails when one of them no longer
passes. A node that fails or is not supported does not fail the build. A run without the baseline, or with an
empty one, fails.

`TckTest` generates RESULTS.md and the baseline together:

```
mvn -pl praecepta-tck -am test -Dtck.update=true
```

When the two files differ from the run, the test that compares them is skipped, so a local build stays green.
With `-Dtck.strict=true` that test fails. The build workflow runs it so: a pull request that changes what passes
must regenerate both files and commit them, and their diff shows what changed.

Regenerating with a node gone from the baseline accepts a regression. Do it only when the change is meant, and
say why in the pull request.

## Known failures

Eleven result nodes fail.

Nine are loan payments. The kit states them as a double computes them. FEEL has 34 digits, so Praecepta's result
differs beyond the digits a double holds ([DMN conformance](../docs/DMN-CONFORMANCE.md), case 33):

- `0008-LX-arithmetic` (2): `(loan.principal*loan.rate/12)/(1-(1+loan.rate/12)**-loan.termMonths)`. Praecepta
  gives `562.7073593732659271562143285576524`, and a 50-digit computation agrees with it. The kit states
  `562.707359373292`, which is off in its last three digits, and `2861.03377700389`, which is off in its last.
  The first payment passes: the kit states it rightly to its 15 digits.
- The same payment, computed by a business knowledge model: `0009-invocation-arithmetic` (2),
  `0005-literal-invocation` (2), `0003-iteration`, `0004-lending` and `0014-loan-comparison`.

Two come from a [known deviation](../docs/DMN-CONFORMANCE.md#known-deviations): a time in a zone with one fixed
offset is read as the time at that offset, so it keeps no zone id.

- `1116-feel-time-function`: `string(time("00:01:00@Etc/UTC"))` gives `"00:01:00Z"`.
- `0103-feel-is-function`: `is(@"23:00:50@Etc/GMT", @"23:00:50Z")` gives true.

## The TCK files

`src/test/resources/tck` holds every test directory of compliance levels 2 and 3: the DMN models and the test
files, without the PDFs, images and other documents. They are copied unchanged from
[dmn-tck/tck](https://github.com/dmn-tck/tck). The DMN TCK contributors license them under the Apache License
2.0 (see `REUSE.toml`).

To update them:

1. Copy the `*.dmn` and `*-test-*.xml` files of both levels from the newer commit.
2. Set `TckSuite.TCK_COMMIT` to that commit, and update the counts in `TckTest` and in this file.
3. Regenerate RESULTS.md and the baseline. Fix what fails, or record it under *Known failures*.

## Publishing results on the DMN TCK site

The [DMN TCK results site](https://dmn-tck.github.io/tck/) lists a product when its vendor adds two files to
`TestResults/<Vendor>/<version>/` of `dmn-tck/tck`: `tck_results.csv` and `tck_results.properties`. The run
writes both to the directory `tck.results` names (`TckExport`). A relative directory is taken from
`praecepta-tck`.

The site counts test cases, and RESULTS.md counts result nodes, so the two totals differ. A test case can have
several result nodes: the pinned commit has 3,407 test cases and 3,511 result nodes. `tck_results.csv` has one
line per test case:

- `SUCCESS`: every result node of the test case passes.
- `ERROR`: at least one fails. The message names each failing node, with what the kit expects and what Praecepta
  gives.
- `IGNORED`: none fails and at least one is not supported. The message is the reason.

To publish a release:

1. Update the vendored kit to the upstream commit you publish against (see *The TCK files*). The pinned commit is
   behind upstream `master`, which has test cases this module does not have: more `1159-prefix-imports` files
   and `1162-import-same-name`, for example. Regenerate, and fix or record what changes.
2. Check out the release and run `mvn -pl praecepta-tck -am test -Dtck.results=<dir>`.
   `tck_results.properties` states the version of the build and the day of the run.
3. Copy the two files to `TestResults/Praecepta/<version>/` in a fork of `dmn-tck/tck`, and open a pull request.
   Once it is merged, the site shows Praecepta with its counts. `instructions.url` points readers to this README
   to reproduce the run.
4. Join the TCK group. It is free: see
   [Joining the Effort](https://github.com/dmn-tck/tck/wiki/Joining-the-Effort).
