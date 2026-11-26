/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import io.github.brunopacheco1.praecepta.engine.beans.DecisionLogic;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionModel;

/**
 * Composes a boxed expression (DMN 1.5, 10.2) into the FEEL tree it boxes, so that it is evaluated, typed and limited
 * as any FEEL expression is: a context into a context (with its final result evaluated in the scope of its entries), a
 * relation into a list of contexts, a list into a list, a conditional into {@code if}, an iterator into {@code for},
 * {@code some} or {@code every}, a filter into a filter, a function definition into a function, an invocation into an
 * invocation by named parameters. Each literal expression within is parsed with the names its enclosing constructs bind
 * in scope (DMN 1.5, 10.3.2.11), beside the scope it is composed in; a decision table within is an
 * {@linkplain FeelExpression.Embedded embedded} expression, which the caller provides. A part whose model declares a
 * type has its value bound to it (10.3.2.9.4).
 */
final class BoxedComposer {

    private final FeelExpression.Scope scope;
    private final Function<FeelExpression.TableSite, FeelExpression.Embedded> tables;
    private final Set<String> free = new LinkedHashSet<>();
    private final Set<FeelExpression.Unsupported> unsupported = new LinkedHashSet<>();
    private final Set<String> uninvocable = new LinkedHashSet<>();

    private BoxedComposer(FeelExpression.Scope scope, Function<FeelExpression.TableSite, FeelExpression.Embedded> tables) {
        this.scope = scope;
        this.tables = tables;
    }

    /**
     * The FEEL tree of a boxed expression.
     *
     * @throws ExpressionSyntaxException if a literal expression within is not FEEL, saying where it is, or the tree
     *                                   nests too deeply
     */
    static FeelParser.Parsed compose(DecisionLogic logic, FeelExpression.Scope scope,
            Function<FeelExpression.TableSite, FeelExpression.Embedded> tables) {
        var composer = new BoxedComposer(scope, tables);
        var root = composer.node(logic, List.of(), false, "");
        int depth = FeelParser.depthOf(root);
        if (depth > FeelParser.MAX_DEPTH) {
            throw new ExpressionSyntaxException("the boxed expression nests more than %d levels".formatted(FeelParser.MAX_DEPTH));
        }
        return new FeelParser.Parsed(root, List.copyOf(composer.free), List.copyOf(composer.unsupported), depth,
                List.copyOf(composer.uninvocable));
    }

    /**
     * A part's tree, its value bound to the type its model declares for it.
     *
     * @param locals the names the enclosing constructs bind, innermost last
     * @param filter whether it is a filter's match, whose names in no scope may name an entry of the element tested
     * @param where  where it is, for messages: empty for the root, else {@code " in ..."}
     */
    private Node node(DecisionLogic logic, List<String> locals, boolean filter, String where) {
        var node = unbound(logic, locals, filter, where);
        var type = logic.type();
        if (where.isEmpty() || type instanceof FeelType.Any || logic instanceof DecisionLogic.FunctionDefinition
                || type instanceof FeelType.FunctionOf) {
            return node; // the root is bound by its decision, a function by its invocation
        }
        return new Node.Bound(node, type);
    }

    /** A part's tree, by its kind. */
    private Node unbound(DecisionLogic logic, List<String> locals, boolean filter, String where) {
        return switch (logic) {
            case DecisionLogic.LiteralExpression literal -> literal(literal.text(), locals, filter, where);
            case DecisionLogic.Table table -> table(table, locals, where);
            case DecisionLogic.Context context -> context(context, locals, where);
            case DecisionLogic.Invocation invocation -> invocation(invocation, locals, where);
            case DecisionLogic.FunctionDefinition function -> function(function, locals, where);
            case DecisionLogic.Relation relation -> relation(relation, locals, where);
            case DecisionLogic.BoxedList list -> boxedList(list, locals, where);
            case DecisionLogic.Conditional(var condition, var then, var otherwise, var type) -> new Node.Checked(new Node.If(
                    new Node.Strict(node(condition, locals, false, where + " in the conditional's if")),
                    node(then, locals, false, where + " in the conditional's then"),
                    node(otherwise, locals, false, where + " in the conditional's else")));
            case DecisionLogic.Iteration iteration -> iteration(iteration, locals, where);
            case DecisionLogic.NoLogic none -> throw new ExpressionSyntaxException((where.isEmpty() ? "" : where.substring(1) + ": ")
                    + "a decision without logic has no expression");
            case DecisionLogic.Filter(var list, var match, var type) -> boxedFilter(list, match, locals, where);
        };
    }

