/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.GraphValues.Binding;
import io.github.brunopacheco1.praecepta.engine.beans.ColumnType;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidInputException;
import io.github.brunopacheco1.praecepta.engine.feel.FeelRange;
import io.github.brunopacheco1.praecepta.engine.feel.FeelType;
import io.github.brunopacheco1.praecepta.engine.feel.YearsAndMonths;
import io.github.brunopacheco1.praecepta.engine.feel.ZonedTime;

/**
 * The values a decision graph passes between its elements: what it takes for an input data of each type and what it
 * rejects, how it reads a path, and how it binds a decision's value to the type the decision declares.
 */
class GraphValuesTest {

    private static final FeelType NUMBER = FeelType.of(ColumnType.NUMBER);
    private static final FeelType STRING = FeelType.of(ColumnType.STRING);
    private static final FeelType DATE_TIME = FeelType.of(ColumnType.DATE_TIME);

    private static Object normalise(Object raw, ColumnType type) {
        return GraphValues.normalise(raw, FeelType.of(type), "X");
    }

    @Test
    void a_number_of_any_java_type_is_passed_on_as_a_big_decimal_of_its_value() {
        var decimal = new BigDecimal("1.50");

        assertThat(GraphValues.number(decimal, "X")).isSameAs(decimal);
        assertThat(GraphValues.number(42, "X")).isEqualTo(new BigDecimal("42"));
        assertThat(GraphValues.number(42L, "X")).isEqualTo(new BigDecimal("42"));
        assertThat(GraphValues.number((short) 42, "X")).isEqualTo(new BigDecimal("42"));
        assertThat(GraphValues.number((byte) 42, "X")).isEqualTo(new BigDecimal("42"));
        assertThat(GraphValues.number(new BigInteger("123456789012345678901234567890"), "X"))
                .isEqualTo(new BigDecimal("123456789012345678901234567890"));
        assertThat(GraphValues.number(1.5d, "X")).isEqualTo(new BigDecimal("1.5"));
        assertThat(GraphValues.number(0.5f, "X")).isEqualTo(new BigDecimal("0.5"));
        assertThat(GraphValues.number(new AtomicInteger(7), "X")).as("any other Number, by its double value").isEqualTo(new BigDecimal("7.0"));
    }

