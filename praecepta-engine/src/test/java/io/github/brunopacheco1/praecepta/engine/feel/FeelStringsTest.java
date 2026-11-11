/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * String literals as FEEL reads and writes them (DMN 1.5, grammar rules 33, 62 and 64, and 10.3.1.1): every
 * way of escaping a character reads back as that character, in cells and in output expressions, and
 * {@link FeelLiterals#literal} writes a literal that holds no vertical space and reads back as the string;
 * and strings are ordered by code point (Table 54).
 */
class FeelStringsTest {

    /** DMN 1.5, Table 54 and 10.3.2.3.2: strings are ordered lexicographically by character, a Unicode code point. */
    @Test
    void strings_are_ordered_by_code_point_not_by_utf16_code_unit() {
        var tilde = "～"; // U+FF5E
        var horse = "🐎"; // U+1F40E, the code units U+D83D U+DC0E
        Assertions.assertThat(horse.compareTo(tilde)).as("Java's order").isNegative();
        Assertions.assertThat(FeelStrings.compare(horse, tilde)).isPositive();
        Assertions.assertThat(FeelStrings.compare(tilde, horse)).isNegative();
        Assertions.assertThat(FeelStrings.compare(String.valueOf((char) 0xE000), horse)).isNegative();
        Assertions.assertThat(FeelStrings.compare("a" + horse, "a" + tilde + "z")).isPositive();
        // otherwise as Java orders strings: by character, a prefix first, case-sensitively
        Assertions.assertThat(FeelStrings.compare(horse, horse)).isZero();
        Assertions.assertThat(FeelStrings.compare("ab", "abc")).isNegative();
        Assertions.assertThat(FeelStrings.compare(horse, horse + "a")).isNegative();
        Assertions.assertThat(FeelStrings.compare("Zed", "apple")).isNegative();
        Assertions.assertThat(FeelStrings.compare("", "")).isZero();
        Assertions.assertThat(FeelTemporals.compare(horse, tilde)).as("FEEL's order of all values").isPositive();
    }

    @Provide
    Arbitrary<String> anyStrings() {
        return codePoints().map(points -> points.stream()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
    }

    /** The order is the lexicographic order of the strings' code points, written independently here. */
    @Property(tries = 3000, edgeCases = EdgeCasesMode.MIXIN)
    void the_order_of_strings_is_that_of_their_code_points(@ForAll("anyStrings") String a, @ForAll("anyStrings") String b) {
        int expected = Integer.signum(Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()));
        Assertions.assertThat(Integer.signum(FeelStrings.compare(a, b))).as("%s vs %s", a, b).isEqualTo(expected);
        Assertions.assertThat(Integer.signum(FeelStrings.compare(b, a))).isEqualTo(-expected);
        Assertions.assertThat(Integer.signum(FeelStrings.compare(a + b, a))).isEqualTo(b.isEmpty() ? 0 : 1);
    }

    @Test
    void literals_escape_quotes_backslashes_and_the_characters_a_literal_may_not_hold() {
        Assertions.assertThat(FeelLiterals.literal("say \"hi\" \\ bye")).isEqualTo("\"say \\\"hi\\\" \\\\ bye\"");
        Assertions.assertThat(FeelLiterals.literal("a\nb\rc\td")).isEqualTo("\"a\\nb\\rc\\td\"");
        Assertions.assertThat(FeelLiterals.literal("a" + (char) 0x0B + "b" + (char) 0x0C)).isEqualTo("\"a\\u000Bb\\u000C\"");
        // other characters stay as they are, those beyond U+FFFF included
        Assertions.assertThat(FeelLiterals.literal("café 🐎 it's")).isEqualTo("\"café 🐎 it's\"");
    }

    @Test
    void a_literal_with_escape_sequences_reads_back_as_its_string() {
        var text = "a\tb\nc\rd" + (char) 0x0B + (char) 0x0C + "\"\\";
        var literal = FeelLiterals.literal(text);
        var cell = new UnaryTests.Positive(List.of(new UnaryTests.Equals(text)));
        Assertions.assertThat(CellParser.parseFeel(literal, ColumnType.STRING)).isEqualTo(cell);
        Assertions.assertThat(CellParser.parse(literal, ColumnType.STRING)).isEqualTo(cell);
        Assertions.assertThat(OutputExpression.parse(literal).evaluate(name -> null)).isEqualTo(text);
    }

    @Provide
    Arbitrary<List<Integer>> codePoints() {
        // ASCII (quotes, backslashes and vertical space among them), the whole of the BMP (lone surrogates
        // included), and the planes beyond it
        return Arbitraries.oneOf(Arbitraries.integers().between(0, 0x7F), Arbitraries.integers().between(0, 0xFFFF),
                Arbitraries.integers().between(0x10000, Character.MAX_CODE_POINT)).list().ofMaxSize(12);
    }

    @Provide
    Arbitrary<List<Integer>> forms() {
        return Arbitraries.integers().between(0, 3).list().ofMinSize(1).ofMaxSize(12);
    }

    /**
     * Each character is written as itself (where a literal may hold it), as a named escape sequence
     * ({@code \n}, {@code \"}...), as {@code \\u} and four hexadecimal digits (a surrogate pair for a
     * character beyond U+FFFF), or as {@code \\U} and six: written by this test, not by Praecepta, the
     * literal must read as the string, in cells of both syntaxes and in output expressions.
     */
    @Property(tries = 2000, edgeCases = EdgeCasesMode.MIXIN)
    void every_escaping_of_a_string_reads_back_as_that_string(@ForAll("codePoints") List<Integer> codePoints,
            @ForAll("forms") List<Integer> forms) {
        var expected = new StringBuilder();
        var literal = new StringBuilder("\"");
        for (int i = 0; i < codePoints.size(); i++) {
            int codePoint = codePoints.get(i);
            expected.appendCodePoint(codePoint);
            literal.append(written(codePoint, forms.get(i % forms.size())));
        }
        literal.append('"');
        var text = expected.toString();
        var written = literal.toString();
        var cell = new UnaryTests.Positive(List.of(new UnaryTests.Equals(text)));
        Assertions.assertThat(CellParser.parseFeel(written, ColumnType.STRING)).as(written).isEqualTo(cell);
        Assertions.assertThat(CellParser.parse(written, ColumnType.STRING)).as(written).isEqualTo(cell);
        Assertions.assertThat(OutputExpression.parse(written).evaluate(name -> null)).as(written).isEqualTo(text);
    }

    @Property(tries = 2000, edgeCases = EdgeCasesMode.MIXIN)
    void a_written_literal_holds_no_vertical_space_and_reads_back(@ForAll("codePoints") List<Integer> codePoints) {
        var text = codePoints.stream().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        var literal = FeelLiterals.literal(text);
        Assertions.assertThat(literal.chars()).as(literal).noneMatch(c -> c >= 0x0A && c <= 0x0D);
        Assertions.assertThat(CellParser.parseFeel(literal, ColumnType.STRING)).as(literal)
                .isEqualTo(new UnaryTests.Positive(List.of(new UnaryTests.Equals(text))));
        Assertions.assertThat(OutputExpression.parse(literal).evaluate(name -> null)).as(literal).isEqualTo(text);
    }

    /** One character of a literal, in one of four forms; a form the character cannot take falls back to the next. */
    private static String written(int codePoint, int form) {
        String named = switch (codePoint) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\'' -> "\\'";
            default -> null;
        };
        boolean plain = codePoint != '"' && codePoint != '\\' && (codePoint < 0x0A || codePoint > 0x0D);
        return switch (form) {
            case 0 -> plain ? Character.toString(codePoint) : fourDigits(codePoint);
            case 1 -> named != null ? named : fourDigits(codePoint);
            case 2 -> fourDigits(codePoint);
            default -> "\\U%06X".formatted(codePoint);
        };
    }

    /** {@code \\u} and four hexadecimal digits, in lower case; a character beyond U+FFFF as its surrogate pair. */
    private static String fourDigits(int codePoint) {
        var units = new StringBuilder();
        for (char unit : Character.toChars(codePoint)) {
            units.append("\\u%04x".formatted((int) unit));
        }
        return units.toString();
    }
}
