/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Kind;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;

/** Readable examples of FEEL cells on typed columns, input value lists and gap reports. */
class TypedColumnsTest {

    record Applicant(String country, Integer age, LocalDate since) {
    }

    //   country        age          since                       → tier
    // 1 "PT","ES"      [18..65)     < date("2020-01-01")         gold
    // 2 not("FR")      >= 18        -                            silver
    // 3 -              < 18         -                            junior
    // 4 null           -            -                            unknown
    private static final List<Praeceptum> RULES = List.of(
            rule(1, "\"PT\",\"ES\"", "[18..65)", "< date(\"2020-01-01\")", "gold"),
            rule(2, "not(\"FR\")", ">= 18", "-", "silver"),
            rule(3, "-", "< 18", "", "junior"),
            rule(4, "null", "", "", "unknown"));

    @Test
    void numbers_dates_lists_and_negation() {
        var engine = engine(HitPolicy.FIRST).build();
        Assertions.assertThat(engine.evaluate(new Applicant("PT", 30, LocalDate.of(2019, 5, 1)))).containsExactly("gold");
        Assertions.assertThat(engine.evaluate(new Applicant("PT", 65, LocalDate.of(2019, 5, 1)))).containsExactly("silver"); // 65 excluded
        Assertions.assertThat(engine.evaluate(new Applicant("PT", 30, LocalDate.of(2020, 1, 1)))).containsExactly("silver"); // not before 2020
        Assertions.assertThat(engine.evaluate(new Applicant("FR", 30, null))).isEmpty();                                   // not("FR")
        Assertions.assertThat(engine.evaluate(new Applicant("FR", 12, null))).containsExactly("junior");
    }

    @Test
    void missing_values_follow_feel() {
        var engine = engine(HitPolicy.COLLECT).build();
        // null country: fails "PT","ES"; satisfies not("FR"); matches the null test
        Assertions.assertThat(engine.evaluate(new Applicant(null, 30, LocalDate.of(2019, 1, 1)))).containsExactly("silver", "unknown");
        // null age: comparisons are unknown, so rules 1-3 fail; only the null-country rule could match
        Assertions.assertThat(engine.evaluate(new Applicant("PT", null, null))).isEmpty();
    }

    @Test
    void numbers_compare_by_value_whatever_their_java_type() {
        var engine = PraeceptaEngine.<Map<String, Number>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .numberInput("x", input -> input.get("x"))
                .output(output -> output.values().get("tier"))
                .rules(List.of(new Praeceptum(1, new PraeceptaInput(Map.of("x", "[1..2]")), new PraeceptaOutput(Map.of("tier", "in")))))
                .build();
        for (Number x : List.<Number>of(1, 2L, 1.5d, new BigDecimal("2.000"))) {
            Assertions.assertThat(engine.evaluate(Map.of("x", x))).as("%s", x).containsExactly("in");
        }
        Assertions.assertThat(engine.evaluate(Map.of("x", 2.0001))).isEmpty();
    }

