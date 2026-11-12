/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Java identifiers for table, column and value names. */
final class JavaNames {

    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield",
            "record", "sealed", "permits", "_",
            // methods every record has, which a component must not shadow
            "hashCode", "toString", "equals", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    private JavaNames() {
    }

    /** {@code customer.age} → {@code customerAge}. */
    static String member(String text) {
        var words = words(text);
        if (words.isEmpty()) {
            return "value";
        }
        var name = new StringBuilder(lowerFirst(words.getFirst()));
        for (var word : words.subList(1, words.size())) {
            name.append(upperFirst(word));
        }
        return safe(name.toString());
    }

    /** {@code discount table} → {@code DiscountTable}. */
    static String type(String text) {
        var name = new StringBuilder();
        words(text).forEach(word -> name.append(upperFirst(word)));
        return name.isEmpty() ? "Decision" : safe(name.toString());
    }

    /** {@code small business} → {@code SMALL_BUSINESS}. */
    static String constant(String text) {
        var name = String.join("_", words(text)).toUpperCase(Locale.ROOT);
        return name.isEmpty() ? "EMPTY" : safe(name);
    }

    /**
     * Applies {@code naming} to every text and fails if two texts get the same identifier.
     *
     * @param what describes the texts in the error message, e.g. "columns of table X"
     */
    static List<String> distinct(List<String> texts, UnaryOperator<String> naming, String what) {
        var names = new ArrayList<String>();
        var seen = new HashMap<String, String>();
        for (var text : texts) {
            var name = naming.apply(text);
            var previous = seen.putIfAbsent(name, text);
            if (previous != null) {
                throw new IllegalArgumentException("%s: '%s' and '%s' both become the Java name %s; rename one"
                        .formatted(what, previous, text, name));
            }
            names.add(name);
        }
        return names;
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+")).filter(word -> !word.isEmpty()).toList();
    }

    private static String safe(String name) {
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name = "_" + name;
        }
        return RESERVED.contains(name) ? name + "_" : name;
    }

    private static String lowerFirst(String word) {
        return Character.toLowerCase(word.charAt(0)) + word.substring(1);
    }

    private static String upperFirst(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
