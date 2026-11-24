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
import java.util.regex.Pattern;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLexer.Kind;
import io.github.brunopacheco1.praecepta.engine.feel.FeelLexer.Token;
import io.github.brunopacheco1.praecepta.engine.feel.OutputExpression.Operator;

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
 * {@code 1115-feel-date-function} to {@code 1120-feel-duration-function}). A date and time at hour 24
 * ({@code 2021-01-01T24:00:00}) is midnight of the next day, as XML Schema's {@code dateTime} reads it (the TCK's
 * {@code 0100-arithmetic}).
 *
 * <p><b>Limits.</b> The parser recurses a few calls a level of nesting, and the evaluator one call a level of the
 * tree, so both nesting and the tree's depth are bounded by {@link #MAX_DEPTH}: deeper input is a syntax error,
 * whatever the thread's stack.
 */
final class FeelParser {

    /** The deepest an expression may nest, in its text and in its tree. */
    static final int MAX_DEPTH = 300;

    /** Words that end a name read word by word: FEEL's keywords and the words of its operators. */
    private static final Set<String> STOP_WORDS = Set.of("and", "or", "in", "between", "instance", "then", "else", "return",
            "satisfies", "if", "for", "some", "every", "function", "true", "false", "null");
    /** The keywords that start an expression. */
    private static final Set<String> KEYWORDS = Set.of("if", "for", "some", "every", "function", "true", "false", "null");
    /** FEEL's names of its built-in types (10.3.2.9.1). */
    private static final Map<String, FeelType> TYPE_NAMES = Map.of(
            "number", FeelType.of(ColumnType.NUMBER), "string", FeelType.of(ColumnType.STRING),
            "boolean", FeelType.of(ColumnType.BOOLEAN), "date", FeelType.of(ColumnType.DATE), "time", FeelType.of(ColumnType.TIME),
            "date and time", FeelType.of(ColumnType.DATE_TIME), "days and time duration", FeelType.of(ColumnType.DAY_TIME_DURATION),
            "years and months duration", FeelType.of(ColumnType.YEAR_MONTH_DURATION), "Any", FeelType.ANY, "Null", FeelType.NULL);
    private static final Pattern HOUR_24 = Pattern.compile("([+-]?\\d{4,}-\\d{2}-\\d{2})T24:00:00(?:\\.0+)?(.*)", Pattern.DOTALL);

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
    record Parsed(Node root, List<String> names, List<String> unsupported, int depth) {
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
    private final Set<String> unsupported = new LinkedHashSet<>();
    private int at;
    private int depth;
    private int filters;
    private int questionMarks;

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
     * @param types the types the model names, which {@code instance of} and parameters may name; null to read any
     *              unknown type name as {@code Any}, as a check of syntax alone does
     * @throws ExpressionSyntaxException if it is not a FEEL expression, or nests more than {@link #MAX_DEPTH} levels
     */
    static Parsed parse(String text, Names names, Map<String, FeelType> types) {
        var parser = new FeelParser(text, names, types);
        var root = parser.all();
        int depth = depthOf(root);
        if (depth > MAX_DEPTH) {
            throw parser.error("it nests more than " + MAX_DEPTH + " levels");
        }
        return new Parsed(root, List.copyOf(parser.free), List.copyOf(parser.unsupported), depth);
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
            throw error("unexpected '" + text.substring(peek().start()) + "'");
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

    private void enter() {
        if (++depth > 2 * MAX_DEPTH) {
            throw error("it nests more than " + MAX_DEPTH + " levels");
        }
    }

    private void leave() {
        depth--;
    }

    ExpressionSyntaxException error(String problem) {
        return new ExpressionSyntaxException("'%s' is not a valid FEEL expression: %s".formatted(text, problem));
    }

    // ---- operators ---------------------------------------------------------------------------------

    private Node expression() {
        enter();
        try {
            return binary(OR);
        } finally {
            leave();
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
            case "between", "in" -> COMPARISON;
            case "instance" -> peek(1).isWord("of") ? INSTANCE : 0;
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
                case "or", "and" -> new Node.Logical(operator.equals("and"), left, binary(precedence + 1));
                case "between" -> between(left);
                case "in" -> new Node.In(left, tests());
                case "instance" -> {
                    expectWord("of");
                    yield new Node.InstanceOf(left, type());
                }
                case "=", "!=", "<", "<=", ">", ">=" -> new Node.Comparison(operator, left, binary(precedence + 1));
                default -> new Node.Arithmetic(arithmetic(operator), left, binary(precedence + 1));
            };
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
        var low = binary(INSTANCE);
        expectWord("and");
        return new Node.Between(value, low, binary(INSTANCE));
    }

    /**
     * The positive unary tests after {@code in} (grammar rule 6): a list of them in parentheses, an interval, or one test.
     */
    private List<Node.Test> tests() {
        if (!peek().is("(")) {
            return List.of(test(() -> binary(ADDITIVE)));
        }
        next();
        enter();
        var first = test(this::expression);
        if (peek().is(RANGE)) {
            next();
            var end = expression();
            var range = new Node.RangeLiteral(first.expression(), false, end, rangeClose());
            leave();
            return List.of(new Node.Test(range, first.questionMark()));
        }
        var tests = new ArrayList<Node.Test>();
        tests.add(first);
        while (peek().is(COMMA)) {
            next();
            tests.add(test(this::expression));
        }
        expect(CLOSE_PARENTHESIS);
        leave();
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

    private Node unary() {
        if (!peek().is("-")) {
            return postfix();
        }
        next();
        enter();
        try {
            var operand = unary();
            return operand instanceof Node.Constant(BigDecimal number) ? new Node.Constant(FeelNumbers.negate(number))
                    : new Node.Negation(operand);
        } finally {
            leave();
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
     * but the end of a range ({@code [1..10[}).
     */
    private boolean endsAfter(int ahead) {
        var token = peek(ahead);
        return token.kind() == Kind.END || token.is(CLOSE_PARENTHESIS) || token.is(CLOSE_BRACKET) || token.is(COMMA)
                || token.is("}") || token.is(COLON) || token.kind() == Kind.WORD && STOP_WORDS.contains(token.text())
                && !KEYWORDS.contains(token.text());
    }

    private Node filterCondition() {
        filters++;
        locals.push(new Names(List.of("item")));
        try {
            var condition = expression();
            expect(CLOSE_BRACKET);
            return condition;
        } finally {
            locals.pop();
            filters--;
        }
    }

    private Node invocation(Node callee) {
        var arguments = new ArrayList<Node>();
        var names = new ArrayList<String>();
        arguments(arguments, names);
        return new Node.Invocation(callee, arguments, names);
    }

    /** The arguments in parentheses, positional or named (grammar rules 40 to 43), which are not mixed. */
    private void arguments(List<Node> arguments, List<String> names) {
        expect("(");
        enter();
        if (peek().is(CLOSE_PARENTHESIS)) {
            next();
            leave();
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
        leave();
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
            default -> throw error("unexpected '" + text.substring(peek().start()) + "'");
        };
    }

    /** {@code < e}, {@code = e}...: a range of one endpoint, which may itself be one ({@code = = 1}). */
    private Node unaryRange(String operator) {
        next();
        enter();
        try {
            return new Node.UnaryRange(operator, binary(ADDITIVE));
        } finally {
            leave();
        }
    }

    /** {@code (e)}, or a range open at its start, {@code (a..b]}. */
    private Node parenthesis() {
        next();
        enter();
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
        leave();
        return node;
    }

    /** {@code [a, b]}, a list, or {@code [a..b]}, a range closed at its start. */
    private Node bracket() {
        next();
        enter();
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
            leave();
        }
    }

    /** {@code ]a..b]}: a range open at its start. */
    private Node openRange() {
        next();
        enter();
        var start = expression();
        expect(RANGE);
        var end = expression();
        var range = new Node.RangeLiteral(start, false, end, rangeClose());
        leave();
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
        enter();
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
            leave();
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
            return new Node.Constant(TemporalLiterals.atLiteral(hour24(string)));
        } catch (IllegalArgumentException | DateTimeException e) {
            return new Node.Invalid("@\"%s\" is not a valid date, time, date and time or duration".formatted(string));
        }
    }

    /** A date and time at hour 24 as midnight of the next day, as XML Schema's {@code dateTime} reads it; any other text as it is. */
    static String hour24(String iso) {
        var matcher = HOUR_24.matcher(iso.strip());
        if (!matcher.matches()) {
            return iso;
        }
        try {
            return FeelLiterals.text(FeelTemporals.parseDate(matcher.group(1)).plusDays(1)) + "T00:00:00" + matcher.group(2);
        } catch (DateTimeException e) {
            return iso;
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
            case "false" -> new Node.Constant(Boolean.FALSE);
            case "null" -> new Node.Constant(null);
            case "if" -> ifExpression();
            case "for" -> forExpression();
            case "function" -> functionDefinition();
            default -> quantified(keyword.equals("every"));
        };
    }

    private Node ifExpression() {
        enter();
        try {
            var condition = expression();
            expectWord("then");
            var then = expression();
            expectWord("else");
            return new Node.If(condition, then, expression());
        } finally {
            leave();
        }
    }

    private Node forExpression() {
        enter();
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
            leave();
        }
    }

    private Node quantified(boolean every) {
        enter();
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
            leave();
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

    /** {@code function(a, b: number) body}; an external function (grammar rule 57) is not evaluated yet. */
    private Node functionDefinition() {
        enter();
        try {
            expect("(");
            var parameters = new ArrayList<String>();
            var parameterTypes = new ArrayList<FeelType>();
            while (!peek().is(CLOSE_PARENTHESIS)) {
                if (!parameters.isEmpty()) {
                    expect(COMMA);
                }
                parameters.add(wordsName());
                FeelType type = FeelType.ANY;
                if (peek().is(COLON)) {
                    next();
                    type = type();
                }
                parameterTypes.add(type);
            }
            next();
            if (peek().isWord("external")) {
                next();
                var mapping = expression();
                var what = "an external function (Java or PMML), planned for 0.11 (#119)";
                unsupported.add(what);
                return new Node.Unsupported(what, List.of(mapping));
            }
            locals.push(new Names(parameters));
            try {
                var body = expression();
                return new Node.FunctionDefinition(parameters, parameterTypes, body, depthOf(body));
            } finally {
                locals.pop();
            }
        } finally {
            leave();
        }
    }

    /**
     * An invocation of a built-in function: a date time literal when it is {@code date}, {@code time},
     * {@code date and time} or {@code duration} of literal arguments; else the function, if Praecepta evaluates it;
     * else what it does not evaluate yet.
     */
    private Node builtInInvocation(String name, List<Node> arguments, List<String> names) {
        if (names.isEmpty() && TemporalLiterals.isLiteralFunction(name) && arguments.stream().allMatch(Node.Constant.class::isInstance)) {
            var values = new ArrayList<>();
            arguments.forEach(argument -> values.add(((Node.Constant) argument).value()));
            if (name.equals("date and time") && values.size() == 1 && values.getFirst() instanceof String iso) {
                values.set(0, hour24(iso));
            }
            try {
                return new Node.Constant(TemporalLiterals.invoke(name, values));
            } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
                return new Node.Invalid("%s(...) gives no value: %s".formatted(name, e.getMessage()));
            }
        }
        var function = BuiltInFunctions.lookup(name);
        if (function != null) {
            return new Node.Invocation(new Node.Constant(function), arguments, names);
        }
        var what = "the built-in function '%s', planned for 0.9 (#117)".formatted(name);
        unsupported.add(what);
        return new Node.Unsupported(what, arguments);
    }

    // ---- types -------------------------------------------------------------------------------------

    /**
     * A type (grammar rule 54): {@code list<T>}, {@code range<T>}, {@code context<k: T, ...>},
     * {@code function<T, ...>->R}, a built-in type's name, or a type the model names.
     */
    private FeelType type() {
        enter();
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
                    case "function" -> {
                        return functionType();
                    }
                    default -> {
                        // a type's name
                    }
                }
            }
            return namedType();
        } finally {
            leave();
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
            var type = TYPE_NAMES.containsKey(name) ? TYPE_NAMES.get(name) : types == null ? null : types.get(name);
            if (type != null) {
                at += count;
                return type;
            }
        }
        if (words.isEmpty()) {
            throw error("a type is expected " + where());
        }
        if (types == null) {
            wordsName();
            return FeelType.ANY;
        }
        throw error("'%s' is not a type: neither one of FEEL's nor one the model defines".formatted(words.getFirst()));
    }
}
