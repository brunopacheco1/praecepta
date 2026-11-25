/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.invalid;
import static io.github.brunopacheco1.praecepta.engine.feel.Arguments.string;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.fixed;
import static io.github.brunopacheco1.praecepta.engine.feel.BuiltInFunction.Arity.optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;

/**
 * FEEL's string functions (DMN 1.5, 10.3.4.3). A string is a sequence of characters, Unicode code points, so that
 * {@code string length("\U01F40E")} is 1 and positions count code points. {@code matches}, {@code replace} and
 * {@code split} take a regular expression as XQuery 1.0 and XPath 2.0 Functions and Operators defines one (7.6), with
 * its flags: {@code s}, {@code m}, {@code i}, {@code x} and {@code q}; Java's regular expressions read it, with XML
 * Schema's character class subtraction ({@code [a-z-[aeiou]]}) written as Java's intersection. Matching one counts
 * against the evaluation's limits, so that a pattern that backtracks without end gives null, with an error.
 */
final class StringFunctions {

    private static final FeelType STRING_TYPE = FeelType.of(ColumnType.STRING);
    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final FeelType BOOLEAN = FeelType.of(ColumnType.BOOLEAN);
    private static final String STRING = "string";
    private static final String DELIMITER = "delimiter";
    private static final String INPUT = "input";
    private static final String PATTERN = "pattern";
    private static final String FLAGS = "flags";
    private static final String MATCH = "match";
    /**
     * How many characters of its text a match reads for each step of the evaluation it counts: a regular expression
     * that backtracks without end, such as {@code (.*a){12}!x}, stops after a hundred million reads, a fraction of a
     * second.
     */
    private static final int READS_PER_STEP = 100;

    private StringFunctions() {
    }

    static void register(Consumer<BuiltInFunction> add) {
        add.accept(BuiltInFunction.of("substring", STRING_TYPE, optional(2, 1), StringFunctions::substring,
                STRING, "start position", "length"));
        add.accept(BuiltInFunction.of("string length", NUMBER, fixed(1),
                (arguments, env) -> FeelNumbers.decimal(string(arguments.getFirst(), STRING).codePointCount(0,
                        string(arguments.getFirst(), STRING).length())), STRING));
        add.accept(BuiltInFunction.of("upper case", STRING_TYPE, fixed(1),
                (arguments, env) -> string(arguments.getFirst(), STRING).toUpperCase(Locale.ROOT), STRING));
        add.accept(BuiltInFunction.of("lower case", STRING_TYPE, fixed(1),
                (arguments, env) -> string(arguments.getFirst(), STRING).toLowerCase(Locale.ROOT), STRING));
        add.accept(BuiltInFunction.of("substring before", STRING_TYPE, fixed(2), (arguments, env) -> {
            var text = string(arguments.get(0), STRING);
            int at = text.indexOf(string(arguments.get(1), MATCH));
            return at < 0 ? "" : text.substring(0, at);
        }, STRING, MATCH));
        add.accept(BuiltInFunction.of("substring after", STRING_TYPE, fixed(2), (arguments, env) -> {
            var text = string(arguments.get(0), STRING);
            var match = string(arguments.get(1), MATCH);
            int at = text.indexOf(match);
            return at < 0 ? "" : text.substring(at + match.length());
        }, STRING, MATCH));
        add.accept(BuiltInFunction.of("replace", STRING_TYPE, optional(3, 1),
                (arguments, env) -> matching(() -> replace(arguments, env)),
                INPUT, PATTERN, "replacement", FLAGS));
        add.accept(BuiltInFunction.of("contains", BOOLEAN, fixed(2),
                (arguments, env) -> string(arguments.get(0), STRING).contains(string(arguments.get(1), MATCH)), STRING, MATCH));
        add.accept(BuiltInFunction.of("starts with", BOOLEAN, fixed(2),
                (arguments, env) -> string(arguments.get(0), STRING).startsWith(string(arguments.get(1), MATCH)), STRING, MATCH));
        add.accept(BuiltInFunction.of("ends with", BOOLEAN, fixed(2),
                (arguments, env) -> string(arguments.get(0), STRING).endsWith(string(arguments.get(1), MATCH)), STRING, MATCH));
        add.accept(BuiltInFunction.of("matches", BOOLEAN, optional(2, 1), (arguments, env) -> {
            var input = string(arguments.get(0), INPUT);
            var pattern = pattern(string(arguments.get(1), PATTERN), flags(arguments, 2));
            return matching(() -> matcher(pattern, input, env).find());
        }, INPUT, PATTERN, FLAGS));
        add.accept(BuiltInFunction.of("split", FeelType.listOf(ColumnType.STRING), fixed(2),
                (arguments, env) -> matching(() -> split(arguments, env)),
                STRING, DELIMITER));
        add.accept(BuiltInFunction.of("string join", STRING_TYPE, optional(1, 1), StringFunctions::join, "list", DELIMITER));
    }

