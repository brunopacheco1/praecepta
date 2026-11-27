<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Integrations

How to use Praecepta from your build tool or framework. All integrations behave alike: a table means the
same everywhere, and an invalid table fails the same way.

| You use | Add | Tables are checked |
|---|---|---|
| Maven 3.9+ | [`praecepta-maven-plugin`](#maven-plugin) | when the project builds |
| Gradle 8.5+ | [plugin `io.github.brunopacheco1.praecepta`](#gradle-plugin) | when the project builds |
| Spring Boot 4 | [`praecepta-spring-boot4-starter`](#spring-boot) | when the application starts |
| Micronaut 4 | [`praecepta-micronaut4`](#micronaut) | when the application starts |
| Quarkus 3.40+ | [`praecepta-quarkus3`](#quarkus) | when Quarkus builds the application |
| Another framework, or none | [`praecepta-integration`](#without-a-framework) | when you call it |
| GraalVM | nothing | see [native images](#native-images) |

Every module needs Java 21 or later. All are on Maven Central under the groupId
`io.github.brunopacheco1`, with the same version. The Gradle plugin is on the Gradle Plugin Portal too.

Each framework module is named after the framework major it supports, and a later major gets a module of
its own. [GOVERNANCE.md](../GOVERNANCE.md#what-is-supported) has the support policy.

| Module | Framework | Java |
|---|---|---|
| `praecepta-spring-boot4-starter` | Spring Boot 4 | 21 or later |
| `praecepta-micronaut4` | Micronaut 4 | 21 or later |
| `praecepta-quarkus3` (and `praecepta-quarkus3-deployment`, which Quarkus adds to its build) | Quarkus 3, from 3.40 | 21 or later |

There are two ways to use tables, and you can mix them:
- **Build plugins** compile Excel, CSV and DMN tables into typed Java classes. Your application ships
  only `praecepta-engine`.
- **Framework integrations** load DMN files when the application starts and give you one engine per
  decision, which takes and returns maps of text.

## Build plugins

### Maven plugin
```xml
<plugin>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-maven-plugin</artifactId>
  <version>0.12.0</version>
  <executions><execution><goals><goal>generate</goal></goals></execution></executions>
  <configuration>
    <packageName>com.acme.pricing</packageName>
    <tables>
      <table>
        <source>src/main/rules/discount.xlsx</source>
        <hitPolicy>FIRST</hitPolicy>
        <inputs>
          <input><name>country</name><allowedValues>"PT","ES","FR"</allowedValues></input>
          <input><name>age</name><type>NUMBER</type></input>
        </inputs>
        <outputs>
          <output><name>discount</name><type>NUMBER</type></output>
        </outputs>
      </table>
      <table>
        <!-- DMN files carry their own hit policy, columns and value lists -->
        <source>src/main/rules/shipping.dmn</source>
      </table>
    </tables>
  </configuration>
</plugin>
```

For each table, the `generate` goal:
1. loads it from Excel, CSV or DMN;
2. validates it, and fails the build on issues at `<failOn>` (`ERROR` by default, `WARNING` or `NONE`);
3. writes it as a DMN resource next to the generated classes;
4. generates a `<Name>Input` record, a `<Name>Output` record and a `<Name>` class that evaluates the
   table.

```java
Optional<DiscountOutput> discount = Discount.evaluate(new DiscountInput(DiscountInput.Country.PT, new BigDecimal(30)));
```

- A single-hit table or an aggregation returns an `Optional`. A multi-hit table returns a `List`.
- `Discount.validationReport()` gives the build-time findings, and `Discount.engine()` the engine.
- Generated code carries no licence header. Set `<fileHeader>` to add your own.

Settings: `packageName`, `failOn`, `fileHeader`, `skip`; per table `source`, `name`, `decision`,
`hitPolicy`, `aggregation`; per column `name`, `type`, `allowedValues`, `priority`, `list`.

Generated Java types:

| Column | Java type |
|---|---|
| STRING | `String`, or an enum when the column has a value list |
| NUMBER | `BigDecimal` |
| DATE | `LocalDate` |
| TIME | `LocalTime`; `OffsetTime` when every value written for the column has an offset; the engine's `ZonedTime` when values have zone ids |
| DATE_TIME | `LocalDateTime`; `ZonedDateTime` when every value written for the column has a zone |
| durations | `Duration` or `Period` |
| a list input (`<list>true</list>`, or a DMN collection) | `java.util.List` of its type |

A time or date-time column that mixes values with and without a zone fails the build.

### Gradle plugin
The Gradle plugin does what the Maven plugin does, with the same settings and the same generated files.

```kotlin
// build.gradle.kts
plugins {
    java
    id("io.github.brunopacheco1.praecepta") version "0.12.0"
}

dependencies {
    implementation("io.github.brunopacheco1:praecepta-engine:0.12.0")
}

praecepta {
    packageName = "com.acme.pricing"
    tables {
        register("discount") {
            source = file("src/main/rules/discount.xlsx")
            hitPolicy = "FIRST"
            inputs {
                column("country") { allowedValues = "\"PT\",\"ES\",\"FR\"" }
                column("age") { type = "NUMBER" }
            }
            outputs {
                column("discount") { type = "NUMBER" }
            }
        }
        register("shipping") { source = file("src/main/rules/shipping.dmn") }
    }
}
```

- The `generatePraecepta` task writes classes to `build/generated/sources/praecepta/java/main` and DMN
  resources to `build/generated/resources/praecepta/main`. Both belong to the `main` source set.
- The task is cacheable and works with the configuration cache.
- One setting differs from Maven: `name` is the name you register a table under, so `className` names the
  generated class.

The [plugin's page](https://bruno.pacheco.lu/praecepta/gradle-plugin/) lists every setting.

### Decision graphs in generated code
When a DMN file's decisions require one another, or are not decision tables, the plugins compile the file
as a [decision requirement graph](GUIDE.md#decision-requirement-graphs):

```java
BigDecimal monthly = MonthlyLimit.evaluate(new MonthlyLimitInput(new BigDecimal(30), new BigDecimal(1200)));  // 200
String risk = RiskCategory.evaluate(new RiskCategoryInput(new BigDecimal(30)));                              // "Low"
DecisionGraph graph = CreditModel.graph();     // the whole graph
```

- **`<Model>Model`** (here `CreditModel`) loads the DMN once and holds the `DecisionGraph`, its
  `validationReport()` and a record for each structured input data.
- **Per decision**, `<Decision>Input` holds the input data the decision needs, and `<Decision>.evaluate`
  returns its value as a Java type: `String`, `BigDecimal`, `Boolean`, `LocalDate`, `Duration`, `Period`,
  a `List`, or a `<Decision>Output` record for a context. A date and time is a `TemporalAccessor` and a
  time an `Object`, because a FEEL value of those types may have a zone or not.
- `<decision>` compiles one decision of the file.
- The plugins find imported models beside the file, by `locationURI` or by namespace.
- A file that defines an external function is not a graph Praecepta evaluates. The plugins compile its
  decision table alone and log one line that says so. Without a table, the build fails.

## Frameworks
The three framework integrations share one core, `praecepta-integration`, so they behave alike:

- **Location.** They load every DMN file under `rules/` on the class path, such as
  `src/main/resources/rules/discount.dmn`.
- **One engine per decision table**, injected by the decision's name (its DMN `name`, or its `id` when it
  has none). Its type is `PraeceptaEngine<Map<String, String>, Map<String, String>>`.
- **One `Decisions` bean** evaluates every decision by name:
  `decisions.evaluate("Discount", Map.of("country", "PT"))`. It also gives `names()`,
  `get("Discount").report()` and `describe()`.
- **Inputs** map column names to values as text: `"42"`, `"2025-01-31"`, `"08:30Z"`, `["PT", "BR"]` for a
  list column. A missing or blank value is a missing input.
- **Outputs** are one map per matching rule, in the hit policy's order. Under a COLLECT aggregation, the
  one map holds the aggregate.
- **Failures.** The application does not start (Quarkus: does not build) when a file cannot be read, two
  decisions share a name, a location you set finds no file, or a table has validation issues at
  `fail-on`. The failure lists every problem at once. The integration logs lesser issues as warnings,
  such as `Discount: WARNING rule 3 can never be selected`.

Settings (Quarkus reads them under `quarkus.praecepta`, at build time):

| Key | Default | Meaning |
|---|---|---|
| `praecepta.locations` | `classpath*:rules/**/*.dmn` | The DMN files to load: a comma-separated list or a YAML list of patterns. `classpath:` and `classpath*:` search every class path root; `file:` searches the file system. `*` matches within a directory, `**` across directories. A class path pattern must start with a directory. |
| `praecepta.fail-on` | `ERROR` | The validation severity that stops startup or the build: `ERROR`, `WARNING` or `NONE`. |
| `praecepta.enabled` | `true` | Spring Boot and Micronaut: `false` turns the integration off. |

**Decision graphs.** A file whose decisions require one another, or are not decision tables, loads as a
[decision requirement graph](GUIDE.md#decision-requirement-graphs). `decisions.evaluate` then evaluates
any of its decisions from the model's input data:

```java
decisions.evaluate("Monthly Limit", Map.of("Applicant Age", "30", "Income", "1200"));   // [{Monthly Limit=200}]
decisions.evaluate("Payment", Map.of("Loan.principal", "600000", "Loan.rate", "0.0375", "Loan.months", "360"));
```

- Give a structured input data by its components' paths (`Loan.principal`).
- The result is one map for a table's outputs or a context's components, the value under the decision's
  name for other logic, one map per element of a list, and no map for null.
- A decision table of a graph is still an engine bean, which evaluates that table alone. Other logic has
  no engine bean.
- Load a model and the models it imports together: the integration finds them by namespace among the
  files it loads.
- A file that defines an external function loads its decision tables alone, and the integration logs one
  INFO line that says so. Without a table, loading fails.

**Excel and CSV.** The frameworks read DMN only. Compile spreadsheets with a build plugin, and add the
package of the DMN resources it writes to `praecepta.locations`
(`classpath*:com/acme/pricing/*.dmn`). The Quarkus extension can also compile them itself.

### Spring Boot
```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-spring-boot4-starter</artifactId>
  <version>0.12.0</version>
</dependency>
```

```java
@Service
class Pricing {
    private final PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    Pricing(@Qualifier("Discount") PraeceptaEngine<Map<String, String>, Map<String, String>> discount) {
        this.discount = discount;
    }

    List<Map<String, String>> discounts(String country, int age) {
        return discount.evaluate(Map.of("country", country, "age", Integer.toString(age)));
    }
}
```

- Inject an engine with `@Qualifier("Discount")`, or `@Named("Discount")` when `jakarta.inject-api` is
  on the class path. The engine beans are named `praecepta.<decision name>`, and the `Decisions` bean
  `praeceptaDecisions`.
- With Actuator, the endpoint `praecepta` reports each decision's columns, hit policy and validation
  issues (`/actuator/praecepta`, `/actuator/praecepta/{name}`). Expose it as you would any endpoint:
  `management.endpoints.web.exposure.include=health,praecepta`. It evaluates nothing.
- Spring AOT works. It validates the tables at build time and reads them again at startup. Set
  `praecepta.locations` and `praecepta.enabled` for the build as for the application.

### Micronaut
```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-micronaut4</artifactId>
  <version>0.12.0</version>
</dependency>
```

- Inject an engine with `@Named("Discount")`.
- With `micronaut-management`, the `praecepta` endpoint reports every decision (`GET /praecepta`,
  `GET /praecepta/Discount`). It is sensitive by default, because the reports show the rules' conditions.
  Open it with `endpoints.praecepta.sensitive: false`, or secure it with Micronaut Security.
- The module needs no reflection and no annotation processing in your build.

### Quarkus
```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-quarkus3</artifactId>
  <version>0.12.0</version>
</dependency>
```

```java
@ApplicationScoped
class Pricing {

    @Inject
    @Identifier("Discount") // or @Named("Discount")
    PraeceptaEngine<Map<String, String>, Map<String, String>> discount;

    @Inject
    Decisions decisions;
}
```

The extension works while Quarkus builds the application (`quarkus:build`, dev mode and tests):
1. it finds the DMN files under `rules/` in the application and its dependencies;
2. it compiles the Excel and CSV tables you declare in `application.properties`;
3. it validates everything, and fails the build at `quarkus.praecepta.fail-on`;
4. it registers the `Decisions` bean and one `@Singleton` engine per decision.

The application then builds its engines from the DMN the build validated, so it cannot fail differently
at runtime.

To compile a spreadsheet, declare it with the Maven plugin's settings in kebab-case:

```properties
quarkus.praecepta.tables.discount.source=src/main/rules/discount.xlsx
quarkus.praecepta.tables.discount.hit-policy=FIRST
quarkus.praecepta.tables.discount.inputs[0].name=country
quarkus.praecepta.tables.discount.inputs[0].allowed-values="PT","ES","FR"
quarkus.praecepta.tables.discount.inputs[1].name=age
quarkus.praecepta.tables.discount.inputs[1].type=NUMBER
quarkus.praecepta.tables.discount.outputs[0].name=discount
quarkus.praecepta.tables.discount.outputs[0].type=NUMBER
```

- The decision is named after the file, without its extension.
- Keep spreadsheets out of `src/main/resources`, so Quarkus does not package them. Apache POI stays in
  the build and never reaches the application.
- In dev mode, a change to a DMN file or a declared spreadsheet restarts the application. A table that
  fails validation shows on the error page. A file added under a `file:` location needs a manual restart.
- There is no report endpoint. `decisions.describe()` gives the same data at runtime.

## Without a framework
`praecepta-integration` is the core the frameworks share. It needs only the engine.

```xml
<dependency>
  <groupId>io.github.brunopacheco1</groupId>
  <artifactId>praecepta-integration</artifactId>
  <version>0.12.0</version>
</dependency>
```

```java
var sources = TableLocations.find(List.of(TableLocations.DEFAULT), getClass().getClassLoader());
var decisions = Decisions.load(sources, Severity.ERROR);       // throws DecisionsException

List<Map<String, String>> discount = decisions.evaluate("Discount", Map.of("country", "PT", "age", "42"));
PraeceptaEngine<Map<String, String>, Map<String, String>> engine = decisions.engine("Discount");
```

| Type | Role |
|---|---|
| `TableLocations` | Finds DMN files by pattern on the class path or the file system. `DEFAULT` is `classpath*:rules/**/*.dmn`. |
| `TableSource` | One DMN file: `TableSource.of(path)`, `of(url)`, `of(name, bytes)`. |
| `Decisions` | The decisions by name: `load`, `evaluate`, `engine`, `get`, `names`, `describe`, `models`. Immutable and thread-safe. |
| `Decision` | One decision: its table, engine, validation report and model. |
| `GraphModel` | A file's decision requirement graph. `graph()` is its `DecisionGraph`, for Java values. |
| `DecisionsLoader` | What a framework does with its settings: finds the files, loads them, logs, and words every failure alike. |
| `DecisionsException` | Everything that went wrong while loading, as `problems()`. |

To write a build-tool integration, use `praecepta-compiler`: `TableCompiler.compile` takes a
`TableDefinition` and `CompilerOptions`, and returns a `CompiledTable` that writes the DMN resource and
the Java sources. The Javadoc of [praecepta-compiler](https://bruno.pacheco.lu/praecepta/api/compiler/)
and [praecepta-integration](https://bruno.pacheco.lu/praecepta/api/integration/) has the details.

## Native images
Applications that use Praecepta build as GraalVM native executables with no configuration for Praecepta.
The engine uses no reflection, and each jar carries the metadata it needs under
`META-INF/native-image/io.github.brunopacheco1/<artifactId>/`.

| Works natively | Notes |
|---|---|
| The engine, `DmnReader`, `DmnWriter` | |
| Classes the build plugins generate | Nothing to do. |
| `praecepta-integration` and the frameworks | The default location, `rules/**/*.dmn`, is registered. Register files elsewhere yourself. |
| `CsvLoader` | Register the CSV files it reads. |
| `ExcelLoader` | **Not supported**: Apache POI does not run in a native image. Compile Excel tables at build time instead. |

Register files your own code reads by a computed name, for example in
`src/main/resources/META-INF/native-image/<groupId>/<artifactId>/resource-config.json`:

```json
{ "resources": { "includes": [ { "pattern": "\\Qtables/discount.csv\\E" }, { "pattern": "tables/.*\\.dmn" } ] } }
```

Use each framework's own native build: Spring Boot's `native` profile, Micronaut's
`-Dpackaging=native-image`, Quarkus' `-Dnative`. Without a framework, use GraalVM's
`native-maven-plugin` or the Gradle plugin `org.graalvm.buildtools.native`.

`--exact-reachability-metadata` (GraalVM for JDK 24 and later) works for Praecepta's own reads. Applied to
the whole program, it stops `java.util.logging` from printing the engine's validation warnings unless you
register the JDK's logging resources for your locale. To avoid that, limit it to your packages:
`--exact-reachability-metadata=com.acme`.

CI builds and runs a native application of each kind: one without a framework (GraalVM for JDK 21 and
25), and a Spring Boot, a Micronaut and a Quarkus application (`praecepta-native-tests`).
