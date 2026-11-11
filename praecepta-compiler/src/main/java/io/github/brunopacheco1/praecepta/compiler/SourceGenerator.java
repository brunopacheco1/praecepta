/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;

/**
 * Generates, for a table {@code Discount}:
 * <ul>
 * <li>{@code DiscountInput}: a record with one component per input column ({@code String},
 * {@code BigDecimal}, {@code LocalDate}, {@code Boolean}, {@code LocalTime} or {@code OffsetTime},
 * {@code LocalDateTime} or {@code ZonedDateTime} (see {@link TimeZones}), {@code Duration} or
 * {@code Period}), and one per other input the cells compare with;
 * a string column with a value list gets a nested enum,
 * bound to the engine's int-coded path, and an input that holds lists a {@code java.util.List} of its type;</li>
 * <li>{@code DiscountOutput}: a record with one component per output column;</li>
 * <li>{@code Discount}: loads {@code Discount.dmn} from the classpath once and evaluates it.</li>
 * </ul>
 * Every type outside the package is fully qualified, so table and column names cannot clash with imports.
 *
 * <p>Each file is written from the templates below: {@code ${Decision}}, {@code ${Input}} and
 * {@code ${Output}} stand for the generated class names, and every other {@code ${slot}} for the code
 * that depends on the table's columns.
 */
final class SourceGenerator {

    /** A {@code ${name}} slot in a template. */
    private static final Pattern SLOT = Pattern.compile("\\$\\{(\\w+)}");

    /** Starts each input binding: generated sources always use {@code \n}, whatever the platform. */
    private static final String BINDING_LINE = "\n                    ";

    // ---- templates ------------------------------------------------------------------------------

    private static final String INPUT_RECORD = """
            /**
             * Input of the {@link ${Decision}} decision.
             */
            public record ${Input}(${components}) {
            ${enums}}
            """;

    /** Nested in the input record for each string column with a value list. */
    private static final String VALUE_ENUM = """

                /** Values of column {@code ${column}}. */
                public enum ${Enum} {
            ${constants}

                    private final String value;

                    ${Enum}(String value) {
                        this.value = value;
                    }

                    /** The value as written in the table. */
                    public String value() {
                        return value;
                    }
                }
            """;

    private static final String OUTPUT_RECORD = """
            /**
             * Output of a rule of the {@link ${Decision}} decision.
             */
            public record ${Output}(${components}) {

                static ${Output} of(io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput output) {
                    var values = output.values();
                    return new ${Output}(${arguments});
                }
            ${parsers}}
            """;

    /** Parses the output values of one column type in the output record. */
    private static final String OUTPUT_PARSER = """

                private static ${Type} ${method}(String value) {
                    return value == null ? null : ${parser}(value);
                }
            """;

    private static final String DECISION_CLASS = """
            /**
             * The {@code ${table}} decision table (hit policy ${hitPolicy}).
             *
             * <p>Rules are read once from {@code ${Decision}.dmn} on the classpath, written by
             * ${generator} from {@code ${source}} and validated at build time.
             * Instances are not needed: all methods are static and thread-safe.
             */
            public final class ${Decision} {

                /** The DMN resource, relative to this class. */
                public static final String RESOURCE = ${resource};

                private static final io.github.brunopacheco1.praecepta.engine.PraeceptaEngine<${Input}, ${Output}> ENGINE = build();

                private ${Decision}() {
                }

            ${evaluate}
                /** The underlying engine, e.g. to pass around as a function. */
                public static io.github.brunopacheco1.praecepta.engine.PraeceptaEngine<${Input}, ${Output}> engine() {
                    return ENGINE;
                }

                /** Findings of the static analysis (overlaps, unreachable rules, gaps). */
                public static io.github.brunopacheco1.praecepta.engine.ValidationReport validationReport() {
                    return ENGINE.validationReport();
                }

                private static io.github.brunopacheco1.praecepta.engine.PraeceptaEngine<${Input}, ${Output}> build() {
                    try (var dmn = ${Decision}.class.getResourceAsStream(RESOURCE)) {
                        if (dmn == null) {
                            throw new IllegalStateException("Missing resource " + RESOURCE + " next to " + ${Decision}.class.getName());
                        }
                        return io.github.brunopacheco1.praecepta.engine.PraeceptaEngine.<${Input}, ${Output}>builder()
                                .table(io.github.brunopacheco1.praecepta.engine.dmn.DmnReader.read(dmn))${bindings}
                                .output(${outputStrategy})
                                .build();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
            """;

