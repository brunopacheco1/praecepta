/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How {@link FeelScan} names what a FEEL expression needs. */
class FeelScanTest {

    @Test
    void a_built_in_function_is_named_with_every_word_of_its_name() {
        assertThat(FeelScan.features("string length(Full Name) + 1", null)).containsExactly(Feature.builtIn("string length"));
        assertThat(FeelScan.features("x + day of year(today())", null))
                .containsExactly(Feature.builtIn("day of year"), Feature.builtIn("today"));
    }

    @Test
    void keywords_in_a_built_in_functions_name_are_not_read_as_operators() {
        assertThat(FeelScan.features("years and months duration(date(\"2020-01-01\"), date(\"2021-01-01\"))", null))
                .containsExactly(Feature.builtIn("years and months duration"));
    }

    /** Output expressions read these: date and time functions of literals, arithmetic, names, strings and comments. */
    @ParameterizedTest
    @ValueSource(strings = {
            "date(\"2025-01-31\") + duration(\"P1D\")",
            "@\"2025-01-31T10:00:00@Europe/Paris\" - @\"PT1H\"",
            "date and time(\"2025-01-31T10:00:00\") - -x ** 2 / (y + 1)",
            "time(10, 30, 0) + duration(\"PT1H\")",
            "\"if \" + Name + \" in [1..2] then {a: 1}\" // for x in xs return x",
            "(price - discount) * 1.2 /* if then else */",
    })
    void what_output_expressions_read_needs_nothing(String text) {
        assertThat(FeelScan.features(text, null)).isEmpty();
    }

    @Test
    void a_date_function_of_something_other_than_literals_is_the_built_in() {
        assertThat(FeelScan.features("date(Birth Date String)", null)).containsExactly(Feature.builtIn("date"));
        assertThat(FeelScan.features("time(date(\"2017-08-10\"))", null)).containsExactly(Feature.builtIn("time"));
        // DMN 1.5 has these as built-in conversion functions (Table 72), whether output expressions read them or not
        assertThat(FeelScan.features("date and time(date(\"2025-01-30\"), time(\"10:00:00\"))", null))
                .containsExactly(Feature.builtIn("date and time"));
    }

    @Test
    void constructs_are_named_in_the_order_written() {
        assertThat(FeelScan.features("if count > 1 then [1, 2] else {a: null}", null)).containsExactly(
                Feature.feel("if then else"), Feature.feel("comparisons"), Feature.feel("lists"), Feature.feel("contexts"),
                Feature.feel("null"));
    }

    @Test
    void a_bracket_after_a_value_is_a_filter_and_after_a_keyword_a_list() {
        assertThat(FeelScan.features("Items[1]", null)).containsExactly(Feature.feel("filters"));
        assertThat(FeelScan.features("x in [1, 2]", null)).containsExactly(Feature.feel("in"), Feature.feel("lists"));
        assertThat(FeelScan.features("4 in [2..4]", null)).containsExactly(Feature.feel("in"), Feature.feel("lists"), Feature.feel("ranges"));
    }

    @Test
    void the_in_of_an_iteration_is_part_of_it() {
        assertThat(FeelScan.features("for x in xs return x * 2", null)).containsExactly(Feature.feel("for loops"));
        assertThat(FeelScan.features("some x in xs satisfies x > 2", null))
                .containsExactly(Feature.feel("quantified expressions (some, every)"), Feature.feel("comparisons"));
    }

    @Test
    void the_type_of_an_instance_of_is_not_read_as_an_expression() {
        assertThat(FeelScan.features("x instance of days and time duration", null)).containsExactly(Feature.feel("instance of"));
        assertThat(FeelScan.features("x instance of list<number>", null)).containsExactly(Feature.feel("instance of"));
    }

    @Test
    void a_property_of_a_literal_is_a_path() {
        assertThat(FeelScan.features("date(\"2018-12-10\").year", null)).containsExactly(Feature.feel("paths"));
    }

    @Test
    void a_function_literal_is_a_function_definition() {
        assertThat(FeelScan.features("(function(a) a + 1)(2)", null)).containsExactly(Feature.FUNCTION_DEFINITIONS);
    }

    @Test
    void an_invocation_names_the_knowledge_model_or_decision_service_of_the_model() {
        var invocation = new DmnModel(model("compliance-level-2/0009-invocation-arithmetic/0009-invocation-arithmetic.dmn"));
        assertThat(FeelScan.features("PMT(Loan.amount, Loan.rate, Loan.term)+fee", invocation))
                .containsExactly(Feature.KNOWLEDGE_MODELS);
        var services = new DmnModel(model("compliance-level-3/0085-decision-services/0085-decision-services.dmn"));
        assertThat(FeelScan.features("decisionService_005(\"bar\")", services)).containsExactly(Feature.DECISION_SERVICES);
        assertThat(FeelScan.features("my function(\"bar\")", services)).containsExactly(Feature.FUNCTION_DEFINITIONS);
    }

    private static Path model(String path) {
        return TckSuite.root().resolve(path);
    }
}
