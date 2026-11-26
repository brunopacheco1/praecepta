/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.attribute;
import static io.github.brunopacheco1.praecepta.engine.dmn.XmlText.escapeText;

import java.util.List;
import java.util.Locale;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;

/**
 * Writes a boxed expression (DMN 1.5, 10.2), part by part: each part with its type as its {@code typeRef}, which
 * {@link BoxedTypes} has declared, a literal expression as written, and a decision table as {@link TableWriter} writes it.
 */
final class ExpressionWriter {

    private ExpressionWriter() {
    }

    /** Whether a decision's logic holds a boxed expression that DMN 1.3 does not have: a conditional, an iterator or a filter. */
    static boolean needsDmn15(DecisionLogic logic) {
        return switch (logic) {
            case DecisionLogic.Conditional c -> true;
            case DecisionLogic.Iteration i -> true;
            case DecisionLogic.Filter f -> true;
            case DecisionLogic.Context(var entries, var result, var type) ->
                    entries.stream().anyMatch(entry -> needsDmn15(entry.value())) || result != null && needsDmn15(result);
            case DecisionLogic.Invocation(var function, var bindings, var type) ->
                    bindings.stream().anyMatch(binding -> binding.value() != null && needsDmn15(binding.value()));
            case DecisionLogic.FunctionDefinition(var parameters, var body, var type) -> needsDmn15(body);
            case DecisionLogic.Relation(var columns, var rows, var type) ->
                    rows.stream().flatMap(List::stream).anyMatch(ExpressionWriter::needsDmn15);
            case DecisionLogic.BoxedList(var elements, var type) -> elements.stream().anyMatch(ExpressionWriter::needsDmn15);
            default -> false;
        };
    }

