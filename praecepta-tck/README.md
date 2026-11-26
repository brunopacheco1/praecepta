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
test file's `xsi:type`. It takes a node when the model as a whole is one `readModel` reads and the graph evaluates
the decision: what is not supported is what the engine rejects. A model that defines an external function is not
read, and a decision the graph's validation report blocks is not evaluated. Boxed expressions, business knowledge models and the decision tables
within them are read and evaluated since 0.10 ([#118](https://github.com/brunopacheco1/praecepta/issues/118)), and
decision services since 0.11 ([#119](https://github.com/brunopacheco1/praecepta/issues/119)): a test case that invokes a
service (`type="decisionService"`), or a result node named after one, is the service invoked with the test case's
values as its arguments (`DecisionGraph.evaluateService`), and one that invokes a business knowledge model
(`type="bkm"`) is that model invoked so, by parameter name (`DecisionGraph.invokeKnowledgeModel`). Literal expressions, and decision tables' input expressions, input entries and output entries, are the
full FEEL language since 0.8 ([#116](https://github.com/brunopacheco1/praecepta/issues/116)), with its built-in
functions since 0.9 ([#117](https://github.com/brunopacheco1/praecepta/issues/117)). Where a decision's value is null and its own logic
reported an error (`DecisionGraph.evaluateWithErrors`), the node counts as an error where the kit expects one
(DMN 1.5, 10.3.2.15), and as its null value otherwise.

The nodes it does not take go to `SingleDecisionEvaluator`, which evaluates one decision on its own:

- a **decision table** with `DmnReader` and the engine (`DecisionTableEvaluator`), as since 0.1;
- a **literal expression** with `FeelExpression` (`LiteralExpressionEvaluator`), parsed with the model's input
  data and decisions in scope, through a graph of that one decision whose input data are what it reads, typed by
  their `typeRef` (`TckTypes` reads item definitions), with the test case's values; its value is bound to the
  decision's declared type (10.3.2.9.4), and FEEL's errors count as `GraphEvaluator` counts them. A decision it
  reads whose value the test case does not give needs graphs.

Everything else is not supported: boxed expressions, business knowledge models, decision services and imports in a
model the graph does not take, and a decision that needs the value of another decision in such a model. Where neither
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
| Decision services, imports and the rest | 0.11 ([#119](https://github.com/brunopacheco1/praecepta/issues/119)) | decision services, imports, decisions without logic |
| Not evaluated, by design | never | external functions (Java, PMML), which Praecepta rejects as it runs no code from outside the model ([DMN conformance](../docs/DMN-CONFORMANCE.md#known-deviations)) |

`FeelScan` names the FEEL a literal expression uses: it drops strings and comments, takes date and time
functions of literals as the literals output entries read, and finds keywords, operators, brackets and
invocations, whose name it resolves as a built-in function (DMN 1.5, 10.3.4), a knowledge model or decision
service of the model, or another function. It is a scanner, not a parser, so its names are a guide to what a
node needs, not a proof.

A node's **feature area**, in RESULTS.md, is the latest area among the features its content uses, whether
Praecepta reads them or not, so it does not change as Praecepta grows: the area's row shows how far that layer is
done. *What each release unlocks* counts the not-supported nodes by the latest area among what they need. A literal
expression of the arithmetic of names and literals that the output expressions of 0.6 and 0.7 read counts as no
feature; 0.8 retired their parser, so the kit's expressions it read are listed, as it read them, in
`src/test/resources/subset-expressions.txt`.

## Known failures
- `0008-LX-arithmetic`, two of the three payments: a literal expression over a structured input data
  (`(loan.principal*loan.rate/12)/(1-(1+loan.rate/12)**-loan.termMonths)`), evaluated through the graph since 0.7.
  Praecepta computes FEEL's 34 digits (`562.7073593732659271562143285576524`), which a 50-digit computation agrees
  with, where the kit states `562.707359373292`, off in its last three digits, and `2861.03377700389`, off in its
  last ([DMN conformance](../docs/DMN-CONFORMANCE.md), case 33). The first payment, which the kit states rightly to
  its 15 digits, passes, as does a power that is not whole in `0100-arithmetic`, since 0.9 (see *How results are
  compared*).
- The same payment, computed by a business knowledge model since 0.10: `0009-invocation-arithmetic` and
  `0005-literal-invocation` (two each), `0003-iteration`, `0004-lending` and `0014-loan-comparison`. The kit states
  these as a double computes them, which differs from FEEL's 34 digits beyond the digits it states (case 33).
- `1116-feel-time-function`, `string(time("00:01:00@Etc/UTC"))`, and `0103-feel-is-function`,
  `is(@"23:00:50@Etc/GMT", @"23:00:50Z")`: a time in a zone with one fixed offset is read as the time at that offset,
  as Table 72's example has it equal, so its zone id is not kept apart from the offset, a known deviation
  ([DMN conformance](../docs/DMN-CONFORMANCE.md#known-deviations)).

## How results are compared
- Inputs are given as text, the way `inputs(...)` takes them, to a decision table evaluated alone; qualified input
  expressions (`applicant.age`) read a component of a structured input. Through the graph they are given as
  *How a model is evaluated* says.
- A single-hit table (and a COLLECT aggregation) yields one value or null, a multi-hit table a
  list; a multi-output table yields a context per matching rule. COLLECT lists are compared in any
  order, other lists in order. Praecepta has no null list, so an empty list matches null.
- Numbers compare as decimals; dates, times and date-times by value; booleans ignoring case. A number the kit writes
  to at least 8 significant digits, as it writes real numbers such as `exp(4)` (`54.59815003`), is the real number it
  states: a result of more decimals less than one unit of its last digit away matches it, as FEEL's 34 digits do
  ([DMN conformance](../docs/DMN-CONFORMANCE.md), case 33). One of fewer digits is compared exactly.
- DMN reports errors as a null result flagged `errorResult`. A node flagged so passes when evaluation throws
  `HitPolicyViolationException` or `InvalidInputException` (a hit policy violation, an input outside its value
  list), or when its decision's value is null and its literal expression reported an error, which FEEL does where
  it defines no value (10.3.2.15); it fails otherwise.

## Output entries
`OutputExpressionsTckTest` evaluates the literal expressions of the arithmetic test cases
(`compliance-level-3/0099-arithmetic-negation` and `0100-arithmetic`) as the output entry of a decision table, which
is any FEEL expression since 0.8 ([#116](https://github.com/brunopacheco1/praecepta/issues/116)): a one-rule table
whose output declares no type, so that each value keeps its own (DMN 1.5, 10.3.2.10, step 3c), evaluated through a
`DecisionGraph`. Every expression must give the kit's result, and all 1,101 checks do, 826 of them null, which FEEL
gives for operands it does not combine (0.7 rejected such a table when it was built). An expression in `string(...)`,
or one that invokes another built-in function, is evaluated as it is (since 0.9). The whole-kit run scores the same
expressions as literal expression decisions (see *How a model is evaluated*), where all of them pass.

## Decision requirement graphs
`DecisionGraph` (0.7, [#14](https://github.com/brunopacheco1/praecepta/issues/14)) evaluates whole models whose
decisions are decision tables and literal expressions, in the full FEEL language since 0.8, and boxed expressions
and business knowledge models since 0.10, decision services and imports since 0.11, and `GraphEvaluator` scores it. A
model that imports others is read with them (`DmnReader.readModel(dmn, imports)`), each found among the DMN files of
its test case's directory by its `locationURI`, else by its namespace; an input of an imported model, which the test
file names by its namespace, is that model's input in the graph (`DecisionGraph.nameOf`). Of the pinned commit's 156
model files of compliance levels 2 and 3, `DmnReader.readModel` reads 155; it rejects one for an external function,
which Praecepta does not evaluate by design. Models it does not read are evaluated decision by decision.

## The TCK files
`src/test/resources/tck` holds every test directory of compliance levels 2 and 3 (their DMN models and test
files, without the PDFs, images and other documents), copied unchanged from
[dmn-tck/tck](https://github.com/dmn-tck/tck) at the commit named in `TckSuite.TCK_COMMIT`. They are licensed
under the Apache License 2.0 by the DMN TCK contributors (see `REUSE.toml`).

To update them, copy the `*.dmn` and `*-test-*.xml` files of both levels from a newer commit, update
`TckSuite.TCK_COMMIT` and the counts in `TckTest`, and regenerate RESULTS.md and the baseline.
