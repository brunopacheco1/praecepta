/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.maven.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;

/**
 * Generates, for a table {@code Discount}:
 * <ul>
 * <li>{@code DiscountInput}: a record with one component per input column ({@code String},
 * {@code BigDecimal}, {@code LocalDate}, {@code Boolean}, {@code LocalTime} or {@code LocalDateTime});
 * a string column with a value list gets a nested enum,
 * bound to the engine's int-coded path;</li>
 * <li>{@code DiscountOutput}: a record with one component per output column;</li>
 * <li>{@code Discount}: loads {@code Discount.dmn} from the classpath once and evaluates it.</li>
 * </ul>
 * Every type outside the package is fully qualified, so table and column names cannot clash with imports.
 */
final class SourceGenerator {

    private static final String ENGINE = "io.github.brunopacheco1.praecepta.engine.";

    private final String packageName;
    private final String className;
    private final DecisionTable table;
    private final String sourceFile;
    private final String inputType;
    private final String outputType;
    private final List<String> inputMembers;
    private final List<String> outputMembers;

    /** Lines put first in every generated file as {@code //} comments, e.g. an SPDX header; empty for none. */
    private final java.util.List<String> fileHeader;

    /**
     * @param fileHeader text put first in every generated file as {@code //} comments (each line
     *                   stripped, surrounding blank lines dropped), or null for none
     */
    SourceGenerator(String packageName, String className, DecisionTable table, String sourceFile, String fileHeader) {
        this.fileHeader = headerLines(fileHeader);
        this.packageName = packageName;
        this.className = className;
        this.table = table;
        this.sourceFile = sourceFile;
        this.inputType = className + "Input";
        this.outputType = className + "Output";
        var where = "Table '" + table.name() + "'";
        this.inputMembers = JavaNames.distinct(table.inputs().stream().map(InputColumn::name).toList(),
                JavaNames::member, where + " inputs");
        this.outputMembers = JavaNames.distinct(table.outputs().stream().map(DecisionTable.OutputColumn::name).toList(),
                JavaNames::member, where + " outputs");
    }

    Map<String, String> generate() {
        var sources = new LinkedHashMap<String, String>();
        sources.put(inputType, input());
        sources.put(outputType, output());
        sources.put(className, decision());
        return sources;
    }

    // ---- input record ---------------------------------------------------------------------------

    private String input() {
        var java = header();
        java.append("/**\n * Input of the {@link ").append(className).append("} decision.\n */\n")
            .append("public record ").append(inputType).append('(');
        for (int c = 0; c < table.inputs().size(); c++) {
            if (c > 0) {
                java.append(", ");
            }
            java.append(inputJavaType(c)).append(' ').append(inputMembers.get(c));
        }
        java.append(") {\n");
        for (int c = 0; c < table.inputs().size(); c++) {
            var column = table.inputs().get(c);
            var values = TableCompiler.enumValues(column);
            if (values == null) {
                continue;
            }
            var constants = JavaNames.distinct(values, JavaNames::constant, "Values of column '" + column.name() + "'");
            java.append("\n    /** Values of column {@code ").append(javadoc(column.name())).append("}. */\n")
                .append("    public enum ").append(enumName(c)).append(" {\n");
            for (int v = 0; v < values.size(); v++) {
                java.append("        ").append(constants.get(v)).append('(').append(stringLiteral(values.get(v))).append(')')
                    .append(v == values.size() - 1 ? ";\n" : ",\n");
            }
            java.append("\n        private final String value;\n\n")
                .append("        ").append(enumName(c)).append("(String value) {\n")
                .append("            this.value = value;\n        }\n\n")
                .append("        /** The value as written in the table. */\n")
                .append("        public String value() {\n            return value;\n        }\n    }\n");
        }
        return java.append("}\n").toString();
    }

    private String inputJavaType(int c) {
        var column = table.inputs().get(c);
        if (TableCompiler.enumValues(column) != null) {
            return enumName(c);
        }
        return javaType(column.type());
    }

    private String enumName(int c) {
        return JavaNames.type(table.inputs().get(c).name());
    }

    // ---- output record --------------------------------------------------------------------------