    /** {@code evaluate} of a table that selects one output: a single-hit policy, or an aggregation. */
    private static final String EVALUATE_ONE = """
                /**
                 * @return the selected output, or ${noMatch} if no rule matches
                 * @throws io.github.brunopacheco1.praecepta.engine.exceptions.HitPolicyViolationException if the hit policy is violated
                 * @throws io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException if a value is outside its column's value list
                 */
                public static java.util.Optional<${Output}> evaluate(${Input} input) {
                    var outputs = ENGINE.evaluate(input);
                    return outputs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(outputs.getFirst());
                }
            """;

    /** {@code evaluate} of a table that returns the outputs of every matching rule. */
    private static final String EVALUATE_ALL = """
                /**
                 * @return the outputs of every matching rule, in hit-policy order; ${noMatch} if no rule matches
                 * @throws io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException if a value is outside its column's value list
                 */
                public static java.util.List<${Output}> evaluate(${Input} input) {
                    return ENGINE.evaluate(input);
                }
            """;

    /**
     * The output strategy of an aggregating table, whose one NUMBER output is checked when compiling;
     * missing values are skipped, as in DMN. It is pasted mid-line after {@code .output(} in the decision
     * class, so its first line has no indentation and the others carry the generated file's own.
     */
    private static final String AGGREGATING_OUTPUT = """
            new io.github.brunopacheco1.praecepta.engine.OutputStrategy<${Output}>() {
                                    @Override
                                    public ${Output} transform(io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput output) {
                                        return ${Output}.of(output);
                                    }

                                    @Override
                                    public ${Output} sum(${Output} a, ${Output} b) {
                                        return a.${field}() == null ? b : b.${field}() == null ? a : new ${Output}(a.${field}().add(b.${field}()));
                                    }

                                    @Override
                                    public ${Output} min(${Output} a, ${Output} b) {
                                        return a.${field}() == null ? b : b.${field}() == null || a.${field}().compareTo(b.${field}()) <= 0 ? a : b;
                                    }

                                    @Override
                                    public ${Output} max(${Output} a, ${Output} b) {
                                        return a.${field}() == null ? b : b.${field}() == null || a.${field}().compareTo(b.${field}()) >= 0 ? a : b;
                                    }

                                    @Override
                                    public ${Output} count(int matches) {
                                        return new ${Output}(java.math.BigDecimal.valueOf(matches));
                                    }
                                }""";

    // ---- generator ------------------------------------------------------------------------------

    private final String packageName;
    /** The tool named in the generated files, such as praecepta-maven-plugin. */
    private final String generator;
    private final String className;
    private final DecisionTable table;
    private final String sourceFile;
    private final String inputType;
    private final String outputType;
    private final List<InputField> inputs;
    private final List<String> outputMembers;
    /** The time and date-time columns whose values have a time zone. */
    private final TimeZones.Zoned zoned;

    /** Lines put first in every generated file as {@code //} comments, e.g. an SPDX header; empty for none. */
    private final List<String> fileHeader;

