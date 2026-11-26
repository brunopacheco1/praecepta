/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.dmn;

import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.AGGREGATION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.COUNT;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DECISION_TABLE;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.DEFAULT_OUTPUT_ENTRY;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.LABEL;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.TYPE_REF;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.child;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.children;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.descendants;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nameOr;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.nonBlankText;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.textOf;
import static io.github.brunopacheco1.praecepta.engine.dmn.DmnXml.variableTypeRef;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import io.github.brunopacheco1.praecepta.engine.beans.AggregationOperator;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.InputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable.OutputColumn;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.dmn.ItemDefinitions.Type;
import io.github.brunopacheco1.praecepta.engine.dmn.Literals.LiteralOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.ModelTypes.Entries;
import io.github.brunopacheco1.praecepta.engine.dmn.RuleReader.Columns;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.UnsupportedFeatureException;
import io.github.brunopacheco1.praecepta.engine.feel.CellParser;
import io.github.brunopacheco1.praecepta.engine.feel.ExpressionSyntaxException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelExpression;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLiterals;
import io.github.brunopacheco1.praecepta.engine.feel.FeelUnaryTests;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * Reads one decision table: its hit policy and aggregation, its input columns and its output columns, with their types,
 * value lists and default values. {@link RuleReader} reads its rules.
 */
final class TableReader {

    private TableReader() {
    }

    /** The model's decisions whose logic is a decision table, in document order. */
    static List<Element> decisionsWithTables(Element root) {
        return descendants(root, DECISION).stream().filter(decision -> child(decision, DECISION_TABLE) != null).toList();
    }

    /** The named decision, or the model's only decision with a table if no name is given. */
    static Element decisionToRead(Element root, String decisionName) {
        var decisions = decisionsWithTables(root);
        var names = decisions.stream().map(decision -> decision.getAttribute("name")).toList();
        if (decisionName != null) {
            int at = names.indexOf(decisionName);
            if (at < 0) {
                throw new InvalidDecisionTableException("DMN model has no decision table named '%s'; it has %s".formatted(decisionName, names));
            }
            return decisions.get(at);
        }
        if (decisions.size() != 1) {
            throw new InvalidDecisionTableException(decisions.isEmpty()
                    ? "DMN model has no decision table"
                    : "DMN model has %d decision tables %s; name the one to read".formatted(decisions.size(), names));
        }
        return decisions.getFirst();
    }

    /**
     * The decision table of a decision, named after it.
     *
     * @param model the types of the model's input data and decisions, which type the columns and outputs that declare
     *              none, when the table is read as part of a model; null when it is read alone
     */
    static DecisionTable decisionTable(Element decision, ItemDefinitions types, ModelTypes model) {
        var name = nameOr(decision, "id");
        return decisionTable(decision, child(decision, DECISION_TABLE), name, decisionWhere(name), types, model, Set.of());
    }

