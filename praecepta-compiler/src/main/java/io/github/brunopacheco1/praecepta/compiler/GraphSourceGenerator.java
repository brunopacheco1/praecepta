/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.DecisionGraph;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Generates the classes of a decision model compiled as a decision requirements graph (#14), for a model
 * {@code Credit} whose decisions are {@code Risk Category} and {@code Monthly Limit}:
 * <ul>
 * <li>{@code CreditModel}: loads {@code CreditModel.dmn} from the classpath once into a {@link DecisionGraph}, and holds
 * a record for each structured input data ({@code CreditModel.Loan});</li>
 * <li>per decision, {@code MonthlyLimitInput}: a record of the input data the decision needs, directly or through the
 * decisions it requires ({@link DecisionGraph#requiredInputData}), each of the Java type of its FEEL type;</li>
 * <li>per decision whose value is a context, or a list of contexts, {@code PricingOutput}: a record of its components;</li>
 * <li>per decision, {@code MonthlyLimit}: {@code evaluate(MonthlyLimitInput)} evaluates it through the graph and gives
 * its value as its Java type.</li>
 * </ul>
 * FEEL types become Java types as follows: {@code string} a {@code String}, {@code number} a {@code BigDecimal},
 * {@code boolean} a {@code Boolean}, {@code date} a {@code LocalDate}, {@code date and time} a {@code TemporalAccessor}
 * (a {@code LocalDateTime} or a {@code ZonedDateTime}, which the graph tells apart by their values, not their types),
 * {@code time} an {@code Object} (a {@code LocalTime}, an {@code OffsetTime} or a {@code ZonedTime}, which share no Java
 * type), the durations a {@code Duration} and a {@code Period}, a list a {@code java.util.List}, a context a record and
 * {@code Any} an {@code Object}. Every type outside the package is fully qualified, as {@link SourceGenerator} does.
 */
final class GraphSourceGenerator {

    /** A {@code ${name}} slot in a template. */
    private static final Pattern SLOT = Pattern.compile("\\$\\{(\\w+)}");

    /** How deep types nest: item definitions and components, as the reader bounds them, and the lists between them. */
    private static final int MAX_DEPTH = 2 * DmnReader.MAX_TYPE_DEPTH + 2;

    private static final String GRAPH_TYPE = "io.github.brunopacheco1.praecepta.engine.DecisionGraph";
    private static final String REPORT_TYPE = "io.github.brunopacheco1.praecepta.engine.ValidationReport";
    private static final String EXCEPTIONS = "io.github.brunopacheco1.praecepta.engine.exceptions.";

    // ---- templates ------------------------------------------------------------------------------

    private static final String MODEL_CLASS = """
            /**
             * The {@code ${model}} decision model: a decision requirements graph of the decisions ${decisions}.
             *
             * <p>Read once from {@code ${Model}.dmn} on the classpath, written by ${generator} from
             * {@code ${source}} and validated at build time. Each decision has a class of its own, whose
             * {@code evaluate} evaluates it through {@link #graph()}, each decision it requires once.
             * Instances are not needed: all methods are static and thread-safe.
             */
            public final class ${Model} {

                /** The DMN resource, relative to this class. */
                public static final String RESOURCE = ${resource};

                private static final ${Graph} GRAPH = build();

                private ${Model}() {
                }

                /** The graph, e.g. to evaluate several decisions at once from Java values. */
                public static ${Graph} graph() {
                    return GRAPH;
                }

                /** Findings of the graph's validation (cycles, missing requirements, type mismatches). */
                public static ${Report} validationReport() {
                    return GRAPH.validationReport();
                }

                /** A list the graph gives, each element converted; null for null. */
                static <T> java.util.List<T> list(Object value, java.util.function.Function<Object, T> element) {
                    if (!(value instanceof java.util.List<?> values)) {
                        return null;
                    }
                    var list = new java.util.ArrayList<T>(values.size());
                    values.forEach(item -> list.add(element.apply(item)));
                    return java.util.Collections.unmodifiableList(list);
                }

                /** A list for the graph, each element converted; null for null. */
                static <T> java.util.List<Object> values(java.util.List<T> list, java.util.function.Function<T, Object> element) {
                    if (list == null) {
                        return null;
                    }
                    var values = new java.util.ArrayList<Object>(list.size());
                    list.forEach(item -> values.add(element.apply(item)));
                    return values;
                }

                /** A years and months duration the graph gives, as a {@code Period}; null for null. */
                static java.time.Period period(Object value) {
                    return value instanceof io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths duration
                            ? duration.toPeriod() : (java.time.Period) value;
                }

                private static ${Graph} build() {
                    try (var dmn = ${Model}.class.getResourceAsStream(RESOURCE)) {
                        if (dmn == null) {
                            throw new IllegalStateException("Missing resource " + RESOURCE + " next to " + ${Model}.class.getName());
                        }
                        return ${Graph}.read(dmn);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            ${records}}
            """;

    private static final String INPUT_RECORD = """
            /**
             * Input data of the {@link ${Decision}} decision: those it needs, directly or through the decisions it requires.
             */
            public record ${Input}(${components}) {
            }
            """;

    private static final String DECISION_CLASS = """
            /**
             * The {@code ${decision}} decision of the {@code ${model}} decision model: ${logic}.
             *
             * <p>Evaluated through the model's graph ({@link ${Model}}), from the input data of {@link ${Input}}, each
             * decision it requires once. Instances are not needed: all methods are static and thread-safe.
             */
            public final class ${Decision} {

                /** The decision's name in the model. */
                public static final String DECISION = ${name};

                private ${Decision}() {
                }

                /**
                 * @return the decision's value, ${type}; null for FEEL's null${noMatch}
                 * @throws ${Exceptions}HitPolicyViolationException if a decision table's hit policy is violated
                 * @throws ${Exceptions}InvalidInputException if a value is outside its column's value list
                 */
                public static ${Value} evaluate(${Input} input) {
                    var inputData = new java.util.HashMap<String, Object>();${puts}
                    return ${result};
                }

                /** Findings of the static analysis of the decision's table (overlaps, unreachable rules, gaps); empty for other logic. */
                public static ${Report} validationReport() {
                    return ${Model}.graph().validationReport(DECISION);
                }
            }
            """;

    /** A record of a context's components, nested at {@code ${indent}}. */
    private static final String CONTEXT_RECORD = """

            ${indent}/** ${description} */
            ${indent}public record ${Record}(${components}) {

            ${indent}    static ${Record} of(Object value) {
            ${indent}        if (!(value instanceof java.util.Map<?, ?> context)) {
            ${indent}            return null;
            ${indent}        }
            ${indent}        return new ${Record}(${arguments});
            ${indent}    }

            ${indent}    java.util.Map<String, Object> toMap() {
            ${indent}        var context = new java.util.LinkedHashMap<String, Object>();${puts}
            ${indent}        return context;
            ${indent}    }
            ${records}${indent}}
            """;

    // ---- generator ------------------------------------------------------------------------------

    private final String packageName;
    private final String generator;
    private final List<String> fileHeader;
    private final String sourceFile;
    private final DecisionGraph graph;
    private final String modelClass;
    /** The decisions to generate classes for, and each one's class. */
    private final Map<String, String> decisionClasses;

    /**
     * @param modelClass      the model class's name
     * @param decisionClasses the decisions to generate classes for, in order, each with its class's name
     */
    GraphSourceGenerator(CompilerOptions options, String sourceFile, DecisionGraph graph, String modelClass,
            Map<String, String> decisionClasses) {
        this.packageName = options.packageName();
        this.generator = options.generator();
        this.fileHeader = SourceGenerator.headerLines(options.fileHeader(), options.setting("fileHeader"));
        this.sourceFile = sourceFile;
        this.graph = graph;
        this.modelClass = modelClass;
        this.decisionClasses = new LinkedHashMap<>(decisionClasses);
    }

    /** The generated sources by class name: the model class, then for each decision its input, output and class. */
    Map<String, String> generate() {
        var sources = new LinkedHashMap<String, String>();
        sources.put(modelClass, header() + modelClass());
        decisionClasses.forEach((decision, className) -> {
            var output = className + "Output";
            var value = type(graph.type(decision), new Name(output, output, null), 0);
            sources.put(className + "Input", header() + inputRecord(decision, className));
            if (!value.records().isEmpty()) {
                sources.put(output, header() + value.records().strip() + "\n");
            }
            sources.put(className, header() + decisionClass(decision, className, value));
        });
        return sources;
    }

    // ---- the model class --------------------------------------------------------------------------

    private String modelClass() {
        var records = new StringBuilder();
        var inputData = graph.inputData();
        var names = JavaNames.distinct(List.copyOf(inputData.keySet()), JavaNames::type, "Decision model '" + graph.model().name() + "' input data");
        int i = 0;
        for (var input : inputData.entrySet()) {
            var name = nested(names.get(i++), List.of(modelClass));
            records.append(type(input.getValue(), new Name(name, modelClass + "." + name, List.of(modelClass)), 1).records());
        }
        var decisions = new StringJoiner(", ");
        graph.decisions().forEach(decision -> decisions.add("{@code " + SourceGenerator.javadoc(decision) + "}"));
        return fill(MODEL_CLASS, Map.of(
                "model", SourceGenerator.javadoc(graph.model().name()),
                "decisions", decisions.toString(),
                "Model", modelClass,
                "generator", SourceGenerator.javadoc(generator),
                "source", SourceGenerator.javadoc(sourceFile),
                "resource", SourceGenerator.stringLiteral(modelClass + ".dmn"),
                "records", records.toString()));
    }

    // ---- a decision's classes ---------------------------------------------------------------------

    private String inputRecord(String decision, String className) {
        var components = new StringJoiner(", ");
        var needed = graph.requiredInputData(decision);
        var members = members(needed, "Decision '" + decision + "' input data");
        for (int i = 0; i < needed.size(); i++) {
            components.add(inputType(needed.get(i)).javaType() + " " + members.get(i));
        }
        return fill(INPUT_RECORD, Map.of("Decision", className, "Input", className + "Input", "components", components.toString()));
    }

    /** The Java type of an input data, whose records the model class holds. */
    private TypeCode inputType(String inputData) {
        var names = JavaNames.distinct(List.copyOf(graph.inputData().keySet()), JavaNames::type, "input data");
        var name = nested(names.get(List.copyOf(graph.inputData().keySet()).indexOf(inputData)), List.of(modelClass));
        return type(graph.inputData().get(inputData), new Name(name, modelClass + "." + name, List.of(modelClass)), 1);
    }

    private String decisionClass(String decision, String className, TypeCode value) {
        var needed = graph.requiredInputData(decision);
        var members = members(needed, "Decision '" + decision + "' input data");
        var puts = new StringBuilder();
        for (int i = 0; i < needed.size(); i++) {
            var getter = "input." + members.get(i) + "()";
            puts.append("\n        inputData.put(").append(SourceGenerator.stringLiteral(needed.get(i))).append(", ")
                .append(inputType(needed.get(i)).toValue(getter, 0)).append(");");
        }
        var logic = switch (graph.model().decision(decision).orElseThrow().logic()) {
            case DecisionLogic.Table(var table) -> "a decision table (hit policy %s%s)".formatted(table.hitPolicy(),
                    table.aggregation() == null ? "" : ", " + table.aggregation());
            case DecisionLogic.LiteralExpression literal -> "the literal expression {@code " + SourceGenerator.javadoc(literal.text().strip()) + "}";
        };
        var noMatch = graph.model().decision(decision).orElseThrow().logic() instanceof DecisionLogic.Table
                ? ", as when no rule matches and the table has no default output" : "";
        var slots = new HashMap<String, String>();
        slots.put("decision", SourceGenerator.javadoc(decision));
        slots.put("model", SourceGenerator.javadoc(graph.model().name()));
        slots.put("logic", logic);
        slots.put("Model", modelClass);
        slots.put("Decision", className);
        slots.put("Input", className + "Input");
        slots.put("name", SourceGenerator.stringLiteral(decision));
        slots.put("type", "a {@code " + SourceGenerator.javadoc(graph.type(decision).feelName()) + "}");
        slots.put("noMatch", noMatch);
        slots.put("Exceptions", EXCEPTIONS);
        slots.put("Value", value.javaType());
        slots.put("puts", puts.toString());
        slots.put("result", value.fromValue(modelClass + ".graph().evaluate(DECISION, inputData)", 0));
        slots.put("Report", REPORT_TYPE);
        return fill(DECISION_CLASS, slots);
    }

    /** The record components of a decision's input data, distinct and clear of the methods records have. */
    private static List<String> members(List<String> names, String what) {
        return JavaNames.distinct(names, JavaNames::member, what);
    }

    // ---- types --------------------------------------------------------------------------------------

    /**
     * Where a context's record goes.
     *
     * @param simple    its simple name
     * @param qualified its name as other classes of the package write it
     * @param enclosing the simple names of the classes it is nested in, outermost first; null for a top-level record
     */
    private record Name(String simple, String qualified, List<String> enclosing) {
    }

    /**
     * How generated code handles a FEEL type.
     *
     * @param javaType the Java type of its values
     * @param kind     how a value of the graph becomes one of the Java type, and back
     * @param element  a list's element type, else null
     * @param records  the source of the records its contexts need
     * @param model    the model class, whose helpers the conversions call
     */
    private record TypeCode(String javaType, Kind kind, TypeCode element, String records, String model) {

        enum Kind {
            /** The graph's value as it is: the Java type is {@code Object}. */
            AS_IS,
            /** Cast to the Java type, and given back as it is. */
            CAST,
            /** A years and months duration, as a {@code Period}. */
            PERIOD,
            LIST,
            CONTEXT
        }

        /** The code that turns {@code value}, an {@code Object} of the graph, into the Java type. */
        String fromValue(String value, int depth) {
            return switch (kind) {
                case AS_IS -> value;
                case CAST -> "(" + javaType + ") " + value;
                case PERIOD -> "%s.period(%s)".formatted(model, value);
                case LIST -> "%s.list(%s, e%d -> %s)".formatted(model, value, depth, element.fromValue("e" + depth, depth + 1));
                case CONTEXT -> javaType + ".of(" + value + ")";
            };
        }

        /** The code that turns {@code value}, of the Java type, into a value the graph takes. */
        String toValue(String value, int depth) {
            return switch (kind) {
                case AS_IS, CAST, PERIOD -> value;
                case LIST -> element.kind == Kind.LIST || element.kind == Kind.CONTEXT
                        ? "%s.values(%s, e%d -> %s)".formatted(model, value, depth, element.toValue("e" + depth, depth + 1))
                        : value;
                case CONTEXT -> value + " == null ? null : " + value + ".toMap()";
            };
        }
    }

    /** The Java type of a FEEL type, with the records of its contexts named after {@code name}. */
    private TypeCode type(FeelType type, Name name, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Decision model '%s': the type of '%s' nests more than %d levels"
                    .formatted(graph.model().name(), name.simple(), MAX_DEPTH));
        }
        return switch (type) {
            case FeelType.Simple(var simple) -> switch (simple) {
                case STRING -> new TypeCode("String", TypeCode.Kind.CAST, null, "", modelClass);
                case NUMBER -> new TypeCode("java.math.BigDecimal", TypeCode.Kind.CAST, null, "", modelClass);
                case BOOLEAN -> new TypeCode("Boolean", TypeCode.Kind.CAST, null, "", modelClass);
                case DATE -> new TypeCode("java.time.LocalDate", TypeCode.Kind.CAST, null, "", modelClass);
                case DATE_TIME -> new TypeCode("java.time.temporal.TemporalAccessor", TypeCode.Kind.CAST, null, "", modelClass);
                case TIME -> new TypeCode("Object", TypeCode.Kind.AS_IS, null, "", modelClass);
                case DAY_TIME_DURATION -> new TypeCode("java.time.Duration", TypeCode.Kind.CAST, null, "", modelClass);
                case YEAR_MONTH_DURATION -> new TypeCode("java.time.Period", TypeCode.Kind.PERIOD, null, "", modelClass);
            };
            case FeelType.ListOf(var element) -> {
                var code = type(element, name, depth + 1);
                yield new TypeCode("java.util.List<" + code.javaType() + ">", TypeCode.Kind.LIST, code, code.records(), modelClass);
            }
            case FeelType.Context(var components) -> context(components, name, depth);
            case FeelType.Any any -> new TypeCode("Object", TypeCode.Kind.AS_IS, null, "", modelClass);
        };
    }

    /** A record of a context's components, nested records for the contexts among them. */
    private TypeCode context(Map<String, FeelType> components, Name name, int depth) {
        var where = "Context '" + name.simple() + "'";
        var componentNames = List.copyOf(components.keySet());
        var members = JavaNames.distinct(componentNames, text -> {
            var member = JavaNames.member(text);
            return member.equals("toMap") ? member + "_" : member;
        }, where + " components");
        var nestedIn = new ArrayList<String>(name.enclosing() == null ? List.of() : name.enclosing());
        nestedIn.add(name.simple());
        var typeNames = JavaNames.distinct(componentNames, JavaNames::type, where + " components");
        int level = nestedIn.size() - 1;
        var indent = "    ".repeat(level);
        var record = new StringJoiner(", ");
        var arguments = new StringJoiner(", ");
        var puts = new StringBuilder();
        var records = new StringBuilder();
        int i = 0;
        for (var component : components.entrySet()) {
            var nested = nested(typeNames.get(i), nestedIn);
            var code = type(component.getValue(), new Name(nested, name.qualified() + "." + nested, List.copyOf(nestedIn)), depth + 1);
            var member = members.get(i++);
            record.add(code.javaType() + " " + member);
            arguments.add(code.fromValue("context.get(" + SourceGenerator.stringLiteral(component.getKey()) + ")", 0));
            puts.append('\n').append(indent).append("        context.put(").append(SourceGenerator.stringLiteral(component.getKey()))
                .append(", ").append(code.toValue(member, 0)).append(");");
            records.append(code.records());
        }
        var source = fill(CONTEXT_RECORD, Map.of(
                "indent", indent,
                "description", "The context {@code " + SourceGenerator.javadoc(name.simple())
                        + "}: its components, as the graph gives and takes them.",
                "Record", name.simple(),
                "components", record.toString(),
                "arguments", arguments.toString(),
                "puts", puts.toString(),
                "records", records.toString()));
        return new TypeCode(name.qualified(), TypeCode.Kind.CONTEXT, null, source, modelClass);
    }

    /** A nested record's name, which Java requires to differ from the classes it is nested in. */
    private static String nested(String name, List<String> enclosing) {
        var nested = name;
        while (enclosing.contains(nested)) {
            nested = nested + "Value";
        }
        return nested;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private String header() {
        var java = new StringBuilder();
        for (var line : fileHeader) {
            java.append(line.isEmpty() ? "//" : "// " + line).append('\n');
        }
        java.append("// Generated by ").append(generator).append(" from ").append(SourceGenerator.javadoc(sourceFile)).append(". Do not edit.\n");
        if (!packageName.isEmpty()) {
            java.append("package ").append(packageName).append(";\n");
        }
        return java.append('\n').toString();
    }

    /** The template with every slot replaced by its value, in one pass, so a value is never searched for slots. */
    private static String fill(String template, Map<String, String> slots) {
        return SLOT.matcher(template).replaceAll(slot -> Matcher.quoteReplacement(switch (slot.group(1)) {
            case "Graph" -> GRAPH_TYPE;
            case "Report" -> REPORT_TYPE;
            default -> Objects.requireNonNull(slots.get(slot.group(1)), slot.group());
        }));
    }
}
