package io.github.brunopacheco1.praecepta.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Test;

/**
 * Renders a {@link Table} as a DMN 1.3 decision table understood by both Camunda and Drools.
 * Every cell is parsed with Praecepta's own {@link CellParser} and re-rendered as standard FEEL
 * (quoted strings, plain numbers, {@code date("...")}), so the engines evaluate the same tests.
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
        var inputs = table.inputs();
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            xml.append("  <inputData id=\"data_").append(input).append("\" name=\"").append(input).append("\">\n")
               .append("    <variable id=\"var_").append(input).append("\" name=\"").append(input)
               .append("\" typeRef=\"").append(typeRef(table.types().get(c))).append("\"/>\n")
               .append("  </inputData>\n");
        }
        xml.append("  <decision id=\"").append(DECISION).append("\" name=\"").append(DECISION).append("\">\n")
           .append("    <variable id=\"var_").append(DECISION).append("\" name=\"").append(DECISION).append("\"/>\n");
        for (var input : inputs) {
            xml.append("    <informationRequirement id=\"req_").append(input).append("\">")
               .append("<requiredInput href=\"#data_").append(input).append("\"/></informationRequirement>\n");
        }
        xml.append("    <decisionTable id=\"table\" hitPolicy=\"").append(table.hitPolicy().name()).append("\">\n");
        for (int c = 0; c < inputs.size(); c++) {
            var input = inputs.get(c);
            var type = table.types().get(c);
            xml.append("      <input id=\"in_").append(input).append("\" label=\"").append(input).append("\">")
               .append("<inputExpression id=\"expr_").append(input).append("\" typeRef=\"").append(typeRef(type))
               .append("\"><text>").append(input).append("</text></inputExpression></input>\n");
        }
        for (var output : table.outputs()) {
            xml.append("      <output id=\"out_").append(output).append("\" name=\"").append(output)
               .append("\" typeRef=\"string\"/>\n");
        }
        int ruleIndex = 0;
        for (Praeceptum rule : table.rules()) {
            xml.append("      <rule id=\"rule_").append(ruleIndex).append("\">\n");
            int cell = 0;
            for (int c = 0; c < inputs.size(); c++) {
                var text = rule.input().values().get(inputs.get(c));
                xml.append("        <inputEntry id=\"ie_").append(ruleIndex).append('_').append(cell++).append("\"><text>")
                   .append(xmlEscape(unaryTests(CellParser.parse(text, table.types().get(c)))))
                   .append("</text></inputEntry>\n");
            }
            for (var output : table.outputs()) {
                xml.append("        <outputEntry id=\"oe_").append(ruleIndex).append('_').append(cell++).append("\"><text>")
                   .append(xmlEscape(literal(rule.output().values().get(output)))).append("</text></outputEntry>\n");
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

    private static String typeRef(ColumnType type) {
        return switch (type) {
            case STRING -> "string";
            case NUMBER -> "number";
            case DATE -> "date";
        };
    }

    /** Standard FEEL text for a parsed cell. */
    static String unaryTests(UnaryTests cell) {
        return switch (cell) {
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
            case Range r -> (r.lowClosed() ? "[" : "(") + literal(r.low()) + ".." + literal(r.high())
                    + (r.highClosed() ? "]" : ")");
        };
    }

    private static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case BigDecimal number -> number.toPlainString();
            case LocalDate date -> "date(\"" + date + "\")";
            default -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        };
    }

    private static String xmlEscape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