    /**
     * @param options the package, the tool named in the files, and the file header: text put first in every
     *                generated file as {@code //} comments (each line stripped, surrounding blank lines
     *                dropped), or null for none
     */
    SourceGenerator(CompilerOptions options, String className, DecisionTable table, String sourceFile) {
        this.fileHeader = headerLines(options.fileHeader(), options.setting("fileHeader"));
        this.packageName = options.packageName();
        this.generator = options.generator();
        this.className = className;
        this.table = table;
        this.sourceFile = sourceFile;
        this.inputType = className + "Input";
        this.outputType = className + "Output";
        var where = "Table '" + table.name() + "'";
        // the table's columns, then the inputs its cells compare with (DMN only, as in > limit)
        var inputColumns = new ArrayList<>(table.inputs());
        table.namedInputs().forEach((name, type) -> inputColumns.add(new InputColumn(name, type)));
        var inputMembers = JavaNames.distinct(inputColumns.stream().map(InputColumn::name).toList(),
                JavaNames::member, where + " inputs");
        this.outputMembers = JavaNames.distinct(table.outputs().stream().map(DecisionTable.OutputColumn::name).toList(),
                JavaNames::member, where + " outputs");
        this.zoned = TimeZones.of(table);
        this.inputs = new ArrayList<>();
        for (int c = 0; c < inputColumns.size(); c++) {
            var column = inputColumns.get(c);
            inputs.add(InputField.of(column, inputMembers.get(c), zoned.inputs().contains(column.name()),
                    table.listInputs().contains(column.name())));
        }
    }

    Map<String, String> generate() {
        var sources = new LinkedHashMap<String, String>();
        sources.put(inputType, header() + inputRecord());
        sources.put(outputType, header() + outputRecord());
        sources.put(className, header() + decisionClass());
        return sources;
    }

    // ---- input record ---------------------------------------------------------------------------

    private String inputRecord() {
        var components = new StringJoiner(", ");
        var enums = new StringBuilder();
        for (var input : inputs) {
            components.add(input.javaType() + " " + input.member());
            if (input.isEnum()) {
                enums.append(valueEnum(input));
            }
        }
        return fill(INPUT_RECORD, Map.of("components", components.toString(), "enums", enums.toString()));
    }

    private String valueEnum(InputField input) {
        var values = input.enumValues();
        var names = JavaNames.distinct(values, JavaNames::constant, "Values of column '" + input.column().name() + "'");
        var constants = new StringJoiner(",\n", "", ";");
        for (int v = 0; v < values.size(); v++) {
            constants.add("        " + names.get(v) + "(" + stringLiteral(values.get(v)) + ")");
        }
        return fill(VALUE_ENUM, Map.of(
                "column", javadoc(input.column().name()),
                "Enum", input.enumName(),
                "constants", constants.toString()));
    }

    // ---- output record --------------------------------------------------------------------------

    private String outputRecord() {
        var components = new StringJoiner(", ");
        var arguments = new StringJoiner(",\n                ");
        for (int c = 0; c < table.outputs().size(); c++) {
            var column = table.outputs().get(c);
            var converter = outputConverter(column);
            components.add(converter.javaType() + " " + outputMembers.get(c));
            var value = "values.get(" + stringLiteral(column.name()) + ")";
            arguments.add(converter.parser() == null ? value : converter.method() + "(" + value + ")");
        }
        return fill(OUTPUT_RECORD, Map.of(
                "components", components.toString(),
                "arguments", arguments.toString(),
                "parsers", outputParsers()));
    }

    /** One parsing helper per output type that needs it, sorted by name so the source is stable. */
    private String outputParsers() {
        var parsers = new StringBuilder();
        table.outputs().stream()
                .map(this::outputConverter)
                .filter(converter -> converter.parser() != null)
                .distinct()
                .sorted(Comparator.comparing(Converter::method))
                .forEach(converter -> parsers.append(fill(OUTPUT_PARSER, Map.of(
                        "Type", converter.javaType(),
                        "method", converter.method(),
                        "parser", converter.parser()))));
        return parsers.toString();
    }

    private Converter outputConverter(DecisionTable.OutputColumn column) {
        return Converter.of(column.type(), zoned.outputs().contains(column.name()));
    }

    // ---- decision class -------------------------------------------------------------------------

