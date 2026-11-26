/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLexer.Kind;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLexer.Token;
import io.github.brunopacheco1.praecepta.engine.feel.FeelArithmetic.Operator;

/**
 * Parses a FEEL expression (DMN 1.5, 10.3.1.2) into a tree of {@link Node}s. Operators bind as FEEL's grammar has
 * them, from the loosest: the textual expressions ({@code if}, {@code for}, {@code some}, {@code every},
 * {@code function}), whose last part reaches as far as it can; {@code or}; {@code and}; comparisons, {@code between}
 * and {@code in}; {@code instance of}; {@code +} and {@code -}; {@code *} and {@code /}; {@code **}; unary minus, which
 * binds tighter than {@code **} ({@code -4 ** 2} is 16, as the DMN TCK has it); and paths, filters and invocations.
 * Binary operators associate to the left, {@code **} included.
 *
 * <p><b>Names.</b> A FEEL name may hold spaces and the additional name symbols {@code . / - ' + *} (grammar rules 27 to
 * 30), and even keywords ({@code Another Date and Time}), so a name is read against the names in scope (10.3.1.2,
 * "names"): the longest one that the text holds at that point, word for word and symbol for symbol, with whitespace
 * where the name has a space and none where it has none. In scope are the names the expression is given, those its
 * own constructs bind (iteration variables, {@code partial}, parameters, context entries before the current one, a
 * filter's {@code item}, a unary test's {@code ?}) and the built-in functions, a built-in's name counting only where
 * it is invoked. Where no name in scope is there, the name is read as words separated by whitespace, up to a keyword
 * or anything else: outside a filter it is a name the expression reads, which the caller resolves; inside one, an
 * entry of the elements it filters.
 *
 * <p><b>Literals.</b> An invocation of {@code date}, {@code time}, {@code date and time} or {@code duration} whose
 * arguments are literals is a date time literal (grammar rule 60), evaluated here, as is an at-literal
 * ({@code @"2025-01-31"}); one that gives no valid value is null, with an error, when it is evaluated (the DMN TCK's
 * {@code 1115-feel-date-function} to {@code 1120-feel-duration-function}). Hour 24 is read as {@link TemporalLiterals}
 * reads it. A number literal is rounded to 34 significant digits (10.3.2.3.1).
 *
 * <p><b>Limits.</b> The evaluator recurses one call a level of the tree, whose depth is bounded by {@link #MAX_DEPTH}.
 * The parser recurses through several calls a level of nesting, as many as the construct takes: seven for
 * parentheses, eight for an {@code if}, nine for a {@code for}, and so on; it counts them against {@link #MAX_CALLS},
 * which lets parentheses nest {@link #MAX_PARENTHESES} levels and the other constructs fewer. Deeper input is a syntax
 * error, whatever the thread's stack: so every construct, nested as deeply as it may be, is parsed, typed and evaluated
 * on a stack of 448 KiB (FeelExpressionStackTest).
 */
final class FeelParser {

    /** The deepest an expression's tree may nest. */
    static final int MAX_DEPTH = 300;
    /** The deepest parentheses, brackets and braces may nest in an expression's text. */
    static final int MAX_PARENTHESES = 250;
    /**
     * How many of the parser's calls the text's nesting may take at once: an expression's own, and those of
     * {@link #MAX_PARENTHESES} levels of parentheses, which take seven a level ({@code expression}, {@code binary},
     * {@code unary}, {@code postfix}, {@code primary}, {@code symbol}, {@code parenthesis}).
     */
    static final int MAX_CALLS = 1 + 7 * MAX_PARENTHESES;
    /** A minus sign: one call, but counted as two, as a long run of them takes a large frame each. */
    private static final int MINUS = 2;
    /** The calls a level of each construct takes, from the {@code expression} it is in to the one it holds. */
    private static final int PARENTHESES = 6;
    private static final int OPERAND = 2;
    private static final int KEYWORD = 7;
    private static final int ITERATION = 8;
    private static final int FILTER = 4;
    private static final int ARGUMENTS = 6;
    private static final int TESTS = 5;
    private static final int UNARY_RANGE = 6;
    /** Two calls ({@code type}, {@code parameterType}), counted as three, as a type's frames are large. */
    private static final int TYPE = 3;
    private static final String UNEXPECTED = "unexpected '";
    private static final String NESTS_MORE_THAN = "it nests more than ";
    private static final String LEVELS = " levels";
    private static final String FUNCTION = "function";
    private static final String BETWEEN = "between";
    private static final String INSTANCE_WORD = "instance";
    private static final String EVERY = "every";
    private static final String FALSE = "false";

