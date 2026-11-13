/*
 * SPDX-FileCopyrightText: Bruno Pacheco
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.brunopacheco1.praecepta.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.brunopacheco1.praecepta.engine.ValidationReport.Severity;
import io.github.brunopacheco1.praecepta.engine.beans.HitPolicy;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaInput;
import io.github.brunopacheco1.praecepta.engine.beans.PraeceptaOutput;
import io.github.brunopacheco1.praecepta.engine.beans.Praeceptum;
import io.github.brunopacheco1.praecepta.engine.exceptions.InvalidDecisionTableException;

/**
 * The engine logs its validation report through {@link System.Logger}, which the JDK backs with
 * {@code java.util.logging}, unless the builder turns that off.
 */
class ValidationReportLoggingTest {

    private final Logger logger = Logger.getLogger(PraeceptaEngine.class.getName());
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };
    private Level level;

    @BeforeEach
    void capture() {
        // the build's test-logging.properties keeps the engine's warnings quiet: let them through to the handler
        level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void release() {
        logger.removeHandler(handler);
        logger.setLevel(level);
    }

    @Test
    void the_report_is_logged_once_as_a_warning_by_default() {
        var engine = overlapping().build();

        Assertions.assertThat(engine.validationReport().isEmpty()).isFalse();
        Assertions.assertThat(records).singleElement().satisfies(logged -> {
            Assertions.assertThat(logged.getLevel()).isEqualTo(Level.WARNING);
            Assertions.assertThat(logged.getParameters()).containsExactly(engine.validationReport().issues().size(),
                    engine.validationReport());
        });
    }

    @Test
    void the_report_is_not_logged_when_turned_off() {
        var engine = overlapping().logValidationReport(false).build();

        Assertions.assertThat(records).isEmpty();
        Assertions.assertThat(engine.validationReport()).isEqualTo(overlapping().build().validationReport());
    }

    @Test
    void turning_it_back_on_logs_again() {
        overlapping().logValidationReport(false).logValidationReport(true).build();

        Assertions.assertThat(records).hasSize(1);
    }

    @Test
    void a_table_without_issues_logs_nothing() {
        var engine = builder(rule(1, Map.of("country", "PT"), "A"), rule(2, Map.of("country", "ES"), "B")).build();

        Assertions.assertThat(engine.validationReport().isEmpty()).isTrue();
        Assertions.assertThat(records).isEmpty();
    }

    @Test
    void fail_on_still_applies_without_logging() {
        var builder = overlapping().logValidationReport(false).failOn(Severity.WARNING);

        Assertions.assertThatThrownBy(builder::build).isInstanceOf(InvalidDecisionTableException.class);
        Assertions.assertThat(records).isEmpty();
    }

    /** Under UNIQUE, rules 1 and 2 overlap on country PT. */
    private static PraeceptaEngine.Builder<Map<String, String>, String> overlapping() {
        return builder(rule(1, Map.of("country", "PT"), "A"), rule(2, Map.of(), "B"));
    }

    private static PraeceptaEngine.Builder<Map<String, String>, String> builder(Praeceptum... rules) {
        return PraeceptaEngine.<Map<String, String>, String>builder()
                .hitPolicy(HitPolicy.UNIQUE)
                .input("country", input -> input.get("country"))
                .output(output -> output.values().get("grade"))
                .rules(List.of(rules));
    }

    private static Praeceptum rule(int id, Map<String, String> cells, String grade) {
        return new Praeceptum(id, new PraeceptaInput(new HashMap<>(cells)), new PraeceptaOutput(Map.of("grade", grade)));
    }
}
