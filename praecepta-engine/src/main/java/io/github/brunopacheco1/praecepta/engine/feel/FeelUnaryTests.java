/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * FEEL unary tests (DMN 1.5, grammar rules 13 to 17), as a decision table's input entry holds them, parsed once and
 * applied to as many values as needed: whether a value, the value of the entry's input expression, satisfies them.
 *
 * <pre>{@code
 * var tests = FeelUnaryTests.parse("> Limit * 2, [Low..Low + 10]", FeelExpression.scope(List.of("Limit", "Low"), Map.of()));
 * tests.names();                                                  // [Limit, Low]
 * tests.matches(new BigDecimal("25"), Map.of("Limit", BigDecimal.TEN, "Low", BigDecimal.ONE)::get, null); // true
 * }</pre>
 *
 * <p><b>The tests</b> (DMN 1.5, 10.3.2.10 and Table 55, as FEEL's {@code in} applies a positive unary test):
 * {@code -} is satisfied by every value, a missing one included. Positive unary tests {@code t1, t2, ...} are satisfied
 * when one of them is true, FEEL's three-valued {@code or}; {@code not(t1, t2, ...)} when that is false, so neither a
 * test nor its {@code not(...)} is satisfied where the test is null, as for a comparison with a missing value. Each
 * positive unary test is an expression (grammar rule 15), whose endpoints and values may be any expression: names, as
 * of other inputs, arithmetic, paths, date and time literals, lists and ranges:
 * <ul>
 * <li>one that names {@code ?}, the value tested, is true where its value is true ({@code ? > 5 and ? < 10});</li>
 * <li>{@code = e} and {@code != e} compare the value with {@code e}, {@code ? = e} and {@code ? != e}, as Table 53
 * defines them, so {@code = null} is true for a missing value;</li>
 * <li>any other one is evaluated, and is true when its value is a range that holds the value ({@code < e},
 * {@code [a..b]}), a list one of whose elements holds it (a range element) or equals it (any other), or a value that
 * equals it, as Table 53 has {@code =}: {@code true} is satisfied by {@code true} alone. A comparison FEEL does not
 * define (a number and a string) is null.</li>
 * </ul>
 * Where DMN 1.5 leaves how a test that does not name {@code ?} but gives a boolean applies, it is compared, as any other
 * value is (docs/DMN-CONFORMANCE.md, case 24).
 *
 * <p>The limits of {@link FeelExpression} apply to each test: the tests nest at most 300 levels, and one application
 * takes at most a million steps. FEEL's built-in functions are planned for 0.9
 * (<a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>): {@link #unsupported()} names those a test
 * invokes. Immutable and thread-safe.
 */
public final class FeelUnaryTests {

    private final String text;
    private final List<Node.Test> tests;
    private final boolean negated;
    private final List<String> names;
    private final List<String> unsupported;
    private final int depth;

    private FeelUnaryTests(String text, FeelParser.ParsedTests parsed) {
        this.text = text;
        this.tests = parsed.tests();
        this.negated = parsed.negated();
        this.names = parsed.names();
        this.unsupported = parsed.unsupported();
        this.depth = parsed.depth();
    }

    /**
     * Parses unary tests with names in scope, those of the values they may read (DMN 1.5, 10.3.2.11), as a decision
     * table's input entry is parsed with the names of the table's inputs and its decision's requirements.
     *
     * @throws ExpressionSyntaxException if they are not unary tests, or nest too deeply
     */
    public static FeelUnaryTests parse(String text, FeelExpression.Scope scope) {
        Objects.requireNonNull(text, "text");
        return new FeelUnaryTests(text, FeelParser.parseUnaryTests(text, scope.names(), scope.types));
    }

    /**
     * Reads a decision table's input entry that {@link CellParser#parseFeel} rejected: as FEEL unary tests, unless they
     * are made only of what that subset reads ({@link #isSimple()}), whose rejection then stands, as such an entry is
     * wrong (a literal of another type than its column's, an interval that starts after it ends), not beyond the
     * subset.
     *
     * @param rejected why the subset rejected it
     * @throws CellSyntaxException {@code rejected}, if the entry is simple, or why it is not FEEL unary tests
     */
    public static FeelUnaryTests parseCell(String cell, FeelExpression.Scope scope, CellSyntaxException rejected) {
        FeelUnaryTests tests;
        try {
            tests = parse(cell == null ? "" : cell, scope);
        } catch (ExpressionSyntaxException e) {
            throw rejected;
        }
        if (tests.isSimple()) {
            throw rejected;
        }
        return tests;
    }

    /** The tests as written. */
    public String text() {
        return text;
    }

    /**
     * The names the tests read from outside, in the order first written: those of other inputs, in scope or read as
     * words; not {@code ?}, which is the value tested.
     */
    public List<String> names() {
        return names;
    }

    /** The literal values the tests hold, in the order written, as {@link FeelExpression#literals()} gives them. */
    public List<Object> literals() {
        return Node.literals(tests.stream().map(Node.Test::expression).toList());
    }

    /**
     * What the tests use that DMN 1.5 defines but Praecepta does not evaluate yet, each naming the release that plans it,
     * as {@link FeelExpression#unsupported()} names it; empty if they use none.
     */
    public List<String> unsupported() {
        return unsupported;
    }

    /** Whether they are {@code -}, satisfied by every value. */
    public boolean isAny() {
        return tests.isEmpty();
    }

    /**
     * Whether every test is one of DMN 1.5's simple positive unary tests (grammar rule 7), or {@code null}: a literal or a
     * name, a comparison with one, or an interval between two, which {@link CellParser} reads into the tests the bitset
     * index of a decision table evaluates.
     */
    public boolean isSimple() {
        return tests.stream().allMatch(FeelUnaryTests::simple);
    }

    private static boolean simple(Node.Test test) {
        return switch (test.expression()) {
            case Node.Equality equality -> simpleValue(equality.value());
            case Node.UnaryRange range -> simpleValue(range.endpoint());
            case Node.RangeLiteral range -> simpleValue(range.start()) && simpleValue(range.end());
            default -> !test.questionMark() && simpleValue(test.expression());
        };
    }

    /** A literal, or a name as {@link CellParser} reads one: words, with dots between them. */
    private static boolean simpleValue(Node node) {
        return switch (node) {
            case Node.Constant constant -> true;
            case Node.Invalid invalid -> true;
            case Node.Name(var name) -> CellParser.isName(name);
            case Node.Path path -> CellParser.isName(pathText(path));
            default -> false;
        };
    }

    /** A path of names as text ({@code a.b.c}), or text that is no name if it is anything else. */
    private static String pathText(Node node) {
        var text = new StringBuilder();
        var at = node;
        while (at instanceof Node.Path(var target, var name)) {
            text.insert(0, "." + name);
            at = target;
        }
        return at instanceof Node.Name(var name) ? name + text : "(" + text;
    }

    /**
     * Whether a value satisfies the tests, as a decision table's rule matches it: when they are true for it. A test that
     * is null for it (a comparison FEEL does not define, or with a missing value) is not satisfied, and neither is its
     * {@code not(...)}.
     *
     * @param value  the value tested, {@code ?}; null for a missing one
     * @param values the value of each name the tests read, of its type; null for a name with no value
     * @param errors receives each error FEEL reports on the way (DMN 1.5, 10.3.2.15); may be null
     */
    public boolean matches(Object value, Function<String, Object> values, Consumer<String> errors) {
        if (tests.isEmpty()) {
            return true;
        }
        var env = Env.root(Objects.requireNonNull(values, "values"), errors, depth);
        Object result = false;
        for (var test : tests) {
            result = FeelValues.or(result, test.holds(value, env));
            if (Boolean.TRUE.equals(result)) {
                break;
            }
        }
        return negated ? Boolean.FALSE.equals(result) : Boolean.TRUE.equals(result);
    }

    /** The tests as written. */
    @Override
    public String toString() {
        return text;
    }
}
