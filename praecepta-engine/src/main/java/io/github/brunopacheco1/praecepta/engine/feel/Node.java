/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

/**
 * A parsed FEEL expression (DMN 1.5, 10.3.1.2), as {@link FeelParser} builds it: a tree of these nodes, each of which
 * evaluates itself in a scope ({@link Env}, 10.3.2) and gives its static type in a scope of types ({@link TypeEnv},
 * 10.3.2.9). Evaluating a node recurses over its children, one call a level; the parser bounds the tree's depth
 * ({@link FeelParser#MAX_DEPTH}), and {@link Env} the nesting of function invocations, so evaluation takes a bounded
 * amount of the thread's stack. Where FEEL defines no value, a node gives null and reports an error (10.3.2.15).
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
     * What DMN 1.5 defines but Praecepta does not evaluate yet, such as a built-in function (0.9, #117): its value is
     * null, with an error; {@link FeelExpression#unsupported()} names it, so a decision that uses it is reported.
     *
     * @param what what it is, naming the release that plans it
     */
    record Unsupported(String what, List<Node> arguments) implements Node {
        @Override
        public Object evaluate(Env env) {
            return env.error(what + " is not supported yet");
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
            var value = operand.evaluate(env);
            var type = FeelValues.simpleType(value);
            if (type == null || FeelArithmetic.negationType(type) == null) {
                return env.reporting() ? env.error("-%s: FEEL negates numbers and durations, not a %s"
                        .formatted(FeelValues.text(value), FeelValues.typeName(value))) : null;
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
            var a = left.evaluate(env);
            var b = right.evaluate(env);
            var typeA = FeelValues.simpleType(a);
            var typeB = FeelValues.simpleType(b);
            if (typeA == null || typeB == null || FeelArithmetic.type(operator, typeA, typeB) == null) {
                return env.reporting() ? env.error("%s %s %s: FEEL's arithmetic does not combine a %s and a %s".formatted(
                        FeelValues.text(a), operator.symbol(), FeelValues.text(b), FeelValues.typeName(a), FeelValues.typeName(b))) : null;
            }
            if (a instanceof String x && b instanceof String y && x.length() + (long) y.length() > Env.MAX_STRING) {
                return env.error("the string %s makes would be longer than %d characters".formatted(operator.symbol(), Env.MAX_STRING));
            }
            var value = FeelArithmetic.apply(operator, a, b);
            if (value == null && env.reporting()) {
                env.error("%s %s %s has no value in FEEL".formatted(FeelValues.text(a), operator.symbol(), FeelValues.text(b)));
            }
            return value;
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
     * {@link FeelValues#equal} has them, {@code <}, {@code <=}, {@code >}, {@code >=} of values FEEL orders; null,
     * with an error, otherwise.
     */
    record Comparison(String operator, Node left, Node right) implements Node {
        @Override
        public Object evaluate(Env env) {
            var a = left.evaluate(env);
            var b = right.evaluate(env);
            var value = switch (operator) {
                case "=" -> FeelValues.equal(a, b);
                case "!=" -> FeelValues.not(FeelValues.equal(a, b));
                default -> {
                    var order = FeelValues.compare(a, b);
                    yield order == null ? null : holds(order);
                }
            };
            if (value == null && env.reporting()) {
                env.error("%s %s %s: FEEL does not compare a %s and a %s".formatted(FeelValues.text(a), operator, FeelValues.text(b),
                        FeelValues.typeName(a), FeelValues.typeName(b)));
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

    /** {@code value between low and high}: {@code value >= low and value <= high} (DMN 1.5, Table 55). */
    record Between(Node value, Node low, Node high) implements Node {
        @Override
        public Object evaluate(Env env) {
            var v = value.evaluate(env);
            var from = FeelValues.compare(v, low.evaluate(env));
            var to = FeelValues.compare(v, high.evaluate(env));
            var result = FeelValues.and(from == null ? null : from >= 0, to == null ? null : to <= 0);
            return result == null ? env.error("between compares values FEEL does not order: " + FeelValues.text(v)) : result;
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
     * A positive unary test after {@code in} (DMN 1.5, 10.3.2.10 and Table 55): an expression that names {@code ?}
     * holds when it is true with {@code ?} bound to the value; one whose value is a list holds when the value is in it,
     * each element taken as a test (a range holds what is in it, any other element what equals it), and is false, not
     * null, when none holds, as {@code list contains} is (the DMN TCK's {@code 0072-feel-in} has
     * {@code true in [false, 2, 3]} false); a range holds what is in it, and a range that is null (an invalid one) holds
     * nothing known; any other value holds what equals it.
     */
    record Test(Node expression, boolean questionMark) {
        Object holds(Object value, Env env) {
            if (questionMark) {
                return test(expression.evaluate(env.with(Collections.singletonMap("?", value))));
            }
            var test = expression.evaluate(env);
            if (test instanceof List<?> list) {
                for (var element : list) {
                    var holds = element instanceof FeelRange range ? FeelValues.inRange(value, range) : FeelValues.equal(value, element);
                    if (Boolean.TRUE.equals(holds)) {
                        return true;
                    }
                }
                return false;
            }
            if (test == null && (expression instanceof RangeLiteral || expression instanceof UnaryRange)) {
                return null;
            }
            return test instanceof FeelRange range ? FeelValues.inRange(value, range) : FeelValues.equal(value, test);
        }
    }

    /** {@code value in tests}: whether one of the positive unary tests holds (DMN 1.5, Table 55), as FEEL's {@code or}. */
    record In(Node value, List<Test> tests) implements Node {
        @Override
        public Object evaluate(Env env) {
            var v = value.evaluate(env);
            Object result = false;
            for (var test : tests) {
                result = FeelValues.or(result, test.holds(v, env));
                if (Boolean.TRUE.equals(result)) {
                    return result;
                }
            }
            return result == null ? env.error("in tests " + FeelValues.text(v) + " against values FEEL does not compare it with") : result;
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
            return FeelValues.isInstance(value.evaluate(env), of);
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
                    return env.error("a range's endpoints %s and %s are not of one type FEEL orders, in order"
                            .formatted(FeelValues.text(from), FeelValues.text(to)));
                }
            }
            return FeelRange.between(from, startIncluded, to, endIncluded);
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
    record UnaryRange(String operator, Node endpoint) implements Node {
        @Override
        public Object evaluate(Env env) {
            var value = endpoint.evaluate(env);
            return value == null ? env.error("the endpoint of " + operator + " is null") : FeelRange.comparison(operator, value);
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
                case null -> env.error("null has no '" + name + "'");
                case Map<?, ?> context -> context.containsKey(name) ? context.get(name)
                        : env.error("the context has no entry '" + name + "'");
                case List<?> list -> {
                    var values = new ArrayList<>(list.size());
                    list.forEach(element -> values.add(element instanceof Map<?, ?> context ? context.get(name) : null));
                    yield Collections.unmodifiableList(values);
                }
                default -> {
                    var property = FeelValues.property(value, name);
                    yield property != FeelValues.NO_PROPERTY ? property
                            : env.error("a %s has no property '%s'".formatted(FeelValues.typeName(value), name));
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
     * from the end when negative (null, with an error, where there is none); else the elements for which the condition
     * is true, each tested in a scope with {@code item} bound to it and, for a context, its entries, which come first.
     * A value that is not a list is filtered as a list of one (the DMN TCK's {@code 0069-feel-list}). Whether the
     * condition is a number is told by its value for the first element.
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
                var test = condition.evaluate(env.with(Collections.singletonMap("item", null)));
                return test instanceof BigDecimal ? env.error("an empty list has no element " + test) : List.of();
            }
            var selected = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (!env.step()) {
                    return null;
                }
                var element = list.get(i);
                var test = condition.evaluate(env.with(itemScope(element)));
                if (i == 0 && test instanceof BigDecimal index) {
                    return element(list, index, env);
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

        private static Object element(List<?> list, BigDecimal index, Env env) {
            int size = list.size();
            if (index.signum() != 0 && index.stripTrailingZeros().scale() <= 0 && index.abs().compareTo(BigDecimal.valueOf(size)) <= 0) {
                int position = index.intValueExact();
                return list.get(position > 0 ? position - 1 : size + position);
            }
            return env.error("a list of %d has no element %s".formatted(size, index.toPlainString()));
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
     * closes over the scope it is evaluated in.
     *
     * @param types each parameter's declared type, {@link FeelType#ANY} where it declares none
     * @param depth the depth of the body's tree, which invoking it adds to the nesting {@link Env} bounds
     */
    record FunctionDefinition(List<String> parameters, List<FeelType> types, Node body, int depth) implements Node {
        @Override
        public Object evaluate(Env env) {
            return new Closure(this, env);
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
     * callee evaluates to. Positional arguments must be as many as its parameters; named ones each name a parameter,
     * those not named being null. Otherwise, or for a callee that is not a function, it is null, with an error.
     *
     * @param names the arguments' names, in order; empty for positional arguments
     */
    record Invocation(Node callee, List<Node> arguments, List<String> names) implements Node {
        @Override
        public Object evaluate(Env env) {
            var function = callee.evaluate(env);
            if (!(function instanceof FeelFunction f)) {
                return env.error(FeelValues.text(function) + " is not a function, and cannot be invoked");
            }
            var parameters = f.parameters();
            List<Object> values;
            if (names.isEmpty()) {
                if (arguments.size() != parameters.size()) {
                    return env.error("the function takes %d arguments, not %d".formatted(parameters.size(), arguments.size()));
                }
                values = new ArrayList<>(arguments.size());
                for (var argument : arguments) {
                    values.add(argument.evaluate(env));
                }
            } else {
                values = new ArrayList<>(Collections.nCopies(parameters.size(), null));
                for (int i = 0; i < names.size(); i++) {
                    int position = parameters.indexOf(names.get(i));
                    if (position < 0) {
                        return env.error("the function has no parameter '" + names.get(i) + "'");
                    }
                    values.set(position, arguments.get(i).evaluate(env));
                }
            }
            return f instanceof Closure closure ? closure.call(values, env) : f.invoke(values, env.errors());
        }

        @Override
        public FeelType type(TypeEnv env) {
            var callee = this.callee.type(env);
            arguments.forEach(argument -> argument.type(env));
            return callee instanceof FeelType.FunctionOf(var parameters, var result) ? result : FeelType.ANY;
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
     * {@code partial} is the list of the values so far.
     */
    record For(List<String> variables, List<Node> domains, Node body) implements Node {
        @Override
        public Object evaluate(Env env) {
            var results = new ArrayList<>();
            var partial = Collections.unmodifiableList(results);
            boolean complete = Iteration.over(variables, domains, env, scope -> {
                var bound = new LinkedHashMap<String, Object>();
                bound.put("partial", partial);
                results.add(body.evaluate(scope.with(bound)));
                return true;
            });
            return complete ? Collections.unmodifiableList(new ArrayList<>(results)) : null;
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
     * {@code some a in as satisfies condition}, {@code every a in as satisfies condition} (DMN 1.5, 10.3.2.14): whether
     * the condition is true for some, or every, combination of the iteration variables.
     */
    record Quantified(boolean every, List<String> variables, List<Node> domains, Node condition) implements Node {
        @Override
        public Object evaluate(Env env) {
            boolean[] found = {every};
            boolean complete = Iteration.over(variables, domains, env, scope -> {
                boolean holds = Boolean.TRUE.equals(condition.evaluate(scope));
                if (holds != every) {
                    found[0] = holds;
                    return false;
                }
                return true;
            });
            return complete ? found[0] : null;
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
