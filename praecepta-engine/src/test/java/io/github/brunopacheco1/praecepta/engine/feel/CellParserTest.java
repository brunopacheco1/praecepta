/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine.feel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Compare;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Equals;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NotEqual;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.NullTest;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Operator;
import io.github.brunopacheco1.praecepta.engine.feel.UnaryTests.Range;

class CellParserTest {

    @Test
    void equal_and_not_equal_tests_take_a_value_or_null() {
        // DMN 1.5, grammar rule 7.a: ( "<" | "<=" | ">" | ">=" | "=" | "!=" ) , endpoint
        Assertions.assertThat(CellParser.parseFeel("= 18", ColumnType.NUMBER)).isEqualTo(positive(new Equals(new BigDecimal(18))));
        Assertions.assertThat(CellParser.parseFeel("!= \"PT\"", ColumnType.STRING))
                .isEqualTo(positive(new NotEqual(new Equals("PT"))));
        Assertions.assertThat(CellParser.parseFeel("!= other", ColumnType.STRING))
                .isEqualTo(positive(new NotEqual(new Equals(new UnaryTests.Name("other")))));
        // 10.3.2.15: e = null is "FEEL(e) is null", e != null "FEEL(e) is not null"
        Assertions.assertThat(CellParser.parseFeel("= null", ColumnType.DATE)).isEqualTo(positive(new NullTest()));
        Assertions.assertThat(CellParser.parseFeel("!= null", ColumnType.DATE)).isEqualTo(positive(new NotEqual(new NullTest())));
        // one test among others, which not(...) around the cell cannot say
        Assertions.assertThat(CellParser.parse("!=0, > 10", ColumnType.NUMBER))
                .isEqualTo(positive(new NotEqual(new Equals(BigDecimal.ZERO)), new Compare(Operator.GT, BigDecimal.TEN)));
        Assertions.assertThat(CellParser.parse("not(= true, != null)", ColumnType.BOOLEAN))
                .isEqualTo(negated(new Equals(true), new NotEqual(new NullTest())));
        // Praecepta's syntax: an unquoted word after = or != is a string, as elsewhere
        Assertions.assertThat(CellParser.parse("!= PT, = ES", ColumnType.STRING))
                .isEqualTo(positive(new NotEqual(new Equals("PT")), new Equals("ES")));
        Assertions.assertThat(CellParser.parse("!= 2025-01-31", ColumnType.DATE))
                .isEqualTo(positive(new NotEqual(new Equals(LocalDate.of(2025, 1, 31)))));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            ".5;        0.5",
            "-.5;       -0.5",
            "1.2e3;     1200",
            "1E-2;      0.01",
            "2.50E+1;   25",
            "0e0;       0",
    })
    void numbers_may_start_with_their_fraction_and_have_an_exponent(String literal, BigDecimal value) {
        // DMN 1.5, 10.3.2.3.1: "FEEL supports literal scientific notation, e.g., 1.2e3"
        var cell = CellParser.parseFeel(literal, ColumnType.NUMBER);
        Assertions.assertThat(cell.values().orElseThrow()).singleElement()
                .satisfies(number -> Assertions.assertThat((BigDecimal) number).isEqualByComparingTo(value));
        Assertions.assertThat(CellParser.parse("< " + literal + ", [" + literal + ".." + literal + "]", ColumnType.NUMBER).tests())
                .hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.", ".", "1e", "1.2.3", "--1", "1e1.5", "1e9999999999", "+1"})
    void numbers_follow_feels_numeric_literal(String literal) {
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel(literal, ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("is not a number");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // at-literals: their string's syntax says their type (DMN 1.5, grammar rule 65, 10.3.4.1)
            "DATE                | @\"2025-01-31\"                                         | 2025-01-31",
            "TIME                | @\"08:30:00\"                                           | 08:30",
            "TIME                | @\"08:30:00+01:00\"                                     | 08:30+01:00",
            "DATE_TIME           | @\"2025-01-31T08:30:00Z\"                               | 2025-01-31T08:30Z",
            "DATE_TIME           | @\"2025-01-31T08:30:00@Europe/Paris\"                   | 2025-01-31T08:30+01:00[Europe/Paris]",
            "DAY_TIME_DURATION   | @\"P1DT2H\"                                             | PT26H",
            "YEAR_MONTH_DURATION | @\"P1Y2M\"                                              | P14M",
            // the other forms of the date and time functions (Table 72)
            "DATE                | date(2025, 1, 31)                                       | 2025-01-31",
            "DATE                | date(date and time(\"2025-01-31T23:59:00Z\"))            | 2025-01-31",
            "DATE                | date( @\"2025-01-31T08:30:00\" )                         | 2025-01-31",
            "TIME                | time(8, 30, 0)                                          | 08:30",
            "TIME                | time(8, 30, 15.5)                                       | 08:30:15.5",
            "TIME                | time(8, 30, 0, duration(\"PT1H\"))                       | 08:30+01:00",
            "TIME                | time(8, 30, 0, null)                                    | 08:30",
            "TIME                | time(date and time(\"2025-01-31T08:30:00-05:00\"))       | 08:30-05:00",
            "TIME                | time(@\"2025-01-31T08:30:00\")                          | 08:30",
            "DATE_TIME           | date and time(date(\"2025-01-31\"), time(\"08:30:00\"))  | 2025-01-31T08:30",
            "DATE_TIME           | date and time(@\"2025-01-31T23:00:00\", @\"08:30:00Z\")  | 2025-01-31T08:30Z",
            "DATE_TIME           | date  and  time ( \"2025-01-31T08:30:00\" )               | 2025-01-31T08:30",
            // a date and time string may be a date alone (10.3.4.1), at midnight
            "DATE_TIME           | date and time(\"2025-01-31\")                            | 2025-01-31T00:00",
    })
    void date_time_literals_take_every_form_of_dmn(ColumnType type, String literal, String value) {
        var expected = switch (type) {
            case DATE -> LocalDate.parse(value);
            case TIME -> FeelTemporals.parseTime(value);
            case DATE_TIME -> FeelTemporals.parseDateTime(value);
            case DAY_TIME_DURATION -> java.time.Duration.parse(value);
            default -> YearsAndMonths.parse(value);
        };
        Assertions.assertThat(CellParser.parseFeel(literal, type)).isEqualTo(positive(new Equals((Comparable<?>) expected)));
        Assertions.assertThat(CellParser.parse("< " + literal, type)).isEqualTo(positive(new Compare(Operator.LT, (Comparable<?>) expected)));
    }

    @Test
    void date_time_literals_mix_in_lists_and_intervals() {
        Assertions.assertThat(CellParser.parseFeel("[@\"2025-01-01\"..date(2025, 1, 31)), date(2025,3,1), != @\"2025-02-01\"", ColumnType.DATE)
                .tests()).hasSize(3);
        Assertions.assertThat(CellParser.parseFeel("not([time(8, 0, 0)..@\"12:00:00\"])", ColumnType.TIME))
                .isEqualTo(negated(new Range(LocalTime.of(8, 0), true, LocalTime.NOON, true)));
        // the date and time functions of DMN 1.5 are not names of inputs
        Assertions.assertThat(CellParser.parseFeel("date(2025, 1, 31)", ColumnType.DATE).namesInputs()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "DATE                | @\"08:30:00\"                        | is a time, not a date",
            "TIME                | @\"2025-01-31\"                      | is a date, not a time",
            "DATE_TIME           | @\"2025-01-31\"                      | is a date, not a date and time",
            "DAY_TIME_DURATION   | @\"P1Y\"                             | invalid days and time duration 'P1Y'",
            "YEAR_MONTH_DURATION | @\"P1D\"                             | invalid years and months duration 'P1D'",
            "DATE                | @2025                                 | @ must be followed by a string",
            "DATE                | @\"2025-02-30\"                      | invalid date '2025-02-30'",
            "DATE                | date(2025, 2, 30)                     | invalid date",
            "DATE                | date(2025, 1.5, 3)                    | invalid date",
            "DATE                | date(2025, 1)                         | date(...) takes",
            "DATE                | date(\"2025-01-31\") + 1             | unexpected '+ 1' after a date",
            "DATE                | date(\"2025-01-31\"                  | not closed",
            "DATE                | date(x)                               | 'x' is not a string, a number or a date time literal",
            "TIME                | time(25, 0, 0)                        | invalid time",
            "TIME                | time(8, 0, 0, duration(\"PT0.5S\"))  | invalid time",
            "TIME                | time(8, 0, 0, duration(\"PT19H\"))   | invalid time",
            "TIME                | @\"10:00:00@Europe/Paris\"           | is a time with a zone id, which is not supported",
            "DATE_TIME           | date and time(time(\"08:00:00\"))    | date and time(...) takes",
            "DATE_TIME           | date and time(date(\"2025-01-31\"), date(\"2025-01-31\")) | date and time(...) takes",
            "DATE_TIME           | @\"2025-10-26T02:30+01:00[Europe/Paris]\" | write a zone id as @Europe/Paris",
            "DAY_TIME_DURATION   | duration(1)                           | duration(...) takes a duration string",
    })
    void invalid_date_time_literals_say_why(ColumnType type, String literal, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel(literal, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void comments_are_ignored_outside_strings() {
        // DMN 1.5, 10.3.1.2: "Java-style comments can be used, i.e., '//' to end of line and /* ... */"
        Assertions.assertThat(CellParser.parseFeel("< 18 // minors", ColumnType.NUMBER))
                .isEqualTo(positive(new Compare(Operator.LT, new BigDecimal(18))));
        Assertions.assertThat(CellParser.parseFeel("/* adults */ >= 18, /* none */ null", ColumnType.NUMBER))
                .isEqualTo(positive(new Compare(Operator.GE, new BigDecimal(18)), new NullTest()));
        Assertions.assertThat(CellParser.parseFeel("\"a // b\", \"c /* d */\" // e\n", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a // b"), new Equals("c /* d */")));
        Assertions.assertThat(CellParser.parseFeel("\"say \\\"//\\\"\" // quoted", ColumnType.STRING))
                .isEqualTo(positive(new Equals("say \"//\"")));
        Assertions.assertThat(CellParser.parse("not(1, // one\n2)", ColumnType.NUMBER))
                .isEqualTo(negated(new Equals(BigDecimal.ONE), new Equals(new BigDecimal(2))));
        Assertions.assertThat(CellParser.parseFeelList("list contains(?, \"PT\") // Portugal", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT")));
        // in Praecepta's syntax an unquoted string keeps them, as a URL does
        Assertions.assertThat(CellParser.parse("http://example.com", ColumnType.STRING)).isEqualTo(positive(new Equals("http://example.com")));
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("1 /* open", ColumnType.NUMBER))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("a comment /* is not closed");
    }

    @Test
    void a_list_column_reads_not_equal_on_its_elements() {
        Assertions.assertThat(CellParser.parseFeelList("some x in ? satisfies x != \"PT\" or some x in ? satisfies x != null",
                ColumnType.STRING)).isEqualTo(positive(new NotEqual(new Equals("PT")), new NotEqual(new NullTest())));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "NUMBER;  !=;          missing value",
            "NUMBER;  = ;          missing value",
            "NUMBER;  != [1..2];   is not a number",
            "BOOLEAN; = 1;         is not a boolean",
    })
    void equal_and_not_equal_need_a_value_of_the_column(ColumnType type, String cell, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel(cell, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void not_equal_negates_only_an_equality_or_null() {
        Assertions.assertThatThrownBy(() -> new NotEqual(new Compare(Operator.LT, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-", " - "})
    void blank_and_dash_match_anything(String cell) {
        Assertions.assertThat(CellParser.parse(cell, ColumnType.STRING)).isEqualTo(new UnaryTests.Any());
    }

    @Test
    void legacy_unquoted_lists_still_mean_string_literals() {
        Assertions.assertThat(CellParser.parse("PT,ES", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parse(" PT , ES ", ColumnType.STRING)) // whitespace is trimmed
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parse("10", ColumnType.STRING)) // a code, not a number
                .isEqualTo(positive(new Equals("10")));
    }

    @Test
    void quoted_strings_may_contain_commas_and_escaped_quotes() {
        Assertions.assertThat(CellParser.parse("\"a,b\", \"say \\\"hi\\\"\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a,b"), new Equals("say \"hi\"")));
    }

    /** DMN 1.5, grammar rule 64 and 10.3.1.1: the escape sequences of a string literal, in Praecepta's syntax and in strict FEEL. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "\"a\\nb\"            | 0061 000A 0062",
            "\"a\\tb\"            | 0061 0009 0062",
            "\"a\\rb\"            | 0061 000D 0062",
            "\"it\\'s \\\"x\\\"\" | 0069 0074 0027 0073 0020 0022 0078 0022",
            "\"a\\\\b\"           | 0061 005C 0062",
            "\"caf\\u00e9\"       | 0063 0061 0066 00E9",
            "\"\\u00E9\"          | 00E9",
            "\"\\U01F40E\"        | 1F40E",
            "\"\\uD83D\\uDC0E\"   | 1F40E",
            "\"\\u0022\"          | 0022",
            "\"\\U000041b\"       | 0041 0062",
    })
    void quoted_strings_read_feel_escape_sequences(String cell, String codePoints) {
        var value = Arrays.stream(codePoints.split(" "))
                .collect(StringBuilder::new, (text, hex) -> text.appendCodePoint(Integer.parseInt(hex, 16)), StringBuilder::append)
                .toString();
        Assertions.assertThat(CellParser.parse(cell, ColumnType.STRING)).as("Praecepta's syntax").isEqualTo(positive(new Equals(value)));
        Assertions.assertThat(CellParser.parseFeel(cell, ColumnType.STRING)).as("strict FEEL").isEqualTo(positive(new Equals(value)));
        Assertions.assertThat(OutputExpression.parse(cell).evaluate(name -> null)).as("an output expression").isEqualTo(value);
    }

    @Test
    void escape_sequences_hold_in_lists_comparisons_and_intervals() {
        Assertions.assertThat(CellParser.parseFeel("\"a\\tb\", < \"\\n\", [\"\\u0041\"..\"\\U00005A\"]", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a\tb"), new Compare(Operator.LT, "\n"), new Range("A", true, "Z", true)));
        Assertions.assertThat(CellParser.parse("not(\"a\\nb\")", ColumnType.STRING)).isEqualTo(negated(new Equals("a\nb")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "\"a\\qb\"     | \\q is not an escape sequence; write \\\\ for a backslash in \"a\\qb\"",
            "\"\\u12\"     | \\u is not followed by the 4 hexadecimal digits of a character",
            "\"\\u12G4\"   | \\u is not followed by the 4 hexadecimal digits of a character",
            "\"\\U110000\" | \\U is not followed by the 6 hexadecimal digits of a character",
            "\"\\U1F40E\"  | \\U is not followed by the 6 hexadecimal digits of a character",
            "\"\\x41\"     | \\x is not an escape sequence",
    })
    void a_backslash_before_anything_but_an_escape_sequence_is_rejected(String cell, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parse(cell, ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel(cell, ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void a_backslash_in_quotes_starts_an_escape_sequence_even_in_a_path() {
        // \t is a tab, so this is "C:", a tab and "emp"; a backslash is written \\
        Assertions.assertThat(CellParser.parse("\"C:\\temp\"", ColumnType.STRING)).isEqualTo(positive(new Equals("C:\temp")));
        Assertions.assertThat(CellParser.parse("\"C:\\\\temp\"", ColumnType.STRING)).isEqualTo(positive(new Equals("C:\\temp")));
    }

    /** Grammar rule 33: a string literal holds no vertical space (rule 62: U+000A to U+000D); it is written \n or \r. */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x0A, 0x0B, 0x0C, 0x0D})
    void a_line_break_inside_a_quoted_string_is_rejected_in_strict_feel(int verticalSpace) {
        var cell = "\"a" + (char) verticalSpace + "b\"";
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel(cell, ColumnType.STRING))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining("a string literal may not hold a line break; write \\n or \\r");
        Assertions.assertThatThrownBy(() -> OutputExpression.parse(cell + " + x"))
                .isInstanceOf(ExpressionSyntaxException.class)
                .hasMessageContaining("a string literal may not hold a line break");
        // Praecepta's own syntax, as spreadsheets hold it, keeps it
        Assertions.assertThat(CellParser.parse(cell, ColumnType.STRING)).isEqualTo(positive(new Equals("a" + (char) verticalSpace + "b")));
        Assertions.assertThat(CellParser.parseFeel("\"a\\nb\"", ColumnType.STRING)).isEqualTo(positive(new Equals("a\nb")));
    }

    @Test
    void unquoted_words_keep_their_backslashes() {
        Assertions.assertThat(CellParser.parse("C:\\temp, a\\nb", ColumnType.STRING))
                .isEqualTo(positive(new Equals("C:\\temp"), new Equals("a\\nb")));
    }

    @Test
    void not_negates_a_list() {
        Assertions.assertThat(CellParser.parse("not(\"FR\", DE)", ColumnType.STRING))
                .isEqualTo(negated(new Equals("FR"), new Equals("DE")));
    }

    @Test
    void not_may_wrap_half_open_intervals() {
        Assertions.assertThat(CellParser.parse("not(0, [0..1))", ColumnType.NUMBER))
                .isEqualTo(negated(new Equals(BigDecimal.ZERO), new Range(BigDecimal.ZERO, true, BigDecimal.ONE, false)));
    }

    @Test
    void null_is_the_missing_value_test() {
        Assertions.assertThat(CellParser.parse("null, \"PT\"", ColumnType.STRING))
                .isEqualTo(positive(new NullTest(), new Equals("PT")));
    }

    @Test
    void numbers_support_literals_comparisons_and_intervals() {
        Assertions.assertThat(CellParser.parse("< 18, 21, >= 65.5", ColumnType.NUMBER))
                .isEqualTo(positive(
                        new Compare(Operator.LT, new BigDecimal("18")),
                        new Equals(new BigDecimal("21")),
                        new Compare(Operator.GE, new BigDecimal("65.5"))));
    }

    @Test
    void booleans_are_true_or_false_without_comparisons() {
        Assertions.assertThat(CellParser.parse("true", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(true)));
        Assertions.assertThat(CellParser.parse("not(false)", ColumnType.BOOLEAN)).isEqualTo(negated(new Equals(false)));
        Assertions.assertThat(CellParser.parse("true, null", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(true), new NullTest()));
        Assertions.assertThat(CellParser.parse("true", ColumnType.STRING))
                .as("an unquoted word in a STRING column is still text")
                .isEqualTo(positive(new Equals("true")));
    }

    @Test
    void strict_feel_reads_unquoted_words_as_names_not_strings() {
        // Praecepta's own tables (Excel, CSV, code) keep the shorter syntax
        Assertions.assertThat(CellParser.parse("PT, ES", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        // in DMN an unquoted word names another input, compared with when evaluating
        Assertions.assertThat(CellParser.parseFeel("\"PT\", \"ES\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("PT"), new Equals("ES")));
        Assertions.assertThat(CellParser.parseFeel("not(Complex.aString)", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Negated(List.of(new Equals(new UnaryTests.Name("Complex.aString")))));
        Assertions.assertThat(CellParser.parseFeel("[numB..10], > dateE", ColumnType.NUMBER))
                .isEqualTo(positive(new Range(new UnaryTests.Name("numB"), true, new BigDecimal("10"), true),
                        new Compare(Operator.GT, new UnaryTests.Name("dateE"))));
        // true, false and null stay literals; and a list holding a name is not a list of values
        Assertions.assertThat(CellParser.parseFeel("true", ColumnType.BOOLEAN)).isEqualTo(positive(new Equals(Boolean.TRUE)));
        Assertions.assertThat(CellParser.parseFeel("\"PT\", ES", ColumnType.STRING).values()).isEmpty();
    }

    @Test
    void strict_feel_needs_the_date_and_time_functions() {
        // in FEEL a bare 2025-01-31 is a subtraction and 08:30 is not valid at all
        Assertions.assertThat(CellParser.parse("< 2025-01-31", ColumnType.DATE))
                .isEqualTo(CellParser.parseFeel("< date(\"2025-01-31\")", ColumnType.DATE));
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("< 2025-01-31", ColumnType.DATE))
                .hasMessageContaining("is not a date; write date(\"yyyy-mm-dd\")");
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("08:30", ColumnType.TIME))
                .hasMessageContaining("is not a time");
        Assertions.assertThatThrownBy(() -> CellParser.parseFeel("2025-01-31T08:30", ColumnType.DATE_TIME))
                .hasMessageContaining("is not a date and time");
    }

    @Test
    void times_and_date_times_use_their_functions_or_iso_text() {
        var morning = LocalTime.of(8, 30);
        Assertions.assertThat(CellParser.parse("[time(\"08:30:00\")..12:00)", ColumnType.TIME))
                .isEqualTo(positive(new Range(morning, true, LocalTime.NOON, false)));
        Assertions.assertThat(CellParser.parse(">= 08:30", ColumnType.TIME))
                .isEqualTo(positive(new Compare(Operator.GE, morning)));
        var moment = LocalDateTime.of(2025, 1, 31, 8, 30, 15, 500_000_000);
        Assertions.assertThat(CellParser.parse("date and time(\"2025-01-31T08:30:15.5\"), 2025-01-31T08:30:15.5", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(moment), new Equals(moment)));
    }

    @Test
    void times_may_have_an_offset_and_date_times_an_offset_or_a_zone_id() {
        var plusOne = ZoneOffset.ofHours(1);
        Assertions.assertThat(CellParser.parseFeel("time(\"08:30:00+01:00\"), < time(\"08:30:00Z\")", ColumnType.TIME))
                .isEqualTo(positive(new Equals(OffsetTime.of(8, 30, 0, 0, plusOne)),
                        new Compare(Operator.LT, OffsetTime.of(8, 30, 0, 0, ZoneOffset.UTC))));
        Assertions.assertThat(CellParser.parse("08:30-05:00", ColumnType.TIME))
                .isEqualTo(positive(new Equals(OffsetTime.of(8, 30, 0, 0, ZoneOffset.ofHours(-5)))));
        var paris = ZonedDateTime.of(2025, 1, 31, 8, 30, 0, 0, ZoneId.of("Europe/Paris"));
        Assertions.assertThat(CellParser.parseFeel("date and time(\"2025-01-31T08:30:00@Europe/Paris\")", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(paris)));
        Assertions.assertThat(CellParser.parse("2025-01-31T08:30@Europe/Paris, 2025-01-31T08:30Z", ColumnType.DATE_TIME))
                .isEqualTo(positive(new Equals(paris), new Equals(ZonedDateTime.of(2025, 1, 31, 8, 30, 0, 0, ZoneOffset.UTC))));
        // the ends of an interval are ordered by instant: 08:30+01:00 is 07:30Z
        Assertions.assertThat(CellParser.parseFeel("[time(\"08:30:00+01:00\")..time(\"07:30:00Z\")]", ColumnType.TIME))
                .isEqualTo(positive(new Range(OffsetTime.of(8, 30, 0, 0, plusOne), true, OffsetTime.of(7, 30, 0, 0, ZoneOffset.UTC), true)));
    }

    @Test
    void zoned_literals_are_written_back_as_they_were_read() {
        for (var cell : List.of("time(\"08:30:00+01:00\")", "time(\"08:30:00.5Z\")", "date and time(\"2025-01-31T08:30:00Z\")",
                "date and time(\"2025-01-31T08:30:00-05:00\")", "date and time(\"2025-01-31T08:30:00@Europe/Paris\")")) {
            var type = cell.startsWith("time") ? ColumnType.TIME : ColumnType.DATE_TIME;
            var value = CellParser.parseFeel(cell, type).values().orElseThrow().getFirst();
            Assertions.assertThat(FeelLiterals.literal(value)).isEqualTo(cell);
            Assertions.assertThat(type == ColumnType.TIME ? FeelTemporals.parseTime(FeelLiterals.text(value))
                    : FeelTemporals.parseDateTime(FeelLiterals.text(value))).isEqualTo(value);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "[1..5];  true;  true",
            "(1..5];  false; true",
            "]1..5];  false; true",
            "[1..5);  true;  false",
            "[1..5[;  true;  false",
            "]1..5[;  false; false",
    })
    void interval_brackets_set_open_and_closed_ends(String cell, boolean lowClosed, boolean highClosed) {
        Assertions.assertThat(CellParser.parse(cell, ColumnType.NUMBER))
                .isEqualTo(positive(new Range(BigDecimal.ONE, lowClosed, new BigDecimal(5), highClosed)));
    }

    @Test
    void intervals_with_outward_brackets_split_correctly_from_following_tests() {
        Assertions.assertThat(CellParser.parse("]1..2[, 7", ColumnType.NUMBER))
                .isEqualTo(positive(new Range(BigDecimal.ONE, false, new BigDecimal(2), false), new Equals(new BigDecimal(7))));
    }

    @Test
    void escapes_and_parentheses_decide_where_a_test_ends() {
        // an escaped backslash does not escape the closing quote
        Assertions.assertThat(CellParser.parse("\"a\\\\\", \"b\"", ColumnType.STRING))
                .isEqualTo(positive(new Equals("a\\"), new Equals("b")));
        // the parenthesis of a date call does not close an interval; the one after it does
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-01-31\")), date(\"2025-03-01\")", ColumnType.DATE))
                .isEqualTo(positive(
                        new Range(LocalDate.of(2025, 1, 1), true, LocalDate.of(2025, 1, 31), false),
                        new Equals(LocalDate.of(2025, 3, 1))));
    }

    @Test
    void unquoted_text_over_several_lines_is_not_checked_for_feel_syntax() {
        Assertions.assertThat(CellParser.parse("a(\nb", ColumnType.STRING)).isEqualTo(positive(new Equals("a(\nb")));
    }

    @Test
    void strings_compare_and_form_intervals_quoted_or_unquoted() {
        Assertions.assertThat(CellParser.parse("[\"A\"..\"M\"]", ColumnType.STRING))
                .isEqualTo(positive(new Range("A", true, "M", true)));
        Assertions.assertThat(CellParser.parseFeel("< \"N\", >= \"x y\"", ColumnType.STRING))
                .isEqualTo(positive(new Compare(Operator.LT, "N"), new Compare(Operator.GE, "x y")));
        // Praecepta's unquoted words work as ends too, and the ends may be equal
        Assertions.assertThat(CellParser.parse("(a..m], [PT..PT]", ColumnType.STRING))
                .isEqualTo(positive(new Range("a", false, "m", true), new Range("PT", true, "PT", true)));
        Assertions.assertThat(CellParser.parse("not(< \"N\")", ColumnType.STRING))
                .isEqualTo(new UnaryTests.Negated(List.of(new Compare(Operator.LT, "N"))));
    }

    @Test
    void dates_use_the_date_function_or_iso_text() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 12, 31);
        Assertions.assertThat(CellParser.parse("[date(\"2025-01-01\")..date(\"2025-12-31\")]", ColumnType.DATE))
                .isEqualTo(positive(new Range(start, true, end, true)));
        Assertions.assertThat(CellParser.parse("< 2025-01-01", ColumnType.DATE))
                .isEqualTo(positive(new Compare(Operator.LT, start)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "BOOLEAN; < true;         comparisons are not supported on BOOLEAN columns",
            "BOOLEAN; [false..true];  intervals are not supported on BOOLEAN columns",
            "BOOLEAN; yes;            is not a boolean",
            "TIME;   time(\"25:00:00\"); invalid time",
            "TIME;   8am;             is not a time",
            "TIME;   time(\"10:00:00@Europe/Paris\"); is a time with a zone id, which is not supported",
            "TIME;   10:00:00+2;      is not a time",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00@Mars/Olympus\"); invalid date and time",
            "DATE_TIME; date and time(\"2025-01-01T10:00:00-0500\"); invalid date and time",
            "DATE_TIME; date and time(\"2025-10-26T02:30+01:00[Europe/Paris]\"); write a zone id as @Europe/Paris",
            "TIME;   [time(\"11:00:00\")..time(\"10:00:00+05:00\")]; starts after it ends",
            "TIME;   [time(\"10:00:00Z\")..time(\"10:00:00+01:00\")]; starts after it ends",
            "DATE_TIME; 2025-01-01;   is not a date and time",
            "DATE_TIME; date and time(\"2025-01-31T\"); invalid date and time",
            "TIME;   time(\"10:00:00+0200\"); invalid time",
            "DATE_TIME; [date and time(\"2025-01-02T00:00:00\")..date and time(\"2025-01-01T00:00:00\")]; starts after it ends",
            "NUMBER; abc;             is not a number",
            "NUMBER; [5..1];          starts after it ends",
            "DATE;   date(\"2025-02-30\"); invalid date",
            "DATE;   2025;            is not a date",
            "STRING; \"open;          unterminated string",
            "NUMBER; [1..2;           unterminated interval",
            "STRING; a,,b;            empty test",
            "STRING; a(b;             quote the text",
            "STRING; \"a\\\";         unterminated string",
            "STRING; a), b;           unexpected characters in 'a), b'",
            "NUMBER; [1..(2, 3];      unterminated interval",
            "NUMBER; [1..\"2];        unterminated interval",
    })
    void invalid_cells_are_rejected_with_a_reason(ColumnType type, String cell, String reason) {
        Assertions.assertThatThrownBy(() -> CellParser.parse(cell, type))
                .isInstanceOf(CellSyntaxException.class)
                .hasMessageContaining(reason);
    }

    private static UnaryTests positive(UnaryTests.Test... tests) {
        return new UnaryTests.Positive(List.of(tests));
    }

    private static UnaryTests negated(UnaryTests.Test... tests) {
        return new UnaryTests.Negated(List.of(tests));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "age                          | true",
            "Credit Score                 | true",
            "Applicant data.Monthly income | true",
            "Rate 2                       | true",
            "is_ok?                       | true",
            "𝓐ge                       | true",
            "Ⅷ rate                    | false",
            "2nd rate                     | false",
            "a..b                         | false",
            "a. b                         | false",
            "a  b                         | false",
            "a.2b                         | false",
            "age * 2                      | false",
            "f(x)                         | false",
    })
    void feel_names_allow_single_spaces_between_words(String text, boolean name) {
        Assertions.assertThat(CellParser.isName(text)).as(text).isEqualTo(name);
    }

    @Test
    void very_long_names_are_checked_without_a_stack_overflow() {
        Assertions.assertThat(CellParser.isName("a".repeat(1_000_000))).isTrue();
        Assertions.assertThat(CellParser.isName("a b.".repeat(250_000) + "c")).isTrue();
    }
}