    /**
     * A decision table, a child of {@code decision}: a decision, or what holds a table within a boxed expression (a
     * context entry, whose variable types it as a decision's does, a function definition, or a list or relation, which
     * may hold several tables).
     *
     * @param table  the table to read: its holder may hold others
     * @param name   the table's name, its single unnamed output's
     * @param locals the names the enclosing constructs of a boxed expression bind, which its FEEL may read beside the
     *               model's input data and decisions
     */
    static DecisionTable decisionTable(Element decision, Element table, String name, String where, ItemDefinitions types,
            ModelTypes model, Set<String> locals) {
        var hitPolicy = hitPolicy(table.getAttribute("hitPolicy"), where);
        var aggregation = aggregation(table.getAttribute(AGGREGATION), where);

        var lists = new HashSet<String>();
        var inputs = children(table, "input").stream().map(input -> inputColumn(input, types, model, lists, where)).toList();
        var outputElements = children(table, "output");
        if (outputElements.isEmpty()) {
            throw new InvalidDecisionTableException(where + ": the decision table has no output");
        }
        // a table read alone has no model, whose item definitions its FEEL could name
        var scope = new TableScope(types, inputs, model != null, locals);
        // a single unnamed output is the decision's result, named after the decision, and typed by it
        var single = outputElements.size() == 1;
        var unnamedOutput = single ? name : "";
        var ruleElements = children(table, "rule");
        var resultType = single ? resultType(decision, table, hitPolicy, aggregation, types, where) : ColumnType.STRING;
        var outputs = new ArrayList<OutputColumn>();
        for (int o = 0; o < outputElements.size(); o++) {
            var output = outputElements.get(o);
            var untypedAs = model == null ? Optional.of(resultType)
                    : model.untypedOutputType(decision, output, o, new Entries(ruleElements, inputs, lists, scope), types, where);
            outputs.add(outputColumn(output, unnamedOutput, untypedAs, types, scope, where));
        }

        var root = table.getOwnerDocument().getDocumentElement();
        var rules = RuleReader.rules(ruleElements, new Columns(inputs, outputs, lists, scope), root, types, where);
        return new DecisionTable(name, hitPolicy, aggregation, inputs, outputs, rules, true, lists);
    }

    /**
     * The names in scope for a table's entries: those of the model's input data and decisions, and of the table's inputs
     * that are names, found and indexed when an entry first needs them, so a table whose entries are all literals and
     * simple tests reads no names.
     */
    static final class TableScope {
        private final ItemDefinitions types;
        private final List<InputColumn> inputs;
        /** Whether the table is read as part of a model, whose item definitions its FEEL may name. */
        private final boolean model;
        /** The names a boxed expression around the table binds. */
        private final Set<String> locals;
        private FeelExpression.Scope scope;

        TableScope(ItemDefinitions types, List<InputColumn> inputs, boolean model, Set<String> locals) {
            this.types = types;
            this.inputs = inputs;
            this.model = model;
            this.locals = locals;
        }

        FeelExpression.Scope get() {
            if (scope == null) {
                var names = new LinkedHashSet<>(types.elementNames());
                names.addAll(locals);
                // a column that is an expression is no name: its text would read as itself
                inputs.stream().filter(input -> CellParser.isName(input.name())).forEach(input -> names.add(input.name()));
                scope = FeelExpression.scope(names, model ? types.all() : Map.of());
            }
            return scope;
        }
    }

