/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelArithmetic.Operator;

/**
 * A parsed FEEL expression (DMN 1.5, 10.3.1.2), as {@link FeelParser} builds it: a tree of these nodes, each of which
 * evaluates itself in a scope ({@link Env}, 10.3.2) and gives its static type in a scope of types ({@link TypeEnv},
 * 10.3.2.9). Evaluating a node recurses over its children, one call a level; the parser bounds the tree's depth
 * ({@link FeelParser#MAX_DEPTH}), and {@link Env} the nesting of function invocations, so evaluation takes a bounded
 * amount of the thread's stack. Where FEEL defines no value, a node gives null and reports an error (10.3.2.16); the
 * messages quote values shortly ({@link FeelValues#text}), and are built only where the evaluation reports errors.
 */
sealed interface Node {

    /** The node's value in a scope; null, with an error reported, where FEEL defines none. */
    Object evaluate(Env env);

    /** The type of the node's values in a scope of types; {@link FeelType#NULL} where it is always null. */
    FeelType type(TypeEnv env);

    /** The nodes it is made of. */
    default List<Node> children() {
        return List.of();
    }

    /**
     * How much of the thread's stack evaluating it takes, beside its children, in levels of the tree: one for most nodes;
     * more for those that evaluate their children through calls of their own (an iteration, a filter, an invocation).
     */
    default int weight() {
        return 1;
    }

    private static Object test(Object value) {
        return value instanceof Boolean ? value : null;
    }

