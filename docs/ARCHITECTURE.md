<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Architecture

Praecepta is a library, not a service. An application builds an engine or a decision graph once, and
calls it from any thread.

This document describes how the project is built, following the [C4 model](https://c4model.com): system
context, containers, components and code, then the main flows. It does not describe DMN itself. For
what a DMN feature means, read the [DMN 1.5 specification](https://www.omg.org/spec/DMN/1.5/) and the
[DMN TCK](https://github.com/dmn-tck/tck); [DMN features and where they live](#dmn-features-and-where-they-live)
maps each feature to its code and its TCK test cases.

- [Level 1: system context](#level-1-system-context)
- [Level 2: containers](#level-2-containers)
- [Level 3: components](#level-3-components)
- [Level 4: code](#level-4-code)
- [Dynamic views](#dynamic-views)
- [The bitset index](#the-bitset-index)
- [DMN features and where they live](#dmn-features-and-where-they-live)
- [Design decisions](#design-decisions)
- [Quality gates](#quality-gates)

## Level 1: system context
Who and what Praecepta works with.

```mermaid
flowchart TB
    author(["<b>Rule author</b><br/>[Person]<br/>Writes decision tables<br/>and DMN models"])
    dev(["<b>Application developer</b><br/>[Person]<br/>Adds Praecepta to a<br/>Java application"])
    modeller["<b>DMN modeller or spreadsheet</b><br/>[External system]<br/>Camunda Modeler, Trisotech,<br/>Excel, CSV"]
    build["<b>Build tool</b><br/>[External system]<br/>Maven or Gradle"]
    app["<b>Java application</b><br/>[External system]<br/>Calls Praecepta in-process"]
    fw["<b>Framework</b><br/>[External system]<br/>Spring Boot, Micronaut<br/>or Quarkus"]
    praecepta["<b>Praecepta</b><br/>[Software system]<br/>Validates and evaluates DMN decision tables<br/>and decision requirement graphs"]
    author -- "writes tables with" --> modeller
    modeller -- "DMN, Excel and CSV files" --> praecepta
    dev -- "configures" --> build
    build -- "runs the plugins of" --> praecepta
    app -- "evaluates decisions with" --> praecepta
    fw -- "loads tables as beans through" --> praecepta
    classDef person fill:#08427b,stroke:#052e56,color:#fff
    classDef system fill:#1168bd,stroke:#0b4884,color:#fff
    classDef external fill:#6b7280,stroke:#4b5563,color:#fff
    class author,dev person
    class praecepta system
    class modeller,build,app,fw external
```

Praecepta reaches nothing outside the process: no network, no database, no other service. Its only
inputs are the files and values the application gives it.

## Level 2: containers
Praecepta has no deployable unit of its own, so its containers are the jars an application or a build
depends on. Each is a Maven module in a directory under the repository root. Its Java package is
`io.github.brunopacheco1.praecepta.<module>`, and no package is split across modules.

```mermaid
flowchart TB
    subgraph buildtime["Build time"]
        maven["<b>praecepta-maven-plugin</b><br/>[Maven plugin]<br/>The generate goal"]
        gradle["<b>praecepta-gradle-plugin</b><br/>[Gradle plugin]<br/>The generatePraecepta task"]
        qdeploy["<b>praecepta-quarkus-deployment</b><br/>[Quarkus build steps]<br/>Finds, compiles and validates tables"]
        compiler["<b>praecepta-compiler</b><br/>[Java library]<br/>Validates tables, writes DMN,<br/>generates typed classes"]
        loader["<b>praecepta-loader</b><br/>[Java library, Apache POI]<br/>Reads Excel and CSV rules"]
    end
    subgraph runtime["Run time"]
        spring["<b>praecepta-spring-boot-starter</b><br/>[Spring Boot auto-configuration]"]
        micronaut["<b>praecepta-micronaut</b><br/>[Micronaut module]"]
        quarkus["<b>praecepta-quarkus</b><br/>[Quarkus extension, runtime]"]
        integration["<b>praecepta-integration</b><br/>[Java library]<br/>Finds DMN files, builds named engines"]
        engine["<b>praecepta-engine</b><br/>[Java library, JPMS module, JDK only]<br/>Tables, FEEL, decision graphs,<br/>DMN reading and writing"]
    end
    maven --> compiler
    gradle --> compiler
    qdeploy --> compiler
    qdeploy --> quarkus
    compiler --> loader
    compiler --> engine
    loader --> engine
    spring --> integration
    micronaut --> integration
    quarkus --> integration
    integration --> engine
    classDef container fill:#1168bd,stroke:#0b4884,color:#fff
    classDef core fill:#08427b,stroke:#052e56,color:#fff
    class maven,gradle,qdeploy,compiler,loader,spring,micronaut,quarkus,integration container
    class engine core
```

An arrow means "depends on".

| Module | Depends on | Role |
|---|---|---|
| `praecepta-engine` | the JDK (`java.xml`) | Decision tables, the bitset index, validation, FEEL, decision graphs, DMN reading and writing. |
| `praecepta-loader` | engine, Apache POI | Reads rules from Excel and CSV files. |
| `praecepta-compiler` | engine, loader | The core of the build tools: loads a table, validates it, writes it as DMN and generates typed Java classes. Knows no build tool. |
| `praecepta-integration` | engine | The core of the framework integrations: finds DMN files and builds one map-based engine per decision. Knows no framework. |
| `praecepta-maven-plugin` | compiler, Maven API | Runs the compiler on the configured tables. |
| `praecepta-gradle-plugin` | compiler, Gradle API | The same, as a cacheable Gradle task. Built by Maven. |
| `praecepta-gradle-plugin-marker` | gradle plugin | The marker POM through which Gradle finds the plugin by id on Maven Central. |
| `praecepta-spring-boot-starter` | integration, Spring Boot | Registers a `Decisions` bean and an engine bean per decision at startup. |
| `praecepta-micronaut` | integration, Micronaut 4 | The same, for Micronaut. |
| `praecepta-quarkus` | integration, Quarkus ArC | Builds the beans at run time from the DMN the build validated. |
| `praecepta-quarkus-deployment` | quarkus, compiler | Finds, compiles and validates the tables while Quarkus builds the application. |

Three more modules are never published: `praecepta-benchmarks` (JMH comparisons with Camunda and
Drools), `praecepta-tck` (runs the DMN TCK) and `praecepta-native-tests` (builds an application per
framework as a GraalVM native executable).

Two rules keep the modules apart:
- **The engine depends on nothing but the JDK.** Every build tool and framework has a module of its own.
- **Spreadsheets at build time, DMN at run time.** Only the loader needs Apache POI, and only the build
  tools use the loader. The frameworks read DMN.

## Level 3: components

### praecepta-engine
The engine has five packages. The root package holds the two entry points, `PraeceptaEngine` for one
table and `DecisionGraph` for a whole model. The packages form layers: the root package uses `feel`,
`beans`, `dmn` and `exceptions`, and none of them uses the root package's engines.

```mermaid
flowchart TB
    caller["<b>Application, compiler<br/>or integration</b>"]
    subgraph engine["praecepta-engine"]
        tengine["<b>Table engine</b><br/>PraeceptaEngine, TableColumns,<br/>DeferredCells, ComputedOutputs,<br/>HitPolicies, TableExpressions"]
        index["<b>Bitset index</b><br/>BitsetIndex, ColumnIndex, ListColumn,<br/>Masks, PrimitiveKeys, OpenIntervals"]
        analysis["<b>Table analysis</b><br/>TableAnalysis, ListAnalysis,<br/>Completeness, ValidationReport"]
        dgraph["<b>Decision graph</b><br/>DecisionGraph, GraphAnalysis and its phases,<br/>GraphNodes, GraphValues, ImportedModels"]
        feel["<b>FEEL</b> (feel)<br/>CellParser, FeelUnaryTests, FeelExpression,<br/>FeelLexer, FeelParser, Node,<br/>BuiltInFunctions"]
        dmn["<b>DMN XML</b> (dmn)<br/>DmnReader, DmnWriter<br/>and their readers and writers"]
        model["<b>Model</b> (beans)<br/>DecisionTable, DecisionModel,<br/>DecisionLogic, Praeceptum"]
    end
    caller --> tengine
    caller --> dgraph
    caller --> dmn
    tengine --> index
    tengine --> analysis
    tengine --> feel
    analysis --> index
    index --> feel
    dgraph --> tengine
    dgraph --> feel
    dgraph --> model
    dmn --> model
    dmn --> feel
    feel --> model
    tengine --> model
    classDef component fill:#1168bd,stroke:#0b4884,color:#fff
    classDef ext fill:#6b7280,stroke:#4b5563,color:#fff
    class tengine,index,analysis,dgraph,feel,dmn,model component
    class caller ext
```

| Component | Responsibility |
|---|---|
| Table engine | Builds one decision table into an immutable engine and evaluates it. Applies the hit policy and the aggregation. |
| Bitset index | Maps each column value to the set of rules that accept it, and intersects the sets. See [the bitset index](#the-bitset-index). |
| Table analysis | Finds overlaps, conflicts, unreachable rules, gaps and unreachable defaults when the engine is built. |
| Decision graph | Resolves the requirements of a model, checks them, orders the decisions and evaluates them. |
| FEEL | Parses cells into unary tests, and FEEL text into a tree of nodes that it types and evaluates. Holds the built-in functions. |
| DMN XML | Reads DMN 1.1 to 1.5 into the model, and writes the model as DMN. Uses the JDK's XML parser with DTDs and external entities off. |
| Model | Plain records with no behaviour: tables, rules, models, decisions and their logic. |

Each entry point is a short class that delegates to package-private classes, one per concern. To find your
way:

| To learn how... | Start at | Then read |
|---|---|---|
| an engine is built from a table | `PraeceptaEngine`'s constructor | `TableColumns` (types, cells, order), `HitPolicies` (bit order, aggregation), `ComputedOutputs` |
| an input is evaluated | `PraeceptaEngine.evaluate` | `BitsetIndex.match`, `ColumnIndex.maskFor`, `DeferredCells` |
| a table is analysed | `TableAnalysis` | `ListAnalysis`, `Completeness` |
| a graph is checked | `GraphAnalysis`'s constructor, which lists the phases | `GraphRequirements`, `GraphCycles`, `GraphTypes`, `TableReadChecks`, `GraphBlocking` |
| a decision of a graph is evaluated | `DecisionGraph.evaluate` | `GraphNodes`, `GraphValues` |
| a DMN file is read | `DmnReader` | `ModelReader`, `TableReader`, `RuleReader`, `ExpressionReader`, `ItemDefinitions`, `DmnXml` |
| a model is written as DMN | `DmnWriter` | `TableWriter`, `ExpressionWriter`, `TypeWriter` |
| FEEL text is parsed and evaluated | `FeelExpression` | `FeelLexer`, `FeelParser`, `Node`, `BuiltInFunctions` |

### praecepta-compiler and the build plugins

```mermaid
flowchart LR
    tool["<b>Build tool adapter</b><br/>GenerateMojo, GeneratePraecepta,<br/>PraeceptaProcessor"]
    subgraph compiler["praecepta-compiler"]
        tc["<b>TableCompiler</b><br/>Loads, validates and<br/>compiles one table"]
        gc["<b>GraphCompiler</b><br/>Compiles a model whose<br/>decisions form a graph"]
        sg["<b>SourceGenerator,<br/>GraphSourceGenerator</b><br/>Write the Java sources"]
        tz["<b>TimeZones, JavaNames</b><br/>Choose Java types and names"]
        ct["<b>CompiledTable</b><br/>The result: DMN, sources, reports"]
    end
    tool -- "TableDefinition,<br/>CompilerOptions" --> tc
    tc --> gc
    tc --> sg
    gc --> sg
    sg --> tz
    tc --> ct
    gc --> ct
    ct -- "write(sources, resources)" --> tool
    classDef component fill:#1168bd,stroke:#0b4884,color:#fff
    classDef ext fill:#6b7280,stroke:#4b5563,color:#fff
    class tc,gc,sg,tz,ct component
    class tool ext
```

A build tool translates its own configuration into a `TableDefinition`, calls `TableCompiler.compile`,
and writes the result with `CompiledTable.write`. So every tool generates the same files. The generated
classes need only `praecepta-engine` at run time.

### praecepta-integration and the frameworks

```mermaid
flowchart LR
    fw["<b>Framework adapter</b><br/>DecisionsRegistrar (Spring),<br/>PraeceptaFactory (Micronaut),<br/>PraeceptaRecorder (Quarkus)"]
    subgraph integration["praecepta-integration"]
        dl["<b>DecisionsLoader</b><br/>Reads the settings, logs,<br/>words every failure"]
        tl["<b>TableLocations, TableSource</b><br/>Find DMN files by pattern"]
        ds["<b>Decisions, Decision</b><br/>Decisions by name, each with<br/>its engine and report"]
        gm["<b>GraphModel</b><br/>A file's decision graph,<br/>from and to maps of text"]
    end
    fw --> dl
    dl --> tl
    dl --> ds
    ds --> gm
    ds -- "one bean per decision" --> fw
    classDef component fill:#1168bd,stroke:#0b4884,color:#fff
    classDef ext fill:#6b7280,stroke:#4b5563,color:#fff
    class dl,tl,ds,gm component
    class fw ext
```

Each framework module is a thin adapter: it gives `DecisionsLoader` its resource lookup and its logger,
and registers the result as beans. So the three load the same files, fail on the same problems and word
them alike. They differ only in when they run:

| Framework | When | How it registers beans |
|---|---|---|
| Spring Boot | at startup | An `ImportBeanDefinitionRegistrar` loads the tables while the context registers bean definitions. `DecisionsAotProcessor` writes the same definitions as code for Spring AOT. |
| Micronaut | at startup | A `@Context` factory creates `Decisions` first and registers one singleton per decision. |
| Quarkus | at build time | `PraeceptaProcessor` validates the tables and writes them into the application. `PraeceptaRecorder` rebuilds the engines from them during static initialisation. |

## Level 4: code
The types an application or a contributor meets first.

```mermaid
classDiagram
    direction LR
    class PraeceptaEngine~I, O~ {
        +builder() Builder
        +evaluate(I input) List~O~
        +validationReport() ValidationReport
    }
    class BitsetIndex {
        match(input, reader) long[]
    }
    class ColumnIndex {
        maskFor(value) long[]
    }
    class TableAnalysis
    class ValidationReport {
        +issues() List~Issue~
    }
    class DecisionGraph {
        +read(InputStream) DecisionGraph
        +evaluate(decision, inputData) Object
        +validationReport() ValidationReport
    }
    class DecisionModel {
        inputData
        decisions
        knowledgeModels
        decisionServices
    }
    class DecisionLogic {
        <<sealed interface>>
    }
    class DecisionTable
    class FeelExpression {
        +parse(text, names) FeelExpression
        +evaluate(values) Object
    }
    class Node {
        <<sealed interface>>
        evaluate(Env) Object
        type(TypeEnv) FeelType
    }
    class DmnReader {
        +read(InputStream) DecisionTable
        +readModel(InputStream) DecisionModel
    }
    PraeceptaEngine --> BitsetIndex : matches with
    BitsetIndex --> "1..*" ColumnIndex : one per column
    PraeceptaEngine --> TableAnalysis : analysed by
    TableAnalysis --> ValidationReport : produces
    DecisionGraph --> DecisionModel : built from
    DecisionGraph --> "0..*" PraeceptaEngine : one per decision table
    DecisionGraph --> "0..*" FeelExpression : one per expression
    DecisionModel --> "0..*" DecisionLogic : each decision has
    DecisionLogic --> DecisionTable : its Table kind holds
    FeelExpression --> Node : tree of
    DmnReader --> DecisionModel : reads
    DmnReader --> DecisionTable : reads
```

`DecisionLogic` is a sealed interface: a decision table, a literal expression, one of the boxed
expressions (context, invocation, function definition, relation, list, conditional, iteration, filter),
or no logic. `Node` is a sealed interface too, with one record per FEEL construct. The
[API documentation](https://bruno.pacheco.lu/praecepta/api/engine/) has every public type.

## Dynamic views

### Building and evaluating a decision table

```mermaid
sequenceDiagram
    participant App as Application
    participant B as PraeceptaEngine.Builder
    participant CP as CellParser
    participant BI as BitsetIndex
    participant TA as TableAnalysis
    participant E as PraeceptaEngine
    App->>B: rules, bindings, hit policy
    App->>B: build()
    B->>CP: parse each cell by its column's type
    CP-->>B: unary tests
    B->>BI: build one ColumnIndex per column
    B->>TA: analyse the table
    TA-->>B: ValidationReport
    B-->>App: engine (immutable)
    App->>E: evaluate(input)
    E->>BI: read each column's value, find its mask
    BI-->>E: AND of the masks: the matching rules
    E->>E: apply FEEL cells to the matches, if any
    E->>E: apply the hit policy and the aggregation
    E-->>App: outputs
```

### Evaluating a decision of a graph

```mermaid
sequenceDiagram
    participant App as Application
    participant R as DmnReader
    participant G as DecisionGraph
    participant A as GraphAnalysis
    participant N as GraphNodes
    App->>R: readModel(dmn)
    R-->>App: DecisionModel
    App->>G: build
    G->>A: check requirements, find cycles, order, type
    A-->>G: slots, order, ValidationReport
    G->>N: one node per decision: a table engine or a FEEL expression
    App->>G: evaluate("Monthly Limit", inputData)
    G->>G: take the decision's plan (cached)
    G->>G: put the input data into slots, as FEEL values
    loop each required decision, in dependency order
        G->>N: evaluate into its slot
    end
    G-->>App: the decision's value
```

Every input data and decision has a *slot* in an array. A decision's *plan* lists the slots it needs
and the decisions to evaluate, itself last. No walk over the graph recurses, so a chain of thousands of
decisions evaluates on any thread's stack.

### Compiling a table in a build

```mermaid
sequenceDiagram
    participant M as Maven or Gradle plugin
    participant TC as TableCompiler
    participant L as Loader or DmnReader
    participant E as PraeceptaEngine
    participant SG as SourceGenerator
    M->>TC: compile(TableDefinition, CompilerOptions)
    TC->>L: load the table
    TC->>E: build, bound as the generated code will bind it
    E-->>TC: ValidationReport
    alt issues at failOn
        TC-->>M: IllegalArgumentException: the build fails
    else
        TC->>SG: generate the records and the class
        TC-->>M: CompiledTable
        M->>M: write the DMN resource and the sources
    end
```

### Starting an application with a framework integration

```mermaid
sequenceDiagram
    participant F as Framework adapter
    participant DL as DecisionsLoader
    participant TL as TableLocations
    participant D as Decisions
    F->>DL: load(finder, log), with locations and fail-on
    DL->>TL: find the DMN files
    DL->>D: Decisions.load(sources, failOn)
    D->>D: read each file: a graph, or tables alone
    alt a problem at fail-on
        D-->>F: DecisionsException: every problem at once
    else
        D-->>F: Decisions
        F->>F: register Decisions and one engine bean per table
    end
```

## The bitset index
The index is what makes Praecepta fast and small. The idea:

1. **Every rule owns one bit.**
2. **Each column splits its values into classes** that no rule can tell apart. A string column has one
   class per value a rule mentions, one for any other value and one for a missing value. A number, date
   or time column is cut at the boundaries the rules mention, into points and intervals.
3. **Each class carries a mask**: the rules that accept it.
4. **To evaluate**, the engine finds the class of each column's value (a hash lookup or a binary search)
   and ANDs the masks, 64 rules per machine word. The hit policy then reads the result: FIRST takes the
   lowest set bit.

Memory grows with columns × classes × rules, never with the product of the values. A mask that only a
few rules share, such as one product code's, keeps only its non-zero words (`Masks`).

The analysis searches the same classes, so it is exact: it reports an overlap or a gap only when one
exists.

Three cases need more than one mask per value:
- **List columns** (`ListColumn`) OR the masks of the list's elements.
- **Times with a zone** have two coordinates, an instant and a local time, because FEEL compares them
  both ways (DMN 1.5, Table 54).
- **FEEL cells beyond literals, comparisons and intervals** (`> Limit * 2`) are invisible to the index,
  which treats them as "any value". The engine applies them afterwards, to the rules the index matched
  (`DeferredCells`).

## DMN features and where they live
For what a feature means, follow the links to the specification and the TCK. The TCK test cases are in
`praecepta-tck/src/test/resources/tck`; [RESULTS.md](../praecepta-tck/RESULTS.md) scores each one.

| Feature | DMN 1.5 | TCK test cases (examples) | Code |
|---|---|---|---|
| Decision tables and hit policies | 8 | `0004-simpletable-U`, `0108-first-hitpolicy`, `0115-sum-collect-hitpolicy` | `PraeceptaEngine`, `BitsetIndex` |
| Unary tests in cells | 10.3.1.2 | `0017-tableTests`, `0072-feel-in`, `1130-feel-interval` | `CellParser`, `FeelUnaryTests` |
| FEEL expressions | 10.3 | `0032-conditionals`, `0033-for-loops`, `0057-feel-context`, `0068-feel-equality` | `FeelParser`, `Node` |
| Built-in functions | 10.3.4 | `0002-string-functions`, `0012-list-functions`, `1115-feel-date-function` | `BuiltInFunctions` and one class per family |
| Decision requirement graphs | 6 | `0034-drg-scopes`, `0004-lending` | `DecisionGraph`, `GraphAnalysis` |
| Boxed expressions | 10.2 | `0040-singlenestedcontext`, `1150-boxed-conditional`, `1151-boxed-filter` | `DecisionLogic`, `BoxedComposer` |
| Business knowledge models | 6.3 | `0009-invocation-arithmetic`, `0038-dt-on-bkm-explicit-params` | `GraphNodes` |
| Decision services | 6.3 | `0085-decision-services` | `DecisionGraph.evaluateService` |
| Imports | 6.3.3 | `0086-import`, `1160-multiple-imports` | `ModelReader`, `ImportedModels` |
| External functions | 10.3.2.13.3 | `0076-feel-external-java` | Rejected by design |

Where DMN 1.5 and the TCK leave a case open, [DMN-CONFORMANCE.md](DMN-CONFORMANCE.md) records what
Praecepta does.

## Design decisions

| Topic | Decision | Reason |
|---|---|---|
| Semantics | Strict DMN: UNIQUE fails on an overlap, ANY needs equal outputs. DMN 1.5 and the TCK are the only references. | A table must mean the same in Praecepta and in any other conformant engine. |
| Matching | A bitset index, not a trie and not generated code. | A trie expands wildcards: 1,000 rules on 10 columns took 1.2 GB. Generated code moves that growth into bytecode. The index takes 160 KB for the same table and is faster. |
| Generated code | The build plugins generate typed records around the engine, never per-rule code. | Same reason; and the generated classes then agree with the engine by construction. |
| Exchange format | DMN. Excel and CSV compile to DMN at build time. | DMN carries the hit policy, the types and the value lists. Apache POI stays out of applications. |
| Dependencies | The engine depends on the JDK only. | It is safe to add anywhere, and it builds as a native image without configuration. |
| Java baseline | Java 21. CI also builds on 25 and 27. | 21 is the LTS most users run. |
| External functions | Never evaluated. | Evaluating a DMN file must not reach the class path or the file system ([security assurance](SECURITY-ASSURANCE.md)). |
| Tables inside FEEL | A decision table inside a boxed expression or a business knowledge model keeps its own index and report. | The index and the analysis are what set Praecepta apart. |
| Gradle plugin build | Built by Maven against the Gradle 8.11.1 API. | One reactor builds and rebuilds every module the same way, which keeps releases reproducible. The functional tests run it on Gradle 8.5, the last 8.x and 9. |
| Licence | Apache-2.0. | |

Propose a new decision as an issue first ([GOVERNANCE.md](../GOVERNANCE.md)).

## Quality gates
What keeps the architecture honest:

- **A reference implementation.** Property-based tests check the engine against a naive matcher on
  random tables (`EngineMatchesReferencePropertiesTest`).
- **The DMN TCK** runs in every build. The build fails when a check that passed stops passing
  ([praecepta-tck](../praecepta-tck/README.md)).
- **Golden files** pin the code the build plugins generate (`GeneratedSourcesGoldenTest`).
- **Fuzzing** of everything that reads untrusted text: `CellParser`, `FeelExpression`, `DmnReader`.
- **Mutation testing** of the engine with PIT, on demand ([CONTRIBUTING.md](../CONTRIBUTING.md#mutation-testing)).
- **A layering test** (`LayeringTest`, with ArchUnit) fails when `feel`, `beans` or `dmn` starts to depend on the
  root package.
- **Native images**: CI builds and runs a native application of each kind.
- **Benchmarks** against Camunda and Drools ([praecepta-benchmarks](../praecepta-benchmarks/README.md)).