    /**
     * A boxed expression, or a part of one, at {@code indent}, with its type as {@code typeRef} unless {@code typeRef}
     * is given in its place (null: none, a decision's or a context entry's type being its variable's).
     */
    static void boxed(StringBuilder xml, DecisionLogic logic, String typeRef, String indent, BoxedTypes types) {
        var type = typeRef == null ? "" : typeRef;
        var inner = indent + "  ";
        switch (logic) {
            case DecisionLogic.LiteralExpression literal -> xml.append(indent).append("<literalExpression").append(type).append("><text>")
                    .append(escapeText(literal.text())).append("</text></literalExpression>\n");
            case DecisionLogic.Table(var table, var tableType) -> TableWriter.decisionTable(xml, table, types.prefix(table));
            case DecisionLogic.Context context -> context(xml, context, type, indent, types);
            case DecisionLogic.Invocation invocation -> invocation(xml, invocation, type, indent, types);
            case DecisionLogic.FunctionDefinition function -> functionDefinition(xml, "functionDefinition", function, type, indent, types);
            case DecisionLogic.Relation relation -> relation(xml, relation, type, indent, types);
            case DecisionLogic.BoxedList(var elements, var listType) -> {
                xml.append(indent).append("<list").append(type).append(">\n");
                elements.forEach(element -> boxed(xml, element, types.attributeOf(element.type()), inner, types));
                xml.append(indent).append("</list>\n");
            }
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var conditionalType) -> {
                xml.append(indent).append("<conditional").append(type).append(">\n");
                part(xml, "if", condition, inner, types);
                part(xml, "then", then, inner, types);
                part(xml, "else", otherwise, inner, types);
                xml.append(indent).append("</conditional>\n");
            }
            case DecisionLogic.Iteration iteration -> iteration(xml, iteration, type, indent, types);
            case DecisionLogic.Filter(var list, var match, var filterType) -> {
                xml.append(indent).append("<filter").append(type).append(">\n");
                part(xml, "in", list, inner, types);
                part(xml, "match", match, inner, types);
                xml.append(indent).append("</filter>\n");
            }
            case DecisionLogic.NoLogic none -> {
                // a decision without logic holds no expression
            }
        }
    }

    /** A boxed context: an entry per variable, typed on its variable, then its result, the entry without a variable. */
    private static void context(StringBuilder xml, DecisionLogic.Context context, String type, String indent, BoxedTypes types) {
        var inner = indent + "  ";
        xml.append(indent).append("<context").append(type).append(">\n");
        for (var entry : context.entries()) {
            xml.append(inner).append("<contextEntry>\n").append(inner).append("  <variable").append(attribute("name", entry.name()))
               .append(types.attributeOf(entry.value().type())).append("/>\n");
            boxed(xml, entry.value(), "", inner + "  ", types);
            xml.append(inner).append("</contextEntry>\n");
        }
        var result = context.result();
        if (result != null) {
            xml.append(inner).append("<contextEntry>\n");
            boxed(xml, result, types.attributeOf(result.type()), inner + "  ", types);
            xml.append(inner).append("</contextEntry>\n");
        }
        xml.append(indent).append("</context>\n");
    }

    /** A boxed invocation: the function it invokes, as a literal expression, then a binding per parameter, typed on the parameter. */
    private static void invocation(StringBuilder xml, DecisionLogic.Invocation invocation, String type, String indent, BoxedTypes types) {
        var inner = indent + "  ";
        xml.append(indent).append("<invocation").append(type).append(">\n").append(inner).append("<literalExpression><text>")
           .append(escapeText(invocation.function())).append("</text></literalExpression>\n");
        for (var binding : invocation.bindings()) {
            xml.append(inner).append("<binding>\n").append(inner).append("  <parameter").append(attribute("name", binding.parameter()))
               .append(binding.value() == null ? "" : types.attributeOf(binding.value().type())).append("/>\n");
            if (binding.value() != null) {
                boxed(xml, binding.value(), "", inner + "  ", types);
            }
            xml.append(inner).append("</binding>\n");
        }
        xml.append(indent).append("</invocation>\n");
    }

    /** A relation: its columns, each with its type, then its rows of cells. */
    private static void relation(StringBuilder xml, DecisionLogic.Relation relation, String type, String indent, BoxedTypes types) {
        var inner = indent + "  ";
        xml.append(indent).append("<relation").append(type).append(">\n");
        for (var column : relation.columns()) {
            xml.append(inner).append("<column").append(attribute("name", column.name())).append(types.attributeOf(column.type()))
               .append("/>\n");
        }
        for (var row : relation.rows()) {
            xml.append(inner).append("<row>\n");
            row.forEach(cell -> boxed(xml, cell, types.attributeOf(cell.type()), inner + "  ", types));
            xml.append(inner).append("</row>\n");
        }
        xml.append(indent).append("</relation>\n");
    }

    /** A boxed iterator ({@code for}, {@code some} or {@code every}): its variable, its domain and its body. */
    private static void iteration(StringBuilder xml, DecisionLogic.Iteration iteration, String type, String indent, BoxedTypes types) {
        var inner = indent + "  ";
        var tag = iteration.kind().name().toLowerCase(Locale.ROOT);
        xml.append(indent).append('<').append(tag).append(type).append(attribute("iteratorVariable", iteration.variable())).append(">\n");
        part(xml, "in", iteration.domain(), inner, types);
        part(xml, iteration.kind() == DecisionLogic.Iteration.Kind.FOR ? "return" : "satisfies", iteration.body(), inner, types);
        xml.append(indent).append("</").append(tag).append(">\n");
    }

    /** A function definition, as {@code tag}: its formal parameters and its body. */
    static void functionDefinition(StringBuilder xml, String tag, DecisionLogic.FunctionDefinition function, String type,
            String indent, BoxedTypes types) {
        var inner = indent + "  ";
        xml.append(indent).append('<').append(tag).append(type).append(">\n");
        for (var parameter : function.parameters()) {
            xml.append(inner).append("<formalParameter").append(attribute("name", parameter.name())).append(types.attributeOf(parameter.type()))
               .append("/>\n");
        }
        boxed(xml, function.body(), types.attributeOf(function.body().type()), inner, types);
        xml.append(indent).append("</").append(tag).append(">\n");
    }

    /** A child element of a boxed expression holding one expression: {@code <if>}, {@code <in>}, {@code <return>}... */
    private static void part(StringBuilder xml, String tag, DecisionLogic logic, String indent, BoxedTypes types) {
        xml.append(indent).append('<').append(tag).append(">\n");
        boxed(xml, logic, types.attributeOf(logic.type()), indent + "  ", types);
        xml.append(indent).append("</").append(tag).append(">\n");
    }
}