    private String output() {
        var java = header();
        java.append("/**\n * Output of a rule of the {@link ").append(className).append("} decision.\n */\n")
            .append("public record ").append(outputType).append('(');
        for (int c = 0; c < table.outputs().size(); c++) {
            if (c > 0) {
                java.append(", ");
            }
            java.append(javaType(table.outputs().get(c).type())).append(' ').append(outputMembers.get(c));
        }
        java.append(") {\n\n")
            .append("    static ").append(outputType).append(" of(").append(ENGINE).append("beans.PraeceptaOutput output) {\n")
            .append("        var values = output.values();\n")
            .append("        return new ").append(outputType).append('(');
        for (int c = 0; c < table.outputs().size(); c++) {
            var column = table.outputs().get(c);
            if (c > 0) {
                java.append(",\n                ");
            }
            var value = "values.get(" + stringLiteral(column.name()) + ")";
            java.append(switch (column.type()) {
                case STRING -> value;
                case NUMBER -> "number(" + value + ")";
                case DATE -> "date(" + value + ")";
                case BOOLEAN -> "bool(" + value + ")";
                case TIME -> "time(" + value + ")";
                case DATE_TIME -> "dateTime(" + value + ")";
            });
        }
        java.append(");\n    }\n");
        if (table.outputs().stream().anyMatch(column -> column.type() == ColumnType.NUMBER)) {
            java.append("\n    private static java.math.BigDecimal number(String value) {\n")
                .append("        return value == null ? null : new java.math.BigDecimal(value);\n    }\n");
        }
        if (table.outputs().stream().anyMatch(column -> column.type() == ColumnType.DATE)) {
            java.append("\n    private static java.time.LocalDate date(String value) {\n")
                .append("        return value == null ? null : java.time.LocalDate.parse(value);\n    }\n");
        }
        if (table.outputs().stream().anyMatch(column -> column.type() == ColumnType.BOOLEAN)) {
            java.append("\n    private static Boolean bool(String value) {\n")
                .append("        return value == null ? null : Boolean.valueOf(value);\n    }\n");
        }
        if (table.outputs().stream().anyMatch(column -> column.type() == ColumnType.TIME)) {
            java.append("\n    private static java.time.LocalTime time(String value) {\n")
                .append("        return value == null ? null : java.time.LocalTime.parse(value);\n    }\n");
        }
        if (table.outputs().stream().anyMatch(column -> column.type() == ColumnType.DATE_TIME)) {
            java.append("\n    private static java.time.LocalDateTime dateTime(String value) {\n")
                .append("        return value == null ? null : java.time.LocalDateTime.parse(value);\n    }\n");
        }
        return java.append("}\n").toString();
    }

    // ---- decision class -----------------------------------------------------------------------

    private String decision() {
        var engineType = ENGINE + "PraeceptaEngine<" + inputType + ", " + outputType + ">";
        var single = TableCompiler.singleResult(table);
        var java = header();
        java.append("/**\n")
            .append(" * The {@code ").append(javadoc(table.name())).append("} decision table (hit policy ").append(table.hitPolicy());
        if (table.aggregation() != null) {
            java.append(", ").append(table.aggregation());
        }
        java.append(").\n *\n")
            .append(" * <p>Rules are read once from {@code ").append(className).append(".dmn} on the classpath, written by\n")
            .append(" * praecepta-maven-plugin from {@code ").append(javadoc(sourceFile)).append("} and validated at build time.\n")
            .append(" * Instances are not needed: all methods are static and thread-safe.\n */\n")
            .append("public final class ").append(className).append(" {\n\n")
            .append("    /** The DMN resource, relative to this class. */\n")
            .append("    public static final String RESOURCE = ").append(stringLiteral(className + ".dmn")).append(";\n\n")
            .append("    private static final ").append(engineType).append(" ENGINE = build();\n\n")
            .append("    private ").append(className).append("() {\n    }\n\n");
        if (single) {
            java.append("    /**\n     * @return the selected output, or empty if no rule matches\n")
                .append("     * @throws ").append(ENGINE).append("exceptions.HitPolicyViolationException if the hit policy is violated\n")
                .append("     * @throws ").append(ENGINE).append("exceptions.InvalidInputException if a value is outside its column's value list\n")
                .append("     */\n")
                .append("    public static java.util.Optional<").append(outputType).append("> evaluate(").append(inputType).append(" input) {\n")
                .append("        var outputs = ENGINE.evaluate(input);\n")
                .append("        return outputs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(outputs.getFirst());\n")
                .append("    }\n\n");
        } else {
            java.append("    /**\n     * @return the outputs of every matching rule, in hit-policy order; empty if no rule matches\n")
                .append("     * @throws ").append(ENGINE).append("exceptions.InvalidInputException if a value is outside its column's value list\n")
                .append("     */\n")
                .append("    public static java.util.List<").append(outputType).append("> evaluate(").append(inputType).append(" input) {\n")
                .append("        return ENGINE.evaluate(input);\n    }\n\n");
        }
        java.append("    /** The underlying engine, e.g. to pass around as a function. */\n")
            .append("    public static ").append(engineType).append(" engine() {\n        return ENGINE;\n    }\n\n")
            .append("    /** Findings of the static analysis (overlaps, unreachable rules, gaps). */\n")
            .append("    public static ").append(ENGINE).append("ValidationReport validationReport() {\n")
            .append("        return ENGINE.validationReport();\n    }\n\n")
            .append("    private static ").append(engineType).append(" build() {\n")
            .append("        try (var dmn = ").append(className).append(".class.getResourceAsStream(RESOURCE)) {\n")
            .append("            if (dmn == null) {\n")
            .append("                throw new IllegalStateException(\"Missing resource \" + RESOURCE + \" next to \" + ")
            .append(className).append(".class.getName());\n            }\n")
            .append("            return ").append(ENGINE).append("PraeceptaEngine.<").append(inputType).append(", ").append(outputType)
            .append(">builder()\n")
            .append("                    .table(").append(ENGINE).append("dmn.DmnReader.read(dmn))\n");
        for (int c = 0; c < table.inputs().size(); c++) {
            var column = table.inputs().get(c);
            var name = stringLiteral(column.name());
            var getter = inputType + "::" + inputMembers.get(c);
            if (TableCompiler.enumValues(column) != null) {
                var enumType = inputType + "." + enumName(c);
                java.append("                    .enumInput(").append(name).append(", ").append(enumType).append(".class, ")
                    .append(enumType).append("::value, ").append(getter).append(")\n");
            } else {
                java.append("                    .").append(switch (column.type()) {
                    case STRING -> "input";
                    case NUMBER -> "numberInput";
                    case DATE -> "dateInput";
                    case BOOLEAN -> "booleanInput";
                    case TIME -> "timeInput";
                    case DATE_TIME -> "dateTimeInput";
                }).append('(').append(name).append(", ").append(getter).append(")\n");
            }
        }
        java.append("                    .output(").append(outputStrategy()).append(")\n")
            .append("                    .build();\n")
            .append("        } catch (java.io.IOException e) {\n")
            .append("            throw new java.io.UncheckedIOException(e);\n        }\n    }\n}\n");
        return java.toString();
    }