    @Test
    void map_based_inputs_parse_text_by_column_type() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("age", ColumnType.NUMBER)
                .columnType("since", ColumnType.DATE)
                .output(output -> output.values().get("tier"))
                .rules(RULES)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("country", "ES", "age", "40", "since", "2018-07-01"))).containsExactly("gold");
        var notANumber = Map.of("country", "ES", "age", "forty");
        Assertions.assertThatThrownBy(() -> engine.evaluate(notANumber))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("age=forty");
    }

    @Test
    void declared_input_values_reject_anything_else() {
        var engine = engine(HitPolicy.FIRST)
                .allowedValues("country", List.of("PT", "ES", "FR"))
                .allowedValues("age", "[0..120]")
                .build();
        var undeclaredCountry = new Applicant("DE", 30, null);
        var undeclaredAge = new Applicant("PT", 130, null);
        Assertions.assertThatThrownBy(() -> engine.evaluate(undeclaredCountry))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> Assertions.assertThat(e.getColumn()).isEqualTo("country"));
        Assertions.assertThatThrownBy(() -> engine.evaluate(undeclaredAge))
                .isInstanceOf(InvalidInputException.class);
        Assertions.assertThat(engine.evaluate(new Applicant(null, 30, null))).isNotEmpty(); // missing is always allowed
    }

    @Test
    void declared_input_values_reveal_gaps() {
        var engine = engine(HitPolicy.FIRST)
                .allowedValues("country", List.of("PT", "ES", "FR"))
                .allowedValues("age", "[0..120]")
                .build();
        var gaps = engine.validationReport().issues(Kind.GAP);
        Assertions.assertThat(gaps).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail()).isEqualTo("country = \"FR\", age in [18..120]");
            Assertions.assertThat(gap.example()).containsEntry("country", "FR");
        });
    }

    @Test
    void without_string_value_lists_gaps_are_not_reported() {
        Assertions.assertThat(engine(HitPolicy.FIRST).build().validationReport().issues(Kind.GAP)).isEmpty();
    }

    @Test
    void strings_compare_by_character_and_then_reveal_gaps_without_value_lists() {
        //   surname          → desk
        // 1 < "N"              1
        // 2 ["N".."T")         2
        var engine = PraeceptaEngine.<String, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .input("surname", surname -> surname)
                .output(output -> output.values().get("desk"))
                .rules(List.of(
                        new Praeceptum(1, new PraeceptaInput(Map.of("surname", "< \"N\"")), new PraeceptaOutput(Map.of("desk", "1"))),
                        new Praeceptum(2, new PraeceptaInput(Map.of("surname", "[\"N\"..\"T\")")), new PraeceptaOutput(Map.of("desk", "2")))))
                .build();
        Assertions.assertThat(engine.evaluate("Mills")).containsExactly("1");
        Assertions.assertThat(engine.evaluate("N")).containsExactly("2");
        Assertions.assertThat(engine.evaluate("Silva")).containsExactly("2");
        Assertions.assertThat(engine.evaluate("Taylor")).isEmpty();
        Assertions.assertThat(engine.evaluate("nash")).as("lower case sorts after upper case").isEmpty();
        Assertions.assertThat(engine.evaluate(null)).as("a missing value fails comparisons").isEmpty();
        // with comparisons, a string column has no class of "any other value", so gaps are reported
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail()).isEqualTo("surname in [\"T\"..+inf)");
            Assertions.assertThat(gap.example()).containsEntry("surname", "T");
        });
    }

    @Test
    void invalid_cells_name_the_rule_and_column() {
        var broken = List.of(rule(7, "\"PT\"", "[65..18]", "", "x"));
        var builder = engine(HitPolicy.FIRST).rules(broken);
        Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(InvalidDecisionTableException.class)
                .hasMessageContaining("Rule 7, column 'age'")
                .hasMessageContaining("interval starts after it ends");
    }

    record Shift(Boolean overtime, LocalTime start, LocalDateTime at) {
    }

    //   overtime   start                          at                                       → rate
    // 1 true       -                              -                                         double
    // 2 false      >= time("22:00:00")            -                                         night
    // 3 false      < time("06:00:00")             -                                         night
    // 4 false      -                              >= date and time("2025-12-25T00:00:00")   holiday
    // 5 -          -                              -                                         standard
    private static final List<Praeceptum> SHIFTS = List.of(
            shift(1, "true", "-", "-", "double"),
            shift(2, "false", ">= time(\"22:00:00\")", "-", "night"),
            shift(3, "false", "< 06:00", "-", "night"),
            shift(4, "false", "-", ">= date and time(\"2025-12-25T00:00:00\")", "holiday"),
            shift(5, "-", "-", "-", "standard"));

    @Test
    void booleans_times_and_date_times() {
        var engine = shiftEngine(SHIFTS);
        var weekday = LocalDateTime.of(2025, 3, 4, 9, 0);
        Assertions.assertThat(engine.evaluate(new Shift(true, LocalTime.of(9, 0), weekday))).containsExactly("double");
        Assertions.assertThat(engine.evaluate(new Shift(false, LocalTime.of(22, 0), weekday))).containsExactly("night");
        Assertions.assertThat(engine.evaluate(new Shift(false, LocalTime.of(5, 59, 59), weekday))).containsExactly("night");
        Assertions.assertThat(engine.evaluate(new Shift(false, LocalTime.of(6, 0), weekday))).containsExactly("standard");
        Assertions.assertThat(engine.evaluate(new Shift(false, LocalTime.NOON, LocalDateTime.of(2025, 12, 25, 0, 0))))
                .containsExactly("holiday");
        Assertions.assertThat(engine.evaluate(new Shift(false, LocalTime.NOON, LocalDateTime.of(2025, 12, 24, 23, 59, 59, 999_999_999))))
                .containsExactly("standard");
        // a missing boolean is neither true nor false
        Assertions.assertThat(engine.evaluate(new Shift(null, LocalTime.of(23, 0), weekday))).containsExactly("standard");
    }

    @Test
    void map_based_inputs_parse_booleans_times_and_date_times() {
        var engine = PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .inputs(PraeceptaInput::new)
                .columnType("overtime", ColumnType.BOOLEAN)
                .columnType("start", ColumnType.TIME)
                .columnType("at", ColumnType.DATE_TIME)
                .output(output -> output.values().get("rate"))
                .rules(SHIFTS)
                .build();
        Assertions.assertThat(engine.evaluate(Map.of("overtime", "TRUE"))).containsExactly("double");
        Assertions.assertThat(engine.evaluate(Map.of("overtime", "false", "start", "23:15"))).containsExactly("night");
        Assertions.assertThat(engine.evaluate(Map.of("overtime", "false", "start", "12:00", "at", "2025-12-25T08:00")))
                .containsExactly("holiday");
        var notABoolean = Map.of("overtime", "yes");
        Assertions.assertThatThrownBy(() -> engine.evaluate(notABoolean))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a valid boolean");
        var zoned = Map.of("start", "10:00:00Z");
        Assertions.assertThatThrownBy(() -> engine.evaluate(zoned))
                .as("times have no zone")
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("is not a valid time");
    }

    @Test
    void boolean_and_time_gaps_are_reported_as_feel_literals() {
        var engine = shiftEngine(List.of(
                shift(1, "true", "-", "-", "double"),
                shift(2, "false", "< time(\"12:00:00\")", "-", "morning"),
                shift(3, "-", "< time(\"00:00:00\")", "-", "never")));
        Assertions.assertThat(engine.validationReport().issues(Kind.GAP)).singleElement().satisfies(gap -> {
            Assertions.assertThat(gap.detail()).isEqualTo("overtime = false, start in [time(\"12:00:00\")..+inf)");
            Assertions.assertThat(gap.example()).containsEntry("overtime", false).containsEntry("start", LocalTime.NOON);
        });
        // no time of day is before midnight
        Assertions.assertThat(engine.validationReport().issues(Kind.UNREACHABLE_RULE))
                .extracting(issue -> issue.ruleIds().getFirst())
                .containsExactly(3);
    }

    /** A FIRST engine over shifts, bound with the boolean, time and date-time getters. */
    private static PraeceptaEngine<Shift, String> shiftEngine(List<Praeceptum> rules) {
        return PraeceptaEngine.<Shift, String>builder()
                .hitPolicy(HitPolicy.FIRST)
                .booleanInput("overtime", Shift::overtime)
                .timeInput("start", Shift::start)
                .dateTimeInput("at", Shift::at)
                .output(output -> output.values().get("rate"))
                .rules(rules)
                .build();
    }

    private static Praeceptum shift(int id, String overtime, String start, String at, String rate) {
        return new Praeceptum(id, new PraeceptaInput(Map.of("overtime", overtime, "start", start, "at", at)),
                new PraeceptaOutput(Map.of("rate", rate)));
    }

    private static PraeceptaEngine.Builder<Applicant, String> engine(HitPolicy policy) {
        return PraeceptaEngine.<Applicant, String>builder()
                .hitPolicy(policy)
                .input("country", Applicant::country)
                .numberInput("age", Applicant::age)
                .dateInput("since", Applicant::since)
                .output(output -> output.values().get("tier"))
                .rules(RULES);
    }

    private static Praeceptum rule(int id, String country, String age, String since, String tier) {
        var cells = new HashMap<String, String>();
        cells.put("country", country);
        cells.put("age", age);
        cells.put("since", since);
        return new Praeceptum(id, new PraeceptaInput(cells), new PraeceptaOutput(Map.of("tier", tier)));
    }
}
