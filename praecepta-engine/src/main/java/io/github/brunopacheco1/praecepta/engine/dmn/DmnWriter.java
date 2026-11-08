/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.CellSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Writes a {@link DecisionTable} as a DMN 1.3 model with one decision. Cells are parsed and written
 * back as standard FEEL (quoted strings, plain numbers, {@code date("...")}, {@code time("...")},
 * {@code date and time("...")}, {@code true} / {@code false}), so tables written in
 * Praecepta's shorter syntax ({@code PT,ES}) become portable. Each input also gets an
 * {@code inputData} element, which Drools needs to bind input values.
 *
 * <p>The result is read back unchanged by {@link DmnReader}, and is evaluated identically by
 * Camunda 8 and Drools (checked by the benchmark module).
 */
public final class DmnWriter {

    static final String NAMESPACE = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    private DmnWriter() {
    }

    /**
     * @throws InvalidDecisionTableException if a cell is not valid for its column type
     */
    public static String write(DecisionTable table) {
        var id = identifier(table.name());
        var xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<definitions xmlns=\"").append(NAMESPACE).append("\" id=\"definitions_").append(id)
           .append("\" name=\"").append(attribute(table.name()))
           .append("\" namespace=\"https://github.com/brunopacheco1/praecepta/").append(id).append("\">\n");
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            xml.append("  <inputData id=\"input_data_").append(c).append("\" name=\"").append(attribute(input.name())).append("\">\n")
               .append("    <variable id=\"input_variable_").append(c).append("\" name=\"").append(attribute(input.name()))
               .append("\" typeRef=\"").append(typeRef(input.type())).append("\"/>\n")
               .append("  </inputData>\n");
        }
        xml.append("  <decision id=\"decision_").append(id).append("\" name=\"").append(attribute(table.name())).append("\">\n")
           .append("    <variable id=\"decision_variable\" name=\"").append(attribute(table.name())).append("\"/>\n");
        for (int c = 0; c < inputs.size(); c++) {
            xml.append("    <informationRequirement id=\"requirement_").append(c).append("\">")
               .append("<requiredInput href=\"#input_data_").append(c).append("\"/></informationRequirement>\n");
        }
        xml.append("    <decisionTable id=\"table\" hitPolicy=\"").append(hitPolicy(table.hitPolicy())).append('"');
        if (table.aggregation() != null) {
            xml.append(" aggregation=\"").append(table.aggregation().name()).append('"');
        }
        xml.append(">\n");
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            xml.append("      <input id=\"input_").append(c).append("\" label=\"").append(attribute(input.name())).append("\">\n")
               .append("        <inputExpression id=\"input_expression_").append(c).append("\" typeRef=\"").append(typeRef(input.type()))
               .append("\"><text>").append(text(input.name())).append("</text></inputExpression>\n");
            if (input.allowedValues() != null) {
                xml.append("        <inputValues id=\"input_values_").append(c).append("\"><text>")
                   .append(text(unaryTests(input.allowedValues(), input.type(), "input values of column '" + input.name() + "'")))
                   .append("</text></inputValues>\n");
            }
            xml.append("      </input>\n");
        }
        var outputs = table.outputs();
        for (int c = 0; c < outputs.size(); c++) {
            var output = outputs.get(c);
            xml.append("      <output id=\"output_").append(c).append("\" name=\"").append(attribute(output.name()))
               .append("\" typeRef=\"").append(typeRef(output.type())).append('"');
            if (output.values().isEmpty()) {
                xml.append("/>\n");
            } else {
                xml.append(">\n        <outputValues id=\"output_values_").append(c).append("\"><text>")
                   .append(text(output.values().stream().map(value -> literal(value, output.type(), "output values of '" + output.name() + "'"))
                           .collect(Collectors.joining(", "))))
                   .append("</text></outputValues>\n      </output>\n");
            }
        }
        int r = 0;
        for (var rule : table.rules()) {
            var where = "Rule " + rule.priority();
            xml.append("      <rule id=\"rule_").append(r).append("\">\n");
            for (int c = 0; c < inputs.size(); c++) {
                var input = inputs.get(c);
                xml.append("        <inputEntry id=\"input_entry_").append(r).append('_').append(c).append("\"><text>")
                   .append(text(unaryTests(rule.input().values().get(input.name()), input.type(), where + ", column '" + input.name() + "'")))
                   .append("</text></inputEntry>\n");
            }
            for (int c = 0; c < outputs.size(); c++) {
                var output = outputs.get(c);
                xml.append("        <outputEntry id=\"output_entry_").append(r).append('_').append(c).append("\"><text>")
                   .append(text(literal(rule.output().values().get(output.name()), output.type(), where + ", output '" + output.name() + "'")))
                   .append("</text></outputEntry>\n");
            }
            xml.append("      </rule>\n");
            r++;
        }
        xml.append("    </decisionTable>\n  </decision>\n</definitions>\n");
        return xml.toString();
    }

    static String typeRef(ColumnType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMBER -> "number";
            case DATE -> "date";
            case BOOLEAN -> "boolean";
            case TIME -> "time";
            case DATE_TIME -> "dateTime";
        };
    }

    static String hitPolicy(HitPolicy policy) {
        return policy.name().replace('_', ' ');
    }

    private static String unaryTests(String cell, ColumnType type, String where) {
        try {
            return render(CellParser.parse(cell, type));
        } catch (CellSyntaxException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /** Standard FEEL text for parsed unary tests. */
    public static String render(UnaryTests tests) {
        return switch (tests) {
            case UnaryTests.Any any -> "-";
            case UnaryTests.Positive positive -> join(positive.tests());
            case UnaryTests.Negated negated -> "not(" + join(negated.tests()) + ")";
        };
    }

    private static String join(List<Test> tests) {
        return tests.stream().map(DmnWriter::test).collect(Collectors.joining(", "));
    }

    private static String test(Test test) {
        return switch (test) {
            case NullTest n -> "null";
            case Equals e -> literal(e.value());
            case Compare c -> c.operator().symbol() + " " + literal(c.value());
            case Range r -> (r.lowClosed() ? "[" : "(") + literal(r.low()) + ".." + literal(r.high()) + (r.highClosed() ? "]" : ")");
        };
    }

    /** An output value (plain text) as a FEEL literal of its column type. */
    private static String literal(String value, ColumnType type, String where) {
        if (value == null) {
            return "null";
        }
        try {
            return switch (type) {
                case STRING -> literal(value);
                case NUMBER -> new BigDecimal(value.strip()).toPlainString();
                case DATE -> literal(LocalDate.parse(value.strip()));
                case TIME -> literal(LocalTime.parse(value.strip()));
                case DATE_TIME -> literal(LocalDateTime.parse(value.strip()));
                case BOOLEAN -> switch (value.strip()) {
                    case "true", "false" -> value.strip();
                    default -> throw new IllegalArgumentException(value);
                };
            };
        } catch (RuntimeException e) {
            throw new InvalidDecisionTableException("%s: '%s' is not a %s".formatted(where, value,
                    type.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
        }
    }

    static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case BigDecimal number -> number.toPlainString();
            case LocalDate date -> "date(\"" + date + "\")";
            case LocalTime time -> "time(\"" + DateTimeFormatter.ISO_LOCAL_TIME.format(time) + "\")";
            case LocalDateTime dateTime -> "date and time(\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime) + "\")";
            case Boolean bool -> bool.toString();
            default -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        };
    }

    private static String identifier(String name) {
        var id = name.replaceAll("[^A-Za-z0-9_]", "_");
        return id.isEmpty() || !Character.isLetter(id.charAt(0)) ? "d_" + id : id;
    }

    private static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String attribute(String value) {
        return text(value).replace("\"", "&quot;");
    }
}
