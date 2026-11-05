package io.github.brunopacheco1.praecepta.benchmarks;

import java.util.Arrays;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;

/**
 * Renders a {@link Table} as a DMN 1.3 decision table understood by both Camunda and Drools.
 * Cell semantics mirror Praecepta: blank means "any" ({@code -}), {@code a,b} means "a or b".
 */
public final class DmnWriter {

    public static final String DECISION = "decision_table";

    private DmnWriter() {
    }

    public static String write(Table table) {
        var xml = new StringBuilder();
        xml.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             id="praecepta_benchmark" name="praecepta_benchmark"
                             namespace="https://github.com/brunopacheco1/praecepta/benchmark">
                """);
        for (var input : table.inputs()) {
            xml.append("  <inputData id=\"data_").append(input).append("\" name=\"").append(input).append("\">\n")
               .append("    <variable id=\"var_").append(input).append("\" name=\"").append(input).append("\" typeRef=\"string\"/>\n")
               .append("  </inputData>\n");
        }
        xml.append("  <decision id=\"").append(DECISION).append("\" name=\"").append(DECISION).append("\">\n")
           .append("    <variable id=\"var_").append(DECISION).append("\" name=\"").append(DECISION).append("\"/>\n");
        for (var input : table.inputs()) {
            xml.append("    <informationRequirement id=\"req_").append(input).append("\">")
               .append("<requiredInput href=\"#data_").append(input).append("\"/></informationRequirement>\n");
        }
        xml.append("    <decisionTable id=\"table\" hitPolicy=\"").append(table.hitPolicy().name()).append("\">\n");
        for (var input : table.inputs()) {
            xml.append("      <input id=\"in_").append(input).append("\" label=\"").append(input).append("\">")
               .append("<inputExpression id=\"expr_").append(input).append("\" typeRef=\"string\"><text>")
               .append(input).append("</text></inputExpression></input>\n");
        }
        for (var output : table.outputs()) {
            xml.append("      <output id=\"out_").append(output).append("\" name=\"").append(output)
               .append("\" typeRef=\"string\"/>\n");
        }
        int ruleIndex = 0;
        for (Praeceptum rule : table.rules()) {
            xml.append("      <rule id=\"rule_").append(ruleIndex).append("\">\n");
            int cell = 0;
            for (var input : table.inputs()) {
                xml.append("        <inputEntry id=\"ie_").append(ruleIndex).append('_').append(cell++).append("\"><text>")
                   .append(unaryTest(rule.input().values().get(input))).append("</text></inputEntry>\n");
            }
            for (var output : table.outputs()) {
                xml.append("        <outputEntry id=\"oe_").append(ruleIndex).append('_').append(cell++).append("\"><text>")
                   .append(literal(rule.output().values().get(output))).append("</text></outputEntry>\n");
            }
            xml.append("      </rule>\n");
            ruleIndex++;
        }
        xml.append("""
                    </decisionTable>
                  </decision>
                </definitions>
                """);
        return xml.toString();
    }

    private static String unaryTest(String cell) {
        if (cell == null || cell.isBlank()) {
            return "-";
        }
        return Arrays.stream(cell.split(","))
                .map(DmnWriter::literal)
                .collect(Collectors.joining(","));
    }

    private static String literal(String value) {
        if (value == null) {
            return "null";
        }
        var escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "\"" + escaped + "\"";
    }
}
