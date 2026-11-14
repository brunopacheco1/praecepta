/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Names the FEEL constructs, built-in functions and invocations a FEEL expression uses, in the order written, so
 * that a node Praecepta cannot evaluate is counted under what it needs. It is a scanner, not a parser: it drops
 * strings and comments, takes date and time literals whose arguments are literals as the literals output entries
 * have, and looks for the keywords, operators and brackets of DMN 1.5's grammar (10.3.1.2) and for invocations,
 * whose name it resolves as a built-in function (10.3.4), a business knowledge model or decision service of the
 * model, or another function. Arithmetic, names and literals are what Praecepta reads, so they name nothing.
 */
final class FeelScan {

    /** DMN 1.5's built-in functions (10.3.4), by FEEL name. */
    static final Set<String> BUILT_INS = Set.of(
            // conversion (10.3.4.1)
            "date", "date and time", "time", "number", "string", "duration", "years and months duration", "range",
            // boolean (10.3.4.2) and miscellaneous (10.3.4.11)
            "not", "is", "now", "today",
            // string (10.3.4.3)
            "substring", "string length", "upper case", "lower case", "substring before", "substring after", "replace",
            "contains", "starts with", "ends with", "matches", "split", "string join",
            // list (10.3.4.4)
            "list contains", "count", "min", "max", "sum", "mean", "all", "any", "sublist", "append", "concatenate",
            "insert before", "remove", "reverse", "index of", "union", "distinct values", "flatten", "product", "median",
            "stddev", "mode", "list replace",
            // numeric (10.3.4.5)
            "decimal", "floor", "ceiling", "round up", "round down", "round half up", "round half down", "abs", "modulo",
            "sqrt", "log", "exp", "odd", "even",
            // date and time (10.3.4.6)
            "day of year", "day of week", "month of year", "week of year",
            // range (10.3.4.7)
            "before", "after", "meets", "met by", "overlaps", "overlaps before", "overlaps after", "finishes", "finished by",
            "includes", "during", "starts", "started by", "coincides",
            // sort (10.3.4.9) and context (10.3.4.10)
            "sort", "get value", "get entries", "context", "context put", "context merge");