    private String decisionClass() {
        var singleResult = table.hitPolicy().isSingleHit() || table.aggregation() != null;
        var hitPolicy = table.aggregation() == null ? String.valueOf(table.hitPolicy()) : table.hitPolicy() + ", " + table.aggregation();
        var bindings = new StringBuilder();
        for (var input : inputs) {
            bindings.append(inputBinding(input));
        }
        return fill(DECISION_CLASS, Map.of(
                "table", javadoc(table.name()),
                "hitPolicy", hitPolicy,
                "source", javadoc(sourceFile),
                "generator", javadoc(generator),
                "resource", stringLiteral(className + ".dmn"),
                "evaluate", fill(singleResult ? EVALUATE_ONE : EVALUATE_ALL, Map.of("noMatch", noMatch(singleResult))),
                "bindings", bindings.toString(),
                "outputStrategy", outputStrategy()));
    }

    /**
     * What {@code evaluate} gives when no rule matches: nothing, or the table's default output (DMN's default
     * output values), which the engine gives whatever the hit policy.
     */
    private String noMatch(boolean singleResult) {
        if (table.defaultOutput().isEmpty()) {
            return "empty";
        }
        return singleResult ? "the table's default output" : "the table's default output alone";
    }

    /**
     * The engine builder call that reads an input column from the input record. It starts with a newline
     * and the call's indentation, so the bindings follow {@code .table(...)} directly in the template and a
     * table without inputs adds nothing there.
     */
    private String inputBinding(InputField input) {
        var column = stringLiteral(input.column().name());
        var getter = inputType + "::" + input.member();
        if (input.list()) {
            var type = input.column().type();
            return type == ColumnType.STRING
                    ? BINDING_LINE + ".listInput(%s, %s)".formatted(column, getter)
                    : BINDING_LINE + ".listInput(%s, io.github.brunopacheco1.praecepta.engine.beans.ColumnType.%s, %s)"
                            .formatted(column, type.name(), getter);
        }
        if (!input.isEnum()) {
            return BINDING_LINE + ".%s(%s, %s)".formatted(input.converter().binding(), column, getter);
        }
        var enumType = inputType + "." + input.enumName();
        return BINDING_LINE + ".enumInput(%s, %s.class, %s::value, %s)".formatted(column, enumType, enumType, getter);
    }

    private String outputStrategy() {
        if (table.aggregation() == null) {
            return outputType + "::of";
        }
        return fill(AGGREGATING_OUTPUT, Map.of("field", outputMembers.getFirst()));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<String> headerLines(String text, String setting) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var lines = text.strip().lines().map(String::strip).toList();
        for (var line : lines) {
            // javac decodes unicode escapes (backslash, 'u', hex digits) even inside comments
            if (line.contains("\\")) {
                throw new IllegalArgumentException(setting + " must not contain backslashes: " + line);
            }
        }
        return lines;
    }

    private String header() {
        var java = new StringBuilder();
        for (var line : fileHeader) {
            java.append(line.isEmpty() ? "//" : "// " + line).append('\n');
        }
        java.append("// Generated by ").append(generator).append(" from ").append(javadoc(sourceFile)).append(". Do not edit.\n");
        if (!packageName.isEmpty()) {
            java.append("package ").append(packageName).append(";\n");
        }
        return java.append('\n').toString();
    }

    /**
     * The template with every slot replaced by its value. One pass over the template: a value is
     * never searched for slots, so a column named {@code ${Output}} stays as it is.
     */
    private String fill(String template, Map<String, String> slots) {
        return SLOT.matcher(template).replaceAll(slot -> Matcher.quoteReplacement(switch (slot.group(1)) {
            case "Decision" -> className;
            case "Input" -> inputType;
            case "Output" -> outputType;
            default -> Objects.requireNonNull(slots.get(slot.group(1)), slot.group());
        }));
    }