    /**
     * {@code substring(string, start position, length?)}: the characters from the start position, 1 for the first and
     * -1 for the last, up to {@code length} of them; positions and lengths are whole numbers. A start position beyond
     * the string, either way, is null, with an error.
     */
    private static Object substring(List<Object> arguments, Env env) {
        var text = string(arguments.get(0), STRING);
        int start = Arguments.whole(arguments.get(1), "start position");
        var points = text.codePoints().toArray();
        int from = start > 0 ? start - 1 : points.length + start;
        if (start == 0 || from < 0 || from > points.length) {
            throw invalid("takes a start position from 1 to %d, or -1 to -%d, not %d".formatted(points.length, points.length, start));
        }
        int to = points.length;
        if (arguments.size() > 2 && arguments.get(2) != null) {
            int length = Arguments.whole(arguments.get(2), "length");
            if (length < 0) {
                throw invalid("takes a length of 0 or more, not " + length);
            }
            to = (int) Math.min(points.length, (long) from + length);
        }
        return new String(points, from, to - from);
    }

    private static Object replace(List<Object> arguments, Env env) {
        var input = string(arguments.get(0), INPUT);
        var flags = flags(arguments, 3);
        var pattern = pattern(string(arguments.get(1), PATTERN), flags);
        var replacement = string(arguments.get(2), "replacement");
        if (matcher(pattern, "", env).matches()) {
            throw invalid("takes a pattern that does not match the empty string, not \"%s\"".formatted(pattern.pattern()));
        }
        if (flags.indexOf('q') >= 0) {
            replacement = Matcher.quoteReplacement(replacement);
        } else if (!isReplacement(replacement)) {
            throw invalid("takes a replacement whose \\ escapes a \\ or a $, and whose $ is followed by a digit, not \"%s\""
                    .formatted(replacement));
        }
        var matcher = matcher(pattern, input, env);
        var result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, replacement);
            if (result.length() > Env.MAX_CHARACTERS) {
                Arguments.characters(env, result.toString());
            }
        }
        matcher.appendTail(result);
        return Arguments.characters(env, result.toString());
    }

    /** Whether a replacement is one XPath takes: {@code \} only before {@code \} or {@code $}, {@code $} only before a digit. */
    private static boolean isReplacement(String replacement) {
        int i = 0;
        while (i < replacement.length()) {
            char c = replacement.charAt(i);
            if (c == '\\' || c == '$') {
                char next = i + 1 < replacement.length() ? replacement.charAt(i + 1) : 0;
                if (c == '\\' && next != '\\' && next != '$' || c == '$' && !Character.isDigit(next)) {
                    return false;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return true;
    }

    private static Object split(List<Object> arguments, Env env) {
        var text = string(arguments.get(0), STRING);
        var pattern = pattern(string(arguments.get(1), DELIMITER), "");
        if (matcher(pattern, "", env).matches()) {
            throw invalid("takes a delimiter that does not match the empty string, not \"%s\"".formatted(pattern.pattern()));
        }
        var parts = new ArrayList<Object>();
        var matcher = matcher(pattern, text, env);
        int from = 0;
        while (matcher.find()) {
            parts.add(text.substring(from, matcher.start()));
            from = matcher.end();
            Arguments.steps(env, 1);
        }
        parts.add(text.substring(from));
        return Arguments.result(parts);
    }

    /** {@code string join(list, delimiter?)}: the strings of a list, its nulls left out, between delimiters. */
    private static Object join(List<Object> arguments, Env env) {
        var list = Arguments.list(arguments.getFirst(), "list");
        var delimiter = arguments.size() > 1 && arguments.get(1) != null ? string(arguments.get(1), DELIMITER) : "";
        Arguments.steps(env, list.size());
        var joined = new StringBuilder();
        boolean first = true;
        for (var item : list) {
            if (item != null && !(item instanceof String)) {
                throw invalid("takes a list of strings, not one holding " + FeelValues.text(item));
            }
            if (item != null && joined.length() <= Env.MAX_CHARACTERS) {
                if (!first) {
                    joined.append(delimiter);
                }
                joined.append((String) item);
                first = false;
            }
        }
        return Arguments.characters(env, joined.toString());
    }

    // ---- regular expressions -----------------------------------------------------------------------

    private static String flags(List<Object> arguments, int index) {
        if (arguments.size() <= index || arguments.get(index) == null) {
            return "";
        }
        var flags = string(arguments.get(index), FLAGS);
        for (int i = 0; i < flags.length(); i++) {
            if ("smixq".indexOf(flags.charAt(i)) < 0) {
                throw invalid("takes flags of s, m, i, x and q, not \"%s\"".formatted(flags));
            }
        }
        return flags;
    }

    /** An XPath regular expression (XQuery 1.0 and XPath 2.0 Functions and Operators, 7.6.1) and its flags, as Java's. */
    static Pattern pattern(String regex, String flags) {
        int javaFlags = Pattern.UNICODE_CASE;
        String source = regex;
        if (flags.indexOf('q') >= 0) {
            javaFlags |= Pattern.LITERAL;
        } else {
            source = javaRegex(flags.indexOf('x') >= 0 ? withoutWhitespace(regex) : regex);
        }
        if (flags.indexOf('s') >= 0) {
            javaFlags |= Pattern.DOTALL;
        }
        if (flags.indexOf('m') >= 0) {
            javaFlags |= Pattern.MULTILINE;
        }
        if (flags.indexOf('i') >= 0) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
        }
        try {
            var pattern = Pattern.compile(source, javaFlags);
            if (flags.indexOf('q') < 0) {
                requireGroups(source, pattern.matcher("").groupCount());
            }
            return pattern;
        } catch (IllegalArgumentException e) {
            throw invalid("takes a regular expression, not \"%s\": %s".formatted(regex, description(e)));
        }
    }

    /**
     * A regular expression without the whitespace the {@code x} flag removes: every space, tab, carriage return and
     * line feed outside a character class, so that {@code hello\ sworld} is {@code hello\sworld} (XQuery 1.0 and
     * XPath 2.0 Functions and Operators, 7.6.1.1).
     */
    private static String withoutWhitespace(String regex) {
        var result = new StringBuilder(regex.length());
        int classes = 0;
        boolean escaped = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (classes == 0 && " \t\n\r".indexOf(c) >= 0) {
                continue;
            }
            result.append(c);
            if (!escaped && c == '[') {
                classes++;
            } else if (!escaped && c == ']' && classes > 0) {
                classes--;
            }
            escaped = !escaped && c == '\\';
        }
        return result.toString();
    }

    /**
     * An XPath regular expression as Java reads it: XML Schema's character class subtraction, {@code [a-z-[aeiou]]}, as
     * Java's {@code [a-z&&[^aeiou]]}, and its block escapes, {@code \p{IsBasicLatin}}, as Java's
     * {@code \p{InBasicLatin}}.
     */
    private static String javaRegex(String regex) {
        var java = regex.contains("-[") ? regex.replace("-[", "&&[^") : regex;
        return java.replace("\\p{Is", "\\p{In").replace("\\P{Is", "\\P{In");
    }

    /**
     * Rejects a back-reference to a group the expression does not have, {@code (.)\2}, or one inside a character
     * class, {@code [\1]}, which XPath makes errors, and Java a reference that never matches.
     */
    private static void requireGroups(String regex, int groups) {
        int classes = 0;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char next = regex.charAt(++i);
                if (next >= '1' && next <= '9' && (classes > 0 || next - '0' > groups)) {
                    throw invalid("takes a regular expression, not \"%s\": \\%c refers to no group".formatted(regex, next));
                }
            } else if (c == '[') {
                classes++;
            } else if (c == ']' && classes > 0) {
                classes--;
            }
        }
    }

    /** A matcher of a pattern in a text, whose reads of the text count against the evaluation's limits. */
    static Matcher matcher(Pattern pattern, String text, Env env) {
        return pattern.matcher(new Budgeted(text, env));
    }

    /**
     * Runs a match, which Java's regular expressions make by recursion: an alternation repeated over a long text,
     * {@code (a|b)*} over a hundred thousand characters, overflows the thread's stack, and gives null, with an error.
     */
    static <T> T matching(java.util.function.Supplier<T> match) {
        try {
            return match.get();
        } catch (StackOverflowError e) {
            throw invalid("takes a regular expression that matches the text without nesting deeper than the thread's stack");
        }
    }

    /** A text that counts a step of the evaluation each {@link #READS_PER_STEP} characters a match reads. */
    private static final class Budgeted implements CharSequence {
        private final String text;
        private final Env env;
        private int reads;

        Budgeted(String text, Env env) {
            this.text = text;
            this.env = env;
        }

        @Override
        public char charAt(int index) {
            if (++reads == READS_PER_STEP) {
                reads = 0;
                Arguments.steps(env, 1);
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** The message of a pattern's syntax error, or of another exception. */
    private static String description(RuntimeException e) {
        return e instanceof PatternSyntaxException syntax ? syntax.getDescription() : e.getMessage();
    }
}
