/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION_TABLE;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.VARIABLE;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.child;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.textOf;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;

/**
 * Reads the expression that is the logic of a decision or of a business knowledge model, beside a decision table: a
 * literal expression, or a boxed expression (DMN 1.5, 10.2: a context, an invocation, a function definition, a relation,
 * a list, a conditional, an iterator, a filter), part by part. A part is an expression in turn: a literal expression, a
 * decision table or a boxed expression.
 */
final class ExpressionReader {

    /** The kinds of boxed expression (DMN 1.5, 10.2) beside decision tables and literal expressions. */
    private static final Set<String> BOXED_EXPRESSIONS =
            Set.of("context", "invocation", "functionDefinition", "relation", "list", "conditional", "for", "every", "some", "filter");

    private ExpressionReader() {
    }

    /**
     * Where a part of a boxed expression is read: the model's item definitions and the types of its elements, the names
     * its enclosing constructs bind, with their declared types (a context's entries, a function's parameters, an
     * iterator's variable), which a decision table within reads and types its columns by, and the name such a table takes.
     */
    record Site(ItemDefinitions types, ModelTypes model, Map<String, FeelType> locals, String name) {

        /** The site within a construct that binds {@code local}, of {@code type}. */
        Site with(String local, FeelType type) {
            var inner = new LinkedHashMap<>(locals);
            inner.put(local, type);
            return new Site(types, model, inner, name);
        }

        /** The site where a table takes {@code tableName}. */
        Site named(String tableName) {
            return new Site(types, model, locals, tableName);
        }
    }