    private static HitPolicy hitPolicy(String value, String where) {
        if (value.isBlank()) {
            return HitPolicy.UNIQUE; // the DMN default
        }
        try {
            return HitPolicy.valueOf(value.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new InvalidDecisionTableException("%s: unknown hit policy '%s'".formatted(where, value));
        }
    }

    private static AggregationOperator aggregation(String value, String where) {
        if (value.isBlank()) {
            return null;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "SUM" -> AggregationOperator.SUM;
            case "MIN" -> AggregationOperator.MIN;
            case "MAX" -> AggregationOperator.MAX;
            case COUNT -> AggregationOperator.COUNT;
            default -> throw new InvalidDecisionTableException(
                    "%s: aggregation '%s' is not supported (SUM, MIN, MAX and COUNT are)".formatted(where, value));
        };
    }

    /**
     * A column; one whose type is a collection is added to {@code lists}, and its allowed values (those of
     * its item type) apply to the list's elements. Its input expression is its name: a name of an input variable, or
     * any FEEL expression ({@code Age * 12}), whose value the engine computes from the inputs it reads, typed, where it
     * declares no type, as FEEL types it in a model; the names of the model's input data and decisions are in scope for
     * it.
     */
    private static InputColumn inputColumn(Element input, ItemDefinitions types, ModelTypes model, Set<String> lists,
            String where) {
        var expression = child(input, "inputExpression");
        var column = expression == null ? "" : textOf(expression).strip();
        if (column.isEmpty()) {
            column = input.getAttribute(LABEL).strip();
        }
        var computed = CellParser.isName(column) ? null : inputExpression(column, types.expressionScope(), where);
        var typeRef = expression == null ? "" : expression.getAttribute(TYPE_REF);
        var name = column;
        var type = typeRef.isBlank()
                ? untypedColumnType(column, computed, model, types).orElseGet(() -> types.resolve("", inputWhere(where, name)))
                : types.resolve(typeRef, inputWhere(where, column));
        var values = nonBlankText(input, "inputValues");
        if (type.list()) {
            if (values != null) {
                throw new InvalidDecisionTableException(("%s, input '%s': input values of a list are not supported; "
                        + "declare the allowed values of its items in their item definition").formatted(where, column));
            }
            lists.add(column);
        }
        if (values == null) {
            return new InputColumn(column, type.columnType(), type.allowedValues());
        }
        var allowed = values.strip();
        Literals.parseCell(allowed, type.columnType(), where + ", input values of '" + column + "'");
        return new InputColumn(column, type.columnType(), allowed);
    }

    /**
     * The type of a column whose input expression declares none: in a model, that of what it reads, or of its expression
     * as FEEL types it; in a table read alone, that of an expression, typed from the file's input data and decisions as
     * in a model, and none for a column that names what it reads; empty where nothing gives one.
     *
     * @param computed its input expression, if it is an expression rather than a name; else null
     */
    private static Optional<Type> untypedColumnType(String column, FeelExpression computed, ModelTypes model,
            ItemDefinitions types) {
        if (model != null) {
            return computed == null ? model.columnType(column) : model.expressionType(computed);
        }
        return computed == null ? Optional.empty()
                : types.modelTypes().expressionType(computed);
    }

    /**
     * An input expression that is not a name: a FEEL expression, checked.
     *
     * @throws UnsupportedFeatureException  if it defines an external function
     * @throws InvalidDecisionTableException if it is not FEEL
     */
    private static FeelExpression inputExpression(String text, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: input expression '%s' is neither a name nor a FEEL expression: %s"
                    .formatted(where, text, e.getMessage()));
        }
        rejectUnsupported(where + ", input expression '" + text + "'", expression.unsupported());
        return expression;
    }

    /**
     * @param untypedAs the type of an output whose {@code typeRef} is blank: a single output's {@link #resultType}, else a
     *                  string; for a model's table, the type the model gives it ({@link ModelTypes#untypedOutputType}),
     *                  empty for one whose entries each give a value of their own type, which is then not
     *                  {@linkplain OutputColumn#typed() typed}, unless it has an output value list
     */
    private static OutputColumn outputColumn(Element output, String unnamed, Optional<ColumnType> untypedAs, ItemDefinitions types,
            TableScope scope, String where) {
        var column = nameOr(output, LABEL);
        if (column.isBlank()) {
            column = unnamed;
        }
        if (column.isBlank()) {
            throw new InvalidDecisionTableException(where + ": every output of a multi-output table needs a name");
        }
        var outputWhere = outputWhere(where, column);
        var typeRef = output.getAttribute(TYPE_REF);
        var valueList = nonBlankText(output, "outputValues");
        ColumnType type = untypedAs.orElse(ColumnType.STRING);
        boolean typed = untypedAs.isPresent() || valueList != null;
        if (!typeRef.isBlank()) {
            typed = true;
            var resolved = types.resolve(typeRef, outputWhere);
            if (resolved.list()) {
                throw new InvalidDecisionTableException("%s: type '%s' is a collection, which is not supported for outputs"
                        .formatted(outputWhere, typeRef));
            }
            type = resolved.columnType();
        }
        List<String> values = List.of();
        if (valueList != null) {
            values = Literals.parseCell(valueList, type, outputWhere + " value list").values()
                    .orElseThrow(() -> new InvalidDecisionTableException(outputWhere + ": the output value list must be a list of values"))
                    .stream().map(FeelLiterals::text).toList();
        }
        var defaultValue = defaultValue(output, type, values, scope, outputWhere);
        // an output with a value list or a default keeps a type, a string where nothing else gives one: both are text
        typed = typed || defaultValue != null;
        return defaultValue == null
                ? new OutputColumn(column, type, values, null, false, typed)
                : new OutputColumn(column, type, values, defaultValue.text(), true, typed);
    }

    /**
     * The type of a single output whose {@code OutputClause} names none, which DMN 1.5 requires of a single output
     * (Table 34: "The OutputClause of a single output decision table SHALL NOT specify a typeRef"): the type of the
     * decision's result, named by the decision table's {@code typeRef}, else by the decision's {@code variable}.
     * That is the output's type, except that a multi-hit table without aggregation results in a list of outputs,
     * whose item type is then the output's, and that COUNT results in the number of outputs, which says nothing of
     * them. Where nothing names a type the output can take (none is given, it is not one Praecepta can hold, or it
     * is a list for a single result or not a list for a list of results), the output is untyped: a string.
     */
    private static ColumnType resultType(Element decision, Element table, HitPolicy hitPolicy, AggregationOperator aggregation,
            ItemDefinitions types, String where) {
        if (aggregation == AggregationOperator.COUNT) {
            return ColumnType.STRING;
        }
        var typeRef = table.getAttribute(TYPE_REF);
        if (typeRef.isBlank()) {
            typeRef = variableTypeRef(decision);
        }
        if (typeRef.isBlank()) {
            return ColumnType.STRING;
        }
        boolean listOfOutputs = !hitPolicy.isSingleHit() && aggregation == null;
        return types.typeIfSupported(typeRef, where + ", output")
                .filter(type -> type.list() == listOfOutputs)
                .map(Type::columnType)
                .orElse(ColumnType.STRING);
    }

    /**
     * An output's default value ({@code defaultOutputEntry}), the value when no rule matches, as plain text like
     * a literal output entry, whose text is null for a default of {@code null}; null if the output has none (no
     * {@code defaultOutputEntry}, or a blank one). A {@code null} default is a default: in a table with several
     * outputs, the default output is then a context with that output null (DMN 1.5, 10.3.2.10 and Table 46), where
     * a table whose outputs have no default gives null. It is a literal of the output's type, or a FEEL expression that
     * reads no input ({@code -1}, {@code "N/" + "A"}), whose value, the same whatever the inputs, is taken when the table
     * is read (DMN 1.5, 10.3.2.10, step 2: "DTI=FEEL(d)"), bound to the output's type as an output entry's is: a string
     * output takes any value as its text, and another one a value of its type, else null. A default that reads an input
     * is rejected: Table 46 has the default output value "one of the output values", which no input changes. When the
     * output has a value list, a default other than {@code null} must be one of its values.
     *
     * @param values the output's value list, as plain text; empty if it has none
     */
    private static LiteralOutput defaultValue(Element output, ColumnType type, List<String> values, TableScope scope,
            String where) {
        var text = nonBlankText(output, DEFAULT_OUTPUT_ENTRY);
        if (text == null) {
            return null;
        }
        var literal = Literals.literalOutput(text, type);
        if (literal == null && isSimple(text.strip(), scope.get())) {
            // a literal of another type, or a name: no value of the output's type
            throw new InvalidDecisionTableException("%s: default output '%s' is not supported; a default output must be a literal %s"
                    .formatted(where, text.strip(), type.feelName()));
        }
        if (literal == null) {
            literal = constantOutput(text.strip(), type, scope.get(), where);
        }
        var given = literal;
        if (given.text() != null && !values.isEmpty() && values.stream().noneMatch(value -> sameValue(value, given.text(), type))) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not in its output values %s"
                    .formatted(where, text.strip(), values));
        }
        return given;
    }

    /** Whether an entry is a single literal or a name, as simple unary tests hold them, rather than an expression. */
    private static boolean isSimple(String text, FeelExpression.Scope scope) {
        try {
            var tests = FeelUnaryTests.parse(text, scope);
            FeelExpression.parse(text, scope); // one test, not several
            return !tests.isAny() && tests.isSimple();
        } catch (ExpressionSyntaxException e) {
            return false;
        }
    }

    /**
     * A default output that is a FEEL expression reading no input, evaluated: its value bound to the output's type, as
     * plain text, null where it is null.
     *
     * @throws InvalidDecisionTableException if it is not FEEL, or reads an input
     */
    private static LiteralOutput constantOutput(String text, ColumnType type, FeelExpression.Scope scope, String where) {
        FeelExpression expression;
        try {
            expression = FeelExpression.parse(text, scope);
        } catch (ExpressionSyntaxException e) {
            throw new InvalidDecisionTableException("%s: default output '%s' is not FEEL: %s".formatted(where, text, e.getMessage()));
        }
        rejectUnsupported(where + ", default output '" + text + "'", expression.unsupported());
        if (!expression.names().isEmpty()) {
            throw new InvalidDecisionTableException(("%s: default output '%s' reads '%s'; a default output is a value, which no input "
                    + "changes (DMN 1.5, Table 46)").formatted(where, text, expression.names().getFirst()));
        }
        var value = expression.evaluate(name -> null);
        if (value == null) {
            return new LiteralOutput(null);
        }
        if (type == ColumnType.STRING) {
            return new LiteralOutput(value instanceof String string ? string : FeelLiterals.text(value));
        }
        var bound = bound(value, type);
        return new LiteralOutput(bound == null ? null : FeelLiterals.text(bound));
    }

    /**
     * A value bound to a simple type (DMN 1.5, 10.3.2.9.4): a list of one as its element, a date as a date and time at UTC
     * midnight for a date and time, a value of the type as it is, and null for any other.
     */
    private static Object bound(Object value, ColumnType type) {
        var single = value instanceof List<?> list && list.size() == 1 ? list.getFirst() : value;
        if (single instanceof LocalDate date && type == ColumnType.DATE_TIME) {
            return date.atStartOfDay(ZoneOffset.UTC);
        }
        boolean ofType = switch (type) {
            case STRING -> single instanceof String;
            case NUMBER -> single instanceof BigDecimal;
            case BOOLEAN -> single instanceof Boolean;
            case DATE -> single instanceof LocalDate;
            case TIME -> single instanceof LocalTime || single instanceof OffsetTime || single instanceof ZonedTime;
            case DATE_TIME -> single instanceof LocalDateTime || single instanceof ZonedDateTime;
            case DAY_TIME_DURATION -> single instanceof Duration;
            case YEAR_MONTH_DURATION -> single instanceof YearsAndMonths;
        };
        return ofType ? single : null;
    }

    /** Whether two values of an output, as plain text, are the same: numbers by value ({@code 1.50} is {@code 1.5}). */
    private static boolean sameValue(String a, String b, ColumnType type) {
        if (type == ColumnType.NUMBER) {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        }
        return a.equals(b);
    }

    /** Rejects an expression that uses what Praecepta does not evaluate: an external function. */
    static void rejectUnsupported(String where, List<FeelExpression.Unsupported> unsupported) {
        if (!unsupported.isEmpty()) {
            throw new UnsupportedFeatureException("%s uses %s".formatted(where, FeelExpression.Unsupported.describe(unsupported)));
        }
    }

    /** A decision, as errors name it. */
    static String decisionWhere(String name) {
        return "Decision '" + name + "'";
    }

    /** An input, as errors name it. */
    static String inputWhere(String where, String column) {
        return where + ", input '" + column + "'";
    }

    /** An output, as errors name it. */
    static String outputWhere(String where, String column) {
        return where + ", output '" + column + "'";
    }
}