    private String outputStrategy() {
        if (table.aggregation() == null) {
            return outputType + "::of";
        }
        // one NUMBER output (checked when compiling); missing values are skipped, as in DMN
        var field = outputMembers.getFirst();
        var strategy = ENGINE + "OutputStrategy<" + outputType + ">";
        return "new " + strategy + "() {\n"
               + "                        @Override\n"
               + "                        public " + outputType + " transform(" + ENGINE + "beans.PraeceptaOutput output) {\n"
               + "                            return " + outputType + ".of(output);\n                        }\n\n"
               + "                        @Override\n"
               + "                        public " + outputType + " sum(" + outputType + " a, " + outputType + " b) {\n"
               + "                            return a." + field + "() == null ? b : b." + field + "() == null ? a : new " + outputType
               + "(a." + field + "().add(b." + field + "()));\n                        }\n\n"
               + "                        @Override\n"
               + "                        public " + outputType + " min(" + outputType + " a, " + outputType + " b) {\n"
               + "                            return a." + field + "() == null ? b : b." + field + "() == null || a." + field
               + "().compareTo(b." + field + "()) <= 0 ? a : b;\n                        }\n\n"
               + "                        @Override\n"
               + "                        public " + outputType + " max(" + outputType + " a, " + outputType + " b) {\n"
               + "                            return a." + field + "() == null ? b : b." + field + "() == null || a." + field
               + "().compareTo(b." + field + "()) >= 0 ? a : b;\n                        }\n"
               + "                    }";
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static java.util.List<String> headerLines(String text) {
        if (text == null || text.isBlank()) {
            return java.util.List.of();
        }
        var lines = text.strip().lines().map(String::strip).toList();
        for (var line : lines) {
            // javac decodes unicode escapes (backslash, 'u', hex digits) even inside comments
            if (line.contains("\\")) {
                throw new IllegalArgumentException("<fileHeader> must not contain backslashes: " + line);
            }
        }
        return lines;
    }

    private StringBuilder header() {
        var java = new StringBuilder();
        for (var line : fileHeader) {
            java.append(line.isEmpty() ? "//" : "// " + line).append('\n');
        }
        java.append("// Generated by praecepta-maven-plugin from ").append(javadoc(sourceFile)).append(". Do not edit.\n");
        if (!packageName.isEmpty()) {
            java.append("package ").append(packageName).append(";\n");
        }
        return java.append('\n');
    }

    private static String javaType(ColumnType type) {
        return switch (type) {
            case STRING -> "String";
            case NUMBER -> "java.math.BigDecimal";
            case DATE -> "java.time.LocalDate";
            case BOOLEAN -> "Boolean";
            case TIME -> "java.time.LocalTime";
            case DATE_TIME -> "java.time.LocalDateTime";
        };
    }

    static String stringLiteral(String value) {
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
}