    /**
     * The values of the constants in trees, in the order written, but null, found with a stack of their own: a
     * constant of a function value (a built-in function) is no literal and is left out.
     */
    static List<Object> literals(List<Node> roots) {
        var literals = new ArrayList<>();
        var pending = new java.util.ArrayDeque<Node>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            pending.push(roots.get(i));
        }
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node instanceof Constant(var value) && value != null && !(value instanceof FeelFunction)) {
                literals.add(value);
            }
            var children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }
        return List.copyOf(literals);
    }

    /**
     * Whether two trees are the same, node for node: of the same kinds, holding the same values beside their children
     * (a constant's value, an operator, a name, a function's parameters and their types...), with the same children, in
     * order. Compared with a stack of its own, as deep as the trees are.
     */
    static boolean sameTree(Node a, Node b) {
        var pending = new java.util.ArrayDeque<Node[]>();
        pending.push(new Node[] {a, b});
        while (!pending.isEmpty()) {
            var pair = pending.pop();
            if (pair[0] == pair[1]) {
                continue;
            }
            if (pair[0].getClass() != pair[1].getClass() || !sameAttributes(pair[0], pair[1])) {
                return false;
            }
            var x = pair[0].children();
            var y = pair[1].children();
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                pending.push(new Node[] {x.get(i), y.get(i)});
            }
        }
        return true;
    }

    /** Whether two nodes of one kind hold the same values beside their children. */
    private static boolean sameAttributes(Node a, Node b) {
        return switch (a) {
            case Constant(var value) -> java.util.Objects.equals(value, ((Constant) b).value());
            case Invalid(var message) -> message.equals(((Invalid) b).message());
            case Unsupported(var what, var arguments) -> what.equals(((Unsupported) b).what());
            case Name(var name) -> name.equals(((Name) b).name());
            case Arithmetic(var operator, var left, var right) -> operator == ((Arithmetic) b).operator();
            case Comparison(var operator, var left, var right) -> operator.equals(((Comparison) b).operator());
            case Logical(var and, var left, var right) -> and == ((Logical) b).and();
            case Equality(var negated, var value) -> negated == ((Equality) b).negated();
            case In(var value, var tests) -> tests.stream().map(Test::questionMark).toList()
                    .equals(((In) b).tests().stream().map(Test::questionMark).toList());
            case InstanceOf(var value, var of) -> of.equals(((InstanceOf) b).of());
            case ContextLiteral(var keys, var values) -> keys.equals(((ContextLiteral) b).keys());
            case ContextResult(var keys, var values, var result) -> keys.equals(((ContextResult) b).keys());
            case Row(var keys, var values) -> keys.equals(((Row) b).keys());
            case Bound(var value, var declared) -> declared.equals(((Bound) b).declared());
            case Embedded(var expression) -> expression == ((Embedded) b).expression();
            case Strict n -> true;
            case Checked n -> true;
            case RangeLiteral(var start, var startIncluded, var end, var endIncluded) -> startIncluded == ((RangeLiteral) b).startIncluded()
                    && endIncluded == ((RangeLiteral) b).endIncluded();
            case UnaryRange(var operator, var endpoint) -> operator == ((UnaryRange) b).operator();
            case Path(var target, var name) -> name.equals(((Path) b).name());
            case FunctionDefinition(var parameters, var types, var body, var depth) -> parameters.equals(((FunctionDefinition) b).parameters())
                    && types.equals(((FunctionDefinition) b).types());
            case Invocation(var callee, var arguments, var names) -> names.equals(((Invocation) b).names());
            case For(var variables, var domains, var body) -> variables.equals(((For) b).variables());
            case Quantified(var every, var variables, var domains, var condition) -> every == ((Quantified) b).every()
                    && variables.equals(((Quantified) b).variables());
            case Negation n -> true;
            case Between n -> true;
            case If n -> true;
            case ListLiteral n -> true;
            case Filter n -> true;
            case IterationRange n -> true;
        };
    }

    // ---- literals and names ------------------------------------------------------------------------

    /** A literal value: a string, a number, a boolean, a date and time literal, {@code null}. */
    record Constant(Object value) implements Node {
        @Override
        public Object evaluate(Env env) {
            return value;
        }

        @Override
        public FeelType type(TypeEnv env) {
            return value == null ? env.alwaysNull("it is null") : FeelValues.typeOf(value);
        }
    }

    /** A literal FEEL gives null for, such as {@code date("2025-13-01")}: null, with an error saying why. */
    record Invalid(String message) implements Node {
        @Override
        public Object evaluate(Env env) {
            return env.error(message);
        }

        @Override
        public FeelType type(TypeEnv env) {
            return env.alwaysNull(message);
        }
    }

    /**
     * What DMN 1.5 defines but Praecepta does not evaluate, such as an external function, which it rejects by design: its
     * value is null, with an error; {@link FeelExpression#unsupported()} names it, so a decision that uses it is reported.
     *
     * @param what what it is
     */
    record Unsupported(FeelExpression.Unsupported what, List<Node> arguments) implements Node {
        @Override
        public Object evaluate(Env env) {
            return env.error(what + ": not evaluated");
        }

        @Override
        public FeelType type(TypeEnv env) {
            return FeelType.ANY;
        }

        @Override
        public List<Node> children() {
            return arguments;
        }
    }

    /** A name, looked up in scope (DMN 1.5, 10.3.2.11). */
    record Name(String name) implements Node {
        @Override
        public Object evaluate(Env env) {
            return env.lookup(name);
        }

        @Override
        public FeelType type(TypeEnv env) {
            return env.lookup(name);
        }
    }

    // ---- arithmetic --------------------------------------------------------------------------------

    /** {@code -operand}: a number or a duration times -1 (DMN 1.5, Table 62). */
    record Negation(Node operand) implements Node {
        @Override
        public Object evaluate(Env env) {
            var value = FeelNumbers.normalise(operand.evaluate(env));
            var type = FeelValues.simpleType(value);
            if (type == null || FeelArithmetic.negationType(type) == null) {
                return env.error(() -> "-%s: FEEL negates numbers and durations, not a %s"
                        .formatted(FeelValues.text(value), FeelValues.typeName(value)));
            }
            return FeelArithmetic.negate(value);
        }

        @Override
        public FeelType type(TypeEnv env) {
            var type = operand.type(env);
            return switch (type) {
                case FeelType.Any any -> type;
                case FeelType.Null nothing -> type;
                case FeelType.Simple(var simple) when FeelArithmetic.negationType(simple) != null -> type;
                default -> env.alwaysNull("- applies to numbers and durations, not to a " + type.feelName());
            };
        }

        @Override
        public List<Node> children() {
            return List.of(operand);
        }
    }

    /**
     * {@code left operator right}, as {@link FeelArithmetic} computes it (DMN 1.5, Tables 57 to 61): null, with an error,
     * for operands FEEL does not combine, a null one among them (the DMN TCK's {@code 0100-arithmetic} has
     * {@code 10 + null} an error), and a result FEEL does not define, such as a division by zero.
     */
    record Arithmetic(Operator operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Env env) {
            var a = FeelNumbers.normalise(left.evaluate(env));
            var b = FeelNumbers.normalise(right.evaluate(env));
            var typeA = FeelValues.simpleType(a);
            var typeB = FeelValues.simpleType(b);
            if (typeA == null || typeB == null || FeelArithmetic.type(operator, typeA, typeB) == null) {
                return env.error(() -> "%s %s %s: FEEL's arithmetic does not combine a %s and a %s".formatted(
                        FeelValues.text(a), operator.symbol(), FeelValues.text(b), FeelValues.typeName(a), FeelValues.typeName(b)));
            }
            if (a instanceof String x && b instanceof String y && !env.characters(x.length() + (long) y.length())) {
                return null;
            }
            var value = FeelArithmetic.apply(operator, a, b);
            return value != null ? value
                    : env.error(() -> "%s %s %s has no value in FEEL".formatted(FeelValues.text(a), operator.symbol(), FeelValues.text(b)));
        }

        @Override
        public FeelType type(TypeEnv env) {
            var a = left.type(env);
            var b = right.type(env);
            if (a instanceof FeelType.Null || b instanceof FeelType.Null) {
                return env.alwaysNull("%s has a null operand".formatted(operator.symbol()));
            }
            if (a instanceof FeelType.Simple(var x) && b instanceof FeelType.Simple(var y)) {
                var type = FeelArithmetic.type(operator, x, y);
                return type != null ? FeelType.of(type) : env.alwaysNull("%s combines a %s and a %s, which gives null in FEEL"
                        .formatted(operator.symbol(), x.feelName(), y.feelName()));
            }
            if (isArithmetic(a) && isArithmetic(b)) {
                return FeelType.ANY;
            }
            return env.alwaysNull("%s combines a %s and a %s, which gives null in FEEL".formatted(operator.symbol(), a.feelName(),
                    b.feelName()));
        }

        /** Whether values of a type may be operands of arithmetic: of any type, or of a simple type other than boolean. */
        private static boolean isArithmetic(FeelType type) {
            return type instanceof FeelType.Any || type instanceof FeelType.Simple(var simple) && simple != ColumnType.BOOLEAN;
        }

        @Override
        public List<Node> children() {
            return List.of(left, right);
        }
    }

    // ---- tests -------------------------------------------------------------------------------------

    /**
     * {@code left operator right}, a comparison (DMN 1.5, Tables 53 and 54): {@code =} and {@code !=} as
     * {@link FeelValues#equality} has them, {@code <}, {@code <=}, {@code >}, {@code >=} of values FEEL orders; null,
     * with an error, otherwise.
     */
    record Comparison(String operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Env env) {
            var a = left.evaluate(env);
            var b = right.evaluate(env);
            var value = switch (operator) {
                case "=" -> FeelValues.equality(a, b, env);
                case "!=" -> FeelValues.not(FeelValues.equality(a, b, env));
                default -> {
                    var order = FeelValues.compare(a, b);
                    yield order == null ? null : holds(order);
                }
            };
            // a null of an evaluation that ran out of steps needs no error of its own: the evaluation reported one
            if (value == null && !env.stopped()) {
                env.error(() -> "%s %s %s: FEEL does not compare a %s and a %s".formatted(FeelValues.text(a), operator,
                        FeelValues.text(b), FeelValues.typeName(a), FeelValues.typeName(b)));
            }
            return value;
        }

        private boolean holds(int order) {
            return switch (operator) {
                case "<" -> order < 0;
                case "<=" -> order <= 0;
                case ">" -> order > 0;
                default -> order >= 0;
            };
        }

        @Override
        public FeelType type(TypeEnv env) {
            left.type(env);
            right.type(env);
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public List<Node> children() {
            return List.of(left, right);
        }
    }

    /**
     * {@code left and right}, {@code left or right}: FEEL's logic of three values (DMN 1.5, Table 50), in which a value
     * that is not a boolean counts as null. The right operand is not evaluated when the left decides.
     */
    record Logical(boolean and, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Env env) {
            var a = test(left.evaluate(env));
            if (Boolean.valueOf(!and).equals(a)) {
                return a;
            }
            var b = test(right.evaluate(env));
            return and ? FeelValues.and(a, b) : FeelValues.or(a, b);
        }

        @Override
        public FeelType type(TypeEnv env) {
            left.type(env);
            right.type(env);
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public List<Node> children() {
            return List.of(left, right);
        }
    }

    /**
     * A decision table's test {@code = e} or {@code != e} (DMN 1.5, grammar rule 7, and Table 53): whether the value tested,
     * {@code ?}, equals {@code e}, or does not; so {@code = null} holds for a missing value.
     *
     * @param negated whether it is {@code !=}, else {@code =}
     */
    record Equality(boolean negated, Node value) implements Node {
        @Override
        public Object evaluate(Env env) {
            var equals = FeelValues.equality(env.lookup("?"), value.evaluate(env), env);
            return negated ? FeelValues.not(equals) : equals;
        }

        @Override
        public FeelType type(TypeEnv env) {
            value.type(env);
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public List<Node> children() {
            return List.of(value);
        }
    }

    /**
     * {@code value between low and high}: {@code value >= low and value <= high} (DMN 1.5, Table 55), so false, not null,
     * when one comparison is false and the other null (Table 50).
     */
    record Between(Node value, Node low, Node high) implements Node {
        @Override
        public Object evaluate(Env env) {
            var v = value.evaluate(env);
            var result = within(v, low.evaluate(env), true, high.evaluate(env), true);
            return result == null ? env.error(() -> "between compares values FEEL does not order: " + FeelValues.text(v)) : result;
        }

        /**
         * {@code value >= from and value <= to}, or {@code >} and {@code <} where an endpoint is excluded, with FEEL's
         * three-valued {@code and}: a comparison with null, or of values FEEL does not order, is null.
         */
        static Object within(Object value, Object from, boolean fromIncluded, Object to, boolean toIncluded) {
            var above = FeelValues.compare(value, from);
            var below = FeelValues.compare(value, to);
            return FeelValues.and(above == null ? null : above > 0 || above == 0 && fromIncluded,
                    below == null ? null : below < 0 || below == 0 && toIncluded);
        }

        @Override
        public FeelType type(TypeEnv env) {
            children().forEach(child -> child.type(env));
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public List<Node> children() {
            return List.of(value, low, high);
        }
    }

    /**
     * A positive unary test (DMN 1.5, 7.3.2, 10.3.2.10 and Table 55), after {@code in} or in a decision table's cell:
     * <ul>
     * <li>an expression that names {@code ?} holds when it is true with {@code ?} bound to the value (7.3.2(d));</li>
     * <li>a range written {@code [e2..e3]}, {@code (e2..e3)}... is Table 55's {@code e1 >= e2 and e1 <= e3} (or
     * {@code >} and {@code <} for an excluded endpoint), so false, not null, when one comparison is false and the
     * other null: {@code 15 in [null..10]} is false, {@code 5 in [null..10]} null, {@code 5 in [10..1]} false. In a
     * cell, a missing endpoint makes the test null, as a simple cell's interval of inputs is
     * (docs/DMN-CONFORMANCE.md, case 3);</li>
     * <li>an expression whose value is a function holds when the function, applied to the value, is true
     * (7.3.2(c));</li>
     * <li>one whose value is a list holds when the value is in it (7.3.2(b)), each element taken as a test (a range
     * holds what is in it, any other element what equals it), and is false, not null, when none holds, as
     * {@code list contains} is (the DMN TCK's {@code 0072-feel-in} has {@code true in [false, 2, 3]} false);</li>
     * <li>a range holds what is in it, and a range of one endpoint that is null ({@code < null}) holds nothing
     * known;</li>
     * <li>any other value holds what equals it (7.3.2(a)).</li>
     * </ul>
     */
    record Test(Node expression, boolean questionMark) {

        /**
         * Whether the test holds for a value: true, false or null.
         *
         * @param cell whether it is a decision table's cell, in which a range's missing endpoint makes it null
         */
        Object holds(Object value, Env env, boolean cell) {
            if (questionMark) {
                return test(expression.evaluate(env.with(Collections.singletonMap("?", value))));
            }
            if (expression instanceof RangeLiteral range) {
                return range.holds(value, env, cell);
            }
            var test = expression.evaluate(env);
            return switch (test) {
                case null -> expression instanceof UnaryRange ? null : FeelValues.equality(value, null, env);
                case List<?> list -> inList(value, list, env);
                case FeelRange range -> FeelValues.inRange(value, range);
                case FeelFunction function -> test(Invocation.call(function, Collections.singletonList(value), env));
                default -> FeelValues.equality(value, test, env);
            };
        }

        private static Object inList(Object value, List<?> list, Env env) {
            for (var element : list) {
                if (!env.step()) {
                    return null;
                }
                var holds = element instanceof FeelRange range ? FeelValues.inRange(value, range) : FeelValues.equality(value, element, env);
                if (Boolean.TRUE.equals(holds)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code value in tests}: whether one of the positive unary tests holds (DMN 1.5, Table 55), as FEEL's {@code or}. */
    record In(Node value, List<Test> tests) implements Node {
        @Override
        public Object evaluate(Env env) {
            var v = value.evaluate(env);
            Object result = false;
            for (var test : tests) {
                result = FeelValues.or(result, test.holds(v, env, false));
                if (Boolean.TRUE.equals(result)) {
                    return result;
                }
            }
            return result == null && !env.stopped()
                    ? env.error(() -> "in tests " + FeelValues.text(v) + " against values FEEL does not compare it with") : result;
        }

        @Override
        public FeelType type(TypeEnv env) {
            value.type(env);
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public int weight() {
            return 2;
        }

        @Override
        public List<Node> children() {
            var children = new ArrayList<Node>();
            children.add(value);
            tests.forEach(test -> children.add(test.expression()));
            return children;
        }
    }

    /** {@code value instance of type} (DMN 1.5, 10.3.2.9), as {@link FeelValues#isInstance} has it. */
    record InstanceOf(Node value, FeelType of) implements Node {
        @Override
        public Object evaluate(Env env) {
            return FeelValues.isInstance(value.evaluate(env), of, env);
        }

        @Override
        public FeelType type(TypeEnv env) {
            value.type(env);
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public List<Node> children() {
            return List.of(value);
        }
    }

    /** {@code if condition then a else b}: {@code a} when the condition is true, else {@code b} (DMN 1.5, Table 52). */
    record If(Node condition, Node then, Node otherwise) implements Node {
        @Override
        public Object evaluate(Env env) {
            return Boolean.TRUE.equals(condition.evaluate(env)) ? then.evaluate(env) : otherwise.evaluate(env);
        }

        @Override
        public FeelType type(TypeEnv env) {
            condition.type(env);
            return TypeEnv.join(then.type(env), otherwise.type(env));
        }

        @Override
        public List<Node> children() {
            return List.of(condition, then, otherwise);
        }
    }

    // ---- lists, contexts and ranges ----------------------------------------------------------------

    /** {@code [a, b, c]}: a list (DMN 1.5, 10.3.2.5). */
    record ListLiteral(List<Node> elements) implements Node {
        @Override
        public Object evaluate(Env env) {
            var values = new ArrayList<>(elements.size());
            elements.forEach(element -> values.add(element.evaluate(env)));
            return Collections.unmodifiableList(values);
        }

        @Override
        public FeelType type(TypeEnv env) {
            FeelType element = FeelType.NULL;
            for (var node : elements) {
                element = TypeEnv.join(element, node.type(env));
            }
            return new FeelType.ListOf(elements.isEmpty() ? FeelType.ANY : element);
        }

        @Override
        public List<Node> children() {
            return elements;
        }
    }

    /**
     * {@code {a: 1, "b c": a + 1}}: a context (DMN 1.5, 10.3.2.6), whose entries are evaluated in order, each in the
     * scope of those before it.
     */
    record ContextLiteral(List<String> keys, List<Node> values) implements Node {
        @Override
        public Object evaluate(Env env) {
            var context = new LinkedHashMap<String, Object>();
            var scope = env.with(context);
            for (int i = 0; i < keys.size(); i++) {
                context.put(keys.get(i), values.get(i).evaluate(scope));
            }
            return Collections.unmodifiableMap(context);
        }

        @Override
        public FeelType type(TypeEnv env) {
            var types = new LinkedHashMap<String, FeelType>();
            var scope = env.with(types);
            for (int i = 0; i < keys.size(); i++) {
                types.put(keys.get(i), values.get(i).type(scope));
            }
            return new FeelType.Context(types);
        }

        @Override
        public int weight() {
            return 2;
        }

        @Override
        public List<Node> children() {
            return values;
        }
    }

    /**
     * A boxed context with a final result (DMN 1.5, 10.2.1): its entries are evaluated in order, each in the scope of
     * those before it and of itself, as a context literal's are, and its value is the result's, evaluated in the scope
     * of them all.
     */
    record ContextResult(List<String> keys, List<Node> values, Node result) implements Node {
        @Override
        public Object evaluate(Env env) {
            var context = new LinkedHashMap<String, Object>();
            var scope = env.with(context);
            for (int i = 0; i < keys.size(); i++) {
                context.put(keys.get(i), values.get(i).evaluate(scope));
            }
            return result.evaluate(scope);
        }

        @Override
        public FeelType type(TypeEnv env) {
            var types = new LinkedHashMap<String, FeelType>();
            var scope = env.with(types);
            for (int i = 0; i < keys.size(); i++) {
                types.put(keys.get(i), values.get(i).type(scope));
            }
            return result.type(scope);
        }

        @Override
        public int weight() {
            return 2;
        }

        @Override
        public List<Node> children() {
            var children = new ArrayList<>(values);
            children.add(result);
            return children;
        }
    }

    /**
     * A row of a relation (DMN 1.5, 10.2.6): the context of its cells' values by column, each cell evaluated in the
     * scope around the relation, not of the other cells.
     */
    record Row(List<String> keys, List<Node> values) implements Node {
        @Override
        public Object evaluate(Env env) {
            var context = new LinkedHashMap<String, Object>();
            for (int i = 0; i < keys.size(); i++) {
                context.put(keys.get(i), values.get(i).evaluate(env));
            }
            return Collections.unmodifiableMap(context);
        }

        @Override
        public FeelType type(TypeEnv env) {
            var types = new LinkedHashMap<String, FeelType>();
            for (int i = 0; i < keys.size(); i++) {
                types.put(keys.get(i), values.get(i).type(env));
            }
            return new FeelType.Context(types);
        }

        @Override
        public List<Node> children() {
            return values;
        }
    }

    /**
     * A part of a boxed expression whose value is bound to the type the model declares for it (DMN 1.5, 10.3.2.9.4), as
     * an argument is bound to its parameter's type: as it is where it conforms, a single value to a list as a list of
     * one, a list of one to a single value as its element, a date to a date and time at UTC midnight; any other value is
     * null, with an error.
     */
    record Bound(Node value, FeelType declared) implements Node {
        @Override
        public Object evaluate(Env env) {
            var result = value.evaluate(env);
            var bound = Closure.bind(result, declared, env);
            if (bound == Closure.NOT_BOUND) {
                return env.error(() -> "%s is not a %s, the type the model declares for it (DMN 1.5, 10.3.2.9.4)"
                        .formatted(FeelValues.text(result), declared.feelName()));
            }
            return bound;
        }

        @Override
        public FeelType type(TypeEnv env) {
            var type = value.type(env);
            return type instanceof FeelType.Null ? type : declared;
        }

        @Override
        public List<Node> children() {
            return List.of(value);
        }
    }

    /**
     * The condition of a boxed conditional, filter or iterator (DMN 1.5, 10.2.7 to 10.2.9), which must be a boolean or
     * null: any other value is an error, which makes the whole construct null ({@link Checked}), where FEEL's
     * {@code if}, filter, {@code some} and {@code every} would take it as not true (the DMN TCK's
     * {@code 1150-boxed-conditional} to {@code 1154-boxed-every}).
     */
    record Strict(Node condition) implements Node {

        /** Thrown, without a stack trace, to the construct's {@link Checked} once its condition is not a boolean. */
        static final class NotBoolean extends RuntimeException {
            private static final long serialVersionUID = 1L;

            NotBoolean() {
                super(null, null, false, false);
            }
        }

        private static final NotBoolean NOT_BOOLEAN = new NotBoolean();

        @Override
        public Object evaluate(Env env) {
            var value = condition.evaluate(env);
            if (value != null && !(value instanceof Boolean)) {
                env.error(() -> "the condition is %s, not a boolean (DMN 1.5, 10.2)".formatted(FeelValues.text(value)));
                throw NOT_BOOLEAN;
            }
            return value;
        }

        @Override
        public FeelType type(TypeEnv env) {
            return condition.type(env);
        }

        @Override
        public List<Node> children() {
            return List.of(condition);
        }
    }

    /** A boxed conditional, filter or iterator whose {@link Strict} condition makes it null when it is not a boolean. */
    record Checked(Node construct) implements Node {
        @Override
        public Object evaluate(Env env) {
            try {
                return construct.evaluate(env);
            } catch (Strict.NotBoolean e) {
                return null;
            }
        }

        @Override
        public FeelType type(TypeEnv env) {
            return construct.type(env);
        }

        @Override
        public List<Node> children() {
            return List.of(construct);
        }
    }

    /**
     * A part of a boxed expression that FEEL does not evaluate itself, such as a decision table in a context entry or a
     * business knowledge model's body, given the value of each name it reads, as the scope holds them.
     */
    record Embedded(FeelExpression.Embedded expression) implements Node {
        @Override
        public Object evaluate(Env env) {
            Consumer<String> errors = env.reporting() ? env::error : null;
            return env.embedding(() -> expression.evaluate(env::lookup, errors));
        }

        @Override
        public FeelType type(TypeEnv env) {
            return expression.type();
        }

        @Override
        public int weight() {
            return 2;
        }
    }

    /**
     * {@code [start..end]}, {@code (start..end)}, {@code ]start..end[}: a range (DMN 1.5, 10.3.2.7). Its endpoints are of
     * one type FEEL orders, the start not after the end; an excluded endpoint may be null. Otherwise it is null, with an
     * error (the DMN TCK's {@code 0072-feel-in} and {@code 0084-feel-for-loops}).
     */
    record RangeLiteral(Node start, boolean startIncluded, Node end, boolean endIncluded) implements Node {
        @Override
        public Object evaluate(Env env) {
            var from = start.evaluate(env);
            var to = end.evaluate(env);
            if (from == null && startIncluded || to == null && endIncluded) {
                return env.error("a range's included endpoint is null");
            }
            if (from != null && to != null) {
                var order = FeelValues.compare(from, to);
                if (order == null || order > 0) {
                    return env.error(() -> "a range's endpoints %s and %s are not of one type FEEL orders, in order"
                            .formatted(FeelValues.text(from), FeelValues.text(to)));
                }
            }
            return FeelRange.between(from, startIncluded, to, endIncluded);
        }

        /**
         * Whether a value is in it, as a positive unary test: Table 55's {@code e1 >= e2 and e1 <= e3}, the endpoints
         * evaluated and compared with the value, whether or not they make a range.
         *
         * @param cell whether it is a decision table's cell, in which a missing endpoint makes it null (case 3)
         */
        Object holds(Object value, Env env, boolean cell) {
            var from = start.evaluate(env);
            var to = end.evaluate(env);
            if (cell && (from == null || to == null)) {
                return null;
            }
            return Between.within(value, from, startIncluded, to, endIncluded);
        }

        @Override
        public FeelType type(TypeEnv env) {
            return new FeelType.RangeOf(TypeEnv.join(start.type(env), end.type(env)));
        }

        @Override
        public List<Node> children() {
            return List.of(start, end);
        }
    }

    /** {@code < e}, {@code <= e}, {@code > e}, {@code >= e}, {@code = e}, {@code != e}: a range of one endpoint (DMN 1.5, 10.3.2.7). */
    record UnaryRange(FeelRange.Comparison operator, Node endpoint) implements Node {
        @Override
        public Object evaluate(Env env) {
            var value = endpoint.evaluate(env);
            return value == null ? env.error(() -> "the endpoint of " + operator.symbol() + " is null") : FeelRange.comparison(operator, value);
        }

        @Override
        public FeelType type(TypeEnv env) {
            return new FeelType.RangeOf(endpoint.type(env));
        }

        @Override
        public List<Node> children() {
            return List.of(endpoint);
        }
    }

    // ---- paths and filters -------------------------------------------------------------------------

    /**
     * {@code target.name} (DMN 1.5, 10.3.2.5 and Table 65): a context's entry; of a list, the list of each element's
     * entry (null for an element without it); a property of a date, time, duration or range. Null, with an error, for
     * a context without the entry and a value without the property.
     */
    record Path(Node target, String name) implements Node {
        @Override
        public Object evaluate(Env env) {
            var value = target.evaluate(env);
            return switch (value) {
                case null -> env.error(() -> "null has no '" + name + "'");
                case Map<?, ?> context -> context.containsKey(name) ? context.get(name)
                        : env.error(() -> "the context has no entry '" + name + "'");
                case List<?> list -> {
                    if (!env.step(list.size())) {
                        yield null;
                    }
                    var values = new ArrayList<>(list.size());
                    list.forEach(element -> values.add(element instanceof Map<?, ?> context ? context.get(name) : null));
                    yield Collections.unmodifiableList(values);
                }
                default -> {
                    var property = FeelValues.property(value, name);
                    yield property == FeelValues.NO_PROPERTY
                            ? env.error(() -> "a %s has no property '%s'".formatted(FeelValues.typeName(value), name)) : property;
                }
            };
        }

        @Override
        public FeelType type(TypeEnv env) {
            var type = target.type(env);
            return switch (type) {
                case FeelType.Any any -> type;
                case FeelType.Null nothing -> type;
                case FeelType.ListOf(FeelType.Context(var components)) -> new FeelType.ListOf(components.getOrDefault(name, FeelType.ANY));
                case FeelType.ListOf list -> new FeelType.ListOf(FeelType.ANY);
                case FeelType.Context(var components) -> components.containsKey(name) ? components.get(name)
                        : env.alwaysNull("a %s has no entry '%s'".formatted(type.feelName(), name));
                default -> {
                    var property = FeelValues.propertyType(type, name);
                    yield property != null ? property : env.alwaysNull("a %s has no property '%s'".formatted(type.feelName(), name));
                }
            };
        }

        @Override
        public List<Node> children() {
            return List.of(target);
        }
    }

    /**
     * {@code target[condition]} (DMN 1.5, 10.3.2.5): with a number, the element at that position, counted from 1, or
     * from the end when negative, and null where there is none (the DMN TCK's {@code 0069-feel-list} has
     * {@code [1, 2, 3][4]} null, not an error); else the elements for which the condition is true, each tested in a
     * scope with {@code item} bound to it and, for a context, its entries, which come first. A value that is not a list
     * is filtered as a list of one (10.3.2.9.4: a singleton list; Table 45 has {@code 3[item > 2]} be {@code [3]}).
     * Whether the condition is a number is told by its value for the first element; for an empty list, by its value
     * with {@code item} null, whose errors are not reported, as no element is tested.
     */
    record Filter(Node target, Node condition) implements Node {
        @Override
        public Object evaluate(Env env) {
            var value = target.evaluate(env);
            if (value == null) {
                return env.error("null cannot be filtered");
            }
            List<?> list = value instanceof List<?> l ? l : List.of(value);
            if (list.isEmpty()) {
                var test = condition.evaluate(env.quiet().with(Collections.singletonMap("item", null)));
                return env.stopped() || test instanceof Number ? null : List.of();
            }
            return select(list, env);
        }

        /**
         * The element at the position the condition gives for the first element, when it gives a number; else the
         * elements of a non-empty list for which the condition is true.
         */
        private Object select(List<?> list, Env env) {
            var selected = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (!env.step()) {
                    return null;
                }
                var element = list.get(i);
                var test = condition.evaluate(env.with(itemScope(element)));
                var index = i == 0 && test instanceof Number number ? FeelNumbers.decimal(number) : null;
                if (index != null) {
                    return element(list, index);
                }
                if (Boolean.TRUE.equals(test)) {
                    selected.add(element);
                }
            }
            return Collections.unmodifiableList(selected);
        }

        private static Map<String, Object> itemScope(Object element) {
            if (!(element instanceof Map<?, ?> context)) {
                return Collections.singletonMap("item", element);
            }
            var scope = new LinkedHashMap<String, Object>();
            context.forEach((key, entry) -> scope.put(String.valueOf(key), entry));
            scope.putIfAbsent("item", element);
            return scope;
        }

        /** The element at a position, counted from 1, or from the end when negative; null where there is none. */
        private static Object element(List<?> list, BigDecimal index) {
            int size = list.size();
            if (index.signum() != 0 && index.stripTrailingZeros().scale() <= 0 && index.abs().compareTo(BigDecimal.valueOf(size)) <= 0) {
                int position = index.intValueExact();
                return list.get(position > 0 ? position - 1 : size + position);
            }
            return null;
        }

        @Override
        public FeelType type(TypeEnv env) {
            var type = target.type(env);
            var element = type instanceof FeelType.ListOf(var of) ? of : type;
            var scope = new LinkedHashMap<String, FeelType>();
            if (element instanceof FeelType.Context(var components)) {
                scope.putAll(components);
            }
            scope.putIfAbsent("item", element);
            var test = condition.type(env.with(scope));
            if (type instanceof FeelType.Any || type instanceof FeelType.Null) {
                return type;
            }
            return test.equals(FeelType.of(ColumnType.NUMBER)) ? element : new FeelType.ListOf(element);
        }

        @Override
        public int weight() {
            return 2;
        }

        @Override
        public List<Node> children() {
            return List.of(target, condition);
        }
    }

    // ---- functions ---------------------------------------------------------------------------------

    /**
     * {@code function(a, b: number) body}: a function definition (DMN 1.5, 10.3.2.13), whose value is a function that
     * closes over the scope of names it is evaluated in.
     *
     * @param types each parameter's declared type, {@link FeelType#ANY} where it declares none
     * @param depth the depth of the body's tree, which invoking it adds to the nesting {@link Env} bounds
     */
    record FunctionDefinition(List<String> parameters, List<FeelType> types, Node body, int depth) implements Node {
        @Override
        public Object evaluate(Env env) {
            return new Closure(this, env.scope());
        }

        @Override
        public FeelType type(TypeEnv env) {
            var bound = new LinkedHashMap<String, FeelType>();
            for (int i = 0; i < parameters.size(); i++) {
                bound.put(parameters.get(i), types.get(i));
            }
            return new FeelType.FunctionOf(types, body.type(env.with(bound)));
        }

        @Override
        public List<Node> children() {
            return List.of(body);
        }
    }

    /**
     * {@code callee(arguments)} or {@code callee(name: argument)}: an invocation (DMN 1.5, 10.3.2.13), of a function the
     * callee evaluates to. Positional arguments must be as many as it takes; named ones each name a parameter, once,
     * those not named being null (10.3.2.13.5). Otherwise, or for a callee that is not a function, it is null, with an
     * error. The function counts against this evaluation's limits, and reports its errors to it.
     *
     * @param names the arguments' names, in order, none twice (the parser makes an invocation that names one twice
     *              {@link Invalid}); empty for positional arguments
     */
    record Invocation(Node callee, List<Node> arguments, List<String> names) implements Node {
        @Override
        public Object evaluate(Env env) {
            var function = callee.evaluate(env);
            if (!(function instanceof FeelFunction f)) {
                return env.error(() -> FeelValues.text(function) + " is not a function, and cannot be invoked");
            }
            if (names.isEmpty()) {
                var values = new ArrayList<>(arguments.size());
                for (var argument : arguments) {
                    values.add(argument.evaluate(env));
                }
                return call(f, values, env);
            }
            int signature = f instanceof BuiltInFunction builtIn ? builtIn.signatureNaming(names) : BuiltInFunction.POSITIONAL;
            var parameters = f instanceof BuiltInFunction builtIn ? builtIn.signature(signature) : f.parameters();
            var positions = new int[names.size()];
            int highest = -1;
            for (int i = 0; i < names.size(); i++) {
                positions[i] = parameters.indexOf(names.get(i));
                if (positions[i] < 0) {
                    var name = names.get(i);
                    return env.error(() -> "the function has no parameter '" + name + "'");
                }
                highest = Math.max(highest, positions[i]);
            }
            int count = switch (f) {
                case Closure closure -> parameters.size();
                case BuiltInFunction builtIn -> Math.max(highest + 1, builtIn.arity().required());
            };
            var values = new ArrayList<>(Collections.nCopies(count, null));
            for (int i = 0; i < names.size(); i++) {
                values.set(positions[i], arguments.get(i).evaluate(env));
            }
            return f instanceof BuiltInFunction builtIn ? builtIn.call(values, signature, env) : call(f, values, env);
        }

        /** Invokes a function from an evaluation, which it counts against and reports its errors to. */
        static Object call(FeelFunction function, List<Object> arguments, Env env) {
            return switch (function) {
                case Closure closure -> closure.call(arguments, env);
                case BuiltInFunction builtIn -> builtIn.call(arguments, env);
            };
        }

        @Override
        public FeelType type(TypeEnv env) {
            var callee = this.callee.type(env);
            arguments.forEach(argument -> argument.type(env));
            return switch (callee) {
                case FeelType.FunctionOf(var parameters, var result) -> result;
                case FeelType.Null nothing -> env.alwaysNull("null is not a function, and cannot be invoked");
                default -> FeelType.ANY;
            };
        }

        @Override
        public int weight() {
            return 2;
        }

        @Override
        public List<Node> children() {
            var children = new ArrayList<Node>();
            children.add(callee);
            children.addAll(arguments);
            return children;
        }
    }

    // ---- iteration ---------------------------------------------------------------------------------

    /**
     * {@code from..to} in an iteration context of {@code for}, {@code some} or {@code every} (DMN 1.5, 10.3.2.14):
     * the whole numbers from one to the other, or the dates, a day apart, ascending or descending. Other endpoints give
     * null, with an error (the DMN TCK's {@code 0084-feel-for-loops}).
     */
    record IterationRange(Node from, Node to) implements Node {
        @Override
        public Object evaluate(Env env) {
            return Iteration.range(from.evaluate(env), to.evaluate(env), env);
        }

        @Override
        public FeelType type(TypeEnv env) {
            var type = TypeEnv.join(from.type(env), to.type(env));
            return new FeelType.ListOf(type);
        }

        @Override
        public List<Node> children() {
            return List.of(from, to);
        }
    }

    /**
     * {@code for a in as, b in bs return body} (DMN 1.5, 10.3.2.14): the list of the body's values for every
     * combination of the iteration variables, the later ones varying fastest, each bound in the scope of the earlier;
     * {@code partial} is the list of the values before the current one, as it is then: a value of its own, which the
     * values after it do not change, so {@code for i in 1..3 return partial} is {@code [[], [[]], [[], [[]]]]}.
     */
    record For(List<String> variables, List<Node> domains, Node body) implements Node {
        @Override
        public Object evaluate(Env env) {
            var results = new ArrayList<>();
            boolean complete = Iteration.over(variables, domains, env, scope -> {
                var value = body.evaluate(scope.with(Collections.singletonMap("partial", new Prefix(results, results.size()))));
                results.add(value);
                return true;
            });
            return complete ? Collections.unmodifiableList(results) : null;
        }

        /**
         * The first {@code size} values of a list that is only ever appended to: an immutable list, made without
         * copying them.
         */
        private static class Prefix extends AbstractList<Object> implements java.util.RandomAccess {
            private final List<Object> values;
            private final int size;

            Prefix(List<Object> values, int size) {
                this.values = values;
                this.size = size;
            }

            @Override
            public Object get(int index) {
                return values.get(java.util.Objects.checkIndex(index, size));
            }

            @Override
            public int size() {
                return size;
            }

            /** Equal, as {@link AbstractList} has it, to any list of equal elements in the same order: its fields only make them. */
            @Override
            public final boolean equals(Object other) {
                return super.equals(other);
            }

            /** The hash code {@link java.util.List#hashCode} defines, from its elements. */
            @Override
            public final int hashCode() {
                return super.hashCode();
            }
        }

        @Override
        public FeelType type(TypeEnv env) {
            var scope = Iteration.types(variables, domains, env);
            scope.put("partial", new FeelType.ListOf(FeelType.ANY));
            return new FeelType.ListOf(body.type(env.with(scope)));
        }

        @Override
        public int weight() {
            return 4;
        }

        @Override
        public List<Node> children() {
            var children = new ArrayList<>(domains);
            children.add(body);
            return children;
        }
    }

    /**
     * {@code some a in as satisfies condition}, {@code every a in as satisfies condition} (DMN 1.5, 10.3.2.14 and
     * Table 49): {@code false or c1 or c2 or ...} and {@code true and c1 and c2 and ...}, the condition's values for
     * every combination of the iteration variables, with FEEL's three-valued {@code or} and {@code and} (Table 50): so
     * {@code some} is true if one is true, false if all are false, and null otherwise, as for a condition that is null
     * or not a boolean for some combination and true for none; {@code every} likewise. It stops at the first value that
     * decides it.
     */
    record Quantified(boolean every, List<String> variables, List<Node> domains, Node condition) implements Node {
        @Override
        public Object evaluate(Env env) {
            Object[] result = {every};
            boolean complete = Iteration.over(variables, domains, env, scope -> {
                var holds = test(condition.evaluate(scope));
                result[0] = every ? FeelValues.and(result[0], holds) : FeelValues.or(result[0], holds);
                return !Boolean.valueOf(!every).equals(result[0]);
            });
            return complete ? result[0] : null;
        }

        @Override
        public FeelType type(TypeEnv env) {
            condition.type(env.with(Iteration.types(variables, domains, env)));
            return FeelType.of(ColumnType.BOOLEAN);
        }

        @Override
        public int weight() {
            return 4;
        }

        @Override
        public List<Node> children() {
            var children = new ArrayList<>(domains);
            children.add(condition);
            return children;
        }
    }
}
