<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Framework and build-tool integrations

The design shared by the integrations of 0.5: the Gradle plugin
([#18](https://github.com/brunopacheco1/praecepta/issues/18)), GraalVM native images
([#19](https://github.com/brunopacheco1/praecepta/issues/19)), the Spring Boot starter
([#20](https://github.com/brunopacheco1/praecepta/issues/20)), the Quarkus extension
([#76](https://github.com/brunopacheco1/praecepta/issues/76)) and the Micronaut module
([#77](https://github.com/brunopacheco1/praecepta/issues/77)). It fixes module names, what each module
depends on, configuration keys, bean naming, how validation is reported and what a failing table does,
so that the integrations behave alike and can be built independently. Sections marked *planned*
describe modules that do not exist yet; their authors update them as they land.

## Principles
1. **The engine stays dependency-free.** Every framework or build tool lives in its own module; nothing
   is added to `praecepta-engine` but what any Java application could use (such as
   `DmnReader.readAll`).
2. **Two shared cores, no framework in either.** Build tools share `praecepta-compiler` (tables to
   DMN resources and typed records, as the Maven plugin has always done); frameworks share
   `praecepta-integration` (DMN files to named engines with their reports, at startup).
3. **DMN at runtime, spreadsheets at build time.** Excel and CSV need Apache POI and a declaration of
   their hit policy and columns; DMN carries both. Runtime integrations read DMN only and never depend on
   `praecepta-loader`. Excel and CSV tables reach them through a build plugin (Maven or Gradle), which
   converts them to DMN resources that a location pattern then finds, or through the Quarkus build step.
4. **Strict and early.** A table that cannot be read, cannot be built, or has validation issues at the
   configured severity stops the build (build tools, Quarkus) or the application's startup (Spring,
   Micronaut), with every problem listed at once. Semantics are those of the engine; no integration
   adds its own.
5. **One meaning per setting.** The same keys, defaults and values in every framework (below).

## Modules

| Module (artifactId) | Directory | Depends on | Status |
|---|---|---|---|
| `praecepta-engine` | `praecepta-engine` | the JDK | released |
| `praecepta-loader` | `praecepta-loader` | engine, Apache POI | released |
| `praecepta-compiler` | `praecepta-compiler` | engine, loader | 0.5 (moved out of the Maven plugin) |
| `praecepta-integration` | `praecepta-integration` | engine | 0.5 |
| `praecepta-maven-plugin` | `praecepta-maven-plugin` | compiler; Maven API (`provided`) | released |
| `praecepta-gradle-plugin` | `praecepta-gradle-plugin` | compiler; Gradle API (`provided`) | planned (#18) |
| `io.github.brunopacheco1.praecepta.gradle.plugin` (groupId `io.github.brunopacheco1.praecepta`) | `praecepta-gradle-plugin-marker` | gradle plugin (pom only) | planned (#18) |
| `praecepta-spring-boot-starter` | `praecepta-spring-boot-starter` | integration; Spring Boot (`provided`) | 0.5 (#20) |
| `praecepta-micronaut` | `praecepta-micronaut` | integration; Micronaut (`provided`) | planned (#77) |
| `praecepta-quarkus` | `praecepta-quarkus` | integration, `quarkus-arc` | planned (#76) |
| `praecepta-quarkus-deployment` | `praecepta-quarkus-deployment` | runtime, `quarkus-arc-deployment`, and compiler if it takes spreadsheets | planned (#76) |

All share the groupId `io.github.brunopacheco1` (except the Gradle plugin marker, whose coordinates
Gradle dictates), the version of the reactor, and the parent POM. Every module is a directory directly
under the repository root, with the reactor's parent POM as its parent: no nested modules, so the release
command, Reproducible Central's version rewrite (`pom.xml */pom.xml`) and the website's Javadoc copy
cover new modules as they are. Java packages are
`io.github.brunopacheco1.praecepta.<module>` (`compiler`, `integration`, `gradle`, `spring`,
`micronaut`, `quarkus`, `quarkus.deployment`); no package is split across modules, and each jar has an
`Automatic-Module-Name` equal to its package. GraalVM support (#19) adds no published module (see
[Native images](#native-images-19)).

## praecepta-compiler: the build-time core
`praecepta-compiler` is what the Maven plugin's `generate` goal always did, without Maven:
`TableCompiler.compile(TableDefinition, CompilerOptions)` loads one table (Excel, CSV or DMN),
validates it as the generated code will bind it, renders it as DMN and generates the
`<Name>Input` / `<Name>Output` records and the `<Name>` decision class. Build tools only translate
their configuration and write the result.

| Type | Role |
|---|---|
| `TableDefinition(source, name, decision, hitPolicy, aggregation, inputs, outputs)` | One table, as configured. `TableDefinition.dmn(path)` for a DMN file. A DMN source takes only `source`, `name` and `decision`. |
| `ColumnDefinition(name, type, allowedValues, priority, list)` | A column of an Excel or CSV table. `ColumnDefinition.of(name, type)`, `ColumnDefinition.listInput(name, type, allowedValues)`. |
| `CompilerOptions(packageName, failOn, fileHeader, generator, settingNames)` | `CompilerOptions.of(packageName, generator)` fails on ERROR, adds no header and names settings `'quoted'`; `withFailOn`, `withFileHeader`, `withSettingNames`. `generator` is the tool named in the generated files ("Generated by praecepta-gradle-plugin from discount.csv"). `settingNames` maps a setting's canonical name (`table`, `source`, `name`, `decision`, `hitPolicy`, `aggregation`, `inputs`, `outputs`, `list`, `fileHeader`) to the tool's spelling in messages: `CompilerOptions.XML_ELEMENTS` (`<hitPolicy>`) for Maven, `QUOTED` (`'hitPolicy'`) by default, kebab-case for Quarkus properties. |
| `CompiledTable(table, packageName, className, report, dmn, sources)` | The result. `write(sourceRoot, resourceRoot)` writes the DMN at `resourcePath()` (`com/acme/Discount.dmn`) and each source into the package's directory, and returns the files written. |

Rules for every build tool:
- `TableCompiler.compile` throws `IllegalArgumentException` with a readable message for any configuration
  or table mistake, and for validation issues at `failOn`; the tool turns it into its build failure,
  unchanged.
- The tool logs every issue of `report()` as a warning, prefixed with the class name, as the Maven plugin
  does (`Fees: WARNING rule 3 can never be selected`).
- The tool rejects two tables that generate the same class: `Two tables generate the class <X>; set
  <name> on one` (with the tool's spelling of `name`).
- Relative sources are resolved against the project directory. Generated sources and resources go to the
  tool's generated-sources and generated-resources directories, registered with the build so they are
  compiled and packaged. Files are written only through `CompiledTable.write`, so anything the compiler
  later adds (such as native-image metadata, #19) reaches every tool.
- Same tables, same options: same bytes. The compiler's golden files
  (`praecepta-compiler/src/test/resources/golden`) pin the Maven plugin's output; the Gradle plugin's
  tests compare against them with `generator` set to `praecepta-maven-plugin`, or against their own
  golden files that differ only in that name.
- Configuration names follow the Maven plugin's: `packageName`, `failOn` (`ERROR`, `WARNING`, `NONE`),
  `fileHeader`, `skip`, and per table `source`, `name`, `decision`, `hitPolicy`, `aggregation`,
  `inputs`/`outputs` with `name`, `type`, `allowedValues`, `priority`, `list`.

## praecepta-integration: the runtime core
`praecepta-integration` (engine only, no framework) turns DMN files into engines the way every framework
integration does:

| Type | Role |
|---|---|
| `TableSource(location, opener)` | A DMN file: where it is (for messages and reports) and how to open it. `TableSource.of(Path)`, `of(URL)`, `of(location, bytes)`. Frameworks turn their own resources into these. |
| `TableLocations` | Finds DMN files by pattern on the class path (directories and jars, and a native image's `resource:` file system) or the file system: `find(patterns, classLoader)`, `split("a,b")`, `Location.parse(pattern).matches(path)`. `TableLocations.DEFAULT` is the default pattern. |
| `Decision(name, location, table, engine, report)` | One decision with its map-based engine; `Decision.of(location, table)` builds it; `describe()` gives it as plain maps for an endpoint. |
| `Decisions` | The application's decisions by name: `Decisions.load(sources, failOn)`, `get(name)`, `find(name)`, `engine(name)`, `evaluate(name, input)`, `names()`, `all()`, `describe()`, `Decisions.failOn("ERROR")`. Immutable and thread-safe. |
| `DecisionsException` | Everything that went wrong while loading, as `problems()` (`<location>: <what>`), all at once. |

### From a file to engines
- Every decision table of every DMN file found becomes one `Decision`, read with `DmnReader.readAll`.
  A file with no decision table, or one the engine cannot read (including decisions that need other
  decisions, #14), is an error; so are two decisions with the same name, in one file or two.
- **Inputs are bound as a map.** An engine has the type
  `PraeceptaEngine<Map<String, String>, Map<String, String>>`: the input maps column names to values as
  text (`"42"`, `"2025-01-31"`, `"08:30Z"`, `["PT", "BR"]` for a list column), read with the column types
  the DMN declares, exactly as `PraeceptaEngine.Builder.inputs(...)` reads them; a missing or blank value
  is a missing input. Each output map holds one matching rule's outputs (a missing computed output maps to
  null), in the hit policy's order. Under a COLLECT aggregation (one NUMBER output, as the build plugins
  require), the one map holds the aggregate: SUM adds with FEEL's 34 digits, MIN and MAX return the
  rule's output as written, missing values are skipped, COUNT is the number of matches. When no rule
  matches, a table whose outputs declare default values (`defaultOutputEntry`) gives one map of them, null
  for an output without one, whatever its hit policy or aggregation (DMN 1.5, 10.3.2.10).
- **Typed records stay a build-time feature.** The classes the Maven and Gradle plugins generate are
  static (`Discount.evaluate(...)`, `Discount.engine()`) and need no container; an application that wants
  one injected declares it as a bean itself. Integrations do not scan for generated classes. The DMN
  resources those plugins write are ordinary DMN files, so a location pattern that covers their package
  (`classpath*:com/acme/pricing/*.dmn`) also gives them map-based engines.
- **Validation** runs when an engine is built; its report is that of the map-based engine (times and
  date-times may have a time zone or not, so gaps are looked for among both kinds). Issues at
  `fail-on` or above fail `Decisions.load`; the others are logged as warnings by the framework, one line
  per issue, prefixed with the decision's name.

## Configuration
The same keys everywhere: Spring Boot and Micronaut read them under `praecepta`, Quarkus under
`quarkus.praecepta` (its convention for extensions), where both are fixed at build time.

| Key | Default | Meaning |
|---|---|---|
| `praecepta.enabled` | `true` | Spring Boot and Micronaut only: `false` turns the integration off (no beans, no endpoint). Quarkus has none: remove the extension. |
| `praecepta.locations` | `classpath*:rules/**/*.dmn` (`TableLocations.DEFAULT`) | The DMN files to load: a comma-separated list (or a YAML list) of patterns. `classpath:` and `classpath*:` both search every class path root (Spring Boot rewrites `classpath:` to `classpath*:` before using its own resolver), `file:` the file system; `*` matches within a path segment, `**` across segments. |
| `praecepta.fail-on` | `ERROR` | The validation severity that stops startup (Spring Boot, Micronaut) or the build (Quarkus): `ERROR`, `WARNING` or `NONE`, in any case; parse it with `Decisions.failOn`, which rejects anything else. Unreadable or invalid tables fail whatever it says. |

A pattern that matches nothing is an error when `praecepta.locations` was set explicitly (the message
names the pattern); with the default, finding nothing gives an empty `Decisions` and an INFO log line.
Integrations add no other key without updating this table (for example the Quarkus extension's
build-time declarations of Excel and CSV tables, if it takes them, under `quarkus.praecepta.tables."<name>"`,
mirroring `TableDefinition` in kebab-case).

## Beans and names
- **A decision's name** is the DMN decision's `name` attribute (its `id` when it has none), exactly as
  written: `Discount`, `Credit Score`. A table compiled by a build plugin keeps its table name (the CSV
  file name without extension, or the DMN decision's name), not its generated class name.
- **Every framework registers one `Decisions` bean** (Spring bean name `praeceptaDecisions`), created at
  startup, never lazily: Micronaut makes it `@Context`, Spring a non-lazy singleton, Quarkus a
  `@Startup`-initialised `@ApplicationScoped`/`@Singleton` bean. This is the portable way in:
  `decisions.evaluate("Discount", Map.of("country", "PT"))`.
- **Each decision is also an engine bean** of type
  `PraeceptaEngine<Map<String, String>, Map<String, String>>`, qualified by the decision's name:
  - Spring Boot: injected with `@Qualifier("Discount")`; the bean is named `praecepta.Discount` (so a
    table cannot clash with another bean) and carries the qualifier `Discount`, with its generic type set
    (`ResolvableType`).
  - Micronaut: `@Named("Discount")` (`Qualifiers.byName`), registered before the application's beans are
    created; if Micronaut cannot do that for files found at runtime, the module offers only `Decisions`
    and says so in its README section.
  - Quarkus: `@Identifier("Discount")` on synthetic beans produced at build time (`@Named` works too).
- Nothing is registered for typed records or generated classes (see above).

## Reports and failures

| | Spring Boot | Micronaut | Quarkus |
|---|---|---|---|
| Where validation runs | startup | startup | build (`quarkus:build`, dev mode, tests) |
| A failing table | context fails to start with the `DecisionsException` (a `FailureAnalyzer` may format it) | context fails to start | the build fails with the `DecisionsException` message |
| Warnings | logged at startup | logged at startup | logged by the build step |
| Report endpoint | Actuator endpoint `praecepta` (`/actuator/praecepta`, and `/actuator/praecepta/{name}` for one decision), when Actuator is present; exposed under Actuator's usual rules | management endpoint `praecepta` (`/praecepta`, `/praecepta/{name}`), when `micronaut-management` is present | `Decisions` at runtime; a Dev UI card and a `/q/praecepta` route if they fit |

The endpoints return `Decisions.describe()` (`{"decisions": {"Discount": {...}}}`) and
`Decision.describe()` for one decision (404 for an unknown name): `name`, `location`, `hitPolicy`,
`aggregation`, `rules`, `inputs` and `outputs` (`name`, `type`, and `list` for inputs) and `issues`
(`severity`, `kind`, `rules`, `detail`, `example`, `message`). They only read; nothing evaluates through
them. In Quarkus the runtime builds engines from the DMN the build step validated (as resources
registered for native images, or bytes recorded at build time), with `fail-on` already enforced, so it
cannot fail differently at runtime.

## Per integration
### Gradle plugin (#18, planned)
- Plugin id `io.github.brunopacheco1.praecepta`, class `io.github.brunopacheco1.praecepta.gradle.PraeceptaPlugin`,
  extension `praecepta { packageName; failOn; fileHeader; tables { register("discount") { source; name;
  decision; hitPolicy; aggregation; inputs { ... }; outputs { ... } } } }` (the Maven plugin's names),
  task `generatePraecepta` (cacheable, inputs: the table files and settings; outputs:
  `build/generated/sources/praecepta/java/main` and `build/generated/resources/praecepta/main`, added to
  the `main` source set so `compileJava` and `processResources` depend on it).
- Built by the Maven reactor like every other module: compiled against the Gradle API from Maven Central
  (`dev.gradleplugins:gradle-api`, `provided`), with `META-INF/gradle-plugins/io.github.brunopacheco1.praecepta.properties`.
  Functional tests (Gradle TestKit, which downloads a Gradle distribution) run in a workflow of their own
  (`gradle-versions.yml`, like `maven-versions.yml`: pull requests touching the Gradle plugin, compiler,
  loader or engine sources, main, weekly), not in the default `mvn verify`.
- Published to Maven Central only, with the plugin marker artifact so that `plugins { id(...) version
  "..." }` resolves from `mavenCentral()` in `pluginManagement`; the Gradle Plugin Portal is not used.

### Native images (#19)
- **No new module.** The test project is `praecepta-maven-plugin/src/it/native-app`, an invoker project:
  an application that uses the engine (`DmnReader`, `DmnWriter`, map-based engines), the loader (CSV), the
  integration core (`TableLocations.DEFAULT`, `Decisions`) and two classes the plugin generates (from a CSV
  and a DMN table), and checks every answer. The default `mvn verify` runs those checks on the JVM in a few
  seconds; the plugin's `native` profile (`-Pnative`) also builds the project with GraalVM's
  `native-maven-plugin` and runs the executable.
- **Workflow `native.yml`** runs `mvn -pl praecepta-maven-plugin -am verify -Pnative` with GraalVM for JDK
  21 and 25, set up by `graalvm/setup-graalvm` (the task that built it asked for that action rather than the
  container image this section first named; locally the same build runs in
  `ghcr.io/graalvm/native-image-community`). It runs on pull requests touching the engine, loader,
  compiler, integration or Maven plugin sources, the test project, a POM or the workflow; on main; and
  weekly; superseded runs are cancelled. On GraalVM for JDK 24 and later the test project builds with
  `--exact-reachability-metadata`, so a resource that no metadata registers fails the run instead of
  reading as absent.
- **Metadata** lives in the jars, as `META-INF/native-image/io.github.brunopacheco1/<artifactId>/resource-config.json`
  (the format GraalVM for JDK 21 reads too), and reaches every native build that has the jar on its class
  path, whatever runs `native-image`:
  - `praecepta-engine`: the JDK XML parser's message bundle (without it a malformed DMN file fails with a
    `MissingResourceException`), and JAXP's `DocumentBuilderFactory` service file (needed under
    `--exact-reachability-metadata` only).
  - `praecepta-integration`: `rules` and `rules/**/*.dmn`, the files of `TableLocations.DEFAULT`. A framework
    module whose `praecepta.locations` names other files registers those through its own native support
    (Spring's `RuntimeHints`, Micronaut's resource configuration, Quarkus's
    `NativeImageResourceBuildItem`); with the default it needs nothing.
  - `praecepta-compiler` writes none, and the golden files are unchanged: the generated classes read their
    DMN through `getResourceAsStream` with a constant name, which `native-image` registers by itself
    (verified on GraalVM for JDK 21 and, under `--exact-reachability-metadata`, 25). `CompiledTable.write`
    stays the place to add some if a GraalVM release stops doing that.
- **Excel is not supported natively.** `ExcelLoader` fails on the first workbook (Apache POI needs the
  `CP1252` charset, XMLBeans' message bundles and its schema type system), and GraalVM's shared metadata
  repository has no entry for Apache POI. Spreadsheets reach a native executable as DMN, compiled at build
  time, as principle 3 already has it.
- **No reflection:** the engine needs none. Framework modules keep to that.

### Spring Boot starter (#20)
- One module, `praecepta-spring-boot-starter` (auto-configuration and starter together), package
  `io.github.brunopacheco1.praecepta.spring`: `PraeceptaAutoConfiguration` (listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  `@ConditionalOnProperty(praecepta.enabled, matchIfMissing)`), `PraeceptaProperties`
  (`@ConfigurationProperties("praecepta")`, with configuration metadata), and the Actuator endpoint in a
  nested configuration conditional on Actuator. Resources are found with Spring's
  `ResourcePatternResolver`, so nested jars of executable jars work.
- Built against Spring Boot 4 (the BOM imported in the module, version `spring-boot.version` in the
  parent), every Spring dependency `provided`.
- **Tables are loaded while bean definitions are registered** (an `ImportBeanDefinitionRegistrar`), since
  each decision becomes a bean of its own: the registrar binds `praecepta.*` with Spring's `Binder`, finds
  the files, runs `Decisions.load`, and registers `praeceptaDecisions` and the `praecepta.<name>` engines
  (qualifier and generic type set) as non-lazy beans that return what it loaded. A failure therefore stops
  the context's refresh before any application bean is created; `PraeceptaFailureAnalyzer` lists the
  problems in Spring Boot's "APPLICATION FAILED TO START" report.
- The endpoint's `@Selector` parameter is found by name, so the module compiles with `-parameters`.

### Micronaut module (#77, planned)
- `praecepta-micronaut`, package `io.github.brunopacheco1.praecepta.micronaut`: a `@Factory`,
  `@ConfigurationProperties("praecepta")` configuration, and a management endpoint when
  `micronaut-management` is present. Files are found with `TableLocations` and the application's class
  loader. Micronaut's annotation processors must not make the compiler warn (the build uses `-Werror`).

### Quarkus extension (#76, planned)
- `praecepta-quarkus` (runtime) and `praecepta-quarkus-deployment`, built with
  `quarkus-extension-maven-plugin`, side by side under the repository root (the Quarkus BOM imported in
  each module's own `<dependencyManagement>`). The deployment module finds DMN files in the application's archives with
  `Location.matches`, validates them with `Decisions.load` at build time, registers them for native
  images, and produces the `Decisions` and engine beans. Spreadsheet tables and dev-mode reload are
  optional; if spreadsheets are taken, they go through `praecepta-compiler` in the deployment module
  only, so Apache POI never reaches the application.

## Registering a module
New modules need these and nothing else:
1. **Reactor:** a `<module>` line in the parent POM, after `praecepta-maven-plugin` and before
   `praecepta-benchmarks` (several integrations land at once, so expect, and simply resolve, conflicts in
   that list). The module is a directory under the repository root and its parent is the reactor's
   parent POM (`../pom.xml`).
2. **Versions:** every dependency and plugin version is a property in the parent POM, in a block
   commented with the issue (`<!-- Spring Boot starter (#20) -->`), and only exact, current, stable
   releases from Maven Central. A framework BOM is imported in the module's own `<dependencyManagement>`
   (importing it in the parent would change the versions every other module resolves), with its version
   property in the parent. The enforcer's `dependencyConvergence` applies to every module.
3. **Publishing:** nothing to list. The release builds every module but the benchmarks and the TCK runner
   (`-pl '!praecepta-benchmarks,!praecepta-tck'`) and publishes every one that does not opt out; the
   GitHub release, provenance and third-party notices find the published jars themselves
   (`.github/scripts/published_modules.py`). A module that must never be published (test projects,
   samples) sets `skipPublishing`, `maven.deploy.skip`, `maven.install.skip` and `cyclonedx.skip` to
   `true`, as `praecepta-tck` does; it is still built and tested in the release.
4. **SBOM and licences:** every published module writes `target/bom.json`, and CI checks all of them
   against the allowlist in `.github/scripts/check_sbom_licenses.py` and scans them for known
   vulnerabilities, without being told about new modules. Framework dependencies the application always
   has (Spring Boot, Micronaut, the Gradle and Maven APIs) are `provided` (or optional), so they are not
   part of what Praecepta ships. Do not widen the allowlist in an integration's change: report a
   dependency it rejects to the maintainer.
5. **API documentation:** the website publishes every published module's Javadoc at
   `api/<directory without "praecepta-", "/" as "-">` (`api/spring-boot-starter`, `api/quarkus`)
   with no change to `pages.yml`; linking it from the website is part of release preparation.
6. **CI load:** the default `mvn verify` (every pull request, on Java 21, 25 and 27) runs a module's fast
   tests only, seconds not minutes. Slow checks (other Gradle or framework versions, native images,
   Quarkus production-mode integration tests) get their own workflow that runs on pull requests only when
   the module's paths (or what it is built from) change, always on main and weekly, and cancels
   superseded runs, as `maven-versions.yml` does. The required checks (Build & test (Java 25),
   SonarCloud, REUSE compliance, CodeQL, Dependency review) need no change.
7. **Files:** SPDX headers on every new file (`mvn license:format` for Java and POMs; data files are
   covered by `REUSE.toml` patterns such as `**/*.dmn`), `reuse lint` clean; Checkstyle and `-Werror`
   as for every module.
8. **Documentation:** a section in the README after the Maven plugin's, a row in the modules table of
   `docs/ARCHITECTURE.md`, a line under `## [Unreleased]` in `CHANGELOG.md`, Javadoc on public types, and
   the module's status in this document.

## Reproducible Central
The release command changed in 0.5 from a list of modules (`-pl praecepta-engine,praecepta-loader,praecepta-maven-plugin -am`)
to everything but the benchmarks and the TCK runner (`-pl '!praecepta-benchmarks,!praecepta-tck'`), so
Reproducible Central's `.buildspec` needs that change once, for 0.5.0; after that, new modules need no
change there either. See [RELEASING.md](RELEASING.md).
