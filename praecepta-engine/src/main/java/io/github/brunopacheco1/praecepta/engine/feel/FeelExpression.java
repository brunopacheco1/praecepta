/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A FEEL expression (DMN 1.5, chapter 10), parsed once and evaluated as often as needed: the full expression language
 * of 10.3.1.2, with the semantics of 10.3.2. It is what a literal expression decision of a {@code DecisionGraph}
 * evaluates, and the entry point for any other part of Praecepta that reads FEEL.
 *
 * <pre>{@code
 * var expression = FeelExpression.parse("for x in Amounts return if x > Limit then Limit else x", List.of("Amounts", "Limit"));
 * expression.names();                         // [Amounts, Limit]
 * expression.type(name -> name.equals("Amounts") ? FeelType.listOf(ColumnType.NUMBER) : FeelType.of(ColumnType.NUMBER));
 *                                             // list<number>
 * expression.evaluate(Map.of("Amounts", List.of(new BigDecimal("5"), new BigDecimal("50")), "Limit", BigDecimal.TEN)::get);
 *                                             // [5, 10]
 * }</pre>
 *
 * <p><b>The language.</b> Literals: strings, numbers (decimals of 34 digits, FEEL's decimal128), {@code true},
 * {@code false}, {@code null}, and date time literals, {@code @"2025-01-31"} and {@code date("2025-01-31")},
 * {@code time(...)}, {@code date and time(...)} and {@code duration(...)} of literal arguments; lists {@code [1, 2]},
 * contexts {@code {a: 1, "b c": a + 1}}, whose entries see those before them, and ranges {@code [1..10)},
 * {@code (< 10)}, {@code (= 10)}; arithmetic ({@code + - * / **}, unary minus), comparisons ({@code = != < <= > >=}),
 * {@code and}, {@code or}, {@code between}, {@code in} with positive unary tests, {@code instance of} with FEEL's types
 * ({@code number}, {@code list<number>}, {@code context<a: number>}, {@code range<date>},
 * {@code function<number>->number}, {@code Any}, {@code Null}, and types the model defines), {@code if then else},
 * {@code for} (with {@code partial} and iteration ranges {@code 1..3}), {@code some} and {@code every}, paths
 * ({@code a.b.c}, over lists as projections, and the properties of dates, times, durations and ranges), filters
 * ({@code xs[item > 3]}, {@code xs[1]}, {@code xs[-1]}), function definitions ({@code function(a, b) a + b}, which close
 * over their scope) and their invocation with positional or named arguments, and comments. FEEL's built-in functions
 * (10.3.4) are planned for 0.9 (<a href="https://github.com/brunopacheco1/praecepta/issues/117">#117</a>): an
 * expression that invokes one parses, and {@link #unsupported()} names it.
 *
 * <p><b>Values given.</b> A name's value is a FEEL value, null for a name with none; a {@link Number} that is not a
 * {@code BigDecimal} ({@code Integer}, {@code Long}, {@code Double}...) is taken as the {@code BigDecimal} of its
 * value. A {@link FeelFunction} is a function only if Praecepta made it: the value of a function definition an
 * evaluation gave.
 *
 * <p><b>Names.</b> A name may hold spaces, keywords and the additional name symbols {@code . / - ' + *}, so the
 * expression is parsed against the names in scope, the longest of which wins wherever the text holds one
 * ({@link #parse(String, Collection)}). A name not in scope is read as words up to a keyword or an operator.
 *
 * <p><b>Values.</b> FEEL's values as {@link FeelType} lists them: {@code String}, {@code BigDecimal}, {@code Boolean},
 * {@code LocalDate}, times, date-times, {@code Duration}, {@link YearsAndMonths}, {@code List}, {@code Map} (a
 * context, in entry order), {@link FeelRange} and {@link FeelFunction}; null is FEEL's null. Where FEEL defines no
 * value (operands it does not combine, a division by zero, an entry a context does not have, an invalid date literal),
 * the value is null and an error is reported to whoever listens ({@link #evaluate(Function, Consumer)}, DMN 1.5,
 * 10.3.2.16).
 *
 * <p><b>Limits.</b> An expression's tree nests at most 300 levels, and its text at most 300 levels of parentheses,
 * brackets and braces, fewer of {@code if}, {@code for}, {@code some}, {@code every}, {@code function}, filters and
 * invocations, which take more of the parser's stack; deeper input is a syntax error, so parsing and evaluating it take
 * a bounded amount of the thread's stack, whatever its length. One evaluation takes at most 1,000,000 steps
 * (iterations of its {@code for}, {@code some} and {@code every} and the elements of the ranges they iterate, function
 * invocations, the elements its filters test, its paths project and its {@code in} looks through, and the values its
 * equalities and {@code instance of} look at), nests at most 800 levels (its own depth and that of the function bodies
 * it invokes, each within the other), and makes strings of at most 10,000,000 characters in all with {@code +}; beyond
 * any of these, its value is null, with an error. It reports at most 100 errors, and then one that counts the others.
 * An expression is immutable and thread-safe.
 */
public final class FeelExpression {

    /**
     * What DMN 1.5 defines but Praecepta does not evaluate yet ({@link #unsupported()}), and the release that plans it.
     *
     * @param feature what it is: {@code the built-in function 'string length'}, {@code an external function (Java or PMML)}
     * @param release the release that plans it: {@code 0.9}
     * @param issue   the GitHub issue that plans it: 117
     */
    public record Unsupported(String feature, String release, int issue) {
        public Unsupported {
            java.util.Objects.requireNonNull(feature, "feature");
            java.util.Objects.requireNonNull(release, "release");
        }

        /** As messages name it: {@code the built-in function 'string length', planned for 0.9 (#117)}. */
        @Override
        public String toString() {
            return "%s, planned for %s (#%d)".formatted(feature, release, issue);
        }
    }

    private final String text;
    private final Node root;
    private final List<String> names;
    private final List<Unsupported> unsupported;
    private final int depth;

    private FeelExpression(String text, FeelParser.Parsed parsed) {
        this.text = text;
        this.depth = parsed.depth();
        this.root = parsed.root();
        this.names = parsed.names();
        this.unsupported = parsed.unsupported();
    }

    /**
     * Parses an expression with no names in scope but those it binds itself, and FEEL's built-in functions: every other
     * name is read as words. This checks its syntax; to evaluate it, parse it with the names it reads in scope.
     *
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply
     */
    public static FeelExpression parse(String text) {
        return new FeelExpression(Objects.requireNonNull(text, "text"), FeelParser.parse(text, NO_NAMES.names(), null));
    }

    /** No names, indexed when first parsed against, so that loading this class does not load the parser. */
    private static final Scope NO_NAMES = new Scope(List.of(), Map.of());

    /**
     * Parses an expression with names in scope: those its values will be given for (DMN 1.5, 10.3.2.11).
     *
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply
     */
    public static FeelExpression parse(String text, Collection<String> names) {
        return parse(text, scope(names, Map.of()));
    }

    /**
     * Parses an expression with names in scope, and the types a model defines (its item definitions), which
     * {@code instance of} and a function's parameters may name. A type that is neither FEEL's nor one of them makes
     * what names it null, with an error (DMN 1.5, Table 61), and its type {@link FeelType#NULL}.
     *
     * @param types the types, by name
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply
     */
    public static FeelExpression parse(String text, Collection<String> names, Map<String, FeelType> types) {
        return parse(text, scope(names, types));
    }

    /**
     * Parses an expression in a scope made once for many expressions ({@link #scope}), as a decision model's names are.
     *
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply
     */
    public static FeelExpression parse(String text, Scope scope) {
        Objects.requireNonNull(text, "text");
        return new FeelExpression(text, FeelParser.parse(text, scope.names(), scope.types));
    }

    /**
     * Names and the types a model defines, in scope for parsing expressions ({@link #parse(String, Scope)}): indexed once,
     * so that each expression parsed against many names costs no more than one parsed against few.
     *
     * @param names the names in scope
     * @param types the types the model defines, by name
     */
    public static Scope scope(Collection<String> names, Map<String, FeelType> types) {
        return new Scope(List.copyOf(names), Map.copyOf(types));
    }

    /**
     * Names in scope, where any type name that is not FEEL's reads as {@code Any}, as a check of syntax alone reads it:
     * for finding which names expressions read, without the types of the model they belong to.
     */
    public static Scope namesScope(Collection<String> names) {
        return new Scope(List.copyOf(names), null);
    }

    /** Names, and the types a model defines, in scope for parsing expressions; immutable and thread-safe. */
    public static final class Scope {
        private final List<String> given;
        final Map<String, FeelType> types;
        /** The names, indexed when the first expression is parsed, so a scope no expression is parsed in costs nothing. */
        private final AtomicReference<FeelParser.Names> names = new AtomicReference<>();

        private Scope(List<String> given, Map<String, FeelType> types) {
            this.given = given;
            this.types = types;
        }

        /** The names, indexed once; two threads may both index them, which gives equal indexes. */
        FeelParser.Names names() {
            var indexed = names.get();
            if (indexed == null) {
                indexed = new FeelParser.Names(given);
                names.compareAndSet(null, indexed);
            }
            return indexed;
        }
    }

    /** The expression as written. */
    public String text() {
        return text;
    }

    /**
     * The names the expression reads from outside, in the order first written: names in scope when it was parsed, and
     * names that are not, read as words, but for those inside a filter, which may name the entries of the elements it
     * filters. The names it binds itself (iteration variables, parameters, context entries, {@code item}) are not among
     * them, nor are the built-in functions it invokes.
     */
    public List<String> names() {
        return names;
    }

    /**
     * The literal values the expression holds, in the order written: strings, numbers, booleans, and the dates, times,
     * date-times and durations of its date and time literals ({@code @"10:30:00Z"}, {@code time("10:30:00")}); not
     * {@code null}. A code generator reads from them, say, whether the times an expression gives have a time zone.
     */
    public List<Object> literals() {
        return Node.literals(List.of(root));
    }

    /**
     * What the expression uses that DMN 1.5 defines but Praecepta does not evaluate yet, each with the release that
     * plans it: a built-in function ({@code the built-in function 'string', planned for 0.9 (#117)}) or an external
     * function (0.11, #119). Empty if it uses none; where it uses one, its value there is null, with an error.
     */
    public List<Unsupported> unsupported() {
        return unsupported;
    }

    /**
     * The type of the expression's value (DMN 1.5, 10.3.2.9), given the types of the names it reads: {@link FeelType#ANY}
     * where only its values will tell, {@link FeelType#NULL} where its value is null whatever its inputs (operands FEEL
     * does not combine, such as a string and a number, which give null rather than fail).
     *
     * @param types the type of each name it reads; null for a name of unknown type
     */
    public FeelType type(Function<String, FeelType> types) {
        return type(types, null);
    }

    /**
     * The type of the expression's value, as {@link #type(Function)} gives it, telling {@code nullReasons} why a part of
     * it is null whatever its inputs ({@code + combines a string and a number, which gives null in FEEL}); the first
     * reason given is why the whole is, when its type is {@link FeelType#NULL}.
     *
     * @param nullReasons receives each reason; may be null
     */
    public FeelType type(Function<String, FeelType> types, Consumer<String> nullReasons) {
        return root.type(TypeEnv.root(Objects.requireNonNull(types, "types"), nullReasons));
    }

    /**
     * Evaluates the expression.
     *
     * @param values the value of each name it reads, of its type; null for a name with no value
     * @return its value, null for FEEL's null
     */
    public Object evaluate(Function<String, Object> values) {
        return evaluate(values, null);
    }

    /**
     * Evaluates the expression, telling {@code errors} of each error FEEL reports on the way (DMN 1.5, 10.3.2.16): where
     * it defines no value, as for operands it does not combine, and the part's value is null. Beyond 100 errors, one
     * more message, at the end, says how many others there were.
     *
     * @param values the value of each name it reads, of its type; null for a name with no value
     * @param errors receives each error's message; may be null
     * @return its value, null for FEEL's null
     */
    public Object evaluate(Function<String, Object> values, Consumer<String> errors) {
        var env = Env.root(Objects.requireNonNull(values, "values"), errors, depth);
        try {
            return env.result(root.evaluate(env));
        } finally {
            env.finish();
        }
    }

    /** The expression as written. */
    @Override
    public String toString() {
        return text;
    }
}