    /** Words that end a name read word by word: FEEL's keywords and the words of its operators. */
    private static final Set<String> STOP_WORDS = Set.of("and", "or", "in", BETWEEN, INSTANCE_WORD, "then", "else", "return",
            "satisfies", "if", "for", "some", EVERY, FUNCTION, "true", FALSE, "null");
    /** The keywords that start an expression. */
    private static final Set<String> KEYWORDS = Set.of("if", "for", "some", EVERY, FUNCTION, "true", FALSE, "null");
    /** FEEL's names of its built-in types (10.3.2.9.1). */
    private static final Map<String, FeelType> TYPE_NAMES = Map.of(
            "number", FeelType.of(ColumnType.NUMBER), "string", FeelType.of(ColumnType.STRING),
            "boolean", FeelType.of(ColumnType.BOOLEAN), "date", FeelType.of(ColumnType.DATE), "time", FeelType.of(ColumnType.TIME),
            "date and time", FeelType.of(ColumnType.DATE_TIME), "days and time duration", FeelType.of(ColumnType.DAY_TIME_DURATION),
            "years and months duration", FeelType.of(ColumnType.YEAR_MONTH_DURATION), "Any", FeelType.ANY, "Null", FeelType.NULL);
    private static final int OR = 1;
    private static final int AND = 2;
    private static final int COMPARISON = 3;
    private static final int INSTANCE = 4;
    private static final int ADDITIVE = 5;
    private static final int MULTIPLICATIVE = 6;
    private static final int POWER = 7;

    private static final String CLOSE_PARENTHESIS = ")";
    private static final String CLOSE_BRACKET = "]";
    private static final String OPEN_BRACKET = "[";
    private static final String RANGE = "..";
    private static final String COLON = ":";
    private static final String COMMA = ",";
    private static final String GREATER = ">";
    private static final String QUESTION_MARK = "?";

    private static final Names BUILT_INS = new Names(BuiltInFunctions.NAMES);

    /** What parsing gives. */
    record Parsed(Node root, List<String> names, List<FeelExpression.Unsupported> unsupported, int depth) {
    }

    /**
     * What parsing unary tests gives: the tests, whether they are negated ({@code not(...)}); no tests for {@code -}.
     */
    record ParsedTests(List<Node.Test> tests, boolean negated, List<String> names, List<FeelExpression.Unsupported> unsupported,
            int depth) {
    }

    /** A name in scope, as tokens, for matching against the text. */
    private record Candidate(String name, List<Token> tokens) {
    }

    /** Names in scope, by their first word, indexed once for any number of expressions. */
    static final class Names {
        private final Map<String, List<Candidate>> byFirst = new HashMap<>();

        Names(Collection<String> names) {
            names.forEach(this::add);
        }

        void add(String name) {
            List<Token> tokens;
            try {
                tokens = FeelLexer.tokens(name);
            } catch (ExpressionSyntaxException e) {
                return; // a name FEEL cannot write outside a string: never in the text
            }
            tokens = tokens.subList(0, tokens.size() - 1);
            if (!tokens.isEmpty() && tokens.getFirst().kind() == Kind.WORD) {
                byFirst.computeIfAbsent(tokens.getFirst().text(), first -> new ArrayList<>()).add(new Candidate(name, tokens));
            }
        }
    }

    /** A name found in the text: the name, how many tokens it takes, and whether the expression binds it itself. */
    private record Match(String name, int length, boolean local) {
    }

    private final String text;
    private final List<Token> tokens;
    private final Names given;
    /** The types the model names (its item definitions); null to read any unknown type name as {@code Any}. */
    private final Map<String, FeelType> types;
    private final Deque<Names> locals = new ArrayDeque<>();
    private final Set<String> free = new LinkedHashSet<>();
    private final Set<FeelExpression.Unsupported> unsupported = new LinkedHashSet<>();
    private int at;
    /** The parser's calls the nesting at the current token takes ({@link #MAX_CALLS}). */
    private int calls;
    private int filters;
    private int questionMarks;
    /** The first type name read that is neither FEEL's nor one the model defines; null if none was. */
    private String unknownType;

    private FeelParser(String text, Names names, Map<String, FeelType> types) {
        this.text = text;
        this.tokens = FeelLexer.tokens(text);
        this.given = names;
        this.types = types;
    }

    /**
     * Parses an expression.
     *
     * @param names the names in scope
     * @param types the types the model names, which {@code instance of} and parameters may name; null for none
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply ({@link #MAX_DEPTH},
     *                                   {@link #MAX_CALLS})
     */
    static Parsed parse(String text, Names names, Map<String, FeelType> types) {
        return parse(text, names, types, List.of(), false);
    }

    /**
     * Parses an expression that is part of a boxed expression (DMN 1.5, 10.2), within constructs that bind names of
     * their own: the entries of a context, a function's parameters, an iterator's variable, a filter's {@code item}.
     * Those are in scope as names the expression binds, so they are not among its free names.
     *
     * @param locals the names the enclosing constructs bind, innermost last
     * @param filter whether it is a filter's match, where a name in no scope may name an entry of the element tested
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests too deeply
     */
    static Parsed parse(String text, Names names, Map<String, FeelType> types, List<String> locals, boolean filter) {
        var parser = new FeelParser(text, names, types);
        if (!locals.isEmpty()) {
            parser.locals.push(new Names(locals));
        }
        if (filter) {
            parser.filters++;
        }
        var root = parser.all();
        int depth = depthOf(root);
        if (depth > MAX_DEPTH) {
            throw parser.tooDeep();
        }
        return new Parsed(root, List.copyOf(parser.free), List.copyOf(parser.unsupported), depth);
    }