    /** A literal expression, of the type its decision's variable (or the expression itself) declares. */
    static DecisionLogic literalExpression(Element decision, Element literal, ItemDefinitions types, String where) {
        checkLiteral(literal, where);
        var text = literalText(literal);
        if (text.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": its literal expression is empty");
        }
        // the value is bound to the decision's variable (DMN 1.5, 10.3.2.9.4), whose typeRef 7.3.1 requires the literal
        // expression's to equal; the literal expression's own is read only where the variable has none
        var typeRef = variableTypeRef(decision);
        if (typeRef.isBlank()) {
            typeRef = literal.getAttribute(TYPE_REF);
        }
        return new DecisionLogic.LiteralExpression(text, types.feelType(typeRef, where));
    }

    /** A boxed expression other than a decision table or a literal expression (DMN 1.5, 10.2), of the type {@code type}. */
    static DecisionLogic boxed(Element element, Site site, FeelType type, String where) {
        try {
            return switch (element.getLocalName()) {
                case "context" -> context(element, site, type, where);
                case "invocation" -> invocation(element, site, type, where);
                case "functionDefinition" -> functionDefinition(element, site, type, where);
                case "relation" -> relation(element, site, type, where);
                case "list" -> list(element, site, type, where);
                case "conditional" -> new DecisionLogic.Conditional(part(element, "if", site, where), part(element, "then", site, where),
                        part(element, "else", site, where), type);
                case "for" -> iteration(element, DecisionLogic.Iteration.Kind.FOR, "return", site, type, where);
                case "some" -> iteration(element, DecisionLogic.Iteration.Kind.SOME, "satisfies", site, type, where);
                case "every" -> iteration(element, DecisionLogic.Iteration.Kind.EVERY, "satisfies", site, type, where);
                case "filter" -> new DecisionLogic.Filter(part(element, "in", site, where),
                        part(element, "match", site.with("item", FeelType.ANY), where), type);
                default -> throw new InvalidDecisionTableException("%s: <%s> is not an expression".formatted(where, element.getLocalName()));
            };
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException(where + ": " + e.getMessage());
        }
    }

    /**
     * An expression within a boxed expression: a literal expression (an empty one is {@code null}), or a boxed
     * expression in turn, of the type {@code type}.
     */
    private static DecisionLogic expression(Element element, Site site, FeelType type, String where) {
        var kind = element.getLocalName();
        if (kind.equals("literalExpression")) {
            checkLiteral(element, where);
            var text = literalText(element);
            return new DecisionLogic.LiteralExpression(text.isEmpty() ? "null" : text, type);
        }
        if (kind.equals(DECISION_TABLE)) {
            // the table is named after what holds it: a context entry, a business knowledge model, else its decision
            var holder = (Element) element.getParentNode();
            var table = TableReader.decisionTable(holder, site.name(), where, site.types(), site.model().with(site.locals()),
                    site.locals().keySet());
            return new DecisionLogic.Table(table, type);
        }
        return boxed(element, site, type, where);
    }

    /** The expression a child element of a boxed expression holds ({@code <if>}, {@code <in>}...), which it must have. */
    private static DecisionLogic part(Element parent, String child, Site site, String where) {
        var element = child(parent, child);
        var expression = element == null ? null : expressionIn(element);
        if (expression == null) {
            throw new InvalidDecisionTableException("%s: its <%s> holds no expression".formatted(where, child));
        }
        return expression(expression, site, typeOf("", expression, site.types(), where), where);
    }

    /** A boxed context: its entries, each of its variable's type, and its final result, the entry without a variable. */
    private static DecisionLogic context(Element context, Site site, FeelType type, String where) {
        var entries = new ArrayList<DecisionLogic.ContextEntry>();
        DecisionLogic result = null;
        var all = children(context, "contextEntry");
        var scope = site;
        for (int i = 0; i < all.size(); i++) {
            var entry = all.get(i);
            var variable = child(entry, VARIABLE);
            var value = expressionIn(entry);
            if (variable == null) {
                if (i != all.size() - 1) {
                    throw new InvalidDecisionTableException(where + ": only a context's last entry may have no variable, as its result "
                            + "(DMN 1.5, 10.2.1)");
                }
                if (value == null) {
                    throw new InvalidDecisionTableException(where + ": the context's result holds no expression");
                }
                result = expression(value, scope.named(site.name()), typeOf("", value, site.types(), where), where + ", its result");
                continue;
            }
            var name = variable.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a context entry's variable has no name");
            }
            var at = "%s, context entry '%s'".formatted(where, name);
            var entryType = typeOf(variable.getAttribute(TYPE_REF), value, site.types(), at);
            // in the scope of itself, and of the entries before it, as a table within it reads them
            scope = scope.with(name, entryType).named(name);
            entries.add(new DecisionLogic.ContextEntry(name, value == null
                    ? new DecisionLogic.LiteralExpression("null", entryType) : expression(value, scope, entryType, at)));
        }
        return new DecisionLogic.Context(entries, result, type);
    }

    /** A boxed invocation: the function its first expression names, and its bindings. */
    private static DecisionLogic invocation(Element invocation, Site site, FeelType type, String where) {
        var function = expressionIn(invocation);
        if (function == null || !function.getLocalName().equals("literalExpression")) {
            throw new InvalidDecisionTableException(where + ": an invocation names the function it invokes with a literal expression");
        }
        checkLiteral(function, where);
        var name = literalText(function);
        if (name.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the invocation names no function");
        }
        var bindings = new ArrayList<DecisionLogic.Binding>();
        for (var binding : children(invocation, "binding")) {
            var parameter = child(binding, "parameter");
            var parameterName = parameter == null ? "" : parameter.getAttribute("name").strip();
            if (parameterName.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a binding of the invocation names no parameter");
            }
            var at = "%s, binding of '%s'".formatted(where, parameterName);
            var value = expressionIn(binding);
            bindings.add(new DecisionLogic.Binding(parameterName, value == null ? null
                    : expression(value, site, typeOf(parameter.getAttribute(TYPE_REF), value, site.types(), at), at)));
        }
        return new DecisionLogic.Invocation(name, bindings, type);
    }

    /**
     * A boxed function definition in FEEL: its formal parameters, of their declared types, and its body. One in Java or
     * PMML is an external function, which Praecepta does not evaluate, by design (docs/DMN-CONFORMANCE.md, known
     * deviations).
     */
    static DecisionLogic functionDefinition(Element function, Site site, FeelType type, String where) {
        var kind = function.getAttribute("kind").strip();
        if (!kind.isEmpty() && !kind.equals("FEEL")) {
            throw new UnsupportedFeatureException(("%s defines a function in %s, an external function, which Praecepta does not "
                    + "evaluate, by design: it runs no code from outside the model").formatted(where, kind));
        }
        var parameters = new ArrayList<DecisionLogic.Parameter>();
        for (var parameter : children(function, "formalParameter")) {
            var name = parameter.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a parameter of the function has no name");
            }
            var parameterType = site.types().feelType(parameter.getAttribute(TYPE_REF), where + ", parameter '" + name + "'");
            parameters.add(new DecisionLogic.Parameter(name, parameterType));
        }
        var body = expressionIn(function);
        if (body == null) {
            throw new InvalidDecisionTableException(where + ": the function definition has no body");
        }
        var at = where + ", the function's body";
        var inner = site;
        for (var parameter : parameters) {
            inner = inner.with(parameter.name(), parameter.type());
        }
        return new DecisionLogic.FunctionDefinition(parameters, expression(body, inner, typeOf("", body, site.types(), at), at), type);
    }

    /** A relation: its columns, each of its declared type, and its rows of expressions. */
    private static DecisionLogic relation(Element relation, Site site, FeelType type, String where) {
        var columns = new ArrayList<DecisionLogic.Parameter>();
        for (var column : children(relation, "column")) {
            var name = column.getAttribute("name").strip();
            if (name.isEmpty()) {
                throw new InvalidDecisionTableException(where + ": a column of the relation has no name");
            }
            var columnType = site.types().feelType(column.getAttribute(TYPE_REF), where + ", column '" + name + "'");
            columns.add(new DecisionLogic.Parameter(name, columnType));
        }
        var rows = new ArrayList<List<DecisionLogic>>();
        for (var row : children(relation, "row")) {
            var cells = new ArrayList<DecisionLogic>();
            for (var cell : expressionsIn(row)) {
                var at = "%s, row %d, column %d".formatted(where, rows.size() + 1, cells.size() + 1);
                cells.add(expression(cell, site, typeOf("", cell, site.types(), at), at));
            }
            rows.add(cells);
        }
        return new DecisionLogic.Relation(columns, rows, type);
    }

    /** A boxed list: its elements, each an expression. */
    private static DecisionLogic list(Element list, Site site, FeelType type, String where) {
        var elements = new ArrayList<DecisionLogic>();
        for (var item : expressionsIn(list)) {
            var at = "%s, element %d of its list".formatted(where, elements.size() + 1);
            elements.add(expression(item, site, typeOf("", item, site.types(), at), at));
        }
        return new DecisionLogic.BoxedList(elements, type);
    }

    /** A boxed iterator: its variable, the expression in {@code <in>} and the one in its {@code <return>} or {@code <satisfies>}. */
    private static DecisionLogic iteration(Element iterator, DecisionLogic.Iteration.Kind kind, String body, Site site,
            FeelType type, String where) {
        var variable = iterator.getAttribute("iteratorVariable").strip();
        if (variable.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the iterator has no iteratorVariable");
        }
        var inner = site.with(variable, FeelType.ANY);
        if (kind == DecisionLogic.Iteration.Kind.FOR) {
            inner = inner.with("partial", FeelType.ANY);
        }
        return new DecisionLogic.Iteration(kind, variable, part(iterator, "in", site, where), part(iterator, body, inner, where), type);
    }

    /**
     * The type a part of a boxed expression declares: {@code typeRef}, the one its variable declares (a context entry's,
     * a decision's), else the expression's own; any type if neither does.
     */
    static FeelType typeOf(String typeRef, Element expression, ItemDefinitions types, String where) {
        var ref = typeRef.isBlank() && expression != null ? expression.getAttribute(TYPE_REF) : typeRef;
        return types.feelType(ref, where);
    }

    /** Whether an element of that local name is a boxed expression. */
    static boolean isBoxed(String kind) {
        return BOXED_EXPRESSIONS.contains(kind);
    }

    /** Whether an element is an expression (DMN 1.5, 10.2) that a boxed expression may hold. */
    private static boolean isExpression(Element element) {
        var kind = element.getLocalName();
        return kind.equals("literalExpression") || kind.equals(DECISION_TABLE) || isBoxed(kind);
    }

    /** The expressions an element holds as its children (a list's elements, the cells of a relation's row), in order. */
    private static List<Element> expressionsIn(Element parent) {
        var expressions = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && isExpression((Element) node)) {
                expressions.add((Element) node);
            }
        }
        return expressions;
    }

    /** The expression an element holds as its only expression child ({@code <if>}, {@code <in>}), or null for none. */
    private static Element expressionIn(Element parent) {
        var expressions = expressionsIn(parent);
        return expressions.isEmpty() ? null : expressions.getFirst();
    }

    /** Rejects a literal expression in another language than FEEL, or one that imports values. */
    private static void checkLiteral(Element literal, String where) {
        var language = literal.getAttribute("expressionLanguage").strip();
        if (!language.isEmpty() && !language.toUpperCase(Locale.ROOT).contains("FEEL")) {
            throw new InvalidDecisionTableException("%s: its literal expression is in '%s'; Praecepta reads FEEL".formatted(where, language));
        }
        if (child(literal, "importedValues") != null) {
            throw new UnsupportedFeatureException(
                    "%s: its literal expression imports values (importedValues), which Praecepta does not read".formatted(where));
        }
    }

    /** The text of a literal expression, without the white space around it; empty if it has no {@code <text>}. */
    private static String literalText(Element literal) {
        return child(literal, "text") == null ? "" : textOf(literal).strip();
    }
}
