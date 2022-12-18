![Workflow](https://github.com/brunopacheco1/praecepta/actions/workflows/build.yml/badge.svg) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=brunopacheco1_praecepta&metric=coverage)](https://sonarcloud.io/summary/new_code?id=brunopacheco1_praecepta)

# Praecepta
Praecepta is a rules engine implemented in Java that uses a trie tree to efficiently search and match patterns in a large set of rules.

# Features
- Fast rule matching using a trie tree data structure
- Flexible rule definition syntax
- Support for rule prioritization and conflict resolution
- Asynchronous rule execution

# Getting Started
## Praecepta Engine 
To use Praecepta Engine in your project, you will need to:

1. Add the Praecepta Engine library to your project's dependencies
2. Define your rules using the provided syntax
3. Create a `PraeceptaEngine` instance and add your rules to it
4. Use the `PraeceptaEngine` to evaluate rules and execute their associated actions

For example:

```java
var input1 = new PraeceptaInput(Map.of("input", "input1"));
var output1 = new PraeceptaOutput(Map.of("output", "output1"));
var praeceptum1 = new Praeceptum(1, input1, output1);

var input2 = new PraeceptaInput(Map.of("input", "input2"));
var output2 = new PraeceptaOutput(Map.of("output", "output2"));
var praeceptum2 = new Praeceptum(2, input2, output2);

var engine = new PraeceptaEngine<>(HitPolicy.UNIQUE, input -> input, output -> output);
engine.register(List.of(praeceptum1, praeceptum2));

var inputToEvaluate = new PraeceptaInput(Map.of("input", "input2"));
var result = engine.evaluate(inputToEvaluate);
```
## Praecepta Loader 
To use Praecepta Loader in your project, you will need to:

1. Add the Praecepta Loader library to your project's dependencies
2. Define your rules using the provided syntax
3. Create a `ExcelLoader` instance and add your rules to it
4. Use the `ExcelLoader` to read the excel file and register the rules into the engine

For example:

```java
var loader = new ExcelLoader("./yourmatrix.xlsx", Set.of("input"), Set.of("output"));

var engine = new PraeceptaEngine<>(HitPolicy.UNIQUE, input -> input, output -> output);

var praecepta = loader.getPraecepta();
engine.register(praecepta);

var inputToEvaluate = new PraeceptaInput(Map.of("input", "input2"));
var result = engine.evaluate(inputToEvaluate);
```

# Building and Testing
To build Praecepta, you will need Maven installed on your system. To build the project, run the following command from the root directory of the repository:

```
mvn clean install
```

To run the tests, use the following command:

```
mvn test
```

# Dependencies
Praecepta Engine has the following dependencies:
* Java 17

Praecepta Loader has the following dependencies:
* Apache POI

# Contributing
We welcome contributions to Praecepta! If you would like to contribute, please follow these steps:

1. Fork the repository
2. Create a new branch for your changes
3. Make your changes
4. Run the tests to ensure they pass
5. Submit a pull request

# License
Praecepta is licensed under the MIT License. See LICENSE for more information.