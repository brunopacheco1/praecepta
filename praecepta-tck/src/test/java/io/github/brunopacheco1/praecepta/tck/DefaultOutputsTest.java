/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.tck;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.brunopacheco1.praecepta.engine.OutputStrategy;
import io.github.brunopacheco1.praecepta.engine.PraeceptaEngine;
import io.github.brunopacheco1.praecepta.engine.beans.DecisionTable;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.dmn.DmnReader;

/**
 * The TCK models that declare default output values ({@code defaultOutputEntry}, #79). Their test cases
 * leave a rule matching in every check {@link TckTest} runs, except through decisions Praecepta does not
 * evaluate, so these tests evaluate the tables where no rule matches: DMN 1.5, 10.3.2.10, "if no rules
 * match, if a default output value d is specified, DTI=FEEL(d)".
 */
class DefaultOutputsTest {

    private static final Path TCK = Path.of("src/test/resources/tck");

    /**
     * Age, RiskCategory and isAffordable as these models take them: a missing age with "Low" passes no rule's
     * age test, in each of the four tables.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0010-multi-output-U", "0108-first-hitpolicy", "0109-ruleOrder-hitpolicy", "0117-multi-any-hitpolicy"})
    void approvals_give_the_declared_status_and_rate_when_no_rule_matches(String model) throws IOException {
        var table = table("compliance-level-2/" + model, null);
        var expected = new HashMap<String, String>();
        expected.put("Status", "Declined");
        expected.put("Rate", "Standard");
        Assertions.assertThat(mapEngine(table).evaluate(Map.of("RiskCategory", "Low", "isAffordable", "true"))).containsExactly(expected);
    }

    /** The example of #79: 0108-first-hitpolicy with an input none of its rules match. */
    @Test
    void the_first_hit_policy_example_of_the_issue_gives_the_defaults() throws IOException {
        var engine = mapEngine(table("compliance-level-2/0108-first-hitpolicy", null));
        Assertions.assertThat(engine.evaluate(Map.of("Age", "30", "RiskCategory", "High", "isAffordable", "true")))
                .containsExactly(Map.of("Status", "Declined", "Rate", "Standard"));
        // a rule that matches is selected, as the TCK's test cases check
        Assertions.assertThat(engine.evaluate(Map.of("Age", "18", "RiskCategory", "Medium", "isAffordable", "true")))
                .containsExactly(Map.of("Status", "Approved", "Rate", "Best"));
    }

    /**
     * 0020-vacation-days computes "Total Vacation Days" as {@code Base Vacation Days + max(Extra days case 1,
     * Extra days case 3) + Extra days case 2}, from three COLLECT MAX tables whose default is 0, and a base of
     * 22. Praecepta evaluates only the tables; the sum is done here, so the TCK's expected totals (its test
     * file, test cases 001 to 007) check them, those where no rule of a table matches included (002 matches
     * none at all: without the defaults, the total would be null).
     */
    @ParameterizedTest(name = "age {0}, years of service {1}: {2} days")
    @CsvSource({"16, 1, 27", "25, 5, 22", "25, 20, 24", "44, 30, 30", "50, 20, 24", "50, 30, 30", "60, 20, 30"})
    void vacation_days_add_up_with_the_tables_defaults(String age, String years, String total) throws IOException {
        var input = Map.of("Age", age, "Years of Service", years);
        var case1 = extraDays("Extra days case 1").evaluate(input);
        var case2 = extraDays("Extra days case 2").evaluate(input);
        var case3 = extraDays("Extra days case 3").evaluate(input);
        Assertions.assertThat(List.of(case1, case2, case3)).allSatisfy(days -> Assertions.assertThat(days).hasSize(1));
        var sum = new BigDecimal(22).add(case1.getFirst().max(case3.getFirst())).add(case2.getFirst());
        Assertions.assertThat(sum).isEqualByComparingTo(total);
    }

    @Test
    void the_vacation_days_tables_read_their_defaults() throws IOException {
        Assertions.assertThat(table("compliance-level-3/0020-vacation-days", "Extra days case 2").defaultOutput())
                .contains(new PraeceptaOutput(Map.of("Extra days", "0"))); // the output is named by its label
    }

    @Test
    void a_rejected_default_output_is_reported_as_its_own_feature() {
        Assertions.assertThat(TckRunner.unsupportedFeature(
                "Decision 'Fee', output 'Fee': default output 'score + 1' is not supported; a default output must be a literal number"))
                .isEqualTo("default outputs that are not literals");
    }

    private static DecisionTable table(String testCase, String decision) throws IOException {
        var directory = TCK.resolve(testCase);
        try (InputStream dmn = Files.newInputStream(directory.resolve(directory.getFileName() + ".dmn"))) {
            return DmnReader.read(dmn, decision);
        }
    }

    private static PraeceptaEngine<Map<String, String>, Map<String, String>> mapEngine(DecisionTable table) {
        return PraeceptaEngine.<Map<String, String>, Map<String, String>>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(PraeceptaOutput::values)
                .build();
    }

    /** A table of 0020-vacation-days: its one output, untyped and so read as text, taken as a number and maximised. */
    private static PraeceptaEngine<Map<String, String>, BigDecimal> extraDays(String decision) throws IOException {
        var table = table("compliance-level-3/0020-vacation-days", decision);
        return PraeceptaEngine.<Map<String, String>, BigDecimal>builder()
                .table(table)
                .inputs(PraeceptaInput::new)
                .output(new OutputStrategy<>() {
                    @Override
                    public BigDecimal transform(PraeceptaOutput output) {
                        return new BigDecimal(output.values().get(table.outputs().getFirst().name()));
                    }

                    @Override
                    public BigDecimal max(BigDecimal a, BigDecimal b) {
                        return a.max(b);
                    }
                })
                .build();
    }
}
