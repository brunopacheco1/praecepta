<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# praecepta-tck

Runs the whole [DMN Technology Compatibility Kit](https://github.com/dmn-tck/tck) (TCK), every model of
compliance levels 2 and 3, against Praecepta and scores it. Not published. The results are in
[RESULTS.md](RESULTS.md).

```
mvn -pl praecepta-tck -am test
```

`TckTest` turns every result node of every TCK test file into a check (3,511 at the pinned commit: 126 in
the 28 models of level 2, 3,385 in the 120 models of level 3; result nodes a test file has commented out are
not counted). The kit runs in about a second; the module's tests, with the kit loaded, in a few.

- **passed**: Praecepta gives the expected result, or reports an error where the kit expects one;
- **failed**: Praecepta gives another result (JUnit reports it as skipped, with what differed);
- **not supported** (skipped too): the node needs a feature Praecepta does not have yet. The message lists
  every feature it needs, each with the release of the DMN 1.5 plan
  ([#120](https://github.com/brunopacheco1/praecepta/issues/120)) that brings it.

## The baseline: only regressions fail the build
`baseline.txt` lists every result node that passed when it was last regenerated, those that passed through the
decision requirements graph itself (`GraphEvaluator`) tagged `graph` after a tab. The build fails only when one
of them no longer passes, or a tagged one passes only through the evaluator the graph falls back to, which would
hide a graph that stopped taking it (`no_node_the_baseline_has_passing_regresses`, and the node's own check); a
node that fails or is not supported never fails it. The baseline is read from the module's directory, and a run
without it, or with an empty one, fails rather than catch nothing. RESULTS.md and the baseline are regenerated
together, deliberately:

```
mvn -pl praecepta-tck -am test -Dtck.update=true
```

While they differ from the run (a node now passes that the baseline does not list, say), the test that compares
them is skipped with the number of new passes, so a local build stays green; with `-Dtck.strict=true`, as the build
workflow runs it, it fails, so a pull request that changes what passes regenerates and commits them, and their
diff shows what changed. A simple value passes only if it is of the kind the kit expects (a number, a string, a
boolean, a date, a time, a date and time or a duration), so the text `"10"` is not the number 10. Regenerating with a node gone
from the baseline accepts a regression, so do it only when the change is meant (the kit's expectation is wrong
by DMN 1.5, say), and say why in the pull request.

## How a model is evaluated
`ModelEvaluator` is the one seam between the kit and Praecepta: given a result node (its model, the test case's
inputs, and the decision, decision service or business knowledge model it names), it returns the node's value,
an error, a failure, or the features it lacks. `ModelEvaluator.praecepta()` is what the run uses:
`new GraphEvaluator(single).orElse(single)`, where `single` is a `SingleDecisionEvaluator`.

`GraphEvaluator` evaluates a decision through its model's decision requirements graph, as Praecepta does since 0.7
([#14](https://github.com/brunopacheco1/praecepta/issues/14)): `DmnReader.readModel` reads the model and
`DecisionGraph` evaluates the decision from the test case's input data, computing each decision it requires. A
decision whose value the test case gives is input data instead, as the kit means it: the graph is built with that
decision replaced by an input data of its type. Input data of a simple type are given as text, as the integrations
give them; a structured one as a map of its components, a list as a list; one of no declared type typed by the
test file's `xsi:type`. It takes a node when everything the node needs is something Praecepta reads and the model
as a whole is one `readModel` reads: a model with a business knowledge model, a decision service, an import, or
FEEL beyond the subset in another decision is not, nor is a decision the graph's validation report blocks
(`0106-feel-ternary-logic`, whose `A and B` reads as a name no requirement provides).

The nodes it does not take go to `SingleDecisionEvaluator`, which evaluates one decision on its own:

- a **decision table** with `DmnReader` and the engine (`DecisionTableEvaluator`), as since 0.1;
- a **literal expression** as an output expression (`LiteralExpressionEvaluator`), the FEEL subset output entries
  compute (arithmetic of numbers, strings, dates, times and durations), reading the decision's input data,
  typed by their `typeRef`, with the test case's values. This is how `OutputExpressionsTckTest` checks the
  arithmetic test cases (below), but stricter: `string(...)` is the built-in it is, an expression output
  expressions reject where FEEL gives null is not supported, a null where the kit expects an error is not
  supported (output expressions report no errors), and a value of another type than the decision's is not
  supported (DMN's implicit conversions, 10.3.2.9.4). `GraphEvaluator` is as strict about a null where the kit
  expects an error.

Everything else is not supported: other boxed expressions, business knowledge models, decision services, imports,
and a decision that needs the value of another decision in a model the graph does not take. Where neither
evaluator supports a node, the reasons are the single evaluator's, without *inputs computed by other decisions*,
which the graph has (`SingleDecisionEvaluator.needsBesideGraphs`); a node that needs nothing but another decision
computed, in a model `readModel` rejects for what another decision uses, needs that first. A later release that
adds a way to evaluate models puts its evaluator in front the same way.

## What a node needs, and its feature area
A not-supported node lists every feature it needs: what stopped evaluation first, then what the decisions,
knowledge models and decision services it requires need, but for decisions whose value the test case gives.
Each is a `Feature` of one area of the plan, named so a release can count what it unlocks:

| Area | Release | Features |
|---|---|---|
| Single decision tables and arithmetic | 0.6 | what `DmnReader` rejects in a table |
| Decision requirement graphs | 0.7 ([#14](https://github.com/brunopacheco1/praecepta/issues/14)) | inputs computed by other decisions, which `GraphEvaluator` computes, so no node needs it now |
| FEEL language | 0.8 ([#116](https://github.com/brunopacheco1/praecepta/issues/116)) | a FEEL construct by name: `if then else`, `for loops`, `lists`, `contexts`, `comparisons`, `in`, `instance of`, `paths`, `null`, errors, a decision's type... |
| Built-in functions | 0.9 ([#117](https://github.com/brunopacheco1/praecepta/issues/117)) | each built-in function by its name: `abs()`, `string length()`... |
| Boxed expressions, business knowledge models and structured values | 0.10 ([#118](https://github.com/brunopacheco1/praecepta/issues/118)) | business knowledge models, invocations, function definitions and lambdas, boxed contexts, relations, lists, conditionals, filters and iterators, structured inputs |
| Decision services, imports and the rest | 0.11 ([#119](https://github.com/brunopacheco1/praecepta/issues/119)) | decision services, imports, external functions, decisions without logic |

`FeelScan` names the FEEL a literal expression uses: it drops strings and comments, takes date and time
functions of literals as the literals output entries read, and finds keywords, operators, brackets and
invocations, whose name it resolves as a built-in function (DMN 1.5, 10.3.4), a knowledge model or decision
service of the model, or another function. It is a scanner, not a parser, so its names are a guide to what a
node needs, not a proof.

A node's **feature area**, in RESULTS.md, is the latest area among the features its content uses, whether
Praecepta reads them or not, so it does not change as Praecepta grows: the area's row shows how far that layer is
done. *What each release unlocks* counts the not-supported nodes by the latest area among what they need.

## Known failures
- `0100-arithmetic`, `exponent_lhs_number_exp_rhs_number_005`: a power that is not whole, which the kit gives to
  13 significant digits, where FEEL's numbers have 34 (DMN 1.5, 10.3.2.3.1 and Table 60), as output expressions
  keep them (#71).
- `1116-feel-time-function`, error cases 067 and 068: `time("13:20:00+19:00")` and `time("13:20:00-19:00")`, an
  offset beyond the range of a time zone, which the kit expects to be an error; the engine reads them as the
  local time `13:20:00`, dropping the offset.
- `1117-feel-date-and-time-function`, error case 068: `date and time("+99999-12-01T11:22:33")`, a year with a
  leading `+`, which the kit expects to be an error; the engine reads it.
- `0008-LX-arithmetic`, the three payments: a literal expression over a structured input data
  (`(loan.principal*loan.rate/12)/(1-(1+loan.rate/12)**-loan.termMonths)`), evaluated through the graph since 0.7,
  whose power is not whole: Praecepta computes FEEL's 34 digits (`2778.693549432766768088520383236299`) where the kit
  gives 15 (`2778.69354943277`), the case of the first failure above.

## How results are compared
- Inputs are given as text, the way `inputs(...)` takes them, to a decision table evaluated alone; qualified input
  expressions (`applicant.age`) read a component of a structured input. Through the graph they are given as
  *How a model is evaluated* says.
- A single-hit table (and a COLLECT aggregation) yields one value or null, a multi-hit table a
  list; a multi-output table yields a context per matching rule. COLLECT lists are compared in any
  order, other lists in order. Praecepta has no null list, so an empty list matches null.
- Numbers compare as decimals; dates, times and date-times by value; booleans ignoring case.
- DMN reports errors (a hit policy violation, an input outside its value list) as a null result
  flagged `errorResult`; Praecepta throws instead. A node flagged `errorResult` passes when
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
gives to 13 digits where FEEL's numbers have 34. The whole-kit run scores the same expressions as decisions,
more strictly (see *How a model is evaluated*), so it counts fewer of them as passed.

## Decision requirement graphs
`DecisionGraph` (0.7, [#14](https://github.com/brunopacheco1/praecepta/issues/14)) evaluates whole models whose
decisions are decision tables and literal expressions in Praecepta's expression subset, and `GraphEvaluator` scores
it. Of the pinned commit's 156 model files of compliance levels 2 and 3, `DmnReader.readModel` reads 32; the rest
are rejected for what later releases bring, nearly all for FEEL beyond the subset (#116) or its functions (#117),
and the others for business knowledge models (#118) and decision services or imports (#119). The graph evaluates
the 103 result nodes of those models' test cases that it takes, of which 100 pass: 81 of the 83 decision-table
checks that pass (the other two, in `0008-listGen`, are in a model with FEEL beyond the subset, and pass evaluated
alone), and every result of the literal-expression models `0001-input-data-string`, `0002-input-data-number`,
`0003-input-data-string-allowed-values`, `0101-feel-constants`, `0102-feel-constants`, `0073-feel-comments`,
`0077-feel-nan` and `0078-feel-infinity`. The other three are the payments of `0008-LX-arithmetic`, a known failure
above. No result node at that commit needs graphs alone, so graphs add no pass to the kit: every node of a decision
that requires another decision, the value of which the test case does not give, also needs what a later release
brings. The closest are:
- `0106-feel-ternary-logic`, whose literal expressions `A and B` and `A or B` use FEEL's boolean operators (#116),
  which the subset reads as a single name no requirement provides;
- `0020-vacation-days`, a graph of three decision tables and two literal expressions, rejected for `max(...)`, a
  built-in function (#117);
- `0008-listGen` (two tables and eight literal expressions), `0009-append-flatten`, `0010-concatenate`,
  `0011-insert-remove` and `0012-list-functions`, graphs of literal expressions that build lists (#116) and call
  list functions (#117);
- `0004-lending` and `0087-chapter-11-example`, whose decision tables read other decisions, the three
  decision-table checks not supported, which invoke business knowledge models (#118) and, for the latter, decision
  services (#119).

## The TCK files
`src/test/resources/tck` holds every test directory of compliance levels 2 and 3 (their DMN models and test
files, without the PDFs, images and other documents), copied unchanged from
[dmn-tck/tck](https://github.com/dmn-tck/tck) at the commit named in `TckSuite.TCK_COMMIT`. They are licensed
under the Apache License 2.0 by the DMN TCK contributors (see `REUSE.toml`).

To update them, copy the `*.dmn` and `*-test-*.xml` files of both levels from a newer commit, update
`TckSuite.TCK_COMMIT` and the counts in `TckTest`, and regenerate RESULTS.md and the baseline.