    /**
     * Parses unary tests (DMN 1.5, grammar rules 13 to 17), as a decision table's input entry holds them: {@code -}; or
     * positive unary tests, each an expression (rule 13), separated by commas; or those inside {@code not(...)}, which
     * must then be the whole text. A test written {@code = e} or {@code != e} is the comparison {@code ? = e} or
     * {@code ? != e} of the value tested, so that {@code = null} holds for a missing value as {@code null} does.
     *
     * @throws ExpressionSyntaxException if it is not unary tests, or nests too deeply
     */
    static ParsedTests parseUnaryTests(String text, Names names, Map<String, FeelType> types) {
        var negation = new FeelParser(text, names, types);
        if (negation.peek().isWord("not") && negation.peek(1).is("(")) {
            try {
                return negation.unaryTests(true);
            } catch (ExpressionSyntaxException e) {
                // not(...) is not the whole text: the text is an expression that invokes the function not()
            }
        }
        return new FeelParser(text, names, types).unaryTests(false);
    }

    private ParsedTests unaryTests(boolean negated) {
        if (negated) {
            next();
            next();
        } else if (peek().kind() == Kind.END || peek().is("-") && peek(1).kind() == Kind.END) {
            return new ParsedTests(List.of(), false, List.of(), List.of(), 0);
        }
        var tests = new ArrayList<Node.Test>();
        do {
            if (!tests.isEmpty()) {
                next();
            }
            tests.add(cellTest());
        } while (peek().is(COMMA));
        if (negated) {
            expect(CLOSE_PARENTHESIS);
        }
        if (peek().kind() != Kind.END) {
            throw error(UNEXPECTED + text.substring(peek().start()) + "'");
        }
        int depth = depthOf(new Node.In(new Node.Name(QUESTION_MARK), tests));
        if (depth > MAX_DEPTH) {
            throw tooDeep();
        }
        return new ParsedTests(List.copyOf(tests), negated, List.copyOf(free), List.copyOf(unsupported), depth);
    }

    /** One positive unary test of a cell: {@code = e} and {@code != e} as comparisons of {@code ?}, anything else as {@link #test}. */
    private Node.Test cellTest() {
        if (!peek().is("=") && !peek().is("!=")) {
            return test(this::expression);
        }
        var operator = next().text();
        locals.push(new Names(List.of(QUESTION_MARK)));
        enter(1);
        try {
            return new Node.Test(new Node.Equality(operator.equals("!="), binary(ADDITIVE)), true);
        } finally {
            leave(1);
            locals.pop();
        }
    }

    /**
     * The depth of a tree, each node counting its {@linkplain Node#weight() weight}: a measure of the stack evaluating it
     * takes, worked out with a stack of its own.
     */
    static int depthOf(Node root) {
        int deepest = 0;
        var nodes = new ArrayDeque<Node>();
        var levels = new ArrayDeque<Integer>();
        nodes.push(root);
        levels.push(root.weight());
        while (!nodes.isEmpty()) {
            var node = nodes.pop();
            int level = levels.pop();
            deepest = Math.max(deepest, level);
            if (level <= MAX_DEPTH) {
                for (var child : node.children()) {
                    nodes.push(child);
                    levels.push(level + child.weight());
                }
            }
        }
        return deepest;
    }

    private Node all() {
        if (peek().kind() == Kind.END) {
            throw error("it is empty");
        }
        var root = expression();
        if (peek().kind() != Kind.END) {
            throw error(UNEXPECTED + text.substring(peek().start()) + "'");
        }
        return root;
    }

    // ---- tokens ------------------------------------------------------------------------------------