    /** A context: each entry in the scope of those before it and of itself, its final result, if any, in the scope of them all. */
    private Node context(DecisionLogic.Context context, List<String> locals, String where) {
        var inner = new ArrayList<>(locals);
        var keys = new ArrayList<String>();
        var values = new ArrayList<Node>();
        for (var entry : context.entries()) {
            if (!inner.contains(entry.name()) && scope.given.contains(entry.name())) {
                // evaluated before it is bound, its value reads the name it hides; a function it defines, itself
                free.add(entry.name());
            }
            inner.add(entry.name()); // in the scope of itself, so that a function it defines may invoke itself
            keys.add(entry.name());
            values.add(node(entry.value(), List.copyOf(inner), false, where + " in context entry '" + entry.name() + "'"));
        }
        if (context.result() == null) {
            return new Node.ContextLiteral(keys, values);
        }
        return new Node.ContextResult(keys, values, node(context.result(), List.copyOf(inner), false, where + " in the context's result"));
    }

    /** An invocation by named parameters; a binding without a value gives its parameter null. */
    private Node invocation(DecisionLogic.Invocation invocation, List<String> locals, String where) {
        var callee = literal(invocation.function(), locals, false, where + " in the invoked function");
        var names = new ArrayList<String>();
        var arguments = new ArrayList<Node>();
        for (var binding : invocation.bindings()) {
            names.add(binding.parameter());
            arguments.add(binding.value() == null ? new Node.Constant(null)
                    : node(binding.value(), locals, false, where + " in the binding of '" + binding.parameter() + "'"));
        }
        return new Node.Invocation(callee, arguments, names);
    }

    /** A function definition: its body in the scope of its parameters. */
    private Node function(DecisionLogic.FunctionDefinition function, List<String> locals, String where) {
        var inner = new ArrayList<>(locals);
        var names = new ArrayList<String>();
        var types = new ArrayList<FeelType>();
        for (var parameter : function.parameters()) {
            inner.add(parameter.name());
            names.add(parameter.name());
            types.add(parameter.type());
        }
        var bodyNode = node(function.body(), List.copyOf(inner), false, where + " in the function's body");
        if (function.type() instanceof FeelType.FunctionOf(var declared, var result) && !(result instanceof FeelType.Any)) {
            // its value is bound to the output type of the function type declared for it, as a decision
            // service's is (the TCK's 1157-implicit-conversions: a number into a list of one)
            bodyNode = new Node.Bound(bodyNode, result);
        }
        return new Node.FunctionDefinition(names, types, bodyNode, FeelParser.depthOf(bodyNode));
    }

    /** A relation: a list of contexts, one per row, each cell bound to the type its column declares unless it declares its own. */
    private Node relation(DecisionLogic.Relation relation, List<String> locals, String where) {
        var columns = relation.columns();
        var rows = relation.rows();
        var keys = columns.stream().map(DecisionLogic.Parameter::name).toList();
        var list = new ArrayList<Node>();
        for (int r = 0; r < rows.size(); r++) {
            var cells = new ArrayList<Node>();
            for (int c = 0; c < columns.size(); c++) {
                var cell = rows.get(r).get(c);
                var at = where + " in row %d, column '%s' of the relation".formatted(r + 1, keys.get(c));
                var node = node(cell, locals, false, at);
                var declared = columns.get(c).type();
                cells.add(declared instanceof FeelType.Any || !cell.type().equals(FeelType.ANY) ? node : new Node.Bound(node, declared));
            }
            list.add(new Node.Row(keys, cells));
        }
        return new Node.ListLiteral(list);
    }