    @Test
    void a_number_that_is_not_finite_is_rejected() {
        for (var notFinite : List.<Number>of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN)) {
            assertThatThrownBy(() -> normalise(notFinite, ColumnType.NUMBER)).isInstanceOf(InvalidInputException.class)
                    .hasMessage("Input X=" + notFinite + " is not a finite number");
        }
    }

    @Test
    void text_is_read_as_a_value_of_a_simple_type_and_blank_text_is_missing_but_for_a_string() {
        assertThat(normalise("42", ColumnType.NUMBER)).isEqualTo(new BigDecimal("42"));
        assertThat(normalise("2025-01-31", ColumnType.DATE)).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(normalise("true", ColumnType.BOOLEAN)).isEqualTo(true);
        assertThat(normalise("  ", ColumnType.NUMBER)).isNull();
        assertThat(normalise("", ColumnType.DATE)).isNull();
        assertThat(normalise("  ", ColumnType.STRING)).as("a string is its text, blank or not").isEqualTo("  ");
        assertThat(normalise(null, ColumnType.STRING)).isNull();
        assertThatThrownBy(() -> normalise("forty-two", ColumnType.NUMBER)).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=forty-two is not a valid number");
        assertThatThrownBy(() -> normalise("31/01/2025", ColumnType.DATE)).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=31/01/2025 is not a valid date");
    }

    @Test
    void a_value_of_a_java_type_that_is_not_its_simple_types_is_rejected() {
        var wrong = new LinkedHashMap<ColumnType, Object>();
        wrong.put(ColumnType.STRING, 1);
        wrong.put(ColumnType.NUMBER, true);
        wrong.put(ColumnType.BOOLEAN, 1);
        wrong.put(ColumnType.DATE, LocalDateTime.of(2025, 1, 31, 8, 30));
        wrong.put(ColumnType.TIME, LocalDate.of(2025, 1, 31));
        wrong.put(ColumnType.DATE_TIME, LocalDate.of(2025, 1, 31));
        wrong.put(ColumnType.DAY_TIME_DURATION, Period.ofMonths(1));
        wrong.put(ColumnType.YEAR_MONTH_DURATION, Duration.ofDays(1));

        assertThat(wrong).hasSize(ColumnType.values().length);
        wrong.forEach((type, value) -> assertThatThrownBy(() -> normalise(value, type)).as(type.name())
                .isInstanceOf(InvalidInputException.class).hasMessage("Input X=" + value + " is not a " + type.feelName()));
    }

    @Test
    void a_time_in_a_zone_of_one_fixed_offset_is_the_time_at_that_offset() {
        var time = LocalTime.of(0, 1);
        var paris = new ZonedTime(time, ZoneId.of("Europe/Paris"));
        var offset = OffsetTime.of(time, ZoneOffset.ofHours(2));

        assertThat(normalise(new ZonedTime(time, ZoneId.of("Etc/UTC")), ColumnType.TIME)).isEqualTo(OffsetTime.of(time, ZoneOffset.UTC));
        assertThat(normalise(paris, ColumnType.TIME)).isSameAs(paris);
        assertThat(normalise(time, ColumnType.TIME)).isSameAs(time);
        assertThat(normalise(offset, ColumnType.TIME)).isSameAs(offset);
    }

    @Test
    void a_date_and_time_with_an_offset_is_passed_on_as_a_zoned_one() {
        var local = LocalDateTime.of(2025, 1, 31, 8, 30);
        var zoned = local.atZone(ZoneId.of("Europe/Paris"));
        var offset = OffsetDateTime.of(local, ZoneOffset.ofHours(1));

        assertThat(normalise(local, ColumnType.DATE_TIME)).isSameAs(local);
        assertThat(normalise(zoned, ColumnType.DATE_TIME)).isSameAs(zoned);
        assertThat(normalise(offset, ColumnType.DATE_TIME)).isEqualTo(ZonedDateTime.of(local, ZoneOffset.ofHours(1)));
    }

    @Test
    void a_period_is_a_years_and_months_duration_unless_it_has_days() {
        var duration = new YearsAndMonths(14);

        assertThat(normalise(Period.of(1, 2, 0), ColumnType.YEAR_MONTH_DURATION)).isEqualTo(duration);
        assertThat(normalise(duration, ColumnType.YEAR_MONTH_DURATION)).isSameAs(duration);
        assertThatThrownBy(() -> normalise(Period.of(1, 2, 3), ColumnType.YEAR_MONTH_DURATION)).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=P1Y2M3D has days; a years and months duration has only years and months");
    }

    @Test
    void a_list_is_copied_unmodifiable_each_element_as_a_value_of_its_type() {
        var raw = new ArrayList<Object>(Arrays.asList(1, null, "3"));

        var list = GraphValues.normalise(raw, FeelType.listOf(ColumnType.NUMBER), "X");

        assertThat(list).isEqualTo(Arrays.asList(BigDecimal.ONE, null, new BigDecimal("3")));
        raw.clear();
        assertThat(list).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(3).isUnmodifiable();
        assertThatThrownBy(() -> GraphValues.normalise(1, FeelType.listOf(ColumnType.NUMBER), "X")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=1 is not a list");
        assertThatThrownBy(() -> GraphValues.normalise(List.of(1, "two"), FeelType.listOf(ColumnType.NUMBER), "X"))
                .isInstanceOf(InvalidInputException.class).hasMessage("Input X=two is not a valid number");
    }

    @Test
    void a_context_is_copied_unmodifiable_with_its_declared_components_only_each_named_in_what_it_rejects() {
        var components = new LinkedHashMap<String, FeelType>();
        components.put("name", STRING);
        components.put("age", NUMBER);
        var person = new FeelType.Context(components);

        var context = GraphValues.normalise(Map.of("age", 36, "undeclared", true), person, "Person");

        var expected = new LinkedHashMap<String, Object>();
        expected.put("name", null);
        expected.put("age", new BigDecimal("36"));
        assertThat(context).isEqualTo(expected);
        assertThat(context).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsOnlyKeys("name", "age");
        assertThatThrownBy(() -> ((Map<?, ?>) context).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> GraphValues.normalise("Ada", person, "Person")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input Person=Ada is not a context (a map of its components)");
        assertThatThrownBy(() -> GraphValues.normalise(Map.of("age", "old"), person, "Person")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input Person.age=old is not a valid number");
    }

    @Test
    void a_value_of_any_type_is_copied_with_numbers_as_big_decimals_and_keys_as_text() {
        var raw = new HashMap<Object, Object>();
        raw.put(1, List.of(2, "two", 2.5d));
        raw.put("none", null);
        var date = LocalDate.of(2025, 1, 31);
        raw.put("date", date);

        var value = GraphValues.normalise(raw, FeelType.ANY, "X");

        var expected = new HashMap<String, Object>();
        expected.put("1", List.of(new BigDecimal("2"), "two", new BigDecimal("2.5")));
        expected.put("none", null);
        expected.put("date", date);
        assertThat(value).isEqualTo(expected);
        assertThatThrownBy(() -> ((Map<?, ?>) value).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) ((Map<?, ?>) value).get("1")).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> GraphValues.normalise(Map.of("rate", List.of(Double.NaN)), FeelType.ANY, "X"))
                .as("a component is named by its path").isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X.rate=NaN is not a finite number");
    }

    /** A number inside that many lists. */
    private static Object nested(int lists) {
        Object value = 1;
        for (int i = 0; i < lists; i++) {
            value = List.of(value);
        }
        return value;
    }

    @Test
    void a_value_of_any_type_nests_as_many_lists_as_the_limit_and_no_more() {
        Object deepest = GraphValues.normalise(nested(GraphValues.MAX_DEPTH), FeelType.ANY, "X");
        for (int i = 0; i < GraphValues.MAX_DEPTH; i++) {
            deepest = ((List<?>) deepest).getFirst();
        }

        assertThat(deepest).isEqualTo(BigDecimal.ONE);
        assertThatThrownBy(() -> GraphValues.normalise(nested(GraphValues.MAX_DEPTH + 1), FeelType.ANY, "X"))
                .isInstanceOf(InvalidInputException.class).hasMessage("Input X=null nests more than 64 lists and contexts");
    }

    @Test
    void a_range_or_a_function_is_taken_only_for_an_input_of_that_type() {
        var range = FeelRange.between(BigDecimal.ONE, true, BigDecimal.TEN, false);
        var rangeOfNumbers = new FeelType.RangeOf(NUMBER);
        var function = new FeelType.FunctionOf(List.of(NUMBER), NUMBER);

        assertThat(GraphValues.normalise(range, rangeOfNumbers, "X")).isSameAs(range);
        assertThatThrownBy(() -> GraphValues.normalise(5, rangeOfNumbers, "X")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=5 is not a range<number>");
        assertThatThrownBy(() -> GraphValues.normalise(range, function, "X")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=[1..10) is not a function<number>->number");
        assertThatThrownBy(() -> GraphValues.normalise(1, FeelType.NULL, "X")).isInstanceOf(InvalidInputException.class)
                .hasMessage("Input X=1 is not a Null");
    }

    @Test
    void a_path_reads_a_contexts_component_and_of_a_list_each_elements() {
        var ada = Map.of("name", "Ada", "address", Map.of("city", "London"));
        var people = Arrays.asList(ada, "no context", null, Map.of("name", "Bob"));

        assertThat(GraphValues.read(ada, new String[0])).isSameAs(ada);
        assertThat(GraphValues.read(ada, new String[] {"address", "city"})).isEqualTo("London");
        assertThat(GraphValues.read(ada, new String[] {"age"})).isNull();
        assertThat(GraphValues.read(ada, new String[] {"age", "years"})).as("a path into a missing value").isNull();
        assertThat(GraphValues.read("Ada", new String[] {"name"})).as("a path into what is no context").isNull();
        assertThat(GraphValues.read(null, new String[] {"name"})).isNull();
        assertThat(GraphValues.read(people, new String[] {"name"})).isEqualTo(Arrays.asList("Ada", null, null, "Bob"));
        assertThat(GraphValues.read(people, new String[] {"address", "city"})).isEqualTo(Arrays.asList("London", null, null, null));
    }

    @Test
    void a_literal_expressions_number_is_passed_on_without_trailing_zeros_or_an_exponent() {
        assertThat(GraphValues.literalValue(new BigDecimal("1.50"))).isEqualTo(new BigDecimal("1.5"));
        assertThat(GraphValues.literalValue(new BigDecimal("1E+3"))).isEqualTo(new BigDecimal("1000"));
        assertThat(GraphValues.literalValue(new BigDecimal("0.000"))).isEqualTo(new BigDecimal("0"));
        assertThat(GraphValues.literalValue(new BigDecimal("0E+5"))).isEqualTo(new BigDecimal("0"));
        assertThat(GraphValues.literalValue(new BigDecimal("12"))).isEqualTo(new BigDecimal("12"));
        assertThat(GraphValues.literalValue("1.50")).isEqualTo("1.50");
        assertThat(GraphValues.literalValue(null)).isNull();
    }

    @Test
    void a_decisions_value_is_bound_as_its_binding_says() {
        var date = LocalDate.of(2025, 1, 31);

        assertThat(GraphValues.bind(date, Binding.AS_IS, DATE_TIME)).isSameAs(date);
        assertThat(GraphValues.bind(date, Binding.DATE_TO_DATE_TIME, DATE_TIME)).isEqualTo(ZonedDateTime.of(2025, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(GraphValues.bind(date, Binding.SINGLETON_LIST, DATE_TIME)).isEqualTo(List.of(date));
        assertThat(GraphValues.bind(List.of(date), Binding.FROM_SINGLETON_LIST, DATE_TIME)).isSameAs(date);
        assertThat(GraphValues.bind(List.of(date, date), Binding.FROM_SINGLETON_LIST, DATE_TIME)).as("a list of two").isNull();
        assertThat(GraphValues.bind(List.of(), Binding.FROM_SINGLETON_LIST, DATE_TIME)).as("an empty list").isNull();
        assertThat(GraphValues.bind(date, Binding.FROM_SINGLETON_LIST, DATE_TIME)).as("no list").isNull();
        assertThat(GraphValues.bind(date, Binding.NULL, DATE_TIME)).isNull();
        assertThat(GraphValues.bind(date, Binding.CHECKED, NUMBER)).isNull();
        assertThat(GraphValues.bind(BigDecimal.ONE, Binding.CHECKED, NUMBER)).isEqualTo(BigDecimal.ONE);
        for (var binding : Binding.values()) {
            assertThat(GraphValues.bind(null, binding, DATE_TIME)).as("null, bound %s", binding).isNull();
        }
    }

    @Test
    void a_value_checked_against_a_simple_type_conforms_as_it_is_or_as_the_one_element_of_a_list_or_as_a_date_for_a_date_and_time() {
        var date = LocalDate.of(2025, 1, 31);

        assertThat(GraphValues.conform("text", FeelType.ANY, 0)).isEqualTo("text");
        assertThat(GraphValues.conform(null, NUMBER, 0)).isNull();
        assertThat(GraphValues.conform(BigDecimal.ONE, NUMBER, 0)).isEqualTo(BigDecimal.ONE);
        assertThat(GraphValues.conform("one", NUMBER, 0)).isNull();
        assertThat(GraphValues.conform(List.of(BigDecimal.ONE), NUMBER, 0)).as("a list of one is its element").isEqualTo(BigDecimal.ONE);
        assertThat(GraphValues.conform(List.of(BigDecimal.ONE, BigDecimal.TEN), NUMBER, 0)).isNull();
        assertThat(GraphValues.conform(List.of(), NUMBER, 0)).isNull();
        assertThat(GraphValues.conform(Arrays.asList((Object) null), NUMBER, 0)).as("a list of one null").isNull();
        assertThat(GraphValues.conform(date, DATE_TIME, 0)).isEqualTo(ZonedDateTime.of(2025, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(GraphValues.conform(List.of(date), DATE_TIME, 0)).isEqualTo(ZonedDateTime.of(2025, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(GraphValues.conform(date, NUMBER, 0)).as("a date is converted for a date and time only").isNull();
    }

    @Test
    void a_value_checked_against_a_list_type_conforms_as_a_list_of_conforming_elements_or_as_a_list_of_itself() {
        var numbers = FeelType.listOf(ColumnType.NUMBER);
        var withNull = Arrays.asList(BigDecimal.ONE, null);

        assertThat(GraphValues.conform(withNull, numbers, 0)).as("null conforms to every type").isSameAs(withNull);
        assertThat(GraphValues.conform(List.of(BigDecimal.ONE, "two"), numbers, 0)).isNull();
        assertThat(GraphValues.conform(BigDecimal.ONE, numbers, 0)).as("a value is a list of one").isEqualTo(List.of(BigDecimal.ONE));
        assertThat(GraphValues.conform("one", numbers, 0)).isNull();
        assertThat(GraphValues.conform(List.of(List.of(BigDecimal.ONE)), new FeelType.ListOf(numbers), 0))
                .isEqualTo(List.of(List.of(BigDecimal.ONE)));
        assertThat(GraphValues.conform(List.of(BigDecimal.ONE), new FeelType.ListOf(numbers), 0))
                .as("the elements of a list are not converted").isNull();
    }

    @Test
    void a_value_checked_against_a_context_type_conforms_if_it_has_every_component_of_a_conforming_type() {
        var components = new LinkedHashMap<String, FeelType>();
        components.put("name", STRING);
        components.put("age", NUMBER);
        var person = new FeelType.Context(components);
        var ada = Map.of("name", "Ada", "age", BigDecimal.TEN, "more", true);
        var ageless = new HashMap<String, Object>();
        ageless.put("name", "Bob");
        ageless.put("age", null);

        assertThat(GraphValues.conform(ada, person, 0)).isSameAs(ada);
        assertThat(GraphValues.conform(ageless, person, 0)).as("a null component conforms").isSameAs(ageless);
        assertThat(GraphValues.conform(Map.of("name", "Eve"), person, 0)).as("a missing component").isNull();
        assertThat(GraphValues.conform(Map.of("name", "Eve", "age", "ten"), person, 0)).isNull();
        assertThat(GraphValues.conform("Ada", person, 0)).isNull();
        assertThat(GraphValues.conform(BigDecimal.ONE, new FeelType.RangeOf(NUMBER), 0)).as("a range type is not checked").isEqualTo(BigDecimal.ONE);
        assertThat(GraphValues.conform(BigDecimal.ONE, NUMBER, GraphValues.MAX_DEPTH + 1)).as("deeper than a type may nest").isNull();
        assertThat(GraphValues.conform(BigDecimal.ONE, NUMBER, GraphValues.MAX_DEPTH)).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void a_value_is_one_of_a_column_type_by_its_java_type() {
        assertThat(GraphValues.isValueOf(Period.ofYears(1), ColumnType.YEAR_MONTH_DURATION)).isTrue();
        assertThat(GraphValues.isValueOf(new YearsAndMonths(12), ColumnType.YEAR_MONTH_DURATION)).isTrue();
        assertThat(GraphValues.isValueOf(Duration.ofDays(1), ColumnType.YEAR_MONTH_DURATION)).isFalse();
        assertThat(GraphValues.isValueOf(Duration.ofDays(1), ColumnType.DAY_TIME_DURATION)).isTrue();
        assertThat(GraphValues.isValueOf(LocalDateTime.MIN, ColumnType.DATE_TIME)).isTrue();
        assertThat(GraphValues.isValueOf(ZonedDateTime.now(ZoneOffset.UTC), ColumnType.DATE_TIME)).isTrue();
        assertThat(GraphValues.isValueOf(LocalDate.MIN, ColumnType.DATE_TIME)).isFalse();
        assertThat(GraphValues.isValueOf(LocalTime.NOON, ColumnType.TIME)).isTrue();
        assertThat(GraphValues.isValueOf(OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC), ColumnType.TIME)).isTrue();
        assertThat(GraphValues.isValueOf(new ZonedTime(LocalTime.NOON, ZoneId.of("Europe/Paris")), ColumnType.TIME)).isTrue();
        assertThat(GraphValues.isValueOf(LocalDateTime.MIN, ColumnType.TIME)).isFalse();
        assertThat(GraphValues.isValueOf("true", ColumnType.BOOLEAN)).isFalse();
        assertThat(GraphValues.isValueOf(1, ColumnType.STRING)).isFalse();
    }

    @Test
    void an_outputs_text_is_read_as_a_value_of_its_type_and_what_is_none_is_rejected_by_the_types_name() {
        assertThat(GraphValues.outputValue(null, ColumnType.NUMBER)).isNull();
        assertThat(GraphValues.outputValue("1.50", ColumnType.NUMBER)).isEqualTo(new BigDecimal("1.50"));
        assertThatThrownBy(() -> GraphValues.outputValue("soon", ColumnType.DATE_TIME)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("output value 'soon' is not a date and time").hasCauseInstanceOf(RuntimeException.class);
    }
}