    /** FEEL's keywords, which a name before a parenthesis does not start with. */
    private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "for", "in", "return", "some", "every", "satisfies",
            "and", "or", "between", "instance", "of", "function", "external", "not");

    /** A date and time literal whose arguments are literals, as output entries read them: {@code date("2025-01-31")}. */
    private static final Pattern TEMPORAL_LITERAL = Pattern.compile(
            "\\b(date and time|date|time|duration)\\s*\\(\\s*(\"\"|[-+]?[\\d.]+)(\\s*,\\s*(\"\"|[-+]?[\\d.]+))*\\s*\\)");

    private static final Feature FOR_LOOPS = Feature.feel("for loops");
    private static final Feature QUANTIFIED = Feature.feel("quantified expressions (some, every)");

    /** Constructs found by a pattern alone, in the masked text; the first wins where two start at the same place. */
    private static final List<Map.Entry<Pattern, Feature>> CONSTRUCTS = List.of(
            Map.entry(Pattern.compile("\\bif\\b"), Feature.feel("if then else")),
            Map.entry(Pattern.compile("\\bfor\\b"), FOR_LOOPS),
            Map.entry(Pattern.compile("\\b(some|every)\\b"), QUANTIFIED),
            Map.entry(Pattern.compile("\\binstance\\s+of\\b"), Feature.feel("instance of")),
            Map.entry(Pattern.compile("\\bbetween\\b"), Feature.feel("between")),
            Map.entry(Pattern.compile("\\b(and|or)\\b"), Feature.feel("and, or")),
            Map.entry(Pattern.compile("\\b(true|false)\\b"), Feature.feel("boolean literals")),
            Map.entry(Pattern.compile("\\bnull\\b"), Feature.feel("null")),
            Map.entry(Pattern.compile("<=?|>=?|!=|="), Feature.feel("comparisons")),
            Map.entry(Pattern.compile("\\.\\."), Feature.feel("ranges")),
            Map.entry(Pattern.compile("\\{"), Feature.feel("contexts")),
            Map.entry(Pattern.compile("[)\\]}]\\s*\\.\\s*[\\p{L}_]"), Feature.feel("paths")),
            Map.entry(Pattern.compile("\\bexternal\\b"), Feature.EXTERNAL_FUNCTIONS),
            Map.entry(Pattern.compile("\\bfunction\\b"), Feature.FUNCTION_DEFINITIONS));

    /** The type an {@code instance of} tests, up to a bracket or comma that ends it: {@code list<Any>}, {@code days and time duration}. */
    private static final Pattern INSTANCE_OF_TYPE = Pattern.compile("\\binstance\\s+of\\s+[^)\\]},]*");

    private static final Pattern IN = Pattern.compile("\\bin\\b");
    private static final Feature IN_FEATURE = Feature.feel("in");
    private static final Feature FILTERS = Feature.feel("filters");
    private static final Feature LISTS = Feature.feel("lists");

    private FeelScan() {
    }

    /**
     * What the expression uses that Praecepta's output expressions do not read, in the order written; empty if it
     * uses nothing the scanner knows.
     *
     * @param model the model whose knowledge models and decision services an invocation may name, or null
     */
    static List<Feature> features(String text, DmnModel model) {
        var masked = new StringBuilder(skeleton(text));
        var found = new TreeMap<Integer, Feature>();
        invocations(masked, model, found);
        var rest = masked.toString();
        for (var construct : CONSTRUCTS) {
            var matcher = construct.getKey().matcher(rest);
            if (matcher.find()) {
                found.putIfAbsent(matcher.start(), construct.getValue());
            }
        }
        var iterating = found.containsValue(FOR_LOOPS) || found.containsValue(QUANTIFIED);
        var in = IN.matcher(rest);
        if (!iterating && in.find()) {
            found.putIfAbsent(in.start(), IN_FEATURE);
        }
        brackets(rest, found);
        return List.copyOf(new LinkedHashSet<>(found.values()));
    }

    /**
     * The text without the contents of strings, without comments, and without the type after {@code instance of}; an
     * at-literal and a date or time function of literals are {@code (0)}, a value that is no literal of its own.
     */
    static String skeleton(String text) {
        var out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                out.append("\"\"");
            } else if (c == '@' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                i = skipString(text, i + 1);
                out.append("(0)");
            } else if (text.startsWith("//", i)) {
                var end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
                out.append(' ');
            } else if (text.startsWith("/*", i)) {
                var end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        var skeleton = out.toString();
        for (int pass = 0; pass < 3; pass++) {
            var next = TEMPORAL_LITERAL.matcher(skeleton).replaceAll("(0)");
            if (next.equals(skeleton)) {
                break;
            }
            skeleton = next;
        }
        return INSTANCE_OF_TYPE.matcher(skeleton).replaceAll("instance of T");
    }

    /** The index after the string that starts at {@code start} (a quote). */
    private static int skipString(String text, int start) {
        int i = start + 1;
        while (i < text.length() && text.charAt(i) != '"') {
            i += text.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, text.length());
    }

    /**
     * Every invocation, {@code name(...)}: the built-in function, knowledge model, decision service or other function
     * it calls. Each name found is masked, so its words ({@code date and time}) are not read as keywords.
     */
    private static void invocations(StringBuilder text, DmnModel model, Map<Integer, Feature> found) {
        for (int open = text.indexOf("("); open >= 0; open = text.indexOf("(", open + 1)) {
            int end = open;
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
            int start = end;
            while (start > 0 && isNamePart(text, start - 1)) {
                start--;
            }
            var words = new ArrayList<>(Arrays.asList(text.substring(start, end).trim().split(" +")));
            words.removeIf(String::isEmpty);
            var feature = invoked(words, model);
            if (feature != null) {
                found.putIfAbsent(start, feature);
                for (int i = start; i < end; i++) {
                    text.setCharAt(i, '#');
                }
            }
        }
    }

    /** Whether the character at {@code i} belongs to a name: a name character, or one space between two words. */
    private static boolean isNamePart(CharSequence text, int i) {
        char c = text.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '?' || c == '.' || c == '\'') {
            return true;
        }
        return c == ' ' && i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1)) || text.charAt(i - 1) == '_');
    }

    /** What a name before a parenthesis invokes, or null if the parenthesis only groups. */
    private static Feature invoked(List<String> words, DmnModel model) {
        if (words.isEmpty()) {
            return null;
        }
        for (int from = 0; from < words.size(); from++) {
            var name = String.join(" ", words.subList(from, words.size()));
            if (BUILT_INS.contains(name)) {
                return Feature.builtIn(name);
            }
        }
        int last = -1;
        for (int i = 0; i < words.size(); i++) {
            if (KEYWORDS.contains(words.get(i))) {
                last = i;
            }
        }
        if (last == words.size() - 1) {
            return "function".equals(words.get(last)) ? Feature.FUNCTION_DEFINITIONS : null;
        }
        var name = String.join(" ", words.subList(last + 1, words.size()));
        if (model != null && model.knowledgeModel(name) != null) {
            return Feature.KNOWLEDGE_MODELS;
        }
        if (model != null && model.service(name) != null) {
            return Feature.DECISION_SERVICES;
        }
        return name.contains(".") && model != null && model.imports() ? Feature.IMPORTS : Feature.FUNCTION_DEFINITIONS;
    }

    /** The word that ends at {@code end}, or empty. */
    private static String wordBefore(String text, int end) {
        int start = end;
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    /** {@code [}: a filter after a value ({@code xs[1]}, {@code xs[item > 3]}), else a list. */
    private static void brackets(String text, Map<Integer, Feature> found) {
        for (int open = text.indexOf('['); open >= 0; open = text.indexOf('[', open + 1)) {
            int before = open - 1;
            while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
                before--;
            }
            var filter = before >= 0 && (Character.isLetterOrDigit(text.charAt(before)) || ")]}\"_#".indexOf(text.charAt(before)) >= 0)
                    && !KEYWORDS.contains(wordBefore(text, before + 1));
            found.putIfAbsent(open, filter ? FILTERS : LISTS);
        }
    }
}