    private Node boxedList(DecisionLogic.BoxedList boxed, List<String> locals, String where) {
        var elements = boxed.elements();
        var list = new ArrayList<Node>();
        for (int i = 0; i < elements.size(); i++) {
            list.add(node(elements.get(i), locals, false, where + " in element %d of the list".formatted(i + 1)));
        }
        return new Node.ListLiteral(list);
    }

    /** An iterator: {@code for}, whose return is in the scope of its variable and {@code partial}, or {@code some} or {@code every}. */
    private Node iteration(DecisionLogic.Iteration iteration, List<String> locals, String where) {
        var kind = iteration.kind();
        var variable = iteration.variable();
        var body = iteration.body();
        var domainNode = node(iteration.domain(), locals, false, where + " in the iterator's in");
        var inner = new ArrayList<>(locals);
        inner.add(variable);
        if (kind == DecisionLogic.Iteration.Kind.FOR) {
            inner.add("partial");
            var returned = node(body, List.copyOf(inner), false, where + " in the iterator's return");
            return new Node.For(List.of(variable), List.of(domainNode), returned);
        }
        var condition = new Node.Strict(node(body, List.copyOf(inner), false, where + " in the iterator's satisfies"));
        return new Node.Checked(new Node.Quantified(kind == DecisionLogic.Iteration.Kind.EVERY, List.of(variable), List.of(domainNode),
                condition));
    }

    /** A filter: its match in the scope of {@code item}, the element tested. */
    private Node boxedFilter(DecisionLogic list, DecisionLogic match, List<String> locals, String where) {
        var target = node(list, locals, false, where + " in the filter's in");
        var inner = new ArrayList<>(locals);
        inner.add("item");
        return new Node.Checked(new Node.Filter(target, new Node.Strict(node(match, List.copyOf(inner), true,
                where + " in the filter's match"))));
    }

    /** A literal expression, parsed with the enclosing constructs' names in scope; its free names are the composition's. */
    private Node literal(String text, List<String> locals, boolean filter, String where) {
        FeelParser.Parsed parsed;
        try {
            parsed = FeelParser.parse(text, scope.names(), scope.types, locals, filter);
        } catch (ExpressionSyntaxException e) {
            throw new ExpressionSyntaxException(where.isEmpty() ? e.getMessage() : where.substring(1) + ": " + e.getMessage());
        }
        free.addAll(parsed.names());
        unsupported.addAll(parsed.unsupported());
        uninvocable.addAll(parsed.uninvocable());
        return parsed.root();
    }

    /** A decision table within the boxed expression, as the caller embeds it; the names it reads that are not bound within are free. */
    private Node table(DecisionLogic.Table table, List<String> locals, String where) {
        if (tables == null) {
            throw new ExpressionSyntaxException((where.isEmpty() ? "" : where.substring(1) + ": ")
                    + "a decision table within a boxed expression is evaluated only within a decision model");
        }
        var embedded = tables.apply(new FeelExpression.TableSite(table, scope, locals));
        for (var name : embedded.names()) {
            if (DecisionModel.elementOf(name, locals::contains) == null) {
                free.add(name);
            }
        }
        return new Node.Embedded(embedded);
    }

    /** A short description of a boxed expression, by its kind, as {@link FeelExpression#text()} gives it. */
    static String describe(DecisionLogic logic) {
        return switch (logic) {
            case DecisionLogic.LiteralExpression literal -> literal.text().strip();
            case DecisionLogic.Table table -> "decision table '%s'".formatted(table.table().name());
            case DecisionLogic.Context context -> "boxed context";
            case DecisionLogic.Invocation invocation -> "invocation of '%s'".formatted(invocation.function().strip());
            case DecisionLogic.FunctionDefinition function -> "function definition";
            case DecisionLogic.Relation relation -> "relation";
            case DecisionLogic.BoxedList list -> "boxed list";
            case DecisionLogic.Conditional conditional -> "boxed conditional";
            case DecisionLogic.Iteration iteration -> "boxed " + iteration.kind().name().toLowerCase(Locale.ROOT);
            case DecisionLogic.Filter filter -> "boxed filter";
            case DecisionLogic.NoLogic none -> "no decision logic";
        };
    }
}