    private Token peek() {
        return tokens.get(at);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(at + ahead, tokens.size() - 1));
    }

    private Token next() {
        var token = tokens.get(at);
        if (token.kind() != Kind.END) {
            at++;
        }
        return token;
    }

    private void expect(String symbol) {
        if (!peek().is(symbol)) {
            throw error("'%s' expected %s".formatted(symbol, where()));
        }
        next();
    }

    private void expectWord(String word) {
        if (!peek().isWord(word)) {
            throw error("'%s' expected %s".formatted(word, where()));
        }
        next();
    }

    private String where() {
        return peek().kind() == Kind.END ? "at the end" : "at '" + text.substring(peek().start()) + "'";
    }

    /** Enters a level of nesting that takes that many of the parser's calls; a syntax error beyond {@link #MAX_CALLS}. */
    private void enter(int levelCalls) {
        calls += levelCalls;
        if (calls > MAX_CALLS) {
            throw tooDeep();
        }
    }

    private void leave(int levelCalls) {
        calls -= levelCalls;
    }

    private ExpressionSyntaxException tooDeep() {
        return error(NESTS_MORE_THAN + MAX_DEPTH + LEVELS);
    }

    ExpressionSyntaxException error(String problem) {
        return new ExpressionSyntaxException("'%s' is not a valid FEEL expression: %s".formatted(text, problem));
    }

    // ---- operators ---------------------------------------------------------------------------------

    private Node expression() {
        enter(1);
        try {
            return binary(OR);
        } finally {
            leave(1);
        }
    }

    /** The precedence of the binary operator at the current token, or 0 if none is there. */
    private int precedence() {
        var token = peek();
        if (token.kind() == Kind.SYMBOL) {
            return switch (token.text()) {
                case "+", "-" -> ADDITIVE;
                case "*", "/" -> MULTIPLICATIVE;
                case "**" -> POWER;
                case "=", "!=", "<", "<=", ">", ">=" -> COMPARISON;
                default -> 0;
            };
        }
        if (token.kind() != Kind.WORD) {
            return 0;
        }
        return switch (token.text()) {
            case "or" -> OR;
            case "and" -> AND;
            case BETWEEN, "in" -> COMPARISON;
            case INSTANCE_WORD -> peek(1).isWord("of") ? INSTANCE : 0;
            default -> 0;
        };
    }

    /** Operands joined by operators that bind at least as tightly as {@code minimum}, to the left. */
    private Node binary(int minimum) {
        var left = unary();
        while (true) {
            int precedence = precedence();
            if (precedence == 0 || precedence < minimum) {
                return left;
            }
            var operator = next().text();
            left = switch (operator) {
                case "or", "and" -> new Node.Logical(operator.equals("and"), left, operand(precedence + 1));
                case BETWEEN -> between(left);
                case "in" -> new Node.In(left, tests());
                case INSTANCE_WORD -> {
                    expectWord("of");
                    yield instanceOf(left);
                }
                case "=", "!=", "<", "<=", ">", ">=" -> new Node.Comparison(operator, left, operand(precedence + 1));
                default -> new Node.Arithmetic(arithmetic(operator), left, operand(precedence + 1));
            };
        }
    }

    /** The right operand of a binary operator: two of the parser's calls, this and {@link #binary}. */
    private Node operand(int minimum) {
        enter(OPERAND);
        try {
            return binary(minimum);
        } finally {
            leave(OPERAND);
        }
    }

    private static Operator arithmetic(String symbol) {
        return switch (symbol) {
            case "+" -> Operator.ADD;
            case "-" -> Operator.SUBTRACT;
            case "*" -> Operator.MULTIPLY;
            case "/" -> Operator.DIVIDE;
            default -> Operator.POWER;
        };
    }

    private Node between(Node value) {
        enter(1);
        try {
            var low = operand(INSTANCE);
            expectWord("and");
            return new Node.Between(value, low, operand(INSTANCE));
        } finally {
            leave(1);
        }
    }

    /**
     * The positive unary tests after {@code in} (grammar rule 6): a list of them in parentheses, an interval, or one test.
     */
    private List<Node.Test> tests() {
        if (!peek().is("(")) {
            enter(TESTS);
            try {
                return List.of(test(() -> binary(ADDITIVE)));
            } finally {
                leave(TESTS);
            }
        }
        next();
        enter(TESTS);
        var first = test(this::expression);
        if (peek().is(RANGE)) {
            next();
            var end = expression();
            var range = new Node.RangeLiteral(first.expression(), false, end, rangeClose());
            leave(TESTS);
            return List.of(new Node.Test(range, first.questionMark()));
        }
        var tests = new ArrayList<Node.Test>();
        tests.add(first);
        while (peek().is(COMMA)) {
            next();
            tests.add(test(this::expression));
        }
        expect(CLOSE_PARENTHESIS);
        leave(TESTS);
        return tests;
    }

    /** A unary test, in whose scope {@code ?} is the value tested. */
    private Node.Test test(Supplier<Node> parse) {
        locals.push(new Names(List.of(QUESTION_MARK)));
        int before = questionMarks;
        try {
            var node = parse.get();
            return new Node.Test(node, questionMarks > before);
        } finally {
            locals.pop();
        }
    }

    /**
     * {@code -e}, or a primary expression. A minus written right before a number ({@code -1}) is folded into the
     * number's literal; one with whitespace after it ({@code - 1}), which the simple unary tests of a decision table's
     * cell do not read, is a negation, evaluated as one.
     */
    private Node unary() {
        if (!peek().is("-")) {
            return postfix();
        }
        next();
        boolean attached = !peek().spaceBefore();
        enter(MINUS);
        try {
            var operand = unary();
            return attached && operand instanceof Node.Constant(BigDecimal number) ? new Node.Constant(FeelNumbers.negate(number))
                    : new Node.Negation(operand);
        } finally {
            leave(MINUS);
        }
    }

    /** A primary expression followed by paths, filters and invocations. */
    private Node postfix() {
        var node = primary();
        while (true) {
            if (peek().is(".") && peek(1).kind() == Kind.WORD) {
                next();
                node = new Node.Path(node, wordsName());
            } else if (peek().is(OPEN_BRACKET) && !endsAfter(1)) {
                next();
                node = new Node.Filter(node, filterCondition());
            } else if (peek().is("(")) {
                node = invocation(node);
            } else {
                return node;
            }
        }
    }

    /**
     * Whether the token {@code ahead} of the current one ends an expression, so a {@code [} before it is not a filter
     * but the end of a range ({@code [1..10[}); a path after the range ({@code [1..10[.start included}, the DMN TCK's
     * {@code 0074-feel-properties}) starts with a {@code .}, which no filter's condition does.
     */
    private boolean endsAfter(int ahead) {
        var token = peek(ahead);
        return token.kind() == Kind.END || token.is(CLOSE_PARENTHESIS) || token.is(CLOSE_BRACKET) || token.is(COMMA) || token.is(".")
                || token.is("}") || token.is(COLON) || token.kind() == Kind.WORD && STOP_WORDS.contains(token.text())
                && !KEYWORDS.contains(token.text());
    }

    private Node filterCondition() {
        filters++;
        locals.push(new Names(List.of("item")));
        enter(FILTER);
        try {
            var condition = expression();
            expect(CLOSE_BRACKET);
            return condition;
        } finally {
            leave(FILTER);
            locals.pop();
            filters--;
        }
    }

    private Node invocation(Node callee) {
        var arguments = new ArrayList<Node>();
        var names = new ArrayList<String>();
        arguments(arguments, names);
        var twice = twice(names);
        return twice == null ? new Node.Invocation(callee, arguments, names)
                : new Node.Invalid("the invocation names the argument '%s' twice".formatted(twice));
    }

    /** A name that is twice among an invocation's named arguments, or null if none is. */
    private static String twice(List<String> names) {
        var unique = new HashSet<String>();
        for (var name : names) {
            if (!unique.add(name)) {
                return name;
            }
        }
        return null;
    }

    /** The arguments in parentheses, positional or named (grammar rules 40 to 43), which are not mixed. */
    private void arguments(List<Node> arguments, List<String> names) {
        expect("(");
        enter(ARGUMENTS);
        if (peek().is(CLOSE_PARENTHESIS)) {
            next();
            leave(ARGUMENTS);
            return;
        }
        boolean named = namedArgumentAhead();
        do {
            if (!arguments.isEmpty()) {
                next();
            }
            if (named != namedArgumentAhead()) {
                throw error("an invocation mixes named and positional arguments " + where());
            }
            if (named) {
                names.add(wordsName());
                expect(COLON);
            }
            arguments.add(expression());
        } while (peek().is(COMMA));
        expect(CLOSE_PARENTHESIS);
        leave(ARGUMENTS);
    }

    /** Whether a name and a colon follow: a named argument. */
    private boolean namedArgumentAhead() {
        int i = 0;
        while (peek(i).kind() == Kind.WORD || peek(i).kind() == Kind.NUMBER && i > 0) {
            i++;
        }
        return i > 0 && peek(i).is(COLON);
    }

    // ---- primary expressions -----------------------------------------------------------------------

    private Node primary() {
        var token = peek();
        return switch (token.kind()) {
            case NUMBER -> {
                next();
                yield new Node.Constant(number(token.text()));
            }
            case STRING -> {
                next();
                yield new Node.Constant(token.text());
            }
            case WORD -> word();
            case SYMBOL -> symbol(token.text());
            case END -> throw error("a value is missing at the end");
        };
    }

    private BigDecimal number(String digits) {
        BigDecimal number;
        try {
            number = new BigDecimal(digits.startsWith(".") ? "0" + digits : digits);
        } catch (NumberFormatException e) {
            throw error("'" + digits + "' is not a number");
        }
        if (!FeelNumbers.isDecimal128(number)) {
            throw error("'" + digits + "' is beyond FEEL's numbers (decimal128)");
        }
        return FeelNumbers.plain(number);
    }

    private Node symbol(String symbol) {
        return switch (symbol) {
            case "(" -> parenthesis();
            case OPEN_BRACKET -> bracket();
            case CLOSE_BRACKET -> openRange();
            case "{" -> context();
            case "@" -> atLiteral();
            case "<", "<=", GREATER, ">=", "=", "!=" -> unaryRange(symbol);
            default -> throw error(UNEXPECTED + text.substring(peek().start()) + "'");
        };
    }

    /** {@code < e}, {@code = e}...: a range of one endpoint, which may itself be one ({@code = = 1}). */
    private Node unaryRange(String operator) {
        next();
        enter(UNARY_RANGE);
        try {
            return new Node.UnaryRange(FeelRange.Comparison.of(operator), binary(ADDITIVE));
        } finally {
            leave(UNARY_RANGE);
        }
    }

    /** {@code (e)}, or a range open at its start, {@code (a..b]}. */
    private Node parenthesis() {
        next();
        enter(PARENTHESES);
        var first = expression();
        Node node;
        if (peek().is(RANGE)) {
            next();
            var end = expression();
            node = new Node.RangeLiteral(first, false, end, rangeClose());
        } else {
            expect(CLOSE_PARENTHESIS);
            node = first;
        }
        leave(PARENTHESES);
        return node;
    }

    /** {@code [a, b]}, a list, or {@code [a..b]}, a range closed at its start. */
    private Node bracket() {
        next();
        enter(PARENTHESES);
        try {
            if (peek().is(CLOSE_BRACKET)) {
                next();
                return new Node.ListLiteral(List.of());
            }
            var first = expression();
            if (peek().is(RANGE)) {
                next();
                var end = expression();
                return new Node.RangeLiteral(first, true, end, rangeClose());
            }
            var elements = new ArrayList<Node>();
            elements.add(first);
            while (peek().is(COMMA)) {
                next();
                elements.add(expression());
            }
            expect(CLOSE_BRACKET);
            return new Node.ListLiteral(elements);
        } finally {
            leave(PARENTHESES);
        }
    }

    /** {@code ]a..b]}: a range open at its start. */
    private Node openRange() {
        next();
        enter(PARENTHESES);
        var start = expression();
        expect(RANGE);
        var end = expression();
        var range = new Node.RangeLiteral(start, false, end, rangeClose());
        leave(PARENTHESES);
        return range;
    }

    /** The end of a range: {@code ]}, which includes its end, or {@code )} or {@code [}, which exclude it. */
    private boolean rangeClose() {
        var token = next();
        if (token.is(CLOSE_BRACKET)) {
            return true;
        }
        if (token.is(CLOSE_PARENTHESIS) || token.is(OPEN_BRACKET)) {
            return false;
        }
        throw error("a range is not closed with ], ) or [");
    }

    /** {@code {a: 1, "b": a + 1}}: a context, each entry in the scope of those before it. */
    private Node context() {
        next();
        enter(PARENTHESES);
        locals.push(new Names(List.of()));
        try {
            var keys = new ArrayList<String>();
            var values = new ArrayList<Node>();
            if (peek().is("}")) {
                next();
                return new Node.ContextLiteral(keys, values);
            }
            do {
                if (!keys.isEmpty()) {
                    next();
                }
                var key = contextKey();
                expect(COLON);
                locals.peek().add(key);
                keys.add(key);
                values.add(expression());
            } while (peek().is(COMMA));
            expect("}");
            var unique = new HashSet<String>();
            for (var key : keys) {
                if (!unique.add(key)) {
                    return new Node.Invalid("the context has the key '%s' twice".formatted(key));
                }
            }
            return new Node.ContextLiteral(keys, values);
        } finally {
            locals.pop();
            leave(PARENTHESES);
        }
    }

    /** A context entry's key (grammar rule 61): a string, or a name, which may hold additional name symbols. */
    private String contextKey() {
        if (peek().kind() == Kind.STRING) {
            return next().text();
        }
        if (peek().kind() != Kind.WORD) {
            throw error("a context entry's key is missing " + where());
        }
        var key = new StringBuilder(next().text());
        while (!peek().is(COLON) && (peek().kind() == Kind.WORD || peek().kind() == Kind.NUMBER || isNameSymbol(peek()))) {
            var token = next();
            key.append(token.spaceBefore() ? " " : "").append(token.text());
        }
        return key.toString();
    }

    private static boolean isNameSymbol(Token token) {
        return token.kind() == Kind.SYMBOL && (token.is(".") || token.is("/") || token.is("-") || token.is("+") || token.is("*"));
    }

    /** {@code @"..."}: an at-literal (grammar rule 65), null with an error if its string is no date, time or duration. */
    private Node atLiteral() {
        next();
        if (peek().kind() != Kind.STRING) {
            throw error("@ must be followed by a string, as in @\"2025-01-31\"");
        }
        var string = next().text();
        try {
            return new Node.Constant(TemporalLiterals.atLiteral(string));
        } catch (IllegalArgumentException | DateTimeException e) {
            return new Node.Invalid("@\"%s\" is not a valid date, time, date and time or duration".formatted(string));
        }
    }

    // ---- words -------------------------------------------------------------------------------------

    private Node word() {
        var token = peek();
        var known = known();
        if (KEYWORDS.contains(token.text()) && (known == null || known.length() < 2)) {
            return keyword(token.text());
        }
        var builtIn = builtIn();
        if (builtIn != null && (known == null || builtIn.length() > known.length())) {
            at += builtIn.length();
            var arguments = new ArrayList<Node>();
            var names = new ArrayList<String>();
            arguments(arguments, names);
            return builtInInvocation(builtIn.name(), arguments, names);
        }
        String name;
        if (known != null) {
            at += known.length();
            name = known.name();
            if (!known.local()) {
                free.add(name);
            }
        } else {
            name = wordsName();
            if (filters == 0) {
                free.add(name);
            }
        }
        if (name.equals(QUESTION_MARK)) {
            questionMarks++;
        }
        return new Node.Name(name);
    }

    /** The longest name in scope at the current token: one the expression binds, else one it is given. */
    private Match known() {
        Match best = null;
        for (var names : locals) {
            best = longer(best, match(names, true));
        }
        return longer(best, match(given, false));
    }

    /** The longest built-in function's name at the current token, if a parenthesis follows it. */
    private Match builtIn() {
        var match = match(BUILT_INS, false);
        return match != null && peek(match.length()).is("(") ? match : null;
    }

    private static Match longer(Match a, Match b) {
        if (a == null) {
            return b;
        }
        return b == null || b.length() <= a.length() ? a : b;
    }

    private Match match(Names names, boolean local) {
        var candidates = names.byFirst.get(peek().text());
        if (candidates == null || peek().kind() != Kind.WORD) {
            return null;
        }
        Match best = null;
        for (var candidate : candidates) {
            if (matches(candidate.tokens()) && (best == null || candidate.tokens().size() > best.length())) {
                best = new Match(candidate.name(), candidate.tokens().size(), local);
            }
        }
        return best;
    }

    private boolean matches(List<Token> name) {
        for (int i = 0; i < name.size(); i++) {
            var token = peek(i);
            var expected = name.get(i);
            if (at + i >= tokens.size() - 1 || token.kind() != expected.kind() || !token.text().equals(expected.text())
                    || i > 0 && token.spaceBefore() != expected.spaceBefore()) {
                return false;
            }
        }
        return true;
    }

    /** A name read as words separated by whitespace, up to a keyword or anything that is not a word. */
    private String wordsName() {
        var first = peek();
        if (first.kind() != Kind.WORD || STOP_WORDS.contains(first.text())) {
            throw error("a name is expected " + where());
        }
        var name = new StringBuilder(next().text());
        while ((peek().kind() == Kind.WORD || peek().kind() == Kind.NUMBER && peek().text().chars().allMatch(Character::isDigit))
                && !STOP_WORDS.contains(peek().text())) {
            name.append(' ').append(next().text());
        }
        return name.toString();
    }

    private Node keyword(String keyword) {
        next();
        return switch (keyword) {
            case "true" -> new Node.Constant(Boolean.TRUE);
            case FALSE -> new Node.Constant(Boolean.FALSE);
            case "null" -> new Node.Constant(null);
            case "if" -> ifExpression();
            case "for" -> forExpression();
            case FUNCTION -> functionDefinition();
            default -> quantified(keyword.equals(EVERY));
        };
    }

    private Node ifExpression() {
        enter(KEYWORD);
        try {
            var condition = expression();
            expectWord("then");
            var then = expression();
            expectWord("else");
            return new Node.If(condition, then, expression());
        } finally {
            leave(KEYWORD);
        }
    }

    private Node forExpression() {
        enter(ITERATION);
        var scope = new Names(List.of());
        locals.push(scope);
        try {
            var variables = new ArrayList<String>();
            var domains = new ArrayList<Node>();
            iterationContexts(variables, domains, scope);
            expectWord("return");
            scope.add("partial");
            return new Node.For(variables, domains, expression());
        } finally {
            locals.pop();
            leave(ITERATION);
        }
    }

    private Node quantified(boolean every) {
        enter(ITERATION);
        var scope = new Names(List.of());
        locals.push(scope);
        try {
            var variables = new ArrayList<String>();
            var domains = new ArrayList<Node>();
            iterationContexts(variables, domains, scope);
            expectWord("satisfies");
            return new Node.Quantified(every, variables, domains, expression());
        } finally {
            locals.pop();
            leave(ITERATION);
        }
    }

    /** {@code a in as, b in 1..3}: iteration contexts, each variable in scope of the later ones (grammar rules 46 and 47). */
    private void iterationContexts(List<String> variables, List<Node> domains, Names scope) {
        do {
            if (!variables.isEmpty()) {
                next();
            }
            var variable = wordsName();
            expectWord("in");
            var domain = expression();
            if (peek().is(RANGE)) {
                next();
                domain = new Node.IterationRange(domain, expression());
            }
            variables.add(variable);
            domains.add(domain);
            scope.add(variable);
        } while (peek().is(COMMA));
    }

    /**
     * {@code function(a, b: number) body}; an external function (grammar rule 57) is not evaluated yet. A parameter of a
     * type that is neither FEEL's nor the model's makes the definition null, with an error, as {@code instance of} such a
     * type is (Table 61).
     */
    private Node functionDefinition() {
        enter(KEYWORD);
        try {
            expect("(");
            var parameters = new ArrayList<String>();
            var parameterTypes = new ArrayList<FeelType>();
            String unknown = null;
            while (!peek().is(CLOSE_PARENTHESIS)) {
                if (!parameters.isEmpty()) {
                    expect(COMMA);
                }
                parameters.add(wordsName());
                FeelType type = FeelType.ANY;
                if (peek().is(COLON)) {
                    next();
                    unknownType = null;
                    type = type();
                    unknown = unknown != null ? unknown : unknownType;
                }
                parameterTypes.add(type);
            }
            next();
            if (peek().isWord("external")) {
                next();
                var mapping = expression();
                // Praecepta runs no code from outside the model, by design (docs/DMN-CONFORMANCE.md, known deviations)
                var what = new FeelExpression.Unsupported("an external function (Java or PMML)", null, 0);
                unsupported.add(what);
                return new Node.Unsupported(what, List.of(mapping));
            }
            locals.push(new Names(parameters));
            try {
                var body = expression();
                return unknown != null ? new Node.Invalid(notAType(unknown))
                        : new Node.FunctionDefinition(parameters, parameterTypes, body, depthOf(body));
            } finally {
                locals.pop();
            }
        } finally {
            leave(KEYWORD);
        }
    }

    /** {@code value instance of type}; null, with an error, for a type that is neither FEEL's nor the model's (Table 61). */
    private Node instanceOf(Node value) {
        unknownType = null;
        var type = type();
        return unknownType == null ? new Node.InstanceOf(value, type) : new Node.Invalid(notAType(unknownType));
    }

    private static String notAType(String name) {
        return "'%s' is not a type: neither one of FEEL's nor one the model defines".formatted(name);
    }

    /**
     * An invocation of a built-in function: a date time literal when it is {@code date}, {@code time},
     * {@code date and time} or {@code duration} of literal arguments; else an invocation of the function.
     */
    private Node builtInInvocation(String name, List<Node> arguments, List<String> names) {
        if (names.isEmpty() && TemporalLiterals.isLiteralFunction(name) && arguments.stream().allMatch(Node.Constant.class::isInstance)) {
            var values = new ArrayList<>();
            arguments.forEach(argument -> values.add(((Node.Constant) argument).value()));
            try {
                return new Node.Constant(TemporalLiterals.invoke(name, values));
            } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
                return new Node.Invalid("%s(...) gives no value: %s".formatted(name, e.getMessage()));
            }
        }
        var twice = twice(names);
        if (twice != null) {
            return new Node.Invalid("the invocation names the argument '%s' twice".formatted(twice));
        }
        return new Node.Invocation(new Node.Constant(BuiltInFunctions.lookup(name)), arguments, names);
    }

    // ---- types -------------------------------------------------------------------------------------

    /**
     * A type (grammar rule 54): {@code list<T>}, {@code range<T>}, {@code context<k: T, ...>},
     * {@code function<T, ...>->R}, a built-in type's name, or a type the model names. A name that is neither, such as
     * {@code list} without its element's type (Table 61: "123 instance of list is null"), is read as {@code Any} and
     * kept in {@link #unknownType}, which makes what declares it null.
     */
    private FeelType type() {
        enter(TYPE);
        try {
            var token = peek();
            if (token.kind() == Kind.WORD && peek(1).is("<")) {
                switch (token.text()) {
                    case "list" -> {
                        return new FeelType.ListOf(parameterType());
                    }
                    case "range" -> {
                        return new FeelType.RangeOf(parameterType());
                    }
                    case "context" -> {
                        return contextType();
                    }
                    case FUNCTION -> {
                        return functionType();
                    }
                    default -> {
                        // a type's name
                    }
                }
            }
            return namedType();
        } finally {
            leave(TYPE);
        }
    }

    private FeelType parameterType() {
        next();
        next();
        var type = type();
        expect(GREATER);
        return type;
    }

    private FeelType contextType() {
        next();
        next();
        var components = new java.util.LinkedHashMap<String, FeelType>();
        while (!peek().is(GREATER)) {
            if (!components.isEmpty()) {
                expect(COMMA);
            }
            var key = peek().kind() == Kind.STRING ? next().text() : wordsName();
            expect(COLON);
            components.put(key, type());
        }
        next();
        return new FeelType.Context(components);
    }

    private FeelType functionType() {
        next();
        next();
        var parameters = new ArrayList<FeelType>();
        while (!peek().is(GREATER)) {
            if (!parameters.isEmpty()) {
                expect(COMMA);
            }
            parameters.add(type());
        }
        next();
        expect("->");
        return new FeelType.FunctionOf(parameters, type());
    }

    /** A built-in type's name, or one the model gives, the longest that is there. */
    private FeelType namedType() {
        var words = new ArrayList<String>();
        for (int i = 0; peek(i).kind() == Kind.WORD && (i == 0 || peek(i).spaceBefore()) && words.size() < 4; i++) {
            words.add(peek(i).text());
        }
        for (int count = words.size(); count > 0; count--) {
            var name = String.join(" ", words.subList(0, count));
            var type = TYPE_NAMES.get(name);
            if (type == null && types != null) {
                type = types.get(name);
            }
            if (type != null) {
                at += count;
                return type;
            }
        }
        if (words.isEmpty()) {
            throw error("a type is expected " + where());
        }
        var name = wordsName();
        if (unknownType == null) {
            unknownType = name;
        }
        return FeelType.ANY;
    }
}