    private static String stringLiteral(String value) {
        var java = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> java.append("\\\"");
                case '\\' -> java.append("\\\\");
                case '\n' -> java.append("\\n");
                case '\r' -> java.append("\\r");
                case '\t' -> java.append("\\t");
                default -> {
                    if (c < 0x20) {
                        java.append("\\u%04x".formatted((int) c));
                    } else {
                        java.append(c);
                    }
                }
            }
        }
        return java.append('"').toString();
    }

    private static String javadoc(String text) {
        // backslashes too: javac decodes unicode escapes (backslash, 'u', hex digits) even inside comments
        return text.replace("\\", "&#92;").replace("*/", "*&#47;").replace("@", "&#64;").replace("{", "&#123;").replace("}", "&#125;")
                .replaceAll("[\\r\\n]", " ");
    }

    // ---- nested types ---------------------------------------------------------------------------

    /**
     * An input column as the generated code sees it.
     *
     * @param member     its component in the input record
     * @param enumName   for a string column with a value list, its nested enum; else null
     * @param enumValues the values of that enum's constants, in order; else empty
     * @param zoned      for a time or date-time, whether its values (a list's elements) have a time zone
     * @param list       whether it holds lists, a {@code java.util.List} component (of strings, even with a value list)
     */
    private record InputField(InputColumn column, String member, String enumName, List<String> enumValues, boolean zoned,
            boolean list) {

        static InputField of(InputColumn column, String member, boolean zoned, boolean list) {
            var values = list ? List.<String>of() : valueListOf(column);
            return new InputField(column, member, values.isEmpty() ? null : JavaNames.type(column.name()), values, zoned, list);
        }

        Converter converter() {
            return Converter.of(column.type(), zoned);
        }

        boolean isEnum() {
            return !enumValues.isEmpty();
        }

        /**
         * The values of a string column's value list, or an empty list if it has none (it then stays a
         * String). A value list always holds at least one value.
         */
        private static List<String> valueListOf(InputColumn column) {
            if (column.type() != ColumnType.STRING || column.allowedValues() == null) {
                return List.of();
            }
            // the engine has already checked that a string column's value list is a list of values
            var values = new LinkedHashSet<String>();
            for (var value : CellParser.parse(column.allowedValues(), ColumnType.STRING).values().orElseThrow()) {
                values.add((String) value);
            }
            return List.copyOf(values);
        }

        String javaType() {
            if (list) {
                return "java.util.List<" + converter().javaType() + ">";
            }
            return isEnum() ? enumName : converter().javaType();
        }
    }

    /**
     * How generated code handles a column type.
     *
     * @param javaType the Java type of its record components
     * @param binding  the engine builder method that binds an input column of the type
     * @param method   the name of the generated helper that parses an output value
     * @param parser   the expression that parses a non-null output value, or null for strings
     */
    private record Converter(String javaType, String binding, String method, String parser) {

        /** @param zoned for a time or date-time, whether its values have a time zone */
        static Converter of(ColumnType type, boolean zoned) {
            if (zoned && type == ColumnType.TIME) {
                return new Converter("java.time.OffsetTime", "offsetTimeInput", "offsetTime", "java.time.OffsetTime.parse");
            }
            if (zoned && type == ColumnType.DATE_TIME) {
                // FEEL writes a zone id as @Europe/Paris, which ZonedDateTime.parse does not read
                return new Converter("java.time.ZonedDateTime", "zonedDateTimeInput", "zonedDateTime",
                        "io.github.brunopacheco1.praecepta.engine.feel.FeelTemporals.parseZonedDateTime");
            }
            return switch (type) {
                case STRING -> new Converter("String", "input", null, null);
                case NUMBER -> new Converter("java.math.BigDecimal", "numberInput", "number", "new java.math.BigDecimal");
                case DATE -> new Converter("java.time.LocalDate", "dateInput", "date", "java.time.LocalDate.parse");
                case BOOLEAN -> new Converter("Boolean", "booleanInput", "bool", "Boolean.valueOf");
                case TIME -> new Converter("java.time.LocalTime", "timeInput", "time", "java.time.LocalTime.parse");
                case DATE_TIME -> new Converter("java.time.LocalDateTime", "dateTimeInput", "dateTime", "java.time.LocalDateTime.parse");
                case DAY_TIME_DURATION -> new Converter("java.time.Duration", "durationInput", "duration", "java.time.Duration.parse");
                case YEAR_MONTH_DURATION -> new Converter("java.time.Period", "periodInput", "period", "java.time.Period.parse");
            };
        }
    }
}
