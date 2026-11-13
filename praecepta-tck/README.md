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

## Output expressions
`OutputExpressionsTckTest` evaluates the literal expressions of the arithmetic test cases
(`compliance-level-3/0099-arithmetic-negation` and `0100-arithmetic`) as output expressions, which
output entries are: every expression Praecepta reads must give the kit's result, or be one the engine
would reject when it is built (operands of types FEEL does not combine) where the kit expects null; the
test counts the two apart, as a rejected expression gives no result at all. Of the 1,101 checks,
Praecepta reads 489: 276 give the kit's result, 212 are rejected where the kit expects null, and one
differs (below). An expression in `string(...)` is evaluated without it and compared as FEEL's text of the value.
Expressions outside Praecepta's subset (functions, lists, contexts, booleans, `null` operands, hour 24)
are counted as not supported, and one check is known to differ: a power that is not whole, which the kit
gives to 13 digits where FEEL's numbers have 34. These cases have no decision table, so they do not
appear in RESULTS.md.

## Decision requirement graphs
`DecisionGraph` (0.7, [#14](https://github.com/brunopacheco1/praecepta/issues/14)) evaluates whole models whose
decisions are decision tables and literal expressions in Praecepta's expression subset. This module does not score
it yet (scoring the whole TCK is [#115](https://github.com/brunopacheco1/praecepta/issues/115)); a survey of the
pinned commit's 156 model files of compliance levels 2 and 3 found that `DmnReader.readModel` reads 32, and the
rest are rejected for what later releases bring, nearly all for FEEL beyond the subset (#116) or its functions
(#117), and the others for business knowledge models (#118) and decision services or imports (#119). Of the 121
decision results of the test cases of those 32 models, 100 are the kit's: besides the decision tables above, the
literal-expression models `0001-input-data-string`, `0002-input-data-number`, `0003-input-data-string-allowed-values`,
`0101-feel-constants`, `0102-feel-constants`, `0073-feel-comments`, `0077-feel-nan` and `0078-feel-infinity` give
every result. No model at that commit has a decision that requires another decision with only tables and literal
expressions in the subset. The closest are:
- `0008-LX-arithmetic`, one literal expression over a structured input data (`loan.principal`), read and evaluated,
  whose payment Praecepta computes to FEEL's 34 digits (`2778.693549432766768088520383236299`) where the kit gives
  15 (`2778.69354943277`), the case of the power that is not whole above;
- `0106-feel-ternary-logic`, whose literal expressions `A and B` and `A or B` use FEEL's boolean operators (#116),
  which the subset reads as a single name no requirement provides;
- `0020-vacation-days`, a graph of three decision tables and two literal expressions, rejected for `max(...)`, a
  built-in function (#117);
- `0008-listGen` (two tables and eight literal expressions), `0009-append-flatten`, `0010-concatenate`,
  `0011-insert-remove` and `0012-list-functions`, graphs of literal expressions that build lists (#116) and call
  list functions (#117);
- `0004-lending` and `0087-chapter-11-example`, whose decision tables read other decisions, the three checks above
  not supported, which invoke business knowledge models (#118) and, for the latter, decision services (#119).

## The TCK files
`src/test/resources/tck` holds the TCK test directories that contain a decision table, and the two
arithmetic test cases above (their DMN models and test files, without the PDFs), copied unchanged from
[dmn-tck/tck](https://github.com/dmn-tck/tck) at the commit named in `TckSuite.TCK_COMMIT`. They
are licensed under the Apache License 2.0 by the DMN TCK contributors (see `REUSE.toml`).

To update them, copy the same directories (and any new ones with a `decisionTable`) from a newer
commit, update `TckSuite.TCK_COMMIT`, and regenerate RESULTS.md.
