<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Integrations

Praecepta fits the build and the framework an application already uses: a Maven and a Gradle plugin,
a Spring Boot starter, a Micronaut module, a Quarkus extension, and GraalVM native images. They are
built to behave alike, so moving a table from one to another changes nothing about what it means or
how a bad one fails. This document is what they have in common and where they differ; how to set up
each one is in the README:
[Maven](../README.md#praecepta-maven-plugin), [Gradle](../README.md#praecepta-gradle-plugin),
[Spring Boot](../README.md#praecepta-spring-boot-starter), [Micronaut](../README.md#praecepta-micronaut),
[Quarkus](../README.md#praecepta-quarkus-extension) and [native images](../README.md#native-images).
Their internals are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Modules

| Module (artifactId) | For | Depends on | Tables are checked |
|---|---|---|---|
| `praecepta-maven-plugin` | Maven 3.9+ | `praecepta-compiler`; the Maven API (`provided`) | when the project builds |
| Gradle plugin `io.github.brunopacheco1.praecepta` (`praecepta-gradle-plugin`) | Gradle 8.5+ | `praecepta-compiler`; the Gradle API (`provided`) | when the project builds |
| `praecepta-spring-boot-starter` | Spring Boot 4 | `praecepta-integration`; Spring Boot (`provided`) | when the application starts |
| `praecepta-micronaut` | Micronaut 4 | `praecepta-integration`; Micronaut (`provided`) | when the application starts |
| `praecepta-quarkus` (and `praecepta-quarkus-deployment`, which Quarkus adds to its build) | Quarkus 3.40+ | `praecepta-integration`; `quarkus-arc` | when Quarkus builds the application |
| `praecepta-compiler` | writing a build-tool integration | `praecepta-engine`, `praecepta-loader` | when you call it |
| `praecepta-integration` | using the runtime core without a framework | `praecepta-engine` | when you call it |

Every module needs Java 21 or later and is published to Maven Central with the groupId
`io.github.brunopacheco1` and the same version (the Gradle plugin's marker, which Gradle finds by the
plugin id, has the groupId `io.github.brunopacheco1.praecepta`). From 0.6.0 the Gradle plugin is on the
Gradle Plugin Portal too, with the same files. Micronaut 5 needs Java 25, so the module
targets Micronaut 4. GraalVM native images need no module: the jars carry their own metadata.

## What they share
1. **The engine stays dependency-free.** Every build tool and framework has a module of its own;
   `praecepta-engine` gains nothing a plain Java application could not use.
2. **Two cores, no framework in either.** The build tools share `praecepta-compiler`: tables to DMN
   resources and typed records, as the Maven plugin has always done, so the Maven and Gradle plugins
   generate the same classes byte for byte (but for the tool named in their comments). The frameworks share
   `praecepta-integration`: DMN files to named engines with their validation reports.
3. **DMN at runtime, spreadsheets at build time.** Excel and CSV need Apache POI and a declaration of
   their hit policy and columns; DMN carries both. The framework integrations read DMN only, and never
   bring Apache POI into an application. Excel and CSV tables reach them through a build plugin, which
   writes them as DMN resources a location pattern then finds, or through the Quarkus extension, which
   compiles the tables declared in `application.properties` while it builds the application.
4. **Strict and early.** A table that cannot be read or built, or that has validation issues at the
   configured severity, stops the build (Maven, Gradle, Quarkus) or the application's startup (Spring Boot,
   Micronaut), with every problem listed at once. The engine decides what a table means; no integration
   adds a meaning of its own.
5. **The same settings everywhere,** with the same defaults and values (below).

## Inputs and outputs
The build plugins generate typed records (`DiscountInput`, `DiscountOutput`) and a static class
(`Discount.evaluate(...)`); see the [Maven plugin](../README.md#praecepta-maven-plugin). The framework
integrations find their tables at runtime, so their engines take maps instead:
- **Every decision table of every DMN file found becomes one decision**, and one engine of type
  `PraeceptaEngine<Map<String, String>, Map<String, String>>`. A file with no decision table, or with one the
  engine cannot read (including decisions that need other decisions,
  [#14](https://github.com/brunopacheco1/praecepta/issues/14)), is an error; so are two decisions with the
  same name, in one file or two.
- **Inputs** map column names to values as text, read with the column types the DMN declares, as
  `PraeceptaEngine.Builder.inputs(...)` reads them: `"42"`, `"2025-01-31"`, `"08:30Z"`, `["PT", "BR"]` for
  a list column (`["PT", null]` for one holding a missing element). A missing or blank value is a missing
  input.
- **Outputs** are one map per matching rule, in the hit policy's order (an output that is missing, such as
  an expression with a missing operand, maps to null). Under a COLLECT aggregation, the one map holds the
  aggregate, as DMN 1.5 defines it over a single output (`AggregationOperator.check`): SUM adds numbers with
  FEEL's 34 digits and keeps their scale, as the build plugins' generated classes do; MIN and MAX return the
  smallest or largest output as written, of any ordered type (numbers, strings by character, dates, times,
  durations); COUNT is the number of matches, of outputs of any type. When a matching rule's output is
  missing (a `null` entry, an expression with a missing operand), SUM, MIN and MAX are missing too, as FEEL's
  `sum`, `min` and `max` are null for a list that holds null (10.3.4.4): the one map maps the output to null
  (`[{fee=null}]`), as the build plugins' generated classes give an output whose component is null; COUNT
  counts that rule. When no rule matches, a table whose outputs declare
  default values (`defaultOutputEntry`, `null` included) gives one map of them, null for an output without
  one, and any other table no map, whatever its hit policy or aggregation (DMN 1.5, 10.3.2.10, step 2): a
  COUNT then gives no map rather than `0`, as the generated classes give an empty `Optional`.
- **Validation** is that of the map-based engine. Times and date-times read from text may have a time zone
  or not, so gaps are looked for among both kinds.
- **Generated classes are not beans.** They are static and need no container; declare one as a bean
  yourself if you want it injected. The DMN resources the build plugins write are ordinary DMN files, so a
  location pattern that covers their package (`classpath*:com/acme/pricing/*.dmn`) gives them map-based
  engines too.

## Configuration
Spring Boot and Micronaut read these keys under `praecepta`; Quarkus reads them under `quarkus.praecepta`,
its prefix for extensions, and fixes them when it builds the application.

| Key | Default | Meaning |
|---|---|---|
| `praecepta.enabled` | `true` | Spring Boot and Micronaut: `false` turns the integration off (no beans, no endpoint). Quarkus has no such key: remove the extension instead. |
| `praecepta.locations` | `classpath*:rules/**/*.dmn` | The DMN files to load: a comma-separated list (or a YAML list) of patterns. `classpath:` and `classpath*:` both search every class path root, directories and jars; `file:` searches the file system. `*` matches within a path segment, `**` across segments. A class path pattern with wildcards must start with a directory (`classpath*:rules/...`), since the roots themselves cannot be listed inside jars. |
| `praecepta.fail-on` | `ERROR` | The validation severity that stops startup (Spring Boot, Micronaut) or the build (Quarkus): `ERROR`, `WARNING` or `NONE`, in any case. Tables that cannot be read or built fail whatever it says. |

With the default location, finding no file gives no decisions and the INFO line
`No decision tables found at <patterns>`; otherwise `Loaded <n> decision(s) from <m> DMN file(s): <names>`.
A location you set yourself must find something. The three frameworks share this step
(`DecisionsLoader`), so they word their problems alike, and list them together with the tables' own
problems:
- `<pattern>: no DMN file matches this location`, for a location you set that finds nothing;
- `praecepta.locations is set but names no location` (Spring Boot and Micronaut; Quarkus reads an empty
  value as unset);
- `Location '<pattern>': expected classpath:, classpath*: or file:`, for a malformed pattern, and
  `Location '<pattern>': a class path pattern must start with a directory without wildcards, ...`;
- `<pattern>: cannot be searched (<why>)`;
- `praecepta.fail-on must be ERROR, WARNING or NONE, not <value>` (`quarkus.praecepta.fail-on` in
  Quarkus); the tables are then still loaded and checked at `ERROR`, so their problems are listed too.

The Quarkus extension has one more setting: Excel and CSV tables to compile while it builds the
application, under `quarkus.praecepta.tables."<key>"`, with the Maven plugin's settings in kebab-case
(`source`, `decision`, `hit-policy`, `aggregation`, and `inputs[n]`/`outputs[n]` with `name`, `type`,
`allowed-values`, `priority`, `list`). The key only groups a table's settings: the decision is named after
the file, as a build plugin names it. A relative `source` is resolved against the project's directory.

The build plugins' settings are the Maven plugin's in every tool: `packageName`, `failOn` (`ERROR`,
`WARNING`, `NONE`), `fileHeader`, `skip`, and per table `source`, `name`, `decision`, `hitPolicy`,
`aggregation`, and the columns' `name`, `type`, `allowedValues`, `priority` and `list`. In Gradle, `name`
is the name a table is registered under, so the generated class is named by `className`.

## Names and beans
- **A decision's name** is the DMN decision's `name` attribute (its `id` when it has none), exactly as
  written: `Discount`, `Credit Score`. A table the Quarkus extension compiles from a spreadsheet is named
  after the file, without its extension.
- **Every framework registers one `Decisions` bean**, created at startup, never lazily (in Spring Boot,
  named `praeceptaDecisions`). It works the same everywhere:
  `decisions.evaluate("Discount", Map.of("country", "PT"))`, `decisions.names()`,
  `decisions.get("Discount").report()`.
- **Each decision is also an engine bean**, qualified by the decision's name:

  | | Inject with | Notes |
  |---|---|---|
  | Spring Boot | `@Qualifier("Discount")` | The bean is named `praecepta.Discount`, so a table cannot clash with another bean. `@Named("Discount")` works too when the application has `jakarta.inject-api`. |
  | Micronaut | `@Named("Discount")` | Registered before the application's own beans are created, so context-scoped beans can inject it too. |
  | Quarkus | `@Identifier("Discount")` or `@Named("Discount")` | A `@Singleton`: an engine is immutable, and `PraeceptaEngine`, a final class, cannot have the client proxy of a normal scope. |

  So `@Named("Discount")` injects an engine in all three.

## Reports and failures

| | Spring Boot | Micronaut | Quarkus |
|---|---|---|---|
| Validation runs | at startup | at startup | while Quarkus builds the application (`quarkus:build`, dev mode, tests) |
| A failing table | the application does not start; Spring Boot's failure report lists every problem | the application does not start, with every problem in one `DecisionsException` | the build fails, listing every problem |
| Other issues | logged as warnings at startup | logged as warnings at startup | logged as warnings by the build |
| Report endpoint | Actuator endpoint `praecepta` (`/actuator/praecepta`, `/actuator/praecepta/{name}`), exposed under Actuator's usual rules | management endpoint `praecepta` (`/praecepta`, `/praecepta/{name}`) with `micronaut-management`, sensitive by default | none yet; `decisions.describe()` gives the same at runtime |

Warnings are one line per issue, prefixed with the decision's name:
`Discount: WARNING rule 3 can never be selected`. Each issue is logged once, through the framework's logging:
the engines do not log their reports as well, and in Quarkus the application's startup does not repeat what
the build logged. The endpoints return `Decisions.describe()`,
`{"decisions": {"Discount": {...}}}`, or one decision's description (404 for an unknown name): `name`,
`location`, `hitPolicy`, `aggregation`, `rules` (the number of rules), `inputs` and `outputs` (`name`,
`type`, and `list` for inputs), and `issues` (`severity`, `kind`, `rules`, `detail`, `example`,
`message`). An issue's `kind` is one of `ValidationReport.Kind`: `OVERLAPPING_RULES`, `CONFLICTING_OUTPUTS`
and `UNREACHABLE_DEFAULT_OUTPUT` (a default output on a table that every input matches, which DMN 1.5,
10.3.2.10, forbids) are errors, so they fail the build or the startup at the default `fail-on`;
`UNREACHABLE_RULE` and `GAP` are warnings. They only read; nothing is evaluated through them. The descriptions show the rules'
conditions, so expose the endpoints as you would any other that shows configuration.

In Quarkus, the application builds its engines from the DMN the build validated, so it cannot fail
differently at runtime.

## Without a framework
`praecepta-integration` is what the framework integrations use, and works on its own:

```java
var sources = TableLocations.find(List.of("classpath*:rules/**/*.dmn"), getClass().getClassLoader());
var decisions = Decisions.load(sources, Severity.ERROR);       // throws DecisionsException
List<Map<String, String>> discount = decisions.evaluate("Discount", Map.of("country", "PT", "age", "42"));
```

| Type | Role |
|---|---|
| `TableLocations` | Finds DMN files by pattern on the class path (directories, jars, and a native image's resources) or the file system: `find(patterns, classLoader)`, `find(location, classLoader)`, `split("a,b")`, `Location.parse(pattern).matches(path)`. `TableLocations.DEFAULT` is the default pattern. |
| `DecisionsLoader` | What a framework does with its settings: `new DecisionsLoader("praecepta", locations, failOn).load(finder, log)` reads `locations` (null for the default) and `fail-on`, finds the files with the framework's lookup (`finder`), loads them, fails with every problem worded as above, and logs what it loaded and the issues below `fail-on`. |
| `TableSource` | A DMN file: where it is (for messages and reports) and how to open it. `TableSource.of(Path)`, `of(URL)`, `of(location, bytes)`. |
| `Decisions` | The decisions by name: `Decisions.load(sources, failOn)`, `get(name)`, `find(name)`, `engine(name)`, `evaluate(name, input)`, `names()`, `all()`, `describe()`; `Decisions.failOn("ERROR")` reads a severity as the settings above do. Immutable and thread-safe. Each engine logs its validation report, as `PraeceptaEngine` does by default; `Decisions.load(sources, failOn, false)` builds them without, for a caller that reports `Decision.report()` itself (as `DecisionsLoader` does). |
| `Decision` | One decision: its `name`, `location`, `table`, `engine` and validation `report`; `describe()` as plain maps. |
| `DecisionsException` | Everything that went wrong while loading, as `problems()` (`<location>: <what>`), all at once. |

## Writing a build-tool integration
`praecepta-compiler` is what the Maven plugin's `generate` goal does, without Maven: it loads a table
(Excel, CSV or DMN), validates it as the generated code will bind it, renders it as DMN, and generates
the `<Name>Input` and `<Name>Output` records and the `<Name>` class. A build tool translates its own
configuration and writes the result.

| Type | Role |
|---|---|
| `TableDefinition(source, name, decision, hitPolicy, aggregation, inputs, outputs)` | One table, as configured; `TableDefinition.dmn(path)` for a DMN file, which takes only `source`, `name` and `decision`. |
| `ColumnDefinition(name, type, allowedValues, priority, list)` | A column of an Excel or CSV table; `ColumnDefinition.of(name, type)`, `ColumnDefinition.listInput(name, type, allowedValues)`. |
| `CompilerOptions(packageName, failOn, fileHeader, generator, settingNames)` | `CompilerOptions.of(packageName, generator)` fails on ERROR and adds no header; `withFailOn`, `withFileHeader`, `withSettingNames`. `generator` is the tool named in the generated files ("Generated by praecepta-gradle-plugin from discount.csv"); `settingNames` spells the settings in messages as the tool does: `CompilerOptions.XML_ELEMENTS` (`<hitPolicy>`) for Maven, `QUOTED` (`'hitPolicy'`) by default. |
| `CompiledTable(table, packageName, className, report, dmn, sources)` | The result. `write(sourceRoot, resourceRoot)` writes the DMN at `resourcePath()` (`com/acme/Discount.dmn`) and each source in its package's directory, and returns the files written. |

What the Maven and Gradle plugins do, and another tool should too:
- `TableCompiler.compile` throws `IllegalArgumentException` with a readable message for any mistake in
  the configuration or the table, and for validation issues at `failOn`; the tool reports it as its build
  failure, unchanged.
- Every other issue of `report()` is logged as a warning, prefixed with the class name
  (`Fees: WARNING rule 3 can never be selected`). The compiler does not log the report itself (its engine is
  built with `logValidationReport(false)`), so each issue appears once.
- Two tables that generate the same class are rejected: `Two tables generate the class <X>; set <name> on
  one`, with the tool's spelling of `name`.
- Relative sources are resolved against the project directory, and the results go to the tool's
  generated-sources and generated-resources directories, registered with the build so that they are
  compiled and packaged. Files are written only through `CompiledTable.write`.

## Native images
The jars carry the reachability metadata they need, under
`META-INF/native-image/io.github.brunopacheco1/<artifactId>/resource-config.json`: the engine registers
the XML parser's messages, and `praecepta-integration` the files of the default location,
`rules/**/*.dmn`. So an application with its tables there needs no configuration for Praecepta. The
framework integrations use no reflection; register tables at other locations yourself, through the
framework's own native support (Spring's `RuntimeHints`, Micronaut's resource configuration) or a
`resource-config.json` of the application's. The Spring Boot starter works with Spring AOT, which processes
it while the application is built (the engine beans are then those of the decisions found at that time,
and the tables are read again at startup). The Quarkus extension registers the tables it validated
itself. CI builds and runs a native application that uses the
engine, the CSV loader, `praecepta-integration` and generated classes, with GraalVM for JDK 21 and 25; it
does not build a Spring Boot, Micronaut or Quarkus application natively yet. `ExcelLoader` does not work
in a native image: compile Excel tables at build time. Details are in the
[README](../README.md#native-images).
